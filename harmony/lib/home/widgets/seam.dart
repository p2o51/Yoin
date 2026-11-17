import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/rendering.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/widgets.dart';

import '../../theme/tokens.dart';
import '../home_metrics.dart';

/// Home's status-bar seam (SeamDissolve.kt / SeamTide.kt, Home = FadeText):
/// the scroll followers ([SeamFlow]), the tide painter and the per-text fade.
/// Everything here repaints from listenables; nothing rebuilds per frame.
///
/// The chrome seam under fixed bars (Library, the detail pages, Settings) is
/// lib/ui/component/seam/chrome_seam.dart, built on these followers.

/// Band growth with scroll: 0 at rest, 1 after [HomeSeamMetrics.revealDistance].
double seamReveal(double scrolledPx) => (scrolledPx / HomeSeamMetrics.revealDistance).clamp(0.0, 1.0);

/// smoothstep((speed − 150) / 1300).
double seamStretch(double speedDpPerSec) {
  final x = ((speedDpPerSec - HomeSeamMetrics.stretchOnsetSpeed) / HomeSeamMetrics.stretchSpanSpeed).clamp(0.0, 1.0);
  return x * x * (3 - 2 * x);
}

/// Text alpha at [x] through its fade (0 at the seam): gone over the first 5 %, then 1 − (1 − t)².
double seamTextAlpha(double x) {
  if (x <= HomeSeamMetrics.textLead) return 0;
  final t = ((x - HomeSeamMetrics.textLead) / (1 - HomeSeamMetrics.textLead)).clamp(0.0, 1.0);
  return 1 - (1 - t) * (1 - t);
}

/// The fade band for a text of [fontSize] at [stretch] and [reveal].
double seamTextBand({required double stretch, required double reveal, required double fontSize}) =>
    math.max(
      ui.lerpDouble(HomeSeamMetrics.textBand, HomeSeamMetrics.textBandStretched, stretch)!,
      HomeSeamMetrics.textSizeFraction * fontSize,
    ) *
    reveal;

/// The front line's base: rest once revealed, hidden above the top edge before.
double tideBase({required double rest, required double reveal}) =>
    rest * reveal - HomeSeamMetrics.tideHidden * (1 - reveal);

/// One wave's y at [x].
double tideWaveY({required double x, required double base, required double amplitude, required double wavelength, required double phase}) {
  const tau = math.pi * 2;
  return base +
      amplitude *
          (math.sin((x / wavelength + phase) * tau) +
              HomeSeamMetrics.tideHarmonic * math.sin((x / (wavelength * 0.5) - phase * 1.7 + 0.3) * tau));
}

/// The seam's followers: scroll offset, travel, the afterglow lag and the
/// first-order speed follower (ω = √[MotionTokens.seamFlowSettleStiffness]).
/// At rest nothing moves; under reduced motion only the offset is tracked.
class SeamFlow extends ChangeNotifier {
  SeamFlow({required TickerProvider vsync}) {
    _ticker = vsync.createTicker(_tick);
  }

  late final Ticker _ticker;
  double _offset = 0;
  double _pendingDelta = 0;
  double _pendingTravel = 0;
  Duration? _last;

  /// Disables the followers (reduced motion).
  bool reduced = false;

  double scrolledPx = 0;
  double travelPx = 0;
  double lagPx = 0;
  double speedDp = 0;

  double get stretch => reduced ? 0 : seamStretch(speedDp);
  double get reveal => seamReveal(scrolledPx);

  /// A new scroll position took over (another view, a restored offset): its
  /// pixels become the reference without counting as travel or speed.
  void jumpTo(double pixels) {
    _offset = pixels;
    scrolledPx = math.max(0, pixels);
    notifyListeners();
  }

  /// A preview drives the followers itself (ScrollEdgePreview): [travel] more
  /// px of phase and the speed behind stretch and disorder.
  void drive({required double travel, required double speed}) {
    travelPx += travel;
    speedDp = speed;
    notifyListeners();
  }

  /// Feed with the scroll position's pixels.
  void onScroll(double pixels) {
    final delta = pixels - _offset;
    _offset = pixels;
    scrolledPx = math.max(0, pixels);
    if (delta != 0 && !reduced) {
      _pendingDelta += delta;
      _pendingTravel += delta.abs();
      travelPx += delta;
      if (!_ticker.isActive) {
        _last = null;
        _ticker.start();
      }
    }
    notifyListeners();
  }

  void _tick(Duration elapsed) {
    final seconds = _last == null ? 1 / 60 : ((elapsed - _last!).inMicroseconds / 1e6).clamp(0.001, 0.05);
    _last = elapsed;
    final omega = math.sqrt(MotionTokens.seamFlowSettleStiffness);
    final delta = _pendingDelta, travel = _pendingTravel;
    _pendingDelta = 0;
    _pendingTravel = 0;
    final decay = math.exp(-omega * seconds);
    var speed = speedDp + (travel / seconds - speedDp) * (1 - decay);
    if (travel == 0 && speed < HomeSeamMetrics.restSpeed) speed = 0;
    final cap = ui.lerpDouble(HomeSeamMetrics.band, HomeSeamMetrics.bandStretched, seamStretch(speed))! * HomeSeamMetrics.flowLag;
    var lag = ((lagPx - delta) * decay).clamp(-cap, cap);
    if (delta == 0 && lag.abs() < 0.05) lag = 0;
    lagPx = lag;
    speedDp = speed;
    if (lag == 0 && speed == 0) _ticker.stop();
    notifyListeners();
  }

  @override
  void dispose() {
    _ticker.dispose();
    super.dispose();
  }
}

/// Draws the tide: two waves of [color] from above the screen down to the
/// line (the back one first, half clear). Invisible at rest (reveal 0).
class TidePainter extends CustomPainter {
  TidePainter({required this.flow, required this.color, required this.statusBarHeight}) : super(repaint: flow);

  final SeamFlow flow;
  final Color color;
  final double statusBarHeight;
  final Path _path = Path();

  @override
  void paint(Canvas canvas, Size size) {
    final reveal = flow.reveal;
    if (reveal <= 0) return;
    final reduced = flow.reduced;
    final amplitude = HomeSeamMetrics.tideAmplitude + HomeSeamMetrics.tideAmplitudeStretched * (reduced ? 0 : flow.stretch);
    final phase = reduced ? 0.0 : (flow.travelPx + flow.lagPx) / HomeSeamMetrics.tidePhaseTravel;
    final base = tideBase(rest: statusBarHeight + HomeSeamMetrics.tideRest, reveal: reveal);
    _setWave(size.width, base + HomeSeamMetrics.tideBackDrop, amplitude * HomeSeamMetrics.tideBackAmplitude,
        HomeSeamMetrics.tideBackWavelength, 0.37 - phase * 0.7);
    canvas.drawPath(_path, Paint()..color = color.withValues(alpha: color.a * HomeSeamMetrics.tideBackAlpha));
    _setWave(size.width, base, amplitude, HomeSeamMetrics.tideFrontWavelength, phase);
    canvas.drawPath(_path, Paint()..color = color);
  }

  void _setWave(double width, double base, double amplitude, double wavelength, double phase) {
    const margin = HomeSeamMetrics.tideMargin;
    _path
      ..reset()
      ..moveTo(-margin, -margin);
    var x = -margin;
    while (true) {
      _path.lineTo(x, tideWaveY(x: x, base: base, amplitude: amplitude, wavelength: wavelength, phase: phase));
      if (x >= width + margin) break;
      x = math.min(x + HomeSeamMetrics.tideStep, width + margin);
    }
    _path
      ..lineTo(width + margin, -margin)
      ..close();
  }

  @override
  bool shouldRepaint(TidePainter old) => old.flow != flow || old.color != color || old.statusBarHeight != statusBarHeight;
}

/// Provides the seam to [SeamFade]s below: the followers and the line's y (global).
class SeamScope extends InheritedWidget {
  const SeamScope({super.key, required this.flow, required this.lineY, this.line, required super.child});

  final SeamFlow flow;
  final double lineY;

  /// The line's y read at paint time, when it moves without the flow (a
  /// chrome seam inside a moving layer); [lineY] otherwise.
  final double Function()? line;

  static SeamScope? maybeOf(BuildContext context) => context.dependOnInheritedWidgetOfExactType<SeamScope>();

  @override
  bool updateShouldNotify(SeamScope old) => old.flow != flow || old.lineY != lineY || old.line != line;
}

/// seamFade: text that rises into the seam line fades out over a band below
/// it. Only elements near the line get a mask layer; the rest paint plainly.
class SeamFade extends SingleChildRenderObjectWidget {
  const SeamFade({super.key, required this.fontSize, super.child});

  /// The text's font size (the band is at least 0.75 × it).
  final double fontSize;

  @override
  RenderSeamFade createRenderObject(BuildContext context) {
    final scope = SeamScope.maybeOf(context);
    return RenderSeamFade(flow: scope?.flow, lineY: scope?.lineY ?? 0, line: scope?.line, fontSize: fontSize);
  }

  @override
  void updateRenderObject(BuildContext context, RenderSeamFade renderObject) {
    final scope = SeamScope.maybeOf(context);
    renderObject
      ..flow = scope?.flow
      ..lineY = scope?.lineY ?? 0
      ..line = scope?.line
      ..fontSize = fontSize;
  }
}

class RenderSeamFade extends RenderProxyBox {
  RenderSeamFade({SeamFlow? flow, required double lineY, double Function()? line, required double fontSize})
      : _flow = flow,
        _lineY = lineY,
        _line = line,
        _fontSize = fontSize;

  SeamFlow? _flow;
  double _lineY;
  double Function()? _line;
  double _fontSize;
  bool _masking = false;

  set line(double Function()? value) {
    if (identical(value, _line)) return;
    _line = value;
    _onFlow();
  }

  double get _seamLine => _line?.call() ?? _lineY;

  set flow(SeamFlow? value) {
    if (identical(value, _flow)) return;
    if (attached) _flow?.removeListener(_onFlow);
    _flow = value;
    if (attached) _flow?.addListener(_onFlow);
    _onFlow();
  }

  set lineY(double value) {
    if (value == _lineY) return;
    _lineY = value;
    _onFlow();
  }

  set fontSize(double value) {
    if (value == _fontSize) return;
    _fontSize = value;
    markNeedsPaint();
  }

  @override
  void attach(PipelineOwner owner) {
    super.attach(owner);
    _flow?.addListener(_onFlow);
  }

  @override
  void detach() {
    _flow?.removeListener(_onFlow);
    super.detach();
  }

  double get _band {
    final flow = _flow;
    if (flow == null) return 0;
    return seamTextBand(stretch: flow.stretch, reveal: flow.reveal, fontSize: _fontSize);
  }

  /// Whether the seam currently touches this element.
  bool _needsMask() {
    if (!attached || !hasSize || _flow == null) return false;
    final band = _band;
    if (band <= 0) return false;
    final top = localToGlobal(Offset.zero).dy;
    return top < _seamLine + band;
  }

  void _onFlow() {
    final mask = _needsMask();
    if (mask != _masking) {
      _masking = mask;
      markNeedsCompositingBitsUpdate();
      markNeedsPaint();
    } else if (mask) {
      markNeedsPaint();
    }
  }

  @override
  bool get alwaysNeedsCompositing => child != null && _masking;

  @override
  void paint(PaintingContext context, Offset offset) {
    if (child == null) return;
    if (!_masking) {
      layer = null;
      super.paint(context, offset);
      return;
    }
    final top = localToGlobal(Offset.zero).dy;
    final band = _band;
    final lineLocal = _seamLine - top;
    if (band <= 0 || lineLocal + band <= 0) {
      layer = null;
      super.paint(context, offset);
      return;
    }
    if (lineLocal >= size.height) {
      layer = null;
      return; // Entirely above the line: gone.
    }
    const steps = 8;
    final colors = <Color>[];
    final stops = <double>[];
    final h = size.height <= 0 ? 1.0 : size.height;
    for (var i = 0; i <= steps; i++) {
      final x = i / steps;
      final y = lineLocal + band * x;
      stops.add((y / h).clamp(0.0, 1.0));
      colors.add(Color.fromRGBO(255, 255, 255, seamTextAlpha(x)));
    }
    final shader = ui.Gradient.linear(Offset.zero, Offset(0, h), colors, stops);
    final maskLayer = (layer is ShaderMaskLayer ? layer! as ShaderMaskLayer : ShaderMaskLayer())
      ..shader = shader
      ..maskRect = offset & size
      ..blendMode = BlendMode.dstIn;
    context.pushLayer(maskLayer, super.paint, offset);
    layer = maskLayer;
  }
}
