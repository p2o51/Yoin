import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/applemusic/apple_music_api.dart';
import 'package:yoin/data/applemusic/apple_music_source.dart' show sourceExceptionFor;
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_reconnect.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/data/subsonic/subsonic_api.dart';
import 'package:yoin/data/subsonic/subsonic_music_source.dart';

const _creds = SubsonicCredentials(baseUrl: 'https://music.test', username: 'u', password: 'p');

void main() {
  group('credentialsNeedReconnect', () {
    test('should_needReconnect_when_credentialsCouldNotBeRead', () {
      expect(credentialsNeedReconnect(null), isTrue);
    });

    test('should_notNeedReconnect_when_credentialsRead', () {
      expect(credentialsNeedReconnect(_creds), isFalse);
      expect(
        credentialsNeedReconnect(const AppleMusicCredentials(endpoint: 'https://t.test', musicUserToken: 'm')),
        isFalse,
      );
    });
  });

  group('refusesCredentials', () {
    test('should_refuse_when_subsonicSaysAuthError40To44', () {
      expect(refusesCredentials(const SourceAuthException('Wrong username or password', code: 40)), isTrue);
      expect(refusesCredentials(const SourceAuthException('Token auth', code: 41)), isTrue);
      for (final code in [42, 43, 44]) {
        expect(refusesCredentials(SourceServerException('auth mechanism', code: code)), isTrue, reason: '$code');
      }
    });

    test('should_notRefuse_when_subsonicSaysAnotherError', () {
      // 50: not authorized for this operation (Android leaves it out of 40–44).
      expect(refusesCredentials(const SourceAuthException('Not authorized', code: 50)), isFalse);
      expect(refusesCredentials(const SourceServerException('Not found', code: 70)), isFalse);
      expect(refusesCredentials(const SourceServerException('HTTP 502')), isFalse);
    });

    test('should_refuse_when_subsonicServerAnswers401Or403', () {
      expect(refusesCredentials(const SourceServerException('HTTP 401', cause: SubsonicHttpStatus(401))), isTrue);
      expect(refusesCredentials(const SourceServerException('HTTP 403', cause: SubsonicHttpStatus(403))), isTrue);
      expect(refusesCredentials(const SourceServerException('HTTP 503', cause: SubsonicHttpStatus(503))), isFalse);
    });

    test('should_notRefuse_when_serverDidNotAnswer', () {
      expect(refusesCredentials(const SourceNetworkException('offline', kind: NetworkFailureKind.connect)), isFalse);
      expect(refusesCredentials(TimeoutException('ping')), isFalse);
      expect(refusesCredentials(StateError('anything else')), isFalse);
    });

    test('should_refuse_when_appleMusicHasNoUserTokenOrDeniesStorefront', () {
      const noToken = AppleMusicApiException(AppleMusicFailure.userAuthorizationRequired);
      const denied = AppleMusicApiException(AppleMusicFailure.accessDenied, status: 403);
      expect(refusesCredentials(noToken), isTrue);
      expect(refusesCredentials(denied), isTrue);
      // As the source throws them.
      expect(refusesCredentials(sourceExceptionFor(noToken)), isTrue);
      expect(refusesCredentials(sourceExceptionFor(denied)), isTrue);
    });

    test('should_notRefuse_when_appleMusicFailsOtherwise', () {
      for (final failure in [
        AppleMusicFailure.developerTokenRejected,
        AppleMusicFailure.developerTokenUnavailable,
        AppleMusicFailure.rateLimited,
        AppleMusicFailure.network,
        AppleMusicFailure.http,
      ]) {
        final e = AppleMusicApiException(failure, status: 500);
        expect(refusesCredentials(sourceExceptionFor(e)), isFalse, reason: '$failure');
      }
    });
  });

  group('SubsonicApi HTTP status', () {
    Future<Object> pingError(int status) async {
      final source = SubsonicMusicSource(
        _creds,
        client: MockClient((_) async => http.Response('<html></html>', status)),
      );
      try {
        await source.library.ping();
      } catch (e) {
        return e;
      }
      fail('ping should have failed');
    }

    test('should_keepStatusAsCause_when_serverAnswersWithoutSubsonicBody', () async {
      final error = await pingError(401);
      expect(error, isA<SourceServerException>().having((e) => e.message, 'message', 'HTTP 401'));
      expect((error as SourceServerException).cause, isA<SubsonicHttpStatus>().having((s) => s.status, 'status', 401));
      expect(refusesCredentials(error), isTrue);
    });

    test('should_notRefuse_when_serverAnswers5xx', () async {
      expect(refusesCredentials(await pingError(502)), isFalse);
    });
  });
}
