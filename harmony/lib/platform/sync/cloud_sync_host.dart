/// What cloud sync needs from the platform (no Google Play services on
/// HarmonyOS, so sign-in is OAuth 2.0 for native apps: the system browser,
/// PKCE and a loopback redirect served from Dart — RFC 8252):
/// - open Google's page in the system browser;
/// - keep the app running while the user is away in the browser, so the
///   loopback redirect is still served when Google sends them back;
/// - this device's name, as other devices list it;
/// - where the sync store's file lives.
///
/// HarmonyOS: `yoin/cloud_sync` (ohos/entry/src/main/ets/yoin/sync/CloudSyncPlugin.ets):
/// the browser through a view-data Want, a transient task
/// (backgroundTaskManager.requestSuspendDelay) for the wait, the market name,
/// the ability's filesDir. Windows and desktop runs: [DesktopCloudSyncHost].
library;

import 'dart:io';

import 'package:flutter/services.dart';

abstract interface class CloudSyncHost {
  /// Opens [url] in the system browser. False when nothing could open it.
  Future<bool> openBrowser(Uri url);

  /// Asks the system not to suspend the app while the user is in the browser.
  Future<void> holdWhileAway();

  /// Ends [holdWhileAway] (always called, also after a cancel or failure).
  Future<void> releaseHold();

  /// This device's name as other devices show it ("HUAWEI Mate 70 Air").
  Future<String> deviceName();

  /// The folder for the sync store (`yoin-sync.json.gz`); null: none (kept in memory).
  Future<Directory?> filesDirectory();
}

const _cloudSyncChannel = MethodChannel('yoin/cloud_sync');

final class ChannelCloudSyncHost implements CloudSyncHost {
  const ChannelCloudSyncHost({MethodChannel channel = _cloudSyncChannel}) : _channel = channel;

  final MethodChannel _channel;

  @override
  Future<bool> openBrowser(Uri url) async =>
      await _channel.invokeMethod<bool>('openBrowser', {'url': url.toString()}) ?? false;

  @override
  Future<void> holdWhileAway() => _channel.invokeMethod<void>('holdWhileAway');

  @override
  Future<void> releaseHold() => _channel.invokeMethod<void>('releaseHold');

  @override
  Future<String> deviceName() async {
    final name = await _channel.invokeMethod<String>('deviceName');
    return name == null || name.trim().isEmpty ? 'HarmonyOS device' : name.trim();
  }

  @override
  Future<Directory?> filesDirectory() async {
    final path = await _channel.invokeMethod<String>('filesDir');
    return path == null || path.isEmpty ? null : Directory(path);
  }
}

/// Windows (and desktop runs): the default browser through the shell, nothing
/// to hold (a desktop app isn't suspended), the host name, the app-data folder.
final class DesktopCloudSyncHost implements CloudSyncHost {
  const DesktopCloudSyncHost(this._appData);

  final Directory _appData;

  @override
  Future<bool> openBrowser(Uri url) async {
    final link = url.toString();
    try {
      final ProcessResult result;
      if (Platform.isWindows) {
        result = await Process.run('rundll32', ['url.dll,FileProtocolHandler', link]);
      } else if (Platform.isMacOS) {
        result = await Process.run('open', [link]);
      } else {
        result = await Process.run('xdg-open', [link]);
      }
      return result.exitCode == 0;
    } on ProcessException {
      return false;
    }
  }

  @override
  Future<void> holdWhileAway() async {}

  @override
  Future<void> releaseHold() async {}

  @override
  Future<String> deviceName() async {
    try {
      final name = Platform.localHostname.trim();
      return name.isEmpty ? 'Windows PC' : name;
    } catch (_) {
      return 'Windows PC';
    }
  }

  @override
  Future<Directory?> filesDirectory() async => _appData;
}

/// No platform (tests, previews): no browser, no files; sync can't turn on.
final class InertCloudSyncHost implements CloudSyncHost {
  const InertCloudSyncHost();

  @override
  Future<bool> openBrowser(Uri url) async => false;

  @override
  Future<void> holdWhileAway() async {}

  @override
  Future<void> releaseHold() async {}

  @override
  Future<String> deviceName() async => 'Yoin';

  @override
  Future<Directory?> filesDirectory() async => null;
}
