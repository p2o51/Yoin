import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_grid_pool_store.dart';
import 'package:yoin/home/home_ui_state.dart';
import 'package:yoin/home/home_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';

import 'home_fakes.dart';

HomeUiState _state(ProviderContainer c) => c.read(homeViewModelProvider);

Future<HomeContent> _content(ProviderContainer c, {bool Function(HomeContent)? until}) async {
  c.listen(homeViewModelProvider, (_, _) {});
  await settle(() {
    final s = _state(c);
    return s is HomeContent && (until?.call(s) ?? true);
  });
  return _state(c) as HomeContent;
}

Future<void> _seedPools(InMemoryStore store, HomeGridPools pools) async {
  // The profile id is random: find it through the stored list.
  final profiles = jsonDecode(store.values['profiles.v1']!) as List;
  final id = (profiles.single as Map)['id'] as String;
  store.values[HomeGridPoolStore.keyFor(id, MediaId.providerSubsonic)] = jsonEncode(pools.toJson());
}

void main() {
  setUp(HomeViewModel.clearProcessCache);

  test('should_publishEmptyFeedWithoutAccount_when_noProfile', () async {
    final h = await homeContainer(withAccount: false);
    addTearDown(h.container.dispose);

    final content = await _content(h.container);

    expect(content.hasAccount, isFalse);
    expect(content.activityEntries, isEmpty);
  });

  test('should_publishLocalTierActivitiesAndAccount_when_loaded', () async {
    final log = MemoryActivityLog([albumEvent('a', minutesAgo: 1), songEvent('s', minutesAgo: 2), artistEvent('r', minutesAgo: 3)]);
    final h = await homeContainer(log: log);
    addTearDown(h.container.dispose);

    final content = await _content(h.container);

    expect(content.accountTitle, 'Navidrome');
    expect(content.activityEntries.map((e) => e.entityKey), ['album:a', 'artist:r']);
    expect(content.activitiesAtMs, homeTestNow.millisecondsSinceEpoch);
  });

  test('should_spliceNetworkBlocks_when_theyLand', () async {
    final source = HomeFakeSource(
      playlists: [playlist('p1'), playlist('p1'), playlist('p2')],
      recent: [album('a'), album('b')],
      starred: Starred(
        tracks: [track('new', starred: '2026-11-10T00:00:00Z'), track('old', starred: '2025-01-01T00:00:00Z')],
        albums: [album('s', starred: '2026-11-11T08:00:00Z')],
      ),
    );
    final h = await homeContainer(source: source, log: MemoryActivityLog([albumEvent('a')]));
    addTearDown(h.container.dispose);

    final content = await _content(h.container, until: (c) => c.playlists.isNotEmpty && c.recentlyPlayedAll.isNotEmpty && c.recentlyAddedAlbums.isNotEmpty);

    expect(content.playlists.map((p) => p.id.rawId), ['p1', 'p2']);
    expect(content.recentlyPlayed.map((a) => a.id.rawId), ['b'], reason: 'album a is already in Activities');
    expect(content.recentlyAddedTracks.map((t) => t.id.rawId), ['new']);
    expect(content.recentlyAddedAlbums.map((a) => a.id.rawId), ['s']);
  });

  test('should_rotateAndPersistPools_when_noneStored', () async {
    final source = HomeFakeSource(
      random: [for (var i = 0; i < 5; i++) album('a$i')],
      randomSongs: [track('t1'), track('t2')],
      playlists: [playlist('p1')],
    );
    final h = await homeContainer(source: source);
    addTearDown(h.container.dispose);

    final content = await _content(h.container, until: (c) => c.grid.isNotEmpty);

    expect(content.grid.length, 8);
    expect(source.calls, containsAll(['random', 'randomSongs', 'playlists']));
    await settle(() => h.store.values.keys.any((k) => k.startsWith('home.gridPools')));
    expect(h.store.values.keys.where((k) => k.startsWith('home.gridPools')), hasLength(1));
  });

  test('should_paintStoredPoolsWithoutRotating_when_poolsFresh', () async {
    final source = HomeFakeSource(random: [album('fresh')]);
    final store = InMemoryStore();
    final h = await homeContainer(source: source, store: store);
    addTearDown(h.container.dispose);
    await _seedPools(store, HomeGridPools(cachedAtMs: homeTestNow.millisecondsSinceEpoch - 60000, albums: [album('stored')]));

    final content = await _content(h.container, until: (c) => c.grid.isNotEmpty);
    await settle(() => false, rounds: 5);

    expect(content.grid.single.stableId, 'grid-album:stored');
    expect(source.calls, isNot(contains('random')));
  });

  test('should_keepOldPools_when_everyRotationReadFails', () async {
    final source = HomeFakeSource()..failing.addAll(['random', 'randomSongs', 'playlists']);
    final store = InMemoryStore();
    final h = await homeContainer(source: source, store: store);
    addTearDown(h.container.dispose);
    await _seedPools(store, HomeGridPools(cachedAtMs: 0, albums: [album('stale')]));

    final content = await _content(h.container, until: (c) => c.grid.isNotEmpty);
    await settle(() => false, rounds: 5);

    expect((_state(h.container) as HomeContent).grid.single.stableId, 'grid-album:stale');
    expect(content.grid.single.stableId, 'grid-album:stale');
  });

  test('should_keepBlockOnFailureAndClearOnEmpty_when_refreshed', () async {
    final source = HomeFakeSource(playlists: [playlist('p1')], recent: [album('r')]);
    final h = await homeContainer(source: source);
    addTearDown(h.container.dispose);
    await _content(h.container, until: (c) => c.playlists.isNotEmpty && c.recentlyPlayedAll.isNotEmpty);

    source.failing.add('playlists');
    source.recent = const [];
    h.container.read(homeViewModelProvider.notifier).refresh();
    await settle(() => false, rounds: 10);

    final content = _state(h.container) as HomeContent;
    expect(content.playlists.single.id.rawId, 'p1');
    expect(content.recentlyPlayedAll, isEmpty);
  });

  test('should_updateActivitiesLive_when_logRecords', () async {
    final log = MemoryActivityLog([albumEvent('a', minutesAgo: 10)]);
    final h = await homeContainer(log: log);
    addTearDown(h.container.dispose);
    await _content(h.container);

    await log.record(playlistEvent('p', minutesAgo: 0));
    final content = await _content(h.container, until: (c) => c.activityEntries.length == 2);

    expect(content.activityEntries.first.entityKey, 'playlist:p');
  });

  test('should_resolveHeroFootnote_when_heroIsAlbum', () async {
    final source = HomeFakeSource(albums: {
      'a': Album(id: MediaId.subsonic('a'), name: 'A', year: 1997, songCount: 12, durationSec: 3000),
    });
    final h = await homeContainer(source: source, log: MemoryActivityLog([albumEvent('a')]));
    addTearDown(h.container.dispose);

    final content = await _content(h.container, until: (c) => c.heroFootnote != null);

    expect(content.heroFootnote, const HeroFootnote(year: 1997, songCount: 12, minutes: 50));
  });

  test('should_dropFootnote_when_heroBecomesPlaylist', () async {
    final source = HomeFakeSource(albums: {'a': Album(id: MediaId.subsonic('a'), name: 'A', year: 2001)});
    final log = MemoryActivityLog([albumEvent('a', minutesAgo: 10)]);
    final h = await homeContainer(source: source, log: log);
    addTearDown(h.container.dispose);
    await _content(h.container, until: (c) => c.heroFootnote != null);

    await log.record(playlistEvent('p', minutesAgo: 0));
    final content = await _content(h.container, until: (c) => c.heroFootnote == null);

    expect(content.activityEntries.first.entityKey, 'playlist:p');
  });

  test('should_showError_when_localTierThrowsWithNothingUp', () async {
    final h = await homeContainer(log: MemoryActivityLog()..failReads = true);
    addTearDown(h.container.dispose);
    h.container.listen(homeViewModelProvider, (_, _) {});

    await settle(() => _state(h.container) is HomeError);

    expect(_state(h.container), isA<HomeError>());
  });

  test('should_playOneTrack_when_songTapped', () async {
    final h = await homeContainer();
    addTearDown(h.container.dispose);
    await _content(h.container);

    await h.container.read(homeViewModelProvider.notifier).playSong(track('t1'));

    expect(h.engine.queue.single.id, 'subsonic:t1');
  });

  test('should_ignoreSongFromOtherProvider_when_tapped', () async {
    final h = await homeContainer();
    addTearDown(h.container.dispose);
    await _content(h.container);

    await h.container.read(homeViewModelProvider.notifier).playSong(Track(id: MediaId('spotify', 'x')));

    expect(h.engine.calls, isEmpty);
  });

  test('should_paintCachedContentAtOnce_when_homeRecreatedForSameScope', () async {
    final store = InMemoryStore();
    final first = await homeContainer(store: store, source: HomeFakeSource(playlists: [playlist('p1')]));
    await _content(first.container, until: (c) => c.playlists.isNotEmpty);
    first.container.dispose();

    // Same store → same profile id → same scope; this source has no playlists yet.
    final source = HomeFakeSource()..failing.add('playlists');
    final second = await homeContainer(store: store, source: source, addProfile: false);
    addTearDown(second.container.dispose);
    final content = await _content(second.container);

    expect(content.playlists.single.id.rawId, 'p1');
  });
}
