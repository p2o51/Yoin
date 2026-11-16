import 'dart:async';
import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/home/home_grid_pool_store.dart';
import 'package:yoin/home/home_selection.dart';
import 'package:yoin/home/home_ui_state.dart';
import 'package:yoin/home/home_view_model.dart';
import 'package:yoin/library/playlist_deletions.dart';
import 'package:yoin/platform/storage/stores.dart';

import 'home_fakes.dart';

/// Home's service, able to delete playlists; [hold] keeps a deletion out, [refuse] refuses it.
class _DeletingHomeSource extends HomeFakeSource implements MusicWriteActions, MusicPlaylistWrites {
  _DeletingHomeSource({super.playlists});

  final hold = <Completer<void>>[];
  Object? refuse;

  @override
  Set<Capability> get capabilities => {...super.capabilities, Capability.playlistAdd, Capability.playlistsWrite};
  @override
  MusicWriteActions get writeActions => this;

  @override
  Future<void> deletePlaylist(MediaId playlistId) async {
    if (hold.isNotEmpty) await hold.removeAt(0).future;
    if (refuse case final error?) throw error;
    playlists = [
      for (final p in playlists)
        if (p.id != playlistId) p,
    ];
  }

  @override
  Future<Playlist> createPlaylist(String name, {List<MediaId> tracks = const [], String? description}) =>
      throw UnimplementedError();
  @override
  Future<void> addTracksToPlaylist(MediaId playlistId, List<MediaId> tracks) async {}
  @override
  Future<void> renamePlaylist(MediaId playlistId, String name) async {}
  @override
  Future<void> removeFromPlaylist(MediaId playlistId, List<int> positions) async {}
  @override
  Future<void> setPlaylistTracks(MediaId playlistId, List<MediaId> tracks) async {}
  @override
  bool acceptsTracks(Playlist playlist) => true;
  @override
  Future<void> setFavorite(MediaId id, bool favorite) async {}
  @override
  Future<void> setRating(MediaId trackId, int rating) async {}
}

const _lines = (deleted: 'Playlist deleted', failed: "Couldn't delete playlist");

Future<HomeContent> _content(ProviderContainer c, {bool Function(HomeContent)? until}) async {
  c.listen(homeViewModelProvider, (_, _) {});
  await settle(() {
    final s = c.read(homeViewModelProvider);
    return s is HomeContent && (until?.call(s) ?? true);
  });
  return c.read(homeViewModelProvider) as HomeContent;
}

void _seedPools(InMemoryStore store, HomeGridPools pools) {
  final profiles = jsonDecode(store.values['profiles.v1']!) as List;
  final id = (profiles.single as Map)['id'] as String;
  store.values[HomeGridPoolStore.keyFor(id, MediaId.providerSubsonic)] = jsonEncode(pools.toJson());
}

void main() {
  setUp(HomeViewModel.clearProcessCache);

  group('homeContentWithoutPlaylists', () {
    final gone = MediaId.subsonic('p1');
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

    test('should_dropTheDeletedPlaylistEverywhere_when_homeShowsIt', () {
      final kept = homeContentWithoutPlaylists(content, {gone});
      expect([for (final e in kept.activities) e.entityKey], ['playlist:p2', 'album:p1']);
      expect([for (final c in kept.grid) c.stableId], isNot(contains('grid-playlist:p1')));
      expect([for (final c in kept.grid) c.stableId], contains('grid-playlist:p2'));
      expect([for (final p in kept.playlists) p.id.rawId], ['p2']);
    });

    test('should_returnTheSameFeed_when_homeDoesNotShowIt', () {
      expect(identical(homeContentWithoutPlaylists(content, {MediaId.subsonic('nope')}), content), isTrue);
    });
  });

  group('a playlist deleted on its page', () {
    test('should_leaveHomesFeedAndStoredRows_when_theServiceDeletesIt', () async {
      final source = _DeletingHomeSource(playlists: [playlist('p1'), playlist('p2')]);
      final log = MemoryActivityLog([playlistEvent('p1'), albumEvent('a', minutesAgo: 30)]);
      final h = await homeContainer(source: source, log: log);
      addTearDown(h.container.dispose);
      _seedPools(
        h.store,
        HomeGridPools(playlists: [playlist('p1'), playlist('p2')], cachedAtMs: homeTestNow.millisecondsSinceEpoch),
      );
      await _content(h.container, until: (c) => c.playlists.length == 2 && c.grid.isNotEmpty);
      h.container.listen(playlistDeletionsProvider, (_, _) {});

      final gate = Completer<void>();
      source.hold.add(gate);
      h.container.read(playlistDeletionsProvider.notifier).delete(MediaId.subsonic('p1'), _lines);
      await settle(() => false, rounds: 5);
      // Still on its way out: Home keeps it, so a refusal costs Home nothing.
      expect((h.container.read(homeViewModelProvider) as HomeContent).playlists, hasLength(2));

      gate.complete();
      final content = await _content(h.container, until: (c) => c.playlists.length == 1);
      expect([for (final p in content.playlists) p.id.rawId], ['p2']);
      expect([for (final c in content.grid) c.stableId], isNot(contains('grid-playlist:p1')));
      expect([for (final e in content.activities) e.entityKey], isNot(contains('playlist:p1')));
      expect([for (final e in await log.recent()) e.entityKey], ['album:a']);
    });

    test('should_keepItOnHome_when_theServiceRefuses', () async {
      final source = _DeletingHomeSource(playlists: [playlist('p1'), playlist('p2')])
        ..refuse = const SourceServerException('refused');
      final log = MemoryActivityLog([playlistEvent('p1')]);
      final h = await homeContainer(source: source, log: log);
      addTearDown(h.container.dispose);
      await _content(h.container, until: (c) => c.playlists.length == 2);
      h.container.listen(playlistDeletionsProvider, (_, _) {});

      h.container.read(playlistDeletionsProvider.notifier).delete(MediaId.subsonic('p1'), _lines);
      await settle(() => false, rounds: 10);
      final content = h.container.read(homeViewModelProvider) as HomeContent;
      expect(content.playlists, hasLength(2));
      expect([for (final e in content.activities) e.entityKey], contains('playlist:p1'));
      expect(await log.recent(), hasLength(1));
    });
  });
}
