/// The app's own folder for files it keeps (HarmonyOS: the sandbox's
/// `filesDir`, served on `yoin/storage` by the ArkTS `YoinStoragePlugin`;
/// desktop: the app-data folder). Offline downloads live under it, out of the
/// cache folder the system may empty (Android keeps them in filesDir too).
library;

import 'dart:io';

import 'package:flutter/services.dart';

abstract interface class AppFilesHost {
  /// The folder, or null where this build keeps no files (tests, previews).
  Future<Directory?> filesDirectory();
}

/// HarmonyOS: `context.filesDir` (the app's el2 sandbox, removed with the app).
final class ChannelAppFilesHost implements AppFilesHost {
  const ChannelAppFilesHost({MethodChannel channel = const MethodChannel('yoin/storage')}) : _channel = channel;

  final MethodChannel _channel;

  @override
  Future<Directory?> filesDirectory() async {
    try {
      final path = await _channel.invokeMethod<String>('filesDir');
      return path == null || path.isEmpty ? null : Directory(path);
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
    }
  }
}

/// A fixed folder (the desktop app-data folder, a test's temp folder).
final class DirectoryAppFilesHost implements AppFilesHost {
  const DirectoryAppFilesHost(this.directory);

  final Directory directory;

  @override
  Future<Directory?> filesDirectory() async => directory;
}

/// No folder: features that keep files stay off (tests and previews by default).
final class NoAppFilesHost implements AppFilesHost {
  const NoAppFilesHost();

  @override
  Future<Directory?> filesDirectory() async => null;
}
