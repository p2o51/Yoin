/// The screen-print cover reveal (Android ArtworkHalftone.kt): a fine,
/// staggered dot texture carried by a curved front that rolls across the
/// artwork as the reveal's effect spring moves. Dots grow from nothing to a
/// solid cover behind the front; a thin primary→tertiary fringe and a faint
/// bloom ride on the moving band only. Android draws it with plain canvas
/// circles and DstIn / DstOut layers, not a shader; so does this.
library;

import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/painting.dart';

/// Circle centres and radii in one flat array, reused frame to frame.
final class HalftoneDots {
  Float32List _data = Float32List(3 * 256);
  int _count = 0;

  int get length => _count;
  bool get isEmpty => _count == 0;

  Offset centerAt(int i) => Offset(_data[i * 3], _data[i * 3 + 1]);
  double radiusAt(int i) => _data[i * 3 + 2];

  void clear() => _count = 0;

  void add(double x, double y, double radius) {
    if (radius <= 0) return;
    if ((_count + 1) * 3 > _data.length) {
      final grown = Float32List(_data.length * 2)..setAll(0, _data);
      _data = grown;
    }
    _data[_count * 3] = x;
    _data[_count * 3 + 1] = y;
    _data[_count * 3 + 2] = radius;
    _count++;
  }

  void draw(Canvas canvas, Paint paint) {
    for (var i = 0; i < _count; i++) {
      canvas.drawCircle(Offset(_data[i * 3], _data[i * 3 + 1]), _data[i * 3 + 2], paint);
    }
  }
}

/// One frame of the reveal: the mask the incoming cover shows through (row
/// strips for the settled region plus the moving band's dots), and the fringe
/// ([ink]) and [bloom] dots around the band. Reused frame to frame.
class ArtworkHalftone {
  final HalftoneDots mask = HalftoneDots();
  final HalftoneDots ink = HalftoneDots();
  final HalftoneDots bloom = HalftoneDots();
  final List<Rect> strips = [];

  static const double _tau = math.pi * 2;

  /// The incoming print's opacity at [progress]: the old cover stays fully
  /// opaque underneath, and softly developing the new one avoids a hard
  /// new-image/old-image boundary.
  static double opacity(double progress) => .62 + .38 * _smoothstep(progress.clamp(0.0, 1.0));

  static double _smoothstep(double v) => v * v * (3 - 2 * v);

  /// Lays the frame out for [size] at [progress] (0..1). [direction] ≥ 0
  /// enters from the right (a skip forward), < 0 from the left. [pitch] is the
  /// dot grid's spacing; [stripOverlap] the hairline the settled strips
  /// overlap by so their antialiased edges leave no seam.
  void layout(Size size, double progress, int direction, {required double pitch, double stripOverlap = 1}) {
    mask.clear();
    ink.clear();
    bloom.clear();
    strips.clear();
    if (size.isEmpty) return;
    final columns = math.max(1, (size.width / pitch).ceil());
    final rows = math.max(1, (size.height / (pitch * .866)).ceil());
    final cellWidth = size.width / columns;
    final cellHeight = size.height / rows;
    final fullRadius = math.sqrt(cellWidth * cellWidth + cellHeight * cellHeight) * .53;
    final front = progress * 1.65 - .325;
    final fromRight = direction >= 0;

    // Overscan keeps half-dots and the mask continuous at the artwork's edges.
    for (var row = -1; row <= rows; row++) {
      final y = (row + .5) * cellHeight;
      final v = y / size.height;
      // The field itself rolls while it travels. Its rate stays below the
      // reveal's forward rate so completed cells never reopen.
      final bend =
          .09 * math.sin((v * .95 + progress * .6 + .07) * _tau) +
          .045 * math.sin((v * 1.85 - progress * .4 - .1) * _tau);
      final stagger = row.isEven ? 0.0 : .5;
      var solidEdge = fromRight ? double.infinity : double.negativeInfinity;
      for (var column = -1; column <= columns; column++) {
        final x = (column + .5 + stagger) * cellWidth;
        final u = x / size.width;
        final order = fromRight ? 1 - u : u;
        final drift =
            .025 * math.sin((order * .8 + v * .8 + progress * .2) * _tau) +
            .015 * math.sin((order * 2.3 + v * 3.1) * _tau);
        final arrival = .14 + order * .72 + bend + drift;
        final local = ((front - arrival) / .52 + .5).clamp(0.0, 1.0);
        if (local <= 0) continue;
        if (local >= 1) {
          solidEdge = fromRight ? math.min(solidEdge, x) : math.max(solidEdge, x);
          continue;
        }
        final envelope = math.sin(local * math.pi);
        final flowX = math.sin((v * 1.4 + order * .45 - progress * .8) * _tau);
        final flowY = math.sin((v * .7 - order * 1.3 + progress * .65) * _tau);
        final cx = x + cellWidth * .42 * flowX * envelope;
        final cy = y + cellHeight * .38 * flowY * envelope;
        final growth = ((local - .12) / .88).clamp(0.0, 1.0);
        final radius = fullRadius * (.2 * local + .8 * _smoothstep(growth));
        mask.add(cx, cy, radius);
        // A very thin coloured fringe and a low-opacity outer bloom, both
        // confined to the moving front. No white wash.
        ink.add(cx, cy, radius + cellWidth * .06 * envelope);
        bloom.add(cx, cy, radius + cellWidth * .32 * envelope);
      }
      if (solidEdge.isFinite) {
        // The completed region merges into row strips: only the moving band
        // needs individual circles. Neighbours overlap by a hairline so their
        // antialiased edges leave no seam (the mask is opaque, so overlaps
        // can't darken).
        final edge = solidEdge.clamp(0.0, size.width);
        strips.add(
          Rect.fromLTRB(
            fromRight ? edge : 0,
            y - cellHeight * .5 - stripOverlap,
            fromRight ? size.width : edge,
            y + cellHeight * .5 + stripOverlap,
          ),
        );
        mask.add(solidEdge, y, fullRadius);
      }
    }
  }

  static final Paint _solid = Paint()..color = const Color(0xFF000000);

  /// Paints [content] (the incoming cover, already at [opacity]) through the
  /// frame [layout] made, with the fringe and bloom tinted [primary] →
  /// [tertiary] outside the mask.
  void paint(
    Canvas canvas,
    Size size, {
    required Color primary,
    required Color tertiary,
    required void Function(Canvas) content,
  }) {
    final bounds = Offset.zero & size;
    final tint = ui.Gradient.linear(Offset(size.width, 0), Offset(0, size.height), [primary, tertiary]);
    // The incoming image is translucent while it develops. Keep the tint
    // outside its mask, otherwise joined dots leave a second colour edge.
    _tintedOutsideMask(canvas, bounds, bloom, tint, .045);
    _tintedOutsideMask(canvas, bounds, ink, tint, .14);
    // The incoming image, kept only inside the mask.
    canvas.saveLayer(bounds, Paint());
    content(canvas);
    canvas.saveLayer(bounds, Paint()..blendMode = BlendMode.dstIn);
    _drawMask(canvas);
    canvas.restore();
    canvas.restore();
  }

  /// [dots] as one union, filled with [tint] at [alpha], minus the mask.
  void _tintedOutsideMask(Canvas canvas, Rect bounds, HalftoneDots dots, Shader tint, double alpha) {
    if (dots.isEmpty) return;
    // Opaque union first, then tint and alpha once: overlaps don't darken.
    canvas.saveLayer(bounds, Paint()..color = Color.fromRGBO(0, 0, 0, alpha));
    dots.draw(canvas, _solid);
    canvas.drawRect(
      bounds,
      Paint()
        ..shader = tint
        ..blendMode = BlendMode.srcIn,
    );
    canvas.saveLayer(bounds, Paint()..blendMode = BlendMode.dstOut);
    _drawMask(canvas);
    canvas.restore();
    canvas.restore();
  }

  void _drawMask(Canvas canvas) {
    for (final strip in strips) {
      canvas.drawRect(strip, _solid);
    }
    mask.draw(canvas, _solid);
  }
}
