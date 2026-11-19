import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/cache/stream_cache.dart';
import 'package:yoin/data/cache/stream_cache_proxy.dart';

/// A Subsonic-like server: the song with its length, ranges honoured; it can
/// hold the rest of the body back until [release] (a slow network).
class _Server {
  _Server(this.body);

  final Uint8List body;
  late HttpServer _server;
  final ranges = <String?>[];
  int fullGets = 0;
  String contentType = 'audio/mpeg';
  bool unknownLength = false;

  /// Bytes sent before a whole-song answer waits for [release]; null sends it all.
  int? holdAfter;
  final _gate = Completer<void>();

  Uri get uri =>
      Uri.parse('http://127.0.0.1:${_server.port}/rest/stream.view?id=s1&u=me&t=tok&s=salt&v=1&c=Yoin&f=json');

  void release() {
    if (!_gate.isCompleted) _gate.complete();
  }

  Future<void> start() async {
    _server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    _server.listen((request) async {
      final range = request.headers.value(HttpHeaders.rangeHeader);
      ranges.add(range);
      final response = request.response;
      response.headers.set(HttpHeaders.contentTypeHeader, contentType);
      if (!unknownLength) {
        response.headers.set(HttpHeaders.acceptRangesHeader, 'bytes');
      }
      try {
        if (range != null && !unknownLength) {
          final m = RegExp(r'bytes=(\d+)-(\d*)').firstMatch(range)!;
          final start = int.parse(m.group(1)!);
          final end = m.group(2)!.isEmpty ? body.length - 1 : int.parse(m.group(2)!);
          response.statusCode = HttpStatus.partialContent;
          response.headers.set(HttpHeaders.contentRangeHeader, 'bytes $start-$end/${body.length}');
          response.contentLength = end - start + 1;
          response.add(body.sublist(start, end + 1));
          await response.close();
          return;
        }
        fullGets++;
        if (!unknownLength) {
          response.contentLength = body.length;
        }
        final hold = holdAfter;
        if (hold == null) {
          response.add(body);
        } else {
          response.add(body.sublist(0, hold));
          await response.flush();
          await _gate.future;
          response.add(body.sublist(hold));
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
  late _Server server;
  final client = HttpClient();

  StreamCacheSource source() => StreamCacheSource(
    uri: server.uri,
    song: const StreamCacheSong(provider: 'subsonic', songId: 'subsonic:s1', title: 'One'),
    suffix: 'mp3',
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
    root = await Directory.systemTemp.createTemp('yoin_stream_proxy');
    cache = StreamCache(directory: () async => root);
    server = _Server(_bytes(200 * 1024));
    await server.start();
    proxy = StreamCacheProxy(cache: cache, idleTimeout: const Duration(milliseconds: 200), lookaheadBytes: 16 * 1024);
    expect(await proxy.start(), isTrue);
  });

  tearDown(() async {
    await proxy.close();
    await server.close();
    await cache.saved;
    if (await root.exists()) await root.delete(recursive: true);
  });

  test('should_giveNoAddress_when_theStreamIsNotHttp', () {
    final address = proxy.addressFor(
      StreamCacheSource(
        uri: Uri.parse('yoin-am:123'),
        song: const StreamCacheSong(provider: 'applemusic', songId: 'applemusic:123'),
      ),
    );
    expect(address, isNull);
  });

  test('should_giveNoAddress_when_thereIsNoCacheFolder', () async {
    final none = StreamCacheProxy(cache: StreamCache(directory: () async => null));
    expect(await none.start(), isFalse);
    expect(none.addressFor(source()), isNull);
    await none.close();
  });

  test('should_giveTheSameLoopbackAddress_when_theSongIsQueuedAgain', () {
    final a = proxy.addressFor(source())!;
    final b = proxy.addressFor(source())!;
    expect(a, b);
    expect(a, startsWith('http://127.0.0.1:'));
    // No credential in the loopback address.
    expect(a.contains('tok'), isFalse);
    expect(a.contains('salt'), isFalse);
  });

  test('should_answerNotFound_when_theTokenIsUnknown', () async {
    final (status, _, _) = await get('${proxy.origin}/a/0123456789abcdef');
    expect(status, HttpStatus.notFound);
  });

  test('should_answerAnError_when_theServerIsUnreachable', () async {
    final address = proxy.addressFor(source())!;
    await server.close();
    final (status, _, body) = await get(address);
    // Never an empty 200 the player would take for the song: the native engine falls back to the server's address.
    expect(status, HttpStatus.badGateway);
    expect(body, isEmpty);
    expect(cache.sizeBytes, 0);
  });

  test('should_storeTheSongAndPlayItFromStorage_when_itPlayedToTheEnd', () async {
    final address = proxy.addressFor(source())!;
    final (status, headers, body) = await get(address);
    expect(status, HttpStatus.ok);
    expect(body, server.body);
    expect(headers['content-length'], '${server.body.length}');
    expect(headers['content-type'], 'audio/mpeg');
    await until(() => cache.sizeBytes == server.body.length);
    expect(cache.entries.single.provider, 'subsonic');
    expect(cache.entries.single.songId, 'subsonic:s1');
    // Again: from storage, the server isn't asked.
    final (again, _, second) = await get(address);
    expect(again, HttpStatus.ok);
    expect(second, server.body);
    expect(server.fullGets, 1);
  });

  test('should_answerARangeFromStorage_when_theSongIsCached', () async {
    final address = proxy.addressFor(source())!;
    await get(address);
    await until(() => cache.sizeBytes > 0);
    final (status, headers, body) = await get(address, range: 'bytes=100-199');
    expect(status, HttpStatus.partialContent);
    expect(headers['content-range'], 'bytes 100-199/${server.body.length}');
    expect(body, server.body.sublist(100, 200));
    final (suffix, _, tail) = await get(address, range: 'bytes=-10');
    expect(suffix, HttpStatus.partialContent);
    expect(tail, server.body.sublist(server.body.length - 10));
    final (outside, _, _) = await get(address, range: 'bytes=${server.body.length}-');
    expect(outside, HttpStatus.requestedRangeNotSatisfiable);
    expect(server.ranges.whereType<String>(), isEmpty, reason: 'every range came from the stored file');
  });

  test('should_readTheGrowingFetch_when_aRangeIsNearWhatArrived', () async {
    server.holdAfter = 64 * 1024;
    final address = proxy.addressFor(source())!;
    final whole = get(address);
    await until(() => server.fullGets == 1);
    // Within what has arrived: no second request to the server.
    final (status, _, body) = await get(address, range: 'bytes=1000-1999');
    expect(status, HttpStatus.partialContent);
    expect(body, server.body.sublist(1000, 2000));
    expect(server.ranges.whereType<String>(), isEmpty);
    server.release();
    final (_, _, all) = await whole;
    expect(all, server.body);
    await until(() => cache.sizeBytes == server.body.length);
  });

  test('should_askTheServerDirectly_when_aSeekIsFarAheadOfTheFetch', () async {
    server.holdAfter = 8 * 1024;
    final address = proxy.addressFor(source())!;
    final whole = get(address);
    await until(() => server.fullGets == 1);
    final (status, headers, body) = await get(address, range: 'bytes=150000-');
    expect(status, HttpStatus.partialContent);
    expect(headers['content-range'], 'bytes 150000-${server.body.length - 1}/${server.body.length}');
    expect(body, server.body.sublist(150000));
    expect(server.ranges.whereType<String>(), ['bytes=150000-']);
    // The fetch carries on and stores the song.
    server.release();
    await whole;
    await until(() => cache.sizeBytes == server.body.length);
  });

  test('should_notStore_when_theServerAnswersWithAnError', () async {
    server.contentType = 'text/xml';
    final address = proxy.addressFor(source())!;
    final (status, headers, _) = await get(address);
    // The player hears the server's own answer.
    expect(status, HttpStatus.ok);
    expect(headers['content-type'], 'text/xml');
    await Future<void>.delayed(const Duration(milliseconds: 100));
    expect(cache.sizeBytes, 0);
  });

  test('should_dropTheFetchAndKeepNothing_when_theSongIsSkipped', () async {
    server.holdAfter = 8 * 1024;
    final address = proxy.addressFor(source())!;
    // A player's own connection, closed as AVPlayer closes it on a skip.
    final uri = Uri.parse(address);
    final socket = await Socket.connect(InternetAddress.loopbackIPv4, uri.port);
    socket.write('GET ${uri.path} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n');
    var received = 0;
    socket.listen((data) => received += data.length, onError: (_) {});
    await until(() => received > 0);
    expect(proxy.runningFetches, 1);
    // The player lets go (a skip): after the idle timeout the fetch stops.
    socket.destroy();
    await until(() => proxy.runningFetches == 0);
    server.release();
    await Future<void>.delayed(const Duration(milliseconds: 100));
    expect(cache.sizeBytes, 0);
    final parts = await Directory(
      '${root.path}/${StreamCache.folderName}',
    ).list().where((e) => e.path.endsWith('.part')).toList();
    expect(parts, isEmpty);
  });

  test('should_playOnButNotStore_when_theCacheIsClearedMidSong', () async {
    server.holdAfter = 8 * 1024;
    final address = proxy.addressFor(source())!;
    final whole = get(address);
    await until(() => server.fullGets == 1);
    await cache.clear();
    server.release();
    final (_, _, body) = await whole;
    expect(body, server.body);
    await Future<void>.delayed(const Duration(milliseconds: 100));
    expect(cache.sizeBytes, 0);
  });

  test('should_reuseRunningFetchWithoutDuplicateTranscode_when_unknownLengthGetsZeroRange', () async {
    server.unknownLength = true;
    server.holdAfter = 16 * 1024;
    final address = proxy.addressFor(source())!;
    final req = get(address, range: 'bytes=0-');
    await until(() => server.fullGets == 1);
    server.release();
    final (status, _, body) = await req;
    expect(status, HttpStatus.ok);
    expect(body, server.body);
    expect(server.fullGets, 1, reason: 'open-ended Range: bytes=0- must reuse the single fetch');
    await until(() => cache.sizeBytes == server.body.length);
  });
}
