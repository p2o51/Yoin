/// Spatial audio (Android player/SpatialAudio.kt, owner task 2026-11-04).
/// Yoin plays a multichannel file (5.1, 7.1) as the original and hands every
/// channel to the system. Whether the listener then hears it spatialized is
/// HarmonyOS's call: its spatial renderer takes a music stream when the
/// system switch is on for the current output (usually headphones) and that
/// output can be spatialized. Elsewhere the system folds the channels down to
/// stereo.
///
/// Yoin's part: ask for it or opt out (the switch, applied per song through
/// the player's audio effect mode: off plays a multichannel song with the
/// effect chain, spatial rendering included, bypassed), and say truthfully
/// which of the two the listener gets — the "Spatial" tag on Now Playing and
/// the status line in Settings › Streaming quality.
///
/// Where HarmonyOS differs from Android: the system switch is kept per output
/// device, so it is only read for the output in use ([SpatialAudioState
/// .outputSupported] comes before [SpatialAudioState.enabled]); no public API
/// says which channel layouts it takes (every multichannel layout counts) or
/// whether the headset tracks the head ([SpatialAudioState.headTracking]
/// stays false on HarmonyOS).
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../platform/audio/audio_host.dart';
import '../platform/storage/stores.dart';

/// Whether Yoin lets the system spatialize multichannel songs.
enum SpatialAudioMode {
  /// Spatialized wherever the system can (Media3 SPATIALIZATION_BEHAVIOR_AUTO).
  auto('auto'),

  /// Always the system's stereo fold-down (SPATIALIZATION_BEHAVIOR_NEVER).
  off('off');

  const SpatialAudioMode(this.key);
  final String key;

  static SpatialAudioMode? fromKey(String? key) => values.where((m) => m.key == key).firstOrNull;
}

/// What the system can do right now, plus Yoin's own choice.
@immutable
final class SpatialAudioState {
  const SpatialAudioState({
    this.mode = SpatialAudioMode.auto,
    this.supported = false,
    this.outputSupported = false,
    this.enabled = false,
    this.headTracking = false,
    this.canChoose = false,
  });

  factory SpatialAudioState.of(
    SpatialAudioCapability system, {
    required SpatialAudioMode mode,
    required bool canChoose,
  }) => SpatialAudioState(
    mode: mode,
    supported: system.supported,
    outputSupported: system.outputSupported,
    enabled: system.enabled,
    headTracking: system.headTracking,
    canChoose: canChoose,
  );

  final SpatialAudioMode mode;

  /// The device has a spatializer at all.
  final bool supported;

  /// The current output can be spatialized.
  final bool outputSupported;

  /// The system switch is on for the current output.
  final bool enabled;

  /// The current headset tracks the head (never reported on HarmonyOS).
  final bool headTracking;

  /// Yoin can choose per song (the switch shows only then).
  final bool canChoose;

  SpatialAudioState copyWith({SpatialAudioMode? mode}) => SpatialAudioState(
    mode: mode ?? this.mode,
    supported: supported,
    outputSupported: outputSupported,
    enabled: enabled,
    headTracking: headTracking,
    canChoose: canChoose,
  );

  @override
  bool operator ==(Object other) =>
      other is SpatialAudioState &&
      other.mode == mode &&
      other.supported == supported &&
      other.outputSupported == outputSupported &&
      other.enabled == enabled &&
      other.headTracking == headTracking &&
      other.canChoose == canChoose;

  @override
  int get hashCode => Object.hash(mode, supported, outputSupported, enabled, headTracking, canChoose);
}

/// How a song with some channel count reaches the ears.
enum SpatialPlayback {
  /// Mono or stereo: nothing to spatialize.
  notMultichannel,

  /// The system spatializes the channels.
  spatialized,

  /// Folded down to stereo because Yoin's switch is off.
  downmixedByChoice,

  /// Folded down: the device has no spatializer.
  downmixedUnsupported,

  /// Folded down: the system's spatial audio switch is off (for this output).
  downmixedSystemOff,

  /// Folded down: the current output (e.g. the speaker) can't be spatialized.
  downmixedOutput,
}

/// How [channels] plays under [state]; [SpatialPlayback.notMultichannel] for
/// unknown, mono and stereo. Yoin's switch counts only where Yoin can apply
/// it ([SpatialAudioState.canChoose]).
SpatialPlayback spatialPlaybackOf(int? channels, SpatialAudioState state) {
  if (channels == null || channels <= 2) return SpatialPlayback.notMultichannel;
  if (state.canChoose && state.mode == SpatialAudioMode.off) return SpatialPlayback.downmixedByChoice;
  if (!state.supported) return SpatialPlayback.downmixedUnsupported;
  if (!state.outputSupported) return SpatialPlayback.downmixedOutput;
  if (!state.enabled) return SpatialPlayback.downmixedSystemOff;
  return SpatialPlayback.spatialized;
}

/// Yoin's choice in [KeyValueStore], for this device (the system's spatial
/// audio is the device's, not the account's).
final class SpatialAudioStore {
  const SpatialAudioStore(this._values);

  final KeyValueStore _values;

  static const key = 'spatial_audio_mode.v1';

  Future<SpatialAudioMode> read() async => SpatialAudioMode.fromKey(await _values.read(key)) ?? SpatialAudioMode.auto;

  Future<void> write(SpatialAudioMode mode) => _values.write(key, mode.key);
}

/// Watches the system's spatial audio and the output, and keeps Yoin's
/// switch, one per process (Android SpatialAudioMonitor). The native player
/// is told the switch at start and on every change; Now Playing and Settings
/// show what it says.
class SpatialAudioMonitor extends Notifier<SpatialAudioState> {
  StreamSubscription<SpatialAudioCapability>? _changes;
  bool _touched = false;

  AudioHost get _host => ref.read(audioHostProvider);

  @override
  SpatialAudioState build() {
    final host = _host;
    _changes = host.spatialChanges.listen(_onSystem, onError: (Object _) {});
    ref.onDispose(() => _changes?.cancel());
    unawaited(_load());
    return SpatialAudioState(canChoose: host.canChooseSpatialization);
  }

  Future<void> _load() async {
    await Future<void>.value();
    var mode = SpatialAudioMode.auto;
    try {
      mode = await SpatialAudioStore(ref.read(keyValueStoreProvider)).read();
    } catch (_) {
      // No storage (tests, a broken store): the default.
    }
    SpatialAudioCapability system;
    try {
      system = await _host.spatial();
    } catch (_) {
      system = SpatialAudioCapability.none;
    }
    if (!ref.mounted) return;
    // A choice made while it loaded wins.
    final chosen = _touched ? state.mode : mode;
    state = SpatialAudioState.of(system, mode: chosen, canChoose: _host.canChooseSpatialization);
    await _push(chosen);
  }

  void _onSystem(SpatialAudioCapability system) {
    if (!ref.mounted) return;
    state = SpatialAudioState.of(system, mode: state.mode, canChoose: state.canChoose);
  }

  /// Yoin's switch; applies at once, to the song playing too.
  Future<void> setMode(SpatialAudioMode mode) async {
    if (state.mode == mode) return;
    _touched = true;
    state = state.copyWith(mode: mode);
    await _push(mode);
    try {
      await SpatialAudioStore(ref.read(keyValueStoreProvider)).write(mode);
    } catch (_) {
      // Kept for this run; the next start reads the old value.
    }
  }

  Future<void> _push(SpatialAudioMode mode) async {
    try {
      await _host.setSpatialDownmix(mode == SpatialAudioMode.off);
    } catch (_) {
      // The native player keeps its last choice.
    }
  }

  /// Opens the system's sound settings (the system switch and head tracking live there).
  Future<bool> openSystemSettings() async {
    try {
      return await _host.openSoundSettings();
    } catch (_) {
      return false;
    }
  }
}

final spatialAudioProvider = NotifierProvider<SpatialAudioMonitor, SpatialAudioState>(SpatialAudioMonitor.new);
