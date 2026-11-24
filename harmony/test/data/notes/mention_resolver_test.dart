import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/notes/mention_resolver.dart';
import 'package:yoin/data/notes/note_markup.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../../support/library_fake_source.dart';

final class _Names implements MentionNameLookup {
  _Names({this.trackRows = const [], this.albumRows = const []});

  final List<MentionNameRow> trackRows;
  final List<MentionNameRow> albumRows;

  @override
  Future<List<MentionNameRow>> tracks() async => trackRows;

  @override
  Future<List<MentionNameRow>> albums() async => albumRows;
}

NoteMention _m(MentionKind kind, String provider, String id, String title, {String? artist, String? album}) =>
    NoteMention(kind: kind, provider: provider, id: id, title: title, artist: artist, album: album);

const _account = MentionAccount('p', MediaId.providerSubsonic);

/// Android LocalMentionResolver: by id on the account's provider, by name otherwise, unresolved when nothing answers.
void main() {
  test('should_resolveById_when_mentionIsOnTheAccountsProvider', () async {
    final resolver = LocalMentionResolver(account: _account, names: _Names());
    final answer = await resolver.resolve(_m(MentionKind.album, 'subsonic', 'al-1', 'Blue'));
    expect(answer, isA<MentionResolved>());
    answer as MentionResolved;
    expect(answer.id, MediaId.subsonic('al-1'));
    expect(answer.via, MentionVia.id);
  });

  test('should_matchByName_when_anotherServicesTrackIsKnownHere', () async {
    final names = _Names(
      trackRows: [
        MentionNameRow(MediaId(MediaId.providerSpotify, 'x'), '晴天', artist: '周杰伦'),
        MentionNameRow(MediaId.subsonic('loose'), '晴天', artist: '周杰伦'),
        MentionNameRow(MediaId.subsonic('named'), '晴天 (Live)', artist: '周杰伦', album: '叶惠美'),
        MentionNameRow(MediaId.subsonic('right'), '晴天', artist: '周杰倫', album: '葉惠美'),
      ],
    );
    final resolver = LocalMentionResolver(account: _account, names: names);
    final answer = await resolver.resolve(_m(MentionKind.track, 'spotify', 'sp', '晴天', artist: '周杰伦', album: '叶惠美'));
    expect(
      (answer as MentionResolved).id,
      MediaId.subsonic('right'),
      reason: 'a row naming the album wins; Live differs',
    );
    expect(answer.via, MentionVia.name);
  });

  test('should_matchAlbumsByName_and_leaveArtistsUnresolved', () async {
    final names = _Names(
      albumRows: [MentionNameRow(MediaId.subsonic('al'), 'Heaven or Las Vegas', artist: 'Cocteau Twins')],
    );
    final resolver = LocalMentionResolver(account: _account, names: names);
    final album = await resolver.resolve(_m(MentionKind.album, 'applemusic', '1', 'Heaven Or Las Vegas'));
    expect((album as MentionResolved).id, MediaId.subsonic('al'));
    expect(await resolver.resolve(_m(MentionKind.artist, 'spotify', 'a', 'Cocteau Twins')), isA<MentionUnresolved>());
    expect(await resolver.resolve(_m(MentionKind.album, 'spotify', '2', 'Treasure')), isA<MentionUnresolved>());
  });

  test('should_leaveEverythingUnresolved_when_noAccount', () async {
    const resolver = LocalMentionResolver(account: null, names: null);
    expect(await resolver.resolve(_m(MentionKind.track, 'subsonic', 't', 'x')), isA<MentionUnresolved>());
  });

  test('should_readNamesFromPlaysAndNotes_when_lookingUpTheMemoryStore', () async {
    final store = MemoryStore(InMemoryStore(), profileId: 'p');
    await store.recordTrackPlay(LibraryFixtures.track('t1', artist: 'Weyes Blood', album: 'Titanic Rising'), atMs: 1);
    await store.recordAlbumPlay(
      MediaId.subsonic('al'),
      atMs: 1,
      albumName: 'Titanic Rising',
      artistName: 'Weyes Blood',
    );
    await store.putSongNote(
      SongNote(
        id: 'n',
        trackId: MediaId.subsonic('t2'),
        content: 'x',
        createdAt: 1,
        updatedAt: 1,
        title: 'Movies',
        artist: 'Weyes Blood',
      ),
    );
    final resolver = LocalMentionResolver(account: _account, names: MemoryMentionNameLookup(store));
    final song = await resolver.resolve(_m(MentionKind.track, 'spotify', 's', 'Movies', artist: 'Weyes Blood'));
    expect((song as MentionResolved).id, MediaId.subsonic('t2'));
    final album = await resolver.resolve(
      _m(MentionKind.album, 'spotify', 'a', 'Titanic Rising', artist: 'Weyes Blood'),
    );
    expect((album as MentionResolved).id, MediaId.subsonic('al'));
  });
}
