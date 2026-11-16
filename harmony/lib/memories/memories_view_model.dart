/// The Memories deck's view model (Android MemoriesViewModel +
/// MemoriesDeckCoordinator): builds the Memory pool of every account (the one
/// in use, then the others: owner 2026-11-08, another account's memory says
/// whose it is and is read-only),
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
import 'memory_ai.dart';
import 'memory_composition.dart';
import 'other_profiles.dart';

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
    // Another account added, removed or renamed: its memories come or go.
    ref.listen(otherProfileSourcesProvider, (previous, next) {
      if (previous?.value != null && !identical(previous!.value, next.value)) _scheduleRefresh();
    });
    ref.onDispose(() {
      _storeChanges?.cancel();
      _refreshDebounce?.cancel();
    });
    // A deck on screen stays while the accounts change under it (a switch
    // from another account's memory keeps that card in view); the reload
    // deals the same cards from the other side.
    final shown = stateOrNull is MemoriesContent ? stateOrNull! : const MemoriesLoading();
    if (source == null) return shown;
    scheduleMicrotask(() => _load(generation));
    return shown;
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
      _write(memory, (store, now) => store.setAlbumReview(memory.albumId, text, nowMs: now));

  /// The album score, 0.0–10.0; null clears it.
  Future<bool> setAlbumRating(MemoryEntry memory, double? rating) =>
      _write(memory, (store, now) => store.setAlbumRating(memory.albumId, rating, nowMs: now));

  /// A note on [track], pinned to [positionMs] (null = not anchored).
  Future<bool> addNote(MemoryEntry memory, Track track, String text, {int? positionMs}) => _write(memory, (store, now) {
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

  /// The user names [memory]'s title (MemoryTitleEditor's Save). Blank
  /// restores Yoin's title (the row goes).
  Future<bool> saveTitle(MemoryEntry memory, String title) =>
      _write(memory, (store, now) => store.setTitle(memory.albumId, title, nowMs: now));

  /// Takes the user's title off: Yoin's own (or the album name) shows again.
  Future<bool> restoreTitle(MemoryEntry memory) =>
      _write(memory, (store, now) => store.setTitle(memory.albumId, null, nowMs: now));

  /// Another account's memory takes no writes here (its title, review and
  /// notes belong to that account): false, nothing written.
  Future<bool> _write(MemoryEntry memory, Future<void> Function(MemoryStore store, int nowMs) write) async {
    if (memory.isFromOtherProfile) return false;
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
      final accounts = await _accounts();
      if (!ref.mounted || generation != _generation) return;
      if (accounts.isEmpty) {
        state = const MemoriesEmpty();
        return;
      }
      // The deck stays across an account switch: a card's id is its account's
      // and album's, so the same memories are dealt, now from the other side.
      _profileId = accounts.first.isOwn ? accounts.first.inputs.profileId : null;
      final pool = await _pool(accounts);
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
      final entries = await _compose(accounts, [for (final id in ids) byId[id]!]);
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
      final accounts = await _accounts();
      if (accounts.isEmpty) return;
      final ownId = accounts.first.isOwn ? accounts.first.inputs.profileId : null;
      if (ownId != _profileId) return; // a switch in flight: the reload deals
      final pool = await _pool(accounts, includeIneligible: true, shown: current.memories);
      final byId = {for (final c in pool) c.sessionId: c};
      final candidates = [for (final id in _deckIds) ?byId[id]];
      final entries = await _compose(accounts, candidates);
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

  /// The pool over every account: the one in use's own ranking first, then
  /// each other account's (Android ensureCandidates: own + others). An
  /// account whose rows can't be read adds nothing; the rest still deal.
  Future<List<AlbumMemoryCandidate>> _pool(
    List<_Account> accounts, {
    bool includeIneligible = false,
    List<MemoryEntry> shown = const [],
  }) async {
    final pools = await Future.wait([
      for (final a in accounts)
        buildAlbumMemoryCandidates(
          a.inputs,
          loadAlbum: a.other?.album ?? _loadAlbum,
          // Another account's pool is smaller (Android limit 24).
          limit: a.isOwn ? memoryMaxCandidateScan : otherProfileMemoryLimit,
          includeIneligible: includeIneligible,
          alsoScan: [
            for (final m in shown)
              if ((m.otherProfile?.id ?? _profileId) == a.inputs.profileId) m.albumId,
          ],
        ).then<List<AlbumMemoryCandidate>>(
          (pool) => pool,
          onError: (Object e) {
            if (a.isOwn) throw e;
            return const <AlbumMemoryCandidate>[];
          },
        ),
    ]);
    return [for (final p in pools) ...p];
  }

  /// The account in use (first, when there is one) and the other accounts
  /// Yoin could switch to, each with its rows read.
  Future<List<_Account>> _accounts() async {
    final out = <_Account>[];
    final own = await _ownInputs();
    if (own != null) out.add(_Account.own(own.$1, titles: own.$2));
    List<OtherProfileSource> others;
    try {
      others = await ref.read(otherProfileSourcesProvider.future);
    } catch (_) {
      others = const [];
    }
    final read = await Future.wait([
      for (final o in others) _otherInputs(o).then<_Account?>((a) => a, onError: (Object _) => null),
    ]);
    out.addAll(read.whereType<_Account>());
    return out;
  }

  Future<(AlbumMemoryInputs, Map<MediaId, MemoryTitleRow>)?> _ownInputs() async {
    final source = await ref.read(activeSourceProvider.future);
    final store = await ref.read(memoryStoreProvider.future);
    if (source == null || store == null) return null;
    final log = await ref.read(activityLogProvider.future);
    final kv = ref.read(keyValueStoreProvider);
    // The stored ratings, with the in-memory ones (the same account's newest) over them.
    final ratings = {...await LocalRatingStore(kv).read(store.profileId), ...ref.read(localRatingsProvider)};
    final inputs = AlbumMemoryInputs(
      profileId: store.profileId,
      provider: source.provider,
      albumRatings: await store.albumRatings(),
      songNotes: await store.songNotes(),
      albumPlays: await store.albumPlays(),
      activity: await log?.recent() ?? const [],
      trackRatings: ratings,
    );
    return (inputs, await store.titles());
  }

  Future<_Account> _otherInputs(OtherProfileSource o) async {
    final store = o.store;
    final inputs = AlbumMemoryInputs(
      profileId: o.id,
      provider: o.provider,
      albumRatings: await store.albumRatings(),
      songNotes: await store.songNotes(),
      albumPlays: await store.albumPlays(),
      activity: await o.log.recent(),
      trackRatings: await o.ratings(),
    );
    return _Account.other(inputs, titles: await store.titles(), other: o);
  }

  Future<List<MemoryEntry>> _compose(List<_Account> accounts, List<AlbumMemoryCandidate> candidates) async {
    final byProfile = {for (final a in accounts) a.inputs.profileId: a};
    // Yoin's AI words (Android MemoriesDeckCoordinator): the deck is where the
    // AI title is generated and the narration written, for the account in
    // use; without a service the cached title (else the album name) and the
    // template stand. Another account's memory is read as it is (read-only).
    final store = await ref.read(memoryStoreProvider.future);
    final own = accounts.where((a) => a.other == null).firstOrNull;
    final ai = store == null || own == null || store.profileId != own.inputs.profileId ? null : await _memoryAi();
    final now = ref.read(memoriesClockProvider)();
    final dayOf = ref.read(memoriesDayOfProvider);
    final language = ref.read(memoriesAppLanguageProvider);
    Future<MemoryEntry?> compose(AlbumMemoryCandidate c) async {
      final a = byProfile[c.profileId];
      if (a == null) return null;
      final ratingRow = a.inputs.albumRatings[c.albumId];
      final notes = [
        for (final n in a.inputs.songNotes)
          if (n.albumId == c.albumId || (c.album?.tracks.any((t) => t.id == n.trackId) ?? false)) n,
      ];
      final words = a.other == null ? ai : null;
      final album = c.album;
      final aiTitle = words == null
          ? null
          : album != null
          ? await words.title(album, memoryTitleWriting(ratingRow, notes))
          : await words.cachedTitle(c.albumId);
      final draft = draftAlbumMemory(
        candidate: c,
        trackRatings: a.inputs.trackRatings,
        ratingRow: ratingRow,
        notes: notes,
        nowMs: now,
        dayOf: dayOf,
        appLanguage: language,
        aiTitle: aiTitle,
        // Another account's memory wears that account's title and covers.
        userTitle: a.titles[c.albumId]?.title,
        otherProfile: a.other?.profile,
        coverFor: a.other == null ? null : (cover) => a.other!.coverFor(cover),
      );
      final brief = draft.narrationBrief;
      final narration = words == null || brief == null ? null : await words.narrate(c.albumId, brief);
      return draft.entry(aiNarration: narration);
    }

    return [...(await Future.wait([for (final c in candidates) compose(c)])).nonNulls];
  }

  Future<MemoryAi?> _memoryAi() async {
    try {
      return await ref.read(memoryAiProvider.future);
    } catch (_) {
      return null;
    }
  }
}

/// Most candidates another account adds to the pool (Android
/// getOtherProfileAlbumMemoryCandidates limit).
const int otherProfileMemoryLimit = 24;

/// One account's rows for a build: the one in use ([other] null) or another.
final class _Account {
  const _Account.own(this.inputs, {required this.titles}) : other = null;

  const _Account.other(this.inputs, {required this.titles, required OtherProfileSource this.other});

  final AlbumMemoryInputs inputs;
  final Map<MediaId, MemoryTitleRow> titles;
  final OtherProfileSource? other;

  bool get isOwn => other == null;
}

final memoriesViewModelProvider = NotifierProvider<MemoriesViewModel, MemoriesUiState>(MemoriesViewModel.new);
