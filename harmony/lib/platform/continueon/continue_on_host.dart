/// What Continue On needs from the platform (Android 17's cross-device
/// hand-off; on HarmonyOS 应用接续, app continuation):
/// - say whether the system may offer this app on the user's other devices
///   now ([setOffered]), with a cursor to hand over should Dart not answer in time;
/// - answer the system when it asks for the hand-off ([handoffRequests]);
/// - deliver a hand-off this device was started or brought to the front with
///   ([received]);
/// - this device's local-network addresses and name, for the pause channel
///   and the other device's card.
///
/// HarmonyOS: `yoin/continue_on` (ohos/entry/src/main/ets/yoin/continueon/
/// ContinueOnPlugin.ets): `setMissionContinueState`, `UIAbility.onContinue`
/// filling the wantParam, `onCreate` / `onNewWant` with launchReason
/// CONTINUATION, `@ohos.net.connection` and the market name. Windows has no
/// app continuation: [NoContinueOnHost]. harmony/docs/continue-on.md.
library;

import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Answers one system hand-off request: the cursor's entries, or null with nothing to hand over.
typedef HandoffAnswer = Future<Map<String, Object?>?> Function();

abstract interface class ContinueOnHost {
  /// Whether this platform can hand off at all.
  bool get supported;

  /// Hand-offs this device received, as the cursor's entries; one at a time,
  /// each delivered once (a cold start's waits until [ready]).
  Stream<Map<String, Object?>> get received;

  /// Who answers the system when it asks for the hand-off. One at a time; null stops answering.
  set handoffRequests(HandoffAnswer? answer);

  /// Dart listens: a hand-off that started the app comes now.
  Future<void> ready();

  /// Whether the system may offer the hand-off now. [snapshot] is handed
  /// over when Dart can't answer the request in time (no pause channel then).
  Future<void> setOffered(bool offered, {Map<String, Object?>? snapshot});

  /// This device's addresses on its Wi-Fi or Ethernet (never a VPN's, never
  /// mobile data's), as written-out IPv4 / IPv6; empty when it is on none.
  Future<List<String>> localAddresses();

  /// What the other device's card calls this one ("HUAWEI Mate 70 Air"); null when unknown.
  Future<String?> deviceName();
}

const _continueOnChannel = MethodChannel('yoin/continue_on');

/// HarmonyOS: the ArkTS side in ContinueOnPlugin.ets.
final class ChannelContinueOnHost implements ContinueOnHost {
  ChannelContinueOnHost({MethodChannel channel = _continueOnChannel}) : _channel = channel;

  final MethodChannel _channel;
  final _received = StreamController<Map<String, Object?>>.broadcast();
  HandoffAnswer? _answer;
  bool _listening = false;

  void _listen() {
    if (_listening) return;
    _listening = true;
    _channel.setMethodCallHandler(_onCall);
  }

  Future<Object?> _onCall(MethodCall call) async {
    switch (call.method) {
      case 'received':
        final entries = continueOnEntriesOf(call.arguments);
        if (entries != null) _received.add(entries);
        return null;
      case 'handoff':
        final answer = _answer;
        if (answer == null) return null;
        try {
          return await answer();
        } catch (e) {
          debugPrint('YoinContinueOn: no cursor (${e.runtimeType})');
          return null;
        }
      default:
        throw MissingPluginException(call.method);
    }
  }

  @override
  bool get supported => true;

  @override
  Stream<Map<String, Object?>> get received {
    _listen();
    return _received.stream;
  }

  @override
  set handoffRequests(HandoffAnswer? answer) {
    _listen();
    _answer = answer;
  }

  @override
  Future<void> ready() {
    _listen();
    return _channel.invokeMethod<void>('ready');
  }

  @override
  Future<void> setOffered(bool offered, {Map<String, Object?>? snapshot}) =>
      _channel.invokeMethod<void>('setOffered', {'offered': offered, 'snapshot': snapshot});

  @override
  Future<List<String>> localAddresses() async {
    final raw = await _channel.invokeMethod<List<Object?>>('localAddresses');
    return [
      for (final a in raw ?? const <Object?>[])
        if (a is String) a,
    ];
  }

  @override
  Future<String?> deviceName() async {
    final name = await _channel.invokeMethod<String>('deviceName');
    return name == null || name.trim().isEmpty ? null : name.trim();
  }
}

/// Windows, desktop runs and tests: no app continuation, nothing offered or received.
final class NoContinueOnHost implements ContinueOnHost {
  const NoContinueOnHost();

  @override
  bool get supported => false;

  @override
  Stream<Map<String, Object?>> get received => const Stream.empty();

  @override
  set handoffRequests(HandoffAnswer? answer) {}

  @override
  Future<void> ready() async {}

  @override
  Future<void> setOffered(bool offered, {Map<String, Object?>? snapshot}) async {}

  @override
  Future<List<String>> localAddresses() async => const [];

  @override
  Future<String?> deviceName() async {
    try {
      final name = Platform.localHostname.trim();
      return name.isEmpty ? null : name;
    } catch (_) {
      return null;
    }
  }
}

/// A channel argument as cursor entries (string keys only); null when it is none.
Map<String, Object?>? continueOnEntriesOf(Object? raw) {
  if (raw is! Map) return null;
  return {
    for (final e in raw.entries)
      if (e.key is String) e.key as String: e.value,
  };
}
