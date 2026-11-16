import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/platform/audio/audio_host.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/playback/spatial_audio.dart';

import '../support/fake_audio_host.dart';

const _ready = SpatialAudioState(supported: true, outputSupported: true, enabled: true, canChoose: true);

void main() {
  group('spatialPlaybackOf', () {
    test('should_notCount_when_monoStereoOrUnknown', () {
      for (final channels in [null, 1, 2]) {
        expect(spatialPlaybackOf(channels, _ready), SpatialPlayback.notMultichannel);
      }
    });

    test('should_spatialize_when_systemOnForThisOutput', () {
      expect(spatialPlaybackOf(6, _ready), SpatialPlayback.spatialized);
      expect(spatialPlaybackOf(8, _ready), SpatialPlayback.spatialized);
    });

    test('should_foldDownByChoice_when_yoinsSwitchOff', () {
      expect(spatialPlaybackOf(6, _ready.copyWith(mode: SpatialAudioMode.off)), SpatialPlayback.downmixedByChoice);
    });

    test('should_ignoreTheSwitch_when_yoinCantChoose', () {
      const fixed = SpatialAudioState(
        mode: SpatialAudioMode.off,
        supported: true,
        outputSupported: true,
        enabled: true,
      );
      expect(spatialPlaybackOf(6, fixed), SpatialPlayback.spatialized);
    });

    test('should_sayWhy_when_foldedDown', () {
      expect(spatialPlaybackOf(6, const SpatialAudioState(canChoose: true)), SpatialPlayback.downmixedUnsupported);
      expect(
        spatialPlaybackOf(6, const SpatialAudioState(supported: true, canChoose: true)),
        SpatialPlayback.downmixedOutput,
      );
      expect(
        spatialPlaybackOf(6, const SpatialAudioState(supported: true, outputSupported: true, canChoose: true)),
        SpatialPlayback.downmixedSystemOff,
      );
    });
  });

  group('monitor', () {
    late InMemoryStore store;
    late FakeAudioHost host;
    late ProviderContainer c;

    setUp(() {
      store = InMemoryStore();
      host = FakeAudioHost();
      c = ProviderContainer(
        overrides: [keyValueStoreProvider.overrideWithValue(store), audioHostProvider.overrideWithValue(host)],
      );
    });
    tearDown(() => c.dispose());

    test('should_tellThePlayerTheStoredChoice_when_started', () async {
      await store.write(SpatialAudioStore.key, 'off');
      host.capability = const SpatialAudioCapability(supported: true, outputSupported: true, enabled: true);
      c.listen(spatialAudioProvider, (_, _) {});
      await pumpEventQueue();
      final state = c.read(spatialAudioProvider);
      expect(state.mode, SpatialAudioMode.off);
      expect(state.supported, isTrue);
      expect(state.enabled, isTrue);
      expect(state.canChoose, isTrue);
      expect(host.downmix, isTrue);
    });

    test('should_followTheSystem_when_itPushesAChange', () async {
      c.listen(spatialAudioProvider, (_, _) {});
      await pumpEventQueue();
      expect(spatialPlaybackOf(6, c.read(spatialAudioProvider)), SpatialPlayback.downmixedUnsupported);
      host.spatialPushes.add(const SpatialAudioCapability(supported: true, outputSupported: true));
      await pumpEventQueue();
      expect(spatialPlaybackOf(6, c.read(spatialAudioProvider)), SpatialPlayback.downmixedSystemOff);
    });

    test('should_storeAndApplyAtOnce_when_switched', () async {
      c.listen(spatialAudioProvider, (_, _) {});
      await pumpEventQueue();
      expect(host.downmix, isFalse);
      await c.read(spatialAudioProvider.notifier).setMode(SpatialAudioMode.off);
      expect(host.downmix, isTrue);
      expect(await SpatialAudioStore(store).read(), SpatialAudioMode.off);
      await c.read(spatialAudioProvider.notifier).setMode(SpatialAudioMode.auto);
      expect(host.downmix, isFalse);
    });

    test('should_keepTheNewChoice_when_madeWhileLoading', () async {
      await store.write(SpatialAudioStore.key, 'auto');
      c.listen(spatialAudioProvider, (_, _) {});
      final change = c.read(spatialAudioProvider.notifier).setMode(SpatialAudioMode.off);
      await pumpEventQueue();
      await change;
      expect(c.read(spatialAudioProvider).mode, SpatialAudioMode.off);
      expect(host.downmix, isTrue);
    });
  });
}
