import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/cache/cover_cache.dart';
import 'package:yoin/platform/network/network_bearer.dart';
import 'package:yoin/ui/component/artwork_retry.dart';

void main() {
  group('ArtworkRetryPolicy.classify', () {
    test('should_retryOnBackoff_when_offlineOr5xx', () {
      expect(ArtworkRetryPolicy.classify(const SocketException('offline')), ArtworkFailureKind.transient);
      expect(ArtworkRetryPolicy.classify(TimeoutException('slow')), ArtworkFailureKind.transient);
      expect(ArtworkRetryPolicy.classify(const ArtworkHttpException(503)), ArtworkFailureKind.transient);
    });

    test('should_awaitSignal_when_4xx', () {
      expect(ArtworkRetryPolicy.classify(const ArtworkHttpException(404)), ArtworkFailureKind.awaitSignal);
      expect(ArtworkRetryPolicy.classify(const ArtworkHttpException(410)), ArtworkFailureKind.awaitSignal);
    });

    test('should_beUndecodable_when_bytesMadeNoImage', () {
      expect(ArtworkRetryPolicy.classify(const ArtworkUndecodableException()), ArtworkFailureKind.undecodable);
      expect(ArtworkRetryPolicy.classify(StateError('weird')), ArtworkFailureKind.undecodable);
    });
  });

  group('ArtworkRetryState', () {
    test('should_walkTheBackoffThenWaitForSignal_when_transientKeepsFailing', () {
      var state = const ArtworkRetryState(requestSignal: 0);
      final waits = <Duration?>[];
      for (var i = 0; i < 4; i++) {
        state = state.failed(const SocketException('offline'));
        waits.add(state.backoff());
        state = state.retried(signal: 0, byBackoff: true);
      }
      expect(waits, [...ArtworkRetryPolicy.backoff, null]);
    });

    test('should_startBackoffOver_when_signalRetries', () {
      final state = const ArtworkRetryState(requestSignal: 0, backoffRetries: 3).failed(const SocketException('x'));
      final next = state.retried(signal: 1, byBackoff: false);
      expect(next.backoffRetries, 0);
      expect(next.requesting, isTrue);
      expect(next.fallbackUnder, isTrue, reason: 'the icon stays until the retried image reveals');
      expect(next.revealed().fallbackUnder, isFalse);
    });

    test('should_haveNoBackoff_when_404', () {
      expect(const ArtworkRetryState(requestSignal: 0).failed(const ArtworkHttpException(404)).backoff(), isNull);
    });
  });

  group('ArtworkRetryWait', () {
    testWidgets('should_retryAfterBackoff_when_transient', (tester) async {
      final signal = ValueNotifier(0);
      ArtworkRetryState? next;
      final failed = const ArtworkRetryState(requestSignal: 0).failed(const SocketException('offline'));
      ArtworkRetryWait(failed, (s) => next = s, signal: signal);
      await tester.pump(const Duration(seconds: 2));
      expect(next, isNull);
      await tester.pump(const Duration(seconds: 1));
      expect(next?.backoffRetries, 1);
    });

    testWidgets('should_retryAtOnce_when_signalMoves', (tester) async {
      final signal = ValueNotifier(0);
      ArtworkRetryState? next;
      final failed = const ArtworkRetryState(requestSignal: 0).failed(const ArtworkHttpException(404));
      ArtworkRetryWait(failed, (s) => next = s, signal: signal);
      await tester.pump(const Duration(minutes: 5));
      expect(next, isNull, reason: 'a 404 waits for a signal');
      signal.value++;
      expect(next?.requestSignal, 1);
      expect(next?.backoffRetries, 0);
    });

    testWidgets('should_retryNow_when_signalMovedWhileRequestWasOut', (tester) async {
      final signal = ValueNotifier(2);
      ArtworkRetryState? next;
      final failed = const ArtworkRetryState(requestSignal: 1).failed(const ArtworkHttpException(404));
      ArtworkRetryWait(failed, (s) => next = s, signal: signal);
      await tester.pump();
      expect(next?.requestSignal, 2);
    });

    testWidgets('should_neverRetry_when_cancelled', (tester) async {
      final signal = ValueNotifier(0);
      ArtworkRetryState? next;
      final failed = const ArtworkRetryState(requestSignal: 0).failed(const SocketException('offline'));
      ArtworkRetryWait(failed, (s) => next = s, signal: signal).cancel();
      signal.value++;
      await tester.pump(const Duration(minutes: 2));
      expect(next, isNull);
    });
  });

  group('ArtworkNetworkRecovery', () {
    test('should_notRetry_when_registrationReplaysCurrentNetwork', () {
      final r = ArtworkNetworkRecovery<int>(100);
      expect(r.onAvailable(100), isFalse);
      expect(r.onCapabilitiesChanged(100, validated: true), isFalse);
      expect(r.onBlockedStatusChanged(100, blocked: false), isFalse);
    });

    test('should_retry_when_anotherNetworkArrives', () {
      final r = ArtworkNetworkRecovery<int>(100);
      r.onLost(100);
      expect(r.onAvailable(101), isTrue);
      expect(r.onAvailable(101), isFalse);
    });

    test('should_retry_when_networkRegainsValidation', () {
      final r = ArtworkNetworkRecovery<int>(100);
      expect(r.onCapabilitiesChanged(100, validated: false), isFalse);
      expect(r.onCapabilitiesChanged(100, validated: true), isTrue);
      expect(r.onCapabilitiesChanged(100, validated: true), isFalse);
      expect(r.onCapabilitiesChanged(999, validated: true), isFalse, reason: 'not the default network');
    });

    test('should_retry_when_trafficUnblocked', () {
      final r = ArtworkNetworkRecovery<int>(100);
      expect(r.onBlockedStatusChanged(100, blocked: true), isFalse);
      expect(r.onBlockedStatusChanged(100, blocked: false), isTrue);
    });

    test('should_readPlatformEvents_when_pushedOnTheChannel', () {
      final r = ArtworkNetworkRecovery<int>(null);
      final available = DefaultNetworkEvent.fromMap({'kind': 'available', 'netId': 7})!;
      expect(r.onEvent(available), isTrue);
      final lost = DefaultNetworkEvent.fromMap({'kind': 'lost', 'netId': 7})!;
      expect(r.onEvent(lost), isFalse);
      expect(
        DefaultNetworkEvent.fromMap({'kind': 'capabilities', 'netId': 7, 'validated': true}),
        isA<DefaultNetworkCapabilities<int>>(),
      );
      expect(
        DefaultNetworkEvent.fromMap({'kind': 'blocked', 'netId': 7, 'blocked': true}),
        isA<DefaultNetworkBlocked<int>>(),
      );
      expect(DefaultNetworkEvent.fromMap({'kind': 'weird', 'netId': 7}), isNull);
      expect(DefaultNetworkEvent.fromMap('nope'), isNull);
    });
  });

  group('ArtworkForegroundRetry', () {
    test('should_retry_when_appReturnsAfterBeingHidden', () {
      var retries = 0;
      final f = ArtworkForegroundRetry(() => retries++);
      f.onResume();
      expect(retries, 0, reason: 'the first resume is not a recovery');
      f.onHide();
      f.onResume();
      f.onResume();
      expect(retries, 1);
    });
  });
}
