import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/playback/play_trace.dart';

/// The playback diagnostics log (Android PlayTraceTest): what it cuts, what it
/// keeps and how its file survives a relaunch.
void main() {
  late Directory temp;

  setUp(() => temp = Directory.systemTemp.createTempSync('yoin_playtrace'));
  tearDown(() => temp.deleteSync(recursive: true));

  File logFile() => File('${temp.path}/playtrace.log');
  List<String> fileLines() => logFile().readAsLinesSync().where((l) => l.isNotEmpty).toList();

  test('should_cutTokens_when_lineCarriesBearerOrOAuthValues', () {
    final out = PlayTrace.redact('GET /me/player Bearer abc.def-123 refresh_token=r1&code=xyz access_token=a9');
    expect(out, isNot(contains('abc.def-123')));
    expect(out, isNot(contains('r1')));
    expect(out, isNot(contains('xyz')));
    expect(out, isNot(contains('a9')));
    expect(out, contains('Bearer …'));
  });

  test('should_cutSubsonicCredentials_when_lineCarriesAStreamUrl', () {
    final out = PlayTrace.redact(
      'failed https://me:secret@music.example.com/rest/stream.view?u=alice&t=deadbeef&s=salt1&p=pw&apiKey=k&id=42',
    );
    for (final leak in ['alice', 'deadbeef', 'salt1', '=pw', 'apiKey=k', 'me:secret']) {
      expect(out, isNot(contains(leak)), reason: '$leak leaked: $out');
    }
    expect(out, contains('id=42'));
  });

  test('should_keepOnlyEndpointAndSafeParams_when_describingAStreamUrl', () {
    final out = PlayTrace.describe(
      'https://music.example.com/rest/stream.view?u=alice&t=deadbeef&s=salt&v=1.16.1&c=Yoin&id=42&maxBitRate=320&format=mp3',
    );
    expect(out, 'https://…/stream.view?id=42&maxBitRate=320&format=mp3');
    expect(PlayTrace.describe('https://music.example.com/rest/stream.view'), 'https://…/stream.view');
  });

  test('should_maskHostAndIp_when_errorMessageNamesTheServer', () {
    expect(
      PlayTrace.maskHosts('Unable to resolve host "music.example.com": No address associated with hostname'),
      'Unable to resolve host "…": No address associated with hostname',
    );
    expect(
      PlayTrace.maskHosts('failed to connect to music.example.com/203.0.113.4 (port 443) after 10000ms'),
      'failed to connect to …/… (port 443) after 10000ms',
    );
    final v6 = PlayTrace.maskHosts('failed to connect to /[2001:db8::1]:4533 and http://navidrome:4533/rest/ping');
    expect(v6.contains('2001:db8') || v6.contains('navidrome'), isFalse, reason: v6);
    expect(PlayTrace.maskHosts(null), '');
  });

  test('should_keepOnlyTheLastLines_when_moreThanCapacityAreAppended', () {
    final ring = PlayTraceRing(3);
    for (var i = 1; i <= 5; i++) {
      ring.append('line $i');
    }
    expect(ring.snapshot(), ['line 3', 'line 4', 'line 5']);
  });

  test('should_readThePreviousRunsTail_when_theFileAlreadyHasLines', () {
    final first = PlayTraceRing(3, logFile());
    for (var i = 1; i <= 4; i++) {
      first.append('old $i');
    }
    final next = PlayTraceRing(3, logFile());
    next.append('new 1');
    expect(next.snapshot(), ['old 3', 'old 4', 'new 1']);
  });

  test('should_cutTheFileBackToCapacity_when_capacityMoreLinesWereWritten', () {
    final ring = PlayTraceRing(3, logFile());
    for (var i = 1; i <= 7; i++) {
      ring.append('line $i');
    }
    expect(fileLines().length, lessThanOrEqualTo(6));
    expect(fileLines().sublist(fileLines().length - 3), ['line 5', 'line 6', 'line 7']);
  });

  test('should_putTheEarlyLinesAfterTheFilesTail_when_theFolderArrivesLater', () {
    logFile().writeAsStringSync('old 1\nold 2\n');
    final ring = PlayTraceRing(3)..append('early 1');
    ring.attach(logFile());
    ring.append('late 1');
    expect(ring.snapshot(), ['old 2', 'early 1', 'late 1']);
    expect(fileLines(), ['old 1', 'old 2', 'early 1', 'late 1']);
  });

  test('should_makeTheFolder_when_theCacheFolderDoesNotExistYet', () {
    final file = File('${temp.path}/cache/playtrace.log');
    PlayTraceRing(3, file).append('app launch');
    expect(file.readAsLinesSync(), ['app launch']);
  });

  test('should_dropLines_when_neverInstalled', () {
    final trace = PlayTrace();
    trace.log('play subsonic s1');
    expect(trace.installed, isFalse);
    expect(trace.lines, isEmpty);
  });

  test('should_stampAndRedactEachLine_when_logged', () {
    final trace = PlayTrace.memory(now: () => DateTime(2026, 11, 17, 9, 5, 7, 42));
    trace.log('player error https://x.test/rest/stream.view?u=alice&t=tok');
    trace.log('engine open stream', atMs: DateTime(2026, 11, 17, 8, 0).millisecondsSinceEpoch);
    expect(trace.lines, [
      '11-17 09:05:07.042 player error https://x.test/rest/stream.view?u=…&t=…',
      '11-17 08:00:00.000 engine open stream',
    ]);
  });

  test('should_keepTheLogInTheCacheFolder_when_installed', () async {
    final trace = PlayTrace(capacity: 10)..install(() async => temp);
    trace.log('app launch');
    await Future<void>.delayed(Duration.zero);
    trace.log('pause LOCAL');
    expect(trace.lines.map((l) => l.substring(19)), ['app launch', 'pause LOCAL']);
    expect(fileLines().map((l) => l.substring(19)), ['app launch', 'pause LOCAL']);
  });

  test('should_writeHeaderThenLinesAndDropEarlierExports_when_exported', () async {
    final trace = PlayTrace.memory(now: () => DateTime(2026, 11, 17, 21, 4, 5));
    trace.log('app launch');
    final shared = Directory('${temp.path}/shared')..createSync();
    File('${shared.path}/yoin-log-20261116-101010.txt').writeAsStringSync('old');
    File('${shared.path}/yoin-lyrics-1.png').writeAsStringSync('keep');

    final file = await trace.writeExport(temp, header: ['Yoin 0.1.0 (1)', 'HarmonyOS 6.0', '']);

    expect(file.path, '${shared.path}/yoin-log-20261117-210405.txt');
    expect(file.readAsStringSync(), 'Yoin 0.1.0 (1)\nHarmonyOS 6.0\n\n11-17 21:04:05.000 app launch\n');
    final names = shared.listSync().map((e) => e.uri.pathSegments.last).toSet();
    expect(names, {'yoin-log-20261117-210405.txt', 'yoin-lyrics-1.png'});
  });
}
