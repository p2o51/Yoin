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

  /// The folder for files the app can fetch again (the stream cache): the
  /// system may empty it when storage runs low. Null where this build keeps none.
  Future<Directory?> cacheDirectory();

  /// The free space on the volume [cacheDirectory] is on, in bytes (the cover
  /// cache's budget is 2 % of it); null when it can't be read.
  Future<int?> cacheFreeBytes();
}

/// HarmonyOS: `context.filesDir` (the app's el2 sandbox, removed with the app).
final class ChannelAppFilesHost implements AppFilesHost {
  const ChannelAppFilesHost({MethodChannel channel = const MethodChannel('yoin/storage')}) : _channel = channel;

  final MethodChannel _channel;

  @override
  Future<Directory?> filesDirectory() => _folder('filesDir');

  /// HarmonyOS: `context.cacheDir` (the app's el2 cache folder).
  @override
  Future<Directory?> cacheDirectory() => _folder('cacheDir');

  /// HarmonyOS: `statfs.getFreeSizeSync(cacheDir)`.
  @override
  Future<int?> cacheFreeBytes() async {
    try {
      return await _channel.invokeMethod<int>('cacheFreeBytes');
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
    }
  }

  Future<Directory?> _folder(String method) async {
    try {
      final path = await _channel.invokeMethod<String>(method);
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

  /// `cache/` inside [directory].
  @override
  Future<Directory?> cacheDirectory() async => Directory('${directory.path}${Platform.pathSeparator}cache');

  /// Unknown in pure Dart (Windows: GetDiskFreeSpaceEx later): the cover cache
  /// keeps Coil's minimum budget.
  @override
  Future<int?> cacheFreeBytes() async => null;
}

/// No folder: features that keep files stay off (tests and previews by default).
final class NoAppFilesHost implements AppFilesHost {
  const NoAppFilesHost();

  @override
  Future<Directory?> filesDirectory() async => null;

  @override
  Future<Directory?> cacheDirectory() async => null;

  @override
  Future<int?> cacheFreeBytes() async => null;
}
