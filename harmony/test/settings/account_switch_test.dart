import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/media_id.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/home/home_account_switcher.dart';
import 'package:yoin/playback/output/output_providers.dart';
import 'package:yoin/playback/output/playback_router.dart';
import 'package:yoin/playback/playback_controller.dart';
import 'package:yoin/settings/account_switch.dart';
import 'package:yoin/settings/settings_screen.dart';
import 'package:yoin/settings/settings_strings.dart';
import 'package:yoin/settings/settings_view_model.dart';

import '../playback/output/output_fakes.dart';
import '../support/fake_music_source.dart';
import '../support/fake_playback_engine.dart';

const _home = SubsonicCredentials(baseUrl: 'https://home.test', username: 'ann', password: 'p');
const _work = SubsonicCredentials(baseUrl: 'https://work.test', username: 'bob', password: 'p');

/// A source whose ping the test answers.
class _PingSource extends FakeMusicSource {
  _PingSource(this.answer);

  final Future<bool> Function() answer;

  @override
  Future<bool> ping() => answer();
}

void main() {
  late InMemoryStore store;
  late ProfileRepository repo;
  late List<FakeMusicSource> built;
  late Future<bool> Function() ping;
  late Future<void> Function() prepare;
  late int prepared;
  late Duration pingTimeout;

  List<Override> overrides() => [
    keyValueStoreProvider.overrideWithValue(store),
    secretStoreProvider.overrideWithValue(store),
    musicSourceFactoryProvider.overrideWithValue((_) {
      final s = _PingSource(() => ping());
      built.add(s);
      return s;
    }),
    accountSwitchPrepareProvider.overrideWithValue(() {
      prepared++;
      return prepare();
    }),
    accountSwitchPingTimeoutProvider.overrideWithValue(pingTimeout),
  ];

  Future<(Profile, Profile)> twoAccounts() async {
    final home = await repo.add(provider: MediaId.providerSubsonic, displayName: 'ann @ home.test', credentials: _home);
    final work = await repo.add(provider: MediaId.providerSubsonic, displayName: 'bob @ work.test', credentials: _work);
    return (home, work); // work is active (the last added).
  }

  Future<void> settle() async {
    for (var i = 0; i < 20; i++) {
      await Future<void>.delayed(Duration.zero);
    }
  }

  setUp(() {
    store = InMemoryStore();
    repo = ProfileRepository(store, store);
    built = [];
    ping = () async => true;
    prepare = () async {};
    prepared = 0;
    pingTimeout = const Duration(milliseconds: 50);
  });

  group('switcher', () {
    late ProviderContainer c;
    late List<AccountSwitchState> states;

    setUp(() {
      c = ProviderContainer(overrides: overrides());
      states = [];
      c.listen(accountSwitcherProvider, (_, next) => states.add(next), fireImmediately: true);
    });
    tearDown(() => c.dispose());

    AccountSwitcher switcher() => c.read(accountSwitcherProvider.notifier);

    test('should_commitThePingedSource_when_theServerAnswers', () async {
      final (home, _) = await twoAccounts();
      await c.read(activeSourceProvider.future);
      built.clear();

      await switcher().switchTo(home.id);

      expect(c.read(accountSwitcherProvider), isA<AccountSwitchIdle>());
      expect((await repo.activeProfile())!.id, home.id);
      // The source that answered is the one in use: no second build.
      expect(built, hasLength(1));
      expect(identical(await c.read(activeSourceProvider.future), built.single), isTrue);
      expect(built.single.disposed, isFalse);
      expect(prepared, 1);
    });

    test('should_passThroughPreparingThenConnecting_when_switching', () async {
      final (home, _) = await twoAccounts();
      await switcher().switchTo(home.id);
      expect(states, [
        isA<AccountSwitchIdle>(),
        AccountSwitching(home.id, AccountSwitchStage.preparing),
        AccountSwitching(home.id, AccountSwitchStage.connecting),
        isA<AccountSwitchIdle>(),
      ]);
    });

    test('should_failAndStayOnTheOldAccount_when_theSpeakerRefusesStop', () async {
      final (home, work) = await twoAccounts();
      prepare = () async => throw const SpeakerStopRefused();

      await switcher().switchTo(home.id);

      final state = c.read(accountSwitcherProvider);
      expect(state, isA<AccountSwitchFailed>());
      expect((state as AccountSwitchFailed).profileId, home.id);
      expect(state.failure, isA<SpeakerStopFailure>());
      expect((await repo.activeProfile())!.id, work.id);
      // Never got as far as the target's server.
      expect(built.whereType<_PingSource>(), isEmpty);
    });

    test('should_failWithTheSourcesLine_when_thePingFails', () async {
      final (home, work) = await twoAccounts();
      ping = () async => throw const SourceAuthException('Wrong username or password', code: 40);

      await switcher().switchTo(home.id);

      final state = c.read(accountSwitcherProvider) as AccountSwitchFailed;
      expect(state.failure, isA<SourceFailure>());
      expect(
        accountSwitchFailureText(const SettingsStrings.forLanguage(chinese: false), state.failure),
        'Wrong username or password',
      );
      expect((await repo.activeProfile())!.id, work.id);
      // The source that failed is let go.
      expect(built.single.disposed, isTrue);
    });

    test('should_failNoAnswer_when_thePingTimesOut', () async {
      final (home, work) = await twoAccounts();
      ping = () => Completer<bool>().future;

      await switcher().switchTo(home.id);

      final state = c.read(accountSwitcherProvider) as AccountSwitchFailed;
      expect(state.failure, isA<NoAnswerFailure>());
      expect((await repo.activeProfile())!.id, work.id);
    });

    test('should_failInvalidCredentials_when_theTargetsCredentialsAreGone', () async {
      final (home, work) = await twoAccounts();
      await store.write(ProfileRepository.credentialsKey(home.id), null);

      await switcher().switchTo(home.id);

      final state = c.read(accountSwitcherProvider) as AccountSwitchFailed;
      expect(state.failure, isA<InvalidCredentialsFailure>());
      expect(
        accountSwitchFailureText(const SettingsStrings.forLanguage(chinese: false), state.failure),
        'Profile ann @ home.test has invalid credentials',
      );
      expect((await repo.activeProfile())!.id, work.id);
    });

    test('should_doNothing_when_switchingToTheAccountInUse', () async {
      final (_, work) = await twoAccounts();
      await switcher().switchTo(work.id);
      expect(states, [isA<AccountSwitchIdle>()]);
      expect(prepared, 0);
    });

    test('should_ignoreASecondSwitch_when_oneIsUnderWay', () async {
      final (home, work) = await twoAccounts();
      final gate = Completer<void>();
      prepare = () => gate.future;
      final first = switcher().switchTo(home.id);
      await settle();
      await switcher().switchTo(work.id);
      expect(prepared, 1);
      gate.complete();
      await first;
      expect((await repo.activeProfile())!.id, home.id);
    });

    test('should_clearTheError_when_acknowledged', () async {
      final (home, _) = await twoAccounts();
      prepare = () async => throw const SpeakerStopRefused();
      await switcher().switchTo(home.id);
      expect(c.read(accountSwitcherProvider), isA<AccountSwitchFailed>());
      switcher().acknowledgeError();
      expect(c.read(accountSwitcherProvider), isA<AccountSwitchIdle>());
    });

    test('should_acknowledgeItsOwnFailure_when_settledFromHome', () async {
      final (home, _) = await twoAccounts();
      prepare = () async => throw const SpeakerStopRefused();
      expect(await switcher().switchAndSettle(home.id), AccountSwitchOutcome.failed);
      // Settings never opens on a switch it didn't start.
      expect(c.read(accountSwitcherProvider), isA<AccountSwitchIdle>());
    });

    test('should_land_when_settledFromHome', () async {
      final (home, _) = await twoAccounts();
      expect(await switcher().switchAndSettle(home.id), AccountSwitchOutcome.landed);
    });

    test('should_waitItOut_when_aSwitchToTheSameAccountIsUnderWay', () async {
      final (home, _) = await twoAccounts();
      final gate = Completer<void>();
      prepare = () => gate.future;
      unawaited(switcher().switchTo(home.id));
      await settle();
      final settled = switcher().switchAndSettle(home.id);
      gate.complete();
      expect(await settled, AccountSwitchOutcome.landed);
    });

    test('should_beTurnedAway_when_aSwitchElsewhereIsUnderWay', () async {
      final (home, work) = await twoAccounts();
      final third = await repo.add(
        provider: MediaId.providerSubsonic,
        displayName: 'cy @ third.test',
        credentials: const SubsonicCredentials(baseUrl: 'https://third.test', username: 'cy', password: 'p'),
      );
      await repo.setActive(work.id);
      final gate = Completer<void>();
      prepare = () => gate.future;
      unawaited(switcher().switchTo(home.id));
      await settle();
      expect(await switcher().switchAndSettle(third.id), AccountSwitchOutcome.turnedAway);
      gate.complete();
    });

    test('should_leaveNoError_when_thePageGoesMidSwitch', () async {
      final (home, _) = await twoAccounts();
      final gate = Completer<void>();
      prepare = () => gate.future;
      final run = switcher().switchTo(home.id);
      await settle();
      switcher().releaseFromPage();
      gate.completeError(const SpeakerStopRefused());
      await run;
      expect(c.read(accountSwitcherProvider), isA<AccountSwitchIdle>());
    });

    test('should_sayTheSpeakerLine_when_theSpeakerRefusedStop', () {
      expect(
        accountSwitchFailureText(const SettingsStrings.forLanguage(chinese: false), const SpeakerStopFailure()),
        'The speaker did not confirm stopping. Retry before changing accounts.',
      );
      expect(
        accountSwitchFailureText(const SettingsStrings.forLanguage(chinese: true), const SpeakerStopFailure()),
        '音响未确认停止。请重试后再切换账号。',
      );
      expect(
        accountSwitchFailureText(
          const SettingsStrings.forLanguage(chinese: false),
          const NoAnswerFailure(accountSwitchPingTimeout),
        ),
        'Server did not respond within 8s',
      );
    });

    test('should_keepTheAccountInUse_when_removingItAndTheSpeakerRefusesStop', () async {
      final (_, work) = await twoAccounts();
      c.listen(settingsViewModelProvider, (_, _) {});
      await settle();
      prepare = () async => throw const SpeakerStopRefused();

      expect(await c.read(settingsViewModelProvider.notifier).remove(work.id), isFalse);
      expect((await repo.profiles()).map((p) => p.id), contains(work.id));
      expect((await repo.activeProfile())!.id, work.id);
    });
  });

  group('with the playback chain', () {
    test('should_stopPlaybackFirst_when_switching', () async {
      final engine = FakePlaybackEngine();
      final real = ProviderContainer(
        overrides: [
          keyValueStoreProvider.overrideWithValue(store),
          secretStoreProvider.overrideWithValue(store),
          playbackEngineProvider.overrideWithValue(engine),
          musicSourceFactoryProvider.overrideWithValue((_) => FakeMusicSource()),
        ],
      );
      addTearDown(real.dispose);
      final (home, _) = await twoAccounts();
      real.read(playbackControllerProvider);

      await real.read(accountSwitcherProvider.notifier).switchTo(home.id);

      expect(engine.calls, contains('stop'));
      expect((await repo.activeProfile())!.id, home.id);
    });

    test('should_failTheSwitchAndKeepTheSpeaker_when_itWontConfirmStop', () async {
      final clock = FakeClock();
      final speakers = <String, FakeRenderer>{};
      final native = MirrorNativeEngine();
      final router = testRouter(clock: clock, native: native, speakers: speakers);
      addTearDown(router.dispose);
      final c = ProviderContainer(
        overrides: [
          keyValueStoreProvider.overrideWithValue(store),
          secretStoreProvider.overrideWithValue(store),
          playbackEngineProvider.overrideWithValue(router),
          playbackRouterProvider.overrideWithValue(router),
          musicSourceFactoryProvider.overrideWithValue((_) => FakeMusicSource()),
        ],
      );
      addTearDown(c.dispose);
      final (home, work) = await twoAccounts();
      c.read(playbackControllerProvider);
      router.states.listen((_) {});
      await router.startQueue([streamItem(0), streamItem(1)], revision: 1, startIndex: 0);
      native.index = 0;
      native.echo(index: 0, isPlaying: true, positionMs: 30000, sampledAtMs: clock.now);
      await pumpEventQueue();
      await router.transferToDlna(fakeSpeaker('uuid:a'));
      speakers['uuid:a']!.ignoreStop = true;

      await c.read(accountSwitcherProvider.notifier).switchTo(home.id);

      final state = c.read(accountSwitcherProvider) as AccountSwitchFailed;
      expect(state.failure, isA<SpeakerStopFailure>());
      expect((await repo.activeProfile())!.id, work.id);
      expect(router.route.value, isA<DlnaOutput>());
    });

    test('should_sayItInTheSnackbar_when_aSwitchBegunOutsideSettingsFails', () async {
      final c = ProviderContainer(
        overrides: [...overrides(), playbackEngineProvider.overrideWithValue(FakePlaybackEngine())],
      );
      addTearDown(c.dispose);
      final (home, work) = await twoAccounts();
      final notices = <PlaybackNotice>[];
      c.read(playbackControllerProvider.notifier).notices.listen(notices.add);
      prepare = () async => throw const SpeakerStopRefused();

      expect(await switchHomeAccountIn(c, home.id), isFalse);
      await settle();

      expect(notices.whereType<TextNotice>().map((n) => n.text), ["Couldn't switch account"]);
      expect(c.read(accountSwitcherProvider), isA<AccountSwitchIdle>());
      expect(c.read(homeSwitchTargetProvider), isNull);
      expect((await repo.activeProfile())!.id, work.id);
    });

    test('should_showTheTargetFromTheTap_when_switchingFromHome', () async {
      final c = ProviderContainer(overrides: overrides());
      addTearDown(c.dispose);
      final (home, _) = await twoAccounts();
      final gate = Completer<void>();
      prepare = () => gate.future;
      final run = switchHomeAccountIn(c, home.id);
      expect(c.read(homeSwitchTargetProvider), home.id);
      // A second pick while it runs starts nothing.
      expect(await switchHomeAccountIn(c, home.id), isFalse);
      gate.complete();
      expect(await run, isTrue);
      expect(c.read(homeSwitchTargetProvider), isNull);
    });
  });

  group('overlay', () {
    Future<void> pump(WidgetTester tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: overrides(),
          child: const MaterialApp(home: SettingsScreen()),
        ),
      );
      for (var i = 0; i < 10; i++) {
        await tester.pump(const Duration(milliseconds: 50));
      }
    }

    Future<void> frames(WidgetTester tester, [int n = 20]) async {
      for (var i = 0; i < n; i++) {
        await tester.pump(const Duration(milliseconds: 32));
      }
    }

    testWidgets('should_showEachStageThenGo_when_switchingFromSettings', (tester) async {
      late Profile home;
      await tester.runAsync(() async => home = (await twoAccounts()).$1);
      final stop = Completer<void>();
      final answer = Completer<bool>();
      prepare = () => stop.future;
      ping = () => answer.future;
      pingTimeout = const Duration(minutes: 1);
      await pump(tester);
      expect(find.text('Switching account'), findsNothing);

      await tester.tap(find.text('ann'));
      await frames(tester);
      expect(find.text('Switching account'), findsOneWidget);
      expect(find.text('Closing the current session…'), findsOneWidget);
      // It blocks the page: a tap on another card goes nowhere.
      expect(find.byType(ModalBarrier), findsWidgets);

      stop.complete();
      await tester.runAsync(settle);
      await frames(tester);
      expect(find.text('Connecting to ann @ home.test…'), findsOneWidget);

      answer.complete(true);
      await tester.runAsync(settle);
      await frames(tester, 40);
      expect(find.text('Switching account'), findsNothing);
      String? active;
      await tester.runAsync(() async => active = (await repo.activeProfile())!.id);
      expect(active, home.id);
    });

    testWidgets('should_showWhyAndStayOnTheOldAccount_when_theSpeakerRefusesStop', (tester) async {
      late Profile work;
      await tester.runAsync(() async => work = (await twoAccounts()).$2);
      prepare = () async => throw const SpeakerStopRefused();
      await pump(tester);

      await tester.tap(find.text('ann'));
      await tester.runAsync(settle);
      await frames(tester);
      expect(find.text("Couldn't switch account"), findsOneWidget);
      expect(find.text('The speaker did not confirm stopping. Retry before changing accounts.'), findsOneWidget);

      await tester.tap(find.text('OK'));
      await frames(tester, 40);
      expect(find.text("Couldn't switch account"), findsNothing);
      String? active;
      await tester.runAsync(() async => active = (await repo.activeProfile())!.id);
      expect(active, work.id);
    });

    testWidgets('should_dropTheError_when_settingsCloses', (tester) async {
      await tester.runAsync(twoAccounts);
      prepare = () async => throw const SpeakerStopRefused();
      final container = ProviderContainer(overrides: overrides());
      addTearDown(container.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: const MaterialApp(home: SettingsScreen()),
        ),
      );
      await frames(tester, 10);
      await tester.tap(find.text('ann'));
      await tester.runAsync(settle);
      await frames(tester);
      expect(container.read(accountSwitcherProvider), isA<AccountSwitchFailed>());

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: const MaterialApp(home: SizedBox()),
        ),
      );
      await tester.pump();
      expect(container.read(accountSwitcherProvider), isA<AccountSwitchIdle>());
    });
  });
}
