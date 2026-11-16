import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/detail/playlist_detail_view_model.dart';
import 'package:yoin/library/playlist_deletions.dart';
import 'package:yoin/library/playlist_writes.dart';
import 'package:yoin/shell/app_messages.dart';

import '../support/fake_music_source.dart';

/// A source with playlists in memory and Subsonic-like playlist writes.
class PlaylistFakeSource extends FakeMusicSource implements MusicWriteActions, MusicPlaylistWrites {
  PlaylistFakeSource({this.edit = true, this.fail = false});

  final bool edit;
  bool fail;
  final calls = <String>[];
  final playlists = <MediaId, Playlist>{
    MediaId.subsonic('pl1'): Playlist(
      id: MediaId.subsonic('pl1'),
      name: 'Road',
      canWrite: true,
      tracks: [
        for (final id in ['a', 'b', 'c']) Track(id: MediaId.subsonic(id), title: id),
      ],
    ),
  };

  @override
  Set<Capability> get capabilities => {
    Capability.playlistsRead,
    Capability.playlistAdd,
    if (edit) Capability.playlistsWrite,
  };

  @override
  MusicWriteActions get writeActions => this;

  @override
  Future<List<Playlist>> getPlaylists() async => playlists.values.toList();
  @override
  Future<Playlist?> getPlaylist(MediaId id) async => playlists[id];

  void _call(String c) {
    calls.add(c);
    if (fail) throw const SourceServerException('refused');
  }

  @override
  Future<void> setFavorite(MediaId id, bool favorite) async {}
  @override
  Future<void> setRating(MediaId trackId, int rating) async {}

  @override
  bool acceptsTracks(Playlist playlist) => playlist.canWrite;

  @override
  Future<Playlist> createPlaylist(String name, {List<MediaId> tracks = const [], String? description}) async {
    _call('create:$name:${tracks.map((t) => t.rawId).join(',')}');
    final p = Playlist(id: MediaId.subsonic('new'), name: name, canWrite: true);
    playlists[p.id] = p;
    return p;
  }

  @override
  Future<void> addTracksToPlaylist(MediaId playlistId, List<MediaId> tracks) async =>
      _call('add:${playlistId.rawId}:${tracks.map((t) => t.rawId).join(',')}');
  @override
  Future<void> renamePlaylist(MediaId playlistId, String name) async {
    _call('rename:${playlistId.rawId}:$name');
    final p = playlists[playlistId]!;
    playlists[playlistId] = Playlist(id: p.id, name: name, canWrite: true, tracks: p.tracks);
  }

  @override
  Future<void> deletePlaylist(MediaId playlistId) async {
    _call('delete:${playlistId.rawId}');
    playlists.remove(playlistId);
  }

  @override
  Future<void> removeFromPlaylist(MediaId playlistId, List<int> positions) async {
    _call('remove:${playlistId.rawId}:${positions.join(',')}');
    final p = playlists[playlistId]!;
    final tracks = [...p.tracks];
    for (final i in [...positions]..sort((a, b) => b.compareTo(a))) {
      tracks.removeAt(i);
    }
    playlists[playlistId] = Playlist(id: p.id, name: p.name, canWrite: p.canWrite, tracks: tracks);
  }

  @override
  Future<void> setPlaylistTracks(MediaId playlistId, List<MediaId> tracks) async =>
      _call('order:${playlistId.rawId}:${tracks.map((t) => t.rawId).join(',')}');
}

void main() {
  late PlaylistFakeSource source;
  late ProviderContainer c;
  late List<String> messages;
  final pl1 = MediaId.subsonic('pl1');

  setUp(() {
    source = PlaylistFakeSource();
    messages = [];
    c = ProviderContainer(
      overrides: [
        activeSourceProvider.overrideWith((ref) async => source),
        appMessageSinkProvider.overrideWithValue(messages.add),
      ],
    );
    addTearDown(c.dispose);
  });

  group('PlaylistWrites', () {
    test('should_fileOnlyThisAccountsSongs_when_creating', () async {
      await c.read(activeSourceProvider.future);
      await c
          .read(playlistWritesProvider)
          .create(
            ' Mix ',
            tracks: [
              Track(id: MediaId.subsonic('a')),
              Track(id: MediaId(MediaId.providerAppleMusic, '9')),
            ],
          );
      expect(source.calls, ['create:Mix:a']);
      expect(c.read(playlistsRevisionProvider), 1);
    });

    test('should_throwUnsupported_when_accountHasNoPlaylistWrites', () async {
      final plain = FakeMusicSource();
      final c2 = ProviderContainer(overrides: [activeSourceProvider.overrideWith((ref) async => plain)]);
      addTearDown(c2.dispose);
      await expectLater(c2.read(playlistWritesProvider).rename(pl1, 'x'), throwsA(isA<UnsupportedSourceOperation>()));
      expect(c2.read(playlistsRevisionProvider), 0);
    });

    test('should_removeTheSongWhereTheServiceHasItNow_when_thePagesPositionIsStale', () async {
      // Another client put X first: the page's position 1 ('b') is 'a' on the service now.
      final p = source.playlists[pl1]!;
      source.playlists[pl1] = Playlist(
        id: pl1,
        name: p.name,
        canWrite: true,
        tracks: [Track(id: MediaId.subsonic('x')), ...p.tracks],
      );
      await c.read(playlistWritesProvider).remove(pl1, [(track: MediaId.subsonic('b'), position: 1)]);
      expect(source.calls, ['remove:pl1:2']);
      expect([for (final t in source.playlists[pl1]!.tracks) t.id.rawId], ['x', 'a', 'c']);
      expect(c.read(playlistsRevisionProvider), 1);
    });

    test('should_sendNothing_when_theSongIsGoneFromTheServiceAlready', () async {
      await c.read(playlistWritesProvider).remove(pl1, [(track: MediaId.subsonic('z'), position: 1)]);
      expect(source.calls, isEmpty);
    });

    test('should_takeTheNearestEntry_when_theSongIsListedTwice', () {
      final ids = [for (final r in ['a', 'b', 'a', 'c', 'a']) MediaId.subsonic(r)];
      expect(playlistIndexNearest(ids, MediaId.subsonic('a'), 2), 2);
      expect(playlistIndexNearest(ids, MediaId.subsonic('a'), 3), 2);
      expect(playlistIndexNearest(ids, MediaId.subsonic('a'), 5), 4);
      expect(playlistIndexNearest(ids, MediaId.subsonic('z'), 0), isNull);
    });

    test('should_tellAddFromEdit_when_readingAbilities', () {
      expect(playlistAbilities(PlaylistFakeSource()), (canAdd: true, canEdit: true));
      expect(playlistAbilities(PlaylistFakeSource(edit: false)), (canAdd: true, canEdit: false));
      expect(playlistAbilities(FakeMusicSource()), (canAdd: false, canEdit: false));
      expect(playlistAbilities(null), (canAdd: false, canEdit: false));
    });
  });

  group('PlaylistDetailNotifier writes', () {
    Future<PlaylistDetailContent> loaded() async {
      final sub = c.listen(playlistDetailProvider(pl1), (_, _) {});
      addTearDown(sub.close);
      while (c.read(playlistDetailProvider(pl1)) is! PlaylistDetailContent) {
        await Future<void>.delayed(Duration.zero);
      }
      return c.read(playlistDetailProvider(pl1)) as PlaylistDetailContent;
    }

    Future<void> settle() async {
      for (var i = 0; i < 20; i++) {
        await Future<void>.delayed(Duration.zero);
      }
    }

    test('should_dropRowAtOnce_when_removing', () async {
      final content = await loaded();
      c.read(playlistDetailProvider(pl1).notifier).removeTrack(content.entries[1].rowKey, PlaylistEditLines.en);
      final shown = c.read(playlistDetailProvider(pl1)) as PlaylistDetailContent;
      expect([for (final t in shown.tracks) t.id.rawId], ['a', 'c']);
      await settle();
      expect(source.calls, ['remove:pl1:1']);
    });

    test('should_putRowBack_when_removeRefused', () async {
      final content = await loaded();
      source.fail = true;
      c.read(playlistDetailProvider(pl1).notifier).removeTrack(content.entries[1].rowKey, PlaylistEditLines.en);
      await settle();
      final state = c.read(playlistDetailProvider(pl1)) as PlaylistDetailContent;
      expect([for (final t in state.tracks) t.id.rawId], ['a', 'b', 'c']);
      expect(messages, ["Couldn't remove track"]);
    });

    test('should_sendNewOrder_when_moving', () async {
      await loaded();
      final vm = c.read(playlistDetailProvider(pl1).notifier);
      final pending = vm.move(0, 2);
      final shown = c.read(playlistDetailProvider(pl1)) as PlaylistDetailContent;
      expect([for (final t in shown.tracks) t.id.rawId], ['b', 'c', 'a']);
      expect(await pending, isTrue);
      expect(source.calls, ['order:pl1:b,c,a']);
    });

    test('should_rereadName_when_renamed', () async {
      await loaded();
      c.read(playlistDetailProvider(pl1).notifier).rename('Night', PlaylistEditLines.en);
      await settle();
      final state = c.read(playlistDetailProvider(pl1)) as PlaylistDetailContent;
      expect(state.playlist.name, 'Night');
      expect(messages, ['Renamed']);
    });

    test('should_reportFailure_when_deleteRefused', () async {
      await loaded();
      source.fail = true;
      final handed = c.read(playlistDetailProvider(pl1).notifier).delete((deleted: 'gone', failed: 'kept'));
      expect(handed, isTrue);
      await settle();
      expect(c.read(playlistsRevisionProvider), 0);
      expect(c.read(playlistDeletionsProvider).pending, isEmpty);
      expect(messages, ['kept']);
    });
  });
}
