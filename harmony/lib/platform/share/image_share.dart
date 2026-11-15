/// Handing an image to the system on `yoin/share` (ArkTS `YoinSharePlugin`):
/// the share sheet (Share Kit) and saving into the gallery through the
/// system's save dialog (photoAccessHelper.showAssetsCreationDialog). The
/// lyrics card uses both (Android LyricsShareCard's share intent and
/// MediaStore save).
library;

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

abstract interface class ImageShare {
  /// Whether this host can save into the gallery (the Save button shows only then).
  bool get canSave;

  /// Opens the system share sheet on [png]; [caption] is its title. False when it couldn't.
  Future<bool> share(Uint8List png, {required String fileName, String? caption});

  /// Saves [png] into the gallery as [fileName] (the user confirms in the
  /// system dialog); false when declined or failed.
  Future<bool> save(Uint8List png, {required String fileName});
}

final class ChannelImageShare implements ImageShare {
  const ChannelImageShare([this._channel = const MethodChannel(channelName)]);

  static const channelName = 'yoin/share';

  final MethodChannel _channel;

  @override
  bool get canSave => true;

  @override
  Future<bool> share(Uint8List png, {required String fileName, String? caption}) async {
    try {
      return await _channel.invokeMethod<bool>('shareImage', {
            'bytes': png,
            'name': fileName,
            'caption': caption ?? '',
          }) ??
          false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  @override
  Future<bool> save(Uint8List png, {required String fileName}) async {
    try {
      return await _channel.invokeMethod<bool>('saveImage', {'bytes': png, 'name': fileName}) ?? false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }
}

/// Hosts without a share sheet or gallery (the Windows build for now): Save
/// isn't offered and Share reports it couldn't.
final class NoImageShare implements ImageShare {
  const NoImageShare();

  @override
  bool get canSave => false;

  @override
  Future<bool> share(Uint8List png, {required String fileName, String? caption}) async => false;

  @override
  Future<bool> save(Uint8List png, {required String fileName}) async => false;
}

/// The image hand-off; tests override it with a recording fake.
final imageShareProvider = Provider<ImageShare>((ref) => const ChannelImageShare());
