/// The grid becomes the top bar (Android ledger/MemoryGridLift.kt, owner
/// 2026-11-09): as All Memories scrolls up past its grid, the grid's squares
/// rise into the top bar; scrolling back down they come home. Everything per
/// frame is read while painting.
///
/// The bar is a mosaic of square pixels (owner 2026-11-12): the squares keep
/// the grid's own look in flight, piece together edge to edge in a few rows
/// as they land, and run oldest first down each column and on across. With
/// more places than squares, a square takes two neighbouring places, so every
/// bar is filled whatever the count.
library;

import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../data/memory/memory_grid.dart';
import '../../theme/tokens.dart';
import 'memory_grid_view.dart';

/// The bar's squares: [rows] of them down, each [side] px, [places] in all
/// (the last column may run off the edge).
@immutable
final class BarMosaic {
  const BarMosaic(this.rows, this.side, this.places);
  final int rows;
  final double side;
  final int places;
}

/// The fewest rows (two at least, so it reads as pixels, not tiles) whose
/// squares, filling the bar's height and running across its [width], have a
/// place for each of [count] squares.
BarMosaic barMosaic(int count, double width, double height) {
  var rows = 2;
  while (true) {
    final side = height / rows;
    final columns = math.max(1, (width / side).ceil());
    if (rows * columns >= count || rows >= MemoryLedgerTokens.liftMaxBarRows) {
      return BarMosaic(rows, side, rows * columns);
    }
    rows++;
  }
}

double _smoothstep(double e0, double e1, double x) {
  final t = ((x - e0) / (e1 - e0)).clamp(0.0, 1.0);
  return t * t * (3 - 2 * t);
}

/// How far the grid has gone under a bar [barBottom] down [into]: 0 wholly
/// below it, 1 wholly gone. Once measured, the grid's place in the scroll is
/// kept, so the bar stays formed after the list has let the grid go.
class MemoryGridLiftProgress {
  MemoryGridLiftProgress(this.lift, this.scroll);

  final MemoryGridLift lift;
  final ScrollController scroll;

  double? _topInScroll;
  double _height = 1;

  /// [measure]: read the grid's place now (only while painting, when the
  /// layout matches the offset); else use the last measured one.
  double of(RenderBox into, double barBottom, {bool measure = true}) {
    final offset = scroll.hasClients ? scroll.offset : 0.0;
    final measured = measure ? lift.gridIn(into) : null;
    if (measured != null) {
      _topInScroll = measured.$1 + offset;
      _height = measured.$2;
    }
    final top = _topInScroll;
    if (top == null) return 0;
    return ((barBottom - (top - offset)) / _height).clamp(0.0, 1.0);
  }
}

/// The squares on their way up and, landed, the bar: painted over the list
/// and under the bar's title and back button. [barBottom] is the bar's bottom
/// from the screen's top (status bar included). Reduced motion: no flight, the
/// whole bar fades in where it ends up.
class MemoryGridLiftPainter extends CustomPainter {
  MemoryGridLiftPainter({
    required this.lift,
    required this.progress,
    required this.pixels,
    required this.colors,
    required this.faint,
    required this.barBottom,
    required this.into,
    required this.reduced,
    required this.rtl,
    required Listenable repaint,
  }) : super(repaint: repaint);

  final MemoryGridLift lift;
  final MemoryGridLiftProgress progress;
  final List<LiftPixel> pixels;
  final LedgerColorBook colors;
  final Color faint;
  final double barBottom;
  final RenderBox? Function() into;
  final bool reduced;
  final bool rtl;

  @override
  void paint(Canvas canvas, Size size) {
    final box = into();
    final n = pixels.length;
    if (box == null || n == 0) return;
    final p = progress.of(box, barBottom);
    if (p <= 0.001) return;
    final mosaic = barMosaic(n, size.width, barBottom);
    if (reduced) canvas.saveLayer(Offset.zero & size, Paint()..color = Color.fromRGBO(0, 0, 0, p));
    for (var k = 0; k < mosaic.places; k++) {
      final i = k * n ~/ mosaic.places;
      final pixel = pixels[i];
      final column = k ~/ mosaic.rows;
      final row = k % mosaic.rows;
      final x = rtl ? size.width - (column + 1) * mosaic.side : column * mosaic.side;
      // A hair larger than the square so neighbours meet without a seam.
      final target = Rect.fromLTRB(x, row * mosaic.side, x + mosaic.side + 1, (row + 1) * mosaic.side + 1);
      if (reduced) {
        _barPixel(canvas, pixel, target, 1);
        continue;
      }
      // Oldest first, but close together so they rise as one flock.
      final lag = MemoryLedgerTokens.liftLag * i / n;
      final q = _smoothstep(0.02 + lag, 0.6 + lag, p);
      final from = lift.rectOf(pixel, box);
      final rect = from == null || q >= 1 ? target : Rect.lerp(from, target, q)!;
      final radius = from == null ? 0.0 : from.width * MemoryLedgerTokens.liftCorner * (1 - q);
      // The grid's look in flight; the bar's pixels as it lands.
      final landed = _smoothstep(0.7, 1, q);
      if (landed < 1) {
        if (pixel.mark < 0) {
          paintLedgerCell(canvas, pixel.cell, rect, radius, colors, faint);
        } else {
          paintLedgerMark(canvas, pixel.cell.marks[pixel.mark], rect, radius, colors);
        }
      }
      if (landed > 0) _barPixel(canvas, pixel, rect, landed);
    }
    if (reduced) canvas.restore();
    // A light veil under the title once the bar has formed, so white reads on pale covers too.
    final veil = MemoryLedgerTokens.liftVeil * _smoothstep(0.6, 1, p);
    if (veil > 0) {
      canvas.drawRect(Rect.fromLTWH(0, 0, size.width, barBottom), Paint()..color = Color.fromRGBO(0, 0, 0, veil));
    }
  }

  /// A landed square, as pixels: two colours (an album written about, or two
  /// albums spliced) make a 2×2 checker of them; one colour stays one square.
  void _barPixel(Canvas canvas, LiftPixel pixel, Rect rect, double alpha) {
    final pair = _barColors(pixel);
    if (pair == null) return;
    Paint paint(Color c) => Paint()..color = c.withValues(alpha: c.a * alpha);
    final (first, second) = pair;
    if (first == second) {
      canvas.drawRect(rect, paint(first));
      return;
    }
    final w = rect.width / 2, h = rect.height / 2;
    final half = Size(w + 0.5, h + 0.5);
    canvas
      ..drawRect(Offset(rect.left, rect.top) & half, paint(first))
      ..drawRect(Offset(rect.left + w, rect.top) & half, paint(second))
      ..drawRect(Offset(rect.left, rect.top + h) & half, paint(second))
      ..drawRect(Offset(rect.left + w, rect.top + h) & half, paint(first));
  }

  (Color, Color)? _barColors(LiftPixel pixel) {
    (Color, Color) of(MemoryGridMark mark) {
      final c = colors.colorsOf(mark.album);
      return mark.wrote ? (c.base, c.accent) : (c.base, c.base);
    }

    if (pixel.mark >= 0) return of(pixel.cell.marks[pixel.mark]);
    final marks = pixel.cell.marks;
    if (marks.isEmpty) return null;
    if (marks.length == 1) return of(marks.first);
    return (colors.colorsOf(marks[0].album).base, colors.colorsOf(marks[1].album).base);
  }

  @override
  bool shouldRepaint(MemoryGridLiftPainter old) =>
      !identical(old.pixels, pixels) ||
      old.barBottom != barBottom ||
      old.reduced != reduced ||
      old.rtl != rtl ||
      old.faint != faint ||
      !identical(old.colors, colors);
}
