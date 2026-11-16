import 'dart:async';
import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/home/home_snapshot_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/home/home_snapshot_mapping.dart';
import 'package:yoin/home/home_ui_state.dart';
import 'package:yoin/home/home_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../support/fake_playback_engine.dart';
import 'home_fakes.dart';

void main() {
  late Directory temp;
  setUp(() {
    HomeViewModel.clearProcessCache();
    temp = Directory.systemTemp.createTempSync('yoin_home_vm_snapshot');
  });
  tearDown(() => temp.deleteSync(recursive: true));

  late HomeSnapshotStore store;
  HomeSnapshotStore snapshots() => store;
  setUp(() => store = HomeSnapshotStore(() async => temp, settleMs: 0, minWriteIntervalMs: 0));

  /// A container whose source arrives when [source] completes.
  Future<(ProviderContainer, String)> container(Future<MusicSource?> source, {MemoryActivityLog? log}) async {
    final c = ProviderContainer(
      overrides: [
        keyValueStoreProvider.overrideWithValue(InMemoryStore()),
        secretStoreProvider.overrideWithValue(InMemoryStore()),
        playbackEngineProvider.overrideWithValue(FakePlaybackEngine()),
        activeSourceProvider.overrideWith((ref) => source),
        activityLogProvider.overrideWith((ref) async => log ?? MemoryActivityLog()),
        homeClockProvider.overrideWithValue(() => homeTestNow),
        homeActivityDebounceProvider.overrideWithValue(const Duration(milliseconds: 5)),
        homeSnapshotStoreProvider.overrideWithValue(snapshots()),
      ],
    );
    final profile = await c
        .read(profileRepositoryProvider)
        .add(
          provider: MediaId.providerSubsonic,
          displayName: 'Navidrome',
          credentials: const SubsonicCredentials(baseUrl: 'https://music.test', username: 'u', password: 'p'),
        );
    return (c, profile.id);
  }

  HomeContent feed(String profileId) => HomeContent(
    scopeKey: 'subsonic|$profileId',
    provider: MediaId.providerSubsonic,
    activities: [albumEvent('a')],
    grid: [
      HomeWidgetCard(
        stableId: 'album:g',
        kind: HomeCardKind.album,
        title: 'Grid',
        subtitle: 'Artist',
        target: HomeOpenAlbum(album('g')),
        signal: const JbiSignal(scoreText: '8.5', basisTracks: (3, 9), comment: 'Night drive', commentIsHeadline: true),
      ),
    ],
    heroFootnote: const HeroFootnote(year: 1971, songCount: 10, minutes: 36),
    recentlyAddedTracks: [track('t1')],
    recentlyAddedAlbums: [album('s')],
    recentlyPlayedAll: [album('r1'), album('r2')],
    playlists: [playlist('p1')],
  );

  test('should_roundTripFeed_when_mapped', () {
    final c = feed('p');
    final back = homeContentFromSnapshot(homeSnapshotFeed(c), scopeKey: c.scopeKey, provider: c.provider, nowMs: 7);
    expect(back.sameFeedAs(c), isTrue);
    expect(back.grid.single.signal, c.grid.single.signal);
    expect(back.grid.single.target, isA<HomeOpenAlbum>());
    expect(back.activitiesAtMs, 7);
  });

  test('should_paintSnapshot_when_sourceNotUpYet', () async {
    final source = Completer<MusicSource?>();
    final (c, profileId) = await container(source.future);
    addTearDown(c.dispose);
    snapshots().save(profileId, MediaId.providerSubsonic, feed: () => homeSnapshotFeed(feed(profileId)));
    await store.whenIdle();

    c.listen(homeViewModelProvider, (_, _) {});
    await settle(() => c.read(homeViewModelProvider) is HomeContent);
    final painted = c.read(homeViewModelProvider) as HomeContent;
    expect(painted.playlists.single.id.rawId, 'p1');
    expect(painted.accountTitle, 'Navidrome');
    source.complete(HomeFakeSource()..failing.addAll({'playlists', 'starred', 'recent'}));
    await settle(() => source.isCompleted && c.read(homeViewModelProvider) is HomeContent, rounds: 5);
    await Future<void>.delayed(const Duration(milliseconds: 50));
    // Offline: every block's read failed, so each keeps what the snapshot showed — no spinner in between.
    final after = c.read(homeViewModelProvider) as HomeContent;
    expect(after.playlists.single.id.rawId, 'p1');
    expect(after.recentlyPlayedAll.map((a) => a.id.rawId), ['r1', 'r2']);
  });

  test('should_writeFeedBack_when_blocksLand', () async {
    final source = HomeFakeSource(playlists: [playlist('fresh')]);
    final (c, profileId) = await container(Future.value(source));
    addTearDown(c.dispose);
    c.listen(homeViewModelProvider, (_, _) {});
    await settle(() {
      final s = c.read(homeViewModelProvider);
      return s is HomeContent && s.playlists.isNotEmpty;
    });
    await settle(() => false, rounds: 10);
    await store.whenIdle();
    final written = await snapshots().read(profileId);
    expect(written, isNotNull);
    final back = homeContentFromSnapshot(written!.feed, scopeKey: '', provider: 'subsonic', nowMs: 0);
    expect(back.playlists.single.id.rawId, 'fresh');
  });
}
