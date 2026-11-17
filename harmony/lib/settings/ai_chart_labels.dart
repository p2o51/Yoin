import 'dart:ui';

/// Where the AI chart puts a dot's name beside it (Android AiChartLabels). A
/// name goes on the dot's right if it fits there, otherwise on its left, above
/// or below (centred, then running off to either side): the first of those
/// that stays inside the plot and covers no dot and no name placed before it.
/// Gemini's two tiers sit almost on top of each other and a phone's plot is
/// narrow, so the right side alone runs names off the plot or over the
/// neighbouring dots.
abstract final class AiChartLabels {
  /// Each of [labels] (an index into [dots] and the name's size), in order,
  /// as the name's top-left relative to its dot's centre. Nothing fits: the
  /// side that covers the least, the right first. [gap] is from the dot's
  /// centre to the name; [dotRadius] is how much of a dot the names keep off.
  static List<Offset> place({
    required Rect plot,
    required List<Offset> dots,
    required double dotRadius,
    required double gap,
    required List<(int, Size)> labels,
  }) {
    final placed = <Rect>[];
    return [for (final (index, size) in labels) _placeOne(plot, dots, dotRadius, gap, index, size, placed)];
  }

  static Offset _placeOne(
    Rect plot,
    List<Offset> dots,
    double dotRadius,
    double gap,
    int index,
    Size size,
    List<Rect> placed,
  ) {
    final centre = dots[index];
    final sides = [
      Offset(gap, -size.height / 2),
      Offset(-gap - size.width, -size.height / 2),
      Offset(-size.width / 2, -gap - size.height),
      Offset(-size.width / 2, gap),
      // Above or below, running off to one side: for a dot near the plot's edge with a
      // neighbour beside it (DeepSeek's two tiers at the cheap end on a phone).
      Offset(0, -gap - size.height),
      Offset(0, gap),
      Offset(-size.width, -gap - size.height),
      Offset(-size.width, gap),
    ];
    Offset? best;
    var fewest = 0;
    for (final side in sides) {
      final box = (centre + side) & size;
      var clashes = 0;
      if (box.left < plot.left || box.right > plot.right || box.top < plot.top || box.bottom > plot.bottom) {
        clashes++;
      }
      for (final (i, dot) in dots.indexed) {
        if (i != index && _touches(box, dot, dotRadius)) clashes++;
      }
      clashes += placed.where((r) => r.overlaps(box)).length;
      // The first side with the fewest clashes, as Kotlin's minBy.
      if (best == null || clashes < fewest) {
        best = side;
        fewest = clashes;
      }
    }
    placed.add((centre + best!) & size);
    return best;
  }

  static bool _touches(Rect box, Offset centre, double radius) {
    final nearX = centre.dx.clamp(box.left, box.right);
    final nearY = centre.dy.clamp(box.top, box.bottom);
    final dx = centre.dx - nearX;
    final dy = centre.dy - nearY;
    return dx * dx + dy * dy < radius * radius;
  }
}
