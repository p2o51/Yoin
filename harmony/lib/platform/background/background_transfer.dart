/// Keeping the app running while songs download (Android YoinDownloadService,
/// a foreground service with a progress notification). HarmonyOS suspends an
/// app soon after it goes to the background unless it holds a continuous
/// task; downloads hold one of the data-transfer kind (`dataTransfer`,
/// ohos.permission.KEEP_BACKGROUND_RUNNING), whose live-view notification
/// shows their progress, served on `yoin/transfer` by the ArkTS
/// `YoinTransferPlugin`. The desktop doesn't suspend apps: nothing to hold.
///
/// One app holds one such task: downloads and a playlist restore share it
/// through [SharedBackgroundTransfer] (shared_background_transfer.dart), never
/// this host directly. The same channel posts the plain notification a
/// finished restore leaves behind, and asks for the notification permission.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// What the progress notification says.
@immutable
final class TransferNotice {
  const TransferNotice({
    required this.title,
    required this.text,
    required this.percent,
    this.chip,
    this.subText,
    this.link,
  });

  final String title;
  final String text;

  /// A line beside the text (Android's setSubText); null: none.
  final String? subText;

  /// 0–100; null: no bar (work whose length isn't known, Android's indeterminate bar).
  final int? percent;

  /// The status-bar capsule's short text ("12 / 45", Android's setShortCriticalText); null: none.
  final String? chip;

  /// What tapping the notification opens, handed back through [BackgroundTransferHost.taps] (Android's
  /// contentIntent); null: the app as it is.
  final String? link;

  Map<String, Object> toMap() => {
    'title': title,
    'text': text,
    'percent': ?percent,
    'chip': ?chip,
    'subText': ?subText,
    'link': ?link,
  };

  @override
  bool operator ==(Object other) =>
      other is TransferNotice &&
      other.title == title &&
      other.text == text &&
      other.percent == percent &&
      other.chip == chip &&
      other.subText == subText &&
      other.link == link;

  @override
  int get hashCode => Object.hash(title, text, percent, chip, subText, link);

  @override
  String toString() => 'TransferNotice($title, $text, $percent%, $chip)';
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

  /// Whether the app may post notifications. With [ask], a system that hasn't
  /// asked yet shows its dialog first (Android's POST_NOTIFICATIONS request).
  Future<bool> notificationsAllowed({bool ask = false});

  /// A plain notification that stays after the task, until swiped or replaced
  /// (a finished restore's result); [id] identifies it.
  Future<void> notify(int id, TransferNotice notice);

  /// Takes the notification [id] posted by [notify] away.
  Future<void> cancelNotice(int id);

  /// A notification with a [TransferNotice.link] was tapped: its link (also the one that started the app).
  Stream<String> get taps;
}

/// HarmonyOS: `yoin/transfer` → backgroundTaskManager (MODE_DATA_TRANSFER) + notificationManager (live view).
final class ChannelBackgroundTransferHost implements BackgroundTransferHost {
  ChannelBackgroundTransferHost({MethodChannel channel = const MethodChannel(channelName)}) : _channel = channel;

  static const channelName = 'yoin/transfer';

  final MethodChannel _channel;
  final _ended = StreamController<bool>.broadcast();
  final _taps = StreamController<String>.broadcast();
  bool _listening = false;
  bool _ready = false;

  void _listen() {
    if (_listening) return;
    _listening = true;
    _channel.setMethodCallHandler((call) async {
      final args = call.arguments;
      switch (call.method) {
        case 'ended':
          _ended.add(args is Map && args['byUser'] == true);
        case 'tap':
          final link = args is Map ? args['link'] : null;
          if (link is String && link.isNotEmpty) _taps.add(link);
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

  @override
  Future<bool> notificationsAllowed({bool ask = false}) async {
    try {
      return await _channel.invokeMethod<bool>('notificationsAllowed', {'ask': ask}) ?? false;
    } on MissingPluginException {
      return false;
    } on PlatformException catch (e) {
      debugPrint('YoinTransfer: notification permission unknown: ${e.code}');
      return false;
    }
  }

  @override
  Future<void> notify(int id, TransferNotice notice) async {
    try {
      await _channel.invokeMethod<void>('notify', {'id': id, ...notice.toMap()});
    } on MissingPluginException {
      // No native side (tests).
    } on PlatformException catch (e) {
      debugPrint('YoinTransfer: notice not posted: ${e.code}');
    }
  }

  @override
  Future<void> cancelNotice(int id) async {
    try {
      await _channel.invokeMethod<void>('cancelNotice', {'id': id});
    } on MissingPluginException {
      // No native side (tests).
    } on PlatformException catch (e) {
      debugPrint('YoinTransfer: notice not removed: ${e.code}');
    }
  }

  @override
  Stream<String> get taps {
    _listen();
    if (!_ready) {
      _ready = true;
      // A tap that started the app waits on the native side until Dart listens.
      unawaited(_sendReady());
    }
    return _taps.stream;
  }

  Future<void> _sendReady() async {
    try {
      await _channel.invokeMethod<void>('ready');
    } on MissingPluginException {
      // No native side (tests).
    } on PlatformException catch (e) {
      debugPrint('YoinTransfer: taps not ready: ${e.code}');
    }
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

  @override
  Future<bool> notificationsAllowed({bool ask = false}) async => false;

  @override
  Future<void> notify(int id, TransferNotice notice) async {}

  @override
  Future<void> cancelNotice(int id) async {}

  @override
  Stream<String> get taps => const Stream.empty();
}
