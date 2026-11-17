/// The seam where scrolling content meets fixed chrome above it
/// (seamDissolveViewport(top = SeamTop.Chrome), SeamDissolve.kt): Library's
/// filters, a detail page's docked band, Settings' bar. Its look is the
/// user's choice ([SeamTopStyle], Settings › Appearance › Scroll edge):
///
///  - Tide line (the default): two waves of the page colour come down from
///    the viewport's top edge as the page scrolls, the back one half clear;
///    content sinks under the water line and text fades below it.
///  - Dots: graphics break into curve C dots over the band ([SeamDissolve]).
///  - Cookie wave: each graphic's top edge becomes one wave lip ([SeamDissolve]).
///
/// Text fades over its last max(10dp, 0.75 × its size) in every style
/// ([SeamFade]). Mark graphics with [SeamDissolve] and text with [SeamFade];
/// both are no-ops outside a seam, so shared components carry them anywhere.
/// With [ChromeSeam.field] the same viewport also takes the bottom halftone
/// field around the window's bar ([SeamFieldViewport]), in every style.
library;

import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../home/home_metrics.dart' show HomeSeamMetrics;
import '../../../home/widgets/seam.dart';
import 'seam_field.dart';
import 'seam_halftone.dart';
import 'seam_style.dart';

/// The page colour behind a seam, as a vertical gradient over the window
/// (one colour = flat).
@immutable
final class SeamBackground {
  /// [colors] top to bottom; at least one.
  const SeamBackground(this.colors);

  final List<Color> colors;

  /// The colour [fraction] of the way down the window.
  Color at(double fraction) {
    if (colors.length == 1) return colors.first;
    final position = fraction.clamp(0.0, 1.0) * (colors.length - 1);
    final index = math.min(position.floor(), colors.length - 2);
    return Color.lerp(colors[index], colors[index + 1], position - index)!;
  }

  @override
  bool operator ==(Object other) => other is SeamBackground && listEquals(other.colors, colors);

  @override
  int get hashCode => Object.hashAll(colors);
}

/// The front line's rest under fixed chrome: a crest below the edge, so no
/// crest is cut straight by it (SeamViewportNode's restPx with inset 0).
double chromeTideRest(double amplitude) => HomeSeamMetrics.tideRest + amplitude * (1 + HomeSeamMetrics.tideHarmonic);

/// How far the back wave reaches below the front line's base (tideDepthPx).
double chromeTideDepth(double amplitude) =>
    HomeSeamMetrics.tideBackDrop + amplitude * HomeSeamMetrics.tideBackAmplitude * (1 + HomeSeamMetrics.tideHarmonic);

/// The front line's base at [reveal]: its rest once revealed, high enough above the edge before
/// that not even the back wave reaches in (reveal 0 = nothing covered; the first pixel adds none).
double chromeTideBase(double reveal, double amplitude) {
  final hidden = math.max(HomeSeamMetrics.tideHidden, chromeTideDepth(amplitude) + 1);
  return chromeTideRest(amplitude) * reveal - hidden * (1 - reveal);
}

/// Where text starts to fade under the tide, below the viewport's top (tideTextSeamPx): never above it.
double chromeTideTextSeam(double reveal) => math.max(0, chromeTideBase(reveal, HomeSeamMetrics.tideAmplitude));

/// One chrome seam's live state, read at paint time by its painters and by
/// every [SeamDissolve] / [SeamFade] below it: the followers, the style, the
/// page colour and where the seam line is on screen.
class ChromeSeamController extends ChangeNotifier {
  ChromeSeamController(this._flow) {
    _flow.addListener(notifyListeners);
  }

  SeamFlow _flow;
  SeamFlow get flow => _flow;
  set flow(SeamFlow value) {
    if (identical(value, _flow)) return;
    _flow.removeListener(notifyListeners);
    _flow = value;
    _flow.addListener(notifyListeners);
    notifyListeners();
  }

  SeamTopStyle _style = SeamTopStyle.defaultStyle;
  SeamTopStyle get style => _style;
  set style(SeamTopStyle value) {
    if (value == _style) return;
    _style = value;
    notifyListeners();
  }

  SeamBackground? _background;
  SeamBackground? get background => _background;
  set background(SeamBackground? value) {
    if (value == _background) return;
    _background = value;
    notifyListeners();
  }

  /// Reduced motion: no afterglow, stretch, disorder or roll.
  bool reduced = false;

  /// Set while [ChromeSeam.jump] moves the scrollable: no scroll, so no flow.
  bool _jumping = false;

  /// A preview: the seam is fully in, whatever the scroll.
  bool fullReveal = false;

  /// The window's height, for the page colour's place in its gradient.
  double windowHeight = 1;

  RenderBox? _viewport;

  /// The seam line's y on screen: the viewport's top edge.
  double get lineY {
    final box = _viewport;
    if (box == null || !box.attached || !box.hasSize) return 0;
    return box.localToGlobal(Offset.zero).dy;
  }

  /// Whether the viewport is laid out (the line is known).
  bool get hasLine {
    final box = _viewport;
    return box != null && box.attached && box.hasSize;
  }

  /// Where text starts to fade, on screen: below the water line under the tide, at the seam otherwise.
  double get textLine => lineY + (_style == SeamTopStyle.tide ? chromeTideTextSeam(reveal) : 0);

  double get reveal => fullReveal ? 1 : flow.reveal;
  double get stretch => reduced ? 0 : flow.stretch;
  double get disorder => reduced ? 0 : seamDisorder(flow.speedDp);
  double get lagPx => reduced ? 0 : flow.lagPx;
  double get travelPx => reduced ? 0 : flow.travelPx;

  /// The page colour at the seam line (the tide's water, the lip's veil).
  Color? get pageAtLine => _background?.at(lineY / math.max(1, windowHeight));

  @override
  void dispose() {
    _flow.removeListener(notifyListeners);
    super.dispose();
  }
}

/// Provides a [ChromeSeamController] to the [SeamDissolve]s below.
class ChromeSeamScope extends InheritedWidget {
  const ChromeSeamScope({super.key, required this.controller, required this.style, required super.child});

  final ChromeSeamController controller;

  /// The style, so dependents rebuild when it changes.
  final SeamTopStyle style;

  static ChromeSeamController? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<ChromeSeamScope>()?.controller;

  @override
  bool updateShouldNotify(ChromeSeamScope old) => old.controller != controller || old.style != style;
}

/// Marks a scroll container's top edge as a chrome seam (put it right around
/// the scrollable, below the fixed chrome). It follows the nearest vertical
/// scrollable under it through scroll notifications — or [controller] when
/// given — and draws the tide over it in [background]'s colour when that is
/// the style.
///
/// [style] pins one look instead of the user's choice, and [flow] hands in
/// followers driven from outside, with [fullReveal]: only for previews of the
/// styles themselves (Settings › Scroll edge, the landing).
class ChromeSeam extends ConsumerStatefulWidget {
  const ChromeSeam({
    super.key,
    required this.background,
    required this.child,
    this.controller,
    this.style,
    this.flow,
    this.fullReveal = false,
    this.field = false,
  });

  final SeamBackground background;
  final Widget child;
  final ScrollController? controller;
  final SeamTopStyle? style;
  final SeamFlow? flow;
  final bool fullReveal;

  /// The viewport also takes the bottom field around the window's bar
  /// (Android passes remainingPx: Home, Library, the detail pages).
  final bool field;

  /// Moves the scrollable under the nearest chrome seam without it counting
  /// as a scroll (LibraryFastScroller.kt: "a jump is no scroll, so the seam
  /// stays still"): [move] jumps the controller, the seam takes the new
  /// offset as its reference.
  static void jump(BuildContext context, VoidCallback move) {
    final seam = context.getInheritedWidgetOfExactType<ChromeSeamScope>()?.controller;
    if (seam == null) return move();
    seam._jumping = true;
    try {
      move();
    } finally {
      seam._jumping = false;
    }
  }

  @override
  ConsumerState<ChromeSeam> createState() => _ChromeSeamState();
}

class _ChromeSeamState extends ConsumerState<ChromeSeam> with SingleTickerProviderStateMixin {
  late final SeamFlow _ownFlow = SeamFlow(vsync: this);
  late final ChromeSeamController _seam = ChromeSeamController(widget.flow ?? _ownFlow);
  BuildContext? _source;

  /// How far the followed scrollable can still scroll toward its end (the field's approach shrinks over the last 40).
  double _remaining = double.infinity;

  double _remainingNow() {
    final controller = widget.controller;
    if (controller != null && controller.hasClients) {
      final position = controller.positions.last;
      if (position.hasContentDimensions) return math.max(0, position.maxScrollExtent - position.pixels);
    }
    return _remaining;
  }

  @override
  void initState() {
    super.initState();
    widget.controller?.addListener(_onController);
  }

  @override
  void didUpdateWidget(ChromeSeam old) {
    super.didUpdateWidget(old);
    if (old.controller != widget.controller) {
      old.controller?.removeListener(_onController);
      widget.controller?.addListener(_onController);
    }
    _seam.flow = widget.flow ?? _ownFlow;
  }

  @override
  void dispose() {
    widget.controller?.removeListener(_onController);
    _seam.dispose();
    _ownFlow.dispose();
    super.dispose();
  }

  bool get _follows => widget.flow == null;

  // A push transition can briefly attach the page twice: read the last.
  void _onController() {
    final controller = widget.controller!;
    if (!_follows || !controller.hasClients) return;
    final pixels = controller.positions.last.pixels;
    _seam._jumping ? _ownFlow.jumpTo(pixels) : _ownFlow.onScroll(pixels);
  }

  bool _onNotification(Notification notification) {
    if (!_follows || widget.controller != null) return false;
    final (metrics, context, depth) = switch (notification) {
      ScrollNotification(:final metrics, :final context, :final depth) => (metrics, context, depth),
      ScrollMetricsNotification(:final metrics, :final context, :final depth) => (metrics, context, depth),
      _ => (null, null, -1),
    };
    if (metrics == null || depth != 0 || metrics.axis != Axis.vertical) return false;
    if (metrics.hasContentDimensions) _remaining = math.max(0, metrics.maxScrollExtent - metrics.pixels);
    // Another scrollable took over (a Library tab, a restored offset): its
    // offset is the new reference, not a scroll.
    if (!identical(context, _source)) {
      _source = context;
      _ownFlow.jumpTo(metrics.pixels);
    } else if (notification is ScrollMetricsNotification || _seam._jumping) {
      _ownFlow.jumpTo(metrics.pixels);
    } else {
      _ownFlow.onScroll(metrics.pixels);
    }
    return false;
  }

  @override
  Widget build(BuildContext context) {
    final SeamTopStyle watched = ref.watch(seamTopStyleProvider);
    final style = widget.style ?? watched;
    final reduced = MediaQuery.disableAnimationsOf(context);
    _ownFlow.reduced = reduced;
    _seam
      ..style = style
      ..background = widget.background
      ..reduced = reduced
      ..fullReveal = widget.fullReveal
      ..windowHeight = MediaQuery.sizeOf(context).height;
    return NotificationListener<Notification>(
      onNotification: _onNotification,
      child: _SeamViewport(
        controller: _seam,
        child: ChromeSeamScope(
          controller: _seam,
          style: style,
          child: SeamScope(
            flow: _seam.flow,
            lineY: 0,
            line: () => _seam.textLine,
            child: CustomPaint(
              foregroundPainter: ChromeTidePainter(_seam),
              child: widget.field
                  ? SeamFieldViewport(
                      flow: _seam.flow,
                      background: widget.background,
                      remaining: _remainingNow,
                      child: widget.child,
                    )
                  : widget.child,
            ),
          ),
        ),
      ),
    );
  }
}

/// The viewport's box: where the seam line is.
class _SeamViewport extends SingleChildRenderObjectWidget {
  const _SeamViewport({required this.controller, super.child});

  final ChromeSeamController controller;

  @override
  _RenderSeamViewport createRenderObject(BuildContext context) => _RenderSeamViewport(controller);

  @override
  void updateRenderObject(BuildContext context, _RenderSeamViewport renderObject) =>
      renderObject.controller = controller;
}

class _RenderSeamViewport extends RenderProxyBox {
  _RenderSeamViewport(this._controller);

  ChromeSeamController _controller;
  set controller(ChromeSeamController value) {
    if (identical(value, _controller)) return;
    if (identical(_controller._viewport, this)) _controller._viewport = null;
    _controller = value;
    if (attached) _controller._viewport = this;
  }

  @override
  void attach(PipelineOwner owner) {
    super.attach(owner);
    _controller._viewport = this;
  }

  @override
  void detach() {
    if (identical(_controller._viewport, this)) _controller._viewport = null;
    super.detach();
  }
}

/// The chrome tide over the scrolling content: two waves of the page colour
/// from above the viewport down to the line, the back one at
/// [HomeSeamMetrics.tideBackAlpha], kept inside the viewport. Nothing at rest,
/// and nothing in the Dots and Cookie styles.
///
/// Android cuts the content with the waves as a mask so whatever is behind
/// shows through; here the waves are painted in the page colour the seam
/// knows ([SeamBackground], the page's own gradient at the line) — the same
/// pixels, without a layer that would have to hold the scrolling list.
class ChromeTidePainter extends CustomPainter {
  ChromeTidePainter(this.seam) : super(repaint: seam);

  final ChromeSeamController seam;
  final Path _wave = Path();

  @override
  void paint(Canvas canvas, Size size) {
    if (seam.style != SeamTopStyle.tide) return;
    final color = seam.pageAtLine;
    final reveal = seam.reveal;
    if (color == null || reveal <= 0) return;
    final amplitude = HomeSeamMetrics.tideAmplitude + HomeSeamMetrics.tideAmplitudeStretched * seam.stretch;
    final base = chromeTideBase(reveal, amplitude);
    if (base + chromeTideDepth(amplitude) <= 0) return;
    final phase = seam.reduced ? 0.0 : (seam.flow.travelPx + seam.flow.lagPx) / HomeSeamMetrics.tidePhaseTravel;
    canvas.save();
    canvas.clipRect(Offset.zero & size);
    _setWave(
      size.width,
      base + HomeSeamMetrics.tideBackDrop,
      amplitude * HomeSeamMetrics.tideBackAmplitude,
      HomeSeamMetrics.tideBackWavelength,
      0.37 - phase * 0.7,
    );
    canvas.drawPath(_wave, Paint()..color = color.withValues(alpha: color.a * HomeSeamMetrics.tideBackAlpha));
    _setWave(size.width, base, amplitude, HomeSeamMetrics.tideFrontWavelength, phase);
    canvas.drawPath(_wave, Paint()..color = color);
    canvas.restore();
  }

  /// Closes [_wave] from above the top edge down to the wave line.
  void _setWave(double width, double base, double amplitude, double wavelength, double phase) {
    const margin = HomeSeamMetrics.tideMargin;
    _wave
      ..reset()
      ..moveTo(-margin, -margin);
    var x = -margin;
    while (true) {
      _wave.lineTo(x, tideWaveY(x: x, base: base, amplitude: amplitude, wavelength: wavelength, phase: phase));
      if (x >= width + margin) break;
      x = math.min(x + HomeSeamMetrics.tideStep, width + margin);
    }
    _wave
      ..lineTo(width + margin, -margin)
      ..close();
  }

  @override
  bool shouldRepaint(ChromeTidePainter old) => old.seam != seam;
}
