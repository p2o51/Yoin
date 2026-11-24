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
import '../data/memory/track_album_links.dart';
import '../data/model/model.dart';
import '../data/notes/note_markup.dart';
import '../detail/detail_cache.dart';
import '../memories/copy/memory_copy.dart';
import '../memories/home_memory_pill.dart';
import '../memories/track_album_linker.dart';
import '../playback/local_ratings.dart';
import 'home_album_memory_mark.dart';
import 'home_selection.dart';
import 'home_ui_state.dart';
import 'home_view_model.dart';
import 'jbi_memory_turn.dart';
import 'rediscover_selection.dart';

/// Memory 1×2s past the first, for the longer shelves.
const int jbiExtraMemoryCards = 2;

/// A memory played this recently sits out its turn while others remain (Android JBI_RECENT_PLAY_MS).
const int jbiRecentPlayMs = 2 * 60 * 60 * 1000;

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
    this.albumMarks = const {},
    this.resolved = false,
  });

  /// `provider|profileId` the signals were built for; matched against the feed's.
  final String scope;

  /// Memory 1×2s, the default shelf's first.
  final List<HomeWidgetCard> memoryCards;

  /// Noted-track 1×2s, the newest first.
  final List<HomeWidgetCard> noteCards;
  final List<HomeRediscoverItem> rediscover;

  /// Every album of the build the user kept something on, and the mark its
  /// plain cover wears (home_album_memory_mark.dart).
  final Map<MediaId, HomeAlbumMemoryMark> albumMarks;
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

/// The memories Jump Back In's 1×2s show, in their fixed slots
/// (HomeViewModel.kt pickJbiMemoryCandidates): every memory in [candidates]
/// takes a turn — scored or not (owner 2026-11-20) — the next ones each new
/// [turn] (jbi_memory_turn.dart), the same within one. The order is a deck
/// shuffled anew each round, so every memory comes up once a round and no
/// round runs like the last. Never the header's latest memory
/// ([avoidRawAlbumId]) nor an album played since [recentlyPlayedBefore] while
/// others remain; the album already shown ([preferRawAlbumId]) stays first
/// while it is a memory, so writing on it doesn't reshuffle the grid.
List<AlbumMemoryCandidate> pickJbiMemoryCandidates(
  List<AlbumMemoryCandidate> candidates, {
  String? avoidRawAlbumId,
  String? preferRawAlbumId,
  int count = 1,
  int turn = 0,
  int? recentlyPlayedBefore,
}) {
  final seen = <String>{};
  final all = [
    for (final c in candidates)
      if (seen.add(c.albumId.rawId)) c,
  ]..sort((a, b) => a.albumId.rawId.compareTo(b.albumId.rawId));
  if (all.isEmpty || count <= 0) return const [];
  final fresh = [
    for (final c in all)
      if (c.albumId.rawId != avoidRawAlbumId &&
          !(recentlyPlayedBefore != null && c.lastPlayedAt != null && c.lastPlayedAt! >= recentlyPlayedBefore))
        c,
  ];
  final notLatest = [
    for (final c in all)
      if (c.albumId.rawId != avoidRawAlbumId) c,
  ];
  final pool = fresh.isNotEmpty ? fresh : (notLatest.isNotEmpty ? notLatest : all);
  final deck = _jbiDeck(pool, turn);
  final start = turn % pool.length;
  final dealt = [for (var i = 0; i < count && i < pool.length; i++) deck[(start + i) % deck.length]];
  final kept = preferRawAlbumId == null ? null : all.where((c) => c.albumId.rawId == preferRawAlbumId).firstOrNull;
  if (kept == null) return dealt;
  return [kept, ...dealt.where((c) => c.albumId.rawId != preferRawAlbumId)].take(count).toList();
}

/// [pickJbiMemoryCandidates]' first: the main 1×2's memory.
AlbumMemoryCandidate? pickJbiMemoryCandidate(
  List<AlbumMemoryCandidate> candidates, {
  String? avoidRawAlbumId,
  String? preferRawAlbumId,
  int turn = 0,
  int? recentlyPlayedBefore,
}) => pickJbiMemoryCandidates(
  candidates,
  avoidRawAlbumId: avoidRawAlbumId,
  preferRawAlbumId: preferRawAlbumId,
  turn: turn,
  recentlyPlayedBefore: recentlyPlayedBefore,
).firstOrNull;

/// [pool] (in a stable order) shuffled for the round [turn] falls in: the same
/// on every device and every refresh of that round (a seeded Fisher–Yates over
/// the albums' ids, the same 64-bit steps as Android's jbiDeck), a new order
/// each round.
List<AlbumMemoryCandidate> _jbiDeck(List<AlbumMemoryCandidate> pool, int turn) {
  final n = pool.length;
  final round = turn >= 0 ? turn ~/ n : -((-turn + n - 1) ~/ n);
  var seed = round * 6364136223846793005 + 1442695040888963407;
  for (final c in pool) {
    for (final unit in c.albumId.rawId.codeUnits) {
      seed = seed * 31 + unit;
    }
  }
  final deck = [...pool];
  for (var i = deck.length - 1; i >= 1; i--) {
    seed = seed * 6364136223846793005 + 1442695040888963407;
    final j = (seed >>> 33) % (i + 1);
    final tmp = deck[i];
    deck[i] = deck[j];
    deck[j] = tmp;
  }
  return deck;
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
      // The words alone: marks dropped, mentions as their names (Android NoteMarkup.plainText).
      comment: NoteMarkup.plainText(note.content).trim(),
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
  StreamSubscription<void>? _linkChanges;
  int _generation = 0;

  /// The turn the memory cards on screen were dealt for.
  int? _builtTurn;
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
    // A song's album learned: its notes and scores move to that album (marks, Rediscover).
    _linkChanges?.cancel();
    _linkChanges = store == null
        ? null
        : TrackAlbumLinkStore.shared(
            ref.read(keyValueStoreProvider),
            store.profileId,
          ).changes.listen((_) => _schedule(source?.provider, store));
    // Home is where the app opens: the song → album linker starts here.
    ref.read(trackAlbumLinkerProvider);
    // Cloud sync brought Yoin's AI titles from another device.
    ref.listen(memoryCopySyncRevisionProvider, (_, _) => _schedule(source?.provider, store));
    // A new turn (the app started, or came back after a while) deals the memory cards again.
    ref.listen(jbiMemoryTurnProvider, (_, _) => _schedule(source?.provider, store));
    ref.onDispose(() {
      _changes?.cancel();
      _linkChanges?.cancel();
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
      final turn = await ref.read(jbiMemoryTurnProvider.notifier).ready();
      final notes = await store.songNotes();
      final ratings = await store.albumRatings();
      final titles = await store.titles();
      final kv = ref.read(keyValueStoreProvider);
      final trackRatings = {...await LocalRatingStore(kv).read(store.profileId), ...ref.read(localRatingsProvider)};
      final trackAlbums = await loadTrackAlbums(store, TrackAlbumLinkStore.shared(kv, store.profileId));
      // The albums written on through their songs: noted, or scored (their covers' marks).
      final writtenAlbums = {
        for (final n in notes)
          if (!n.isBlank) ?(n.albumId ?? trackAlbums[n.trackId]),
        for (final r in trackRatings.values)
          if (r.rating != null) ?trackAlbums[r.trackId],
      };
      final plays = [
        for (final p in await store.albumPlays())
          // Written on (the memory cards, the marks), or away long enough to come back (Rediscover).
          if (ratings.containsKey(p.albumId) ||
              writtenAlbums.contains(p.albumId) ||
              now - p.lastPlayedAt >= rediscoverAwayMs)
            p,
      ];
      final inputs = AlbumMemoryInputs(
        profileId: store.profileId,
        provider: provider,
        albumRatings: ratings,
        songNotes: notes,
        albumPlays: plays,
        trackRatings: trackRatings,
        trackAlbums: trackAlbums,
      );
      final pool = await buildAlbumMemoryCandidates(
        inputs,
        loadAlbum: ref.read(detailCacheProvider).album,
        includeIneligible: true,
        withTrackSignals: true,
      );
      if (!ref.mounted || generation != _generation) return;
      final eligible = pool.where((c) => c.isMemoryEligible).toList();
      final latest = pickLatestMemory(eligible);
      // Writing on the album shown keeps it; a new turn is exactly when it should change.
      final previous = state.memoryCards.isEmpty || turn != _builtTurn
          ? null
          : memoryCardRawAlbumId(state.memoryCards.first);
      final memories = pickJbiMemoryCandidates(
        eligible,
        avoidRawAlbumId: latest?.albumId.rawId,
        preferRawAlbumId: previous,
        count: 1 + jbiExtraMemoryCards,
        turn: turn,
        recentlyPlayedBefore: now - jbiRecentPlayMs,
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
      _builtTurn = turn;
      state = HomeMemorySignals(
        scope: '$provider|${store.profileId}',
        memoryCards: memoryCards,
        noteCards: noteCards,
        rediscover: buildRediscover(pool, nowMs: now, excludeRawAlbumIds: exclude),
        albumMarks: homeAlbumMemoryMarks(pool),
        resolved: true,
      );
    } catch (e) {
      debugPrint('YoinHome: memory signals failed: ${e.runtimeType}');
    }
  }
}

final homeMemorySignalsProvider = NotifierProvider<HomeMemorySignalsNotifier, HomeMemorySignals>(HomeMemorySignalsNotifier.new);
