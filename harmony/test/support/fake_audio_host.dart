import 'dart:async';

import 'package:yoin/platform/audio/audio_host.dart';

/// The audio host as tests drive it: an output, a spatial state, and what was asked of it.
class FakeAudioHost implements AudioHost {
  AudioOutputKind output = AudioOutputKind.speaker;
  SpatialAudioCapability capability = SpatialAudioCapability.none;
  bool downmix = false;
  int settingsOpened = 0;
  int outputReads = 0;
  final spatialPushes = StreamController<SpatialAudioCapability>.broadcast();

  @override
  Future<AudioOutputKind> currentOutput() async {
    outputReads++;
    return output;
  }

  @override
  Stream<AudioOutputKind> get outputChanges => const Stream.empty();

  @override
  Future<SpatialAudioCapability> spatial() async => capability;

  @override
  Stream<SpatialAudioCapability> get spatialChanges => spatialPushes.stream;

  @override
  bool get canChooseSpatialization => true;

  @override
  Future<void> setSpatialDownmix(bool value) async => downmix = value;

  @override
  Future<bool> openSoundSettings() async {
    settingsOpened++;
    return true;
  }
}
