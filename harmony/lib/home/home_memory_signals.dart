/// Home's memory signals (Android HomeViewModel.loadMemorySignals): one
/// candidate build per account and write, shared by Jump Back In's signal
/// cards (the rating / review 1×2 and the noted-track 1×2) and Rediscover.
/// The covers stay the view model's; the screen seats these among them
/// ([withJbiSignals]).
///
/// Only albums the user wrote on, or played in Yoin long enough ago to come
/// back, are looked at: Home never fetches the whole activity log's albums.
/// A memory card's headline is the Memory's written title, resolved the way
/// Memories resolves it: the user's own, else Yoin's AI title from the cache
/// (Home never asks the model), else none (the album name is already the
/// card's title).
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/memory/album_memory_candidate.dart';
import '../data/memory/memory_copy_cache.dart';
import '../data/memory/memory_store.dart';
import '../data/model/model.dart';
import '../detail/detail_cache.dart';
import '../memories/copy/memory_copy.dart';
import '../memories/home_memory_pill.dart';
import '../playback/local_ratings.dart';
import 'home_selection.dart';
import 'home_ui_state.dart';
import 'home_view_model.dart';
import 'rediscover_selection.dart';

/// Memory 1×2s past the first, for the longer shelves.
const int jbiExtraMemoryCards = 2;

/// Note 1×2s past the first.
const int jbiExtraNoteCards = 2;

/// Notes read for the note cards (Android JBI_NOTE_READ_LIMIT).
const int jbiNoteReadLimit = 12;

const String memoryCardPrefix = 'grid-memory:';
const String noteCardPrefix = 'grid-note:';

@immutable
final class HomeMemorySignals {
  const HomeMemorySignals({
    this.scope = '',
    this.memoryCards = const [],
    this.noteCards = const [],
    this.rediscover = const [],
    this.resolved = false,
  });

  /// `provider|profileId` the signals were built for; matched against the feed's.
  final String scope;

  /// Memory 1×2s, the default shelf's first.
  final List<HomeWidgetCard> memoryCards;

  /// Noted-track 1×2s, the newest first.
  final List<HomeWidgetCard> noteCards;
  final List<HomeRediscoverItem> rediscover;
  final bool resolved;

  /// The wide cards in rank order: the memory, the note, then a memory, a note…
  List<HomeWidgetCard> get wideCards => [
        ...memoryCards.take(1),
        ...noteCards.take(1),
        ...interleave([memoryCards.skip(1).toList(), noteCards.skip(1).toList()]),
      ];
}

/// What the memory 1×2's line under the score rests on: a date for a
/// reviewed or hand-scored album (the record's last touch), the "Based on
/// rated/total tracks" count for an auto-averaged score.
({int? basisDate, bool trackBasis}) memoryCardBasis({
  required bool hasReview,
  required bool hasManualRating,
  required int ratedTrackCount,
  required int totalTracks,
  required int? lastTouchedAt,
}) =>
    (
      basisDate: hasReview || hasManualRating ? lastTouchedAt : null,
      trackBasis: !hasReview && !hasManualRating && ratedTrackCount > 0 && totalTracks > 0,
    );

bool _qualifies(AlbumMemoryCandidate c) => c.hasAlbumReview || c.albumRating != null || c.averageSongRating != null;

/// The grid's memory 1×2 (pickJbiMemoryCandidate): the strongest memory with
/// a review, else with any score, preferring another album than the header's
/// latest memory; [preferRawAlbumId] (the album already shown) wins while it
/// still qualifies.
AlbumMemoryCandidate? pickJbiMemoryCandidate(List<AlbumMemoryCandidate> candidates, {String? avoidRawAlbumId, String? preferRawAlbumId}) {
  AlbumMemoryCandidate? pick(Iterable<AlbumMemoryCandidate> pool) =>
      pool.where((c) => c.hasAlbumReview).firstOrNull ?? pool.where((c) => c.albumRating != null || c.averageSongRating != null).firstOrNull;
  if (preferRawAlbumId != null) {
    final kept = candidates.where((c) => c.albumId.rawId == preferRawAlbumId && _qualifies(c)).firstOrNull;
    if (kept != null) return kept;
  }
  return pick(candidates.where((c) => c.albumId.rawId != avoidRawAlbumId)) ?? pick(candidates);
}

/// Up to [count] memory 1×2s: the pick first, then the other qualifying
/// albums — reviewed before rated — never the header's latest among the extras.
List<AlbumMemoryCandidate> pickJbiMemoryCandidates(
  List<AlbumMemoryCandidate> candidates, {
  String? avoidRawAlbumId,
  String? preferRawAlbumId,
  int count = 1,
}) {
  final first = pickJbiMemoryCandidate(candidates, avoidRawAlbumId: avoidRawAlbumId, preferRawAlbumId: preferRawAlbumId);
  if (first == null) return const [];
  if (count <= 1) return [first];
  final seen = <String>{first.albumId.rawId};
  final rest = [
    for (final c in candidates)
      if (c.albumId.rawId != avoidRawAlbumId && _qualifies(c) && seen.add(c.albumId.rawId)) c,
  ];
  return [first, ...rest.where((c) => c.hasAlbumReview), ...rest.where((c) => !c.hasAlbumReview)].take(count).toList();
}

/// The rated / reviewed album 1×2 for [c]; a tap opens Memories on it.
/// [title]: the Memory's written title (the user's own), its headline; none,
/// no headline (the album name is already the card's title).
HomeWidgetCard memoryWidgetCard(AlbumMemoryCandidate c, {String? title}) {
  final manual = (c.albumRating ?? 0) > 0 ? c.albumRating : null;
  final rating = manual ?? c.averageSongRating;
  final basis = memoryCardBasis(
    hasReview: c.hasAlbumReview,
    hasManualRating: manual != null,
    ratedTrackCount: c.ratedTrackCount,
    totalTracks: c.totalTracks,
    lastTouchedAt: c.lastPlayedAt ?? c.firstPlayedAt,
  );
  return HomeWidgetCard(
    stableId: '$memoryCardPrefix${c.albumId.provider}:${c.albumId.rawId}',
    kind: HomeCardKind.album,
    title: c.albumName,
    subtitle: c.artistName ?? '',
    cover: c.coverArt,
    target: HomeOpenMemory(c.sessionId),
    signal: JbiSignal(
      scoreText: rating == null ? null : MemoryScores.text(rating),
      basisDateMs: basis.basisDate,
      basisTracks: basis.trackBasis ? (c.ratedTrackCount, c.totalTracks) : null,
      comment: title == null || title.trim().isEmpty ? null : title.trim(),
      commentIsHeadline: true,
    ),
  );
}

/// The noted-track 1×2: the note's song, its rating, the note's day and
/// text. A tap plays the song alone.
HomeWidgetCard noteWidgetCard(SongNote note, {double? rating}) {
  final track = Track(
    id: note.trackId,
    title: note.title,
    artist: note.artist.trim().isEmpty ? null : note.artist,
    albumId: note.albumId,
  );
  return HomeWidgetCard(
    stableId: '$noteCardPrefix${note.id}',
    kind: HomeCardKind.song,
    title: note.title,
    subtitle: note.artist,
    cover: trackCover(track),
    target: HomePlaySong(track),
    signal: JbiSignal(
      scoreText: rating == null ? null : MemoryScores.text(rating),
      basisDateMs: note.updatedAt,
      comment: note.content.trim(),
    ),
  );
}

/// The newest notes, one per song (none blank), at most [limit].
List<SongNote> pickJbiNotes(List<SongNote> newestFirst, {int limit = 1 + jbiExtraNoteCards}) {
  final seen = <MediaId>{};
  return [
    for (final n in newestFirst.take(jbiNoteReadLimit))
      if (!n.isBlank && seen.add(n.trackId)) n,
  ].take(limit).toList();
}

/// Jump Back In with its signal cards (buildWidgetGrid's wide half): the
/// wide cards first, then the covers minus the albums and songs a wide card
/// already shows.
List<HomeWidgetCard> withJbiSignals(List<HomeWidgetCard> covers, List<HomeWidgetCard> wide) {
  if (wide.isEmpty) return covers;
  final shown = <MediaId>{};
  for (final w in wide) {
    switch (w.target) {
      case HomePlaySong(:final track):
        shown.add(track.id);
      case HomeOpenMemory():
        final raw = w.stableId.substring(memoryCardPrefix.length);
        final colon = raw.indexOf(':');
        if (colon > 0) shown.add(MediaId(raw.substring(0, colon), raw.substring(colon + 1)));
      default:
        break;
    }
  }
  return [
    ...wide,
    for (final c in covers)
      if (!switch (c.target) {
        HomeOpenAlbum(:final album) => shown.contains(album.id),
        HomePlaySong(:final track) => shown.contains(track.id),
        _ => false,
      })
        c,
  ];
}

/// The album a memory card points at, from its stable id.
String? memoryCardRawAlbumId(HomeWidgetCard card) {
  if (!card.stableId.startsWith(memoryCardPrefix)) return null;
  final raw = card.stableId.substring(memoryCardPrefix.length);
  final colon = raw.indexOf(':');
  return colon < 0 ? null : raw.substring(colon + 1);
}

/// Builds [HomeMemorySignals] for the active account, again after every
/// Memory write (plays included: they move Rediscover) and track rating.
class HomeMemorySignalsNotifier extends Notifier<HomeMemorySignals> {
  StreamSubscription<MemoryStoreChange>? _changes;
  int _generation = 0;
  Timer? _debounce;

  @override
  HomeMemorySignals build() {
    final source = ref.watch(activeSourceProvider).value;
    final store = ref.watch(memoryStoreProvider).value;
    ref.listen(localRatingsProvider, (prev, next) {
      if (prev != null && !identical(prev, next)) _schedule(source?.provider, store);
    });
    _changes?.cancel();
    _changes = store?.changes.listen((_) => _schedule(source?.provider, store));
    // Cloud sync brought Yoin's AI titles from another device.
    ref.listen(memoryCopySyncRevisionProvider, (_, _) => _schedule(source?.provider, store));
    ref.onDispose(() {
      _changes?.cancel();
      _debounce?.cancel();
    });
    scheduleMicrotask(() => _load(source?.provider, store));
    return const HomeMemorySignals();
  }

  /// Yoin's AI title as the deck last wrote it; null on a miss or a failed read.
  Future<String?> _cachedAiTitle(String profileId, MediaId albumId) async {
    try {
      return await MemoryCopyCache.shared(ref.read(keyValueStoreProvider), profileId).title(albumId);
    } catch (_) {
      return null;
    }
  }

  void _schedule(String? provider, MemoryStore? store) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 300), () => _load(provider, store));
  }

  Future<void> _load(String? provider, MemoryStore? store) async {
    final generation = ++_generation;
    if (provider == null || store == null) return;
    try {
      final now = ref.read(homeClockProvider)().millisecondsSinceEpoch;
      final notes = await store.songNotes();
      final ratings = await store.albumRatings();
      final titles = await store.titles();
      final kv = ref.read(keyValueStoreProvider);
      final trackRatings = {...await LocalRatingStore(kv).read(store.profileId), ...ref.read(localRatingsProvider)};
      final notedAlbums = {for (final n in notes) if (!n.isBlank && n.albumId != null) n.albumId!};
      final plays = [
        for (final p in await store.albumPlays())
          // Written on (the memory cards), or away long enough to come back (Rediscover).
          if (ratings.containsKey(p.albumId) || notedAlbums.contains(p.albumId) || now - p.lastPlayedAt >= rediscoverAwayMs) p,
      ];
      final inputs = AlbumMemoryInputs(
        profileId: store.profileId,
        provider: provider,
        albumRatings: ratings,
        songNotes: notes,
        albumPlays: plays,
        trackRatings: trackRatings,
      );
      final pool = await buildAlbumMemoryCandidates(inputs, loadAlbum: ref.read(detailCacheProvider).album, includeIneligible: true);
      if (!ref.mounted || generation != _generation) return;
      final eligible = pool.where((c) => c.isMemoryEligible).toList();
      final latest = pickLatestMemory(eligible);
      final previous = state.memoryCards.isEmpty ? null : memoryCardRawAlbumId(state.memoryCards.first);
      final memories = pickJbiMemoryCandidates(
        eligible,
        avoidRawAlbumId: latest?.albumId.rawId,
        preferRawAlbumId: previous,
        count: 1 + jbiExtraMemoryCards,
      );
      final noteCards = [
        for (final n in pickJbiNotes(notes)) noteWidgetCard(n, rating: trackRatings[n.trackId]?.rating),
      ];
      final exclude = {
        if (memories.isNotEmpty) memories.first.albumId.rawId,
        if (latest != null) latest.albumId.rawId,
      };
      final memoryCards = [
        for (final c in memories)
          memoryWidgetCard(c, title: titles[c.albumId]?.title ?? await _cachedAiTitle(store.profileId, c.albumId)),
      ];
      if (!ref.mounted || generation != _generation) return;
      state = HomeMemorySignals(
        scope: '$provider|${store.profileId}',
        memoryCards: memoryCards,
        noteCards: noteCards,
        rediscover: buildRediscover(pool, nowMs: now, excludeRawAlbumIds: exclude),
        resolved: true,
      );
    } catch (e) {
      debugPrint('YoinHome: memory signals failed: ${e.runtimeType}');
    }
  }
}

final homeMemorySignalsProvider = NotifierProvider<HomeMemorySignalsNotifier, HomeMemorySignals>(HomeMemorySignalsNotifier.new);
