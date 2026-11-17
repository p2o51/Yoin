/// Handing a file to the system share sheet on `yoin/share` (ArkTS
/// `YoinSharePlugin.shareFile`, Share Kit): Help and about's Export log, as
/// Android's ACTION_SEND of a FileProvider uri (SettingsScreen ExportLogItem).
library;

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'image_share.dart';

/// What kind of file is shared: the share sheet offers the targets that take it.
enum SharedFileKind {
  /// Plain text (`general.plain-text`): a log.
  text,
}

abstract interface class FileShare {
  /// Opens the system share sheet on the file at [path] (in the app's
  /// sandbox); [title] heads the sheet's preview. False when it couldn't.
  Future<bool> shareFile(String path, {required SharedFileKind kind, String? title});
}

final class ChannelFileShare implements FileShare {
  const ChannelFileShare([this._channel = const MethodChannel(ChannelImageShare.channelName)]);

  final MethodChannel _channel;

  @override
  Future<bool> shareFile(String path, {required SharedFileKind kind, String? title}) async {
    try {
      return await _channel.invokeMethod<bool>('shareFile', {'path': path, 'kind': kind.name, 'title': title ?? ''}) ??
          false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }
}

/// Hosts without a share sheet (the Windows build for now): Share reports it couldn't.
final class NoFileShare implements FileShare {
  const NoFileShare();

  @override
  Future<bool> shareFile(String path, {required SharedFileKind kind, String? title}) async => false;
}

/// The file hand-off; tests override it with a recording fake.
final fileShareProvider = Provider<FileShare>((ref) => const ChannelFileShare());
