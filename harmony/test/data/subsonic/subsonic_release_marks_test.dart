// OpenSubsonic's explicit / clean mark and release kind (Android SubsonicContentRatingTest, PR #343):
// `explicitStatus` on songs and albums ("" or absent is unknown), `releaseTypes` by :shared's rule.
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/subsonic/subsonic_mappers.dart';

void main() {
  test('should_readExplicit_when_songExplicitStatusIsExplicit', () {
    final track = trackFromJson({'id': 's1', 'title': 'HUMBLE.', 'explicitStatus': 'explicit'})!;
    expect(track.contentRating, ContentRating.explicit);
    expect(track.isExplicit, isTrue);
  });

  test('should_readClean_when_songExplicitStatusIsClean', () {
    expect(trackFromJson({'id': 's1', 'explicitStatus': 'clean'})!.contentRating, ContentRating.clean);
  });

  test('should_beUnknown_when_explicitStatusIsEmptyOrAbsent', () {
    expect(trackFromJson({'id': 's1', 'explicitStatus': ''})!.contentRating, isNull);
    expect(trackFromJson({'id': 's1'})!.contentRating, isNull);
  });

  test('should_readAlbumAndSongMarks_when_albumCarriesThem', () {
    final album = albumFromJson({
      'id': 'al1',
      'name': 'DAMN.',
      'explicitStatus': 'explicit',
      'releaseTypes': ['EP'],
      'song': [
        {'id': 's1', 'explicitStatus': 'explicit'},
        {'id': 's2', 'explicitStatus': 'clean'},
      ],
    })!;
    expect(album.contentRating, ContentRating.explicit);
    expect(album.releaseType, ReleaseType.ep);
    expect([for (final t in album.tracks) t.contentRating], [ContentRating.explicit, ContentRating.clean]);
  });

  test('should_takeFirstRecognisedKind_when_severalAreListed', () {
    expect(releaseTypeFrom(['Live', ' single ', 'album']), ReleaseType.single);
    expect(releaseTypeFrom(['mixtape']), isNull);
  });
}
