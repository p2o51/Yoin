import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/media_id.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/settings_screen.dart';
import 'package:yoin/settings/settings_view_model.dart';
import 'package:yoin/settings/subsonic_setup_screen.dart';
import 'package:yoin/settings/subsonic_setup_view_model.dart';

import '../support/fake_music_source.dart';

const _home = SubsonicCredentials(baseUrl: 'https://home.test', username: 'ann', password: 'secret');
const _work = SubsonicCredentials(baseUrl: 'https://work.test', username: 'bob', password: 'p');

void main() {
  late InMemoryStore store;
  late ProfileRepository repo;
  late List<SubsonicCredentials> built;

  List<Override> overrides() => [
        keyValueStoreProvider.overrideWithValue(store),
        secretStoreProvider.overrideWithValue(store),
        musicSourceFactoryProvider.overrideWithValue((credentials) {
          if (credentials is SubsonicCredentials) built.add(credentials);
          return FakeMusicSource();
        }),
      ];

  Future<void> settle() async {
    for (var i = 0; i < 20; i++) {
      await Future<void>.delayed(Duration.zero);
    }
  }

  Future<(Profile, Profile)> twoAccounts() async {
    final home = await repo.add(provider: MediaId.providerSubsonic, displayName: 'ann @ home.test', credentials: _home);
    final work = await repo.add(provider: MediaId.providerSubsonic, displayName: 'bob @ work.test', credentials: _work);
    return (home, work); // work is active.
  }

  setUp(() {
    store = InMemoryStore();
    repo = ProfileRepository(store, store);
    built = [];
  });

  group('view model', () {
    late ProviderContainer c;

    setUp(() => c = ProviderContainer(overrides: overrides()));
    tearDown(() => c.dispose());

    test('should_loadStoredCredentials_when_managing', () async {
      final (home, _) = await twoAccounts();
      c.listen(subsonicSetupProvider(home.id), (_, _) {});
      expect(c.read(subsonicSetupProvider(home.id)).loaded, isFalse);
      await settle();
      final state = c.read(subsonicSetupProvider(home.id));
      expect(state.loaded, isTrue);
      expect(state.isManaging, isTrue);
      expect(state.stored?.password, 'secret');
      expect(state.account?.title, 'ann');
      expect(state.account?.isActive, isFalse);
      expect(state.credentialsMissing, isFalse);
    });

    test('should_rewriteCredentialsAndName_when_saved', () async {
      final (home, work) = await twoAccounts();
      c.listen(subsonicSetupProvider(home.id), (_, _) {});
      await settle();
      await c
          .read(subsonicSetupProvider(home.id).notifier)
          .connect(address: 'music.home.test/', username: 'anna', password: 'new');
      await settle();

      expect(c.read(subsonicSetupProvider(home.id)).connected, isTrue);
      final stored = await repo.credentials(home.id) as SubsonicCredentials;
      expect(stored.baseUrl, 'https://music.home.test');
      expect(stored.username, 'anna');
      expect(stored.password, 'new');
      final profiles = await repo.profiles();
      expect(profiles, hasLength(2));
      expect(profiles.first.displayName, 'anna @ music.home.test');
      // Saving another account leaves the one in use alone.
      expect((await repo.activeProfile())!.id, work.id);
      expect(built.last.username, 'anna'); // probed before saving
    });

    test('should_keepStoredValues_when_probeFails', () async {
      final (home, _) = await twoAccounts();
      c.dispose();
      c = ProviderContainer(overrides: [
        keyValueStoreProvider.overrideWithValue(store),
        secretStoreProvider.overrideWithValue(store),
        musicSourceFactoryProvider.overrideWithValue((_) => FakeMusicSource(failWith: Exception('down'))),
      ]);
      c.listen(subsonicSetupProvider(home.id), (_, _) {});
      await settle();
      await c.read(subsonicSetupProvider(home.id).notifier).connect(address: 'x.test', username: 'z', password: 'z');
      expect(c.read(subsonicSetupProvider(home.id)).connected, isFalse);
      expect((await repo.credentials(home.id) as SubsonicCredentials).username, 'ann');
    });

    test('should_markCredentialsMissing_when_secretGone', () async {
      final (home, _) = await twoAccounts();
      await store.write(ProfileRepository.credentialsKey(home.id), null);
      c.listen(settingsViewModelProvider, (_, _) {});
      c.listen(subsonicSetupProvider(home.id), (_, _) {});
      await settle();

      final cards = (c.read(settingsViewModelProvider) as SettingsContent).accounts;
      final card = cards.firstWhere((a) => a.id == home.id);
      expect(card.credentialsMissing, isTrue);
      expect(card.tapOpensPage, isTrue);
      expect(cards.firstWhere((a) => a.id != home.id).tapOpensPage, isTrue); // the one in use
      expect(c.read(subsonicSetupProvider(home.id)).credentialsMissing, isTrue);
    });

    test('should_rereadSettings_when_otherAccountEdited', () async {
      final (home, _) = await twoAccounts();
      c.listen(settingsViewModelProvider, (_, _) {});
      c.listen(subsonicSetupProvider(home.id), (_, _) {});
      await settle();
      await c.read(subsonicSetupProvider(home.id).notifier).connect(address: 'home.test', username: 'anna', password: 'x');
      await settle();
      final cards = (c.read(settingsViewModelProvider) as SettingsContent).accounts;
      expect(cards.map((a) => a.title), contains('anna'));
    });
  });

  group('screen', () {
    void tall(WidgetTester tester) {
      tester.view.physicalSize = const Size(800, 1800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
    }

    testWidgets('should_fillFieldsAndOfferSave_when_managing', (tester) async {
      tall(tester);
      late Profile home;
      await tester.runAsync(() async => home = (await twoAccounts()).$1);
      await tester.pumpWidget(ProviderScope(
        overrides: overrides(),
        child: MaterialApp(home: SubsonicSetupScreen(profileId: home.id)),
      ));
      await tester.runAsync(settle);
      await tester.pumpAndSettle();

      expect(find.text('Server'), findsOneWidget);
      expect(find.widgetWithText(FilledButton, 'Save'), findsOneWidget);
      expect(find.text("You'll need"), findsNothing);
      expect(find.text('Streaming quality'), findsOneWidget);
      final fields = tester.widgetList<TextField>(find.byType(TextField)).toList();
      expect(fields[0].controller!.text, 'https://home.test');
      expect(fields[1].controller!.text, 'ann');
      expect(fields[2].controller!.text, 'secret');
    });

    testWidgets('should_sayPasswordMissing_when_credentialsGone', (tester) async {
      tall(tester);
      late Profile home;
      await tester.runAsync(() async {
        home = (await twoAccounts()).$1;
        await store.write(ProfileRepository.credentialsKey(home.id), null);
      });
      await tester.pumpWidget(ProviderScope(
        overrides: overrides(),
        child: MaterialApp(home: SubsonicSetupScreen(profileId: home.id)),
      ));
      await tester.runAsync(settle);
      await tester.pumpAndSettle();
      expect(find.text('Password missing'), findsOneWidget);
    });

    testWidgets('should_showCredentialsMissingPill_when_secretGone', (tester) async {
      await tester.runAsync(() async {
        final home = (await twoAccounts()).$1;
        await store.write(ProfileRepository.credentialsKey(home.id), null);
      });
      await tester.pumpWidget(ProviderScope(overrides: overrides(), child: const MaterialApp(home: SettingsScreen())));
      await tester.runAsync(settle);
      await tester.pumpAndSettle();
      expect(find.text('Credentials missing'), findsOneWidget);
    });

    testWidgets('should_openManagePage_when_activeCardTapped', (tester) async {
      await tester.runAsync(twoAccounts);
      await tester.pumpWidget(ProviderScope(overrides: overrides(), child: const MaterialApp(home: SettingsScreen())));
      await tester.runAsync(settle);
      await tester.pumpAndSettle();

      await tester.tap(find.text('bob'));
      await tester.runAsync(settle);
      await tester.pumpAndSettle();
      expect(find.byType(SubsonicSetupScreen), findsOneWidget);
      expect(find.widgetWithText(FilledButton, 'Save'), findsOneWidget);
    });

    testWidgets('should_offerManage_when_subsonicMenuOpened', (tester) async {
      await tester.runAsync(twoAccounts);
      await tester.pumpWidget(ProviderScope(overrides: overrides(), child: const MaterialApp(home: SettingsScreen())));
      await tester.runAsync(settle);
      await tester.pumpAndSettle();

      await tester.tap(find.byTooltip('Options for ann'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Manage'));
      await tester.runAsync(settle);
      await tester.pumpAndSettle();
      expect(find.byType(SubsonicSetupScreen), findsOneWidget);
    });

    testWidgets('should_disableAutofill_when_settingUpSubsonic', (tester) async {
      tall(tester);
      await tester.pumpWidget(ProviderScope(
        overrides: overrides(),
        child: const MaterialApp(home: SubsonicSetupScreen()),
      ));
      await tester.pumpAndSettle();
      expect(find.byType(AutofillGroup), findsNothing);
      final editables = tester.stateList<EditableTextState>(find.byType(EditableText)).toList();
      expect(editables, hasLength(3));
      for (final field in editables) {
        expect(field.widget.autofillHints, isNull);
        expect(field.textInputConfiguration.autofillConfiguration.enabled, isFalse);
      }
    });
  });
}
