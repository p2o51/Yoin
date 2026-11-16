import 'dart:collection';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';
import 'package:flutter/scheduler.dart';

import '../theme/motion.dart';
import '../theme/tokens.dart';

// The Note page's empty state (NoteEmptyDoodle.kt, 2026-10-08 owner: a doodle
// instead of a prompt). Three drawings, one picked at random per song:
// - stroke: a wave pulled out of the playhead's dot that lands as a note — the
//   same idea as the progress wave and its note anchors;
// - paper: a tilted sticky note whose third line stops halfway, a caret waiting;
// - mark: the app icon's three arrows sketched in a loose circle.
//
// This is artwork, not an icon: the path data is the approved web
// prototype's, as Android has it. Strokes draw themselves in order on effects
// springs (no overshoot: a stroke never draws past its end); filled shapes pop
// in on the expressive spatial spring. Once drawn it rests — the caret blinks
// a few times, then stays — so an idle empty page asks for no frames. Colours
// are the theme's roles, so the doodle follows the playing palette.

/// The three drawings (NoteDoodle).
enum NoteDoodle { stroke, paper, mark }

/// The drawings' coordinate box; the canvas is sized at this aspect ratio.
const double noteDoodleViewBoxWidth = 260;
const double noteDoodleViewBoxHeight = 170;

const int _caretBlinks = 3;
const int _caretBlinkMs = 500;
const double _paperTint = 0.45;

/// The caret's alpha [sinceDrawnMs] after the drawing settled (the blinks
/// before it rests): each half-second on is followed by a half-second off,
/// [_caretBlinks] times; on from then on.
double noteCaretBlink(double sinceDrawnMs) {
  if (sinceDrawnMs < 0 || sinceDrawnMs >= _caretBlinks * _caretBlinkMs * 2) return 1;
  return (sinceDrawnMs ~/ _caretBlinkMs).isOdd ? 0 : 1;
}

/// How many songs' picks are remembered.
const int _rememberedPicks = 64;
final LinkedHashMap<Object, NoteDoodle> _picks = LinkedHashMap();

/// The doodle for [song]: one of the three at random the first time, the same
/// one every time after (the most recent [_rememberedPicks] songs).
NoteDoodle noteDoodleFor(Object song, {math.Random? random}) {
  final kept = _picks.remove(song);
  final pick = kept ?? NoteDoodle.values[(random ?? math.Random()).nextInt(NoteDoodle.values.length)];
  _picks[song] = pick;
  while (_picks.length > _rememberedPicks) {
    _picks.remove(_picks.keys.first);
  }
  return pick;
}

/// Forgets every pick (tests).
@visibleForTesting
void resetNoteDoodlePicks() => _picks.clear();

enum _Ink { primary, secondary, tertiary, paper, outline }

Color _inkColor(_Ink ink, ColorScheme scheme) => switch (ink) {
  _Ink.primary => scheme.primary,
  _Ink.secondary => scheme.secondary,
  _Ink.tertiary => scheme.tertiary,
  // A tint of paper, not a block of accent: tertiaryContainer is loud on some
  // playing palettes, so it is mixed into the page's own surface.
  _Ink.paper => Color.lerp(scheme.surfaceContainerHigh, scheme.tertiaryContainer, _paperTint)!,
  _Ink.outline => scheme.outlineVariant,
};

/// One mark of a drawing, in the order it appears.
sealed class _Mark {
  _Mark(String d, this.ink, {required this.delayMs}) : path = doodlePath(d);
  final Path path;
  final _Ink ink;
  final int delayMs;

  /// The spring that draws it in.
  SpringDescription get spring;
}

/// A stroke that draws itself along its length.
final class _Line extends _Mark {
  _Line(super.d, super.ink, {required this.width, required super.delayMs, this.long = false});
  final double width;
  final bool long;
  @override
  SpringDescription get spring => long ? _slowEffects : YoinSprings.defaultEffects;
}

/// A filled shape that pops in from its centre.
final class _Fill extends _Mark {
  _Fill(super.d, super.ink, {required super.delayMs, this.rotationDegrees = 0});
  final double rotationDegrees;
  @override
  SpringDescription get spring => YoinSprings.defaultSpatial;
}

/// The paper's caret: fades in, then blinks once everything is drawn.
final class _Caret extends _Mark {
  _Caret(super.d, super.ink, {required this.width, required super.delayMs});
  final double width;
  @override
  SpringDescription get spring => YoinSprings.defaultEffects;
}

final SpringDescription _slowEffects = YoinSprings.of(MotionTokens.expressiveSlowEffects);

// All coordinates are in a 260 × 170 box (the approved web prototype's viewBox).
List<_Mark> _marks(NoteDoodle doodle) => switch (doodle) {
  NoteDoodle.stroke => [
    _Fill('M25 104 a5 5 0 1 0 10 0 a5 5 0 1 0 -10 0 Z', _Ink.tertiary, delayMs: 0),
    _Line(
      'M30 104 C44 78 58 128 74 104 S102 78 116 104 S144 128 158 102 S184 80 198 100',
      _Ink.primary,
      width: 3.2,
      delayMs: 250,
      long: true,
    ),
    _Fill('M196 122 a10 7.5 0 1 0 20 0 a10 7.5 0 1 0 -20 0 Z', _Ink.secondary, delayMs: 1300, rotationDegrees: -22),
    _Line('M215 119 L214 76', _Ink.secondary, width: 3, delayMs: 1400),
    _Line('M214 76 C226 80 232 90 226 102', _Ink.secondary, width: 3, delayMs: 1700),
    _Line('M232 66 l8 -8', _Ink.tertiary, width: 2.4, delayMs: 2000),
    _Line('M240 80 l10 -2', _Ink.tertiary, width: 2.4, delayMs: 2100),
  ],
  NoteDoodle.paper => [
    _Fill('M56 26 L200 18 L208 132 L66 142 Z', _Ink.paper, delayMs: 0),
    _Line('M56 26 L200 18 L208 132 L184 134', _Ink.secondary, width: 2.4, delayMs: 100, long: true),
    _Line('M184 134 L66 142 L56 26', _Ink.secondary, width: 2.4, delayMs: 550, long: true),
    _Line('M184 134 C186 124 190 116 207 114', _Ink.secondary, width: 2.4, delayMs: 900),
    _Line('M78 58 C108 52 146 56 182 50', _Ink.primary, width: 3, delayMs: 1150),
    _Line('M80 82 C116 78 150 81 186 76', _Ink.primary, width: 3, delayMs: 1500),
    _Line('M82 106 C98 103 112 105 126 102', _Ink.primary, width: 3, delayMs: 1850),
    _Caret('M134 92 L135 114', _Ink.tertiary, width: 3, delayMs: 2150),
  ],
  NoteDoodle.mark => [
    _Line(
      'M66 96 C58 52 120 30 168 44 C210 56 214 116 176 134 C138 150 76 140 66 104',
      _Ink.outline,
      width: 2.4,
      delayMs: 0,
      long: true,
    ),
    _Line('M128 88 L88 56', _Ink.primary, width: 5, delayMs: 600),
    _Line('M88 56 L106 58', _Ink.primary, width: 5, delayMs: 950),
    _Line('M88 56 L91 74', _Ink.primary, width: 5, delayMs: 1050),
    _Line('M128 88 L178 80', _Ink.secondary, width: 5, delayMs: 1200),
    _Line('M178 80 L163 70', _Ink.secondary, width: 5, delayMs: 1550),
    _Line('M178 80 L165 93', _Ink.secondary, width: 5, delayMs: 1650),
    _Line('M128 88 L119 136', _Ink.tertiary, width: 5, delayMs: 1800),
    _Line('M119 136 L108 123', _Ink.tertiary, width: 5, delayMs: 2150),
    _Line('M119 136 L131 125', _Ink.tertiary, width: 5, delayMs: 2250),
  ],
};

/// SVG path data → [Path]: M L H V C S Z and A, absolute and relative (the
/// doodles' strings use M, L, l, C, S, a and Z). A command's extra coordinate
/// sets repeat it; M's repeat as L.
Path doodlePath(String d) {
  final tokens = RegExp(r'[A-Za-z]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?').allMatches(d).map((m) => m[0]!).toList();
  final path = Path();
  var current = Offset.zero;
  var start = Offset.zero;
  // The last cubic's second control point, for S's reflection.
  Offset? lastControl;
  var i = 0;
  String? command;
  double next() => double.parse(tokens[i++]);
  bool hasNumber() => i < tokens.length && double.tryParse(tokens[i]) != null;
  while (i < tokens.length) {
    if (!hasNumber()) command = tokens[i++];
    final c = command;
    if (c == null) throw FormatException('Path data starts without a command: $d');
    final relative = c == c.toLowerCase();
    Offset point(double x, double y) => relative ? current + Offset(x, y) : Offset(x, y);
    switch (c.toUpperCase()) {
      case 'M':
        current = point(next(), next());
        start = current;
        path.moveTo(current.dx, current.dy);
        lastControl = null;
        // Further pairs are line-tos.
        command = relative ? 'l' : 'L';
      case 'L':
        current = point(next(), next());
        path.lineTo(current.dx, current.dy);
        lastControl = null;
      case 'H':
        final x = next();
        current = Offset(relative ? current.dx + x : x, current.dy);
        path.lineTo(current.dx, current.dy);
        lastControl = null;
      case 'V':
        final y = next();
        current = Offset(current.dx, relative ? current.dy + y : y);
        path.lineTo(current.dx, current.dy);
        lastControl = null;
      case 'C':
        final c1 = point(next(), next());
        final c2 = point(next(), next());
        final end = point(next(), next());
        path.cubicTo(c1.dx, c1.dy, c2.dx, c2.dy, end.dx, end.dy);
        lastControl = c2;
        current = end;
      case 'S':
        final c1 = lastControl == null ? current : current * 2 - lastControl;
        final c2 = point(next(), next());
        final end = point(next(), next());
        path.cubicTo(c1.dx, c1.dy, c2.dx, c2.dy, end.dx, end.dy);
        lastControl = c2;
        current = end;
      case 'A':
        final rx = next();
        final ry = next();
        final rotation = next();
        final largeArc = next() != 0;
        final sweep = next() != 0;
        final end = point(next(), next());
        // SVG's positive-angle sweep is clockwise on a y-down canvas, as Flutter's is.
        path.arcToPoint(
          end,
          radius: Radius.elliptical(rx, ry),
          rotation: rotation,
          largeArc: largeArc,
          clockwise: sweep,
        );
        current = end;
        lastControl = null;
      case 'Z':
        path.close();
        current = start;
        lastControl = null;
        command = null;
      default:
        throw FormatException('Unsupported path command $c in $d');
    }
  }
  return path;
}

/// The empty Note page's drawing, [doodle], drawing itself in on first show.
/// Decorative: it says nothing to a screen reader (the label under it does).
class NoteEmptyDoodle extends StatefulWidget {
  const NoteEmptyDoodle({super.key, required this.doodle});

  final NoteDoodle doodle;

  @override
  State<NoteEmptyDoodle> createState() => _NoteEmptyDoodleState();
}

class _NoteEmptyDoodleState extends State<NoteEmptyDoodle> with SingleTickerProviderStateMixin {
  late List<_Mark> _shapes = _marks(widget.doodle);
  late List<SpringSimulation> _sims = _simulations(_shapes);
  late final Ticker _ticker = createTicker(_tick);
  final ValueNotifier<double> _elapsedMs = ValueNotifier(0);

  /// When every mark had settled (ms), or null while some still move.
  double? _drawnAtMs;
  bool _reduced = false;

  static List<SpringSimulation> _simulations(List<_Mark> marks) => [
    for (final m in marks)
      SpringSimulation(m.spring, 0, 1, 0, tolerance: const Tolerance(distance: 0.001, velocity: 0.01)),
  ];

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _reduced = MediaQuery.disableAnimationsOf(context);
    if (_reduced) {
      _ticker.stop();
    } else if (!_ticker.isActive && _drawnAtMs == null) {
      _ticker.start();
    }
  }

  @override
  void didUpdateWidget(NoteEmptyDoodle old) {
    super.didUpdateWidget(old);
    if (old.doodle == widget.doodle) return;
    _shapes = _marks(widget.doodle);
    _sims = _simulations(_shapes);
    _drawnAtMs = null;
    _elapsedMs.value = 0;
    _ticker.stop();
    if (!_reduced) _ticker.start();
  }

  @override
  void dispose() {
    _ticker.dispose();
    _elapsedMs.dispose();
    super.dispose();
  }

  void _tick(Duration elapsed) {
    final ms = elapsed.inMicroseconds / 1000;
    _elapsedMs.value = ms;
    if (_drawnAtMs == null) {
      var settled = true;
      for (var i = 0; i < _shapes.length; i++) {
        final t = (ms - _shapes[i].delayMs) / 1000;
        if (t < 0 || !_sims[i].isDone(t)) {
          settled = false;
          break;
        }
      }
      if (settled) _drawnAtMs = ms;
    }
    final drawnAt = _drawnAtMs;
    if (drawnAt == null) return;
    // Rest once drawn; the paper's caret blinks a few times first.
    final blinkFor = widget.doodle == NoteDoodle.paper ? _caretBlinks * _caretBlinkMs * 2 : 0;
    if (ms >= drawnAt + blinkFor) _ticker.stop();
  }

  /// Mark [i]'s progress at [ms]: 0 before its delay, its spring after, 1 at rest.
  double _progress(int i, double ms) {
    if (_reduced || (_drawnAtMs != null && !_ticker.isActive)) return 1;
    final t = (ms - _shapes[i].delayMs) / 1000;
    if (t <= 0) return 0;
    final sim = _sims[i];
    return sim.isDone(t) ? 1 : sim.x(t);
  }

  /// The caret's blink at [ms] once drawn: on half a second, off half a second, three times, then on.
  double _blink(double ms) {
    final drawnAt = _drawnAtMs;
    if (_reduced || drawnAt == null || widget.doodle != NoteDoodle.paper) return 1;
    return noteCaretBlink(ms - drawnAt);
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return ExcludeSemantics(
      child: RepaintBoundary(
        child: ValueListenableBuilder<double>(
          valueListenable: _elapsedMs,
          builder: (context, ms, _) => CustomPaint(
            size: Size.infinite,
            painter: _DoodlePainter(
              marks: _shapes,
              progress: [for (var i = 0; i < _shapes.length; i++) _progress(i, ms)],
              blink: _blink(ms),
              scheme: scheme,
            ),
          ),
        ),
      ),
    );
  }
}

class _DoodlePainter extends CustomPainter {
  _DoodlePainter({required this.marks, required this.progress, required this.blink, required this.scheme});

  final List<_Mark> marks;
  final List<double> progress;
  final double blink;
  final ColorScheme scheme;

  @override
  void paint(Canvas canvas, Size size) {
    final unit = size.width / noteDoodleViewBoxWidth;
    canvas.save();
    canvas.scale(unit, unit);
    for (var i = 0; i < marks.length; i++) {
      final p = progress[i];
      if (p <= 0) continue;
      final mark = marks[i];
      final color = _inkColor(mark.ink, scheme);
      switch (mark) {
        case _Line(:final width):
          canvas.drawPath(
            _drawn(mark.path, p.clamp(0.0, 1.0)),
            Paint()
              ..color = color
              ..style = PaintingStyle.stroke
              ..strokeWidth = width
              ..strokeCap = StrokeCap.round
              ..strokeJoin = StrokeJoin.round,
          );
        case _Fill(:final rotationDegrees):
          final center = mark.path.getBounds().center;
          canvas.save();
          canvas.translate(center.dx, center.dy);
          canvas.scale(p, p);
          canvas.rotate(rotationDegrees * math.pi / 180);
          canvas.translate(-center.dx, -center.dy);
          canvas.drawPath(mark.path, Paint()..color = color.withValues(alpha: color.a * p.clamp(0.0, 1.0)));
          canvas.restore();
        case _Caret(:final width):
          canvas.drawPath(
            mark.path,
            Paint()
              ..color = color.withValues(alpha: color.a * p.clamp(0.0, 1.0) * blink)
              ..style = PaintingStyle.stroke
              ..strokeWidth = width
              ..strokeCap = StrokeCap.round,
          );
      }
    }
    canvas.restore();
  }

  /// The first [fraction] of [path]'s length.
  static Path _drawn(Path path, double fraction) {
    if (fraction >= 1) return path;
    final metrics = path.computeMetrics().toList();
    final total = metrics.fold<double>(0, (sum, m) => sum + m.length);
    var left = total * fraction;
    final out = Path();
    for (final ui.PathMetric m in metrics) {
      if (left <= 0) break;
      out.addPath(m.extractPath(0, math.min(left, m.length)), Offset.zero);
      left -= m.length;
    }
    return out;
  }

  @override
  bool shouldRepaint(_DoodlePainter old) =>
      old.marks != marks || old.blink != blink || old.scheme != scheme || !_same(old.progress, progress);

  static bool _same(List<double> a, List<double> b) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }
}

/// No notes on this song (NoteEmptyState): its doodle over a plain label — no
/// prompt. The doodle drops out when the region is too short for it (keyboard
/// up on a short window); the label alone still fits
/// [NowPlayingNoteTokens.emptyMinHeight].
class NoteEmptyState extends StatelessWidget {
  const NoteEmptyState({super.key, required this.doodle, required this.label});

  final NoteDoodle doodle;
  final String label;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = math.min(constraints.maxWidth, NowPlayingNoteTokens.doodleMaxWidth);
        final height = width * noteDoodleViewBoxHeight / noteDoodleViewBoxWidth;
        final showDoodle = constraints.maxHeight >= height + NowPlayingNoteTokens.emptyMinHeight;
        return Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: NowPlayingNoteTokens.emptyMinHeight),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                if (showDoodle) ...[
                  SizedBox(
                    width: width,
                    height: height,
                    child: NoteEmptyDoodle(doodle: doodle),
                  ),
                  const SizedBox(height: NowPlayingNoteTokens.doodleGap),
                ],
                Text(label, style: theme.textTheme.titleSmall!.copyWith(color: theme.colorScheme.onSurfaceVariant)),
              ],
            ),
          ),
        );
      },
    );
  }
}
