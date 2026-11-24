// 被提及, ported from Android's NoteBacklinksTest: the same matching cases,
// and the account's writings read from the MemoryStore.
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/memory/track_album_links.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/notes/note_backlinks.dart';
import 'package:yoin/data/notes/note_markup.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../../support/library_fake_source.dart';

const _sub = MediaId.providerSubsonic;

String _link(MentionKind kind, String provider, String id, String title, {String? artist, String? album}) =>
    NoteMarkup.serializeMention(
      NoteMention(kind: kind, provider: provider, id: id, title: title, artist: artist, album: album),
    );

BacklinkWriting _songNote(String id, String trackId, String text, int at, {MediaId? albumId}) => BacklinkWriting(
  BacklinkSongNote(
    noteId: id,
    trackId: MediaId.subsonic(trackId),
    trackTitle: 'Song',
    trackArtist: 'Artist',
    albumId: albumId,
  ),
  text,
  at,
);

List<String> _ids(List<NoteBacklink> links) => [for (final l in links) (l.source as BacklinkSongNote).noteId];

void main() {
  final titanic = BacklinkAlbum(
    MediaId.subsonic('al-weyes'),
    title: 'Titanic Rising',
    artist: 'Weyes Blood',
    tracks: [
      BacklinkTrack(MediaId.subsonic('t-alot'), title: "A Lot's Gonna Change", artist: 'Weyes Blood'),
      BacklinkTrack(MediaId.subsonic('t-andromeda'), title: 'Andromeda', artist: 'Weyes Blood'),
    ],
  );

  test('should_countAlbumAndTrackMentions_byIdOnTheSameProvider', () {
    final links = findBacklinks([
      _songNote('n1', 't-x', '像 ${_link(MentionKind.album, _sub, 'al-weyes', 'Titanic Rising')} 的白天', 3),
      _songNote('n2', 't-y', '后来在 ${_link(MentionKind.track, _sub, 't-andromeda', 'Andromeda')} 里', 5),
      _songNote(
        'n3',
        't-z',
        'another ${_link(MentionKind.album, _sub, 'al-other', 'Titanic Rising', artist: 'Weyes Blood')}',
        9,
      ),
    ], titanic);
    expect(_ids(links), ['n2', 'n1']);
    expect(links.first.snippet, '后来在 Andromeda 里');
    expect(links.first.marks, [(4, 13)]);
    expect(links.first.mentions.single.id, 't-andromeda');
  });

  test('should_matchOtherProvidersByName_includingATracksAlbum', () {
    final links = findBacklinks([
      _songNote(
        'apple-album',
        't-x',
        _link(MentionKind.album, 'applemusic', '1', 'Titanic Rising', artist: 'Weyes Blood'),
        1,
      ),
      _songNote(
        'spotify-track',
        't-y',
        _link(MentionKind.track, 'spotify', 'sp', 'Movies', artist: 'Weyes Blood', album: 'Titanic Rising'),
        2,
      ),
      _songNote(
        'spotify-listed',
        't-z',
        _link(MentionKind.track, 'spotify', 'sp2', 'Andromeda', artist: 'Weyes Blood'),
        3,
      ),
      _songNote(
        'wrong-artist',
        't-w',
        _link(MentionKind.album, 'spotify', 'x', 'Titanic Rising', artist: 'Somebody Else'),
        4,
      ),
      _songNote('artist', 't-v', _link(MentionKind.artist, 'spotify', 'ar', 'Weyes Blood'), 5),
    ], titanic);
    expect(_ids(links), ['spotify-listed', 'spotify-track', 'apple-album']);
  });

  test('should_matchByNameWithinSpotifyAndAppleMusic_butNotWithinSubsonic', () {
    final apple = BacklinkAlbum(MediaId('applemusic', '1440'), title: 'Titanic Rising', artist: 'Weyes Blood');
    final spotifyTrack = BacklinkTrackTarget(
      MediaId('spotify', 'sp-andromeda'),
      title: 'Andromeda',
      artist: 'Weyes Blood',
    );
    final writings = [
      _songNote(
        'library',
        't-1',
        _link(MentionKind.album, 'applemusic', 'library:l.x', 'Titanic Rising', artist: 'Weyes Blood'),
        1,
      ),
      _songNote(
        'reissue',
        't-2',
        _link(MentionKind.track, 'spotify', 'sp-reissue', 'Andromeda', artist: 'Weyes Blood'),
        2,
      ),
    ];
    expect(_ids(findBacklinks(writings, apple)), ['library']);
    expect(_ids(findBacklinks(writings, spotifyTrack)), ['reissue']);
    final subsonic = [
      _songNote(
        'same-name',
        't-3',
        _link(MentionKind.album, _sub, 'al-other', 'Titanic Rising', artist: 'Weyes Blood'),
        3,
      ),
    ];
    expect(findBacklinks(subsonic, titanic), isEmpty);
  });

  test('should_markOnlyTheMentionThatPointsHere_notTheSameWordElsewhere', () {
    final link = findBacklinks([
      _songNote(
        'n1',
        't-x',
        'Andromeda: ${_link(MentionKind.track, _sub, 't-andromeda', 'Andromeda')} and ${_link(MentionKind.album, _sub, 'al-x', 'Other')}',
        1,
      ),
    ], titanic).single;
    expect(link.snippet, 'Andromeda: Andromeda and Other');
    expect(link.marks, [(11, 20)]);
  });

  test('should_leaveOutWritingsOnTheAlbumItself', () {
    final mention = _link(MentionKind.album, _sub, 'al-weyes', 'Titanic Rising');
    final links = findBacklinks([
      _songNote('on-track', 't-andromeda', mention, 1),
      _songNote('linked', 't-unlisted', mention, 2, albumId: MediaId.subsonic('al-weyes')),
      BacklinkWriting(
        BacklinkAlbumNote(
          noteId: 'an',
          albumId: MediaId.subsonic('al-weyes'),
          albumName: 'Titanic Rising',
          albumArtist: 'Weyes Blood',
        ),
        mention,
        3,
      ),
      BacklinkWriting(BacklinkReview(albumId: MediaId.subsonic('al-weyes')), mention, 4),
      BacklinkWriting(
        BacklinkReview(albumId: MediaId.subsonic('al-charm'), albumName: 'Charm', albumArtist: 'Clairo'),
        mention,
        5,
      ),
    ], titanic);
    expect(links.single.source, isA<BacklinkReview>());
    expect((links.single.source as BacklinkReview).albumId, MediaId.subsonic('al-charm'));
  });

  test('should_matchTracksAndArtists_andSkipTheTracksOwnNotes', () {
    final andromeda = BacklinkTrackTarget(
      MediaId.subsonic('t-andromeda'),
      title: 'Andromeda',
      artist: 'Weyes Blood',
      album: 'Titanic Rising',
    );
    expect(
      _ids(
        findBacklinks([
          _songNote('own', 't-andromeda', _link(MentionKind.track, _sub, 't-andromeda', 'Andromeda'), 1),
          _songNote('other', 't-x', _link(MentionKind.track, _sub, 't-andromeda', 'Andromeda'), 2),
          _songNote('by-name', 't-y', _link(MentionKind.track, 'spotify', 's', 'Andromeda', artist: 'Weyes Blood'), 3),
          _songNote('album-only', 't-z', _link(MentionKind.album, _sub, 'al-weyes', 'Titanic Rising'), 4),
        ], andromeda),
      ),
      ['by-name', 'other'],
    );
    final artist = BacklinkArtist(MediaId.subsonic('ar-weyes'), name: 'Weyes Blood');
    expect(
      _ids(
        findBacklinks([
          _songNote('id', 't-x', _link(MentionKind.artist, _sub, 'ar-weyes', 'Weyes Blood'), 1),
          _songNote('name', 't-y', _link(MentionKind.artist, 'spotify', 'sp', 'weyes blood'), 2),
          _songNote(
            'track',
            't-z',
            _link(MentionKind.track, _sub, 't-andromeda', 'Andromeda', artist: 'Weyes Blood'),
            3,
          ),
        ], artist),
      ),
      ['name', 'id'],
    );
  });

  test('should_readEveryKindOfWriting_forTheAccountInUse', () async {
    final kv = InMemoryStore();
    final store = MemoryStore(kv, profileId: 'profile-a');
    final mention = _link(MentionKind.album, _sub, 'al-weyes', 'Titanic Rising', artist: 'Weyes Blood');
    await store.putSongNote(
      SongNote(id: 'n1', trackId: MediaId.subsonic('t-charm-1'), content: '整张像 $mention', createdAt: 10, updatedAt: 10),
    );
    await store.putSongNote(
      SongNote(
        id: 'plain',
        trackId: MediaId.subsonic('t-charm-1'),
        content: 'no link here',
        createdAt: 11,
        updatedAt: 11,
      ),
    );
    final links = TrackAlbumLinkStore.shared(kv, 'profile-a');
    await links.putAll([
      TrackAlbumLink(
        trackId: MediaId.subsonic('t-charm-1'),
        albumId: MediaId.subsonic('al-charm'),
        source: TrackAlbumLink.sourcePlay,
        updatedAt: 1,
      ),
    ]);
    await store.putAlbumNote(
      AlbumNote(
        id: 'an1',
        albumId: MediaId.subsonic('al-charm'),
        content: '周日下午，$mention',
        createdAt: 20,
        updatedAt: 20,
        albumName: 'Charm',
        artist: 'Clairo',
      ),
    );
    await store.setAlbumReview(MediaId.subsonic('al-cocteau'), '# 天堂\n听到 $mention 的温度', nowMs: 30);
    await store.recordTrackPlay(
      LibraryFixtures.track(
        't-cocteau-5',
        albumId: 'al-cocteau',
        album: 'Heaven or Las Vegas',
        artist: 'Cocteau Twins',
      ),
      atMs: 5,
    );

    final found = findBacklinks(await backlinkWritings(store, links: links), titanic);

    expect(found, hasLength(3));
    final review = found[0].source as BacklinkReview;
    expect(review.albumId, MediaId.subsonic('al-cocteau'));
    expect(review.albumName, 'Heaven or Las Vegas');
    expect(found[0].at, 30);
    expect(found[0].snippet, '天堂 · 听到 Titanic Rising 的温度');
    final albumNote = found[1].source as BacklinkAlbumNote;
    expect((albumNote.noteId, albumNote.albumName, albumNote.albumArtist), ('an1', 'Charm', 'Clairo'));
    final note = found[2].source as BacklinkSongNote;
    expect(note.albumId, MediaId.subsonic('al-charm'));
    expect(note.noteId, 'n1');
  });
}
