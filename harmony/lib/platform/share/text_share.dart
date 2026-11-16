/// Handing text to the system share sheet on `yoin/share` (ArkTS
/// `YoinSharePlugin.shareText`, Share Kit): a page's or a song's name with its
/// link, as Android's ACTION_SEND text/plain chooser (DetailMenu.kt
/// rememberDetailShare).
library;

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'image_share.dart';

abstract interface class TextShare {
  /// Opens the system share sheet on [text] (plain text; a link inside it
  /// stays a link). [title] heads the sheet's preview. False when it couldn't.
  Future<bool> shareText(String text, {String? title});
}

final class ChannelTextShare implements TextShare {
  const ChannelTextShare([this._channel = const MethodChannel(ChannelImageShare.channelName)]);

  final MethodChannel _channel;

  @override
  Future<bool> shareText(String text, {String? title}) async {
    try {
      return await _channel.invokeMethod<bool>('shareText', {'text': text, 'title': title ?? ''}) ?? false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }
}

/// Hosts without a share sheet (the Windows build for now): Share reports it couldn't.
final class NoTextShare implements TextShare {
  const NoTextShare();

  @override
  Future<bool> shareText(String text, {String? title}) async => false;
}

/// The text hand-off; tests override it with a recording fake.
final textShareProvider = Provider<TextShare>((ref) => const ChannelTextShare());
