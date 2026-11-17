/// Drive v3 REST client scoped to the hidden appDataFolder (scope
/// drive.appdata), over HTTPS from Dart (Android drive/DriveAppDataClient.kt).
///
/// Every request carries a Bearer token from [tokens]; a 401 invalidates that
/// token and retries exactly once with a fresh one. HTTP failures map to
/// [SyncTransportException] subtypes so the manager can pick a user-visible
/// state without parsing Drive errors itself. Nothing here logs: bearer
/// tokens and note text must never reach the log.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io' show HttpDate, SocketException, HandshakeException;
import 'dart:math';
import 'dart:typed_data';

import 'package:http/http.dart' as http;

import '../sync_contracts.dart';

class DriveAppDataClient implements SyncTransport {
  DriveAppDataClient({
    required http.Client http,
    required SyncTokenProvider tokens,
    String apiBase = defaultApiBase,
    String uploadBase = defaultUploadBase,
    String userAgent = 'Yoin (gzip)',
    Random? random,
  }) : _http = http,
       _tokens = tokens,
       _apiBase = apiBase.endsWith('/') ? apiBase : '$apiBase/',
       _uploadBase = uploadBase.endsWith('/') ? uploadBase : '$uploadBase/',
       _userAgent = userAgent,
       _random = random ?? Random.secure();

  static const defaultApiBase = 'https://www.googleapis.com/drive/v3/';
  static const defaultUploadBase = 'https://www.googleapis.com/upload/drive/v3/';

  static const _appDataFolder = 'appDataFolder';
  static const _fileFields = 'id,name,version,modifiedTime,size,appProperties';
  static const _maxGeneratedIds = 1000;
  static const _timeout = Duration(seconds: 60);

  final http.Client _http;
  final SyncTokenProvider _tokens;
  final String _apiBase;
  final String _uploadBase;
  final String _userAgent;
  final Random _random;

  Uri _api(String path, [Map<String, String>? query]) => Uri.parse('$_apiBase$path').replace(queryParameters: query);

  Uri _upload(String path, [Map<String, String>? query]) =>
      Uri.parse('$_uploadBase$path').replace(queryParameters: query);

  @override
  Future<List<RemoteFile>> list() async {
    final files = <RemoteFile>[];
    final seenTokens = <String>{};
    String? pageToken;
    do {
      final url = _api('files', {
        'spaces': _appDataFolder,
        'pageSize': '1000',
        'fields': 'nextPageToken,files($_fileFields)',
        'pageToken': ?pageToken,
      });
      final page = await _send(() => http.Request('GET', url), read: _json);
      for (final f in (page['files'] as List? ?? const [])) {
        if (f is Map<String, Object?>) files.add(_remoteFile(f));
      }
      final next = page['nextPageToken'];
      pageToken = next is String && next.isNotEmpty ? next : null;
      if (pageToken != null && !seenTokens.add(pageToken)) {
        throw const SyncServerError(200, 'Drive repeated a page token');
      }
    } while (pageToken != null);
    return files;
  }

  @override
  Future<Uint8List?> download(String fileId) => _send(
    () => http.Request('GET', _api('files/${Uri.encodeComponent(fileId)}', {'alt': 'media'})),
    accept: const {404},
    read: (r) => r.statusCode == 404 ? null : r.bodyBytes,
  );

  @override
  Future<RemoteFile> create({
    required String fileId,
    required String name,
    required Map<String, String> appProperties,
    required String mimeType,
    required Uint8List bytes,
  }) {
    final url = _upload('files', {'uploadType': 'multipart', 'fields': _fileFields});
    final metadata = jsonEncode({
      'id': fileId,
      'name': name,
      'parents': [_appDataFolder],
      'mimeType': mimeType,
      'appProperties': appProperties,
    });
    final boundary = 'yoin${List.generate(24, (_) => _random.nextInt(16).toRadixString(16)).join()}';
    final body = BytesBuilder(copy: false)
      ..add(utf8.encode('--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n'))
      ..add(utf8.encode('--$boundary\r\nContent-Type: $mimeType\r\n\r\n'))
      ..add(bytes)
      ..add(utf8.encode('\r\n--$boundary--\r\n'));
    final payload = body.takeBytes();
    return _send(
      () => http.Request('POST', url)
        ..headers['Content-Type'] = 'multipart/related; boundary=$boundary'
        ..bodyBytes = payload,
      accept: const {409},
      read: (r) {
        if (r.statusCode == 409) throw SyncAlreadyExists('Drive file $fileId already exists');
        return _remoteFile(_json(r));
      },
    );
  }

  @override
  Future<RemoteFile?> update(String fileId, Uint8List bytes) {
    final url = _upload('files/${Uri.encodeComponent(fileId)}', {'uploadType': 'media', 'fields': _fileFields});
    final gz = bytes.length >= 2 && bytes[0] == 0x1f && bytes[1] == 0x8b;
    return _send(
      () => http.Request('PATCH', url)
        ..headers['Content-Type'] = gz ? 'application/gzip' : 'application/octet-stream'
        ..bodyBytes = bytes,
      accept: const {404},
      read: (r) => r.statusCode == 404 ? null : _remoteFile(_json(r)),
    );
  }

  @override
  Future<bool> delete(String fileId) => _send(
    () => http.Request('DELETE', _api('files/${Uri.encodeComponent(fileId)}')),
    accept: const {404},
    read: (_) => true,
  );

  @override
  Future<List<String>> generateIds(int count) async {
    if (count < 1 || count > _maxGeneratedIds) throw ArgumentError.value(count, 'count');
    final url = _api('files/generateIds', {'space': _appDataFolder, 'count': '$count'});
    final json = await _send(() => http.Request('GET', url), read: _json);
    final ids = [
      for (final id in (json['ids'] as List? ?? const []))
        if (id is String && id.trim().isNotEmpty) id,
    ];
    if (ids.length < count) {
      throw SyncServerError(200, 'Drive returned ${ids.length} of $count requested file ids');
    }
    return ids.take(count).toList();
  }

  @override
  Future<GoogleAccountInfo> about() async {
    final url = _api('about', {'fields': 'user(permissionId,emailAddress,displayName)'});
    final json = await _send(() => http.Request('GET', url), read: _json);
    final user = json['user'];
    final permissionId = user is Map ? user['permissionId'] : null;
    if (permissionId is! String || permissionId.trim().isEmpty) {
      throw const SyncServerError(200, 'Drive returned no permissionId');
    }
    String? text(Object? v) => v is String && v.trim().isNotEmpty ? v : null;
    return GoogleAccountInfo(
      permissionId: permissionId,
      email: text((user as Map)['emailAddress']),
      displayName: text(user['displayName']),
    );
  }

  /// Runs the request with a Bearer token. 2xx and statuses in [accept] go to
  /// [read]; a 401 is retried once with a fresh token; everything else
  /// becomes a [SyncTransportException].
  Future<T> _send<T>(
    http.Request Function() request, {
    Set<int> accept = const {},
    required T Function(http.Response response) read,
  }) async {
    final first = await _freshToken();
    final firstResponse = await _attempt(request(), first);
    if (firstResponse.statusCode != 401) return _finish(firstResponse, accept, read);
    await _tokens.invalidate(first);
    final second = await _freshToken();
    final secondResponse = await _attempt(request(), second);
    if (secondResponse.statusCode != 401) return _finish(secondResponse, accept, read);
    throw const SyncUnauthorized('Drive rejected a refreshed access token');
  }

  T _finish<T>(http.Response response, Set<int> accept, T Function(http.Response) read) {
    final code = response.statusCode;
    if ((code >= 200 && code < 300) || accept.contains(code)) return read(response);
    throw errorFor(response);
  }

  Future<String> _freshToken() async {
    try {
      return await _tokens.token();
    } on SyncTransportException {
      rethrow;
    } on SocketException catch (e) {
      throw SyncNetworkError("Couldn't get a Google access token (${e.osError?.errorCode ?? 0})");
    } on TimeoutException {
      throw const SyncNetworkError("Couldn't get a Google access token");
    }
  }

  Future<http.Response> _attempt(http.Request request, String token) async {
    request.headers['Authorization'] = 'Bearer $token';
    request.headers['User-Agent'] = _userAgent;
    try {
      final streamed = await _http.send(request).timeout(_timeout);
      return await http.Response.fromStream(streamed).timeout(_timeout);
    } on SocketException {
      throw const SyncNetworkError("Couldn't reach Google Drive");
    } on HandshakeException {
      throw const SyncNetworkError("Couldn't reach Google Drive");
    } on http.ClientException {
      throw const SyncNetworkError("Couldn't reach Google Drive");
    } on TimeoutException {
      throw const SyncNetworkError("Couldn't reach Google Drive");
    }
  }

  static Map<String, Object?> _json(http.Response r) {
    try {
      final decoded = jsonDecode(utf8.decode(r.bodyBytes));
      if (decoded is Map<String, Object?>) return decoded;
    } on FormatException {
      // fall through
    }
    throw SyncServerError(r.statusCode, 'Malformed Drive response');
  }

  static RemoteFile _remoteFile(Map<String, Object?> f) {
    final id = f['id'];
    if (id is! String) throw const SyncServerError(200, 'Malformed Drive response: file without id');
    int? asInt(Object? v) => v is int ? v : (v is String ? int.tryParse(v) : null);
    final modified = f['modifiedTime'];
    final props = f['appProperties'];
    return RemoteFile(
      id: id,
      name: f['name'] as String? ?? '',
      // Drive encodes int64 fields (version, size) as quoted strings.
      version: asInt(f['version']) ?? 0,
      modifiedTime: modified is String ? DateTime.tryParse(modified)?.millisecondsSinceEpoch : null,
      size: asInt(f['size']),
      appProperties: props is Map ? {for (final e in props.entries) '${e.key}': '${e.value}'} : const {},
    );
  }

  static const _misconfiguredReasons = {'accessNotConfigured', 'SERVICE_DISABLED'};
  static const _unauthorizedReasons = {'insufficientPermissions', 'ACCESS_TOKEN_SCOPE_INSUFFICIENT'};
  static const _rateLimitReasons = {'userRateLimitExceeded', 'rateLimitExceeded', 'RATE_LIMIT_EXCEEDED'};

  static SyncTransportException errorFor(http.Response response) {
    final code = response.statusCode;
    Map<String, Object?>? error;
    try {
      final decoded = jsonDecode(utf8.decode(response.bodyBytes));
      if (decoded is Map && decoded['error'] is Map) error = (decoded['error'] as Map).cast<String, Object?>();
    } catch (_) {
      error = null;
    }
    final reasons = <String>{
      for (final item in (error?['errors'] as List? ?? const []))
        if (item is Map && item['reason'] is String) item['reason'] as String,
      for (final detail in (error?['details'] as List? ?? const []))
        if (detail is Map && detail['reason'] is String) detail['reason'] as String,
    };
    final rawMessage = error?['message'];
    final message =
        'Drive HTTP $code${reasons.isEmpty ? '' : ' (${reasons.first})'}'
        '${rawMessage is String ? ': ${rawMessage.length > 200 ? rawMessage.substring(0, 200) : rawMessage}' : ''}';
    if (reasons.contains('storageQuotaExceeded')) return SyncStorageFull(message);
    if (reasons.any(_misconfiguredReasons.contains)) return SyncMisconfigured(message);
    if (reasons.any(_unauthorizedReasons.contains)) return SyncUnauthorized(message);
    if (code == 429 || reasons.any(_rateLimitReasons.contains)) {
      return SyncRateLimited(message, retryAfterMs: _retryAfterMs(response.headers['retry-after']));
    }
    return SyncServerError(code, message);
  }

  /// Retry-After as delta-seconds or an HTTP date.
  static int? _retryAfterMs(String? header) {
    final value = header?.trim();
    if (value == null || value.isEmpty) return null;
    final seconds = int.tryParse(value);
    if (seconds != null) return max(0, seconds * 1000);
    try {
      return max(0, HttpDate.parse(value).millisecondsSinceEpoch - DateTime.now().millisecondsSinceEpoch);
    } catch (_) {
      return null;
    }
  }
}
