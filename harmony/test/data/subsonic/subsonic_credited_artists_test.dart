import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/subsonic/subsonic_mappers.dart';

void main() {
  test('should_keepEachNamedArtist_when_openSubsonicNamesMoreThanOne', () {
    final track = trackFromJson({
      'id': 's1',
      'title': 'Duet',
      'artist': 'A',
      'displayArtist': 'A & B',
      'artists': [
        {'id': 'ar1', 'name': ' A '},
        {'id': 'ar2', 'name': 'B'},
        {'id': 'ar3', 'name': '  '},
      ],
    })!;
    expect(track.artist, 'A & B');
    expect(track.creditedArtists, ['A', 'B']);
  });

  test('should_keepNoList_when_theServerNamesOneArtist', () {
    final track = trackFromJson({
      'id': 's1',
      'title': 'Solo',
      'artist': 'A',
      'artists': [
        {'id': 'ar1', 'name': 'A'},
      ],
    })!;
    expect(track.creditedArtists, isEmpty);
  });
}
