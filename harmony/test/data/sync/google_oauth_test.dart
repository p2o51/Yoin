import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/sync/auth/google_oauth.dart';
import 'package:yoin/data/sync/sync_contracts.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/platform/sync/cloud_sync_host.dart';

/// A browser that goes straight to Google's answer: [answer] builds the
/// redirect query from the authorize URL (null = the user never comes back).
class _FakeBrowser implements CloudSyncHost {
  _FakeBrowser(this.answer);

  final Map<String, String>? Function(Uri authorize) answer;
  Uri? opened;
  int holds = 0;
  int releases = 0;

  @override
  Future<bool> openBrowser(Uri url) async {
    opened = url;
    final query = answer(url);
    if (query != null) {
      final redirect = Uri.parse(url.queryParameters['redirect_uri']!).replace(queryParameters: query);
      // Like the browser: a request to the loopback address, after a moment.
      Future<void>(() async {
        final client = HttpClient();
        final request = await client.getUrl(redirect);
        final response = await request.close();
        await response.drain<void>();
        client.close();
      });
    }
    return true;
  }

  @override
  Future<void> holdWhileAway() async => holds++;

  @override
  Future<void> releaseHold() async => releases++;

  @override
  Future<String> deviceName() async => 'Test';

  @override
  Future<Directory?> filesDirectory() async => null;
}

void main() {
  const config = GoogleOAuthConfig(clientId: 'client.apps.googleusercontent.com');

  test('should_exchangeCodeWithPkce_when_googleSendsBrowserBack', () async {
    late Map<String, String> tokenRequest;
    final http = MockClient((request) async {
      tokenRequest = Uri.splitQueryString(request.body);
      return _json({'access_token': 'at1', 'expires_in': 3600, 'refresh_token': 'rt1'});
    });
    final secrets = InMemoryStore();
    final browser = _FakeBrowser((url) => {'code': 'c0de', 'state': url.queryParameters['state']!});
    final oauth = GoogleOAuth(config: config, host: browser, http: http, secrets: secrets);

    final result = await oauth.authorizeInteractively(loginHint: 'me@example.com');

    expect(result, isA<GoogleAuthToken>());
    expect((result as GoogleAuthToken).accessToken, 'at1');
    final authorize = browser.opened!;
    expect(authorize.queryParameters['scope'], GoogleOAuth.scopeDriveAppData);
    expect(authorize.queryParameters['code_challenge_method'], 'S256');
    expect(authorize.queryParameters['login_hint'], 'me@example.com');
    expect(authorize.queryParameters['redirect_uri'], startsWith('http://127.0.0.1:'));
    // The verifier sent with the code matches the challenge sent to the browser.
    final verifier = tokenRequest['code_verifier']!;
    final challenge = base64Url.encode(sha256.convert(ascii.encode(verifier)).bytes).replaceAll('=', '');
    expect(authorize.queryParameters['code_challenge'], challenge);
    expect(tokenRequest['grant_type'], 'authorization_code');
    expect(tokenRequest['code'], 'c0de');
    expect(tokenRequest.containsKey('client_secret'), isFalse);
    expect(await secrets.read(GoogleOAuth.refreshTokenKey), 'rt1');
    expect(browser.holds, 1);
    expect(browser.releases, 1);
    expect(await oauth.token(), 'at1');
  });

  test('should_ignoreRedirect_when_stateDoesNotMatch', () async {
    final browser = _FakeBrowser((_) => {'code': 'c', 'state': 'forged'});
    final oauth = GoogleOAuth(
      config: config,
      host: browser,
      http: MockClient((_) async => fail('no exchange')),
      secrets: InMemoryStore(),
    );
    final pending = oauth.authorizeInteractively();
    await Future<void>.delayed(const Duration(milliseconds: 200));
    expect(oauth.isWaitingForBrowser, isTrue);
    oauth.cancelWaiting();
    expect(await pending, isA<GoogleAuthCanceled>());
  });

  test('should_cancel_when_userDeniesAccess', () async {
    final browser = _FakeBrowser((url) => {'error': 'access_denied', 'state': url.queryParameters['state']!});
    final oauth = GoogleOAuth(
      config: config,
      host: browser,
      http: MockClient((_) async => fail('no exchange')),
      secrets: InMemoryStore(),
    );
    expect(await oauth.authorizeInteractively(), isA<GoogleAuthCanceled>());
  });

  test('should_reportMisconfigured_when_noClientIdBuiltIn', () async {
    final oauth = GoogleOAuth(
      config: const GoogleOAuthConfig(clientId: ''),
      host: _FakeBrowser((_) => null),
      http: MockClient((_) async => fail('no request')),
      secrets: InMemoryStore(),
    );
    expect(await oauth.authorizeInteractively(), isA<GoogleAuthMisconfigured>());
  });

  test('should_refreshWithStoredToken_when_accessTokenExpired', () async {
    var now = 0;
    final secrets = InMemoryStore();
    await secrets.write(GoogleOAuth.refreshTokenKey, 'rt');
    var calls = 0;
    final http = MockClient((request) async {
      calls++;
      expect(Uri.splitQueryString(request.body)['grant_type'], 'refresh_token');
      return _json({'access_token': 'at$calls', 'expires_in': 3600});
    });
    final oauth = GoogleOAuth(
      config: config,
      host: _FakeBrowser((_) => null),
      http: http,
      secrets: secrets,
      clock: () => now,
    );
    expect(await oauth.token(), 'at1');
    expect(await oauth.token(), 'at1');
    now = 3600 * 1000;
    expect(await oauth.token(), 'at2');
    await oauth.invalidate('at2');
    expect(await oauth.token(), 'at3');
  });

  test('should_dropRefreshTokenAndAskAgain_when_grantRevoked', () async {
    final secrets = InMemoryStore();
    await secrets.write(GoogleOAuth.refreshTokenKey, 'rt');
    final http = MockClient((_) async => _json({'error': 'invalid_grant'}, 400));
    final oauth = GoogleOAuth(config: config, host: _FakeBrowser((_) => null), http: http, secrets: secrets);
    await expectLater(oauth.token(), throwsA(isA<SyncUnauthorized>()));
    expect(await secrets.read(GoogleOAuth.refreshTokenKey), isNull);
  });

  test('should_failUnauthorized_when_neverSignedIn', () async {
    final oauth = GoogleOAuth(
      config: config,
      host: _FakeBrowser((_) => null),
      http: MockClient((_) async => fail('no request')),
      secrets: InMemoryStore(),
    );
    await expectLater(oauth.token(), throwsA(isA<SyncUnauthorized>()));
  });
}

http.Response _json(Map<String, Object?> body, [int status = 200]) =>
    http.Response(jsonEncode(body), status, headers: {'content-type': 'application/json'});
