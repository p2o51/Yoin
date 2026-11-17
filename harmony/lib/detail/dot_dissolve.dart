import 'dart:ui' as ui;

import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart';

/// The dot dissolve's numbers (Android nowplaying/DotDissolve.kt).
abstract final class DotDissolveTokens {
  /// One dot per cell; the dot's full radius.
  static const double cell = 5;
  static const double radius = 3.3;

  /// Radius steps (one cached tile each).
  static const int steps = 20;
}

/// How far a dissolve at [progress] has shrunk its dots: 1 = whole, 0 = gone
/// (Android: 1 − smoothstep(min(1, progress × 1.35))).
double dotDissolveShrink(double progress) {
  final x = (progress * 1.35).clamp(0.0, 1.0);
  return 1 - x * x * (3 - 2 * x);
}

/// The whole layer's fade over the last tenth, for content the dots never fully clear.
double dotDissolveAlpha(double progress) {
  final x = ((progress - 0.9) / 0.1).clamp(0.0, 1.0);
  return 1 - x * x * (3 - 2 * x);
}

/// Breaks [child] up into fine dots as [progress] goes 0 → 1 (the focus
/// mode's dot dissolve, owner 2026-11-06; Android Modifier.dotDissolve): the
/// child is masked by a grid of dots that shrink until nothing is left. At 0
/// nothing is masked and no layer is pushed; at 1 the child isn't painted.
/// [progress] is read at paint time — repaint by passing a listenable.
class DotDissolve extends SingleChildRenderObjectWidget {
  const DotDissolve({super.key, required this.progress, this.repaint, super.child});

  final double Function() progress;
  final Listenable? repaint;

  @override
  RenderDotDissolve createRenderObject(BuildContext context) =>
      RenderDotDissolve(progress: progress, repaint: repaint, devicePixelRatio: MediaQuery.devicePixelRatioOf(context));

  @override
  void updateRenderObject(BuildContext context, RenderDotDissolve renderObject) {
    renderObject
      ..progress = progress
      ..repaint = repaint
      ..devicePixelRatio = MediaQuery.devicePixelRatioOf(context);
  }
}

class RenderDotDissolve extends RenderProxyBox {
  RenderDotDissolve({required double Function() progress, Listenable? repaint, required double devicePixelRatio})
    : _progress = progress,
      _repaint = repaint,
      _dpr = devicePixelRatio;

  double Function() _progress;
  set progress(double Function() value) {
    _progress = value;
    markNeedsPaint();
  }

  Listenable? _repaint;
  set repaint(Listenable? value) {
    if (identical(value, _repaint)) return;
    if (attached) _repaint?.removeListener(markNeedsPaint);
    _repaint = value;
    if (attached) _repaint?.addListener(markNeedsPaint);
    markNeedsPaint();
  }

  double _dpr;
  set devicePixelRatio(double value) {
    if (value == _dpr) return;
    _dpr = value;
    _disposeTiles();
    markNeedsPaint();
  }

  final _tiles = <int, ui.Image>{};
  final _layer = LayerHandle<ShaderMaskLayer>();
  final _opacity = LayerHandle<OpacityLayer>();

  @override
  void attach(PipelineOwner owner) {
    super.attach(owner);
    _repaint?.addListener(markNeedsPaint);
  }

  @override
  void detach() {
    _repaint?.removeListener(markNeedsPaint);
    super.detach();
  }

  @override
  void dispose() {
    _layer.layer = null;
    _opacity.layer = null;
    _disposeTiles();
    super.dispose();
  }

  void _disposeTiles() {
    for (final t in _tiles.values) {
      t.dispose();
    }
    _tiles.clear();
  }

  @override
  bool get alwaysNeedsCompositing => child != null;

  /// One small tile per radius step, in device pixels: a dot in the middle of a cell.
  ui.Image _tile(int step) => _tiles[step] ??= () {
    final cell = (DotDissolveTokens.cell * _dpr).round().clamp(2, 64);
    final recorder = ui.PictureRecorder();
    Canvas(recorder).drawCircle(
      Offset(cell / 2, cell / 2),
      DotDissolveTokens.radius * _dpr * step / DotDissolveTokens.steps,
      Paint()
        ..isAntiAlias = true
        ..color = const Color(0xFF000000),
    );
    final picture = recorder.endRecording();
    final image = picture.toImageSync(cell, cell);
    picture.dispose();
    return image;
  }();

  @override
  void paint(PaintingContext context, Offset offset) {
    final child = this.child;
    if (child == null) return;
    final c = _progress().clamp(0.0, 1.0);
    if (c <= 0.001) {
      _layer.layer = null;
      _opacity.layer = null;
      context.paintChild(child, offset);
      return;
    }
    final step = (dotDissolveShrink(c) * DotDissolveTokens.steps).round();
    if (step <= 0) {
      _layer.layer = null;
      _opacity.layer = null;
      return;
    }
    final alpha = (dotDissolveAlpha(c) * 255).round();
    _opacity.layer = context.pushOpacity(offset, alpha, (context, offset) {
      if (step >= DotDissolveTokens.steps) {
        _layer.layer = null;
        context.paintChild(child, offset);
        return;
      }
      final scale = 1 / _dpr;
      final shader = ImageShader(
        _tile(step),
        TileMode.repeated,
        TileMode.repeated,
        Matrix4.diagonal3Values(scale, scale, 1).storage,
      );
      _layer.layer = (_layer.layer ?? ShaderMaskLayer())
        ..shader = shader
        ..maskRect = offset & size
        ..blendMode = BlendMode.dstIn;
      context.pushLayer(_layer.layer!, (context, offset) => context.paintChild(child, offset), offset);
    }, oldLayer: _opacity.layer);
  }
}
