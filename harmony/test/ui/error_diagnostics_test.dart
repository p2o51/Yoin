import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/applemusic/apple_music_source.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/settings/licenses.dart';
import 'package:yoin/ui/component/error_diagnostics.dart';
import 'package:yoin/ui/component/error_strings.dart';

void main() {
  final now = DateTime.utc(2026, 11, 17, 8, 30, 5, 678);

  String report(Object error) => diagnosticReport(error, now: now, device: 'HarmonyOS 6.0');

  group('diagnosticReport', () {
    test('should_nameBuildDeviceAndTime_when_reported', () {
      final lines = report(const SourceNetworkException('x', kind: NetworkFailureKind.timeout)).split('\n');
      expect(lines.first, 'Yoin $yoinVersionName ($yoinVersionCode)');
      expect(lines[1], 'HarmonyOS 6.0');
      expect(lines[2], 'Network timeout');
      expect(lines.last, '2026-11-17T08:30:05Z');
    });

    test('should_nameTransportCause_when_networkFails', () {
      final text = report(
        const SourceNetworkException('x', kind: NetworkFailureKind.tls, cause: HandshakeException('bad cert')),
      );
      expect(text, contains('Network tls'));
      expect(text, contains('cause HandshakeException'));
      expect(text, isNot(contains('bad cert')));
    });

    test('should_neverQuoteSecrets_when_messagesCarryThem', () {
      // A server's or a transport's words can quote the request, its token and salt, a password, a host.
      const secret = 'https://music.example.com/rest/ping.view?u=gpo&t=0123456789abcdef&s=pepper&p=hunter2';
      final errors = <Object>[
        const SourceAuthException('Wrong password for $secret', code: 40),
        const SourceServerException('Boom at $secret', code: 0),
        const SourceServerException('Nope $secret'),
        const SourceNetworkException(secret, cause: SocketException(secret)),
        sourceExceptionFor(const AppleMusicApiException(AppleMusicFailure.http, status: 500, detail: secret)),
        const FormatException(secret),
      ];
      for (final e in errors) {
        final text = report(e);
        for (final leak in ['0123456789abcdef', 'pepper', 'hunter2', 'music.example.com', 'gpo']) {
          expect(text, isNot(contains(leak)), reason: '$e leaked $leak');
        }
      }
    });

    test('should_sayCodes_when_subsonicRefuses', () {
      expect(report(const SourceAuthException('x', code: 40)).split('\n')[2], 'Subsonic auth 40');
      expect(report(const SourceServerException('x', code: 70)).split('\n')[2], 'Subsonic 70');
      expect(report(const SourceServerException('HTTP 502')).split('\n')[2], 'HTTP 502');
      expect(report(const SourceServerException('Not a Subsonic response')).split('\n')[2], 'Not a Subsonic response');
      expect(report(const SourceServerException('something with words')).split('\n')[2], 'Server error');
    });

    test('should_sayAppleStatusAndCode_when_appleRefuses', () {
      final e = sourceExceptionFor(
        const AppleMusicApiException(AppleMusicFailure.accessDenied, status: 403, appleCode: '40300'),
      );
      expect(report(e).split('\n')[2], 'Apple Music accessDenied, HTTP 403, Apple 40300');
    });
  });

  group('CopyDiagnosticsButton', () {
    testWidgets('should_copyReportAndSayCopied_when_tapped', (tester) async {
      String? copied;
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
        if (call.method == 'Clipboard.setData') copied = (call.arguments as Map)['text'] as String?;
        return null;
      });
      addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(body: CopyDiagnosticsButton(report: 'Yoin 0.1.0 (1)')),
        ),
      );
      expect(find.text('Copy details'), findsOneWidget);

      await tester.tap(find.text('Copy details'));
      await tester.pumpAndSettle();
      expect(copied, 'Yoin 0.1.0 (1)');
      expect(find.text('Copied'), findsOneWidget);
      expect(find.text('Copy details'), findsNothing);
    });

    test('should_sayChinese_when_zh', () {
      const zh = ErrorStrings.forLanguage(chinese: true);
      expect(zh.copyDetails, '复制诊断信息');
      expect(zh.detailsCopied, '已复制');
      expect(ErrorStrings.en.copyDetails, 'Copy details');
    });
  });
}
