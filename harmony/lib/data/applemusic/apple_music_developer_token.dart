/// The Apple Music developer token (Android data/remote/applemusic/AppleMusicDeveloperTokenProvider.kt).
///
/// Apple signs nothing for us: an HTTPS service the app operator runs holds
/// the MusicKit private key and hands out short-lived ES256 JWTs as
/// `{"developerToken": "..."}`. This client fetches one, checks its shape and
/// `exp`, and caches it until it runs short. It never sees a private key.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;

import '../source/network_failure.dart';
import 'apple_music_api.dart';

/// Fetches and caches developer tokens from one token service.
final class AppleMusicDeveloperTokenProvider {
  AppleMusicDeveloperTokenProvider(
    String endpoint, {
    required http.Client client,
    DateTime Function()? now,
    this.timeout = const Duration(seconds: 20),
  }) : _endpoint = checkedEndpoint(endpoint),
       _client = client,
       _now = now ?? DateTime.now;

  final Uri _endpoint;
  final http.Client _client;
  final DateTime Function() _now;

  /// Android's call timeout for the token service.
  final Duration timeout;

  String? _cached;
  int _expiresAt = 0;
  Future<String>? _inFlight;

  /// [endpoint] as a URI when it is HTTPS (or loopback, for a local service)
  /// and carries no user info; throws [ArgumentError] otherwise.
  static Uri checkedEndpoint(String endpoint) {
    final uri = Uri.tryParse(endpoint.trim());
    if (uri == null || uri.host.isEmpty || uri.userInfo.isNotEmpty) {
      throw ArgumentError.value('<redacted>', 'endpoint', 'Not a usable Apple Music token endpoint');
    }
    final loopback = const {'localhost', '127.0.0.1', '::1'}.contains(uri.host);
    if (!uri.isScheme('https') && !(loopback && uri.isScheme('http'))) {
      throw ArgumentError.value('<redacted>', 'endpoint', 'Apple Music token endpoint must use HTTPS');
    }
    return uri;
  }

  int get _nowSeconds => _now().millisecondsSinceEpoch ~/ 1000;

  /// The cached token while it has more than [minValiditySeconds] left, else a
  /// new one from the service. Concurrent callers share one request. Throws
  /// [AppleMusicApiException] ([AppleMusicFailure.developerTokenUnavailable] or
  /// [AppleMusicFailure.network]).
  Future<String> token({int minValiditySeconds = 60}) {
    final cached = _cached;
    if (cached != null && _expiresAt > _nowSeconds + minValiditySeconds) return Future.value(cached);
    return _inFlight ??= _fetch().whenComplete(() => _inFlight = null);
  }

  /// Drops the cached token (after Apple answered 401 with it).
  void invalidate() {
    _cached = null;
    _expiresAt = 0;
  }

  Future<String> _fetch() async {
    final http.Response response;
    try {
      final request = http.Request('GET', _endpoint)
        ..followRedirects = false
        ..headers['Accept'] = 'application/json';
      response = await http.Response.fromStream(await _client.send(request).timeout(timeout)).timeout(timeout);
    } on TimeoutException {
      throw const AppleMusicApiException(
        AppleMusicFailure.network,
        detail: 'Developer token service timed out',
        networkKind: NetworkFailureKind.timeout,
      );
    } on IOException catch (e) {
      throw AppleMusicApiException(
        AppleMusicFailure.network,
        detail: 'Developer token service unreachable',
        networkKind: networkFailureKindOf(e),
      );
    } on http.ClientException catch (e) {
      throw AppleMusicApiException(
        AppleMusicFailure.network,
        detail: 'Developer token service unreachable',
        networkKind: networkFailureKindOf(e),
      );
    }
    if (response.statusCode != 200) {
      throw AppleMusicApiException(
        AppleMusicFailure.developerTokenUnavailable,
        detail: 'Developer token service unavailable (${response.statusCode})',
      );
    }
    final String? token;
    try {
      final body = jsonDecode(utf8.decode(response.bodyBytes));
      token = body is Map && body['developerToken'] is String ? body['developerToken'] as String : null;
    } on FormatException {
      // Parser messages can quote the body; keep it out.
      throw const AppleMusicApiException(
        AppleMusicFailure.developerTokenUnavailable,
        detail: 'Developer token service returned an invalid response',
      );
    }
    if (token == null || token.isEmpty) {
      throw const AppleMusicApiException(
        AppleMusicFailure.developerTokenUnavailable,
        detail: 'Developer token service returned no token',
      );
    }
    final expiry = developerTokenExpiry(token);
    if (expiry == null) {
      throw const AppleMusicApiException(
        AppleMusicFailure.developerTokenUnavailable,
        detail: 'Developer token service returned an invalid token',
      );
    }
    if (expiry <= _nowSeconds + 60) {
      throw const AppleMusicApiException(
        AppleMusicFailure.developerTokenUnavailable,
        detail: 'Developer token is expired or expires too soon',
      );
    }
    _cached = token;
    _expiresAt = expiry;
    return token;
  }
}

/// The `exp` (epoch seconds) of an ES256 developer JWT, or null for anything
/// else. A shape and freshness check only: Apple verifies the signature.
int? developerTokenExpiry(String token) {
  final parts = token.split('.');
  if (parts.length != 3 || parts.any((p) => p.trim().isEmpty)) return null;
  try {
    final header = jsonDecode(utf8.decode(base64Url.decode(base64Url.normalize(parts[0]))));
    if (header is! Map || header['alg'] != 'ES256') return null;
    final payload = jsonDecode(utf8.decode(base64Url.decode(base64Url.normalize(parts[1]))));
    if (payload is! Map) return null;
    return switch (payload['exp']) {
      final int i => i,
      final num n when n == n.truncate() => n.toInt(),
      _ => null,
    };
  } on FormatException {
    return null;
  }
}
