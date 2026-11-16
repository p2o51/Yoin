/// Google sign-in for Drive without Google Play services: OAuth 2.0 for
/// native apps (RFC 8252) with PKCE (RFC 7636). Google's page opens in the
/// system browser ([CloudSyncHost.openBrowser]); Google sends the browser back
/// to a loopback address this app listens on (`http://127.0.0.1:port`), the
/// code is exchanged for tokens over HTTPS, and only the refresh token is
/// kept, in the encrypted [SecretStore]. Scope: drive.appdata only, as on
/// Android.
///
/// The OAuth client is read from build config (never committed):
/// `--dart-define=YOIN_GOOGLE_CLIENT_ID=…` and, for a "Desktop app" client,
/// `--dart-define=YOIN_GOOGLE_CLIENT_SECRET=…` (Google issues desktop clients
/// a secret it documents as not confidential). harmony/docs/cloud-sync.md
/// says what to register. Without a client id, sync shows "not available in
/// this build".
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import '../../../platform/storage/stores.dart';
import '../../../platform/sync/cloud_sync_host.dart';
import '../sync_contracts.dart';

/// The OAuth client this build signs in with.
@immutable
final class GoogleOAuthConfig {
  const GoogleOAuthConfig({required this.clientId, this.clientSecret});

  /// From `--dart-define`.
  factory GoogleOAuthConfig.fromEnvironment() => const GoogleOAuthConfig(
    clientId: String.fromEnvironment('YOIN_GOOGLE_CLIENT_ID'),
    clientSecret: bool.hasEnvironment('YOIN_GOOGLE_CLIENT_SECRET')
        ? String.fromEnvironment('YOIN_GOOGLE_CLIENT_SECRET')
        : null,
  );

  final String clientId;
  final String? clientSecret;

  bool get isConfigured => clientId.trim().isNotEmpty;
}

/// What an interactive sign-in came to.
sealed class GoogleAuthResult {
  const GoogleAuthResult();
}

final class GoogleAuthToken extends GoogleAuthResult {
  const GoogleAuthToken(this.accessToken);
  final String accessToken;
}

/// The user backed out (closed Google's page, came back without finishing, denied).
final class GoogleAuthCanceled extends GoogleAuthResult {
  const GoogleAuthCanceled();
}

/// No OAuth client in this build, or Google doesn't know it.
final class GoogleAuthMisconfigured extends GoogleAuthResult {
  const GoogleAuthMisconfigured();
}

final class GoogleAuthFailure extends GoogleAuthResult {
  const GoogleAuthFailure(this.message);
  final String message;
}

/// What the sync manager needs from Google sign-in (Android
/// GoogleSyncAuthorizer + its token provider). [GoogleOAuth] in the app; fakes in tests.
abstract interface class GoogleSyncAuthorizer implements SyncTokenProvider {
  /// A Google OAuth client is built in.
  bool get isConfigured;

  /// Signs in through Google's page. [loginHint] preselects the account (reconnect).
  Future<GoogleAuthResult> authorizeInteractively({String? loginHint});

  /// The user came back without finishing on Google's page: stop waiting.
  void cancelWaiting();

  /// Whether a sign-in is waiting for Google to send the browser back.
  bool get isWaitingForBrowser;

  /// Drops the cached access token.
  void invalidateCached();

  /// Forgets the sign-in on this device.
  Future<void> forget();
}

/// OAuth tokens for Drive through the system browser and a loopback redirect.
class GoogleOAuth implements GoogleSyncAuthorizer {
  GoogleOAuth({
    required GoogleOAuthConfig config,
    required CloudSyncHost host,
    required http.Client http,
    required SecretStore secrets,
    int Function()? clock,
    Random? random,
    Future<HttpServer> Function()? bindLoopback,
    this.authorizeEndpoint = 'https://accounts.google.com/o/oauth2/v2/auth',
    this.tokenEndpoint = 'https://oauth2.googleapis.com/token',
  }) : _config = config,
       _host = host,
       _http = http,
       _secrets = secrets,
       _clock = clock ?? (() => DateTime.now().millisecondsSinceEpoch),
       _random = random ?? Random.secure(),
       _bindLoopback = bindLoopback ?? (() => HttpServer.bind(InternetAddress.loopbackIPv4, 0));

  static const scopeDriveAppData = 'https://www.googleapis.com/auth/drive.appdata';
  static const refreshTokenKey = 'cloud_sync.google.refresh_token';

  /// A token this close to expiry is refreshed before use.
  static const _expirySlackMs = 60 * 1000;
  static const _timeout = Duration(seconds: 30);

  final GoogleOAuthConfig _config;
  final CloudSyncHost _host;
  final http.Client _http;
  final SecretStore _secrets;
  final int Function() _clock;
  final Random _random;
  final Future<HttpServer> Function() _bindLoopback;
  final String authorizeEndpoint;
  final String tokenEndpoint;

  String? _accessToken;
  int _expiresAt = 0;
  Future<String>? _refreshing;
  Completer<Uri?>? _waiting;

  @override
  bool get isConfigured => _config.isConfigured;

  @override
  bool get isWaitingForBrowser => _waiting != null;

  @override
  Future<GoogleAuthResult> authorizeInteractively({String? loginHint}) async {
    if (!_config.isConfigured) return const GoogleAuthMisconfigured();
    _waiting?.complete(null);
    final verifier = _randomUrlSafe(64);
    final challenge = base64Url.encode(sha256.convert(ascii.encode(verifier)).bytes).replaceAll('=', '');
    final state = _randomUrlSafe(24);
    final HttpServer server;
    try {
      server = await _bindLoopback();
    } on SocketException {
      return const GoogleAuthFailure("Couldn't reach Google. Try again in a moment.");
    }
    final redirectUri = 'http://${server.address.address}:${server.port}';
    final url = Uri.parse(authorizeEndpoint).replace(
      queryParameters: {
        'client_id': _config.clientId,
        'redirect_uri': redirectUri,
        'response_type': 'code',
        'scope': scopeDriveAppData,
        'code_challenge': challenge,
        'code_challenge_method': 'S256',
        'state': state,
        'access_type': 'offline',
        // A fresh refresh token every time (the device may have dropped its old one).
        'prompt': 'consent',
        'login_hint': ?loginHint,
      },
    );
    final waiting = Completer<Uri?>();
    _waiting = waiting;
    final sub = server.listen((request) => _onRedirect(request, state, waiting));
    Uri? redirect;
    try {
      await _host.holdWhileAway();
      if (!await _host.openBrowser(url)) {
        return const GoogleAuthFailure("Couldn't reach Google. Try again in a moment.");
      }
      redirect = await waiting.future;
    } finally {
      if (identical(_waiting, waiting)) _waiting = null;
      await sub.cancel();
      await server.close(force: true);
      try {
        await _host.releaseHold();
      } catch (_) {
        // Nothing held.
      }
    }
    if (redirect == null) return const GoogleAuthCanceled();
    final error = redirect.queryParameters['error'];
    if (error != null) {
      return error == 'access_denied' ? const GoogleAuthCanceled() : GoogleAuthFailure('Google: $error');
    }
    final code = redirect.queryParameters['code'];
    if (code == null || code.isEmpty) return const GoogleAuthCanceled();
    return _exchange(code: code, verifier: verifier, redirectUri: redirectUri);
  }

  @override
  void cancelWaiting() {
    final waiting = _waiting;
    if (waiting != null && !waiting.isCompleted) waiting.complete(null);
  }

  Future<void> _onRedirect(HttpRequest request, String state, Completer<Uri?> waiting) async {
    final uri = request.uri;
    final params = uri.queryParameters;
    final ours = params['state'] == state && (params.containsKey('code') || params.containsKey('error'));
    request.response
      ..statusCode = ours ? HttpStatus.ok : HttpStatus.notFound
      ..headers.contentType = ContentType.html;
    if (ours) request.response.write(_returnPage);
    await request.response.close();
    if (ours && !waiting.isCompleted) waiting.complete(uri);
  }

  /// The page the browser shows once Google sent it back: nothing to do but
  /// switch back to Yoin (no Android counterpart; the system sheet closes itself there).
  static const _returnPage =
      '<!doctype html><html><head><meta charset="utf-8">'
      '<meta name="viewport" content="width=device-width,initial-scale=1"><title>Yoin</title></head>'
      '<body style="font-family:sans-serif;display:flex;align-items:center;justify-content:center;'
      'height:90vh;margin:0"><h1 style="font-weight:500">Yoin ✓</h1></body></html>';

  Future<GoogleAuthResult> _exchange({
    required String code,
    required String verifier,
    required String redirectUri,
  }) async {
    try {
      final json = await _postToken({
        'grant_type': 'authorization_code',
        'code': code,
        'code_verifier': verifier,
        'redirect_uri': redirectUri,
      });
      final access = json['access_token'];
      final refresh = json['refresh_token'];
      if (access is! String || access.isEmpty) {
        return const GoogleAuthFailure("Couldn't reach Google. Try again in a moment.");
      }
      _remember(access, json['expires_in']);
      if (refresh is String && refresh.isNotEmpty) await _secrets.write(refreshTokenKey, refresh);
      return GoogleAuthToken(access);
    } on SyncMisconfigured {
      return const GoogleAuthMisconfigured();
    } on SyncTransportException {
      return const GoogleAuthFailure("Couldn't reach Google. Try again in a moment.");
    }
  }

  void _remember(String access, Object? expiresIn) {
    _accessToken = access;
    final seconds = expiresIn is num ? expiresIn.toInt() : 3600;
    _expiresAt = _clock() + seconds * 1000;
  }

  /// A valid access token, refreshed with the stored refresh token when needed.
  @override
  Future<String> token() {
    final cached = _accessToken;
    if (cached != null && _clock() < _expiresAt - _expirySlackMs) return Future.value(cached);
    return _refreshing ??= _refresh().whenComplete(() => _refreshing = null);
  }

  Future<String> _refresh() async {
    final refresh = await _secrets.read(refreshTokenKey);
    if (refresh == null || refresh.isEmpty) throw const SyncUnauthorized('Not signed in to Google');
    final json = await _postToken({'grant_type': 'refresh_token', 'refresh_token': refresh});
    final access = json['access_token'];
    if (access is! String || access.isEmpty) throw const SyncServerError(200, 'Google returned no access token');
    _remember(access, json['expires_in']);
    final rotated = json['refresh_token'];
    if (rotated is String && rotated.isNotEmpty && rotated != refresh) await _secrets.write(refreshTokenKey, rotated);
    return access;
  }

  @override
  Future<void> invalidate(String token) async {
    if (_accessToken == token) {
      _accessToken = null;
      _expiresAt = 0;
    }
  }

  /// Forgets the access token. The grant stays with Google.
  @override
  void invalidateCached() {
    _accessToken = null;
    _expiresAt = 0;
  }

  /// Forgets the sign-in on this device: the refresh token goes. The grant
  /// stays with Google (other devices read the reset marker through theirs).
  @override
  Future<void> forget() async {
    invalidateCached();
    await _secrets.write(refreshTokenKey, null);
  }

  Future<Map<String, Object?>> _postToken(Map<String, String> fields) async {
    final body = {
      ...fields,
      'client_id': _config.clientId,
      if (_config.clientSecret != null && _config.clientSecret!.isNotEmpty) 'client_secret': _config.clientSecret!,
    };
    final http.Response response;
    try {
      response = await _http.post(Uri.parse(tokenEndpoint), body: body).timeout(_timeout);
    } on SocketException {
      throw const SyncNetworkError("Couldn't reach Google");
    } on HandshakeException {
      throw const SyncNetworkError("Couldn't reach Google");
    } on http.ClientException {
      throw const SyncNetworkError("Couldn't reach Google");
    } on TimeoutException {
      throw const SyncNetworkError("Couldn't reach Google");
    }
    Map<String, Object?> json;
    try {
      final decoded = jsonDecode(utf8.decode(response.bodyBytes));
      json = decoded is Map<String, Object?> ? decoded : const {};
    } on FormatException {
      json = const {};
    }
    if (response.statusCode == 200) return json;
    final error = json['error'];
    if (error == 'invalid_grant') {
      // Revoked, expired (a "Testing" consent screen lasts 7 days) or replaced: sign in again.
      await _secrets.write(refreshTokenKey, null);
      throw const SyncUnauthorized('Google sign-in expired');
    }
    if (error == 'invalid_client' || error == 'unauthorized_client') {
      throw SyncMisconfigured('Google OAuth: $error');
    }
    if (response.statusCode == 429) throw const SyncRateLimited('Google token endpoint rate limited');
    if (response.statusCode >= 500) throw SyncNetworkError('Google token endpoint ${response.statusCode}');
    throw SyncServerError(response.statusCode, 'Google token endpoint: ${error ?? response.statusCode}');
  }

  static const _alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~';

  String _randomUrlSafe(int length) =>
      String.fromCharCodes(List.generate(length, (_) => _alphabet.codeUnitAt(_random.nextInt(_alphabet.length))));
}
