/// Graphics at a chrome seam and in the bottom field (Modifier.seamDissolve,
/// SeamDissolve.kt): in the Dots style they break into the curve C print as
/// they near the seam, in the Cookie style their top edge is cut into the
/// wave lip, under the tide they simply go under the water (the seam paints
/// it). Near the window's floating bar, inside a [SeamFieldViewport], they
/// break into the bottom field's halftone whatever the style. Outside both,
/// or at rest away from them, the child paints as is with no layer.
///
/// Text never becomes dots: keep text out of a [SeamDissolve] and give it a
/// [SeamFade] instead. A graphic that is part of a larger print (a row's
/// icon on its segment) sits under a [SeamPrint]: it takes the print's
/// geometry — one lattice, one lip — so the pieces break up as one.
///
/// Masked through a [ShaderMaskLayer] over a small mask image (the same path
/// as the focus mode's DotDissolve): an anti-aliased clip over scrolling
/// layers rasterised black wedges on HarmonyOS.
library;

import 'dart:async';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart' show Material, MaterialType;
import 'package:flutter/rendering.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/widgets.dart';

import '../../../theme/tokens.dart';
import 'chrome_seam.dart';
import 'seam_cookie.dart';
import 'seam_field.dart';
import 'seam_halftone.dart';
import 'seam_style.dart';

/// How far below the seam an element can still be touched by it (Dots: band + front + two pitches).
const double seamDissolveReach = SeamTokens.bandStretched + SeamTokens.frontStretched + SeamTokens.pitch * 2;

/// The mask a graphic takes at the seam, in its own px; null when it paints whole.
sealed class SeamMaskShape {
  const SeamMaskShape();
}

/// Wholly above the seam: gone.
final class SeamMaskGone extends SeamMaskShape {
  const SeamMaskGone();
}

/// The Dots print and / or the bottom field: these dots, and the solid band
/// between [SeamDotsMask.solidFrom] and [SeamDotsMask.solidTo].
final class SeamMaskDots extends SeamMaskShape {
  const SeamMaskDots(this.mask, this.origin, {this.tail});

  final SeamDotsMask mask;

  /// The element's top-left inside the print it belongs to.
  final Offset origin;

  /// The bottom field, when the dots are (partly) its: the 退色 ramp follows it.
  final SeamTail? tail;
}

/// The Cookie lip over the print [printSize]; the element sits at [origin] in
/// it. In the bottom field too, the lip cuts the field's print ([field]).
final class SeamMaskLip extends SeamMaskShape {
  const SeamMaskLip(this.lip, this.printSize, this.origin, {required this.round, this.field, this.tail});

  final SeamLip lip;
  final Size printSize;
  final Offset origin;
  final bool round;
  final SeamDotsMask? field;
  final SeamTail? tail;
}

/// The mask for an element at [origin] inside a print [printSize] whose top is
/// [offsetFromSeam] below the seam line (negative: above it), its left edge at
/// [printLeft] on screen; [tail] adds the bottom field (in print px), [lum]
/// the print's lightness at a lattice site for the field's yield. Pure: the
/// render object and the tests share it.
SeamMaskShape? seamMaskFor({
  required SeamTopStyle style,
  required double offsetFromSeam,
  required Size printSize,
  required Offset origin,
  required Size size,
  required double printLeft,
  required double reveal,
  required double stretch,
  required double disorder,
  required double lagPx,
  required double travelPx,
  required bool reduced,
  required bool round,
  required double seed,
  SeamTail? tail,
  double Function(int column, int row)? lum,
}) {
  if (size.isEmpty || printSize.isEmpty) return null;
  SeamDotsSeam? top;
  SeamLip? lip;
  // The element's own band of the print, measured from the seam.
  final ownTop = offsetFromSeam + origin.dy;
  if (style != SeamTopStyle.tide && ownTop <= seamDissolveReach) {
    if (ownTop + size.height <= 0) return const SeamMaskGone();
    switch (style) {
      case SeamTopStyle.dots:
        top = SeamDotsSeam.configure(offsetFromSeam: offsetFromSeam, reveal: reveal, stretch: stretch, lagPx: lagPx);
      case SeamTopStyle.cookie:
        lip = SeamLip.configure(
          seam: -offsetFromSeam,
          width: printSize.width,
          height: printSize.height,
          centreX: printLeft + printSize.width / 2,
          stretch: stretch,
          disorder: disorder,
          lagPx: lagPx,
          travelPx: travelPx,
          reveal: reveal,
          reduced: reduced,
        );
        if (lip != null && lip.seam + lip.reach <= origin.dy) lip = null;
      case SeamTopStyle.tide:
        break;
    }
  }
  if (lip != null) {
    final field = tail == null
        ? null
        : SeamDotsMask.build(
            width: printSize.width,
            height: printSize.height,
            tail: tail,
            seed: seed,
            disorder: disorder,
            lum: lum,
            within: origin & size,
          );
    return SeamMaskLip(lip, printSize, origin, round: round, field: field, tail: tail);
  }
  if (top == null && tail == null) return null;
  final mask = SeamDotsMask.build(
    width: printSize.width,
    height: printSize.height,
    top: top,
    tail: tail,
    seed: seed,
    disorder: disorder,
    lum: lum,
    within: origin & size,
  );
  if (mask.covers(origin.dy, origin.dy + size.height)) return null;
  return SeamMaskDots(mask, origin, tail: tail);
}

/// The 退色 ramp of [tail] over an element at [origin] in its print, [size]
/// big, painted at [offset]: the dots ease toward the page colour from the
/// approach's start (none) to the bar's top and on to the screen's edge.
/// SrcATop, so only the element's own pixels take it.
void paintSeamFieldFade(Canvas canvas, Offset offset, Size size, Offset origin, SeamTail tail) {
  final from = tail.y0 - origin.dy;
  final to = tail.fadeEnd - origin.dy;
  if (to <= from || from >= size.height || tail.reveal <= 0) return;
  final atBar = ((tail.bar.top - tail.y0) / (tail.fadeEnd - tail.y0)).clamp(0.0, 1.0);
  final page = tail.page;
  final rect = Rect.fromLTRB(
    offset.dx,
    offset.dy + math.max(0.0, from),
    offset.dx + size.width,
    offset.dy + size.height,
  );
  canvas.drawRect(
    rect,
    Paint()
      ..blendMode = BlendMode.srcATop
      ..shader = ui.Gradient.linear(
        Offset(0, offset.dy + from),
        Offset(0, offset.dy + to),
        [
          page.withValues(alpha: 0),
          page.withValues(alpha: page.a * SeamTokens.fieldFadeAtBar * tail.reveal),
          page.withValues(alpha: page.a * SeamTokens.fieldFadeAtEdge * tail.reveal),
        ],
        [0, atBar, 1],
      ),
  );
}

/// A print several [SeamDissolve]s belong to (a settings segment: its surface
/// and its icon): they all take this box's geometry.
class SeamPrint extends StatefulWidget {
  const SeamPrint({super.key, required this.child});

  final Widget child;

  @override
  State<SeamPrint> createState() => _SeamPrintState();
}

class _SeamPrintState extends State<SeamPrint> {
  final _handle = SeamPrintHandle();

  @override
  Widget build(BuildContext context) {
    // Inside another print (a cover on a tinted card): that one breaks up for both.
    if (context.getInheritedWidgetOfExactType<_SeamPrintScope>() != null) return widget.child;
    return _SeamPrintScope(
      handle: _handle,
      child: _SeamPrintBox(handle: _handle, child: widget.child),
    );
  }
}

/// A tinted surface (a card, a pill, a panel) that breaks up at the seams
/// while what sits on it does not: its fill is a [SeamDissolve] under the
/// [child], one [SeamPrint] for both, so graphics on it take its lattice and
/// its text passes whole (Android lifts the text out of the print; here it
/// was never in it). [clipBehavior] clips the child to [shape], as a Material would.
class SeamSurface extends StatelessWidget {
  const SeamSurface({
    super.key,
    required this.shape,
    required this.color,
    this.clipBehavior = Clip.none,
    required this.child,
  });

  final ShapeBorder shape;
  final Color color;
  final Clip clipBehavior;
  final Widget child;

  @override
  Widget build(BuildContext context) => SeamPrint(
    child: Stack(
      clipBehavior: Clip.none,
      children: [
        Positioned.fill(
          child: SeamDissolve(
            child: DecoratedBox(
              decoration: ShapeDecoration(color: color, shape: shape),
            ),
          ),
        ),
        if (clipBehavior == Clip.none)
          child
        else
          // The clip a tinted Material gave its content, without its fill.
          Material(type: MaterialType.transparency, shape: shape, clipBehavior: clipBehavior, child: child),
      ],
    ),
  );
}

/// A [SeamPrint]'s box and seed, shared with the graphics inside it.
class SeamPrintHandle {
  RenderBox? box;
  final double seed = (identityHashCode(Object()) % 1000) / 100;
}

class _SeamPrintScope extends InheritedWidget {
  const _SeamPrintScope({required this.handle, required super.child});

  final SeamPrintHandle handle;

  @override
  bool updateShouldNotify(_SeamPrintScope old) => old.handle != handle;
}

class _SeamPrintBox extends SingleChildRenderObjectWidget {
  const _SeamPrintBox({required this.handle, super.child});

  final SeamPrintHandle handle;

  @override
  _RenderSeamPrintBox createRenderObject(BuildContext context) => _RenderSeamPrintBox(handle);
}

class _RenderSeamPrintBox extends RenderProxyBox {
  _RenderSeamPrintBox(this.handle);

  final SeamPrintHandle handle;

  @override
  void attach(PipelineOwner owner) {
    super.attach(owner);
    handle.box = this;
  }

  @override
  void detach() {
    if (identical(handle.box, this)) handle.box = null;
    super.detach();
  }
}

/// Marks a graphic (a cover, an avatar, a thumbnail, a tinted surface) that
/// breaks up at a chrome seam and in the bottom field. [round] tells a round
/// item (an avatar): no shoulders on its lip, an elliptical guard.
class SeamDissolve extends SingleChildRenderObjectWidget {
  const SeamDissolve({super.key, this.round = false, super.child});

  final bool round;

  @override
  RenderSeamDissolve createRenderObject(BuildContext context) => RenderSeamDissolve(
    seam: ChromeSeamScope.maybeOf(context),
    field: SeamFieldScope.maybeOf(context),
    print: context.dependOnInheritedWidgetOfExactType<_SeamPrintScope>()?.handle,
    round: round,
    devicePixelRatio: MediaQuery.devicePixelRatioOf(context),
  );

  @override
  void updateRenderObject(BuildContext context, RenderSeamDissolve renderObject) {
    renderObject
      ..seam = ChromeSeamScope.maybeOf(context)
      ..field = SeamFieldScope.maybeOf(context)
      .._printHandle = context.dependOnInheritedWidgetOfExactType<_SeamPrintScope>()?.handle
      ..round = round
      ..devicePixelRatio = MediaQuery.devicePixelRatioOf(context);
  }
}

/// The print's lightness at its lattice sites, read off this element's own
/// pixels (the bottom field's yield). Valid for one element size and place
/// in its print.
final class _LightnessProbe {
  _LightnessProbe(this.size, this.origin, this.lattice, this.bytes, this.width, this.height, this.scale);

  final Size size;
  final Offset origin;
  final SeamLattice lattice;
  final ByteData bytes;
  final int width;
  final int height;
  final double scale;

  /// CIELAB L* at ([column], [row]); NaN off this element or where it is (mostly) clear.
  double at(int column, int row) {
    final site = seamSite(lattice, column, row);
    final x = site.x - origin.dx, y = site.y - origin.dy;
    if (x < 0 || y < 0 || x >= size.width || y >= size.height) return double.nan;
    final px = (x * scale).floor().clamp(0, width - 1);
    final py = (y * scale).floor().clamp(0, height - 1);
    final i = (py * width + px) * 4;
    if (bytes.getUint8(i + 3) < 128) return double.nan;
    return seamLightnessRgb(bytes.getUint8(i) / 255, bytes.getUint8(i + 1) / 255, bytes.getUint8(i + 2) / 255);
  }
}

class RenderSeamDissolve extends RenderProxyBox {
  RenderSeamDissolve({
    ChromeSeamController? seam,
    SeamFieldController? field,
    SeamPrintHandle? print,
    required bool round,
    required double devicePixelRatio,
  }) : _seam = seam,
       _field = field,
       _print = print,
       _round = round,
       _dpr = devicePixelRatio;

  ChromeSeamController? _seam;
  SeamFieldController? _field;
  SeamPrintHandle? _print;

  set _printHandle(SeamPrintHandle? value) {
    if (identical(value, _print)) return;
    _print = value;
    markNeedsPaint();
  }

  bool _round;
  double _dpr;

  /// Varies the dissolve front between items; stable for the object's life.
  late final double _ownSeed = (identityHashCode(this) % 1000) / 100;

  bool _masking = false;
  bool _nearTop = false;
  bool _nearField = false;
  SeamTopStyle? _paintedStyle;
  ui.Image? _image;
  final _layer = LayerHandle<ShaderMaskLayer>();

  // The bottom field's lightness probe: the child is painted into its own
  // layer while the element is beside the bar, and read back now and then.
  final _probeLayer = LayerHandle<OffsetLayer>();
  Offset _probeOffset = Offset.zero;
  _LightnessProbe? _probe;
  bool _probing = false;
  int _probedAt = -SeamTokens.yieldProbeIntervalMs;

  /// The paint a landed probe asked for: it must not ask for another.
  bool _probeLanded = false;

  /// A repaint inside the interval (a cover landing) looks again once the interval is up.
  Timer? _probeAgain;

  set seam(ChromeSeamController? value) {
    if (identical(value, _seam)) return;
    if (attached) _seam?.removeListener(_onSeam);
    _seam = value;
    if (attached) _seam?.addListener(_onSeam);
    _onSeam();
  }

  set field(SeamFieldController? value) {
    if (identical(value, _field)) return;
    if (attached) _field?.removeListener(_onSeam);
    _field = value;
    if (attached) _field?.addListener(_onSeam);
    _onSeam();
  }

  set round(bool value) {
    if (value == _round) return;
    _round = value;
    markNeedsPaint();
  }

  set devicePixelRatio(double value) {
    if (value == _dpr) return;
    _dpr = value;
    markNeedsPaint();
  }

  @override
  void attach(PipelineOwner owner) {
    super.attach(owner);
    _seam?.addListener(_onSeam);
    _field?.addListener(_onSeam);
    // Built already at a seam (a row scrolled in under it, or next to the bar): checked once it is laid out.
    if (_seam != null || _field != null) {
      SchedulerBinding.instance.addPostFrameCallback((_) {
        if (attached) _onSeam();
      });
    }
  }

  @override
  void detach() {
    _seam?.removeListener(_onSeam);
    _field?.removeListener(_onSeam);
    _probeAgain?.cancel();
    _probeAgain = null;
    super.detach();
  }

  @override
  void dispose() {
    _layer.layer = null;
    _probeLayer.layer = null;
    _image?.dispose();
    _image = null;
    super.dispose();
  }

  /// The box whose geometry the print takes: the [SeamPrint] around this, else this.
  RenderBox get _printBox {
    final box = _print?.box;
    return box != null && box.attached && box.hasSize ? box : this;
  }

  /// Whether the chrome seam may touch this element now (the style breaks graphics up and it is near).
  bool _touchesTop() {
    final seam = _seam;
    if (seam == null || !attached || !hasSize || !seam.hasLine || seam.style == SeamTopStyle.tide) return false;
    final top = localToGlobal(Offset.zero).dy - seam.lineY;
    return top < seamDissolveReach && top + size.height > -seamDissolveReach;
  }

  /// Whether the bottom field may touch this element now (its bottom is near the window's).
  bool _touchesField() {
    final field = _field;
    if (field == null || !attached || !hasSize) return false;
    return field.watches(localToGlobal(Offset(0, size.height)).dy);
  }

  void _onSeam() {
    final nearTop = _touchesTop();
    final nearField = _touchesField();
    final near = nearTop || nearField;
    final style = _seam?.style;
    final wasMasking = _masking;
    _nearTop = nearTop;
    _nearField = nearField;
    if (near != wasMasking) {
      _masking = near;
      markNeedsCompositingBitsUpdate();
      markNeedsPaint();
    } else if (near || style != _paintedStyle) {
      markNeedsPaint();
    }
  }

  @override
  bool get alwaysNeedsCompositing => child != null && _masking;

  SeamMaskShape? _shape() {
    if (!_masking) return null;
    final seam = _seam;
    final field = _field;
    final printBox = _printBox;
    final printTopLeft = printBox.localToGlobal(Offset.zero);
    final origin = identical(printBox, this) ? Offset.zero : localToGlobal(Offset.zero) - printTopLeft;
    SeamTail? tail;
    if (_nearField && field != null && field.active) {
      final bar = field.bar!;
      final page = field.background!;
      tail = SeamTail.configure(
        printTopLeft: printTopLeft,
        printSize: printBox.size,
        bar: bar.bounds,
        barRadius: bar.cornerRadius,
        barColor: bar.containerColor,
        rootHeight: bar.rootHeight,
        reveal: bar.reveal,
        remaining: field.remaining(),
        stretch: field.stretch,
        lagPx: field.lagPx,
        pageAtBar: page.at(bar.bounds.top / bar.rootHeight),
        pageAtEdge: page.at(1),
      );
    }
    final top = _nearTop && seam != null;
    final probe = _probe;
    return seamMaskFor(
      style: top ? seam.style : SeamTopStyle.tide,
      offsetFromSeam: top ? printTopLeft.dy - seam.lineY : double.infinity,
      printSize: printBox.size,
      origin: origin,
      size: size,
      printLeft: printTopLeft.dx,
      reveal: seam?.reveal ?? 0,
      stretch: seam?.stretch ?? field?.stretch ?? 0,
      disorder: seam?.disorder ?? field?.disorder ?? 0,
      lagPx: seam?.lagPx ?? field?.lagPx ?? 0,
      travelPx: seam?.travelPx ?? 0,
      reduced: seam?.reduced ?? field?.reduced ?? false,
      round: _round,
      seed: _print?.seed ?? _ownSeed,
      tail: tail,
      lum: probe != null && probe.size == size && probe.origin == origin ? probe.at : null,
    );
  }

  /// The field's tail, when [shape] has one.
  static SeamTail? _tailOf(SeamMaskShape shape) => switch (shape) {
    SeamMaskDots(:final tail) => tail,
    SeamMaskLip(:final tail) => tail,
    SeamMaskGone() => null,
  };

  /// Whether this element has rows within the lightness yield's reach of the bar.
  bool _besideBar(SeamTail tail, Offset origin) {
    final reach = tail.yieldReach + tail.yieldInset;
    return tail.bar.top - reach < origin.dy + size.height && tail.bar.bottom + reach > origin.dy;
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    final child = this.child;
    final probeLanded = _probeLanded;
    _probeLanded = false;
    if (child == null) return;
    _paintedStyle = _seam?.style;
    final shape = _shape();
    if (shape == null) {
      _layer.layer = null;
      _probeLayer.layer = null;
      context.paintChild(child, offset);
      return;
    }
    if (shape is SeamMaskGone) {
      _layer.layer = null;
      _probeLayer.layer = null;
      return;
    }
    final (image, cropTop, veil) = _mask(shape);
    final previous = _image;
    _image = image;
    final scale = 1 / _dpr;
    final matrix = Matrix4.translationValues(0, cropTop, 0)..scaleByDouble(scale, scale, 1, 1);
    final shader = ImageShader(image, TileMode.clamp, TileMode.clamp, matrix.storage);
    previous?.dispose();
    final tail = _tailOf(shape);
    final origin = switch (shape) {
      SeamMaskDots(:final origin) => origin,
      SeamMaskLip(:final origin) => origin,
      SeamMaskGone() => Offset.zero,
    };
    final probe = tail != null && tail.reveal > 0 && _besideBar(tail, origin);
    _layer.layer = (_layer.layer ?? ShaderMaskLayer())
      ..shader = shader
      ..maskRect = offset & size
      ..blendMode = BlendMode.dstIn;
    context.pushLayer(_layer.layer!, (context, offset) {
      if (probe) {
        // The child in a layer of its own, so its pixels can be read back.
        _probeOffset = offset;
        context.pushLayer(_probeLayer.layer ??= OffsetLayer(), (c, o) => c.paintChild(child, o), offset);
      } else {
        _probeLayer.layer = null;
        context.paintChild(child, offset);
      }
      if (veil != null) _paintVeil(context.canvas, offset, veil);
      if (tail != null) paintSeamFieldFade(context.canvas, offset, size, origin, tail);
    }, offset);
    if (probe && !probeLanded) _scheduleProbe(origin);
  }

  /// Reads this element's pixels at its print's lattice sites, after the frame,
  /// at most every [SeamTokens.yieldProbeIntervalMs] (sooner when it changed size).
  void _scheduleProbe(Offset origin) {
    if (_probing) return;
    final now = DateTime.now().millisecondsSinceEpoch;
    final current = _probe;
    final stale = current == null || current.size != size || current.origin != origin;
    final wait = SeamTokens.yieldProbeIntervalMs - (now - _probedAt);
    if (!stale && wait > 0) {
      // Repainted within the interval (its content may have changed): look again once it is up.
      _probeAgain ??= Timer(Duration(milliseconds: wait), () {
        _probeAgain = null;
        if (attached) markNeedsPaint();
      });
      return;
    }
    _probing = true;
    _probedAt = now;
    final probeSize = size;
    final lattice = SeamLattice.of(_printBox.size.width);
    SchedulerBinding.instance.addPostFrameCallback((_) async {
      try {
        final layer = _probeLayer.layer;
        if (layer == null || !attached || size != probeSize) return;
        final ratio = SeamTokens.yieldProbePixelsPerCell / lattice.cellWidth;
        final image = await layer.toImage(_probeOffset & probeSize, pixelRatio: ratio);
        final bytes = await image.toByteData(format: ui.ImageByteFormat.rawStraightRgba);
        final width = image.width, height = image.height;
        image.dispose();
        if (bytes == null || !attached || width == 0 || height == 0) return;
        _probe = _LightnessProbe(probeSize, origin, lattice, bytes, width, height, ratio);
        _probeLanded = true;
        markNeedsPaint();
      } catch (_) {
        // No read-back here (a test harness): the field simply doesn't yield.
      } finally {
        _probing = false;
      }
    });
  }

  /// Renders [shape] as an alpha image over this element (device px) from
  /// [cropTop] down — the rows above it repeat its first, solid row — and
  /// the lip's veil to paint over the kept tips, if any.
  (ui.Image, double, SeamMaskLip?) _mask(SeamMaskShape shape) {
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder)..scale(_dpr);
    final paint = Paint()
      ..isAntiAlias = true
      ..color = const Color(0xFF000000);
    var height = size.height;
    var cropTop = 0.0;
    SeamMaskLip? veil;
    void drawDots(SeamDotsMask mask, Offset origin, double bottom) {
      final solidTo = math.min(mask.solidTo, bottom);
      if (solidTo > mask.solidFrom) {
        canvas.drawRect(Rect.fromLTRB(-1, mask.solidFrom, origin.dx + size.width + 1, solidTo), paint);
      }
      for (final dot in mask.dots) {
        canvas.drawCircle(Offset(dot.x, dot.y), dot.radius, paint);
      }
    }

    switch (shape) {
      case SeamMaskDots(:final mask, :final origin):
        if (mask.solidTo.isInfinite) {
          // Top seam only: down to the first solid row, whole below (the image's last row clamps on).
          height = (mask.solidFrom - origin.dy + 1).clamp(1.0, size.height);
        } else if (mask.solidFrom <= origin.dy) {
          // Field only: from the last solid row down (the image's first row clamps on above it).
          cropTop = (mask.solidTo - origin.dy - 1).clamp(0.0, math.max(0.0, size.height - 1));
          height = size.height - cropTop;
        }
        canvas.translate(-origin.dx, -origin.dy - cropTop);
        drawDots(mask, origin, origin.dy + cropTop + height + 1);
      case SeamMaskLip(:final lip, :final printSize, :final origin, :final round, :final field):
        if (field == null) height = (lip.seam + lip.reach + 1 - origin.dy).clamp(1.0, size.height);
        canvas.translate(-origin.dx, -origin.dy);
        final keep = lip.keep(printSize.width, printSize.height, round: round, bottom: origin.dy + height + 1);
        if (field == null) {
          canvas.drawPath(keep, paint);
        } else {
          // The lip cuts the field's print, when an item is in both at once.
          canvas
            ..save()
            ..clipPath(keep);
          drawDots(field, origin, origin.dy + height + 1);
          canvas.restore();
        }
        if (lip.veil > 0 && _seam?.pageAtLine != null) veil = shape;
      case SeamMaskGone():
        height = 1;
    }
    final picture = recorder.endRecording();
    final image = picture.toImageSync(math.max(1, (size.width * _dpr).ceil()), math.max(1, (height * _dpr).ceil()));
    picture.dispose();
    return (image, cropTop, veil);
  }

  /// The tips ease toward the page colour (SrcAtop: onto the item's own pixels only).
  void _paintVeil(Canvas canvas, Offset offset, SeamMaskLip shape) {
    final page = _seam?.pageAtLine;
    if (page == null) return;
    final lip = shape.lip;
    const stops = 10;
    final top = lip.seam - shape.origin.dy;
    final rect = Rect.fromLTWH(offset.dx, offset.dy + top, size.width, math.max(1, lip.veilDepth));
    final colors = <Color>[];
    final positions = <double>[];
    for (var i = 0; i < stops; i++) {
      final u = i / (stops - 1);
      positions.add(u);
      colors.add(page.withValues(alpha: page.a * lip.veilAt(u * lip.veilDepth)));
    }
    canvas.drawRect(
      rect,
      Paint()
        ..blendMode = BlendMode.srcATop
        ..shader = ui.Gradient.linear(rect.topCenter, rect.bottomCenter, colors, positions),
    );
  }
}
