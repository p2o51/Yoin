/// Home's Memories pill (Android ui/home/HomeMemoryPill, the HeaderPill
/// variant): the header's way into Memories, saying what is in the attic —
/// the most recently WRITTEN memory's cover and score, and how many notes the
/// account has kept — with a ⌄ at its end. Its outline is the score's
/// provenance, the emblem's grammar told with a line: a tonal wash = your
/// album rating, a solid line = the average of your track ratings, a dashed
/// line = nothing scored yet (or nothing kept yet). A tap opens Memories on
/// that memory.
///
/// Only albums the user wrote on are looked at (album scores, reviews,
/// notes): Home never fetches the whole activity log's albums for it.
library;

import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../data/memory/album_memory_candidate.dart';
import '../data/memory/memory_store.dart';
import '../data/memory/track_album_links.dart';
import '../detail/detail_cache.dart';
import '../nowplaying/haptics.dart';
import '../playback/local_ratings.dart';
import '../theme/motion.dart';
import '../theme/theme.dart';
import '../ui/component/cover_art.dart';
import '../ui/component/desktop_input.dart';
import 'copy/memory_copy.dart';
import 'memories_reveal_scope.dart';

enum MemoryPillScore { album, average, none }

@immutable
final class HomeMemoryPillState {
  const HomeMemoryPillState({
    this.latest,
    this.noteCount = 0,
    this.resolved = false,
    this.recall = const [],
    this.scope = '',
    this.newsKey = '',
  });

  final AlbumMemoryCandidate? latest;
  final int noteCount;

  /// The memories the bubble's idle reminder brings back ([pickMemoryRecall]).
  final List<AlbumMemoryCandidate> recall;

  /// Whose memories these are (`provider|profileId`): the bubble remembers what it told per scope.
  final String scope;

  /// What "something new" is keyed on ([memoryNewsKey]); empty = never news.
  final String newsKey;

  /// A latest memory or kept notes: the bubble has a memory to talk about.
  bool get hasSomethingToSay => latest != null || noteCount > 0;

  /// False until the first read: the header keeps the pill's slot empty.
  final bool resolved;

  MemoryPillScore get scoreKind => latest?.albumRating != null
      ? MemoryPillScore.album
      : (latest?.averageSongRating != null ? MemoryPillScore.average : MemoryPillScore.none);

  String? get scoreText {
    final c = latest;
    final s = c?.albumRating ?? c?.averageSongRating;
    return s == null ? null : MemoryScores.text(s);
  }
}

/// The most recently written memory (plays and visits only break ties); the
/// first of equal keys wins, and candidates arrive strength-ranked.
AlbumMemoryCandidate? pickLatestMemory(List<AlbumMemoryCandidate> candidates) {
  AlbumMemoryCandidate? best;
  var bestKey = -1;
  for (final c in candidates) {
    final key = c.lastWrittenAt ?? c.lastPlayedAt ?? c.firstPlayedAt ?? 0;
    if (key > bestKey) {
      best = c;
      bestKey = key;
    }
  }
  return best;
}

/// The memories the bubble's idle reminder brings back (Android
/// pickMemoryRecall): every one but [latest] (news tells that one), newest
/// written first — plays only, never written on, after them — one per
/// album, at most [memoryRecallLimit].
List<AlbumMemoryCandidate> pickMemoryRecall(List<AlbumMemoryCandidate> candidates, AlbumMemoryCandidate? latest) {
  String album(AlbumMemoryCandidate c) => '${c.albumId.provider}|${c.albumId.rawId}';
  final latestAlbum = latest == null ? null : album(latest);
  final seen = <String>{};
  final kept = [
    for (final c in candidates)
      if (album(c) != latestAlbum && seen.add(album(c))) c,
  ];
  int when(AlbumMemoryCandidate c) => c.lastWrittenAt ?? c.lastPlayedAt ?? c.firstPlayedAt ?? 0;
  // List.sort isn't stable: ties keep the candidates' own (strength) order by index.
  final order = {for (var i = 0; i < kept.length; i++) kept[i]: i};
  kept.sort((a, b) {
    final written = (b.lastWrittenAt != null ? 1 : 0).compareTo(a.lastWrittenAt != null ? 1 : 0);
    if (written != 0) return written;
    final newer = when(b).compareTo(when(a));
    return newer != 0 ? newer : order[a]!.compareTo(order[b]!);
  });
  return kept.take(memoryRecallLimit).toList();
}

const int memoryRecallLimit = 8;

/// What "something new" is keyed on (Android memoryNewsKey): the newest write
/// on any memory and the note count, as "time#count". Plays and visits never
/// change it; the bubble compares the parts as numbers, so a deletion that
/// moves them back is not news.
String memoryNewsKey(AlbumMemoryCandidate? latest, int noteCount) => '${latest?.lastWrittenAt ?? 0}#$noteCount';

class HomeMemoryPillNotifier extends Notifier<HomeMemoryPillState> {
  StreamSubscription<MemoryStoreChange>? _changes;
  StreamSubscription<void>? _linkChanges;
  int _generation = 0;

  @override
  HomeMemoryPillState build() {
    final source = ref.watch(activeSourceProvider).value;
    final store = ref.watch(memoryStoreProvider).value;
    ref.listen(localRatingsProvider, (prev, next) {
      if (prev != null && !identical(prev, next)) _load(source?.provider, store);
    });
    _changes?.cancel();
    _changes = store?.changes.listen((c) {
      if (c != MemoryStoreChange.plays) _load(source?.provider, store);
    });
    // A song's album learned: its notes and scores may make that album the latest memory.
    _linkChanges?.cancel();
    _linkChanges = store == null
        ? null
        : TrackAlbumLinkStore.shared(
            ref.read(keyValueStoreProvider),
            store.profileId,
          ).changes.listen((_) => _load(source?.provider, store));
    ref.onDispose(() {
      _changes?.cancel();
      _linkChanges?.cancel();
    });
    scheduleMicrotask(() => _load(source?.provider, store));
    return const HomeMemoryPillState();
  }

  Future<void> _load(String? provider, MemoryStore? store) async {
    final generation = ++_generation;
    if (provider == null || store == null) return;
    try {
      final notes = await store.songNotes();
      final trackAlbums = await loadTrackAlbums(
        store,
        TrackAlbumLinkStore.shared(ref.read(keyValueStoreProvider), store.profileId),
      );
      final notedAlbums = {for (final n in notes) ?(n.albumId ?? trackAlbums[n.trackId])};
      final inputs = AlbumMemoryInputs(
        profileId: store.profileId,
        provider: provider,
        albumRatings: await store.albumRatings(),
        songNotes: notes,
        albumPlays: [
          for (final p in await store.albumPlays())
            if (await store.albumRating(p.albumId) != null || notedAlbums.contains(p.albumId)) p,
        ],
        trackRatings: ref.read(localRatingsProvider),
        trackAlbums: trackAlbums,
      );
      final pool = await buildAlbumMemoryCandidates(inputs, loadAlbum: ref.read(detailCacheProvider).album);
      if (!ref.mounted || generation != _generation) return;
      final latest = pickLatestMemory(pool);
      final noteCount = notes.where((n) => !n.isBlank).length;
      state = HomeMemoryPillState(
        latest: latest,
        noteCount: noteCount,
        resolved: true,
        recall: pickMemoryRecall(pool, latest),
        scope: '$provider|${store.profileId}',
        newsKey: memoryNewsKey(latest, noteCount),
      );
    } catch (e) {
      debugPrint('YoinMemories: pill failed: ${e.runtimeType}');
    }
  }
}

final homeMemoryPillProvider = NotifierProvider<HomeMemoryPillNotifier, HomeMemoryPillState>(
  HomeMemoryPillNotifier.new,
);

/// Home strings for the pill, from Android strings_home.xml (key in the comment).
class _PillStrings {
  const _PillStrings(this.zh);
  final bool zh;
  String get label => 'Memories'; // home_memories_label
  String notes(int n) => n > 999
      ? (zh ? '999+ 条笔记' : '999+ notes') // home_notes_capped
      : (zh ? '$n 条笔记' : (n == 1 ? '$n note' : '$n notes')); // home_notes
  String get cdEmpty => zh ? 'Memories，空白' : 'Memories, empty'; // home_cd_memories_empty
  String cdNotes(String notes) => zh ? 'Memories，$notes' : 'Memories, $notes'; // home_cd_memories_notes

  /// home_cd_pill_{rating,average,unrated}[_artist][_notes].
  String cdLatest({
    required String album,
    String? artist,
    required MemoryPillScore kind,
    String? score,
    String? notes,
  }) {
    final head = zh
        ? 'Memories。最新：$album${artist == null ? '' : '，$artist'}。'
        : 'Memories. Latest: $album${artist == null ? '' : ' by $artist'}.';
    final scorePart = switch (kind) {
      MemoryPillScore.album => zh ? '你的专辑评分 $score。' : ' Your album rating $score.',
      MemoryPillScore.average => zh ? '曲目均分 $score。' : ' Track average $score.',
      MemoryPillScore.none => zh ? '尚未评分。' : ' Not rated yet.',
    };
    final tail = notes == null ? '' : (zh ? '$notes。' : ' $notes.');
    return '$head$scorePart$tail';
  }
}

class HomeMemoryPill extends ConsumerStatefulWidget {
  const HomeMemoryPill({super.key});

  @override
  ConsumerState<HomeMemoryPill> createState() => _HomeMemoryPillState();
}

class _HomeMemoryPillState extends ConsumerState<HomeMemoryPill> with TickerProviderStateMixin {
  /// The pill unrolls from its ⌄ once resolved (spatial), and fades in (effects).
  late final SpringValue _shown = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);
  late final SpringValue _alpha = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);

  @override
  void dispose() {
    _shown.dispose();
    _alpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final pill = ref.watch(homeMemoryPillProvider);
    final colors = Theme.of(context).colorScheme;
    final strings = _PillStrings(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');
    if (pill.resolved) {
      if (MediaQuery.disableAnimationsOf(context)) {
        _shown.snapTo(1);
      } else {
        _shown.animateTo(1);
      }
      _alpha.animateTo(1);
    }
    final latest = pill.latest;
    final notes = pill.noteCount > 0 ? strings.notes(pill.noteCount) : null;
    final kind = pill.scoreKind;
    final label = latest == null
        ? (notes == null ? strings.cdEmpty : strings.cdNotes(notes))
        : strings.cdLatest(
            album: latest.albumName,
            artist: latest.artistName,
            kind: kind,
            score: pill.scoreText,
            notes: notes,
          );
    final ink = colors.onSurface;
    final textStyle = gsf(14, weight: 600, height: 1, letterSpacing: 0).copyWith(color: ink);
    final quiet = gsf(13, weight: 500, height: 1, letterSpacing: 0).copyWith(color: colors.onSurfaceVariant);
    void open() {
      YoinHaptics.click();
      ref.read(memoriesRevealHandleProvider).open(focus: latest?.sessionId);
    }

    return Semantics(
      button: true,
      label: label,
      excludeSemantics: true,
      child: AnimatedBuilder(
        animation: Listenable.merge([_shown.animation, _alpha.animation]),
        builder: (context, child) => Opacity(
          opacity: _alpha.value.clamp(0.0, 1.0),
          child: Transform.scale(
            scale: 0.85 + 0.15 * _shown.value.clamp(0.0, 1.2),
            alignment: Alignment.centerRight,
            child: child,
          ),
        ),
        // Mouse / keyboard: hover layer, click cursor, focus ring, Enter.
        child: YoinHover(
          onTap: open,
          focusStadium: true,
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: open,
            child: CustomPaint(
              painter: _PillOutline(
                kind: latest == null ? MemoryPillScore.none : kind,
                color: colors.outline.withValues(alpha: 0.6),
                wash: colors.secondaryContainer,
              ),
              child: SizedBox(
                height: 40,
                child: Padding(
                  padding: EdgeInsetsDirectional.only(start: latest == null ? 14 : 6, end: 8),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      if (latest != null) ...[
                        CoverArt(cover: latest.coverArt, size: 28, radius: 4, requestSize: 96),
                        if (pill.scoreText != null) ...[
                          const SizedBox(width: 10),
                          Text(
                            pill.scoreText!,
                            style: textStyle.copyWith(fontFeatures: const [FontFeature.tabularFigures()]),
                          ),
                        ],
                      ] else
                        Text(strings.label, style: textStyle),
                      if (notes != null) ...[
                        const SizedBox(width: 10),
                        Flexible(
                          child: Text(notes, style: quiet, maxLines: 1, overflow: TextOverflow.fade, softWrap: false),
                        ),
                      ],
                      const SizedBox(width: 4),
                      Icon(YoinSymbols.chevronDown, size: 20, color: colors.onSurfaceVariant),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _PillOutline extends CustomPainter {
  _PillOutline({required this.kind, required this.color, required this.wash});

  final MemoryPillScore kind;
  final Color color;
  final Color wash;

  @override
  void paint(Canvas canvas, Size size) {
    final r = RRect.fromRectAndRadius(Offset.zero & size, Radius.circular(size.height / 2));
    switch (kind) {
      case MemoryPillScore.album:
        canvas.drawRRect(r, Paint()..color = wash);
      case MemoryPillScore.average:
        canvas.drawRRect(
          r.deflate(0.5),
          Paint()
            ..style = PaintingStyle.stroke
            ..strokeWidth = 1
            ..color = color,
        );
      case MemoryPillScore.none:
        final path = Path()..addRRect(r.deflate(0.5));
        final paint = Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1
          ..color = color;
        for (final metric in path.computeMetrics()) {
          var d = 0.0;
          while (d < metric.length) {
            canvas.drawPath(metric.extractPath(d, math.min(d + 4, metric.length)), paint);
            d += 7;
          }
        }
    }
  }

  @override
  bool shouldRepaint(_PillOutline old) => old.kind != kind || old.color != color || old.wash != wash;
}
