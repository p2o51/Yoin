import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/home/home_account_switcher.dart';
import 'package:yoin/home/home_screen.dart';
import 'package:yoin/home/home_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/account_reconnect.dart';
import 'package:yoin/settings/settings_screen.dart';
import 'package:yoin/settings/subsonic_setup_screen.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import '../support/fake_playback_engine.dart';
import 'home_fakes.dart';

const _ann = SubsonicCredentials(baseUrl: 'https://home.test', username: 'ann', password: 'p');
const _den = SubsonicCredentials(baseUrl: 'https://den.test', username: 'den', password: 'p');

void main() {
  setUp(() {
    HomeViewModel.clearProcessCache();
    HomeScreen.resetProcessState();
  });

  late InMemoryStore values;
  late InMemoryStore secrets;
  late ProfileRepository repo;

  /// Home over the real active-source provider: an account whose stored
  /// credentials are gone has no source, as on a phone restored without them.
  Future<ProviderContainer> pumpHome(WidgetTester tester, Future<void> Function() accounts) async {
    tester.view.physicalSize = const Size(400, 860);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    values = InMemoryStore();
    secrets = InMemoryStore();
    repo = ProfileRepository(values, secrets);
    await accounts();
    final container = ProviderContainer(
      overrides: [
        keyValueStoreProvider.overrideWithValue(values),
        secretStoreProvider.overrideWithValue(secrets),
        playbackEngineProvider.overrideWithValue(FakePlaybackEngine()),
        musicSourceFactoryProvider.overrideWithValue((_) => HomeFakeSource()),
        activityLogProvider.overrideWith((ref) async => MemoryActivityLog()),
        homeClockProvider.overrideWithValue(() => homeTestNow),
        homeActivityDebounceProvider.overrideWithValue(const Duration(milliseconds: 5)),
      ],
    );
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          theme: yoinTheme(Brightness.light),
          builder: (context, child) => YoinWindowScope.measured(child: child!),
          home: const Scaffold(body: HomeScreen()),
        ),
      ),
    );
    await settleFrames(tester, 20);
    return container;
  }

  Future<Profile> add(SubsonicCredentials credentials) => repo.add(
    provider: MediaId.providerSubsonic,
    displayName: '${credentials.username} @ host',
    credentials: credentials,
  );

  Future<void> lose(Profile p) => secrets.write(ProfileRepository.credentialsKey(p.id), null);

  Future<void> giveBack(Profile p, SubsonicCredentials credentials) =>
      secrets.write(ProfileRepository.credentialsKey(p.id), jsonEncode(credentials.toJson()));

  Future<void> done(WidgetTester tester, ProviderContainer c) async {
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(const Duration(seconds: 1));
    c.dispose();
  }

  Finder dot() => find.byKey(homeAccountReconnectDotKey);

  testWidgets('should_markAvatarWithDot_when_accountInUseHasNoSource', (tester) async {
    final semantics = tester.ensureSemantics();
    late Profile ann;
    final c = await pumpHome(tester, () async {
      ann = await add(_ann);
      await lose(ann);
    });
    expect(await c.read(activeNeedsReconnectProvider.future), isTrue);
    await settleFrames(tester);
    expect(dot(), findsOneWidget);
    // The dot is heard with the avatar.
    expect(find.bySemanticsLabel('Account: ann @ host, Account needs reconnecting'), findsOneWidget);
    // The error colour, on a ring of the header's.
    final colors = Theme.of(tester.element(dot())).colorScheme;
    final ring = tester.widget<Container>(dot()).decoration! as BoxDecoration;
    expect(ring.color, colors.surfaceContainer);
    final badge = tester.widget<DecoratedBox>(find.descendant(of: dot(), matching: find.byType(DecoratedBox)).last);
    expect((badge.decoration as BoxDecoration).color, colors.error);
    semantics.dispose();
    await done(tester, c);
  });

  testWidgets('should_wearNoDot_when_accountWorks', (tester) async {
    final c = await pumpHome(tester, () async => add(_ann));
    expect(await c.read(activeNeedsReconnectProvider.future), isFalse);
    await settleFrames(tester);
    expect(find.byType(HomeAccountButton), findsOneWidget);
    expect(dot(), findsNothing);
    expect(find.text('Reconnect'), findsNothing);
    await done(tester, c);
  });

  testWidgets('should_reconnectAndCloseCard_when_credentialsReadAgain', (tester) async {
    late Profile ann;
    final c = await pumpHome(tester, () async {
      ann = await add(_ann);
      await lose(ann);
    });
    await settleFrames(tester);
    await tester.tap(find.byType(HomeAccountButton));
    await settleFrames(tester);
    expect(find.text('Manage accounts'), findsOneWidget);
    expect(find.byKey(homeAccountReconnectKey), findsOneWidget);
    expect(find.text('Reconnect'), findsOneWidget);

    // The secret is back (say, the key store recovered): Reconnect reads it again.
    await giveBack(ann, _ann);
    await tester.tap(find.byKey(homeAccountReconnectKey));
    await settleFrames(tester, 40);

    // Fixed: the card went back into the avatar, which wears no dot now.
    expect(find.text('Manage accounts'), findsNothing);
    expect(await c.read(activeSourceProvider.future), isNotNull);
    expect(await c.read(activeNeedsReconnectProvider.future), isFalse);
    await settleFrames(tester);
    expect(dot(), findsNothing);
    await done(tester, c);
  });

  testWidgets('should_openAccountPage_when_reconnectCannotFix', (tester) async {
    late Profile ann;
    final c = await pumpHome(tester, () async {
      ann = await add(_ann);
      await lose(ann);
    });
    await settleFrames(tester);
    await tester.tap(find.byType(HomeAccountButton));
    await settleFrames(tester);
    await tester.tap(find.byKey(homeAccountReconnectKey));
    await settleFrames(tester, 40);

    // Still unreadable: the card closed and the account's own page opened, to enter them again.
    expect(find.text('Manage accounts'), findsNothing);
    expect(find.byType(SubsonicSetupScreen), findsOneWidget);
    expect(tester.widget<SubsonicSetupScreen>(find.byType(SubsonicSetupScreen)).profileId, ann.id);
    await done(tester, c);
  });

  testWidgets('should_openSettingsNotSwitch_when_unreadableAccountIsPickedByAccessibilityAction', (tester) async {
    final semantics = tester.ensureSemantics();
    final c = await pumpHome(tester, () async {
      final den = await add(_den);
      await lose(den);
      final ann = await add(_ann);
      await repo.setActive(ann.id);
    });
    await settleFrames(tester);
    // TalkBack's "Switch to …" on the avatar is the hold's pick: as on the card, Settings.
    final node = tester.getSemantics(find.byType(HomeAccountButton));
    final action = CustomSemanticsAction(label: 'Switch to den @ host');
    expect(node.getSemanticsData().customSemanticsActionIds, contains(CustomSemanticsAction.getIdentifier(action)));
    tester.binding.renderViews.first.owner!.semanticsOwner!.performAction(
      node.id,
      SemanticsAction.customAction,
      CustomSemanticsAction.getIdentifier(action),
    );
    await settleFrames(tester, 40);
    expect(find.byType(SettingsScreen), findsOneWidget);
    expect((await c.read(profileRepositoryProvider).activeProfile())!.displayName, 'ann @ host');
    semantics.dispose();
    await done(tester, c);
  });

  testWidgets('should_sayCredentialsMissingAndOpenSettings_when_otherAccountIsUnreadable', (tester) async {
    final c = await pumpHome(tester, () async {
      final den = await add(_den);
      await lose(den);
      final ann = await add(_ann);
      await repo.setActive(ann.id);
    });
    await settleFrames(tester);
    // The account in use works: no dot, no Reconnect.
    expect(dot(), findsNothing);
    await tester.tap(find.byType(HomeAccountButton));
    await settleFrames(tester);
    expect(find.text('Reconnect'), findsNothing);
    expect(find.text('Credentials missing'), findsOneWidget);

    // An account that can't be used now is recovered in Settings, not switched to.
    await tester.tap(find.text('Credentials missing'));
    await settleFrames(tester, 40);
    expect(find.byType(SettingsScreen), findsOneWidget);
    expect((await c.read(profileRepositoryProvider).activeProfile())!.displayName, 'ann @ host');
    await done(tester, c);
  });
}

Future<void> settleFrames(WidgetTester tester, [int n = 30]) async {
  for (var i = 0; i < n; i++) {
    await tester.pump(const Duration(milliseconds: 32));
  }
}
