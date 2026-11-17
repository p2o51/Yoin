/// Album memory marks (Android ui/home/HomeAlbumMemoryMark.kt, owner
/// 2026-10-11, T4: "JBI 往下全是光封面").
///
/// A plain album cover the user kept something on — an album rating, rated
/// tracks, notes — wears a small memory mark on its cover's top-end corner:
/// the Memories pill's cookie sticker, no number, no word, no outline round
/// the cover. Solid = your album rating, a line = your track average, dashed
/// = notes only. Jump Back In's plain album covers, Recently Added's and
/// Recently Played's album cards wear it; songs, playlists and the 1×2 signal
/// cards (which carry their score already) never do, and neither does a
/// Spotify cover (its guidelines forbid overlays on artwork). The data is the
/// memory signals' own candidate build ([HomeMemorySignals.albumMarks]) — no
/// query of its own — so an album outside that build stays bare.
library;

import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/memory/album_memory_candidate.dart';
import '../data/model/model.dart';
import '../memories/copy/memory_copy.dart';
import '../memories/memories_ui_state.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/shapes/cookie_unit.dart';
import 'home_memory_signals.dart';
import 'home_strings.dart';
import 'home_ui_state.dart';
import 'widgets/cover_palette.dart';

/// One album's memory mark. [kind]: albumRating (solid), averageTrackRating
/// (a line) or none (dashed: notes only). [scoreText] ("9.0", the seal's own
/// format; null for none) and [noteCount] are for the screen reader — the
/// cover shows neither.
@immutable
final class HomeAlbumMemoryMark {
  const HomeAlbumMemoryMark({required this.kind, this.scoreText, this.noteCount = 0});

  final MemoryScoreKind kind;
  final String? scoreText;
  final int noteCount;

  @override
  bool operator ==(Object other) =>
      other is HomeAlbumMemoryMark &&
      other.kind == kind &&
      other.scoreText == scoreText &&
      other.noteCount == noteCount;

  @override
  int get hashCode => Object.hash(kind, scoreText, noteCount);
}

/// The mark for [candidate], or null when nothing is kept on it: your album
/// rating first, else the average of your rated tracks (the Memories seal's
/// rule, no coverage gate — the cover shows no number), else its notes.
/// Reviews and plays alone don't count.
HomeAlbumMemoryMark? albumMemoryMarkOf(AlbumMemoryCandidate candidate) {
  final notes = math.max(0, candidate.noteCount);
  final albumRating = (candidate.albumRating ?? 0) > 0 ? candidate.albumRating : null;
  // A 0.0 track average is a score; only no rated track is none.
  final average = candidate.ratedTrackCount > 0 ? candidate.averageSongRating : null;
  if (albumRating != null) {
    return HomeAlbumMemoryMark(
      kind: MemoryScoreKind.albumRating,
      scoreText: MemoryScores.text(albumRating),
      noteCount: notes,
    );
  }
  if (average != null) {
    return HomeAlbumMemoryMark(
      kind: MemoryScoreKind.averageTrackRating,
      scoreText: MemoryScores.text(average),
      noteCount: notes,
    );
  }
  return notes > 0 ? HomeAlbumMemoryMark(kind: MemoryScoreKind.none, noteCount: notes) : null;
}

/// Album → its mark, for every album of [pool] (the memory signals' build,
/// ineligible albums included) that has one. Should one album come back
/// twice, the two merge (Android mergedWith): the stronger kind with its own
/// score, and the most notes either counted.
Map<MediaId, HomeAlbumMemoryMark> homeAlbumMemoryMarks(List<AlbumMemoryCandidate> pool) {
  final marks = <MediaId, HomeAlbumMemoryMark>{};
  for (final candidate in pool) {
    final mark = albumMemoryMarkOf(candidate);
    if (mark == null) continue;
    final was = marks[candidate.albumId];
    marks[candidate.albumId] = was == null ? mark : _merged(was, mark);
  }
  return marks;
}

HomeAlbumMemoryMark _merged(HomeAlbumMemoryMark a, HomeAlbumMemoryMark b) {
  final stronger = _strength(b.kind) > _strength(a.kind) ? b : a;
  return HomeAlbumMemoryMark(
    kind: stronger.kind,
    scoreText: stronger.scoreText,
    noteCount: math.max(a.noteCount, b.noteCount),
  );
}

/// Your album rating outranks your track average, which outranks notes alone.
int _strength(MemoryScoreKind kind) => switch (kind) {
  MemoryScoreKind.albumRating => 2,
  MemoryScoreKind.averageTrackRating => 1,
  MemoryScoreKind.none => 0,
};

/// Whether a cover from [provider] may wear a mark at all. Spotify's design
/// guidelines forbid laying images or text over its artwork, so its covers
/// stay bare.
bool allowsCoverMarks(String provider) => provider != MediaId.providerSpotify;

/// The album whose mark a Jump Back In card wears: plain (1×1) album covers
/// only. Songs, playlists and the 1×2 signal cards never wear one.
MediaId? jbiMarkAlbum(HomeWidgetCard card) {
  if (card.expanded || card.kind != HomeCardKind.album) return null;
  return switch (card.target) {
    HomeOpenAlbum(:final album) when allowsCoverMarks(album.id.provider) => album.id,
    _ => null,
  };
}

/// The album whose mark a shelf's album card (Recently Added, Recently
/// Played) wears.
MediaId? shelfMarkAlbum(Album album) => allowsCoverMarks(album.id.provider) ? album.id : null;

/// The screen reader's words for [mark]: "Your rating 9.0, 3 notes" /
/// "Track average 8.4" / "2 notes".
String albumMemoryMarkDescription(HomeAlbumMemoryMark mark, HomeStrings strings) => [
  if (mark.scoreText case final score?)
    mark.kind == MemoryScoreKind.averageTrackRating
        ? strings.rediscoverCdTrackAverage(score)
        : strings.rediscoverCdYourRating(score),
  if (mark.noteCount > 0) strings.rediscoverNotes(mark.noteCount),
].join(', ');

/// The mark [album]'s cover wears, from Home's memory signals, on its
/// top-end corner (the cover's own footprint, lifted half the disc above its
/// top edge). [coverUrl] / [coverImage]: the cover the ink is read from.
class AlbumMemoryMarkSlot extends ConsumerWidget {
  const AlbumMemoryMarkSlot({super.key, required this.album, this.coverUrl, this.coverImage});

  final MediaId album;
  final String? coverUrl;
  final ImageProvider? coverImage;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final mark = ref.watch(homeMemorySignalsProvider.select((s) => s.albumMarks[album]));
    return AlbumMemoryMarkSticker(mark: mark, coverUrl: coverUrl, coverImage: coverImage);
  }
}

/// The sticker for [mark], inked from its cover's palette: its accent in
/// dark, its base in light (the JBI score rule); until the palette is read,
/// the Memories pill's own ink — tertiary in dark, secondary in light — and
/// the hand-off rides the effects spring. Nothing is laid out for a cover
/// that never had a mark. A mark landing after the cover fades in on the
/// effects spring; one that goes leaves as itself; a change of kind inks in
/// or out (fill, line, dashes closing into a line) on the same spring.
/// Reduced motion snaps.
class AlbumMemoryMarkSticker extends StatefulWidget {
  const AlbumMemoryMarkSticker({super.key, required this.mark, this.coverUrl, this.coverImage});

  final HomeAlbumMemoryMark? mark;
  final String? coverUrl;
  final ImageProvider? coverImage;

  @override
  State<AlbumMemoryMarkSticker> createState() => _AlbumMemoryMarkStickerState();
}

class _AlbumMemoryMarkStickerState extends State<AlbumMemoryMarkSticker> with TickerProviderStateMixin {
  late final SpringValue _appear;
  late final SpringValue _fill;
  late final SpringValue _line;
  late final SpringValue _solidity;

  /// The mark shown: the last one there was, so a leaving mark leaves as itself.
  HomeAlbumMemoryMark? _shown;

  static double _fillOf(MemoryScoreKind k) => k == MemoryScoreKind.albumRating ? 1 : 0;

  static double _lineOf(MemoryScoreKind k) => switch (k) {
    MemoryScoreKind.albumRating => 0,
    MemoryScoreKind.averageTrackRating => 1,
    MemoryScoreKind.none => HomeTokens.memoryMarkNotesAlpha,
  };

  static double _solidityOf(MemoryScoreKind k) => k == MemoryScoreKind.none ? 0 : 1;

  @override
  void initState() {
    super.initState();
    final mark = widget.mark;
    _shown = mark;
    final kind = mark?.kind ?? MemoryScoreKind.none;
    // A cover that comes with its mark shows it at once.
    _appear = SpringValue(vsync: this, initial: mark == null ? 0 : 1, spring: YoinSprings.defaultEffects);
    _fill = SpringValue(vsync: this, initial: _fillOf(kind), spring: YoinSprings.defaultEffects);
    _line = SpringValue(vsync: this, initial: _lineOf(kind), spring: YoinSprings.defaultEffects);
    _solidity = SpringValue(vsync: this, initial: _solidityOf(kind), spring: YoinSprings.defaultEffects);
  }

  @override
  void didUpdateWidget(AlbumMemoryMarkSticker oldWidget) {
    super.didUpdateWidget(oldWidget);
    final mark = widget.mark;
    if (mark == oldWidget.mark) return;
    final reduced = MediaQuery.disableAnimationsOf(context);
    void go(SpringValue v, double to) => reduced ? v.snapTo(to) : v.animateTo(to);
    if (mark != null) {
      final kind = mark.kind;
      if (_shown == null) {
        // The first mark of this cover: its ink starts where it lands, only the alpha moves.
        _fill.snapTo(_fillOf(kind));
        _line.snapTo(_lineOf(kind));
        _solidity.snapTo(_solidityOf(kind));
      } else {
        go(_fill, _fillOf(kind));
        go(_line, _lineOf(kind));
        go(_solidity, _solidityOf(kind));
      }
      _shown = mark;
    }
    go(_appear, mark == null ? 0 : 1);
  }

  @override
  void dispose() {
    _appear.dispose();
    _fill.dispose();
    _line.dispose();
    _solidity.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final shown = _shown;
    if (shown == null) return const SizedBox.shrink();
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final dark = theme.brightness == Brightness.dark;
    final label = widget.mark == null ? null : albumMemoryMarkDescription(shown, HomeStrings.of(context));
    return Semantics(
      container: true,
      label: label,
      excludeSemantics: true,
      child: SizedBox.square(
        dimension: HomeTokens.memoryMarkBacking,
        child: BackdropColorBuilder(
          cacheKey: widget.coverUrl,
          image: widget.coverImage,
          fallback: dark ? colors.tertiary : colors.secondary,
          accent: dark,
          builder: (context, ink, _) => CustomPaint(
            painter: AlbumMemoryMarkPainter(
              ink: ink,
              backing: colors.surface,
              appear: _appear.animation,
              fill: _fill.animation,
              line: _line.animation,
              solidity: _solidity.animation,
            ),
          ),
        ),
      ),
    );
  }
}

/// The disc and its cookie: the page colour behind, the cookie [fill]ed
/// with [ink], its outline at [line] alpha, dashed until [solidity] closes
/// the gaps (Android Modifier.dashedOutline: whole dashes round the loop,
/// each centred on its slot). Everything × [appear].
class AlbumMemoryMarkPainter extends CustomPainter {
  AlbumMemoryMarkPainter({
    required this.ink,
    required this.backing,
    required this.appear,
    required this.fill,
    required this.line,
    required this.solidity,
  }) : super(repaint: Listenable.merge([appear, fill, line, solidity]));

  final Color ink;
  final Color backing;
  final Animation<double> appear;
  final Animation<double> fill;
  final Animation<double> line;
  final Animation<double> solidity;

  @override
  void paint(Canvas canvas, Size size) {
    final alpha = appear.value.clamp(0.0, 1.0);
    if (alpha <= 0) return;
    final centre = size.center(Offset.zero);
    canvas.drawCircle(centre, size.shortestSide / 2, Paint()..color = backing.withValues(alpha: backing.a * alpha));
    const side = HomeTokens.memoryMarkCookie;
    final origin = centre - const Offset(side / 2, side / 2);
    final f = fill.value.clamp(0.0, 1.0);
    if (f > 0) {
      canvas.drawPath(
        _cookie(side, origin),
        Paint()
          ..isAntiAlias = true
          ..color = ink.withValues(alpha: ink.a * f * alpha),
      );
    }
    final l = line.value.clamp(0.0, 1.0);
    if (l > 0) {
      const stroke = HomeTokens.memoryMarkLine;
      final outline = _cookie(side - stroke, origin + const Offset(stroke / 2, stroke / 2));
      final paint = Paint()
        ..isAntiAlias = true
        ..style = PaintingStyle.stroke
        ..strokeWidth = stroke
        ..color = ink.withValues(alpha: ink.a * l * alpha);
      for (final dash in dashedRuns(outline, solidity.value)) {
        canvas.drawPath(dash, paint);
      }
    }
  }

  static Path _cookie(double side, Offset origin) {
    final m = Float64List(16)
      ..[0] = side
      ..[5] = side
      ..[10] = 1
      ..[12] = origin.dx
      ..[13] = origin.dy
      ..[15] = 1;
    return CookieUnit.path.transform(m);
  }

  @override
  bool shouldRepaint(AlbumMemoryMarkPainter old) =>
      old.ink != ink ||
      old.backing != backing ||
      old.appear != appear ||
      old.fill != fill ||
      old.line != line ||
      old.solidity != solidity;
}

/// [path]'s outline as whole dashes of [dash] with [gap] between, stretched
/// to divide the loop, each centred on its slot; [solidity] 0..1 grows the
/// dashes into the gaps (period unchanged) until the loop is one line. A
/// gap under half a pixel is the whole outline.
@visibleForTesting
List<Path> dashedRuns(
  Path path,
  double solidity, {
  double dash = HomeTokens.memoryMarkDash,
  double gap = HomeTokens.memoryMarkGap,
}) {
  final out = <Path>[];
  for (final metric in path.computeMetrics()) {
    final perimeter = metric.length;
    if (perimeter <= 0) continue;
    final periods = math.max(1, (perimeter / (dash + gap)).round());
    final period = perimeter / periods;
    final share = dash / (dash + gap);
    final s = solidity.clamp(0.0, 1.0);
    final on = period * (share + (1 - share) * s);
    final off = period - on;
    if (off < 0.5) {
      out.add(metric.extractPath(0, perimeter));
      continue;
    }
    for (var i = 0; i < periods; i++) {
      final start = i * period + off / 2;
      final end = start + on;
      if (end <= perimeter) {
        out.add(metric.extractPath(start, end));
      } else {
        // The last dash runs past the loop's start: it wraps.
        out
          ..add(metric.extractPath(start, perimeter))
          ..add(metric.extractPath(0, end - perimeter));
      }
    }
  }
  return out;
}
