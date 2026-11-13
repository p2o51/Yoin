import 'dart:async';
import 'dart:math' as math;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/activity/activity.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';
import '../playback/playback_controller.dart';
import 'home_grid_pool_store.dart';
import 'home_metrics.dart';
import 'home_selection.dart';
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

  @override
  HomeUiState build() {
    final source = ref.watch(activeSourceProvider);
    ref.onDispose(_stop);
    return switch (source) {
      AsyncData(:final value) => _start(value),
      // Credentials unreadable: a sourceless load (local tier only) publishes once.
      AsyncError() => _start(null),
      _ => const HomeLoading(),
    };
  }

  /// Retry (Error page) or an explicit refresh: reloads the active scope.
  void refresh() {
    final source = ref.read(activeSourceProvider);
    if (source is AsyncLoading) return;
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

  HomeUiState _start(MusicSource? source) {
    _stop();
    final generation = _generation;
    _heroKey = null;
    Future.microtask(() => _load(source, generation));
    return const HomeLoading();
  }

  bool _live(int generation) => ref.mounted && generation == _generation;

  int get _nowMs => ref.read(homeClockProvider)().millisecondsSinceEpoch;

  void _publish(int generation, HomeContent content) {
    if (!_live(generation)) return;
    final current = state;
    if (current is HomeContent && current.sameFeedAs(content)) return;
    state = content;
    if (content.scopeKey.isNotEmpty) _contentCache[content.scopeKey] = content;
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
      _publish(generation, HomeContent(scopeKey: '', provider: MediaId.providerSubsonic));
      return;
    }
    final provider = source?.provider ?? profile.provider;
    final scopeKey = '$provider|${profile.id}';
    final cached = _contentCache[scopeKey];
    if (cached != null) _publish(generation, cached.copyWith(accountTitle: profile.displayName));

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
      grid: pools == null ? null : buildWidgetGrid(pools),
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
      final shelf = distinctBy(list, (p) => p.id).take(homePlaylistLimit).toList();
      _splice(generation, (c) => c.copyWith(playlists: shelf));
    }));

    unawaited(_guard(source.library.getStarred()).then((starred) {
      if (starred == null) return;
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
    final grid = buildWidgetGrid(pools);
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
