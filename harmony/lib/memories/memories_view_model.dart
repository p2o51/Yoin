/// The Memories deck's view model (Android MemoriesViewModel +
/// MemoriesDeckCoordinator): builds the Memory pool of the active account,
/// deals a deck of up to [memoryDeckSize] cards, keeps that deck for the
/// session, refreshes cards in place when the user writes, and saves the
/// writes (album score, review, notes) through the account's [MemoryStore].
library;

import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/memory/album_memory_candidate.dart';
import '../data/memory/memory_store.dart';
import '../data/model/model.dart';
import '../detail/detail_cache.dart';
import '../playback/local_ratings.dart';
import 'copy/memory_copy.dart';
import 'copy/memory_voice.dart';
import 'memories_ui_state.dart';
import 'memory_composition.dart';

/// Wall clock for Memories (dates, "two days ago", days since first play).
final memoriesClockProvider = Provider<int Function()>(
  (ref) =>
      () => DateTime.now().millisecondsSinceEpoch,
);

/// The calendar day of an instant; tests pin the zone.
final memoriesDayOfProvider = Provider<MemoryDayOf>((ref) => localMemoryDay);

/// Shuffles the deck; tests pass a seeded Random.
final memoriesRandomProvider = Provider<math.Random>((ref) => math.Random());

/// The language Yoin's prose falls back to when the user has written nothing.
final memoriesAppLanguageProvider = Provider<MemoryProseLanguage>(
  (ref) => PlatformDispatcher.instance.locale.languageCode == 'zh' ? MemoryProseLanguage.zh : MemoryProseLanguage.en,
);

class MemoriesViewModel extends Notifier<MemoriesUiState> {
  /// The deck dealt this session, by [MemoryEntry.sessionId] (Android
  /// ExperienceSessionStore): reopening Memories shows the same cards.
  List<int> _deckIds = const [];
  int _page = 0;
  String? _profileId;
  int _generation = 0;
  StreamSubscription<MemoryStoreChange>? _storeChanges;
  Timer? _refreshDebounce;

  @override
  MemoriesUiState build() {
    final source = ref.watch(activeSourceProvider).value;
    final generation = ++_generation;
    ref.listen(memoryStoreProvider, (_, next) => _watchStore(next.value), fireImmediately: true);
    ref.listen(localRatingsProvider, (previous, next) {
      if (previous != null && !identical(previous, next)) _scheduleRefresh();
    });
    ref.onDispose(() {
      _storeChanges?.cancel();
      _refreshDebounce?.cancel();
    });
    if (source == null) return const MemoriesLoading();
    scheduleMicrotask(() => _load(generation));
    return const MemoriesLoading();
  }

  /// Loads (or re-deals with [focus] first: the Home pill's memory).
  Future<void> ensureLoaded({int? focus}) => _load(++_generation, focus: focus);

  /// The page the deck rests on; kept for the next opening.
  void setCurrentPage(int page) {
    _page = page;
    final s = state;
    if (s is MemoriesContent && s.currentPage != page) state = s.copyWith(currentPage: page);
  }

  /// Saves [text] as the album's review (blank clears it). False when the
  /// write failed: the caller keeps the draft (mem_snackbar_save_review).
  Future<bool> saveReview(MemoryEntry memory, String text) =>
      _write((store, now) => store.setAlbumReview(memory.albumId, text, nowMs: now));

  /// The album score, 0.0–10.0; null clears it.
  Future<bool> setAlbumRating(MemoryEntry memory, double? rating) =>
      _write((store, now) => store.setAlbumRating(memory.albumId, rating, nowMs: now));

  /// A note on [track], pinned to [positionMs] (null = not anchored).
  Future<bool> addNote(MemoryEntry memory, Track track, String text, {int? positionMs}) => _write((store, now) {
    return store.putSongNote(
      SongNote(
        id: '${now.toRadixString(36)}-${track.id.rawId.hashCode.toRadixString(36)}',
        trackId: track.id,
        albumId: track.albumId ?? memory.albumId,
        content: text.trim(),
        createdAt: now,
        updatedAt: now,
        title: track.title ?? '',
        artist: track.artist ?? '',
        positionMs: positionMs,
      ),
    );
  });

  Future<bool> _write(Future<void> Function(MemoryStore store, int nowMs) write) async {
    try {
      final store = await ref.read(memoryStoreProvider.future);
      if (store == null) return false;
      await write(store, ref.read(memoriesClockProvider)());
      await _refreshInPlace();
      return true;
    } catch (e) {
      debugPrint('YoinMemories: write failed: ${e.runtimeType}');
      return false;
    }
  }

  void _watchStore(MemoryStore? store) {
    _storeChanges?.cancel();
    _storeChanges = store?.changes.listen((_) => _scheduleRefresh());
  }

  void _scheduleRefresh() {
    _refreshDebounce?.cancel();
    _refreshDebounce = Timer(const Duration(milliseconds: 120), () => unawaited(_refreshInPlace()));
  }

  Future<void> _load(int generation, {int? focus}) async {
    try {
      final inputs = await _inputs();
      if (!ref.mounted || generation != _generation) return;
      if (inputs == null) {
        state = const MemoriesEmpty();
        return;
      }
      if (inputs.profileId != _profileId) {
        _profileId = inputs.profileId;
        _deckIds = const [];
        _page = 0;
      }
      final pool = await buildAlbumMemoryCandidates(inputs, loadAlbum: _loadAlbum);
      if (!ref.mounted || generation != _generation) return;
      if (pool.isEmpty) {
        _deckIds = const [];
        state = const MemoriesEmpty();
        return;
      }
      final byId = {for (final c in pool) c.sessionId: c};
      var ids = [
        for (final id in _deckIds)
          if (byId.containsKey(id)) id,
      ];
      if (focus != null && byId.containsKey(focus)) {
        ids = [focus, ...ids.where((id) => id != focus)];
        if (ids.length == 1) {
          ids = [
            focus,
            ..._sample(pool, exclude: {focus}),
          ].take(memoryDeckSize).toList();
        }
        _page = 0;
      } else if (ids.isEmpty) {
        ids = _sample(pool, exclude: const {});
        _page = 0;
      }
      _deckIds = ids;
      final entries = await _compose(inputs, [for (final id in ids) byId[id]!]);
      if (!ref.mounted || generation != _generation) return;
      _page = _page.clamp(0, entries.length - 1);
      state = MemoriesContent(entries, currentPage: _page);
    } catch (e) {
      debugPrint('YoinMemories: load failed: ${e.runtimeType}');
      if (ref.mounted && generation == _generation) state = const MemoriesError();
    }
  }

  /// The cards of the current deck rebuilt from fresh rows, in place: same
  /// cards, same order, even one that no longer passes the gates (a card
  /// never vanishes under the user's finger).
  Future<void> _refreshInPlace() async {
    final current = state;
    if (current is! MemoriesContent || _deckIds.isEmpty) return;
    final generation = _generation;
    try {
      final inputs = await _inputs();
      if (inputs == null || inputs.profileId != _profileId) return;
      final shown = [for (final m in current.memories) m.albumId];
      final pool = await buildAlbumMemoryCandidates(
        inputs,
        loadAlbum: _loadAlbum,
        includeIneligible: true,
        alsoScan: shown,
      );
      final byId = {for (final c in pool) c.sessionId: c};
      final candidates = [for (final id in _deckIds) ?byId[id]];
      final entries = await _compose(inputs, candidates);
      if (!ref.mounted || generation != _generation || entries.isEmpty) return;
      final s = state;
      state = MemoriesContent(
        entries,
        currentPage: (s is MemoriesContent ? s.currentPage : _page).clamp(0, entries.length - 1),
      );
    } catch (e) {
      debugPrint('YoinMemories: refresh failed: ${e.runtimeType}');
    }
  }

  List<int> _sample(List<AlbumMemoryCandidate> pool, {required Set<int> exclude}) {
    final rng = ref.read(memoriesRandomProvider);
    final picked = [
      for (final c in pool)
        if (!exclude.contains(c.sessionId)) c.sessionId,
    ]..shuffle(rng);
    return picked.take(memoryDeckSize).toList();
  }

  Future<Album?> _loadAlbum(MediaId id) => ref.read(detailCacheProvider).album(id);

  Future<AlbumMemoryInputs?> _inputs() async {
    final source = await ref.read(activeSourceProvider.future);
    final store = await ref.read(memoryStoreProvider.future);
    if (source == null || store == null) return null;
    final log = await ref.read(activityLogProvider.future);
    final kv = ref.read(keyValueStoreProvider);
    // The stored ratings, with the in-memory ones (the same account's newest) over them.
    final ratings = {...await LocalRatingStore(kv).read(store.profileId), ...ref.read(localRatingsProvider)};
    return AlbumMemoryInputs(
      profileId: store.profileId,
      provider: source.provider,
      albumRatings: await store.albumRatings(),
      songNotes: await store.songNotes(),
      albumPlays: await store.albumPlays(),
      activity: await log?.recent() ?? const [],
      trackRatings: ratings,
    );
  }

  Future<List<MemoryEntry>> _compose(AlbumMemoryInputs inputs, List<AlbumMemoryCandidate> candidates) async {
    final now = ref.read(memoriesClockProvider)();
    final dayOf = ref.read(memoriesDayOfProvider);
    final language = ref.read(memoriesAppLanguageProvider);
    return [
      for (final c in candidates)
        composeAlbumMemory(
          candidate: c,
          trackRatings: inputs.trackRatings,
          ratingRow: inputs.albumRatings[c.albumId],
          notes: [
            for (final n in inputs.songNotes)
              if (n.albumId == c.albumId || (c.album?.tracks.any((t) => t.id == n.trackId) ?? false)) n,
          ],
          nowMs: now,
          dayOf: dayOf,
          appLanguage: language,
        ),
    ];
  }
}

final memoriesViewModelProvider = NotifierProvider<MemoriesViewModel, MemoriesUiState>(MemoriesViewModel.new);
