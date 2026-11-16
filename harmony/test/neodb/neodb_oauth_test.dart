import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/neodb/neodb_api.dart';
import 'package:yoin/data/neodb/neodb_oauth.dart';
import 'package:yoin/platform/sync/cloud_sync_host.dart';

/// A browser that goes straight to NeoDB's answer: [answer] builds the
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

http.Response _json(Object body, [int status = 200]) => http.Response(jsonEncode(body), status);

void main() {
  late List<http.Request> requests;
  late MockClient client;

  setUp(() {
    requests = [];
    client = MockClient((request) async {
      requests.add(request);
      if (request.url.path == '/api/v1/apps') return _json({'client_id': 'cid', 'client_secret': 'csec'});
      if (request.url.path == '/oauth/token') return _json({'access_token': 'at1', 'token_type': 'Bearer'});
      return http.Response('', 404);
    });
  });

  test('should_registerClientForLoopbackAndExchangeCode_when_neoDbSendsBrowserBack', () async {
    final browser = _FakeBrowser((url) => {'code': 'c0de', 'state': url.queryParameters['state']!});
    final oauth = NeoDbOAuth(api: NeoDbApi(client), host: browser);

    final result = await oauth.signIn('eggplant.place/');

    expect(result, isA<NeoDbOAuthSuccess>());
    final success = result as NeoDbOAuthSuccess;
    expect(success.instance, 'https://eggplant.place');
    expect(success.accessToken, 'at1');
    final register = Uri.splitQueryString(requests.first.body);
    expect(requests.first.url.toString(), 'https://eggplant.place/api/v1/apps');
    expect(register['client_name'], 'Yoin');
    expect(register['redirect_uris'], startsWith('http://127.0.0.1:'));
    expect(register['redirect_uris'], endsWith('/neodb/callback'));
    final authorize = browser.opened!;
    expect(authorize.path, '/oauth/authorize');
    expect(authorize.queryParameters['client_id'], 'cid');
    expect(authorize.queryParameters['scope'], 'read write');
    expect(authorize.queryParameters['redirect_uri'], register['redirect_uris']);
    final token = Uri.splitQueryString(requests.last.body);
    expect(token['grant_type'], 'authorization_code');
    expect(token['code'], 'c0de');
    expect(token['client_secret'], 'csec');
    expect(token['redirect_uri'], register['redirect_uris']);
    expect(browser.holds, 1);
    expect(browser.releases, 1);
    expect(oauth.isWaitingForBrowser, isFalse);
  });

  test('should_fail_when_neoDbAnswersWithError', () async {
    final browser = _FakeBrowser((url) => {'error': 'access_denied', 'state': url.queryParameters['state']!});
    final result = await NeoDbOAuth(api: NeoDbApi(client), host: browser).signIn('neodb.social');
    expect(result, isA<NeoDbOAuthFailure>());
    expect((result as NeoDbOAuthFailure).message, 'NeoDB auth error: access_denied');
  });

  test('should_cancel_when_userComesBackWithoutFinishing', () async {
    final browser = _FakeBrowser((_) => null);
    final oauth = NeoDbOAuth(api: NeoDbApi(client), host: browser);
    final pending = oauth.signIn('neodb.social');
    await Future<void>.delayed(const Duration(milliseconds: 50));
    expect(oauth.isWaitingForBrowser, isTrue);
    oauth.cancelWaiting();
    expect(await pending, isA<NeoDbOAuthCancelled>());
    expect(browser.releases, 1);
  });

  test('should_ignoreRedirect_when_stateDoesNotMatch', () async {
    final browser = _FakeBrowser((_) => {'code': 'c', 'state': 'forged'});
    final oauth = NeoDbOAuth(api: NeoDbApi(client), host: browser);
    final pending = oauth.signIn('neodb.social');
    await Future<void>.delayed(const Duration(milliseconds: 200));
    // Still waiting: a forged state is not NeoDB's answer.
    expect(oauth.isWaitingForBrowser, isTrue);
    oauth.cancelWaiting();
    expect(await pending, isA<NeoDbOAuthCancelled>());
    expect(requests.where((r) => r.url.path == '/oauth/token'), isEmpty);
  });

  test('should_failWithoutOpeningBrowser_when_instanceInvalid', () async {
    final browser = _FakeBrowser((_) => null);
    final result = await NeoDbOAuth(api: NeoDbApi(client), host: browser).signIn('  ');
    expect(result, isA<NeoDbOAuthFailure>());
    expect(browser.opened, isNull);
  });

  test('should_failWithServerWords_when_registrationRejected', () async {
    final rejecting = MockClient((_) async => _json({'error': 'Validation failed: redirect_uris is required'}, 422));
    final browser = _FakeBrowser((_) => null);
    final result = await NeoDbOAuth(api: NeoDbApi(rejecting), host: browser).signIn('neodb.social');
    expect((result as NeoDbOAuthFailure).message, 'Validation failed: redirect_uris is required');
    expect(browser.opened, isNull);
  });
}
