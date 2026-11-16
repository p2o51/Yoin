/// Cover art's disk cache (Android YoinImageLoader.kt: Coil's DiskCache under
/// `cacheDir`, ImageDiskCacheInterceptor and SuccessOnlyCacheStrategy).
///
/// Every cover the app shows, every palette read from one and every card's
/// cover goes through [CoverDiskCache.load], so a cover seen once shows again
/// offline (downloads, Home's snapshot, the service cards fed from it).
///
/// - Key: [coverCacheKey], a signed Subsonic url without the parameters that
///   sign it, so the next process (a new salt) still finds what this one stored.
/// - Budget: Coil's default, 2 % of the free space when the cache opens, held
///   between 10 and 250 MB ([CoverDiskCache.budgetFor]); the least recently
///   used covers go first.
/// - What is stored: only what can be shown again (a 2xx that carries an
///   image, [CoverDiskCache.isShowable]); a cover that fails to decode is
///   dropped ([CoverDiskCache.remove]) so its retry fetches it afresh.
/// - A stored cover is served as stored, never revalidated, like Android: a
///   cover a server replaces under the same id shows the stored bytes until
///   it is evicted or the app's storage is cleared.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';

/// The key a cover url is stored under (Android ImageDiskCacheInterceptor):
/// [subsonicCacheKey] for a signed Subsonic url, the url itself otherwise.
String coverCacheKey(String url) => subsonicCacheKey(url) ?? url;

/// The cache key of a signed Subsonic REST url (Android SubsonicCacheKey): the
/// url without the parameters that sign it (token and salt) or name the
/// protocol (version, client, format). The key keeps the server, the path, the
/// user and the resource's own parameters (id, size), so two accounts or two
/// sizes never share an entry, and it holds no credential. Null when [url] is
/// not a signed Subsonic REST url.
String? subsonicCacheKey(String url) {
  if (!url.contains('/rest/')) return null;
  final uri = Uri.tryParse(url);
  if (uri == null || !uri.hasQuery || !uri.pathSegments.contains('rest')) return null;
  final params = uri.queryParametersAll;
  if (!params.containsKey('t') || !params.containsKey('s')) return null;
  final cut = url.indexOf('?');
  final base = url.substring(0, cut);
  final kept = [
    for (final part in url.substring(cut + 1).split('&'))
      if (part.isNotEmpty && !_subsonicSignature.contains(Uri.decodeQueryComponent(part.split('=').first))) part,
  ];
  return kept.isEmpty ? base : '$base?${kept.join('&')}';
}

/// What Subsonic adds after the user: the signature, then the protocol.
const _subsonicSignature = {'t', 's', 'v', 'c', 'f'};

/// Why a cover could not be loaded. The retry policy
/// (ui/component/artwork_retry.dart) reads the kind off the type.
sealed class ArtworkLoadException implements Exception {
  const ArtworkLoadException();
}

/// The server answered [statusCode], not a 2xx.
final class ArtworkHttpException extends ArtworkLoadException {
  const ArtworkHttpException(this.statusCode);

  final int statusCode;

  @override
  String toString() => 'ArtworkHttpException($statusCode)';
}

/// Bytes came back but made no image: a body that is not an image (Subsonic's
/// JSON error under HTTP 200, a captive portal's page), or one that failed to
/// decode.
final class ArtworkUndecodableException extends ArtworkLoadException {
  const ArtworkUndecodableException([this.cause]);

  final Object? cause;

  @override
  String toString() => 'ArtworkUndecodableException($cause)';
}

/// One cover response: the status, what the server says the body is, and the body.
@immutable
final class CoverResponse {
  const CoverResponse({required this.statusCode, required this.bytes, this.contentType, this.contentLength});

  final int statusCode;
  final Uint8List bytes;

  /// The raw Content-Type header, or null when the server sent none.
  final String? contentType;

  /// The raw Content-Length header, or null when the server sent none.
  final String? contentLength;
}

/// Fetches one cover url.
typedef CoverFetch = Future<CoverResponse> Function(Uri uri);

/// A stored cover: its file's size and when it was last read or written.
@immutable
final class _Stored {
  const _Stored(this.sizeBytes, this.lastUsed);

  final int sizeBytes;
  final DateTime lastUsed;
}

class CoverDiskCache {
  CoverDiskCache({
    Future<Directory?> Function()? directory,
    Future<int?> Function()? freeBytes,
    CoverFetch? fetch,
    DateTime Function()? now,
    @visibleForTesting int? budgetBytes,
  }) : _directory = directory ?? _noDirectory,
       _freeBytes = freeBytes ?? _unknownFreeBytes,
       _fetch = fetch ?? httpFetch,
       _now = now ?? DateTime.now,
       _fixedBudget = budgetBytes;

  final int? _fixedBudget;
  final Future<Directory?> Function() _directory;
  final Future<int?> Function() _freeBytes;
  final CoverFetch _fetch;
  final DateTime Function() _now;

  /// The folder under the app's cache folder.
  static const folderName = 'cover_cache_v1';
  static const _tempSuffix = '.tmp';

  /// Coil's DiskCache.Builder defaults: 2 % of the free space, 10–250 MB.
  static const double budgetFraction = 0.02;
  static const int minBudgetBytes = 10 * 1024 * 1024;
  static const int maxBudgetBytes = 250 * 1024 * 1024;

  /// The budget for [freeBytes] of free space; Coil keeps the minimum when it
  /// can't read the free space.
  static int budgetFor(int? freeBytes) {
    if (freeBytes == null || freeBytes <= 0) return minBudgetBytes;
    return (freeBytes * budgetFraction).round().clamp(minBudgetBytes, maxBudgetBytes);
  }

  /// OkHttp's defaults, which Android's image client keeps: 10 s to connect,
  /// 10 s between reads.
  static const timeout = Duration(seconds: 10);

  Directory? _dir;
  Future<void>? _starting;
  int _budgetBytes = minBudgetBytes;
  int _tempSeq = 0;
  final _stored = <String, _Stored>{};
  final _inFlight = <String, Future<Uint8List>>{};

  /// Opens the folder, sweeps half-written files and evicts down to the
  /// budget. Safe to call again; [load] calls it.
  Future<void> start() => _starting ??= _start();

  Future<void> _start() async {
    try {
      final base = await _directory();
      if (base == null) return;
      final dir = Directory('${base.path}${Platform.pathSeparator}$folderName');
      await dir.create(recursive: true);
      _budgetBytes = _fixedBudget ?? budgetFor(await _freeBytes());
      await for (final entity in dir.list()) {
        if (entity is! File) continue;
        final name = _nameOf(entity);
        if (name.contains(_tempSuffix)) {
          await _delete(entity);
          continue;
        }
        final stat = await entity.stat();
        _stored[name] = _Stored(stat.size, stat.modified);
      }
      _dir = dir;
      await _evict();
    } catch (e) {
      debugPrint('YoinCoverCache: no folder: $e');
      _dir = null;
    }
  }

  /// This build keeps covers on disk (it has a cache folder); known once started.
  bool get available => _dir != null;

  /// The most the stored covers take together.
  int get budgetBytes => _budgetBytes;

  /// What the stored covers take now.
  int get sizeBytes => _stored.values.fold(0, (sum, e) => sum + e.sizeBytes);

  /// Whether [url]'s cover is stored (no disk read).
  bool contains(String url) => _stored.containsKey(_fileName(coverCacheKey(url)));

  /// [url]'s cover bytes: the stored copy when there is one (marked as just
  /// used), else the network's, stored when it can be shown again. Requests
  /// for one key while it is being fetched share that fetch. Throws an
  /// [ArtworkLoadException], or the transport's error (offline, timeout).
  Future<Uint8List> load(String url) {
    final key = coverCacheKey(url);
    final pending = _inFlight[key];
    if (pending != null) return pending;
    final future = _load(url, key);
    _inFlight[key] = future;
    void done() {
      _inFlight.remove(key);
    }

    future.then<void>((_) => done(), onError: (Object _) => done());
    return future;
  }

  Future<Uint8List> _load(String url, String key) async {
    await start();
    final stored = await _read(key);
    if (stored != null) return stored;
    final response = await _fetch(Uri.parse(url));
    if (response.statusCode < 200 || response.statusCode > 299) throw ArtworkHttpException(response.statusCode);
    final showable = isShowable(
      statusCode: response.statusCode,
      contentType: response.contentType,
      contentLength: response.contentLength,
      bodyLength: response.bytes.length,
    );
    if (!showable) throw ArtworkUndecodableException('not an image: ${response.contentType}');
    await _write(key, response.bytes);
    return response.bytes;
  }

  /// Drops [url]'s stored cover (its bytes made no image).
  Future<void> remove(String url) async {
    final name = _fileName(coverCacheKey(url));
    final dir = _dir;
    if (_stored.remove(name) == null || dir == null) return;
    await _delete(File(_pathIn(dir, name)));
  }

  /// Caches only what can be shown again (SuccessOnlyCacheStrategy): a 2xx
  /// that carries an image. A 2xx can still carry none: Subsonic answers a
  /// failed getCoverArt with HTTP 200 and its JSON or XML error, a captive
  /// portal with its HTML page. A missing or generic type (octet-stream,
  /// text/plain) stays cacheable: servers send images under those too.
  static bool isShowable({
    required int statusCode,
    String? contentType,
    String? contentLength,
    required int bodyLength,
  }) {
    if (statusCode == 204 || statusCode < 200 || statusCode > 299) return false;
    if (bodyLength == 0 || contentLength?.trim() == '0') return false;
    final type = contentType?.split(';').first.trim().toLowerCase();
    if (type == null || type.isEmpty) return true;
    return type.startsWith('image/') || !_notImageMarkers.any(type.contains);
  }

  static const _notImageMarkers = ['json', 'xml', 'html'];

  Future<Uint8List?> _read(String key) async {
    final dir = _dir;
    final name = _fileName(key);
    if (dir == null || !_stored.containsKey(name)) return null;
    final file = File(_pathIn(dir, name));
    try {
      final bytes = await file.readAsBytes();
      final now = _now();
      _stored[name] = _Stored(bytes.length, now);
      // The order survives the process: the next start reads it back off the files.
      unawaited(file.setLastModified(now).catchError((Object _) {}));
      return bytes;
    } catch (_) {
      // The system emptied the cache folder under us: fetch it again.
      _stored.remove(name);
      return null;
    }
  }

  Future<void> _write(String key, Uint8List bytes) async {
    final dir = _dir;
    if (dir == null) return;
    final name = _fileName(key);
    final temp = File(_pathIn(dir, '$name$_tempSuffix${_tempSeq++}'));
    try {
      await temp.writeAsBytes(bytes, flush: true);
      await temp.rename(_pathIn(dir, name));
      _stored[name] = _Stored(bytes.length, _now());
      await _evict();
    } catch (e) {
      debugPrint('YoinCoverCache: not stored: $e');
      await _delete(temp);
    }
  }

  /// The least recently used covers go until the rest fit the budget.
  Future<void> _evict() async {
    final dir = _dir;
    if (dir == null) return;
    var size = sizeBytes;
    if (size <= _budgetBytes) return;
    final oldestFirst = _stored.entries.toList()..sort((a, b) => a.value.lastUsed.compareTo(b.value.lastUsed));
    for (final entry in oldestFirst) {
      if (size <= _budgetBytes) break;
      _stored.remove(entry.key);
      size -= entry.value.sizeBytes;
      await _delete(File(_pathIn(dir, entry.key)));
    }
  }

  static String _fileName(String key) => sha256.convert(utf8.encode(key)).toString();

  static String _pathIn(Directory dir, String name) => '${dir.path}${Platform.pathSeparator}$name';

  static String _nameOf(FileSystemEntity entity) =>
      entity.uri.pathSegments.lastWhere((s) => s.isNotEmpty, orElse: () => '');

  static Future<void> _delete(File file) async {
    try {
      if (await file.exists()) await file.delete();
    } catch (_) {
      // Already gone, or held open: the next start sweeps it.
    }
  }

  static Future<Directory?> _noDirectory() async => null;

  static Future<int?> _unknownFreeBytes() async => null;

  static HttpClient? _client;

  /// The default fetch: its own client (not the Subsonic one), OkHttp's
  /// timeouts, the body read whole.
  static Future<CoverResponse> httpFetch(Uri uri) async {
    final client = _client ??= HttpClient()..connectionTimeout = timeout;
    final request = await client.getUrl(uri).timeout(timeout);
    final response = await request.close().timeout(timeout);
    if (response.statusCode < 200 || response.statusCode > 299) {
      unawaited(response.drain<void>().catchError((Object _) {}));
      return CoverResponse(statusCode: response.statusCode, bytes: Uint8List(0));
    }
    final body = BytesBuilder(copy: false);
    await for (final chunk in response.timeout(timeout)) {
      body.add(chunk);
    }
    return CoverResponse(
      statusCode: response.statusCode,
      bytes: body.takeBytes(),
      contentType: response.headers.value(HttpHeaders.contentTypeHeader),
      contentLength: response.headers.value(HttpHeaders.contentLengthHeader),
    );
  }
}
