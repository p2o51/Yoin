/// The panel's own corner radius on `yoin/window` (ArkTS `YoinWindowHost`,
/// `display.Display.getRoundedCorner()`, API 23+): what "Match screen" in
/// Settings › Appearance › Screen corners follows (Android
/// `Display.getRoundedCorner`). Null when the display reports none (older
/// systems, a panel without a configured radius, a virtual or external
/// display, the Windows build): the slider still sets the corner by hand.
library;

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

abstract interface class DisplayCorners {
  /// The largest of the default display's corner radii, in vp (logical px), or null.
  Future<double?> cornerRadius();
}

final class ChannelDisplayCorners implements DisplayCorners {
  const ChannelDisplayCorners([this._channel = const MethodChannel(channelName)]);

  static const channelName = 'yoin/window';

  final MethodChannel _channel;

  @override
  Future<double?> cornerRadius() async {
    try {
      final value = await _channel.invokeMethod<num>('screenCornerRadius');
      return value == null || value <= 0 ? null : value.toDouble();
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
    }
  }
}

/// No display report (Windows, tests): the corner is set by hand.
final class NoDisplayCorners implements DisplayCorners {
  const NoDisplayCorners();

  @override
  Future<double?> cornerRadius() async => null;
}

/// The display's corner reading; main.dart wires the platform's, tests a fake.
final displayCornersProvider = Provider<DisplayCorners>((ref) => const NoDisplayCorners());
