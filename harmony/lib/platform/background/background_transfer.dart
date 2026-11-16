/// Keeping the app running while songs download (Android YoinDownloadService,
/// a foreground service with a progress notification). HarmonyOS suspends an
/// app soon after it goes to the background unless it holds a continuous
/// task; downloads hold one of the data-transfer kind (`dataTransfer`,
/// ohos.permission.KEEP_BACKGROUND_RUNNING), whose live-view notification
/// shows their progress, served on `yoin/transfer` by the ArkTS
/// `YoinTransferPlugin`. The desktop doesn't suspend apps: nothing to hold.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// What the progress notification says.
@immutable
final class TransferNotice {
  const TransferNotice({required this.title, required this.text, required this.percent});

  final String title;
  final String text;

  /// 0–100.
  final int percent;

  Map<String, Object> toMap() => {'title': title, 'text': text, 'percent': percent};

  @override
  bool operator ==(Object other) =>
      other is TransferNotice && other.title == title && other.text == text && other.percent == percent;

  @override
  int get hashCode => Object.hash(title, text, percent);
}

abstract interface class BackgroundTransferHost {
  /// Holds the continuous task (showing [notice]), or updates it when held.
  /// False when the system refused (the app is behind, the permission is missing).
  Future<bool> hold(TransferNotice notice);

  /// Lets the task go, and its notification with it.
  Future<void> release();

  /// The task ended without [release]: true when the user ended it (removed
  /// its notification), false when the system did (slow transfer, no
  /// progress for 10 minutes).
  Stream<bool> get ended;
}

/// HarmonyOS: `yoin/transfer` → backgroundTaskManager (MODE_DATA_TRANSFER) + notificationManager (live view).
final class ChannelBackgroundTransferHost implements BackgroundTransferHost {
  ChannelBackgroundTransferHost({MethodChannel channel = const MethodChannel(channelName)}) : _channel = channel;

  static const channelName = 'yoin/transfer';

  final MethodChannel _channel;
  final _ended = StreamController<bool>.broadcast();
  bool _listening = false;

  void _listen() {
    if (_listening) return;
    _listening = true;
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'ended') {
        final args = call.arguments;
        _ended.add(args is Map && args['byUser'] == true);
      }
      return null;
    });
  }

  @override
  Future<bool> hold(TransferNotice notice) async {
    _listen();
    try {
      return await _channel.invokeMethod<bool>('hold', notice.toMap()) ?? false;
    } on MissingPluginException {
      return false;
    } on PlatformException catch (e) {
      debugPrint('YoinTransfer: hold refused: ${e.code}');
      return false;
    }
  }

  @override
  Future<void> release() async {
    try {
      await _channel.invokeMethod<void>('release');
    } on MissingPluginException {
      // No native side (tests).
    } on PlatformException catch (e) {
      debugPrint('YoinTransfer: release failed: ${e.code}');
    }
  }

  @override
  Stream<bool> get ended {
    _listen();
    return _ended.stream;
  }
}

/// Nothing to hold: a desktop app keeps running behind other windows (and tests).
final class NoBackgroundTransferHost implements BackgroundTransferHost {
  const NoBackgroundTransferHost();

  @override
  Future<bool> hold(TransferNotice notice) async => true;

  @override
  Future<void> release() async {}

  @override
  Stream<bool> get ended => const Stream.empty();
}
