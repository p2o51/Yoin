import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/platform/window/window_controls.dart';
import 'package:yoin/playback/keep_screen_on.dart';
import 'package:yoin/playback/now_playing_state.dart';
import 'package:yoin/playback/playback_controller.dart';

final class _Window implements WindowControls {
  final keepOn = <bool>[];
  @override
  Future<void> setKeepScreenOn(bool on) async => keepOn.add(on);
  @override
  Future<void> showToast(String message) async {}
}

/// Stands in for the playback chain: only `isPlaying` matters here.
class _Playback extends PlaybackController {
  @override
  NowPlayingState build() => const NowPlayingState();
  set playing(bool value) => state = state.copyWith(isPlaying: value);
}

void main() {
  late _Window window;
  late InMemoryStore store;
  late ProviderContainer c;

  setUp(() {
    window = _Window();
    store = InMemoryStore();
    c = ProviderContainer(overrides: [
      windowControlsProvider.overrideWithValue(window),
      keyValueStoreProvider.overrideWithValue(store),
      playbackControllerProvider.overrideWith(_Playback.new),
    ]);
    c.listen(nowPlayingKeepScreenOnProvider, (_, _) {});
  });
  tearDown(() => c.dispose());

  _Playback playback() => c.read(playbackControllerProvider.notifier) as _Playback;
  NowPlayingKeepScreenOn keep() => c.read(nowPlayingKeepScreenOnProvider.notifier);

  test('should_holdScreenOn_when_openChosenAndPlaying', () async {
    await c.read(keepScreenOnPreferenceProvider.notifier).set(true);
    playback().playing = true;
    keep().setKeepScreenOn(true);
    expect(c.read(nowPlayingKeepScreenOnProvider), isTrue);
    expect(window.keepOn, [true]);
    expect(store.values[KeepScreenOnPreference.storeKey], 'true');
  });

  test('should_releaseScreen_when_paused', () async {
    await c.read(keepScreenOnPreferenceProvider.notifier).set(true);
    playback().playing = true;
    keep().setKeepScreenOn(true);
    playback().playing = false;
    await pumpEventQueue();
    expect(c.read(nowPlayingKeepScreenOnProvider), isFalse);
    expect(window.keepOn, [true, false]);
  });

  test('should_releaseScreen_when_nowPlayingCloses', () async {
    await c.read(keepScreenOnPreferenceProvider.notifier).set(true);
    playback().playing = true;
    keep().setKeepScreenOn(true);
    keep().setKeepScreenOn(false);
    expect(window.keepOn, [true, false]);
  });

  test('should_leaveScreenAlone_when_userDidNotChooseIt', () async {
    playback().playing = true;
    keep().setKeepScreenOn(true);
    await pumpEventQueue();
    expect(c.read(nowPlayingKeepScreenOnProvider), isFalse);
    expect(window.keepOn, isEmpty);
  });

  test('should_readStoredChoice_when_built', () async {
    store.values[KeepScreenOnPreference.storeKey] = 'true';
    final fresh = ProviderContainer(overrides: [keyValueStoreProvider.overrideWithValue(store)]);
    addTearDown(fresh.dispose);
    fresh.listen(keepScreenOnPreferenceProvider, (_, _) {});
    await pumpEventQueue();
    expect(fresh.read(keepScreenOnPreferenceProvider), isTrue);
  });
}
