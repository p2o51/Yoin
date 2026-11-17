import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/playback/replay_gain.dart';

/// Android ReplayGainTest, ported, plus HarmonyOS's AVPlayer volume (0–1).
void main() {
  const flac = ReplayGain(trackGainDb: -9.6, trackPeak: 0.9886);
  const butterfly = ReplayGain(trackGainDb: 1.0, trackPeak: 0.335);
  const albumTagged = ReplayGain(trackGainDb: -3, albumGainDb: -5, trackPeak: 0.5, albumPeak: 0.6);

  ReplayGainSettings settings(ReplayGainMode mode, {double preamp = 0}) =>
      ReplayGainSettings(mode: mode, preampDb: preamp);

  group('applied gain', () {
    test('should_applyTrackGain_when_modeIsTrack', () {
      final applied = appliedReplayGain(albumTagged, settings(ReplayGainMode.track));
      expect(applied.db, closeTo(-3, 1e-4));
      expect(applied.linear, closeTo(dbToLinear(-3), 1e-6));
      expect(applied.source, ReplayGainSource.track);
    });

    test('should_applyAlbumGain_when_modeIsAlbumAndSongHasIt', () {
      final applied = appliedReplayGain(albumTagged, settings(ReplayGainMode.album));
      expect(applied.db, closeTo(-5, 1e-4));
      expect(applied.source, ReplayGainSource.album);
    });

    test('should_fallBackToTrackGain_when_modeIsAlbumAndAlbumGainMissing', () {
      final applied = appliedReplayGain(flac, settings(ReplayGainMode.album));
      expect(applied.db, closeTo(-9.6, 1e-4));
      expect(applied.source, ReplayGainSource.albumFromTrack);
    });

    test('should_addPreamp_when_set', () {
      final applied = appliedReplayGain(flac, settings(ReplayGainMode.track, preamp: 4));
      expect(applied.db, closeTo(-5.6, 1e-4));
      expect(applied.peakLimited, isFalse);
    });

    test('should_limitGainToThePeak_when_peakTimesGainPassesFullScale', () {
      // +1 dB + 4 dB on a 0.335 peak is ×1.78: well under 1/0.335 = ×2.99, no limit.
      expect(appliedReplayGain(butterfly, settings(ReplayGainMode.track, preamp: 4)).peakLimited, isFalse);
      // +12 dB would be ×3.98: held at ×2.99 so the peak lands at 1.0.
      final loud = appliedReplayGain(butterfly, settings(ReplayGainMode.track, preamp: 11));
      expect(loud.peakLimited, isTrue);
      expect(loud.linear * 0.335, closeTo(1, 1e-5));
      expect(loud.db, closeTo(linearToDb(1 / 0.335), 1e-4));
    });

    test('should_limitWithTheTracksPeak_when_albumGainIsUsed', () {
      final applied = appliedReplayGain(
        const ReplayGain(albumGainDb: 6, trackPeak: 0.9, albumPeak: 0.99),
        settings(ReplayGainMode.album),
      );
      expect(applied.peakLimited, isTrue);
      expect(applied.linear, closeTo(1 / 0.9, 1e-5));
    });

    test('should_notLimit_when_noPeakIsKnown', () {
      final applied = appliedReplayGain(const ReplayGain(trackGainDb: 6), settings(ReplayGainMode.track));
      expect(applied.peakLimited, isFalse);
      expect(applied.linear, closeTo(dbToLinear(6), 1e-5));
    });

    test('should_leaveVolumeAlone_when_songHasNoTags', () {
      final preamp = settings(ReplayGainMode.album, preamp: 4);
      expect(appliedReplayGain(null, preamp), AppliedReplayGain.unity);
      expect(appliedReplayGain(const ReplayGain(fallbackGainDb: -6), preamp), AppliedReplayGain.unity);
      expect(
        appliedReplayGain(const ReplayGain(albumGainDb: -6), settings(ReplayGainMode.track, preamp: 4)),
        AppliedReplayGain.unity,
      );
    });

    test('should_leaveVolumeAlone_when_modeIsOff', () {
      expect(appliedReplayGain(flac, settings(ReplayGainMode.off, preamp: 4)), AppliedReplayGain.unity);
    });

    test('should_levelTheSurroundAlbum_when_modeIsAlbum', () {
      // 《再生（全景声）》: track gains only, about 10 dB apart; by album they meet.
      final s = settings(ReplayGainMode.album, preamp: 4);
      final flacLoudness = -9 + appliedReplayGain(flac, s).db;
      final butterflyLoudness = -19 + appliedReplayGain(butterfly, s).db;
      expect(flacLoudness, closeTo(butterflyLoudness, 1.5));
    });

    test('should_defaultToAlbumWithFourDbPreamp_when_nothingIsStored', () {
      expect(const ReplayGainSettings().mode, ReplayGainMode.album);
      expect(const ReplayGainSettings().preampDb, 4);
    });
  });

  group('player volume', () {
    test('should_turnTheGainIntoAVolume_when_itLowersTheSong', () {
      // −9.6 + 4 = −5.6 dB → ×0.525.
      expect(engineGain(flac, const ReplayGainSettings()), closeTo(dbToLinear(-5.6), 1e-6));
    });

    test('should_stopAtTheFilesOwnLevel_when_theGainWouldRaiseIt', () {
      // +1 + 4 dB is ×1.78 on Android; AVPlayer's volume ends at 1.
      expect(engineGain(butterfly, const ReplayGainSettings()), 1.0);
    });

    test('should_playAsItIs_when_untaggedOrOff', () {
      expect(engineGain(null, const ReplayGainSettings()), 1.0);
      expect(engineGain(flac, const ReplayGainSettings(mode: ReplayGainMode.off)), 1.0);
    });
  });

  group('preference', () {
    late InMemoryStore store;
    late ProviderContainer c;

    setUp(() {
      store = InMemoryStore();
      c = ProviderContainer(overrides: [keyValueStoreProvider.overrideWithValue(store)]);
      c.listen(replayGainProvider, (_, _) {});
    });
    tearDown(() => c.dispose());

    test('should_beNullThenDefault_when_nothingStored', () async {
      expect(c.read(replayGainProvider), isNull, reason: 'still loading');
      expect(await c.read(replayGainProvider.notifier).ready(), const ReplayGainSettings());
      expect(c.read(replayGainProvider), const ReplayGainSettings());
    });

    test('should_readTheStoredMode_when_oneWasChosen', () async {
      await store.write(ReplayGainStore.key, 'track');
      final fresh = ProviderContainer(overrides: [keyValueStoreProvider.overrideWithValue(store)]);
      addTearDown(fresh.dispose);
      expect((await fresh.read(replayGainProvider.notifier).ready()).mode, ReplayGainMode.track);
    });

    test('should_fallBackToDefault_when_storedValueUnknown', () async {
      await store.write(ReplayGainStore.key, 'loudest');
      expect((await const ReplayGainStore(_UnknownMode()).read()).mode, ReplayGainMode.album);
      final fresh = ProviderContainer(overrides: [keyValueStoreProvider.overrideWithValue(store)]);
      addTearDown(fresh.dispose);
      expect((await fresh.read(replayGainProvider.notifier).ready()).mode, ReplayGainMode.album);
    });

    test('should_storeAndPublish_when_modeChanges', () async {
      await c.read(replayGainProvider.notifier).setMode(ReplayGainMode.off);
      expect(c.read(replayGainProvider)!.mode, ReplayGainMode.off);
      expect(await store.read(ReplayGainStore.key), 'off');
    });

    test('should_keepTheChoice_when_madeWhileLoading', () async {
      final setting = c.read(replayGainProvider.notifier).setMode(ReplayGainMode.track);
      await setting;
      await pumpEventQueue();
      expect(c.read(replayGainProvider)!.mode, ReplayGainMode.track);
    });

    test('should_useTheDefault_when_noStorage', () async {
      final bare = ProviderContainer();
      addTearDown(bare.dispose);
      expect(await bare.read(replayGainProvider.notifier).ready(), const ReplayGainSettings());
    });
  });
}

/// A store holding a mode this build does not know.
final class _UnknownMode implements KeyValueStore {
  const _UnknownMode();
  @override
  Future<String?> read(String key) async => 'loudest';
  @override
  Future<void> write(String key, String? value) async {}
}
