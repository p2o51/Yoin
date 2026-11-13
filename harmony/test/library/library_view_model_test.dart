import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/activity/activity.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/library/library_stores.dart';
import 'package:yoin/library/library_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../support/fake_music_source.dart';
import '../support/library_fake_source.dart';

ProviderContainer _container(MusicSource? source, {InMemoryStore? store, ActivityLog? log}) {
  final kv = store ?? InMemoryStore();
  return ProviderContainer(
    overrides: [
      activeSourceProvider.overrideWith((ref) async => source),
      keyValueStoreProvider.overrideWithValue(kv),
      secretStoreProvider.overrideWithValue(InMemoryStore()),
      activityLogProvider.overrideWith((ref) async => log),
    ],
  );
}

Future<void> _pump([int times = 30]) async {
  for (var i = 0; i < times; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

Future<LibraryUiState> _settled(ProviderContainer c) async {
  c.listen(libraryViewModelProvider, (_, _) {});
  for (var i = 0; i < 50 && c.read(libraryViewModelProvider) is LibraryLoading; i++) {
    await Future<void>.delayed(Duration.zero);
  }
  await _pump();
  return c.read(libraryViewModelProvider);
}

LibraryContent _content(ProviderContainer c) => c.read(libraryViewModelProvider) as LibraryContent;

LibraryFakeSource _library({int albums = 3}) => LibraryFakeSource(
  artists: [
    LibraryFixtures.artist('ar1', name: 'Beck'),
    LibraryFixtures.artist('ar2', name: 'ABBA'),
  ],
  albums: [for (var i = 0; i < albums; i++) LibraryFixtures.album('al$i', addedAt: '2024-0${i % 9 + 1}-01')],
  playlists: [Playlist(id: MediaId.subsonic('pl1'), name: 'Mix')],
);

void main() {
  test('should_offerConnect_when_noAccount', () async {
    final c = _container(null);
    addTearDown(c.dispose);
    expect(await _settled(c), isA<LibraryNoAccount>());
  });

  test('should_probeArtistsOnly_when_coldStart', () async {
    final source = _library();
    final c = _container(source);
    addTearDown(c.dispose);

    final state = await _settled(c);

    expect(state, isA<LibraryContent>());
    final content = state as LibraryContent;
    expect(content.selected, LibraryTab.all);
    expect(content.artists, hasLength(2));
    expect(content.albums, isNull);
    expect(content.playlists, isNull);
    expect(source.albumListCalls, isEmpty);
    expect(content.availableTabs, [
      LibraryTab.playlists,
      LibraryTab.artists,
      LibraryTab.albums,
      LibraryTab.songs,
      LibraryTab.favorites,
    ]);
  });

  test('should_showError_when_artistProbeFails', () async {
    final source = _library()..failures['getArtists'] = const SourceNetworkException('offline');
    final c = _container(source);
    addTearDown(c.dispose);
    expect(await _settled(c), isA<LibraryError>().having((e) => e.error, 'error', isA<SourceNetworkException>()));
  });

  test('should_loadAlbumsAndPlaylists_when_libraryShown', () async {
    final source = _library();
    final c = _container(source);
    addTearDown(c.dispose);
    await _settled(c);

    c.read(libraryViewModelProvider.notifier).onShown();
    await _pump();

    final content = _content(c);
    expect(content.albums, hasLength(3));
    expect(content.playlists, hasLength(1));
    expect(content.allSettled, isTrue);
    expect(content.sorted.all!.items, hasLength(6));
    expect(source.albumListCalls.single, (type: AlbumListType.newest, size: 500, offset: 0));
  });

  test('should_readAlbumsInBatches_when_libraryIsLarge', () async {
    final source = _library(albums: 1200);
    final c = _container(source);
    addTearDown(c.dispose);
    await _settled(c);

    await c.read(libraryViewModelProvider.notifier).selectTab(LibraryTab.albums);
    await _pump();

    expect(source.albumListCalls.map((call) => call.offset), [0, 500, 1000]);
    expect(_content(c).albums, hasLength(1200));
  });

  test('should_stepBackAndNotify_when_chipFetchFails', () async {
    final source = _library()..failures['getStarred'] = const SourceNetworkException('offline');
    final c = _container(source);
    addTearDown(c.dispose);
    await _settled(c);

    await c.read(libraryViewModelProvider.notifier).selectTab(LibraryTab.favorites);

    final content = _content(c);
    expect(content.selected, LibraryTab.all);
    expect(content.notice?.tab, LibraryTab.favorites);
  });

  test('should_returnToAll_when_selectedChipTappedAgain', () async {
    final c = _container(_library());
    addTearDown(c.dispose);
    await _settled(c);
    final vm = c.read(libraryViewModelProvider.notifier);

    await vm.selectTab(LibraryTab.artists);
    expect(_content(c).selected, LibraryTab.artists);
    await vm.selectTab(LibraryTab.artists);
    expect(_content(c).selected, LibraryTab.all);
  });

  test('should_persistAndResort_when_sortChosen', () async {
    final store = InMemoryStore();
    final c = _container(_library(), store: store);
    addTearDown(c.dispose);
    await _settled(c);
    final vm = c.read(libraryViewModelProvider.notifier);

    await vm.selectSort(LibraryTab.artists, LibrarySort.alphabetical);
    await _pump();

    expect(_content(c).sortOf(LibraryTab.artists), LibrarySort.alphabetical);
    expect([for (final i in _content(c).sorted.artists!.items) i.name], ['ABBA', 'Beck']);
  });

  test('should_readStoredSort_when_coldStart', () async {
    final store = InMemoryStore();
    await LibrarySortStore(store).write('p1', LibraryTab.artists, LibrarySort.alphabetical);
    final sorts = await LibrarySortStore(store).read('p1');
    expect(sorts[LibraryTab.artists], LibrarySort.alphabetical);
    store.values['library.sort.v1/p1/Albums'] = 'bogus';
    expect((await LibrarySortStore(store).read('p1')).containsKey(LibraryTab.albums), isFalse);
  });

  test('should_pageSongs_when_songsTabNearsEnd', () async {
    final details = <MediaId, Album>{};
    final albums = <Album>[];
    for (var i = 0; i < 15; i++) {
      final a = LibraryFixtures.album('s$i', tracks: [LibraryFixtures.track('t$i', albumId: 's$i')]);
      details[a.id] = a;
      albums.add(LibraryFixtures.album('s$i'));
    }
    final source = LibraryFakeSource(albums: albums, albumDetails: details);
    final c = _container(source);
    addTearDown(c.dispose);
    await _settled(c);
    final vm = c.read(libraryViewModelProvider.notifier);

    await vm.selectTab(LibraryTab.songs);
    await _pump();
    expect(_content(c).songs, hasLength(10));
    expect(_content(c).songsMore, SongsMore.available);

    await vm.loadMoreSongs();
    expect(_content(c).songs, hasLength(15));
    expect(_content(c).songsMore, SongsMore.none);
  });

  test('should_keepRowsAndNotify_when_refreshFails', () async {
    final source = _library();
    final c = _container(source);
    addTearDown(c.dispose);
    await _settled(c);
    final vm = c.read(libraryViewModelProvider.notifier);
    await vm.selectTab(LibraryTab.playlists);
    await _pump();

    source.failures['getPlaylists'] = const SourceNetworkException('offline');
    await vm.refresh();

    final content = _content(c);
    expect(content.playlists, hasLength(1));
    expect(content.isRefreshing, isFalse);
    expect(content.notice?.tab, LibraryTab.playlists);
  });

  test('should_sortByActivityLog_when_recents', () async {
    final store = InMemoryStore();
    final log = StoredActivityLog(store, profileId: 'p');
    await log.record(
      ActivityEvent(
        entityType: ActivityEntityType.artist,
        actionType: ActivityActionType.visited,
        entityId: 'ar1',
        title: 'Beck',
        timestampMs: DateTime.now().millisecondsSinceEpoch,
      ),
    );
    final c = _container(_library(), store: store, log: log);
    addTearDown(c.dispose);
    await _settled(c);

    expect([for (final i in _content(c).sorted.artists!.items) i.name], ['Beck', 'ABBA']);
  });

  test('should_keepOldFakeSourceWorking_when_noArtists', () async {
    final c = _container(FakeMusicSource());
    addTearDown(c.dispose);
    final state = await _settled(c);
    expect(state, isA<LibraryContent>().having((s) => s.artists, 'artists', isEmpty));
  });
}
