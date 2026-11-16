import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/applemusic/apple_music_source.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/data/subsonic/subsonic_api.dart';
import 'package:yoin/ui/component/error_strings.dart';
import 'package:yoin/ui/component/user_message.dart';

void main() {
  const en = ErrorStrings.en;
  const zh = ErrorStrings.forLanguage(chinese: true);

  String? say(Object? error) => userMessageOf(error)?.text(en);

  group('networkFailureKindOf', () {
    test('should_sayTimeout_when_timeoutException', () {
      expect(networkFailureKindOf(TimeoutException('slow')), NetworkFailureKind.timeout);
    });

    test('should_sayTls_when_handshakeFails', () {
      expect(networkFailureKindOf(const HandshakeException('CERTIFICATE_VERIFY_FAILED')), NetworkFailureKind.tls);
      expect(networkFailureKindOf(const TlsException('bad record')), NetworkFailureKind.tls);
    });

    test('should_sayConnect_when_socketRefused', () {
      expect(networkFailureKindOf(const SocketException('Connection refused')), NetworkFailureKind.connect);
    });

    test('should_sayTimeout_when_connectTimedOut', () {
      expect(networkFailureKindOf(const SocketException('Connection timed out')), NetworkFailureKind.timeout);
    });

    test('should_readClientExceptionMessage_when_wrapped', () {
      expect(
        networkFailureKindOf(http.ClientException('HandshakeException: CERTIFICATE_VERIFY_FAILED')),
        NetworkFailureKind.tls,
      );
      expect(networkFailureKindOf(http.ClientException('Operation timed out')), NetworkFailureKind.timeout);
      expect(networkFailureKindOf(http.ClientException('Connection closed')), NetworkFailureKind.io);
    });

    test('should_sayIo_when_otherIOException', () {
      expect(networkFailureKindOf(const FileSystemException('x')), NetworkFailureKind.io);
    });
  });

  group('userMessageOf', () {
    test('should_sayUnreachable_when_connectionRefused', () {
      expect(
        say(const SourceNetworkException('x', kind: NetworkFailureKind.connect)),
        "Can't reach the server. Check your connection.",
      );
      expect(say(const SourceNetworkException('x')), "Can't reach the server. Check your connection.");
    });

    test('should_sayTimeout_when_serverTooSlow', () {
      expect(
        say(const SourceNetworkException('x', kind: NetworkFailureKind.timeout)),
        'The server is taking too long. Try again.',
      );
      expect(userMessageOf(TimeoutException('x'))?.text(zh), '服务器响应超时。请重试。');
    });

    test('should_saySecureConnectionFailed_when_tlsFails', () {
      expect(
        say(const SourceNetworkException('x', kind: NetworkFailureKind.tls)),
        'Secure connection failed. Check the server address.',
      );
      expect(say(const HandshakeException('x')), 'Secure connection failed. Check the server address.');
    });

    test('should_passServerWords_when_subsonicRefuses', () {
      expect(say(const SourceAuthException('Wrong username or password', code: 40)), 'Wrong username or password');
      expect(say(const SourceServerException('Album not found', code: 70)), 'Album not found');
    });

    test('should_fallBack_when_failureIsYoinsOwnEnglish', () {
      // An HTTP status or an unreadable answer has no line; the screen says its own.
      expect(userMessageOf(const SourceServerException('HTTP 404')), isNull);
      expect(userMessageOf(const SourceServerException('Not a Subsonic response')), isNull);
      expect(userMessageOf(StateError('No account')), isNull);
      expect(userMessageOf(null), isNull);
      expect(userMessageOf(const SourceServerException('   ', code: 0)), isNull);
    });

    test('should_sayAppleLines_when_appleRefuses', () {
      String? apple(AppleMusicFailure failure, {int? status}) =>
          say(sourceExceptionFor(AppleMusicApiException(failure, status: status)));
      expect(apple(AppleMusicFailure.userAuthorizationRequired), 'Apple Music access expired. Reconnect in Settings.');
      expect(
        apple(AppleMusicFailure.developerTokenRejected, status: 401),
        'Apple Music rejected the developer token. Check the token service.',
      );
      expect(apple(AppleMusicFailure.accessDenied, status: 403), 'Apple Music refused this request. Try again later.');
      expect(
        apple(AppleMusicFailure.rateLimited, status: 429),
        'Apple Music is busy right now. Try again in a moment.',
      );
      // Yoin's own English for the rest is never said.
      expect(apple(AppleMusicFailure.http, status: 500), isNull);
      expect(apple(AppleMusicFailure.invalidResponse), isNull);
    });

    test('should_sayAppleNetworkKind_when_appleUnreachable', () {
      final e = sourceExceptionFor(
        const AppleMusicApiException(AppleMusicFailure.network, networkKind: NetworkFailureKind.tls),
      );
      expect(e, isA<SourceNetworkException>().having((n) => n.kind, 'kind', NetworkFailureKind.tls));
      expect(say(e), 'Secure connection failed. Check the server address.');
    });

    testWidgets('should_sayFallback_when_noLine', (tester) async {
      late String text;
      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (context) {
              text = userMessageText(context, const FormatException('x'), 'Couldn’t load this album.');
              return const SizedBox();
            },
          ),
        ),
      );
      expect(text, 'Couldn’t load this album.');
    });
  });

  group('SubsonicApi transport', () {
    SubsonicApi api(Future<http.Response> Function(http.Request) handler) => SubsonicApi(
      credentials: const SubsonicCredentials(baseUrl: 'https://music.example.com', username: 'u', password: 'p'),
      client: MockClient(handler),
      salt: 'salt',
      timeout: const Duration(milliseconds: 50),
    );

    test('should_classifyTls_when_handshakeFails', () async {
      final call = api((_) => throw const HandshakeException('CERTIFICATE_VERIFY_FAILED')).call('ping');
      await expectLater(
        call,
        throwsA(
          isA<SourceNetworkException>()
              .having((e) => e.kind, 'kind', NetworkFailureKind.tls)
              .having((e) => e.cause, 'cause', isA<HandshakeException>()),
        ),
      );
    });

    test('should_classifyTimeout_when_serverHangs', () async {
      final call = api((_) => Completer<http.Response>().future).call('ping');
      await expectLater(
        call,
        throwsA(isA<SourceNetworkException>().having((e) => e.kind, 'kind', NetworkFailureKind.timeout)),
      );
    });

    test('should_classifyConnect_when_refused', () async {
      final call = api((_) => throw const SocketException('Connection refused')).call('ping');
      await expectLater(
        call,
        throwsA(isA<SourceNetworkException>().having((e) => e.kind, 'kind', NetworkFailureKind.connect)),
      );
    });

    test('should_keepAuthCode_when_wrongPassword', () async {
      final call = api(
        (_) async => http.Response(
          '{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}}',
          200,
        ),
      ).call('ping');
      await expectLater(call, throwsA(isA<SourceAuthException>().having((e) => e.code, 'code', 40)));
    });
  });
}
