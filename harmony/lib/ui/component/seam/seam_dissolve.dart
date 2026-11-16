/// Graphics at a chrome seam (Modifier.seamDissolve, SeamDissolve.kt): in the
/// Dots style they break into the curve C print as they near the seam, in
/// the Cookie style their top edge is cut into the wave lip, under the tide
/// they simply go under the water (the seam paints it). Outside a
/// [ChromeSeam], or at rest away from it, the child paints as is with no
/// layer.
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

import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/rendering.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/widgets.dart';

import '../../../theme/tokens.dart';
import 'chrome_seam.dart';
import 'seam_cookie.dart';
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

/// The Dots print: these dots, and everything below [solidFrom].
final class SeamMaskDots extends SeamMaskShape {
  const SeamMaskDots(this.mask, this.origin);

  final SeamDotsMask mask;

  /// The element's top-left inside the print it belongs to.
  final Offset origin;
}

/// The Cookie lip over the print [printSize]; the element sits at [origin] in it.
final class SeamMaskLip extends SeamMaskShape {
  const SeamMaskLip(this.lip, this.printSize, this.origin, {required this.round});

  final SeamLip lip;
  final Size printSize;
  final Offset origin;
  final bool round;
}

/// The mask for an element at [origin] inside a print [printSize] whose top is
/// [offsetFromSeam] below the seam line (negative: above it), its left edge at
/// [printLeft] on screen. Pure: the render object and the tests share it.
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
}) {
  if (style == SeamTopStyle.tide || size.isEmpty || printSize.isEmpty) return null;
  // The element's own band of the print, measured from the seam.
  final ownTop = offsetFromSeam + origin.dy;
  if (ownTop > seamDissolveReach) return null;
  if (ownTop + size.height <= 0) return const SeamMaskGone();
  switch (style) {
    case SeamTopStyle.dots:
      final top = SeamDotsSeam.configure(
        offsetFromSeam: offsetFromSeam,
        reveal: reveal,
        stretch: stretch,
        lagPx: lagPx,
      );
      if (top == null) return null;
      final mask = SeamDotsMask.build(
        width: printSize.width,
        height: printSize.height,
        top: top,
        seed: seed,
        disorder: disorder,
      );
      if (mask.solidFrom <= origin.dy) return null;
      return SeamMaskDots(mask, origin);
    case SeamTopStyle.cookie:
      final lip = SeamLip.configure(
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
      if (lip == null || lip.seam + lip.reach <= origin.dy) return null;
      return SeamMaskLip(lip, printSize, origin, round: round);
    case SeamTopStyle.tide:
      return null;
  }
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
  Widget build(BuildContext context) => _SeamPrintScope(
    handle: _handle,
    child: _SeamPrintBox(handle: _handle, child: widget.child),
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
/// breaks up at a chrome seam. [round] tells a round item (an avatar): no
/// shoulders on its lip, an elliptical guard.
class SeamDissolve extends SingleChildRenderObjectWidget {
  const SeamDissolve({super.key, this.round = false, super.child});

  final bool round;

  @override
  RenderSeamDissolve createRenderObject(BuildContext context) => RenderSeamDissolve(
    seam: ChromeSeamScope.maybeOf(context),
    print: context.dependOnInheritedWidgetOfExactType<_SeamPrintScope>()?.handle,
    round: round,
    devicePixelRatio: MediaQuery.devicePixelRatioOf(context),
  );

  @override
  void updateRenderObject(BuildContext context, RenderSeamDissolve renderObject) {
    renderObject
      ..seam = ChromeSeamScope.maybeOf(context)
      .._printHandle = context.dependOnInheritedWidgetOfExactType<_SeamPrintScope>()?.handle
      ..round = round
      ..devicePixelRatio = MediaQuery.devicePixelRatioOf(context);
  }
}

class RenderSeamDissolve extends RenderProxyBox {
  RenderSeamDissolve({
    ChromeSeamController? seam,
    SeamPrintHandle? print,
    required bool round,
    required double devicePixelRatio,
  }) : _seam = seam,
       _print = print,
       _round = round,
       _dpr = devicePixelRatio;

  ChromeSeamController? _seam;
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
  SeamTopStyle? _paintedStyle;
  ui.Image? _image;
  final _layer = LayerHandle<ShaderMaskLayer>();

  set seam(ChromeSeamController? value) {
    if (identical(value, _seam)) return;
    if (attached) _seam?.removeListener(_onSeam);
    _seam = value;
    if (attached) _seam?.addListener(_onSeam);
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
    // Built already at the seam (a row scrolled in under it): checked once it is laid out.
    if (_seam != null) {
      SchedulerBinding.instance.addPostFrameCallback((_) {
        if (attached) _onSeam();
      });
    }
  }

  @override
  void detach() {
    _seam?.removeListener(_onSeam);
    super.detach();
  }

  @override
  void dispose() {
    _layer.layer = null;
    _image?.dispose();
    _image = null;
    super.dispose();
  }

  /// The box whose geometry the print takes: the [SeamPrint] around this, else this.
  RenderBox get _printBox {
    final box = _print?.box;
    return box != null && box.attached && box.hasSize ? box : this;
  }

  /// Whether the seam may touch this element now (the style breaks graphics up and it is near).
  bool _near() {
    final seam = _seam;
    if (seam == null || !attached || !hasSize || !seam.hasLine || seam.style == SeamTopStyle.tide) return false;
    final top = localToGlobal(Offset.zero).dy - seam.lineY;
    return top < seamDissolveReach && top + size.height > -seamDissolveReach;
  }

  void _onSeam() {
    final near = _near();
    final style = _seam?.style;
    if (near != _masking) {
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
    final seam = _seam;
    if (seam == null || !_masking) return null;
    final printBox = _printBox;
    final printTopLeft = printBox.localToGlobal(Offset.zero);
    final origin = identical(printBox, this) ? Offset.zero : localToGlobal(Offset.zero) - printTopLeft;
    return seamMaskFor(
      style: seam.style,
      offsetFromSeam: printTopLeft.dy - seam.lineY,
      printSize: printBox.size,
      origin: origin,
      size: size,
      printLeft: printTopLeft.dx,
      reveal: seam.reveal,
      stretch: seam.stretch,
      disorder: seam.disorder,
      lagPx: seam.lagPx,
      travelPx: seam.travelPx,
      reduced: seam.reduced,
      round: _round,
      seed: _print?.seed ?? _ownSeed,
    );
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    final child = this.child;
    if (child == null) return;
    _paintedStyle = _seam?.style;
    final shape = _shape();
    if (shape == null) {
      _layer.layer = null;
      context.paintChild(child, offset);
      return;
    }
    if (shape is SeamMaskGone) {
      _layer.layer = null;
      return;
    }
    final (image, veil) = _mask(shape);
    final previous = _image;
    _image = image;
    final scale = 1 / _dpr;
    final shader = ImageShader(image, TileMode.clamp, TileMode.clamp, Matrix4.diagonal3Values(scale, scale, 1).storage);
    previous?.dispose();
    _layer.layer = (_layer.layer ?? ShaderMaskLayer())
      ..shader = shader
      ..maskRect = offset & size
      ..blendMode = BlendMode.dstIn;
    context.pushLayer(_layer.layer!, (context, offset) {
      context.paintChild(child, offset);
      if (veil != null) _paintVeil(context.canvas, offset, veil);
    }, offset);
  }

  /// Renders [shape] as an alpha image over this element (device px), and
  /// the lip's veil to paint over the kept tips, if any.
  (ui.Image, SeamMaskLip?) _mask(SeamMaskShape shape) {
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder)..scale(_dpr);
    final paint = Paint()
      ..isAntiAlias = true
      ..color = const Color(0xFF000000);
    double height;
    SeamMaskLip? veil;
    switch (shape) {
      case SeamMaskDots(:final mask, :final origin):
        // Down to the first solid row, whole below (the image's last row clamps on).
        height = (mask.solidFrom - origin.dy + 1).clamp(1.0, size.height);
        canvas.translate(-origin.dx, -origin.dy);
        canvas.drawRect(Rect.fromLTRB(-1, mask.solidFrom, origin.dx + size.width + 1, origin.dy + height + 1), paint);
        for (final dot in mask.dots) {
          canvas.drawCircle(Offset(dot.x, dot.y), dot.radius, paint);
        }
      case SeamMaskLip(:final lip, :final printSize, :final origin, :final round):
        final bottom = lip.seam + lip.reach + 1;
        height = (bottom - origin.dy).clamp(1.0, size.height);
        canvas.translate(-origin.dx, -origin.dy);
        canvas.drawPath(
          lip.keep(printSize.width, printSize.height, round: round, bottom: origin.dy + height + 1),
          paint,
        );
        if (lip.veil > 0 && _seam?.pageAtLine != null) veil = shape;
      case SeamMaskGone():
        height = 1;
    }
    final picture = recorder.endRecording();
    final image = picture.toImageSync(math.max(1, (size.width * _dpr).ceil()), math.max(1, (height * _dpr).ceil()));
    picture.dispose();
    return (image, veil);
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
