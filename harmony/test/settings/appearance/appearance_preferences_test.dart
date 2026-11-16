import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/platform/window/display_corners.dart';
import 'package:yoin/settings/appearance/screen_corner_control.dart';
import 'package:yoin/settings/appearance/screen_corners.dart';
import 'package:yoin/theme/tokens.dart';
import 'package:yoin/ui/component/seam/seam_style.dart';

class _FakeDisplay implements DisplayCorners {
  _FakeDisplay(this.value);

  double? value;
  int reads = 0;

  @override
  Future<double?> cornerRadius() async {
    reads++;
    return value;
  }
}

Future<void> _settle() async {
  for (var i = 0; i < 5; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late InMemoryStore store;
  late _FakeDisplay display;

  ProviderContainer container() {
    final c = ProviderContainer(
      overrides: [keyValueStoreProvider.overrideWithValue(store), displayCornersProvider.overrideWithValue(display)],
    );
    addTearDown(c.dispose);
    return c;
  }

  setUp(() {
    store = InMemoryStore();
    display = _FakeDisplay(null);
  });

  group('scroll edge style', () {
    test('should_fallBackToTide_when_keyUnknownOrMissing', () {
      expect(SeamTopStyle.fromKey(null), SeamTopStyle.tide);
      expect(SeamTopStyle.fromKey('halftone'), SeamTopStyle.tide);
      expect(SeamTopStyle.fromKey('cookie'), SeamTopStyle.cookie);
      expect([for (final s in SeamTopStyle.values) s.key], ['tide', 'dots', 'cookie']);
    });

    test('should_liftTextOnlyFromBrokenPrints_when_styleChosen', () {
      expect(SeamTopStyle.tide.liftsText, isFalse);
      expect(SeamTopStyle.dots.liftsText, isTrue);
      expect(SeamTopStyle.cookie.liftsText, isTrue);
    });

    test('should_readAndroidSyncedKey_when_stored', () async {
      store.values['seam_top_style'] = 'dots';
      final c = container();
      expect(c.read(seamTopStyleProvider), SeamTopStyle.tide);
      await _settle();
      expect(c.read(seamTopStyleProvider), SeamTopStyle.dots);
    });

    test('should_storeUnderSyncedKey_when_selected', () async {
      final c = container();
      await c.read(seamTopStyleProvider.notifier).select(SeamTopStyle.cookie);
      expect(c.read(seamTopStyleProvider), SeamTopStyle.cookie);
      expect(store.values[SeamTopStylePreference.key], 'cookie');
      expect(SeamTopStylePreference.key, 'seam_top_style');
    });

    test('should_keepChoice_when_loadFinishesAfterIt', () async {
      store.values['seam_top_style'] = 'dots';
      final c = container();
      await c.read(seamTopStyleProvider.notifier).select(SeamTopStyle.cookie);
      await _settle();
      expect(c.read(seamTopStyleProvider), SeamTopStyle.cookie);
    });

    test('should_storeDefaultPick_when_pickedBeforeStoredValueLoaded', () async {
      store.values['seam_top_style'] = 'dots';
      final c = container();
      // The tide shows (the default) while 'dots' is still loading: picking it is a choice.
      await c.read(seamTopStyleProvider.notifier).select(SeamTopStyle.tide);
      await _settle();
      expect(c.read(seamTopStyleProvider), SeamTopStyle.tide);
      expect(store.values['seam_top_style'], 'tide');
    });

    test('should_ignoreUnknownSyncedValue_when_applied', () async {
      final c = container();
      expect(await c.read(seamTopStyleProvider.notifier).applyKey('sparkles'), isFalse);
      expect(c.read(seamTopStyleProvider), SeamTopStyle.tide);
      expect(await c.read(seamTopStyleProvider.notifier).applyKey('dots'), isTrue);
      expect(c.read(seamTopStyleProvider), SeamTopStyle.dots);
    });
  });

  group('screen corners', () {
    test('should_preferUserThenDisplayThenFallback_when_resolvingRadius', () {
      expect(const ScreenCornersState().radius, ScreenCornerTokens.fallback);
      expect(const ScreenCornersState(device: 40).radius, 40);
      expect(const ScreenCornersState(user: 12, device: 40).radius, 12);
      expect(const ScreenCornersState(user: 12, device: 40).screenDefault, 40);
      expect(const ScreenCornersState(device: 40).following, isTrue);
    });

    test('should_followDisplay_when_nothingStored', () async {
      display.value = 47.5;
      final c = container()..read(screenCornersProvider);
      await _settle();
      final state = c.read(screenCornersProvider);
      expect(state.device, 47.5);
      expect(state.following, isTrue);
      expect(c.read(screenCornerRadiusProvider), 47.5);
    });

    test('should_clampDisplayReading_when_outOfRange', () async {
      display.value = 120;
      final c = container()..read(screenCornersProvider);
      await _settle();
      expect(c.read(screenCornersProvider).device, ScreenCornerTokens.max);
    });

    test('should_readStoredValue_when_userSetOne', () async {
      store.values[ScreenCornersPreference.key] = '22.0';
      display.value = 40;
      final c = container()..read(screenCornersProvider);
      await _settle();
      expect(c.read(screenCornerRadiusProvider), 22);
      expect(c.read(screenCornersProvider).following, isFalse);
    });

    test('should_notWriteEachFrame_when_sliderMoves', () async {
      final c = container()..read(screenCornersProvider);
      await _settle();
      final notifier = c.read(screenCornersProvider.notifier);
      await notifier.select(30, persist: false);
      expect(c.read(screenCornerRadiusProvider), 30);
      expect(store.values.containsKey(ScreenCornersPreference.key), isFalse);
      await notifier.commit();
      expect(store.values[ScreenCornersPreference.key], '30.0');
    });

    test('should_forgetUserValue_when_matchScreen', () async {
      store.values[ScreenCornersPreference.key] = '22.0';
      display.value = 36;
      final c = container()..read(screenCornersProvider);
      await _settle();
      await c.read(screenCornersProvider.notifier).select(null);
      expect(c.read(screenCornersProvider).following, isTrue);
      expect(c.read(screenCornerRadiusProvider), 36);
      expect(store.values.containsKey(ScreenCornersPreference.key), isFalse);
    });

    test('should_readDisplayAgain_when_refreshed', () async {
      display.value = 30;
      final c = container()..read(screenCornersProvider);
      await _settle();
      display.value = 44;
      await c.read(screenCornersProvider.notifier).refreshDevice();
      expect(c.read(screenCornersProvider).device, 44);
      expect(display.reads, greaterThanOrEqualTo(2));
    });
  });

  group('corner bar', () {
    test('should_mapEndsToRange_when_draggedAcross', () {
      expect(screenCornerValueAt(0, 300, 56), ScreenCornerTokens.min);
      expect(screenCornerValueAt(300, 300, 56), ScreenCornerTokens.max);
      expect(screenCornerValueAt(150, 300, 56), 32);
    });

    test('should_pickHaptic_when_valueSteps', () {
      expect(screenCornerStepFor(30, 30.4), ScreenCornerStep.reported);
      expect(screenCornerStepFor(0, null), ScreenCornerStep.end);
      expect(screenCornerStepFor(64, null), ScreenCornerStep.end);
      expect(screenCornerStepFor(16, null), ScreenCornerStep.segment);
      expect(screenCornerStepFor(17, null), ScreenCornerStep.tick);
    });
  });
}
