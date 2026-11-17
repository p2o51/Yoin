/// Window-level system calls on `yoin/window` (ArkTS `YoinWindowHost`):
/// keeping the screen lit (Android `View.keepScreenOn`) and a system toast for
/// the moments the app's own snackbar can't be seen (Android `Toast`).
library;

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

abstract interface class WindowControls {
  /// Holds the main window's screen on while [on] (ArkTS
  /// `Window.setWindowKeepScreenOn`). The system drops it with the window.
  Future<void> setKeepScreenOn(bool on);

  /// A short system toast (ArkTS `PromptAction.showToast`, top-most so it
  /// shows over the lock screen card / 播控中心 too).
  Future<void> showToast(String message);
}

final class ChannelWindowControls implements WindowControls {
  const ChannelWindowControls([this._channel = const MethodChannel(channelName)]);

  static const channelName = 'yoin/window';

  final MethodChannel _channel;

  @override
  Future<void> setKeepScreenOn(bool on) => _channel.invokeMethod<void>('setKeepScreenOn', {'on': on});

  @override
  Future<void> showToast(String message) => _channel.invokeMethod<void>('showToast', {'message': message});
}

/// The window's system calls; tests override it with a recording fake.
final windowControlsProvider = Provider<WindowControls>((ref) => const ChannelWindowControls());
