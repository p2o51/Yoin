import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/detail/playlist_detail_view_model.dart';
import 'package:yoin/library/playlist_writes.dart';

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
  Future<void> removeFromPlaylist(MediaId playlistId, List<int> positions) async =>
      _call('remove:${playlistId.rawId}:${positions.join(',')}');
  @override
  Future<void> setPlaylistTracks(MediaId playlistId, List<MediaId> tracks) async =>
      _call('order:${playlistId.rawId}:${tracks.map((t) => t.rawId).join(',')}');
}

void main() {
  late PlaylistFakeSource source;
  late ProviderContainer c;
  final pl1 = MediaId.subsonic('pl1');

  setUp(() {
    source = PlaylistFakeSource();
    c = ProviderContainer(overrides: [activeSourceProvider.overrideWith((ref) async => source)]);
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

    test('should_dropRowAtOnce_when_removing', () async {
      await loaded();
      final ok = await c.read(playlistDetailProvider(pl1).notifier).removeAt(1);
      expect(ok, isTrue);
      expect(source.calls, ['remove:pl1:1']);
    });

    test('should_putRowBack_when_removeRefused', () async {
      await loaded();
      source.fail = true;
      final ok = await c.read(playlistDetailProvider(pl1).notifier).removeAt(1);
      expect(ok, isFalse);
      final state = c.read(playlistDetailProvider(pl1)) as PlaylistDetailContent;
      expect([for (final t in state.tracks) t.id.rawId], ['a', 'b', 'c']);
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
      expect(await c.read(playlistDetailProvider(pl1).notifier).rename('Night'), isTrue);
      for (var i = 0; i < 5; i++) {
        await Future<void>.delayed(Duration.zero);
      }
      final state = c.read(playlistDetailProvider(pl1)) as PlaylistDetailContent;
      expect(state.playlist.name, 'Night');
    });

    test('should_reportFailure_when_deleteRefused', () async {
      await loaded();
      source.fail = true;
      expect(await c.read(playlistDetailProvider(pl1).notifier).delete(), isFalse);
      expect(c.read(playlistsRevisionProvider), 0);
    });
  });
}
