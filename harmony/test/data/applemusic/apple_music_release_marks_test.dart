// Apple Music's explicit / clean mark and release kind (Android AppleMusicContentRatingTest +
// AppleMusicReleaseTypeTest, PR #343): `contentRating` the resource's own else its catalog song's / album's;
// the kind from `isCompilation` / `isSingle` (each the resource's own when it has the key, else the
// catalog's) and the " - EP" / " - Single" name.
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/applemusic/apple_music_mappers.dart';
import 'package:yoin/data/model/model.dart';

import 'apple_music_fixtures.dart' as fx;

Map<String, Object?> _withAttributes(Map<String, Object?> resource, Map<String, Object?> extra) => {
  ...resource,
  'attributes': {...(resource['attributes']! as Map<String, Object?>), ...extra},
};

Map<String, Object?> _album(
  String id, {
  String type = 'albums',
  String name = 'DAMN.',
  Map<String, Object?> attributes = const {},
  Map<String, Object?>? catalogAttributes,
}) => {
  'id': id,
  'type': type,
  'attributes': {'name': name, 'artistName': 'Kendrick Lamar', ...attributes},
  if (catalogAttributes != null)
    'relationships': {
      'catalog': {
        'data': [
          {'id': '1440', 'type': 'albums', 'attributes': catalogAttributes},
        ],
      },
    },
};

void main() {
  group('song rating', () {
    test('should_readExplicit_when_catalogSongSaysSo', () {
      final song = songFrom(_withAttributes(fx.catalogSong('11'), {'contentRating': 'explicit'}))!;
      expect(song.track.contentRating, ContentRating.explicit);
      expect(song.track.isExplicit, isTrue);
    });

    test('should_readClean_when_catalogSongSaysClean', () {
      final song = songFrom(_withAttributes(fx.catalogSong('11'), {'contentRating': 'clean'}))!;
      expect(song.track.contentRating, ContentRating.clean);
      expect(song.track.isExplicit, isFalse);
    });

    test('should_beUnknown_when_songHasNoRating', () {
      expect(songFrom(fx.catalogSong('11'))!.track.contentRating, isNull);
    });

    test('should_takeCatalogRating_when_librarySongHasNone', () {
      final catalog = _withAttributes(fx.catalogSong('11'), {'contentRating': 'explicit'});
      final song = songFrom(fx.librarySong('i.ABC', catalog: [catalog]))!;
      expect(song.track.contentRating, ContentRating.explicit);
    });

    test('should_keepOwnRating_when_librarySongHasOne', () {
      final catalog = _withAttributes(fx.catalogSong('11'), {'contentRating': 'explicit'});
      final library = _withAttributes(fx.librarySong('i.ABC', catalog: [catalog]), {'contentRating': 'clean'});
      expect(songFrom(library)!.track.contentRating, ContentRating.clean);
    });
  });

  group('album rating', () {
    test('should_readExplicit_when_catalogAlbumSaysSo', () {
      expect(
        albumFrom(_album('1440', attributes: {'contentRating': 'explicit'}))!.contentRating,
        ContentRating.explicit,
      );
    });

    test('should_takeCatalogRating_when_libraryAlbumHasNone', () {
      final album = albumFrom(
        _album('l.AAA', type: 'library-albums', catalogAttributes: {'contentRating': 'explicit'}),
      )!;
      expect(album.contentRating, ContentRating.explicit);
      expect(album.isExplicit, isTrue);
    });

    test('should_beUnknown_when_neitherAlbumNorCatalogSays', () {
      expect(albumFrom(_album('l.AAA', type: 'library-albums', catalogAttributes: const {}))!.contentRating, isNull);
    });

    test('should_keepRating_when_tracksAreAdded', () {
      final album = albumFrom(_album('1440', attributes: {'contentRating': 'clean'}))!;
      expect(albumWithTracks(album, const []).contentRating, ContentRating.clean);
    });
  });

  group('release kind', () {
    test('should_readEp_when_nameEndsWithEp', () {
      final album = albumFrom(_album('1', name: 'Section.80 - EP', attributes: {'isSingle': false}))!;
      expect(album.releaseType, ReleaseType.ep);
    });

    test('should_readSingle_when_isSingle', () {
      expect(
        albumFrom(_album('1', name: 'HUMBLE. - Single', attributes: {'isSingle': true}))!.releaseType,
        ReleaseType.single,
      );
    });

    test('should_readCompilation_when_isCompilation', () {
      final album = albumFrom(_album('1', name: 'Now 100', attributes: {'isCompilation': true, 'isSingle': false}))!;
      expect(album.releaseType, ReleaseType.compilation);
    });

    test('should_readAlbum_when_isSingleFalseAndNoEpName', () {
      expect(albumFrom(_album('1', attributes: {'isSingle': false}))!.releaseType, ReleaseType.album);
    });

    test('should_beUnknown_when_noFlagNorSuffix', () {
      expect(albumFrom(_album('1'))!.releaseType, isNull);
    });

    test('should_takeCatalogFlags_when_libraryAlbumHasNone', () {
      final single = albumFrom(_album('l.A', type: 'library-albums', catalogAttributes: {'isSingle': true}))!;
      expect(single.releaseType, ReleaseType.single);
      final album = albumFrom(_album('l.B', type: 'library-albums', catalogAttributes: {'isSingle': false}))!;
      expect(album.releaseType, ReleaseType.album);
    });

    test('should_preferOwnFlag_when_resourceHasTheKey', () {
      final album = albumFrom(
        _album('l.A', type: 'library-albums', attributes: {'isSingle': false}, catalogAttributes: {'isSingle': true}),
      )!;
      expect(album.releaseType, ReleaseType.album);
    });
  });
}
