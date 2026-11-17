import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_grid_pool_store.dart';
import 'package:yoin/home/home_selection.dart';
import 'package:yoin/home/home_ui_state.dart';
import 'package:yoin/home/home_view_model.dart';
import 'package:yoin/library/playlist_renames.dart';
import 'package:yoin/platform/storage/stores.dart';

import 'home_fakes.dart';

String _profileId(InMemoryStore store) {
  final profiles = jsonDecode(store.values['profiles.v1']!) as List;
  return (profiles.single as Map)['id'] as String;
}

Future<HomeContent> _content(ProviderContainer c, {bool Function(HomeContent)? until}) async {
  c.listen(homeViewModelProvider, (_, _) {});
  await settle(() {
    final s = c.read(homeViewModelProvider);
    return s is HomeContent && (until?.call(s) ?? true);
  });
  return c.read(homeViewModelProvider) as HomeContent;
}

bool _isP1(HomeWidgetCard card) => card.stableId == 'grid-playlist:p1';

void main() {
  setUp(HomeViewModel.clearProcessCache);

  group('homeContentWithPlaylistName', () {
    final renamed = MediaId.subsonic('p1');
    final content = HomeContent(
      scopeKey: '${MediaId.providerSubsonic}|me',
      provider: MediaId.providerSubsonic,
      activities: [playlistEvent('p1'), playlistEvent('p2', minutesAgo: 9), albumEvent('p1', minutesAgo: 20)],
      grid: buildWidgetGrid(
        HomeGridPools(playlists: [playlist('p1'), playlist('p2')], albums: [album('a')], cachedAtMs: 0),
        limit: null,
      ),
      playlists: [playlist('p1'), playlist('p2')],
    );

    test('should_takeTheNewNameEverywhere_when_homeShowsThePlaylist', () {
      final next = homeContentWithPlaylistName(content, renamed, 'Night');
      expect([for (final e in next.activities) e.title], ['Night', 'Playlist p2', 'Album p1']);
      final card = next.grid.singleWhere(_isP1);
      expect(card.title, 'Night');
      expect((card.target as HomeOpenPlaylist).playlist.name, 'Night');
      expect([for (final p in next.playlists) p.name], ['Night', 'Playlist p2']);
    });

    test('should_returnTheSameFeed_when_homeDoesNotShowItUnderAnotherName', () {
      expect(identical(homeContentWithPlaylistName(content, MediaId.subsonic('nope'), 'Night'), content), isTrue);
      final once = homeContentWithPlaylistName(content, renamed, 'Night');
      expect(identical(homeContentWithPlaylistName(once, renamed, 'Night'), once), isTrue);
    });
  });

  test('should_renameHomesFeedAndStoredRows_when_aRenameIsTaken', () async {
    final source = HomeFakeSource(playlists: [playlist('p1'), playlist('p2')]);
    final log = MemoryActivityLog([playlistEvent('p1'), albumEvent('a', minutesAgo: 30)]);
    final h = await homeContainer(source: source, log: log);
    addTearDown(h.container.dispose);
    final profileId = _profileId(h.store);
    final pools = HomeGridPoolStore(h.store);
    await pools.write(
      profileId,
      MediaId.providerSubsonic,
      HomeGridPools(playlists: [playlist('p1'), playlist('p2')], cachedAtMs: homeTestNow.millisecondsSinceEpoch),
    );
    await _content(h.container, until: (c) => c.playlists.length == 2 && c.grid.any(_isP1));

    await h.container.read(playlistRenamesProvider.notifier).renamed(profileId, MediaId.subsonic('p1'), 'Night');
    final content = await _content(
      h.container,
      until: (c) => c.playlists.first.name == 'Night' && c.activities.any((e) => e.title == 'Night'),
    );
    expect(content.grid.singleWhere(_isP1).title, 'Night');
    expect([for (final e in await log.recent()) e.title], ['Night', 'Album a']);
    final stored = await pools.read(profileId, MediaId.providerSubsonic);
    expect([for (final p in stored!.playlists) p.name], ['Night', 'Playlist p2']);
  });

  test('should_leaveHomeAlone_when_theRenameWasOnAnotherAccount', () async {
    final source = HomeFakeSource(playlists: [playlist('p1')]);
    final h = await homeContainer(source: source, log: MemoryActivityLog([playlistEvent('p1')]));
    addTearDown(h.container.dispose);
    await _content(h.container, until: (c) => c.playlists.length == 1);

    await h.container.read(playlistRenamesProvider.notifier).renamed('elsewhere', MediaId.subsonic('p1'), 'Night');
    await settle(() => false, rounds: 10);
    final content = h.container.read(homeViewModelProvider) as HomeContent;
    expect(content.playlists.single.name, 'Playlist p1');
    expect([for (final e in content.activities) e.title], ['Playlist p1']);
  });
}
