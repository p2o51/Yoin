import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/data/subsonic/subsonic_music_source.dart';

import 'subsonic_fixtures.dart' as fx;

const _creds = SubsonicCredentials(baseUrl: 'https://music.example.com/nd', username: 'alice', password: 'sesame');

final _navidrome = fx.ok('''
"openSubsonicExtensions": [
  {"name": "transcodeOffset", "versions": [1]},
  {"name": "playbackReport", "versions": [1]},
  {"name": "songLyrics", "versions": [1]}
]''');

final _noExtensions = fx.ok('"openSubsonicExtensions": []');

/// A fake server: endpoint (without `.view`) → body; anything else is a 404.
final class _Server {
  _Server(this.routes);
  final Map<String, Object> routes;
  final requests = <Uri>[];

  List<String> get endpoints => [for (final r in requests) r.pathSegments.last.replaceAll('.view', '')];

  int asked(String endpoint) => endpoints.where((e) => e == endpoint).length;

  SubsonicMusicSource source({SubsonicStreamPlanner? planner}) => SubsonicMusicSource(
    _creds,
    salt: 'salt',
    streamPlanner: planner,
    client: MockClient((request) async {
      requests.add(request.url);
      final route = routes[request.url.pathSegments.last.replaceAll('.view', '')];
      return switch (route) {
        final String body => http.Response(body, 200, headers: {'content-type': 'application/json'}),
        final Object Function() thrower => throw thrower(),
        _ => http.Response('not found', 404),
      };
    }),
  );
}

Track _song({int? kbps}) => Track(
  id: MediaId.subsonic('s-1'),
  title: 'One',
  audioFormat: kbps == null ? null : AudioFormat(suffix: 'flac', bitRateKbps: kbps),
);

Future<SubsonicStreamPlan?> _mp3At320(Track _) async => (maxBitRateKbps: 320, format: 'mp3', estimateLength: true);

void main() {
  group('seeking in a transcode', () {
    test('should_offerTimeSeek_when_fileIsAboveTheCapAndTranscodedToMp3', () async {
      final server = _Server({'getOpenSubsonicExtensions': _navidrome});
      final handle = await server.source(planner: _mp3At320).playback.handleFor(_song(kbps: 900)) as DirectStream;
      expect(handle.timeSeek, isNotNull);
      expect(handle.timeSeek!.kbps, 320);

      final uri = (await handle.timeSeek!.uriAt(95))!;
      expect(uri.pathSegments, ['nd', 'rest', 'stream.view']);
      final q = uri.queryParameters;
      expect(q['id'], 's-1');
      expect(q['maxBitRate'], '320');
      expect(q['format'], 'mp3');
      expect(q['timeOffset'], '95');
      expect(q['estimateContentLength'], 'true');
      // Signed like the stream it stands in for.
      final base = Uri.parse(handle.uri).queryParameters;
      for (final key in ['u', 't', 's', 'v', 'c', 'f']) {
        expect(q[key], base[key], reason: key);
      }
      expect(Uri.parse(handle.uri).queryParameters.containsKey('timeOffset'), isFalse);
    });

    test('should_askExtensionsOnce_when_manySongsAndSeeksNeedThem', () async {
      final server = _Server({'getOpenSubsonicExtensions': _navidrome, 'getLyricsBySongId': fx.structuredLyrics});
      final source = server.source(planner: _mp3At320);
      final handles = await Future.wait([for (var i = 0; i < 3; i++) source.playback.handleFor(_song(kbps: 900))]);
      for (final h in handles) {
        await (h as DirectStream).timeSeek!.uriAt(10);
      }
      await source.metadata.getLyrics(_song());
      expect(await (source.playback as MusicPlaybackStateReports).supportsPlaybackStateReport(), isTrue);
      expect(server.asked('getOpenSubsonicExtensions'), 1);
    });

    test('should_giveNoTimeSeekAddress_when_serverLacksTranscodeOffset', () async {
      final server = _Server({'getOpenSubsonicExtensions': _noExtensions});
      final handle = await server.source(planner: _mp3At320).playback.handleFor(_song(kbps: 900)) as DirectStream;
      expect(await handle.timeSeek!.uriAt(95), isNull);
    });

    test('should_giveNoTimeSeekAddress_when_plainSubsonicAnswers404', () async {
      final server = _Server({});
      final handle = await server.source(planner: _mp3At320).playback.handleFor(_song(kbps: 900)) as DirectStream;
      expect(await handle.timeSeek!.uriAt(95), isNull);
    });

    test('should_giveNoTimeSeekAddress_when_atTheStart', () async {
      final server = _Server({'getOpenSubsonicExtensions': _navidrome});
      final handle = await server.source(planner: _mp3At320).playback.handleFor(_song(kbps: 900)) as DirectStream;
      expect(await handle.timeSeek!.uriAt(0), isNull);
    });

    test('should_notSeekByTime_when_fileBitRateUnknown', () async {
      // The server may not encode at the cap: a byte is not a time.
      final server = _Server({'getOpenSubsonicExtensions': _navidrome});
      final handle =
          await server
                  .source(planner: (_) async => (maxBitRateKbps: 320, format: 'mp3', estimateLength: false))
                  .playback
                  .handleFor(_song())
              as DirectStream;
      expect(handle.timeSeek, isNull);
    });

    test('should_notSeekByTime_when_formatIsNotMp3', () async {
      final server = _Server({'getOpenSubsonicExtensions': _navidrome});
      final handle =
          await server
                  .source(planner: (_) async => (maxBitRateKbps: 128, format: 'opus', estimateLength: true))
                  .playback
                  .handleFor(_song(kbps: 900))
              as DirectStream;
      expect(handle.timeSeek, isNull);
    });

    test('should_notSeekByTime_when_theFileItselfPlays', () async {
      final server = _Server({'getOpenSubsonicExtensions': _navidrome});
      final handle = await server.source().playback.handleFor(_song(kbps: 900)) as DirectStream;
      expect(handle.timeSeek, isNull);
      expect(server.requests, isEmpty);
    });

    test('should_mapAByteToTheSecondItFallsIn_when_constantBitRate', () {
      // 320 kbps = 40 000 bytes a second.
      expect(StreamTimeSeek.timeOffsetSeconds(0, 320), 0);
      expect(StreamTimeSeek.timeOffsetSeconds(39999, 320), 0);
      expect(StreamTimeSeek.timeOffsetSeconds(40000, 320), 1);
      expect(StreamTimeSeek.timeOffsetSeconds(3800000, 320), 95);
      expect(StreamTimeSeek.timeOffsetSeconds(1000, 0), 0);
      expect(StreamTimeSeek.timeOffsetSeconds(-5, 320), 0);
    });
  });

  group('playbackReport', () {
    test('should_sendReportPlaybackWithIgnoreScrobble_when_stateReported', () async {
      final server = _Server({'reportPlayback': fx.ok()});
      final playback = server.source().playback as MusicPlaybackStateReports;
      await playback.reportPlaybackState(MediaId.subsonic('s-1'), PlaybackReportState.paused, 61234);
      final q = server.requests.single.queryParameters;
      expect(server.endpoints.single, 'reportPlayback');
      expect(q['mediaId'], 's-1');
      expect(q['mediaType'], 'song');
      expect(q['positionMs'], '61234');
      expect(q['state'], 'paused');
      expect(q['ignoreScrobble'], 'true');
    });

    test('should_sendZero_when_positionIsNegative', () async {
      final server = _Server({'reportPlayback': fx.ok()});
      final playback = server.source().playback as MusicPlaybackStateReports;
      await playback.reportPlaybackState(MediaId.subsonic('s-1'), PlaybackReportState.starting, -20);
      expect(server.requests.single.queryParameters['positionMs'], '0');
    });

    test('should_sayNotSupported_when_serverLacksTheExtension', () async {
      final server = _Server({'getOpenSubsonicExtensions': _noExtensions});
      final playback = server.source().playback as MusicPlaybackStateReports;
      expect(await playback.supportsPlaybackStateReport(), isFalse);
      expect(await playback.supportsPlaybackStateReport(), isFalse);
      expect(server.asked('getOpenSubsonicExtensions'), 1);
    });

    test('should_rememberNone_when_plainSubsonicAnswersWithAnError', () async {
      final server = _Server({'getOpenSubsonicExtensions': fx.failed(0, 'Unknown method')});
      final playback = server.source().playback as MusicPlaybackStateReports;
      expect(await playback.supportsPlaybackStateReport(), isFalse);
      expect(await playback.supportsPlaybackStateReport(), isFalse);
      expect(server.asked('getOpenSubsonicExtensions'), 1);
    });

    test('should_askAgain_when_firstAskFoundNoServer', () async {
      final server = _Server({'getOpenSubsonicExtensions': () => http.ClientException('offline')});
      final playback = server.source().playback as MusicPlaybackStateReports;
      await expectLater(playback.supportsPlaybackStateReport(), throwsA(isA<SourceNetworkException>()));
      server.routes['getOpenSubsonicExtensions'] = _navidrome;
      expect(await playback.supportsPlaybackStateReport(), isTrue);
      expect(server.asked('getOpenSubsonicExtensions'), 2);
    });
  });
}
