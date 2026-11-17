/// The one message Continue On needs that the system doesn't carry: "pause,
/// I've taken over", from the device that took a hand-off back to the one
/// that gave it (Android player/continueon/ContinueOnPauseChannel.kt, the same
/// protocol). The other device shows a small card first; only when the person
/// taps it does this device stop (owner 2026-11-08).
///
/// The device handing off listens on a local port and puts its addresses, the
/// port and a one-time token in the hand-off ([ContinueOnCursor.pause]). One
/// line each way, plain text:
///
///     → PAUSE <token> <trackId>
///     ← OK <positionMs> <trackId>   (the song this device was on by then, and where it stopped)
///     ← OK -1                       (it had no song loaded any more)
///     ← NO                          (wrong token)
///
/// The port closes after one good PAUSE, five wrong tokens, or the first time
/// [ContinueOnPauseServer.lifetime] runs out with the listener no longer
/// needed; nothing else is ever answered.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:flutter/foundation.dart';

import 'continue_on_cursor.dart';

/// Where the device that handed off stopped: the song it was on by then, and its position in it.
@immutable
final class PausedAt {
  const PausedAt(this.trackId, this.positionMs);
  final String trackId;
  final int positionMs;
}

/// What the device that handed off answered.
sealed class PauseAnswer {
  const PauseAnswer();
}

/// It paused. [trackId] is the song it was on by then (null: the one asked
/// about) and [positionMs] where in it (null: it had no song loaded any more).
final class PauseAnswerPaused extends PauseAnswer {
  const PauseAnswerPaused({this.positionMs, this.trackId});
  final int? positionMs;
  final String? trackId;

  @override
  bool operator ==(Object other) =>
      other is PauseAnswerPaused && other.positionMs == positionMs && other.trackId == trackId;

  @override
  int get hashCode => Object.hash(positionMs, trackId);

  @override
  String toString() => 'Paused($positionMs, $trackId)';
}

/// No answer (not reachable, a router isolating its devices, the port closed).
final class PauseAnswerUnreachable extends PauseAnswer {
  const PauseAnswerUnreachable();

  @override
  bool operator ==(Object other) => other is PauseAnswerUnreachable;

  @override
  int get hashCode => 0;

  @override
  String toString() => 'Unreachable';
}

const String _nothingLoaded = '-1';
final RegExp _whitespace = RegExp(r'\s');

/// The answer to a good PAUSE. As in [PauseRequest], a space or line break in a track id is never sent.
String pausedLine(PausedAt? stopped) {
  final trackId = stopped?.trackId.replaceAll(_whitespace, '') ?? '';
  if (stopped == null || trackId.isEmpty) return 'OK $_nothingLoaded\n';
  return 'OK ${stopped.positionMs < 0 ? 0 : stopped.positionMs} $trackId\n';
}

/// One PAUSE line. Track ids are sent as given; a space or line break in one is never sent.
@immutable
final class PauseRequest {
  const PauseRequest(this.token, this.trackId);
  final String token;
  final String trackId;

  String line() => 'PAUSE $token ${trackId.replaceAll(_whitespace, '')}\n';

  @override
  bool operator ==(Object other) => other is PauseRequest && other.token == token && other.trackId == trackId;

  @override
  int get hashCode => Object.hash(token, trackId);

  static PauseRequest? parse(String line) {
    final parts = line.trim().split(' ');
    if (parts.length != 3 || parts[0] != 'PAUSE' || parts[1].isEmpty || parts[2].isEmpty) return null;
    return PauseRequest(parts[1], parts[2]);
  }
}

/// A reply line read strictly; null for anything else.
PauseAnswer? parsePauseReply(String reply) {
  final parts = reply.trim().split(' ');
  if (parts.first != 'OK') return null;
  final second = parts.length > 1 ? parts[1] : null;
  final position = second == null ? null : int.tryParse(second);
  if (position == null || position < 0) {
    return second == _nothingLoaded ? const PauseAnswerPaused() : null;
  }
  final trackId = parts.length > 2 && parts[2].isNotEmpty ? parts[2] : null;
  return PauseAnswerPaused(positionMs: position, trackId: trackId);
}

/// The listening end. The system may hand a cursor over long after it read it,
/// so the answer names the song this device is on by then, and the port
/// outlives [lifetime] for as long as [stillNeeded] says so.
class ContinueOnPauseServer {
  ContinueOnPauseServer({
    required this.onPause,
    this.lifetime = defaultLifetime,
    bool Function()? stillNeeded,
    Random? random,
  }) : _stillNeeded = stillNeeded ?? (() => false),
       _random = random ?? Random.secure();

  /// Pauses this device's playback: the song it was on and where it stopped,
  /// which need not be the one asked about any more; null when no song is loaded.
  final Future<PausedAt?> Function(String trackId) onPause;

  /// Asked each time [lifetime] runs out: true keeps the listener for another round.
  final bool Function() _stillNeeded;
  final Duration lifetime;
  final Random _random;

  static const Duration defaultLifetime = Duration(minutes: 2);
  static const Duration readTimeout = Duration(seconds: 2);
  static const int _maxWrong = 5;
  static const int _tokenBytes = 16;

  ServerSocket? _socket;
  String? _token;
  Timer? _expiry;
  int _wrong = 0;
  bool _paused = false;
  Future<(int, String)?>? _opening;

  /// Whether a listener is open now.
  bool get isOpen => _socket != null;

  /// The port and token to put in a hand-off: the listener already open (its
  /// time restarted), or a new one; null when none can be opened.
  Future<(int, String)?> open() {
    final open = _socket;
    final token = _token;
    if (open != null && token != null) {
      _restartExpiry(open);
      return Future.value((open.port, token));
    }
    return _opening ??= _bind().whenComplete(() => _opening = null);
  }

  Future<(int, String)?> _bind() async {
    ServerSocket server;
    try {
      server = await ServerSocket.bind(InternetAddress.anyIPv6, 0, v6Only: false);
    } catch (_) {
      try {
        server = await ServerSocket.bind(InternetAddress.anyIPv4, 0);
      } catch (e) {
        debugPrint('YoinContinueOn: no pause port (${e.runtimeType})');
        return null;
      }
    }
    final token = _newToken();
    _socket = server;
    _token = token;
    _wrong = 0;
    _paused = false;
    server.listen(
      (client) => _serve(server, client, token),
      onError: (Object _) => _close(server),
      cancelOnError: true,
    );
    _restartExpiry(server);
    return (server.port, token);
  }

  void _restartExpiry(ServerSocket server) {
    _expiry?.cancel();
    _expiry = Timer(lifetime, () {
      if (_stillNeeded()) {
        _restartExpiry(server);
      } else {
        _close(server);
      }
    });
  }

  /// Closes the listener now (the account changed, the app is going away).
  Future<void> close() async {
    final server = _socket;
    if (server != null) await _close(server);
  }

  Future<void> _close(ServerSocket server) async {
    if (!identical(_socket, server)) return;
    _socket = null;
    _token = null;
    _expiry?.cancel();
    _expiry = null;
    try {
      await server.close();
    } catch (_) {}
  }

  Future<void> _serve(ServerSocket server, Socket client, String secret) async {
    try {
      final line = await readContinueOnLine(client, readTimeout);
      final request = line == null ? null : PauseRequest.parse(line);
      if (request == null || !identical(_socket, server) || _paused) return;
      if (!_constantTimeEquals(request.token, secret)) {
        client.add(utf8.encode('NO\n'));
        await client.flush();
        if (++_wrong >= _maxWrong) await _close(server);
        return;
      }
      _paused = true;
      PausedAt? stopped;
      try {
        stopped = await onPause(request.trackId);
      } catch (e) {
        debugPrint('YoinContinueOn: pause failed (${e.runtimeType})');
      }
      client.add(utf8.encode(pausedLine(stopped)));
      await client.flush();
      await _close(server);
    } catch (_) {
      // A dropped client: nothing to answer.
    } finally {
      client.destroy();
    }
  }

  String _newToken() {
    final bytes = Uint8List.fromList([for (var i = 0; i < _tokenBytes; i++) _random.nextInt(256)]);
    return base64Url.encode(bytes).replaceAll('=', '');
  }
}

/// Asks the device that handed off to pause (Android ContinueOnPauseClient).
/// Each address in turn; [PauseAnswerUnreachable] when none answers in [total].
Future<PauseAnswer> requestContinueOnPause(
  ContinueOnPauseChannel channel,
  String trackId, {
  Duration connectTimeout = const Duration(milliseconds: 1200),
  Duration readTimeout = const Duration(seconds: 2),
  Duration total = const Duration(seconds: 4),
}) async {
  Future<PauseAnswer> ask() async {
    for (final host in channel.hosts) {
      Socket? socket;
      try {
        socket = await Socket.connect(host, channel.port, timeout: connectTimeout);
        socket.add(utf8.encode(PauseRequest(channel.token, trackId).line()));
        await socket.flush();
        final reply = await readContinueOnLine(socket, readTimeout);
        final answer = reply == null ? null : parsePauseReply(reply);
        if (answer != null) return answer;
      } catch (_) {
        // The next address.
      } finally {
        socket?.destroy();
      }
    }
    return const PauseAnswerUnreachable();
  }

  try {
    return await ask().timeout(total, onTimeout: () => const PauseAnswerUnreachable());
  } catch (_) {
    return const PauseAnswerUnreachable();
  }
}

/// At most this many characters in a line, so a stray peer can't make a read go on forever.
const int _maxLineChars = 1024;

/// One line from [socket] (without its `\n`), or null: too long, nothing
/// before [timeout], or the peer gone with nothing sent. The caller destroys
/// the socket when it is done with it.
@visibleForTesting
Future<String?> readContinueOnLine(Socket socket, Duration timeout) {
  final done = Completer<String?>();
  final bytes = BytesBuilder(copy: false);
  void finish(String? line) {
    if (!done.isCompleted) done.complete(line);
  }

  socket.listen(
    (chunk) {
      if (done.isCompleted) return;
      for (final b in chunk) {
        if (b == 0x0A) {
          finish(utf8.decode(bytes.takeBytes(), allowMalformed: true));
          return;
        }
        bytes.addByte(b);
        if (bytes.length >= _maxLineChars) {
          finish(null);
          return;
        }
      }
    },
    onError: (Object _) => finish(null),
    onDone: () {
      final rest = bytes.takeBytes();
      finish(rest.isEmpty ? null : utf8.decode(rest, allowMalformed: true));
    },
    cancelOnError: true,
  );
  return done.future.timeout(timeout, onTimeout: () => null);
}

bool _constantTimeEquals(String a, String b) {
  final x = utf8.encode(a);
  final y = utf8.encode(b);
  var diff = x.length ^ y.length;
  for (var i = 0; i < x.length; i++) {
    diff |= x[i] ^ (i < y.length ? y[i] : 0);
  }
  return diff == 0;
}
