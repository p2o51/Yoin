import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/continueon/continue_on_cursor.dart';
import 'package:yoin/continueon/continue_on_pause_channel.dart';

/// Android ContinueOnPauseChannelTest, over real loopback sockets.
void main() {
  late List<String> paused;
  late PausedAt? nowPlaying;
  late bool stillNeeded;
  late List<ContinueOnPauseServer> servers;
  late ContinueOnPauseServer server;

  ContinueOnPauseServer newServer({Duration lifetime = ContinueOnPauseServer.defaultLifetime}) {
    final s = ContinueOnPauseServer(
      onPause: (trackId) async {
        paused.add(trackId);
        return nowPlaying;
      },
      lifetime: lifetime,
      stillNeeded: () => stillNeeded,
    );
    servers.add(s);
    return s;
  }

  setUp(() {
    paused = [];
    nowPlaying = const PausedAt('t1', 42000);
    stillNeeded = false;
    servers = [];
    server = newServer();
  });

  tearDown(() async {
    for (final s in servers) {
      await s.close();
    }
  });

  Future<ContinueOnPauseChannel> channel({String? token, ContinueOnPauseServer? of}) async {
    final opened = (await (of ?? server).open())!;
    return ContinueOnPauseChannel(hosts: const ['127.0.0.1'], port: opened.$1, token: token ?? opened.$2);
  }

  const quick = Duration(milliseconds: 300);

  test('should_pauseAndSayWhere_when_tokenMatches', () async {
    expect(
      await requestContinueOnPause(await channel(), 't1'),
      const PauseAnswerPaused(positionMs: 42000, trackId: 't1'),
    );
    expect(paused, ['t1']);
  });

  test('should_sayWhichSongItIsOnByNow_when_itHasMovedOnSinceTheHandOff', () async {
    nowPlaying = const PausedAt('t6', 7000);
    expect(
      await requestContinueOnPause(await channel(), 't2'),
      const PauseAnswerPaused(positionMs: 7000, trackId: 't6'),
    );
    expect(paused, ['t2']);
  });

  test('should_pauseWithoutAPosition_when_noSongIsLoadedAnyMore', () async {
    nowPlaying = null;
    expect(await requestContinueOnPause(await channel(), 't1'), const PauseAnswerPaused());
  });

  test('should_notPause_when_tokenIsWrong', () async {
    expect(await requestContinueOnPause(await channel(token: 'wrong'), 't1'), const PauseAnswerUnreachable());
    expect(paused, isEmpty);
  });

  test('should_closeAfterFiveWrongTokens_when_guessed', () async {
    final good = await channel();
    final wrong = ContinueOnPauseChannel(hosts: good.hosts, port: good.port, token: 'wrong');
    for (var i = 0; i < 5; i++) {
      await requestContinueOnPause(wrong, 't1', connectTimeout: quick);
    }
    expect(server.isOpen, isFalse);
    expect(await requestContinueOnPause(good, 't1', connectTimeout: quick), const PauseAnswerUnreachable());
    expect(paused, isEmpty);
  });

  test('should_closeAfterOnePause_when_asked', () async {
    final first = await channel();
    await requestContinueOnPause(first, 't1');
    expect(await requestContinueOnPause(first, 't1', connectTimeout: quick), const PauseAnswerUnreachable());
    expect(paused, hasLength(1));
  });

  test('should_keepTheSameToken_when_openedAgainWhileOpen', () async {
    final first = await server.open();
    final second = await server.open();
    expect(second, first);
  });

  test('should_stayOpenPastItsTime_when_theSongIsStillHere', () async {
    // The other device may take the hand-off long after the cursor was made.
    stillNeeded = true;
    final c = await channel(of: newServer(lifetime: const Duration(milliseconds: 100)));
    await Future<void>.delayed(const Duration(milliseconds: 600));
    expect(await requestContinueOnPause(c, 't1'), const PauseAnswerPaused(positionMs: 42000, trackId: 't1'));
  });

  test('should_closeWhenItsTimeRunsOut_when_noSongIsHereAnyMore', () async {
    stillNeeded = false;
    final c = await channel(of: newServer(lifetime: const Duration(milliseconds: 100)));
    await Future<void>.delayed(const Duration(milliseconds: 600));
    expect(await requestContinueOnPause(c, 't1', connectTimeout: quick), const PauseAnswerUnreachable());
    expect(paused, isEmpty);
  });

  test('should_reportUnreachable_when_nothingListens', () async {
    final probe = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
    final port = probe.port;
    await probe.close();
    final answer = await requestContinueOnPause(
      ContinueOnPauseChannel(hosts: const ['127.0.0.1'], port: port, token: 'tok'),
      't1',
      connectTimeout: quick,
    );
    expect(answer, const PauseAnswerUnreachable());
  });

  test('should_tryTheNextAddress_when_theFirstDoesNotAnswer', () async {
    final good = await channel();
    // 192.0.2.1 (TEST-NET-1) never answers: the next address does.
    final answer = await requestContinueOnPause(
      ContinueOnPauseChannel(hosts: const ['192.0.2.1', '127.0.0.1'], port: good.port, token: good.token),
      't1',
      connectTimeout: quick,
    );
    expect(answer, const PauseAnswerPaused(positionMs: 42000, trackId: 't1'));
  });

  test('should_parseRepliesStrictly', () {
    expect(parsePauseReply('OK 5 t6'), const PauseAnswerPaused(positionMs: 5, trackId: 't6'));
    // No song named: the one that was asked about.
    expect(parsePauseReply('OK 5'), const PauseAnswerPaused(positionMs: 5));
    expect(parsePauseReply('OK -1'), const PauseAnswerPaused());
    expect(parsePauseReply('NO'), isNull);
    expect(parsePauseReply('OK'), isNull);
    expect(parsePauseReply('OK soon t6'), isNull);
    expect(PauseRequest.parse('PAUSE onlytoken'), isNull);
    expect(PauseRequest.parse('PAUSE a b\n'), const PauseRequest('a', 'b'));
  });

  test('should_neverSendASpaceOrALineBreak_when_theSongsIdHasOne', () {
    expect(pausedLine(const PausedAt('t6 \nx', 5)), 'OK 5 t6x\n');
    expect(pausedLine(const PausedAt('t6', -3)), 'OK 0 t6\n');
    expect(pausedLine(null), 'OK -1\n');
    expect(pausedLine(const PausedAt(' ', 5)), 'OK -1\n');
    expect(const PauseRequest('tok', 'a b\nc').line(), 'PAUSE tok abc\n');
  });
}
