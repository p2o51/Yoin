import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui';

import 'shape_path.dart';

/// MaterialShapes.Cookie12Sided as a unit path, with its measured centre and
/// outer (lobe) radius (Android GrooveEmblem.kt CookieUnit): the score
/// emblems' cookie label and the unrated "empty mould".
abstract final class CookieUnit {
  /// CookieUnit samples the outline this many times to measure it.
  static const int _samples = 720;

  /// The unit path (MaterialShapes.Cookie12Sided.toPath()), starting on the
  /// top lobe's peak and running clockwise.
  static final Path path = MaterialShapes.cookie12Sided.toPath();

  static final List<Offset> _points = () {
    final metric = path.computeMetrics().first;
    return [for (var i = 0; i < _samples; i++) metric.getTangentForOffset(metric.length * i / _samples)!.position];
  }();

  /// The middle of the sampled outline's bounds.
  static final Offset center = () {
    var minX = double.infinity, minY = double.infinity, maxX = -double.infinity, maxY = -double.infinity;
    for (final p in _points) {
      minX = math.min(minX, p.dx);
      maxX = math.max(maxX, p.dx);
      minY = math.min(minY, p.dy);
      maxY = math.max(maxY, p.dy);
    }
    return Offset((minX + maxX) / 2, (minY + maxY) / 2);
  }();

  /// The farthest sampled point from [center]: the lobes' reach.
  static final double outerRadius = _points.map((p) => (p - center).distance).reduce(math.max);

  /// Cookie12Sided scaled so its lobes reach [radius], centred on [centre].
  static Path at(Offset centre, double radius) {
    final scale = radius / outerRadius;
    return path.transform(
      Float64List.fromList([
        scale, 0, 0, 0, //
        0, scale, 0, 0, //
        0, 0, 1, 0, //
        centre.dx - center.dx * scale, centre.dy - center.dy * scale, 0, 1,
      ]),
    );
  }
}
