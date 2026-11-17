/// The sound output as the system routes it, from the `yoin/audio` channel
/// served by the ArkTS `YoinAudioPlugin` (ohos/entry/src/main/ets/yoin/audio/):
///
///  - the kind of output music plays through (Streaming quality's Auto,
///    Android AudioOutputKind), read from the routing manager's preferred
///    output for music and pushed again on every change;
///  - what the system's spatial audio can do on it (Android Spatializer,
///    here `AudioSpatializationManager`), pushed on every change;
///  - Yoin's own spatial switch, handed to the native player (multichannel
///    songs play with the audio effect off when it is off);
///  - the system's sound settings.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// What the sound goes out through (Android AudioOutputKind).
enum AudioOutputKind {
  /// Wired headphones, a USB DAC or headset, HDMI / DisplayPort, a line out.
  wired,

  /// Bluetooth (A2DP, hearing aids) and NearLink: re-encoded on the way.
  bluetooth,

  /// The device's own speaker, a cast device, anything else.
  speaker;

  static AudioOutputKind fromName(Object? name) => values.where((k) => k.name == name).firstOrNull ?? speaker;
}

/// What the system's spatial audio can do right now (the platform half of
/// Android SpatialAudioState).
///
/// [supported]: the device has a spatializer at all
/// (`SystemCapability.Multimedia.Audio.Spatialization`). [outputSupported]:
/// the current output can be spatialized (`AudioDeviceDescriptor
/// .spatializationSupported`). [enabled]: the system's switch is on for the
/// current output (`isSpatializationEnabledForCurrentDevice`; HarmonyOS keeps
/// it per output device). [headTracking]: the output tracks the head; no
/// public HarmonyOS API reports it, so it stays false there.
@immutable
final class SpatialAudioCapability {
  const SpatialAudioCapability({
    this.supported = false,
    this.outputSupported = false,
    this.enabled = false,
    this.headTracking = false,
  });

  static const none = SpatialAudioCapability();

  final bool supported;
  final bool outputSupported;
  final bool enabled;
  final bool headTracking;

  factory SpatialAudioCapability.fromMap(Object? value) {
    if (value is! Map) return none;
    bool flag(String key) => value[key] == true;
    return SpatialAudioCapability(
      supported: flag('supported'),
      outputSupported: flag('outputSupported'),
      enabled: flag('enabled'),
      headTracking: flag('headTracking'),
    );
  }

  @override
  bool operator ==(Object other) =>
      other is SpatialAudioCapability &&
      other.supported == supported &&
      other.outputSupported == outputSupported &&
      other.enabled == enabled &&
      other.headTracking == headTracking;

  @override
  int get hashCode => Object.hash(supported, outputSupported, enabled, headTracking);

  @override
  String toString() =>
      'SpatialAudioCapability(supported: $supported, output: $outputSupported, enabled: $enabled, head: $headTracking)';
}

abstract interface class AudioHost {
  /// The output music plays through now.
  Future<AudioOutputKind> currentOutput();

  /// Every change of [currentOutput].
  Stream<AudioOutputKind> get outputChanges;

  /// The system's spatial audio now.
  Future<SpatialAudioCapability> spatial();

  /// Every change of [spatial] (the system switch, the output).
  Stream<SpatialAudioCapability> get spatialChanges;

  /// Whether Yoin can choose per song whether the system spatializes it
  /// (Android's SPATIALIZATION_BEHAVIOR_AUTO / NEVER). HarmonyOS: the
  /// player's audio effect mode.
  bool get canChooseSpatialization;

  /// Yoin's switch: [downmix] true plays multichannel songs folded down to
  /// stereo, without the system's spatial rendering. Applies at once.
  Future<void> setSpatialDownmix(bool downmix);

  /// Opens the system's sound settings; false when nothing opened.
  Future<bool> openSoundSettings();
}

/// The ArkTS plugin over `yoin/audio`. Reads once, then keeps what the
/// platform pushes. Hosts and tests without the plugin get a speaker and no
/// spatial audio.
final class ChannelAudioHost implements AudioHost {
  ChannelAudioHost({MethodChannel channel = const MethodChannel('yoin/audio')}) : _channel = channel;

  final MethodChannel _channel;
  bool _listening = false;
  AudioOutputKind? _output;
  SpatialAudioCapability? _spatial;
  final _outputs = StreamController<AudioOutputKind>.broadcast();
  final _spatials = StreamController<SpatialAudioCapability>.broadcast();

  /// The handler goes on at first use: a plain unit test has no binding.
  void _listen() {
    if (_listening) return;
    _listening = true;
    try {
      _channel.setMethodCallHandler((call) async {
        switch (call.method) {
          case 'outputChanged':
            final kind = AudioOutputKind.fromName(call.arguments);
            _output = kind;
            _outputs.add(kind);
          case 'spatialChanged':
            final state = SpatialAudioCapability.fromMap(call.arguments);
            _spatial = state;
            _spatials.add(state);
        }
      });
    } catch (_) {
      // No binding (unit tests): nothing is pushed.
    }
  }

  Future<T?> _invoke<T>(String method, [Object? arguments]) async {
    _listen();
    try {
      return await _channel.invokeMethod<T>(method, arguments);
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
    } catch (_) {
      return null;
    }
  }

  @override
  Future<AudioOutputKind> currentOutput() async {
    final known = _output;
    if (known != null) return known;
    final name = await _invoke<String>('output');
    return name == null ? AudioOutputKind.speaker : (_output = AudioOutputKind.fromName(name));
  }

  @override
  Stream<AudioOutputKind> get outputChanges {
    _listen();
    return _outputs.stream;
  }

  @override
  Future<SpatialAudioCapability> spatial() async {
    final known = _spatial;
    if (known != null) return known;
    final map = await _invoke<Map<Object?, Object?>>('spatial');
    return map == null ? SpatialAudioCapability.none : (_spatial = SpatialAudioCapability.fromMap(map));
  }

  @override
  Stream<SpatialAudioCapability> get spatialChanges {
    _listen();
    return _spatials.stream;
  }

  @override
  bool get canChooseSpatialization => true;

  @override
  Future<void> setSpatialDownmix(bool downmix) => _invoke<void>('setSpatialDownmix', downmix);

  @override
  Future<bool> openSoundSettings() async => await _invoke<bool>('openSoundSettings') ?? false;
}

/// A desktop's output as streaming quality's Auto sees it: [output] (the
/// speaker until the Windows audio-endpoint list, `IMMDeviceEnumerator`,
/// says otherwise). No spatial audio (Windows Sonic / Dolby Atmos for
/// headphones are the system's, with no per-app read here), no way to the
/// sound settings yet (`ms-settings:sound` through the shell later).
final class FixedAudioHost implements AudioHost {
  const FixedAudioHost([this.output = AudioOutputKind.speaker]);

  final AudioOutputKind output;

  @override
  Future<AudioOutputKind> currentOutput() async => output;

  @override
  Stream<AudioOutputKind> get outputChanges => const Stream.empty();

  @override
  Future<SpatialAudioCapability> spatial() async => SpatialAudioCapability.none;

  @override
  Stream<SpatialAudioCapability> get spatialChanges => const Stream.empty();

  @override
  bool get canChooseSpatialization => false;

  @override
  Future<void> setSpatialDownmix(bool downmix) async {}

  @override
  Future<bool> openSoundSettings() async => false;
}

/// The phone's volume keys, taken over while a DLNA speaker plays (Android
/// gives the session a remote DeviceInfo, so the keys move the speaker's
/// volume). HarmonyOS: `inputConsumer.on('keyPressed')`, which only works
/// while Yoin's window is in front and has focus.
abstract interface class VolumeKeyHost {
  /// [on] takes the keys from the system (its volume no longer moves);
  /// false gives them back.
  Future<void> setIntercepting(bool on);

  /// +1 for volume up, -1 for volume down, while intercepting (repeats too).
  Stream<int> get presses;
}

/// The ArkTS plugin over `yoin/volume_keys`.
final class ChannelVolumeKeyHost implements VolumeKeyHost {
  ChannelVolumeKeyHost({MethodChannel channel = const MethodChannel('yoin/volume_keys')}) : _channel = channel;

  final MethodChannel _channel;
  bool _listening = false;
  final _presses = StreamController<int>.broadcast();

  void _listen() {
    if (_listening) return;
    _listening = true;
    try {
      _channel.setMethodCallHandler((call) async {
        if (call.method == 'volumeKey') {
          // A whole number may come over as a double from ArkTS.
          final step = call.arguments;
          if (step is num && step != 0) _presses.add(step > 0 ? 1 : -1);
        }
      });
    } catch (_) {
      // No binding (unit tests).
    }
  }

  @override
  Future<void> setIntercepting(bool on) async {
    _listen();
    try {
      await _channel.invokeMethod<void>('setIntercepting', on);
    } catch (_) {
      // No plugin, or the system refused: the keys stay the system's.
    }
  }

  @override
  Stream<int> get presses {
    _listen();
    return _presses.stream;
  }
}

/// No volume keys to take (desktop, tests).
final class NoVolumeKeyHost implements VolumeKeyHost {
  const NoVolumeKeyHost();

  @override
  Future<void> setIntercepting(bool on) async {}

  @override
  Stream<int> get presses => const Stream.empty();
}

/// main() gets the platform's (platformOverrides); tests and hosts without one a speaker.
final audioHostProvider = Provider<AudioHost>((ref) => const FixedAudioHost());

/// main() gets the platform's; tests and hosts without one: none.
final volumeKeyHostProvider = Provider<VolumeKeyHost>((ref) => const NoVolumeKeyHost());
