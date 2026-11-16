import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/media_id.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/landing/landing_host.dart';
import 'package:yoin/landing/landing_mascot.dart';
import 'package:yoin/landing/landing_store.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/settings_screen.dart';
import 'package:yoin/settings/subsonic_setup_view_model.dart';
import 'package:yoin/shell/shell_state.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import '../support/fake_music_source.dart';

void main() {
  late InMemoryStore store;
  late ProviderContainer container;

  final shellFocus = FocusNode(debugLabel: 'shell');
  tearDownAll(shellFocus.dispose);

  Future<void> pumpApp(WidgetTester tester, {Size size = const Size(400, 860), bool reduced = true}) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    container = ProviderContainer(
      overrides: [
        keyValueStoreProvider.overrideWithValue(store),
        secretStoreProvider.overrideWithValue(store),
        musicSourceFactoryProvider.overrideWithValue((_) => FakeMusicSource()),
      ],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          navigatorKey: appNavigatorKey,
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context).copyWith(disableAnimations: reduced),
            child: YoinWindowScope.measured(child: LandingHost(child: child!)),
          ),
          home: Scaffold(
            body: Center(
              child: Focus(focusNode: shellFocus, child: const Text('shell')),
            ),
          ),
        ),
      ),
    );
  }

  /// The store and the account list answer in microtasks: let them, then settle.
  Future<void> settle(WidgetTester tester) async {
    for (var i = 0; i < 6; i++) {
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await tester.pump();
    }
    await tester.pumpAndSettle();
  }

  setUp(() => store = InMemoryStore());

  testWidgets('should_showTheLanding_when_firstLaunchWithoutAccount', (tester) async {
    await pumpApp(tester);
    await settle(tester);
    expect(find.text("Hi, I'm Yoin."), findsOneWidget);
    expect(find.text('Get started'), findsOneWidget);
    expect(find.byType(LandingMascot), findsOneWidget);
    // The shell waits offstage.
    expect(find.text('shell'), findsNothing);
  });

  testWidgets('should_neverShow_when_alreadyDone', (tester) async {
    store.values[KeyValueLandingStore.key] = 'true';
    await pumpApp(tester);
    await settle(tester);
    expect(find.text('Get started'), findsNothing);
    expect(find.text('shell'), findsOneWidget);
  });

  testWidgets('should_skipAndRememberIt_when_anAccountAlreadyExists', (tester) async {
    await tester.runAsync(
      () => ProfileRepository(store, store).add(
        provider: MediaId.providerSubsonic,
        displayName: 'ann @ music.test',
        credentials: const SubsonicCredentials(baseUrl: 'https://music.test', username: 'ann', password: 'p'),
      ),
    );
    await pumpApp(tester);
    await settle(tester);
    expect(find.text('Get started'), findsNothing);
    expect(find.text('shell'), findsOneWidget);
    expect(store.values[KeyValueLandingStore.key], 'true');
  });

  testWidgets('should_refuseNext_when_nothingPicked', (tester) async {
    await pumpApp(tester);
    await settle(tester);
    await tester.tap(find.text('Get started'));
    await tester.pumpAndSettle();
    expect(find.text('What do you listen with?'), findsOneWidget);
    await tester.tap(find.text('Next'));
    await tester.pumpAndSettle();
    // Still picking.
    expect(find.text('What do you listen with?'), findsOneWidget);
    expect(find.text('Subsonic'), findsOneWidget);
  });

  testWidgets('should_walkToTheConnectStepAndBack_when_aServiceIsPicked', (tester) async {
    await pumpApp(tester);
    await settle(tester);
    await tester.tap(find.text('Get started'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Subsonic'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Next'));
    await tester.pumpAndSettle();
    expect(find.text("Here's my part, and what stays with your service."), findsOneWidget);
    expect(find.text('A Subsonic-compatible server'), findsOneWidget);
    await tester.tap(find.text('Next'));
    await tester.pumpAndSettle();
    expect(find.text("Where's your server?"), findsOneWidget);
    expect(find.text('Server address'), findsOneWidget);
    // Not connected yet: the primary button skips.
    expect(find.text('Skip'), findsOneWidget);

    // System back steps back through the same path as the button.
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text("Here's my part, and what stays with your service."), findsOneWidget);
    await tester.tap(find.bySemanticsLabel('Back'));
    await tester.pumpAndSettle();
    expect(find.text('What do you listen with?'), findsOneWidget);
  });

  testWidgets('should_connectAndCheer_when_subsonicFormSubmitted', (tester) async {
    await pumpApp(tester);
    await settle(tester);
    final vm = container.read(subsonicSetupViewModelProvider.notifier);
    expect(vm, isNotNull);
    await tester.tap(find.text('Get started'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Subsonic'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Next'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Next'));
    await tester.pumpAndSettle();
    final fields = find.byType(EditableText);
    await tester.enterText(fields.at(0), 'music.test');
    await tester.enterText(fields.at(1), 'ann');
    await tester.enterText(fields.at(2), 'secret');
    await tester.pump();
    await tester.tap(find.text('Connect'));
    await settle(tester);
    expect(find.text('Connected'), findsOneWidget);
    // The account exists: Next instead of Skip, and Home's step is in the flow.
    await tester.tap(find.text('Next'));
    await tester.pumpAndSettle();
    expect(find.text('What goes on Home?'), findsOneWidget);
    expect(find.text('Jump Back In'), findsOneWidget);
    for (var i = 0; i < 3; i++) {
      await tester.tap(find.text('Next'));
      await tester.pumpAndSettle();
    }
    expect(find.text('All set.'), findsOneWidget);
    expect(find.text('ann @ music.test'), findsOneWidget);
    expect(find.text('In use'), findsOneWidget);
  });

  testWidgets('should_closeAndRemember_when_openYoinPressedWithoutAccount', (tester) async {
    await pumpApp(tester);
    await settle(tester);
    await tester.tap(find.text('Get started'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Apple Music'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Next'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Next'));
    await tester.pumpAndSettle();
    expect(find.text('Apple Music needs a developer token.'), findsOneWidget);
    expect(find.text("Yoin's token service"), findsOneWidget);
    await tester.tap(find.text('Skip'));
    await tester.pumpAndSettle();
    // The scroll edge's style, then the screen's corners (LandingEdgeStep, ScreenCornerControl).
    expect(find.text('When you scroll, how should things slip under the top bar?'), findsOneWidget);
    expect(find.text('Cookie wave'), findsWidgets);
    await tester.tap(find.text('Next'));
    await tester.pumpAndSettle();
    expect(find.text("How round are your screen's corners?"), findsOneWidget);
    expect(find.text('Match screen · 28 dp'), findsOneWidget);
    await tester.tap(find.text('Next'));
    await tester.pumpAndSettle();
    expect(find.text('No account yet.'), findsOneWidget);
    expect(find.text('No accounts connected'), findsOneWidget);
    await tester.tap(find.text('Open Yoin'));
    await settle(tester);
    expect(find.text('Open Yoin'), findsNothing);
    expect(find.text('shell'), findsOneWidget);
    expect(store.values[KeyValueLandingStore.key], 'true');
  });

  testWidgets('should_runAgainAndCloseFromTheFirstStep_when_welcomeGuideRequested', (tester) async {
    store.values[KeyValueLandingStore.key] = 'true';
    await pumpApp(tester);
    await settle(tester);
    expect(find.text('Get started'), findsNothing);

    container.read(landingRerunProvider.notifier).request();
    await settle(tester);
    expect(find.text('Get started'), findsOneWidget);
    // A re-run's first step: back closes the landing.
    await tester.binding.handlePopRoute();
    await settle(tester);
    expect(find.text('Get started'), findsNothing);
    expect(find.text('shell'), findsOneWidget);
  });

  testWidgets('should_runTheIntroAndLandTheMascot_when_motionAllowed', (tester) async {
    await pumpApp(tester, reduced: false);
    for (var i = 0; i < 4; i++) {
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await tester.pump();
    }
    // The fins fly in, the pill rises, the bubble pops: well within two seconds.
    await tester.pump(const Duration(seconds: 2));
    await tester.pump(const Duration(seconds: 1));
    final mascot = tester.widget<LandingMascot>(find.byType(LandingMascot));
    expect(mascot.state.alpha.value, closeTo(1, 0.01));
    for (final fin in mascot.state.fins) {
      expect(fin.dx.value, closeTo(0, 0.5));
      expect(fin.scale.value, closeTo(1, 0.01));
    }
    expect(find.text('Get started'), findsOneWidget);
    // The landing keeps breathing: leave it for the next test's teardown.
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('should_returnToTheShellAndAskForARerun_when_welcomeGuideTapped', (tester) async {
    store.values[KeyValueLandingStore.key] = 'true';
    await pumpApp(tester, size: const Size(400, 1600));
    await settle(tester);
    appNavigatorKey.currentState!.push(MaterialPageRoute<void>(builder: (_) => const SettingsScreen()));
    await settle(tester);
    expect(find.text('Welcome guide'), findsOneWidget);

    await tester.tap(find.text('Welcome guide'));
    await settle(tester);
    expect(container.read(landingRerunProvider), 1);
    expect(find.byType(SettingsScreen), findsNothing);
    expect(find.text('Get started'), findsOneWidget);
  });

  testWidgets('should_keepTheShellOutOfFocus_when_theLandingShows', (tester) async {
    await pumpApp(tester);
    await settle(tester);
    expect(find.text('Get started'), findsOneWidget);
    // The shell's shortcuts (play, Settings, search) can't hear the keyboard under the landing.
    expect(shellFocus.canRequestFocus, isFalse);

    await tester.tap(find.text('Get started'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Apple Music'));
    await tester.pumpAndSettle();
    for (var i = 0; i < 2; i++) {
      await tester.tap(find.text('Next'));
      await tester.pumpAndSettle();
    }
    await tester.tap(find.text('Skip'));
    await tester.pumpAndSettle();
    // Past the scroll edge and the screen corners.
    for (var i = 0; i < 2; i++) {
      await tester.tap(find.text('Next'));
      await tester.pumpAndSettle();
    }
    await tester.tap(find.text('Open Yoin'));
    await settle(tester);
    expect(find.text('Open Yoin'), findsNothing);
    expect(shellFocus.canRequestFocus, isTrue);
  });

  testWidgets('should_stepBack_when_escapePressed', (tester) async {
    await pumpApp(tester);
    await settle(tester);
    await tester.tap(find.text('Get started'));
    await tester.pumpAndSettle();
    expect(find.text('What do you listen with?'), findsOneWidget);
    await tester.sendKeyEvent(LogicalKeyboardKey.escape);
    await tester.pumpAndSettle();
    expect(find.text("Hi, I'm Yoin."), findsOneWidget);
  });

  testWidgets('should_notRemember_when_theAccountListCannotBeRead', (tester) async {
    store.values[ProfileRepository.profilesKey] = 'not json';
    await pumpApp(tester);
    await settle(tester);
    expect(find.text('Get started'), findsNothing);
    expect(find.text('shell'), findsOneWidget);
    expect(store.values[KeyValueLandingStore.key], isNull);
  });
}
