/// NeoDB sign-in through the system browser (Android NeoDBOAuthActivity).
///
/// NeoDB registers OAuth apps per instance (Mastodon's `/api/v1/apps`) and
/// matches the redirect URI in full, so every sign-in registers a fresh
/// client for exactly the address it will listen on. Android listens on
/// `yoin://auth/neodb/callback`; HarmonyOS takes cloud sync's road instead
/// (OAuth for native apps, RFC 8252): Dart serves the redirect on a loopback
/// address, the page opens in the system browser through [CloudSyncHost], and
/// the app is held awake while the user is away. No new ArkTS, and Windows
/// gets it for free.
///
///  1. Register a client on the instance for `http://127.0.0.1:<port>/neodb/callback`.
///  2. Open `/oauth/authorize` (scope "read write", a random state).
///  3. Take the code from the redirect (state checked).
///  4. Exchange it for the access token.
library;

import 'dart:async';
import 'dart:io';
import 'dart:math';

import '../../platform/sync/cloud_sync_host.dart';
import 'neodb_api.dart';
import 'neodb_config.dart';

/// What a browser sign-in came to (Android NeoDBOAuthResult).
sealed class NeoDbOAuthResult {
  const NeoDbOAuthResult();
}

final class NeoDbOAuthSuccess extends NeoDbOAuthResult {
  const NeoDbOAuthSuccess({required this.instance, required this.accessToken});
  final String instance;
  final String accessToken;
}

/// [message] is Android's raw English (it is shown as is, as there).
final class NeoDbOAuthFailure extends NeoDbOAuthResult {
  const NeoDbOAuthFailure(this.message);
  final String message;
}

final class NeoDbOAuthCancelled extends NeoDbOAuthResult {
  const NeoDbOAuthCancelled();
}

class NeoDbOAuth {
  NeoDbOAuth({
    required NeoDbApi api,
    required CloudSyncHost host,
    Random? random,
    Future<HttpServer> Function()? bindLoopback,
  }) : _api = api,
       _host = host,
       _random = random ?? Random.secure(),
       _bindLoopback = bindLoopback ?? (() => HttpServer.bind(InternetAddress.loopbackIPv4, 0));

  static const clientName = 'Yoin';
  static const appWebsite = 'https://github.com/p2o51/Yoin';
  static const callbackPath = '/neodb/callback';
  static const scope = 'read write';

  final NeoDbApi _api;
  final CloudSyncHost _host;
  final Random _random;
  final Future<HttpServer> Function() _bindLoopback;

  Completer<Uri?>? _waiting;

  /// A sign-in is waiting for the browser to come back.
  bool get isWaitingForBrowser => _waiting != null;

  /// The user came back to Yoin without finishing: stop waiting (a cancel).
  void cancelWaiting() {
    final waiting = _waiting;
    if (waiting != null && !waiting.isCompleted) waiting.complete(null);
  }

  Future<NeoDbOAuthResult> signIn(String rawInstance) async {
    final instance = normalizeNeoDbInstance(rawInstance, blankAsDefault: false);
    if (instance == null) return const NeoDbOAuthFailure('Invalid NeoDB URL. Include a valid host.');
    // A sign-in still waiting gives way to this one.
    cancelWaiting();
    final HttpServer server;
    try {
      server = await _bindLoopback();
    } on SocketException catch (e) {
      return NeoDbOAuthFailure(e.message.isEmpty ? 'NeoDB sign-in failed' : e.message);
    }
    final redirectUri = 'http://${server.address.address}:${server.port}$callbackPath';
    final state = _randomState();
    final waiting = Completer<Uri?>();
    _waiting = waiting;
    final sub = server.listen((request) => _onRedirect(request, state, waiting));
    Uri? redirect;
    try {
      final String clientId;
      final String clientSecret;
      try {
        final registration = await _api.registerOAuthApp(
          instance: instance,
          redirectUri: redirectUri,
          clientName: clientName,
          website: appWebsite,
        );
        clientId = registration.clientId;
        clientSecret = registration.clientSecret;
      } catch (e) {
        return NeoDbOAuthFailure(_message(e, 'NeoDB sign-in failed'));
      }
      final authorize = Uri.parse('$instance/oauth/authorize').replace(
        queryParameters: {
          'response_type': 'code',
          'client_id': clientId,
          'redirect_uri': redirectUri,
          'scope': scope,
          'state': state,
        },
      );
      await _host.holdWhileAway();
      if (!await _host.openBrowser(authorize)) return const NeoDbOAuthFailure('NeoDB sign-in failed');
      redirect = await waiting.future;
      if (redirect == null) return const NeoDbOAuthCancelled();
      final error = redirect.queryParameters['error'];
      if (error != null) return NeoDbOAuthFailure('NeoDB auth error: $error');
      final code = redirect.queryParameters['code'];
      if (code == null || code.isEmpty) return const NeoDbOAuthFailure('NeoDB redirect missing required parameters');
      try {
        final token = await _api.exchangeOAuthCode(
          instance: instance,
          clientId: clientId,
          clientSecret: clientSecret,
          code: code,
          redirectUri: redirectUri,
        );
        return NeoDbOAuthSuccess(instance: instance, accessToken: token.accessToken);
      } catch (e) {
        return NeoDbOAuthFailure(_message(e, 'NeoDB token exchange failed'));
      }
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
  }

  /// Answers the browser's return. A request with another state (or another
  /// path) is not ours: 404, and the wait goes on (Android: "state nonce mismatch"
  /// ends the sign-in; here a stray local request must not end a real one).
  Future<void> _onRedirect(HttpRequest request, String state, Completer<Uri?> waiting) async {
    final uri = request.uri;
    final params = uri.queryParameters;
    final ours =
        uri.path == callbackPath &&
        params['state'] == state &&
        (params.containsKey('code') || params.containsKey('error'));
    request.response
      ..statusCode = ours ? HttpStatus.ok : HttpStatus.notFound
      ..headers.contentType = ContentType.html;
    if (ours) request.response.write(_returnPage);
    await request.response.close();
    if (ours && !waiting.isCompleted) waiting.complete(uri);
  }

  /// The browser's page once NeoDB sent it back (the one cloud sync shows).
  static const _returnPage =
      '<!doctype html><html><head><meta charset="utf-8">'
      '<meta name="viewport" content="width=device-width,initial-scale=1"><title>Yoin</title></head>'
      '<body style="font-family:sans-serif;display:flex;align-items:center;justify-content:center;'
      'height:90vh;margin:0"><h1 style="font-weight:500">Yoin ✓</h1></body></html>';

  static String _message(Object e, String fallback) {
    final text = e is SocketException ? e.message : (e is TimeoutException ? '' : e.toString());
    return text.trim().isEmpty ? fallback : text;
  }

  String _randomState() {
    const hex = '0123456789abcdef';
    return List.generate(32, (_) => hex[_random.nextInt(16)]).join();
  }
}
