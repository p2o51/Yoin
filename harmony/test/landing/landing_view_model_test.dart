import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/media_id.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/home/home_section.dart';
import 'package:yoin/landing/landing_models.dart';
import 'package:yoin/landing/landing_store.dart';
import 'package:yoin/landing/landing_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../support/fake_music_source.dart';

void main() {
  late InMemoryStore store;

  ProviderContainer container() {
    final c = ProviderContainer(
      overrides: [
        keyValueStoreProvider.overrideWithValue(store),
        secretStoreProvider.overrideWithValue(store),
        musicSourceFactoryProvider.overrideWithValue((_) => FakeMusicSource()),
      ],
    );
    addTearDown(c.dispose);
    return c;
  }

  Future<void> settle() async {
    for (var i = 0; i < 20; i++) {
      await Future<void>.delayed(Duration.zero);
    }
  }

  Future<Profile> addAccount(ProviderContainer c, String user) async {
    final profile = await c
        .read(profileRepositoryProvider)
        .add(
          provider: MediaId.providerSubsonic,
          displayName: '$user @ music.test',
          credentials: SubsonicCredentials(baseUrl: 'https://music.test', username: user, password: 'p'),
        );
    c.invalidate(activeSourceProvider);
    await settle();
    return profile;
  }

  setUp(() => store = InMemoryStore());

  const firstRun = LandingSession(LandingMode.firstRun, 1);
  const rerun = LandingSession(LandingMode.rerun, 2);

  test('should_stepForwardAndBack_when_nextAndBackCalled', () async {
    final c = container();
    c.listen(landingViewModelProvider(firstRun), (_, _) {});
    final vm = c.read(landingViewModelProvider(firstRun).notifier);
    expect(c.read(landingViewModelProvider(firstRun)).step, LandingStep.hello);
    vm.back();
    expect(c.read(landingViewModelProvider(firstRun)).index, 0);
    vm
      ..next()
      ..next();
    expect(c.read(landingViewModelProvider(firstRun)).step, LandingStep.about);
    vm.back();
    expect(c.read(landingViewModelProvider(firstRun)).step, LandingStep.pick);
  });

  test('should_addAndRemoveConnectSteps_when_servicesToggled', () async {
    final c = container();
    c.listen(landingViewModelProvider(firstRun), (_, _) {});
    final vm = c.read(landingViewModelProvider(firstRun).notifier);
    vm.toggleService(LandingService.appleMusic);
    expect(c.read(landingViewModelProvider(firstRun)).steps, contains(LandingStep.appleMusic));
    vm.toggleService(LandingService.appleMusic);
    expect(c.read(landingViewModelProvider(firstRun)).steps, isNot(contains(LandingStep.appleMusic)));
  });

  test('should_gainHomeStepAndKeepTheStep_when_anAccountIsAddedOnAConnectStep', () async {
    final c = container();
    c.listen(landingViewModelProvider(firstRun), (_, _) {});
    await settle();
    final vm = c.read(landingViewModelProvider(firstRun).notifier);
    vm.toggleService(LandingService.subsonic);
    vm.goTo(LandingStep.subsonic);
    expect(vm.profilesAtStart, isEmpty);

    final added = await addAccount(c, 'ann');
    final state = c.read(landingViewModelProvider(firstRun));
    expect(state.hasAccount, isTrue);
    expect(state.step, LandingStep.subsonic);
    expect(state.steps, contains(LandingStep.home));
    expect(vm.createdProfileIds, {added.id});
  });

  test('should_writeTheLayoutToAccountsAddedHere_when_finished', () async {
    final c = container();
    c.listen(landingViewModelProvider(firstRun), (_, _) {});
    await settle();
    final vm = c.read(landingViewModelProvider(firstRun).notifier);
    final added = await addAccount(c, 'ann');
    vm.setSectionEnabled(HomeSection.rediscover, false);
    vm.moveShownSection(1, 0);

    expect(await vm.finish(), isTrue);
    final raw = await store.read(HomeLayoutNotifier.keyFor(MediaId.providerSubsonic, added.id));
    final layout = HomeLayout.fromJson((jsonDecode(raw!) as Map).cast<String, Object?>());
    expect(layout.sections.first.id, HomeSection.jumpBackIn.id);
    expect(layout.sections.firstWhere((p) => p.id == HomeSection.rediscover.id).enabled, isFalse);
    expect(await KeyValueLandingStore(store).isDone(), isTrue);
    expect(c.read(landingViewModelProvider(firstRun)).finishing, isTrue);
    // A second press while it runs does nothing.
    expect(await vm.finish(), isFalse);
  });

  test('should_leaveAccountsThatWereThereAlone_when_finished', () async {
    final c = container();
    final before = await addAccount(c, 'old');
    c.listen(landingViewModelProvider(rerun), (_, _) {});
    await settle();
    final vm = c.read(landingViewModelProvider(rerun).notifier);
    expect(vm.profilesAtStart, {before.id});
    final added = await addAccount(c, 'new');
    // The new account is the active one now: the old one is untouched.
    vm.setSectionEnabled(HomeSection.activities, false);
    await vm.finish();
    expect(await store.read(HomeLayoutNotifier.keyFor(MediaId.providerSubsonic, before.id)), isNull);
    expect(await store.read(HomeLayoutNotifier.keyFor(MediaId.providerSubsonic, added.id)), isNotNull);
  });

  test('should_startFromAndWriteTheActiveLayout_when_rerun', () async {
    final c = container();
    final account = await addAccount(c, 'ann');
    final key = HomeLayoutNotifier.keyFor(MediaId.providerSubsonic, account.id);
    final saved = withSectionEnabled(HomeLayout.defaultLayout, HomeSection.memoryGrid, false);
    await store.write(key, jsonEncode(saved.toJson()));

    c.listen(landingViewModelProvider(rerun), (_, _) {});
    await settle();
    final vm = c.read(landingViewModelProvider(rerun).notifier);
    final state = c.read(landingViewModelProvider(rerun));
    expect(state.hasAccount, isTrue);
    expect(state.layout.sections.firstWhere((p) => p.id == HomeSection.memoryGrid.id).enabled, isFalse);

    vm.setSectionEnabled(HomeSection.memoryGrid, true);
    await vm.finish();
    // Back to the default: the stored row is cleared, not rewritten.
    expect(await store.read(key), isNull);
  });

  test('should_keepTheEdit_when_theStoredLayoutArrivesAfterIt', () async {
    final c = container();
    final account = await addAccount(c, 'ann');
    final key = HomeLayoutNotifier.keyFor(MediaId.providerSubsonic, account.id);
    final saved = withSectionEnabled(HomeLayout.defaultLayout, HomeSection.memoryGrid, false);
    await store.write(key, jsonEncode(saved.toJson()));

    c.listen(landingViewModelProvider(rerun), (_, _) {});
    // Edited before the stored layout has been read.
    c.read(landingViewModelProvider(rerun).notifier).setSectionEnabled(HomeSection.rediscover, false);
    await settle();
    final sections = c.read(landingViewModelProvider(rerun)).layout.sections;
    expect(sections.firstWhere((p) => p.id == HomeSection.rediscover.id).enabled, isFalse);
    expect(sections.firstWhere((p) => p.id == HomeSection.memoryGrid.id).enabled, isTrue);
  });

  test('should_listOnlySectionsThisBuildDraws_when_homeStepShown', () {
    final shown = landingShownSections(HomeLayout.defaultLayout);
    expect(shown.map((p) => p.section), isNot(contains(HomeSection.otherProfiles)));
    expect(shown.map((p) => p.section), isNot(contains(HomeSection.lyricHighlight)));
    expect(shown.length, HomeSection.values.where((s) => s.availableInV1).length);
  });

  test('should_keepHiddenSectionsInPlace_when_shownRowsMove', () async {
    final c = container();
    c.listen(landingViewModelProvider(firstRun), (_, _) {});
    final vm = c.read(landingViewModelProvider(firstRun).notifier);
    final shown = landingShownSections(c.read(landingViewModelProvider(firstRun)).layout);
    // Move the last shown row (All Memories) to the top.
    vm.moveShownSection(shown.length - 1, 0);
    final sections = c.read(landingViewModelProvider(firstRun)).layout.sections;
    expect(sections.first.id, HomeSection.memoryGrid.id);
    expect(sections.length, HomeLayout.defaultLayout.sections.length);
  });

  group('store', () {
    test('should_rememberDone_when_marked', () async {
      final landing = KeyValueLandingStore(store);
      expect(await landing.isDone(), isFalse);
      await landing.markDone();
      expect(await landing.isDone(), isTrue);
      expect(store.values[KeyValueLandingStore.key], 'true');
    });
  });
}
