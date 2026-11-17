import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/memory/album_memory_candidate.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/memories/home_memory_pill.dart';

AlbumMemoryCandidate _c(String id, {int? written, int? played, double? album, double? avg}) => AlbumMemoryCandidate(
      profileId: 'p',
      albumId: MediaId.subsonic(id),
      albumName: id,
      totalTracks: 8,
      ratedTrackCount: 0,
      ratingCoverage: 0,
      hasAlbumReview: true,
      noteCount: 0,
      isMemoryEligible: true,
      lastWrittenAt: written,
      lastPlayedAt: played,
      albumRating: album,
      averageSongRating: avg,
    );

void main() {
  test('should_keyOnWriteElsePlay_when_picking', () {
    final latest = pickLatestMemory([_c('a', written: 10, played: 99), _c('b', written: 20), _c('c', played: 500)]);
    // Keyed on the write time, else the play time (Android pickLatestMemory).
    expect(latest!.albumName, 'c');
    expect(pickLatestMemory([_c('a', written: 10), _c('b', written: 20)])!.albumName, 'b');
    expect(pickLatestMemory(const []), isNull);
  });

  test('should_keepFirst_when_tied', () {
    expect(pickLatestMemory([_c('a', written: 5), _c('b', written: 5)])!.albumName, 'a');
  });

  test('should_readAlbumScoreFirst_when_both', () {
    final s = HomeMemoryPillState(latest: _c('a', album: 9.95, avg: 7), resolved: true);
    expect(s.scoreKind, MemoryPillScore.album);
    expect(s.scoreText, '10.0');
    expect(HomeMemoryPillState(latest: _c('a', avg: 7), resolved: true).scoreKind, MemoryPillScore.average);
    expect(const HomeMemoryPillState(resolved: true).scoreText, isNull);
  });
}
