/// "Keep screen on" for Now Playing (Android ui/nowplaying/NowPlayingKeepScreenOn.kt;
/// owner 2026-10-17: a tester asked for the screen to stay lit with Now
/// Playing open). The window is held on only while all three hold:
///
/// - Now Playing asks for it ([NowPlayingKeepScreenOn.setKeepScreenOn]:
///   true while it is open, false when it closes),
/// - the user chose it ([keepScreenOnPreferenceProvider], stored per device,
///   off by default as on Android), and
/// - music is actually playing: paused, the normal screen timeout comes back,
///   so a forgotten pause doesn't keep the screen lit all night.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../platform/storage/stores.dart';
import '../platform/window/window_controls.dart';
import 'playback_controller.dart';

/// The stored choice (Android prefs key `now_playing_keep_screen_on`).
class KeepScreenOnPreference extends Notifier<bool> {
  static const storeKey = 'nowPlaying.keepScreenOn';

  bool _touched = false;

  @override
  bool build() {
    _load();
    return false;
  }

  /// The user's choice: applies at once and is stored.
  Future<void> set(bool on) async {
    _touched = true;
    state = on;
    try {
      await _store()?.write(storeKey, on ? 'true' : null);
    } catch (e) {
      debugPrint('YoinKeepScreenOn: not saved: ${e.runtimeType}');
    }
  }

  Future<void> _load() async {
    try {
      final stored = await _store()?.read(storeKey);
      // A choice made while the read was in flight wins.
      if (ref.mounted && !_touched) state = stored == 'true';
    } catch (e) {
      debugPrint('YoinKeepScreenOn: not read: ${e.runtimeType}');
    }
  }

  KeyValueStore? _store() {
    try {
      return ref.read(keyValueStoreProvider);
    } catch (_) {
      return null; // Tests / previews without storage.
    }
  }
}

final keepScreenOnPreferenceProvider = NotifierProvider<KeepScreenOnPreference, bool>(KeepScreenOnPreference.new);

/// Whether the window is held on right now (the state). Now Playing calls
/// [setKeepScreenOn]; this provider gates it on the preference and on
/// playback and drives the window (`yoin/window` setKeepScreenOn).
class NowPlayingKeepScreenOn extends Notifier<bool> {
  bool _requested = false;
  bool _applied = false;
  WindowControls? _controls;

  @override
  bool build() {
    try {
      _controls = ref.read(windowControlsProvider);
    } catch (_) {
      _controls = null;
    }
    ref.listen(playbackControllerProvider.select((s) => s.isPlaying), (_, _) => _apply());
    ref.listen(keepScreenOnPreferenceProvider, (_, _) => _apply());
    ref.onDispose(() {
      if (_applied) _send(false);
    });
    return false;
  }

  /// Now Playing: true while it is open (on screen), false when it closes.
  void setKeepScreenOn(bool on) {
    if (_requested == on) return;
    _requested = on;
    _apply();
  }

  void _apply() {
    final on = _requested && ref.read(keepScreenOnPreferenceProvider) && ref.read(playbackControllerProvider).isPlaying;
    if (on == _applied) return;
    _applied = on;
    state = on;
    _send(on);
  }

  void _send(bool on) {
    try {
      _controls?.setKeepScreenOn(on).catchError((Object e) {
        debugPrint('YoinKeepScreenOn: window call failed: ${e.runtimeType}');
      });
    } catch (e) {
      debugPrint('YoinKeepScreenOn: window call failed: ${e.runtimeType}');
    }
  }
}

final nowPlayingKeepScreenOnProvider = NotifierProvider<NowPlayingKeepScreenOn, bool>(NowPlayingKeepScreenOn.new);
