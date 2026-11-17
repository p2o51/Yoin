/// The screen's corner radius (ScreenCorners.kt), as the user set it
/// (Settings › Appearance › Screen corners, or the landing's step) or as the
/// display reports it. The shapes that stand in for "this page is the screen,
/// shrinking or sliding" take it, so their corners meet the glass: Now
/// Playing while it opens, closes or is dragged, and its side panel's start
/// corners.
///
/// Per device, never synced: two phones have two different screens.
library;

import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/providers.dart';
import '../../platform/window/display_corners.dart';
import '../../theme/tokens.dart';

@immutable
final class ScreenCornersState {
  const ScreenCornersState({this.user, this.device});

  /// What the user picked, or null to follow the screen.
  final double? user;

  /// What the display reports (its largest corner, in dp), or null.
  final double? device;

  /// The radius every screen-shaped surface uses.
  double get radius => user ?? device ?? ScreenCornerTokens.fallback;

  /// The radius the screen itself has, for "follow the screen".
  double get screenDefault => device ?? ScreenCornerTokens.fallback;

  /// No value of the user's own: the screen's (or the stand-in) is used.
  bool get following => user == null;

  ScreenCornersState withUser(double? value) => ScreenCornersState(user: value, device: device);

  ScreenCornersState withDevice(double? value) => ScreenCornersState(user: user, device: value);

  @override
  bool operator ==(Object other) => other is ScreenCornersState && other.user == user && other.device == device;

  @override
  int get hashCode => Object.hash(user, device);

  @override
  String toString() => 'ScreenCornersState(user: $user, device: $device)';
}

/// [value] on the slider's range, or null.
double? clampScreenCorner(double? value) => value?.clamp(ScreenCornerTokens.min, ScreenCornerTokens.max).toDouble();

/// The stored choice and the display's reading. The display is read again
/// whenever the window's metrics change (fold / unfold, rotation, another
/// display), as Android does on a configuration change.
class ScreenCornersPreference extends Notifier<ScreenCornersState> with WidgetsBindingObserver {
  /// Android's key (yoin_ui_preferences); a float in dp.
  static const key = 'screen_corner_radius_dp';

  bool _touched = false;
  bool _observing = false;
  Timer? _refresh;

  @override
  ScreenCornersState build() {
    _load();
    refreshDevice();
    _observe();
    ref.onDispose(() {
      _refresh?.cancel();
      if (_observing) WidgetsBinding.instance.removeObserver(this);
    });
    return const ScreenCornersState();
  }

  void _observe() {
    try {
      WidgetsBinding.instance.addObserver(this);
      _observing = true;
    } catch (_) {
      // No binding (a pure unit test): read once.
    }
  }

  @override
  void didChangeMetrics() {
    _refresh?.cancel();
    _refresh = Timer(const Duration(milliseconds: 300), refreshDevice);
  }

  Future<void> _load() async {
    final String? raw;
    try {
      raw = await ref.read(keyValueStoreProvider).read(key);
    } catch (_) {
      // No store (a harness without one): follow the screen, for this session.
      return;
    }
    if (!ref.mounted || _touched || raw == null) return;
    final value = double.tryParse(raw);
    if (value != null) state = state.withUser(clampScreenCorner(value));
  }

  /// Reads the display's corners again.
  Future<void> refreshDevice() async {
    double? reported;
    try {
      reported = await ref.read(displayCornersProvider).cornerRadius();
    } catch (_) {
      reported = null;
    }
    if (!ref.mounted) return;
    final value = clampScreenCorner(reported);
    if (value != state.device) state = state.withDevice(value);
  }

  /// The user's choice, shown at once everywhere; null follows the screen
  /// again. [persist] false while a slider is still moving (no write per
  /// frame); the release stores it.
  Future<void> select(double? radius, {bool persist = true}) async {
    _touched = true;
    final value = clampScreenCorner(radius);
    if (value != state.user) state = state.withUser(value);
    if (!persist) return;
    try {
      await ref.read(keyValueStoreProvider).write(key, value?.toString());
    } catch (_) {
      // Kept for this session.
    }
  }

  /// Stores what is shown now (a slider's release).
  Future<void> commit() => select(state.user);
}

final screenCornersProvider = NotifierProvider<ScreenCornersPreference, ScreenCornersState>(
  ScreenCornersPreference.new,
);

/// The radius every screen-shaped surface uses (Now Playing's moving player,
/// its side panel's start corners).
final screenCornerRadiusProvider = Provider<double>((ref) => ref.watch(screenCornersProvider).radius);
