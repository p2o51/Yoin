/// Music services' marks redrawn in the Yoin Symbols hand (ServiceMarks.kt):
/// a 24 × 24 grid, a 1.5 stroke with round caps and joins, so they sit with the
/// rest of the icon set instead of pasting in brand artwork. Brand marks are
/// artwork, not symbols (AGENTS.md), so they stay in the app, as Android's do.
///
/// Only the services HarmonyOS ships are here: Apple Music. (Android's Spotify
/// mark joins when a Spotify provider does.)
library;

import 'package:flutter/widgets.dart';

/// One path of a mark, in SVG path data on the 24 × 24 grid: stroked, or
/// filled and stroked (the stroke rounds a filled shape's corners).
@immutable
final class MarkPath {
  const MarkPath(this.data, {this.filled = false});

  final String data;
  final bool filled;
}

/// A service's redrawn mark.
@immutable
final class ServiceMark {
  const ServiceMark(this.name, this.paths);

  final String name;
  final List<MarkPath> paths;

  /// The grid every mark is drawn on (Yoin Symbols' viewport).
  static const double grid = 24;

  /// The Yoin Symbols stroke on that grid (YoinSymbolStrokeWidth).
  static const double strokeWidth = 1.5;

  /// Apple Music: two eighth notes joined by a heavy slanted beam.
  static const appleMusic = ServiceMark('AppleMusic', [
    // Note heads, on the same outline weight as MusicNote's.
    MarkPath(
      'M7.55 14.75 C8.903 14.75 10 15.847 10 17.2 C10 18.553 8.903 19.65 7.55 19.65 '
      'C6.197 19.65 5.1 18.553 5.1 17.2 C5.1 15.847 6.197 14.75 7.55 14.75 Z',
    ),
    MarkPath(
      'M16.05 12.95 C17.403 12.95 18.5 14.047 18.5 15.4 C18.5 16.753 17.403 17.85 16.05 17.85 '
      'C14.697 17.85 13.6 16.753 13.6 15.4 C13.6 14.047 14.697 12.95 16.05 12.95 Z',
    ),
    // Stems up to the beam.
    MarkPath('M10 17.2 L10 8.6'),
    MarkPath('M18.5 15.4 L18.5 6.7'),
    // The beam: filled, its stroke rounds the corners.
    MarkPath('M10 6.35 L18.5 4.45 L18.5 6.7 L10 8.6 Z', filled: true),
  ]);
}

/// Parses the path data a mark uses — M, L, H, V, C and Z, absolute or
/// relative — into a [Path] on the 24 × 24 grid.
Path parseMarkPath(String data) {
  final path = Path();
  final tokens = RegExp(r'[MLHVCZmlhvcz]|-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?').allMatches(data).map((m) => m[0]!);
  final it = tokens.iterator;
  String? command;
  var x = 0.0, y = 0.0, startX = 0.0, startY = 0.0;
  final pending = <double>[];

  int arity(String c) => switch (c.toUpperCase()) {
    'M' || 'L' => 2,
    'H' || 'V' => 1,
    'C' => 6,
    _ => 0,
  };

  void apply(String c, List<double> a) {
    final rel = c == c.toLowerCase();
    final ox = rel ? x : 0.0, oy = rel ? y : 0.0;
    switch (c.toUpperCase()) {
      case 'M':
        x = ox + a[0];
        y = oy + a[1];
        startX = x;
        startY = y;
        path.moveTo(x, y);
      case 'L':
        x = ox + a[0];
        y = oy + a[1];
        path.lineTo(x, y);
      case 'H':
        x = ox + a[0];
        path.lineTo(x, y);
      case 'V':
        y = oy + a[0];
        path.lineTo(x, y);
      case 'C':
        path.cubicTo(ox + a[0], oy + a[1], ox + a[2], oy + a[3], ox + a[4], oy + a[5]);
        x = ox + a[4];
        y = oy + a[5];
    }
  }

  while (it.moveNext()) {
    final token = it.current;
    final number = double.tryParse(token);
    if (number == null) {
      command = token;
      pending.clear();
      if (token == 'Z' || token == 'z') {
        path.close();
        x = startX;
        y = startY;
      }
      continue;
    }
    final c = command;
    if (c == null) throw FormatException('Mark path data starts with a number', data);
    pending.add(number);
    if (pending.length == arity(c)) {
      apply(c, pending);
      pending.clear();
      // Extra pairs after a moveto are linetos (SVG).
      if (c == 'M') command = 'L';
      if (c == 'm') command = 'l';
    }
  }
  if (pending.isNotEmpty) throw FormatException('Mark path data ends mid-command', data);
  return path;
}

/// Paints [mark] over its box in [color], scaled from the 24 × 24 grid.
class ServiceMarkPainter extends CustomPainter {
  ServiceMarkPainter(this.mark, this.color);

  final ServiceMark mark;
  final Color color;

  static final Map<String, Path> _parsed = {};

  @override
  void paint(Canvas canvas, Size size) {
    if (size.isEmpty) return;
    final scale = size.shortestSide / ServiceMark.grid;
    canvas.save();
    canvas.translate((size.width - ServiceMark.grid * scale) / 2, (size.height - ServiceMark.grid * scale) / 2);
    canvas.scale(scale);
    final stroke = Paint()
      ..isAntiAlias = true
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = ServiceMark.strokeWidth
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;
    final fill = Paint()
      ..isAntiAlias = true
      ..color = color
      ..style = PaintingStyle.fill;
    for (final p in mark.paths) {
      final path = _parsed.putIfAbsent(p.data, () => parseMarkPath(p.data));
      if (p.filled) canvas.drawPath(path, fill);
      canvas.drawPath(path, stroke);
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(ServiceMarkPainter old) => old.mark != mark || old.color != color;
}

/// A service mark at an icon's size and colour (an [Icon]'s defaults from
/// the ambient [IconTheme] when not given). Decorative, like the service
/// glyphs: the service's name beside it is what's read.
class ServiceMarkIcon extends StatelessWidget {
  const ServiceMarkIcon(this.mark, {super.key, this.size, this.color});

  final ServiceMark mark;
  final double? size;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final theme = IconTheme.of(context);
    final side = size ?? theme.size ?? ServiceMark.grid;
    final ink = color ?? theme.color ?? const Color(0xFF000000);
    final opacity = theme.opacity ?? 1;
    return SizedBox.square(
      dimension: side,
      child: CustomPaint(painter: ServiceMarkPainter(mark, ink.withValues(alpha: ink.a * opacity))),
    );
  }
}
