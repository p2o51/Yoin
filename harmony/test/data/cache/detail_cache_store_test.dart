import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/cache/detail_cache_json.dart';
import 'package:yoin/data/cache/detail_cache_store.dart';
import 'package:yoin/data/model/model.dart';

Album album(String id, {String name = 'Blue', int tracks = 3}) => Album(
  id: MediaId.subsonic(id),
  name: name,
  artist: 'Joni',
  artistId: MediaId.subsonic('ar1'),
  coverArt: const CoverSourceRelative('cv1'),
  year: 1971,
  isStarred: true,
  releaseType: ReleaseType.album,
  tracks: [
    for (var i = 0; i < tracks; i++)
      Track(
        id: MediaId.subsonic('$id-t$i'),
        title: 'Song $i',
        trackNumber: i + 1,
        durationSec: 200,
        userRating: 4,
        audioFormat: const AudioFormat(suffix: 'flac', bitDepth: 24, sampleRateHz: 96000),
        replayGain: const ReplayGain(trackGainDb: -6.2, trackPeak: 0.98),
      ),
  ],
);

void main() {
  late Directory temp;
  var now = 1000000;
  setUp(() {
    temp = Directory.systemTemp.createTempSync('yoin_detail');
    now = 1000000;
  });
  tearDown(() => temp.deleteSync(recursive: true));

  DetailCacheStore store({int maxBytes = DetailCacheStore.defaultMaxBytes, DetailCacheKeeps? keeps}) =>
      DetailCacheStore(() async => temp, clock: () => now, maxBytes: maxBytes, keeps: keeps);

  group('DetailCacheJson', () {
    test('should_roundTripEveryField_when_encoded', () {
      final a = album('al1');
      final back = DetailCacheJson.albumFrom(DetailCacheJson.album(a))!;
      expect(back.id, a.id);
      expect(back.artistId, a.artistId);
      expect(back.coverArt, a.coverArt);
      expect(back.isStarred, isTrue);
      expect(back.releaseType, ReleaseType.album);
      expect(back.tracks, hasLength(3));
      expect(back.tracks[1].audioFormat!.bitDepth, 24);
      expect(back.tracks[1].replayGain!.trackGainDb, -6.2);
      expect(back.tracks[1].userRating, 4);
    });

    test('should_readNull_when_jsonNotARow', () {
      expect(DetailCacheJson.albumFrom({'n': 'no id'}), isNull);
      expect(DetailCacheJson.playlistFrom('x'), isNull);
    });
  });

  group('DetailCacheStore', () {
    test('should_serveWrittenRow_when_reopened', () async {
      final s = store();
      await s.writeAlbum('p1', 'subsonic:al1', album('al1'));
      final reopened = store();
      final row = await reopened.readAlbum('p1', 'subsonic:al1');
      expect(row!.cachedAt, now);
      expect((await row.value())!.name, 'Blue');
      expect(await reopened.readAlbum('p2', 'subsonic:al1'), isNull); // per account
    });

    test('should_skipWrite_when_noLongerCurrent', () async {
      final s = store();
      await s.writeAlbum('p1', 'subsonic:al1', album('al1'), stillCurrent: () => false);
      expect(await s.readAlbum('p1', 'subsonic:al1'), isNull);
    });

    test('should_dropExpiredRow_when_read', () async {
      final s = store();
      await s.writeAlbum('p1', 'subsonic:al1', album('al1'));
      now += DetailCacheStore.defaultMaxAgeMs + 1;
      expect(await s.readAlbum('p1', 'subsonic:al1'), isNull);
    });

    test('should_keepDownloadedRow_when_expired', () async {
      final s = store(keeps: (p, k, i) async => k == DetailCacheStore.kindAlbum);
      await s.writeAlbum('p1', 'subsonic:al1', album('al1'));
      now += DetailCacheStore.defaultMaxAgeMs + 1;
      await s.purgeExpired();
      expect(await s.readAlbum('p1', 'subsonic:al1'), isNotNull);
    });

    test('should_evictOldestAccessed_when_overBudget', () async {
      final probe = store();
      await probe.writeAlbum('p', 'x', album('x', tracks: 20));
      final one = await probe.totalBytes();
      temp.deleteSync(recursive: true);
      temp.createSync();

      final s = store(maxBytes: one * 3 + one ~/ 2);
      await s.writeAlbum('p', 'a', album('a', tracks: 20));
      now += 10;
      await s.writeAlbum('p', 'b', album('b', tracks: 20));
      now += 10;
      await s.writeAlbum('p', 'c', album('c', tracks: 20));
      now += 10;
      await (await s.readAlbum('p', 'a'))!.value(); // a is touched: b is now the oldest
      now += 10;
      await s.writeAlbum('p', 'd', album('d', tracks: 20));
      expect(await s.readAlbum('p', 'b'), isNull);
      expect(await s.readAlbum('p', 'a'), isNotNull);
      expect(await s.readAlbum('p', 'd'), isNotNull);
      expect(await s.totalBytes(), lessThanOrEqualTo(one * 3 + one ~/ 2));
    });

    test('should_deleteCorruptRow_when_decodeFails', () async {
      final s = store();
      await s.writeAlbum('p1', 'subsonic:al1', album('al1'));
      for (final f in Directory('${temp.path}/detail_cache').listSync().whereType<File>()) {
        if (!f.path.endsWith('index.json')) f.writeAsStringSync('{not json');
      }
      final row = await s.readAlbum('p1', 'subsonic:al1');
      expect(await row!.value(), isNull);
      expect(await s.readAlbum('p1', 'subsonic:al1'), isNull);
    });

    test('should_forgetAccount_when_profileRemoved', () async {
      final s = store();
      await s.writeAlbum('p1', 'a', album('a'));
      await s.writePlaylist('p1', 'pl', Playlist(id: MediaId.subsonic('pl'), name: 'Mix'));
      await s.writeAlbum('p2', 'a', album('a'));
      await s.removeProfile('p1');
      expect(await s.readAlbum('p1', 'a'), isNull);
      expect(await s.readPlaylist('p1', 'pl'), isNull);
      expect(await s.readAlbum('p2', 'a'), isNotNull);
    });

    test('should_stayOff_when_noFilesFolder', () async {
      final s = DetailCacheStore(() async => null);
      await s.writeAlbum('p', 'a', album('a'));
      expect(await s.readAlbum('p', 'a'), isNull);
    });
  });
}
