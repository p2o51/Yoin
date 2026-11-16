import 'dart:async';
import 'dart:math' as math;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/activity/activity.dart';
import '../data/home/home_snapshot_store.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';
import '../library/playlist_deletions.dart';
import '../playback/playback_controller.dart';
import 'home_grid_pool_store.dart';
import 'home_metrics.dart';
import 'home_selection.dart';
import 'home_snapshot_mapping.dart';
import 'home_ui_state.dart';

/// Wall clock for Home (time-ago, the 30-day window, pool TTL); tests override it.
final homeClockProvider = Provider<DateTime Function()>((ref) => DateTime.now);

/// Shuffles for Jump Back In's pools; tests override it with a seeded Random.
final homeRandomProvider = Provider<math.Random>((ref) => math.Random());

/// Activities' live debounce (1 s on Android); tests shorten it.
final homeActivityDebounceProvider = Provider<Duration>((ref) => HomeTimings.activityDebounce);

/// Home's view model (Android HomeViewModel). One load per scope (account):
/// the local tier (activity log + persisted Jump Back In pools) goes up first
/// as one Content, then each network block splices its own fields in as it
/// lands. A failed block keeps what it had; an empty one clears. Activities
/// stays live through the log (debounced).
///
/// Cold start (Android HomeSnapshotStore, P2 PR3): with nothing of the scope
/// in this process, the account's snapshot paints the last feed at once —
/// before the source is up, and offline — and the load replaces it block by
/// block. A snapshot painted as it is isn't written back; every Content
/// published after it is (conflated), each block no read has filled keeping
/// what the file has.
class HomeViewModel extends Notifier<HomeUiState> {
  /// Last Content per scope key, for the process: a re-created Home paints at once.
  static final Map<String, HomeContent> _contentCache = {};

  /// Tests start each case cold.
  static void clearProcessCache() => _contentCache.clear();

  int _generation = 0;
  final List<HomeContent Function(HomeContent)> _draft = [];
  StreamSubscription<List<ActivityEvent>>? _activitySubscription;
  Timer? _activityDebounce;
  String? _heroKey;
  int _footnoteGeneration = 0;
  int _songTapGeneration = 0;

  /// The Content up is the account's snapshot as read: nothing of it is new yet.
  bool _snapshotOnly = false;

  /// Network blocks the Content up lacks: still out, or failed, on a feed
  /// built from nothing. A save keeps the file's for them.
  Set<HomeNetworkBlock> _unread = {};

  /// Whose feed the load writes back (null: none, no account).
  ({String profileId, String provider})? _saveTo;

  /// Playlists the service deleted while Home was up ([PlaylistDeletions]):
  /// the feed leaves them out wherever it would show them, reads that started
  /// before the deletion included.
  final Set<PlaylistDeletionKey> _deletedPlaylists = {};
  int _deletionsSeen = 0;

  @override
  HomeUiState build() {
    final source = ref.watch(activeSourceProvider);
    ref.onDispose(_stop);
    _deletionsSeen = ref.read(playlistDeletionsProvider).lastSeq;
    ref.listen(playlistDeletionsProvider, (_, next) => _onPlaylistDeletions(next));
    // A feed up (the snapshot painted while the source came up, or a reload)
    // stays through the rebuild: the load replaces it, never a spinner between.
    final previous = stateOrNull;
    final keep = previous is HomeContent ? previous : null;
    return switch (source) {
      // Reloading (an account switch or edit): the value is still the previous
      // account's source, never to be read under the new one's scope.
      _ when source.isLoading => _awaitSource(keep),
      // Another provider's feed (an account switch) never stands in for this one's.
      AsyncData(:final value) => _start(value, keep: value == null || keep?.provider != value.provider ? null : keep),
      // Credentials unreadable: a sourceless load (local tier only) publishes once.
      AsyncError() => _start(null, keep: keep),
      _ => _awaitSource(keep),
    };
  }

  /// The source isn't up yet: paint the account's snapshot meanwhile.
  /// A feed kept up (a reload of the same account) stays; another account's
  /// (a switch: the source is rebuilding) gives way to this one's.
  HomeUiState _awaitSource(HomeContent? keep) {
    final generation = _generation;
    Future.microtask(() => _paintSnapshotEarly(generation));
    return keep ?? const HomeLoading();
  }

  Future<void> _paintSnapshotEarly(int generation) async {
    final profile = await ref.read(profileRepositoryProvider).activeProfile().catchError((_) => null);
    if (profile == null || !_live(generation)) return;
    final scopeKey = '${profile.provider}|${profile.id}';
    final up = state;
    if (up is HomeContent) {
      if (up.scopeKey == scopeKey) return;
      // The account switched: its feed must not sit under the new one.
      _snapshotOnly = false;
      state = const HomeLoading();
    }
    final cached = _contentCache[scopeKey];
    if (cached != null) {
      state = cached.copyWith(accountTitle: profile.displayName);
      return;
    }
    final painted = await _snapshotContent(profile.id, profile.provider, scopeKey, profile.displayName);
    if (painted == null || !_live(generation) || state is HomeContent) return;
    _snapshotOnly = true;
    state = painted;
  }

  /// [profileId]'s snapshot as [scopeKey]'s Content, when it is [provider]'s.
  Future<HomeContent?> _snapshotContent(String profileId, String provider, String scopeKey, String? title) async {
    final store = ref.read(homeSnapshotStoreProvider);
    if (store == null) return null;
    try {
      final snapshot = await store.read(profileId);
      if (snapshot == null || snapshot.provider != provider) return null;
      return homeContentFromSnapshot(
        snapshot.feed,
        scopeKey: scopeKey,
        provider: provider,
        accountTitle: title,
        nowMs: _nowMs,
      );
    } catch (_) {
      return null;
    }
  }

  /// Retry (Error page) or an explicit refresh: reloads the active scope.
  void refresh() {
    final source = ref.read(activeSourceProvider);
    if (source.isLoading) return;
    if (state is! HomeContent) state = const HomeLoading();
    _start(source.value);
  }

  /// Plays [track] alone (queue of 1, no navigation). Waits up to 4 s for the
  /// account's source on a cold start; only the latest tap plays.
  Future<void> playSong(Track track) async {
    final tap = ++_songTapGeneration;
    MusicSource? source;
    try {
      source = await ref.read(activeSourceProvider.future).timeout(HomeTimings.activeSourceWait);
    } catch (_) {
      return;
    }
    if (!ref.mounted || tap != _songTapGeneration) return;
    if (source == null || source.provider != track.id.provider) return;
    await ref.read(playbackControllerProvider.notifier).playTracks([track]);
  }

  void _stop() {
    _generation++;
    _draft.clear();
    _activitySubscription?.cancel();
    _activitySubscription = null;
    _activityDebounce?.cancel();
    _activityDebounce = null;
  }

  HomeUiState _start(MusicSource? source, {HomeContent? keep}) {
    _stop();
    final generation = _generation;
    _heroKey = null;
    Future.microtask(() => _load(source, generation));
    return keep ?? const HomeLoading();
  }

  bool _live(int generation) => ref.mounted && generation == _generation;

  int get _nowMs => ref.read(homeClockProvider)().millisecondsSinceEpoch;

  /// A playlist the service deleted (Android observePlaylistDeletions): the
  /// feed up drops it wherever it shows it (an Activities row, a Jump Back In
  /// card, Your Playlists) without reading Home again. One still on its way
  /// out stays: a refusal must not cost Home anything.
  void _onPlaylistDeletions(PlaylistDeletionState next) {
    final gone = next.doneAfter(_deletionsSeen);
    _deletionsSeen = next.lastSeq;
    if (gone.isEmpty) return;
    _deletedPlaylists.addAll(gone);
    final current = state;
    if (current is! HomeContent) return;
    final kept = _withoutDeleted(current);
    if (identical(kept, current)) return;
    state = kept;
    if (kept.scopeKey.isNotEmpty) _contentCache[kept.scopeKey] = kept;
    _save(kept);
  }

  /// [content] without the playlists deleted on its account.
  HomeContent _withoutDeleted(HomeContent content) {
    if (_deletedPlaylists.isEmpty) return content;
    final account = content.scopeKey.split('|').last;
    final ids = {
      for (final key in _deletedPlaylists)
        if (key.account == null || key.account == account) key.id,
    };
    return ids.isEmpty ? content : homeContentWithoutPlaylists(content, ids);
  }

  void _publish(int generation, HomeContent content) {
    if (!_live(generation)) return;
    content = _withoutDeleted(content);
    final current = state;
    if (current is HomeContent && current.sameFeedAs(content)) return;
    state = content;
    if (content.scopeKey.isNotEmpty) _contentCache[content.scopeKey] = content;
    _snapshotOnly = false;
    _save(content);
  }

  /// Queues [content] as its account's snapshot (conflated by the store).
  void _save(HomeContent content) {
    final to = _saveTo;
    final store = ref.read(homeSnapshotStoreProvider);
    if (to == null || store == null || _snapshotOnly || content.scopeKey != '${to.provider}|${to.profileId}') return;
    store.save(to.profileId, to.provider, unread: {..._unread}, feed: () => homeSnapshotFeed(content));
  }

  /// [block] was read: it no longer keeps the file's copy.
  void _filled(int generation, HomeNetworkBlock block) {
    if (_live(generation)) _unread.remove(block);
  }

  /// Applies [change] to the published Content, or queues it until the local tier is up.
  void _splice(int generation, HomeContent Function(HomeContent) change) {
    if (!_live(generation)) return;
    final current = state;
    if (current is HomeContent) {
      _publish(generation, change(current));
    } else {
      _draft.add(change);
    }
  }

  Future<void> _load(MusicSource? source, int generation) async {
    final profile = await ref.read(profileRepositoryProvider).activeProfile().catchError((_) => null);
    if (!_live(generation)) return;
    if (profile == null) {
      // No account: the header plus the empty Activities card; the header leads to Settings.
      _saveTo = null;
      _publish(generation, HomeContent(scopeKey: '', provider: MediaId.providerSubsonic));
      return;
    }
    final provider = source?.provider ?? profile.provider;
    final scopeKey = '$provider|${profile.id}';
    _saveTo = (profileId: profile.id, provider: provider);
    final cached = _contentCache[scopeKey];
    final up = state;
    if (cached != null) {
      _unread = {};
      _publish(generation, cached.copyWith(accountTitle: profile.displayName));
    } else if (up is HomeContent && up.scopeKey == scopeKey) {
      // The snapshot (or a same-scope reload) is up: every block holds what it read.
      _unread = {};
    } else {
      // Nothing of this scope in the process: the account's snapshot, else a spinner.
      final painted = await _snapshotContent(profile.id, provider, scopeKey, profile.displayName);
      if (!_live(generation)) return;
      if (painted != null) {
        _unread = {};
        _snapshotOnly = true;
        state = painted;
      } else {
        _unread = HomeNetworkBlock.unreadFromNothing();
        if (state is HomeContent) state = const HomeLoading();
      }
    }

    // Network blocks start with the load and splice in whenever they land.
    Future<List<Playlist>?>? playlists;
    if (source != null) {
      playlists = _guard(source.library.getPlaylists());
      _launchNetworkBlocks(source, generation, playlists);
    }

    // Local tier.
    final List<ActivityEvent> events;
    try {
      final log = await ref.read(activityLogProvider.future);
      events = log == null ? const [] : await log.recent(limit: homeActivityReadLimit);
    } catch (error) {
      if (_live(generation) && state is! HomeContent) state = HomeError(error);
      return;
    }
    HomeGridPools? pools;
    var poolReadOk = true;
    final store = ref.read(homeGridPoolStoreProvider);
    try {
      pools = await store.read(profile.id, provider);
    } catch (_) {
      poolReadOk = false;
    }
    if (!_live(generation)) return;

    final current = state;
    var local = (current is HomeContent && current.scopeKey == scopeKey
            ? current
            : (cached ?? HomeContent(scopeKey: scopeKey, provider: provider)))
        .copyWith(
      accountTitle: profile.displayName,
      activities: collapseToLatestUnique(events),
      activitiesAtMs: _nowMs,
      grid: pools == null ? null : buildWidgetGrid(pools, limit: null),
    );
    for (final change in _draft) {
      local = change(local);
    }
    _draft.clear();
    _heroKey = heroKeyOf(local.activityEntries);
    _publish(generation, local);
    if (!_live(generation)) return;

    if (source != null) {
      unawaited(_resolveFootnote(source, generation, heroChanged: cached == null || heroKeyOf(cached.activityEntries) != _heroKey));
      final stale = pools == null || _nowMs - pools.cachedAtMs > HomeTimings.gridPoolsTtl.inMilliseconds;
      if (poolReadOk && stale) unawaited(_rotatePools(source, generation, profile.id, pools, playlists!));
    }
    _watchActivities(source, generation);
  }

  void _launchNetworkBlocks(MusicSource source, int generation, Future<List<Playlist>?> playlists) {
    unawaited(playlists.then((list) {
      if (list == null) return; // Failed: keep what the block had.
      _filled(generation, HomeNetworkBlock.playlists);
      final shelf = distinctBy(list, (p) => p.id).take(homePlaylistLimit).toList();
      _splice(generation, (c) => c.copyWith(playlists: shelf));
    }));

    unawaited(_guard(source.library.getStarred()).then((starred) {
      if (starred == null) return;
      _filled(generation, HomeNetworkBlock.recentlyAdded);
      final now = _nowMs;
      final tracks = recentlyAddedFrom<Track>(
        starred.tracks,
        nowMs: now,
        starredAt: (t) => t.starredAt,
        id: (t) => t.id,
        cap: recentlyAddedMaxTracks,
      );
      final albums = recentlyAddedFrom<Album>(
        starred.albums,
        nowMs: now,
        starredAt: (a) => a.starredAt,
        id: (a) => a.id,
        cap: recentlyAddedMaxAlbums,
      );
      _splice(generation, (c) => c.copyWith(recentlyAddedTracks: tracks, recentlyAddedAlbums: albums));
    }));

    unawaited(_guard(source.library.getAlbumList(AlbumListType.recent, size: homeRecentlyPlayedLimit)).then((albums) {
      if (albums == null) return;
      _filled(generation, HomeNetworkBlock.recentlyPlayed);
      final distinct = distinctBy(albums, (a) => a.id);
      _splice(generation, (c) => c.copyWith(recentlyPlayedAll: distinct));
    }));
  }

  /// fetchAndPersistGridPools: random albums, random songs (or starred
  /// tracks), the shared playlists read. Keeps the old pools when a read
  /// failed and nothing came back; a failed kind keeps its old pool.
  Future<void> _rotatePools(
    MusicSource source,
    int generation,
    String profileId,
    HomeGridPools? old,
    Future<List<Playlist>?> playlists,
  ) async {
    final rng = ref.read(homeRandomProvider);
    final hasRandom = source.capabilities.contains(Capability.randomSongs);
    final results = await Future.wait<List<Object>?>([
      _guard(source.library.getAlbumList(AlbumListType.random, size: gridAlbumFetch)),
      hasRandom
          ? _guard(source.library.getRandomSongs(size: gridSongFetch))
          : _guard(source.library.getStarred().then((s) => s.tracks)),
      playlists,
    ]);
    if (!_live(generation)) return;
    final albums = results[0] as List<Album>?;
    final tracks = results[1] as List<Track>?;
    final lists = results[2] as List<Playlist>?;
    final anyFailed = results.contains(null);
    final anyReturned = results.any((r) => r != null && r.isNotEmpty);
    if (anyFailed && !anyReturned) return;
    final pools = HomeGridPools(
      cachedAtMs: _nowMs,
      albums: albums == null ? (old?.albums ?? const []) : albumPoolFrom(albums, rng),
      tracks: tracks == null ? (old?.tracks ?? const []) : trackPoolFrom(tracks, rng, shuffle: true),
      playlists: lists == null ? (old?.playlists ?? const []) : playlistPoolFrom(lists, rng),
    );
    unawaited(ref.read(homeGridPoolStoreProvider).write(profileId, source.provider, pools));
    final grid = buildWidgetGrid(pools, limit: null);
    _splice(generation, (c) => c.copyWith(grid: grid));
  }

  void _watchActivities(MusicSource? source, int generation) {
    final logFuture = ref.read(activityLogProvider.future);
    unawaited(logFuture.then((log) {
      if (log == null || !_live(generation)) return;
      _activitySubscription = log.watchRecent(limit: homeActivityReadLimit).listen((rows) {
        _activityDebounce?.cancel();
        _activityDebounce = Timer(ref.read(homeActivityDebounceProvider), () => _onActivities(source, generation, rows));
      }, onError: (_) {});
    }, onError: (_) {}));
  }

  void _onActivities(MusicSource? source, int generation, List<ActivityEvent> rows) {
    if (!_live(generation)) return;
    final activities = collapseToLatestUnique(rows);
    final now = _nowMs;
    _splice(generation, (c) => c.copyWith(activities: activities, activitiesAtMs: now));
    final current = state;
    if (current is! HomeContent) return;
    final hero = heroKeyOf(current.activityEntries);
    if (hero == _heroKey) return;
    _heroKey = hero;
    if (source != null) unawaited(_resolveFootnote(source, generation, heroChanged: true));
  }

  /// loadActivityHeroFootnote: only for an album hero. A failure never wipes a
  /// good footnote of the same hero; a changed hero keeps the old line until
  /// the new one resolves, and drops it when the new one fails.
  Future<void> _resolveFootnote(MusicSource source, int generation, {required bool heroChanged}) async {
    final run = ++_footnoteGeneration;
    final current = state;
    if (current is! HomeContent) return;
    final i = selectHeroIndex(current.activityEntries);
    final target = i < 0 ? null : current.activityEntries[i].target;
    if (target is! HomeOpenAlbum) {
      if (current.heroFootnote != null) _splice(generation, (c) => c.copyWith(clearHeroFootnote: true));
      return;
    }
    Album? album;
    try {
      album = await source.library.getAlbum(target.album.id);
    } catch (_) {
      album = null;
    }
    if (!_live(generation) || run != _footnoteGeneration) return;
    final footnote = album == null ? null : footnoteOf(album);
    if (footnote == null) {
      if (heroChanged) _splice(generation, (c) => c.copyWith(clearHeroFootnote: true));
      return;
    }
    _splice(generation, (c) => c.copyWith(heroFootnote: footnote));
  }

  /// null when a read throws (the block keeps what it had).
  static Future<T?> _guard<T>(Future<T> read) => read.then<T?>((v) => v, onError: (Object _) => null);
}

/// [content] without the playlists [ids], which their service deleted: their
/// Activities rows, their Jump Back In cards and their place in Your
/// Playlists (Android HomeUiState.Content.withoutPlaylist). The same content
/// when none shows them.
HomeContent homeContentWithoutPlaylists(HomeContent content, Set<MediaId> ids) {
  bool activityIsOne(ActivityEvent e) =>
      e.entityType == ActivityEntityType.playlist &&
      ids.any((id) => id.provider == content.provider && id.rawId == e.entityId);
  bool cardIsOne(HomeWidgetCard card) => switch (card.target) {
    HomeOpenPlaylist(:final playlist) => ids.contains(playlist.id),
    _ => false,
  };
  if (!content.activities.any(activityIsOne) &&
      !content.grid.any(cardIsOne) &&
      !content.playlists.any((p) => ids.contains(p.id))) {
    return content;
  }
  return content.copyWith(
    activities: [
      for (final e in content.activities)
        if (!activityIsOne(e)) e,
    ],
    grid: [
      for (final c in content.grid)
        if (!cardIsOne(c)) c,
    ],
    playlists: [
      for (final p in content.playlists)
        if (!ids.contains(p.id)) p,
    ],
  );
}

/// The hero footnote from an album: year, songs (> 0), minutes (> 60 s), version.
HeroFootnote? footnoteOf(Album album) {
  final seconds = album.durationSec;
  final footnote = HeroFootnote(
    year: album.year,
    songCount: (album.songCount ?? 0) > 0 ? album.songCount : (album.tracks.isNotEmpty ? album.tracks.length : null),
    minutes: seconds != null && seconds > 60 ? seconds ~/ 60 : null,
    version: album.version,
  );
  return footnote.isEmpty ? null : footnote;
}

final homeViewModelProvider = NotifierProvider<HomeViewModel, HomeUiState>(HomeViewModel.new);
