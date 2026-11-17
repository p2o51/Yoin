import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/applemusic/apple_music_source.dart';

import 'apple_music_fixtures.dart' as fx;

const _endpoint = 'https://token.example.com/token';

void main() {
  final now = DateTime.utc(2026, 11, 14, 12);
  final nowS = now.millisecondsSinceEpoch ~/ 1000;

  ({AppleMusicDeveloperTokenProvider provider, List<http.Request> requests}) setUp0(List<String> tokens) {
    final requests = <http.Request>[];
    var i = 0;
    final client = MockClient((r) async {
      requests.add(r);
      final token = tokens[i < tokens.length ? i++ : tokens.length - 1];
      return http.Response('{"developerToken":"$token"}', 200);
    });
    return (provider: AppleMusicDeveloperTokenProvider(_endpoint, client: client, now: () => now), requests: requests);
  }

  test('should_reuseCachedToken_when_itHasTimeLeft', () async {
    final t = setUp0([fx.fakeDeveloperToken(exp: nowS + 3600)]);
    final a = await t.provider.token();
    final b = await t.provider.token();
    expect(a, b);
    expect(t.requests, hasLength(1));
    expect(t.requests.single.headers['Accept'], 'application/json');
  });

  test('should_fetchAgain_when_cachedTokenRunsShorterThanAsked', () async {
    final first = fx.fakeDeveloperToken(exp: nowS + 200);
    final second = fx.fakeDeveloperToken(exp: nowS + 3600);
    final t = setUp0([first, second]);
    expect(await t.provider.token(), first);
    expect(await t.provider.token(minValiditySeconds: 300), second);
  });

  test('should_shareOneRequest_when_callersAskTogether', () async {
    final t = setUp0([fx.fakeDeveloperToken(exp: nowS + 3600)]);
    await Future.wait([t.provider.token(), t.provider.token(), t.provider.token()]);
    expect(t.requests, hasLength(1));
  });

  test('should_fetchAgain_when_invalidated', () async {
    final t = setUp0([fx.fakeDeveloperToken(exp: nowS + 3600)]);
    await t.provider.token();
    t.provider.invalidate();
    await t.provider.token();
    expect(t.requests, hasLength(2));
  });

  test('should_refuseToken_when_itIsExpiredOrNotES256', () async {
    for (final bad in [fx.fakeDeveloperToken(exp: nowS + 30), 'not.a.jwt', 'abc']) {
      final t = setUp0([bad]);
      await expectLater(
        t.provider.token(),
        throwsA(isA<AppleMusicApiException>().having((e) => e.failure, 'failure', AppleMusicFailure.developerTokenUnavailable)),
      );
    }
  });

  test('should_notQuoteBody_when_serviceAnswersGarbage', () async {
    final client = MockClient((_) async => http.Response('secret-looking body', 200));
    final provider = AppleMusicDeveloperTokenProvider(_endpoint, client: client, now: () => now);
    await expectLater(
      provider.token(),
      throwsA(predicate<Object>((e) => !e.toString().contains('secret-looking'))),
    );
  });

  test('should_rejectEndpoint_when_notHttpsOrCarriesUserInfo', () {
    expect(() => AppleMusicDeveloperTokenProvider.checkedEndpoint('http://token.example.com/t'), throwsArgumentError);
    expect(() => AppleMusicDeveloperTokenProvider.checkedEndpoint('https://u:p@token.example.com/t'), throwsArgumentError);
    expect(AppleMusicDeveloperTokenProvider.checkedEndpoint('http://127.0.0.1:8787/token').port, 8787);
  });

  test('should_readExpiry_when_tokenIsES256Jwt', () {
    expect(developerTokenExpiry(fx.fakeDeveloperToken(exp: 1800000000)), 1800000000);
    expect(developerTokenExpiry('a.b'), isNull);
  });

  group('credentials', () {
    test('should_roundTripAndHideToken_when_serialized', () {
      const creds = AppleMusicCredentials(endpoint: _endpoint, musicUserToken: 'mut');
      final json = creds.toJson();
      expect(json['type'], 'applemusic');
      expect(AppleMusicCredentials.fromJson(json).musicUserToken, 'mut');
      expect(creds.toString(), isNot(contains('mut')));
    });

    test('should_throwFormatException_when_typeIsNotAppleMusic', () {
      expect(() => AppleMusicCredentials.fromJson({'type': 'subsonic'}), throwsFormatException);
    });
  });
}
