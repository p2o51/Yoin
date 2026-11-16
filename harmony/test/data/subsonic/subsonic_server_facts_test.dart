import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/cache/detail_cache_json.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/subsonic/subsonic_mappers.dart';
import 'package:yoin/playback/track_codec.dart';

// What the server says about the file, for About's receipt (Android
// SubsonicMappers EXTRA_SIZE … EXTRA_COMPOSER), and that it survives the
// queue's codec and the detail cache.
void main() {
  test('should_readTheFileFacts_when_theServerSendsThem', () {
    final track = trackFromJson({
      'id': 's1',
      'title': 'Song',
      'size': 9437184,
      'playCount': '12',
      'bpm': 120,
      'displayComposer': ' 钱雷 ',
      'comment': ' Remaster ',
      'path': 'Pop/song.flac',
    })!;
    expect(track.sizeBytes, 9437184);
    expect(track.playCount, 12);
    expect(track.bpm, 120);
    expect(track.composer, '钱雷');
    expect(track.comment, 'Remaster');
    expect(track.path, 'Pop/song.flac');
  });

  test('should_leaveFactsOut_when_theServerSendsZerosOrBlanks', () {
    final track = trackFromJson({
      'id': 's1',
      'size': 0,
      'playCount': 0,
      'bpm': 0,
      'displayComposer': ' ',
      'comment': '',
      'path': ' ',
    })!;
    expect(track.sizeBytes, isNull);
    expect(track.playCount, isNull);
    expect(track.bpm, isNull);
    expect(track.composer, isNull);
    expect(track.comment, isNull);
    expect(track.path, isNull);
  });

  Track facts() => Track(
    id: MediaId.subsonic('s1'),
    sizeBytes: 1024,
    playCount: 3,
    bpm: 98,
    composer: 'C',
    comment: 'note',
    path: 'a/b.flac',
  );

  void expectFacts(Track back) {
    expect(back.sizeBytes, 1024);
    expect(back.playCount, 3);
    expect(back.bpm, 98);
    expect(back.composer, 'C');
    expect(back.comment, 'note');
    expect(back.path, 'a/b.flac');
  }

  test('should_keepTheFileFacts_when_theQueueCodecRoundTrips', () {
    expectFacts(TrackCodec.decode(TrackCodec.encode(facts()))!);
  });

  test('should_keepTheFileFacts_when_theDetailCacheRoundTrips', () {
    expectFacts(DetailCacheJson.trackFrom(DetailCacheJson.track(facts()))!);
  });

  test('should_keepTheFileFacts_when_starredOrRated', () {
    expectFacts(facts().copyWith(isStarred: true, userRating: 3));
  });
}
