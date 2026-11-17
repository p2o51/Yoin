import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/applemusic/apple_music_mappers.dart';
import 'package:yoin/data/model/model.dart';

import 'apple_music_fixtures.dart' as fx;

MediaId _am(String raw) => MediaId(MediaId.providerAppleMusic, raw);

void main() {
  group('song identity', () {
    test('should_foldOntoCatalogId_when_librarySongNamesItsCatalogSong', () {
      final song = songFrom(fx.librarySong('i.ABC', catalog: [fx.catalogSong('11')]))!;

      expect(song.track.id, _am('11'));
      expect(song.facts.libraryId, 'i.ABC');
      expect(song.facts.catalogId, '11');
      expect(song.facts.catalogUnavailable, isFalse);
      expect(song.track.albumId, _am('1500'), reason: "the catalogue song's album");
      expect(song.track.artistId, _am('900'));
    });

    test('should_foldOntoPlayParamsCatalogId_when_catalogRelationshipIsMissing', () {
      final song = songFrom(fx.librarySong('i.ABC', playParamsCatalogId: '42'))!;
      expect(song.track.id, _am('42'));
      expect(song.facts.catalogUnavailable, isFalse, reason: 'an unasked relationship says nothing');
    });

    test('should_keepLibraryId_when_songIsAnImport', () {
      final song = songFrom(fx.librarySong('i.IMP', catalog: const []))!;
      expect(song.track.id, _am('library:i.IMP'));
      expect(song.facts.catalogId, isNull);
      expect(song.facts.catalogUnavailable, isFalse, reason: 'an import is never "unavailable"');
    });

    test('should_markUnavailable_when_catalogAnswersEmptyButPlayParamsNameOne', () {
      final song = songFrom(fx.librarySong('i.GONE', catalog: const [], playParamsCatalogId: '77'))!;
      expect(song.track.id, _am('77'));
      expect(song.facts.catalogUnavailable, isTrue);
    });

    test('should_markUnavailable_when_catalogSongHasNoPlayParams', () {
      final song = songFrom(fx.catalogSong('13', playable: false))!;
      expect(song.facts.catalogUnavailable, isTrue);
    });

    test('should_skipEntry_when_itIsNotASong', () {
      expect(songFrom({'id': '77', 'type': 'music-videos'}), isNull);
      expect(songFrom({'type': 'songs'}), isNull);
    });

    test('should_keepFirstCopy_when_albumTracklistHoldsTwoLibraryCopiesOfOneCatalogSong', () {
      final songs = songsFrom([
        fx.librarySong('i.ONE', catalog: [fx.catalogSong('11')]),
        fx.librarySong('i.TWO', catalog: [fx.catalogSong('11')]),
      ], dedupe: true);
      expect(songs, hasLength(1));
      expect(songs.single.facts.libraryId, 'i.ONE');
    });

    test('should_keepEveryEntry_when_dedupeIsNotAsked', () {
      final songs = songsFrom([
        fx.librarySong('i.ONE', catalog: [fx.catalogSong('11')]),
        fx.librarySong('i.TWO', catalog: [fx.catalogSong('11')]),
        fx.catalogSong('11'),
      ]);
      expect(songs.map((s) => s.track.id.rawId), ['11', '11', '11']);
    });
  });

  group('albums, artists, playlists', () {
    test('should_takeCatalogId_when_libraryAlbumListsItsCatalogAlbum', () {
      final album = albumFrom(fx.resourcesOf(fx.libraryAlbumMatched).single)!;
      expect(album.id, _am('1500'));
      expect(album.artistId, _am('library:r.ART'));
      expect(album.addedAt, '2026-01-02T00:00:00Z');
      expect((album.coverArt! as CoverUrl).url, 'https://is1.example.com/libalbum/600x600bb.jpg');
    });

    test('should_keepLibraryId_when_libraryAlbumHasNoCatalogMatch', () {
      expect(albumFrom(fx.resourcesOf(fx.libraryAlbumImport).single)!.id, _am('library:l.IMP'));
    });

    test('should_rememberCatalogPlaylist_when_libraryPlaylistIsPublished', () {
      final listed = fx.resourcesOf(fx.libraryPlaylists);
      expect(catalogPlaylistIdOf(listed.first), 'pl.u-PUBLISHED');
      expect(catalogPlaylistIdOf(listed.last), isNull);
    });

    test('should_useEmbeddedTracklist_when_everySongCarriesItsRelationships', () {
      final album = fx.resourcesOf(fx.catalogAlbum1500).single;
      expect(embeddedTracks(album), hasLength(2));
    });

    test('should_readTracklistSeparately_when_embeddedTracklistHasMorePages', () {
      final album = {
        ...fx.resourcesOf(fx.catalogAlbum1500).single,
        'relationships': {
          'tracks': {
            'next': '/v1/catalog/jp/albums/1500/tracks?offset=300',
            'data': [fx.catalogSong('11')],
          },
        },
      };
      expect(embeddedTracks(album), isNull);
    });
  });

  group('playlist ownership', () {
    test('should_readCanEdit_when_playlistIsInTheLibrary', () {
      Playlist? listed(bool? canEdit) => playlistFrom({
        'id': 'p.1',
        'type': 'library-playlists',
        'attributes': {'name': 'Mine', 'canEdit': ?canEdit},
      });
      expect(listed(true)!.ownedByMe, isTrue);
      expect(listed(false)!.ownedByMe, isFalse);
      expect(listed(null)!.ownedByMe, isNull);
    });

    test('should_sayNothing_when_playlistIsFromTheCatalog', () {
      final playlist = playlistFrom({
        'id': 'pl.1',
        'type': 'playlists',
        'attributes': {'name': 'Theirs', 'canEdit': false},
      });
      expect(playlist!.ownedByMe, isNull);
    });
  });

  group('song composer', () {
    test('should_readTheComposer_when_theCatalogNamesOne', () {
      final json = fx.catalogSong('21');
      (json['attributes']! as Map<String, Object?>)['composerName'] = ' Max Martin ';
      expect(songFrom(json)!.track.composer, 'Max Martin');
    });

    test('should_haveNoComposer_when_theCatalogNamesNone', () {
      expect(songFrom(fx.catalogSong('22'))!.track.composer, isNull);
    });
  });
}
