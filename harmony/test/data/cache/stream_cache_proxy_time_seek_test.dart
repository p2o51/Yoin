import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/cache/stream_cache.dart';
import 'package:yoin/data/cache/stream_cache_proxy.dart';
import 'package:yoin/data/model/model.dart';

/// 8 kbps: 1 000 bytes a second, so a byte offset reads as a time at a glance.
const _kbps = 8;
const _bytesPerSecond = 1000;

/// A Navidrome-like transcode: no byte ranges (a Range is ignored and the
/// stream starts over), but `timeOffset=<s>` starts it that many seconds in.
class _TranscodeServer {
  _TranscodeServer(this.body);

  final Uint8List body;
  late HttpServer _server;
  final requests = <(Uri, String?)>[];

  /// Sends no Content-Length (no `estimateContentLength`).
  bool sendLength = true;

  /// Answers a `timeOffset` request with a Subsonic error instead.
  bool refuseOffset = false;

  /// Bytes sent before the first whole-song answer waits for [release].
  int? holdAfter;
  int _held = 0;

  /// The first whole-song answer breaks off after this many bytes.
  int? dropAfter;
  int _dropped = 0;
  final _gate = Completer<void>();

  Uri get uri => Uri.parse(
    'http://127.0.0.1:${_server.port}/rest/stream.view?id=s1&maxBitRate=$_kbps&format=mp3'
    '&estimateContentLength=true&u=me&t=tok&s=salt&v=1&c=Yoin&f=json',
  );

  Uri at(int seconds) => uri.replace(queryParameters: {...uri.queryParameters, 'timeOffset': '$seconds'});

  void release() {
    if (!_gate.isCompleted) _gate.complete();
  }

  Iterable<(Uri, String?)> get timed => requests.where((r) => r.$1.queryParameters.containsKey('timeOffset'));

  Future<void> start() async {
    _server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    _server.listen((request) async {
      requests.add((request.uri, request.headers.value(HttpHeaders.rangeHeader)));
      final response = request.response;
      try {
        final offset = int.tryParse(request.uri.queryParameters['timeOffset'] ?? '');
        if (offset != null && refuseOffset) {
          response.headers.set(HttpHeaders.contentTypeHeader, 'application/json');
          response.write('{"subsonic-response":{"status":"failed","error":{"code":0,"message":"no"}}}');
          await response.close();
          return;
        }
        response.headers.set(HttpHeaders.contentTypeHeader, 'audio/mpeg');
        final from = offset == null ? 0 : offset * _bytesPerSecond;
        final bytes = body.sublist(from);
        if (sendLength) response.contentLength = bytes.length;
        final hold = holdAfter;
        final drop = dropAfter;
        if (offset == null && drop != null && _dropped++ == 0) {
          // The connection breaks mid-song.
          response.persistentConnection = false;
          final socket = await response.detachSocket();
          socket.add(bytes.sublist(0, drop));
          await socket.flush();
          socket.destroy();
          return;
        }
        // Only the first whole-song answer (the fetch) is held back.
        if (offset == null && hold != null && _held++ == 0) {
          response.add(bytes.sublist(0, hold));
          await response.flush();
          await _gate.future;
          response.add(bytes.sublist(hold));
        } else {
          response.add(bytes);
        }
        await response.close();
      } catch (_) {
        // The proxy went away.
      }
    });
  }

  Future<void> close() async {
    release();
    await _server.close(force: true);
  }
}

Uint8List _bytes(int n) => Uint8List.fromList(List<int>.generate(n, (i) => (i * 31 + 7) % 256));

void main() {
  late Directory root;
  late StreamCache cache;
  late StreamCacheProxy proxy;
  late _TranscodeServer server;
  final client = HttpClient();
  late bool serverHasOffset;
  late List<int> asked;

  StreamCacheSource source() => StreamCacheSource(
    uri: server.uri,
    song: const StreamCacheSong(provider: 'subsonic', songId: 'subsonic:s1', title: 'One'),
    suffix: 'mp3',
    timeSeek: StreamTimeSeek(
      kbps: _kbps,
      uriAt: (seconds) async {
        asked.add(seconds);
        return serverHasOffset ? server.at(seconds) : null;
      },
    ),
  );

  Future<(int, Map<String, String>, Uint8List)> get(String address, {String? range}) async {
    final request = await client.getUrl(Uri.parse(address));
    if (range != null) request.headers.set(HttpHeaders.rangeHeader, range);
    final response = await request.close();
    final builder = BytesBuilder();
    await response.forEach(builder.add);
    final headers = <String, String>{};
    response.headers.forEach((name, values) => headers[name] = values.join(','));
    return (response.statusCode, headers, builder.takeBytes());
  }

  Future<void> until(bool Function() condition) async {
    for (var i = 0; i < 400 && !condition(); i++) {
      await Future<void>.delayed(const Duration(milliseconds: 10));
    }
    expect(condition(), isTrue);
  }

  setUp(() async {
    root = await Directory.systemTemp.createTemp('yoin_stream_proxy_seek');
    cache = StreamCache(directory: () async => root);
    server = _TranscodeServer(_bytes(200 * _bytesPerSecond));
    await server.start();
    serverHasOffset = true;
    asked = [];
    proxy = StreamCacheProxy(cache: cache, idleTimeout: const Duration(milliseconds: 200), lookaheadBytes: 16 * 1024);
    expect(await proxy.start(), isTrue);
  });

  tearDown(() async {
    await proxy.close();
    await server.close();
    await cache.saved;
    if (await root.exists()) await root.delete(recursive: true);
  });

  test('should_askTheServerFromThatSecond_when_aSeekIsFarAheadInATranscode', () async {
    server.holdAfter = 8 * 1024;
    final address = proxy.addressFor(source())!;
    final whole = get(address);
    await until(() => server.requests.length == 1);
    final total = server.body.length;
    final (status, headers, body) = await get(address, range: 'bytes=150500-');
    expect(status, HttpStatus.partialContent);
    expect(headers['content-range'], 'bytes 150500-${total - 1}/$total');
    // The stream from second 150 stands in for the bytes from 150 500 on, cut to what was asked.
    expect(body, server.body.sublist(150000, 150000 + (total - 150500)));
    expect(asked, [150]);
    final timed = server.timed.single;
    expect(timed.$1.queryParameters['timeOffset'], '150');
    expect(timed.$2, isNull, reason: 'a transcode takes no Range');
    // The fetch from the start carries on and stores the song.
    server.release();
    await whole;
    await until(() => cache.sizeBytes == total);
  });

  test('should_answerTheRangeAsked_when_itEndsBeforeTheSong', () async {
    server.holdAfter = 8 * 1024;
    final address = proxy.addressFor(source())!;
    final whole = get(address);
    await until(() => server.requests.length == 1);
    final (status, headers, body) = await get(address, range: 'bytes=120000-129999');
    expect(status, HttpStatus.partialContent);
    expect(headers['content-range'], 'bytes 120000-129999/${server.body.length}');
    expect(body, server.body.sublist(120000, 130000));
    server.release();
    await whole;
  });

  test('should_passTheRangeOn_when_theServerLacksTranscodeOffset', () async {
    serverHasOffset = false;
    server.holdAfter = 8 * 1024;
    final address = proxy.addressFor(source())!;
    final whole = get(address);
    await until(() => server.requests.length == 1);
    await get(address, range: 'bytes=150500-');
    expect(server.timed, isEmpty);
    expect(server.requests.last.$2, 'bytes=150500-');
    server.release();
    await whole;
  });

  test('should_passTheRangeOn_when_theServerRefusesTheOffset', () async {
    server.refuseOffset = true;
    server.holdAfter = 8 * 1024;
    final address = proxy.addressFor(source())!;
    final whole = get(address);
    await until(() => server.requests.length == 1);
    final (status, _, _) = await get(address, range: 'bytes=150500-');
    expect(status, HttpStatus.ok, reason: 'the server answers as it does');
    expect(server.requests.last.$2, 'bytes=150500-');
    server.release();
    await whole;
  });

  test('should_readTheFetch_when_theSeekIsNearWhatArrived', () async {
    server.holdAfter = 64 * 1024;
    final address = proxy.addressFor(source())!;
    final whole = get(address);
    await until(() => server.requests.length == 1);
    final (_, _, body) = await get(address, range: 'bytes=1000-1999');
    expect(body, server.body.sublist(1000, 2000));
    expect(asked, isEmpty);
    server.release();
    await whole;
  });

  test('should_passTheRangeOn_when_neitherStreamHasALength', () async {
    server.sendLength = false;
    server.holdAfter = 8 * 1024;
    final address = proxy.addressFor(source())!;
    final whole = get(address);
    await until(() => server.requests.length == 1);
    // The offset stream has no length either: nothing to answer a range with, so it goes on as before.
    final (status, _, _) = await get(address, range: 'bytes=150500-');
    expect(status, HttpStatus.ok);
    expect(server.requests.last.$2, 'bytes=150500-');
    server.release();
    await whole;
  });

  test('should_seekByTime_when_theSongIsNotFetchedAndTheOffsetStreamHasALength', () async {
    // A transcode that arrives with no length of its own (no estimate), while its offset stream says how much is left.
    server.holdAfter = 8 * 1024;
    server.sendLength = false;
    final address = proxy.addressFor(source())!;
    final whole = get(address);
    await until(() => server.requests.length == 1);
    server.sendLength = true;
    final (status, headers, body) = await get(address, range: 'bytes=150000-');
    final left = server.body.length - 150000;
    expect(status, HttpStatus.partialContent);
    expect(headers['content-range'], 'bytes 150000-${150000 + left - 1}/${150000 + left}');
    expect(body, server.body.sublist(150000));
    server.release();
    await whole;
  });

  test('should_goOnFromThatSecond_when_theFetchBreaksOffMidSong', () async {
    server.dropAfter = 8 * 1024;
    final address = proxy.addressFor(source())!;
    final (status, _, body) = await get(address);
    expect(status, HttpStatus.ok);
    final total = server.body.length;
    expect(body, hasLength(total));
    // What arrived, then the stream from second 8 standing in for the bytes from 8 192 on.
    expect(body.sublist(0, 8192), server.body.sublist(0, 8192));
    expect(body.sublist(8192), server.body.sublist(8000, 8000 + total - 8192));
    expect(server.timed.single.$1.queryParameters['timeOffset'], '8');
    expect(server.timed.single.$2, isNull);
  });

  test('should_serveTheStoredSong_when_aSeekComesAfterItWasCached', () async {
    final address = proxy.addressFor(source())!;
    await get(address);
    await until(() => cache.sizeBytes == server.body.length);
    final (_, _, body) = await get(address, range: 'bytes=150500-150999');
    expect(body, server.body.sublist(150500, 151000));
    expect(asked, isEmpty);
  });
}
