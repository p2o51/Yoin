/// Keeping a file where the user chooses and picking one, through the
/// system's document picker on `yoin/share` (ArkTS `YoinSharePlugin.saveFile`
/// / `pickFile`, picker.DocumentViewPicker): Settings › Export all data and
/// Import from backup, Android's SAF CreateDocument / OpenDocument.
library;

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'image_share.dart';

abstract interface class DocumentFiles {
  /// Asks where to keep the file at [path] (in the app's sandbox), suggested as
  /// [name], and copies it there. False when the user cancelled or it failed.
  Future<bool> saveFile(String path, {required String name});

  /// Lets the user pick one file; its path in the app's cache (a copy the
  /// caller deletes), or null when nothing was picked.
  Future<String?> pickFile();
}

final class ChannelDocumentFiles implements DocumentFiles {
  const ChannelDocumentFiles([this._channel = const MethodChannel(ChannelImageShare.channelName)]);

  final MethodChannel _channel;

  @override
  Future<bool> saveFile(String path, {required String name}) async {
    try {
      return await _channel.invokeMethod<bool>('saveFile', {'path': path, 'name': name}) ?? false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  @override
  Future<String?> pickFile() async {
    try {
      return await _channel.invokeMethod<String>('pickFile');
    } on PlatformException {
      return null;
    } on MissingPluginException {
      return null;
    }
  }
}

/// The picker; tests override it with a fake.
final documentFilesProvider = Provider<DocumentFiles>((ref) => const ChannelDocumentFiles());
