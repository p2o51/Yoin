/// The player's way into the stream cache (Android CacheDataSource). HarmonyOS
/// AVPlayer has no data-source hook, so the player opens a loopback address
/// served here instead of the server's: a song already cached is served from
/// its file; any other is fetched once, whole, into a part file that the
/// player reads as it grows and that becomes the cached song when it is all
/// there. Range requests work against both (seeking, and AVPlayer's
/// read-ahead); a seek far past what has arrived goes to the server directly
/// for that request while the fetch carries on. A fetch nobody listens to for
/// [StreamCacheProxy.idleTimeout] (the song was skipped) stops and keeps nothing.
///
/// A transcode has no byte ranges (Navidrome sends it from the start whatever
/// the Range asks). A constant-bit-rate one the server can start mid-song
/// ([StreamCacheSource.timeSeek], Android SubsonicStreamResolver's
/// `timeOffset`) is asked from the second a far range falls in instead, and
/// its bytes stand in for the ones from there on.
///
/// Only addresses handed out by [StreamCacheProxy.addressFor] are served,
/// under an unguessable token, so another app on the device can't use the
/// port to reach the server or read the cache. The native player falls back
/// to the server's address when this one fails (the proxy is gone).
library;

import 'dart:async';
import 'dart:io';
import 'dart:math';

import 'package:flutter/foundation.dart';

import '../model/model.dart' show StreamTimeSeek;
import 'stream_cache.dart';

/// What one queue entry streams: the server's address and headers, and the song.
@immutable
final class StreamCacheSource {
  const StreamCacheSource({required this.uri, this.headers = const {}, required this.song, this.suffix, this.timeSeek});

  final Uri uri;
  final Map<String, String> headers;
  final StreamCacheSong song;

  /// The file's kind (`mp3`, `flac`), for the cached file's name.
  final String? suffix;

  /// How a range past the start reaches the server when [uri] is a transcode
  /// without byte ranges; null when the server takes ranges (the file).
  final StreamTimeSeek? timeSeek;
}

class StreamCacheProxy {
  StreamCacheProxy({
    required this.cache,
    HttpClient Function()? client,
    this.idleTimeout = defaultIdleTimeout,
    this.connectTimeout = const Duration(seconds: 30),
    this.stallTimeout = const Duration(seconds: 30),
    this.lookaheadBytes = 512 * 1024,
    Random? random,
  }) : _client = (client ?? HttpClient.new)()..autoUncompress = false,
       _random = random ?? Random.secure();

  final StreamCache cache;
  final HttpClient _client;
  final Random _random;

  /// A fetch no player reads stops after this (the song was skipped).
  final Duration idleTimeout;

  /// Longer than the native engine's gapless lead (PlaybackEngine PRELOAD_LEAD_MS, 20 s): the spare player
  /// reads the next song's start that long before it plays, and its fetch must still be running then.
  static const defaultIdleTimeout = Duration(seconds: 30);
  final Duration connectTimeout;

  /// The server sent nothing for this long: the fetch counts as dropped.
  final Duration stallTimeout;

  /// A range starting this far past what has arrived goes to the server
  /// directly instead of waiting for the fetch.
  final int lookaheadBytes;

  /// Registered addresses kept at most (a session's distinct songs).
  static const maxSources = 20000;
  static const _chunk = 64 * 1024;
  static const _path = 'a';

  HttpServer? _server;
  Future<bool>? _starting;
  bool _closed = false;

  /// token → (key, source); key → token (one address per song and quality).
  final _sources = <String, (String, StreamCacheSource)>{};
  final _tokens = <String, String>{};
  final _fetches = <String, _Fetch>{};

  /// Starts the cache and binds the loopback port. False when this build
  /// keeps no cache or the port can't be bound: songs stream directly.
  Future<bool> start() => _starting ??= _start();

  Future<bool> _start() async {
    try {
      await cache.start();
      if (!cache.available || _closed) return false;
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      if (_closed) {
        await server.close(force: true);
        return false;
      }
      server.autoCompress = false;
      server.defaultResponseHeaders.clear();
      _server = server;
      server.listen((request) => unawaited(_handle(request)), onError: (Object e) => debugPrint('YoinStreamCache: $e'));
      return true;
    } catch (e) {
      debugPrint('YoinStreamCache: proxy not started: $e');
      return false;
    }
  }

  /// `http://127.0.0.1:<port>` while serving.
  String? get origin {
    final server = _server;
    return server == null ? null : 'http://${InternetAddress.loopbackIPv4.address}:${server.port}';
  }

  /// The loopback address the player opens for [source] (cached under
  /// [StreamCache.keyOf]); null while the proxy isn't up or the address
  /// isn't http(s).
  String? addressFor(StreamCacheSource source) {
    final origin = this.origin;
    if (origin == null || !(source.uri.isScheme('http') || source.uri.isScheme('https'))) return null;
    final key = StreamCache.keyOf(source.song.provider, source.uri.toString());
    var token = _tokens[key];
    if (token == null) {
      token = _newToken();
      _tokens[key] = token;
      if (_sources.length >= maxSources) {
        final oldest = _sources.keys.first;
        final dropped = _sources.remove(oldest);
        if (dropped != null) _tokens.remove(dropped.$1);
      }
    }
    // The newest signature: the server may have been re-signed since.
    _sources[token] = (key, source);
    return '$origin/$_path/$token';
  }

  Future<void> close() async {
    _closed = true;
    for (final f in _fetches.values.toList()) {
      f.cancel();
    }
    _fetches.clear();
    await _server?.close(force: true);
    _server = null;
    _client.close(force: true);
  }

  /// Fetches running now, for tests.
  @visibleForTesting
  int get runningFetches => _fetches.length;

  String _newToken() {
    final bytes = List<int>.generate(16, (_) => _random.nextInt(256));
    return bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  }

  // ── Serving ───────────────────────────────────────────────────────

  Future<void> _handle(HttpRequest request) async {
    final response = request.response;
    try {
      final segments = request.uri.pathSegments;
      final found = segments.length == 2 && segments[0] == _path ? _sources[segments[1]] : null;
      if (found == null || (request.method != 'GET' && request.method != 'HEAD')) {
        response.statusCode = found == null ? HttpStatus.notFound : HttpStatus.methodNotAllowed;
        await response.close();
        return;
      }
      final (key, source) = found;
      final range = _Range.parse(request.headers.value(HttpHeaders.rangeHeader));
      final hit = await cache.open(key);
      if (hit != null) {
        await _serveFile(request, hit.$1, hit.$2.contentType, range);
        return;
      }
      await _serveFetch(request, key, source, range);
    } catch (e) {
      // The player went away mid-answer, or the server failed before anything was sent.
      debugPrint('YoinStreamCache: request ended: ${e.runtimeType}');
      try {
        // Nothing sent yet (the server unreachable): an error, never an empty 200 the player takes for the song,
        // so the native engine falls back to the server's address. Throws once the headers are out.
        response.statusCode = HttpStatus.badGateway;
      } catch (_) {}
      try {
        await response.close();
      } catch (_) {}
    }
  }

  Future<void> _serveFile(HttpRequest request, File file, String? contentType, _Range? range) async {
    final response = request.response;
    final reader = await file.open();
    try {
      final total = await reader.length();
      final span = range?.resolve(total);
      if (range != null && span == null) {
        await _unsatisfiable(response, total);
        return;
      }
      final (start, end) = span ?? (0, total - 1);
      _head(response, range: range != null, start: start, end: end, total: total, contentType: contentType);
      if (request.method == 'HEAD') {
        await response.close();
        return;
      }
      final body = await _Body.open(response);
      try {
        var pos = start;
        while (pos <= end) {
          await reader.setPosition(pos);
          final bytes = await reader.read(min(_chunk, end - pos + 1));
          if (bytes.isEmpty || !await body.add(bytes)) break;
          pos += bytes.length;
        }
      } finally {
        await body.close();
      }
    } finally {
      await reader.close();
    }
  }

  Future<void> _serveFetch(HttpRequest request, String key, StreamCacheSource source, _Range? range) async {
    final response = request.response;
    final fetch = _fetchFor(key, source);
    if (fetch == null) {
      await _passThrough(request, source, range);
      return;
    }
    fetch.attach();
    try {
      try {
        await fetch.ready;
      } catch (_) {
        // The server refused or answered with something that isn't the song:
        // the player hears the server's own answer.
        await _passThrough(request, source, range);
        return;
      }
      final total = fetch.total;
      final openStartUnknownTotal = range != null && total == null && range.start == 0 && range.end == null;
      if (range != null && total == null && !openStartUnknownTotal) {
        // No length (a transcode) with a mid-song range: only the server can answer it.
        await _passThrough(request, source, range);
        return;
      }
      final serveAsRange = range != null && !openStartUnknownTotal;
      final span = serveAsRange ? range.resolve(total!) : null;
      if (serveAsRange && span == null) {
        await _unsatisfiable(response, total!);
        return;
      }
      final start = span?.$1 ?? 0;
      final end = span?.$2 ?? (total == null ? null : total - 1);
      if (start > fetch.available + lookaheadBytes) {
        // A seek well ahead of the fetch: the server answers it now.
        await _passThrough(request, source, range, total: total);
        return;
      }
      final RandomAccessFile reader;
      try {
        reader = await fetch.part.open();
      } on FileSystemException {
        // Finished (and moved into the cache) or dropped since: answer from where it is now.
        final hit = await cache.open(key);
        if (hit != null) {
          await _serveFile(request, hit.$1, hit.$2.contentType, range);
        } else {
          await _passThrough(request, source, range, total: total);
        }
        return;
      }
      try {
        _head(response, range: serveAsRange, start: start, end: end, total: total, contentType: fetch.contentType);
        if (request.method == 'HEAD') {
          await response.close();
          return;
        }
        final body = await _Body.open(response);
        try {
          var pos = start;
          while (!body.gone && (end == null || pos <= end)) {
            final available = fetch.available;
            if (pos < available) {
              final want = min(_chunk, (end == null ? available : min(available, end + 1)) - pos);
              await reader.setPosition(pos);
              final bytes = await reader.read(want);
              if (bytes.isEmpty || !await body.add(bytes)) break;
              pos += bytes.length;
              continue;
            }
            if (fetch.complete) break;
            if (fetch.failed) {
              // The fetch dropped: the rest comes from the server directly.
              if (total != null) await _continueFromServer(body, source, pos, end ?? total - 1);
              break;
            }
            // Wakes for new bytes, the end, or the player hanging up (a skip, a seek elsewhere).
            await Future.any([fetch.changed, body.hungUp]);
          }
        } finally {
          await body.close();
        }
      } finally {
        await reader.close();
      }
    } finally {
      fetch.detach();
    }
  }

  static Future<void> _unsatisfiable(HttpResponse response, int total) async {
    response.statusCode = HttpStatus.requestedRangeNotSatisfiable;
    response.headers.set(HttpHeaders.contentRangeHeader, 'bytes */$total');
    await response.close();
  }

  void _head(
    HttpResponse response, {
    required bool range,
    required int start,
    int? end,
    int? total,
    String? contentType,
  }) {
    response.statusCode = range ? HttpStatus.partialContent : HttpStatus.ok;
    response.headers.set(HttpHeaders.contentTypeHeader, contentType ?? 'application/octet-stream');
    if (total != null) {
      response.headers.set(HttpHeaders.acceptRangesHeader, 'bytes');
      final last = end ?? total - 1;
      response.contentLength = last - start + 1;
      if (range) response.headers.set(HttpHeaders.contentRangeHeader, 'bytes $start-$last/$total');
    }
  }

  /// The running fetch of [key], or a new one; null when nothing can be written.
  _Fetch? _fetchFor(String key, StreamCacheSource source) {
    final running = _fetches[key];
    if (running != null && !running.failed) return running;
    final part = cache.newPartFile(key);
    if (part == null || _closed) return null;
    final fetch = _Fetch(
      key: key,
      source: source,
      part: part,
      generation: cache.generation,
      idleTimeout: idleTimeout,
      onIdle: (f) {
        if (identical(_fetches[f.key], f)) _fetches.remove(f.key);
        f.cancel();
        unawaited(cache.discard(f.part));
      },
    );
    _fetches[key] = fetch;
    unawaited(_run(fetch));
    return fetch;
  }

  Future<void> _run(_Fetch fetch) async {
    RandomAccessFile? out;
    try {
      final request = await _client.getUrl(fetch.source.uri).timeout(connectTimeout);
      fetch.source.headers.forEach(request.headers.set);
      fetch.request = request;
      final upstream = await request.close().timeout(connectTimeout);
      if (fetch.cancelled) {
        _drain(upstream);
        return;
      }
      final type = upstream.headers.contentType?.mimeType.toLowerCase() ?? '';
      // A Subsonic error comes back as a 200 with an XML or JSON body, never audio.
      if (upstream.statusCode != HttpStatus.ok || type.contains('xml') || type.contains('json')) {
        _drain(upstream);
        throw HttpException('HTTP ${upstream.statusCode} $type');
      }
      fetch.total = upstream.contentLength >= 0 ? upstream.contentLength : null;
      fetch.contentType = upstream.headers.value(HttpHeaders.contentTypeHeader);
      out = await fetch.part.open(mode: FileMode.write);
      fetch.markReady();
      final writer = out;
      await for (final chunk in upstream.timeout(stallTimeout)) {
        if (fetch.cancelled) break;
        await writer.writeFrom(chunk);
        fetch.grew(chunk.length);
      }
      await writer.close();
      out = null;
      if (fetch.cancelled) return;
      final total = fetch.total;
      if (total != null && fetch.available != total) throw HttpException('short: ${fetch.available} of $total');
      final stored = await cache.commit(
        fetch.key,
        fetch.source.song,
        fetch.part,
        generation: fetch.generation,
        contentType: fetch.contentType,
        suffix: fetch.source.suffix,
      );
      debugPrint('YoinStreamCache: ${stored == null ? 'not stored' : 'stored ${stored.fileSizeBytes} B'}');
      fetch.finish();
    } catch (e) {
      try {
        await out?.close();
      } catch (_) {}
      if (!fetch.cancelled) debugPrint('YoinStreamCache: fetch dropped: ${e.runtimeType}');
      fetch.fail(e);
      // Readers keep their own handles; the part file goes now.
      unawaited(cache.discard(fetch.part));
    } finally {
      if (identical(_fetches[fetch.key], fetch) && (fetch.complete || fetch.failed)) _fetches.remove(fetch.key);
    }
  }

  /// Answers [request] straight from the server (no caching), headers and all.
  /// A range past the start of a transcode that can start mid-song is asked
  /// by time instead ([_answerFromTime]); [total] is the song's length when
  /// the running fetch knows it.
  Future<void> _passThrough(HttpRequest request, StreamCacheSource source, _Range? range, {int? total}) async {
    if (request.method != 'HEAD' && range != null && await _answerFromTime(request, source, range, total)) return;
    final response = request.response;
    final upstreamRequest = request.method == 'HEAD'
        ? await _client.headUrl(source.uri).timeout(connectTimeout)
        : await _client.getUrl(source.uri).timeout(connectTimeout);
    source.headers.forEach(upstreamRequest.headers.set);
    if (range != null) upstreamRequest.headers.set(HttpHeaders.rangeHeader, range.header);
    final upstream = await upstreamRequest.close().timeout(connectTimeout);
    var streamHandedOff = false;
    try {
      response.statusCode = upstream.statusCode;
      for (final name in const [
        HttpHeaders.contentTypeHeader,
        HttpHeaders.contentRangeHeader,
        HttpHeaders.acceptRangesHeader,
      ]) {
        final value = upstream.headers.value(name);
        if (value != null) response.headers.set(name, value);
      }
      if (upstream.contentLength >= 0) response.contentLength = upstream.contentLength;
      if (request.method == 'HEAD') {
        _drain(upstream);
        streamHandedOff = true;
        await response.close();
        return;
      }
      final body = await _Body.open(response);
      try {
        streamHandedOff = true;
        await body.addStream(upstream.timeout(stallTimeout));
      } finally {
        await body.close();
      }
    } finally {
      if (!streamHandedOff) _drain(upstream);
    }
  }

  /// The rest of an answer already under way ([from]–[to]), from the server.
  Future<void> _continueFromServer(_Body body, StreamCacheSource source, int from, int to) async {
    final timed = await _openFromTime(source, from);
    if (timed != null) {
      final (upstream, _) = timed;
      // Stopping early cancels the server's answer.
      await body.addStream(upstream.timeout(stallTimeout), limit: to - from + 1);
      return;
    }
    final request = await _client.getUrl(source.uri).timeout(connectTimeout);
    source.headers.forEach(request.headers.set);
    request.headers.set(HttpHeaders.rangeHeader, 'bytes=$from-$to');
    final upstream = await request.close().timeout(connectTimeout);
    if (upstream.statusCode != HttpStatus.partialContent) {
      // The server can't resume there: the player gets a short answer and asks again.
      _drain(upstream);
      return;
    }
    await body.addStream(upstream.timeout(stallTimeout));
  }

  /// Answers a [range] of a transcode from the second its start falls in
  /// (Android SubsonicStreamResolver.resolveDataSpec with `timeOffset`): the
  /// server starts the transcode there, and at a constant bit rate its bytes
  /// stand in for the ones from the range's start. False, with nothing sent,
  /// when this one can't: no [StreamCacheSource.timeSeek], a range at the
  /// start, a server that won't start there, or no length to answer with.
  Future<bool> _answerFromTime(HttpRequest request, StreamCacheSource source, _Range range, int? total) async {
    final start = range.start;
    if (start == null || start <= 0) return false;
    final timed = await _openFromTime(source, start);
    if (timed == null) return false;
    final (upstream, seconds) = timed;
    // The song's length: the fetch's, else what is left plus where it starts.
    final length = total ?? (upstream.contentLength >= 0 ? start + upstream.contentLength : null);
    if (length == null) {
      _drain(upstream);
      return false;
    }
    final response = request.response;
    final span = range.resolve(length);
    if (span == null) {
      _drain(upstream);
      await _unsatisfiable(response, length);
      return true;
    }
    final (first, last) = span;
    debugPrint('YoinStreamCache: seek by time ${seconds}s');
    final _Body body;
    try {
      _head(
        response,
        range: true,
        start: first,
        end: last,
        total: length,
        contentType: upstream.headers.value(HttpHeaders.contentTypeHeader),
      );
      body = await _Body.open(response);
    } catch (_) {
      // The player went away first: stop the server's transcode too.
      _drain(upstream);
      rethrow;
    }
    try {
      // Stopping early (the span is sent, the player hung up) cancels the server's answer.
      await body.addStream(upstream.timeout(stallTimeout), limit: last - first + 1);
    } finally {
      await body.close();
    }
    return true;
  }

  /// The server's transcode of [source] from the second byte [position]
  /// falls in, and that second; null when it can't start there (no time
  /// seek, the very start, the server lacks it, refused or unreachable).
  Future<(HttpClientResponse, int)?> _openFromTime(StreamCacheSource source, int position) async {
    final seek = source.timeSeek;
    if (seek == null) return null;
    final seconds = seek.secondsAt(position);
    if (seconds <= 0) return null;
    try {
      final uri = await seek.uriAt(seconds).timeout(connectTimeout);
      if (uri == null) return null;
      final request = await _client.getUrl(uri).timeout(connectTimeout);
      source.headers.forEach(request.headers.set);
      final upstream = await request.close().timeout(connectTimeout);
      final type = upstream.headers.contentType?.mimeType.toLowerCase() ?? '';
      // Not audio (a Subsonic error is a 200 with XML / JSON): ask as before.
      if (upstream.statusCode != HttpStatus.ok || type.contains('xml') || type.contains('json')) {
        _drain(upstream);
        return null;
      }
      return (upstream, seconds);
    } catch (e) {
      debugPrint('YoinStreamCache: no seek by time: ${e.runtimeType}');
      return null;
    }
  }

  static void _drain(HttpClientResponse response) {
    unawaited(response.listen(null, onError: (_) {}, cancelOnError: true).cancel());
  }
}

/// An answer's body on the bare socket, detached from the server once the
/// headers are out: Dart's HttpServer doesn't notice a player hanging up
/// while nothing is written, the socket does at once. Each answer closes its
/// connection (`Connection: close`), so a body of unknown length simply ends.
final class _Body {
  _Body._(this._socket) {
    _socket.listen((_) {}, onDone: _hangUp, onError: (_) => _hangUp(), cancelOnError: true);
  }

  static Future<_Body> open(HttpResponse response) async {
    response.persistentConnection = false;
    response.headers.chunkedTransferEncoding = false;
    return _Body._(await response.detachSocket());
  }

  final Socket _socket;
  final _gone = Completer<void>();

  bool get gone => _gone.isCompleted;

  /// Completes when the player hangs up.
  Future<void> get hungUp => _gone.future;

  void _hangUp() {
    if (!_gone.isCompleted) _gone.complete();
  }

  /// Sends [bytes]; false once the player has hung up.
  Future<bool> add(List<int> bytes) async {
    if (gone) return false;
    try {
      _socket.add(bytes);
      await Future.any([_socket.flush(), hungUp]);
    } catch (_) {
      _hangUp();
    }
    return !gone;
  }

  /// Sends [stream] until it ends, [limit] bytes have gone, or the player
  /// hangs up (cancelling the upstream subscription immediately instead of waiting for stallTimeout).
  Future<void> addStream(Stream<List<int>> stream, {int? limit}) async {
    var left = limit;
    final iter = StreamIterator<List<int>>(stream);
    try {
      while (!gone) {
        final hasNext = await Future.any<bool?>([
          iter.moveNext(),
          hungUp.then<bool?>((_) => null),
        ]);
        if (hasNext != true || gone) break;
        final chunk = iter.current;
        if (left != null && chunk.length >= left) {
          if (left > 0) await add(chunk.sublist(0, left));
          break;
        }
        if (!await add(chunk)) break;
        if (left != null) left -= chunk.length;
      }
    } finally {
      unawaited(iter.cancel().catchError((_) {}));
    }
  }

  Future<void> close() async {
    if (!gone) {
      try {
        await Future.any([_socket.close(), hungUp, Future<void>.delayed(const Duration(seconds: 5))]);
      } catch (_) {}
    }
    _socket.destroy();
  }
}

/// One whole-song fetch into a part file, read by every player request for it.
final class _Fetch {
  _Fetch({
    required this.key,
    required this.source,
    required this.part,
    required this.generation,
    required this.idleTimeout,
    required this.onIdle,
  });

  final String key;
  final StreamCacheSource source;
  final File part;
  final int generation;
  final Duration idleTimeout;
  final void Function(_Fetch) onIdle;

  HttpClientRequest? request;
  int available = 0;
  int? total;
  String? contentType;
  bool complete = false;
  bool cancelled = false;
  Object? error;
  bool get failed => error != null || cancelled;

  final _ready = Completer<void>();
  Completer<void>? _changed;
  int _readers = 0;
  Timer? _idle;

  /// Completes once the server's headers arrived and the part file is open; errors when the server refused.
  Future<void> get ready => _ready.future;

  /// Completes at the next change (bytes, done, failure).
  Future<void> get changed => (_changed ??= Completer<void>()).future;

  void markReady() {
    if (!_ready.isCompleted) _ready.complete();
  }

  void grew(int bytes) {
    available += bytes;
    _ping();
  }

  void finish() {
    complete = true;
    _idle?.cancel();
    _ping();
  }

  void fail(Object e) {
    error ??= e;
    _idle?.cancel();
    if (!_ready.isCompleted) {
      _ready.completeError(e);
      unawaited(_ready.future.catchError((_) {}));
    }
    _ping();
  }

  void cancel() {
    cancelled = true;
    request?.abort();
    fail(const _Cancelled());
  }

  void attach() {
    _readers++;
    _idle?.cancel();
    _idle = null;
  }

  void detach() {
    _readers--;
    if (_readers <= 0 && !complete && !failed) {
      _idle?.cancel();
      _idle = Timer(idleTimeout, () {
        if (_readers <= 0 && !complete && !failed) onIdle(this);
      });
    }
  }

  void _ping() {
    final c = _changed;
    _changed = null;
    if (c != null && !c.isCompleted) c.complete();
  }
}

final class _Cancelled implements Exception {
  const _Cancelled();
}

/// One `Range: bytes=` request (a single range; suffix ranges too).
@immutable
final class _Range {
  const _Range(this.start, this.end, {this.suffix});

  final int? start;
  final int? end;

  /// `bytes=-N`: the last N bytes.
  final int? suffix;

  static _Range? parse(String? header) {
    if (header == null) return null;
    final m = RegExp(r'^\s*bytes\s*=\s*(\d*)\s*-\s*(\d*)\s*$').firstMatch(header);
    if (m == null) return null;
    final a = int.tryParse(m.group(1)!);
    final b = int.tryParse(m.group(2)!);
    if (a == null && b == null) return null;
    if (a == null) return _Range(null, null, suffix: b);
    return _Range(a, b);
  }

  String get header => suffix != null ? 'bytes=-$suffix' : 'bytes=$start-${end ?? ''}';

  /// (first, last) within [total] bytes; null when it can't be satisfied.
  (int, int)? resolve(int total) {
    if (total <= 0) return null;
    final s = suffix;
    if (s != null) return s <= 0 ? null : (max(0, total - s), total - 1);
    final first = start!;
    if (first >= total) return null;
    final last = end == null ? total - 1 : min(end!, total - 1);
    return last < first ? null : (first, last);
  }
}
