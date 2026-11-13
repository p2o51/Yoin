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
import 'package:yoin/settings/settings_screen.dart';
import 'package:yoin/settings/settings_view_model.dart';

import '../support/fake_music_source.dart';

const _home = SubsonicCredentials(baseUrl: 'https://home.test', username: 'ann', password: 'p');
const _work = SubsonicCredentials(baseUrl: 'https://work.test', username: 'bob', password: 'p');

void main() {
  late InMemoryStore store;
  late ProfileRepository repo;
  late List<MusicSource> built;

  List<Override> overrides() => [
    keyValueStoreProvider.overrideWithValue(store),
    secretStoreProvider.overrideWithValue(store),
    musicSourceFactoryProvider.overrideWithValue((_) {
      final s = FakeMusicSource();
      built.add(s);
      return s;
    }),
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
  });

  group('view model', () {
    late ProviderContainer c;

    setUp(() {
      c = ProviderContainer(overrides: overrides());
      c.listen(settingsViewModelProvider, (_, _) {});
    });
    tearDown(() => c.dispose());

    SettingsContent content() => c.read(settingsViewModelProvider) as SettingsContent;

    test('should_listAccountsWithActive_when_loaded', () async {
      final (home, work) = await twoAccounts();
      c.invalidate(activeSourceProvider);
      await settle();
      expect(content().accounts.map((a) => a.id), [home.id, work.id]);
      expect(content().activeId, work.id);
    });

    test('should_leadWithUsernameAndHost_when_subsonicAccount', () async {
      await twoAccounts();
      c.invalidate(activeSourceProvider);
      await settle();
      final first = content().accounts.first;
      expect(first.title, 'ann');
      expect(first.host, 'home.test');
      expect(first.displayName, 'ann @ home.test');
    });

    test('should_fallBackToDisplayName_when_credentialsMissing', () async {
      final (home, _) = await twoAccounts();
      await store.write(ProfileRepository.credentialsKey(home.id), null);
      c.invalidate(activeSourceProvider);
      await settle();
      final first = content().accounts.first;
      expect(first.title, 'ann @ home.test');
      expect(first.host, isNull);
    });

    test('should_giveEachAccountItsOwnShape_when_loaded', () async {
      await twoAccounts();
      c.invalidate(activeSourceProvider);
      await settle();
      final shapes = content().accounts.map((a) => a.avatarShape).toSet();
      expect(shapes, hasLength(2));
    });

    test('should_activateAndRebuildSource_when_switched', () async {
      final (home, _) = await twoAccounts();
      c.invalidate(activeSourceProvider);
      await c.read(activeSourceProvider.future);
      final before = c.read(activeSourceProvider).value;
      await settle();

      expect(await c.read(settingsViewModelProvider.notifier).switchTo(home.id), isTrue);
      await settle();

      expect((await repo.activeProfile())!.id, home.id);
      expect(content().activeId, home.id);
      expect(identical(await c.read(activeSourceProvider.future), before), isFalse);
    });

    test('should_fallBackToRemainingAccount_when_activeRemoved', () async {
      final (home, work) = await twoAccounts();
      c.invalidate(activeSourceProvider);
      await settle();

      await c.read(settingsViewModelProvider.notifier).remove(work.id);
      await settle();

      expect(content().accounts.map((a) => a.id), [home.id]);
      expect(content().activeId, home.id);
      expect(await repo.credentials(work.id), isNull);
    });

    test('should_keepActiveSource_when_inactiveRemoved', () async {
      final (home, work) = await twoAccounts();
      c.invalidate(activeSourceProvider);
      final source = await c.read(activeSourceProvider.future);
      await settle();

      await c.read(settingsViewModelProvider.notifier).remove(home.id);
      await settle();

      expect(content().accounts.map((a) => a.id), [work.id]);
      expect(identical(await c.read(activeSourceProvider.future), source), isTrue);
    });
  });

  group('screen', () {
    Future<void> pump(WidgetTester tester) async {
      await tester.pumpWidget(ProviderScope(
        overrides: overrides(),
        child: const MaterialApp(home: SettingsScreen()),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('should_switchAccount_when_inactiveCardTapped', (tester) async {
      late Profile home;
      await tester.runAsync(() async => home = (await twoAccounts()).$1);
      await pump(tester);

      await tester.tap(find.text('ann'));
      await tester.runAsync(settle);
      await tester.pumpAndSettle();

      String? active;
      await tester.runAsync(() async => active = (await repo.activeProfile())!.id);
      expect(active, home.id);
    });

    testWidgets('should_offerUseOnlyForOtherAccounts_when_menuOpened', (tester) async {
      await tester.runAsync(twoAccounts);
      await pump(tester);

      await tester.tap(find.byTooltip('Options for bob'));
      await tester.pumpAndSettle();
      expect(find.text('Use this account'), findsNothing);
      expect(find.text('Remove'), findsOneWidget);
      await tester.tapAt(Offset.zero);
      await tester.pumpAndSettle();

      await tester.tap(find.byTooltip('Options for ann'));
      await tester.pumpAndSettle();
      expect(find.text('Use this account'), findsOneWidget);
    });

    testWidgets('should_removeAccount_when_removeConfirmed', (tester) async {
      await tester.runAsync(twoAccounts);
      await pump(tester);

      await tester.tap(find.byTooltip('Options for ann'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Remove'));
      await tester.pumpAndSettle();
      expect(find.text('Remove this account?'), findsOneWidget);
      expect(find.textContaining('ann @ home.test'), findsOneWidget);
      await tester.tap(find.widgetWithText(TextButton, 'Remove'));
      await tester.runAsync(settle);
      await tester.pumpAndSettle();

      expect(find.text('ann'), findsNothing);
      expect(find.text('bob'), findsOneWidget);
    });

    testWidgets('should_showEmptyAndAdd_when_noAccount', (tester) async {
      await pump(tester);
      expect(find.text('No accounts'), findsOneWidget);
      expect(find.text('Add account'), findsOneWidget);
    });

    testWidgets('should_showAddTile_when_accountsExist', (tester) async {
      await tester.runAsync(twoAccounts);
      await pump(tester);
      expect(find.text('Add'), findsOneWidget);
      expect(find.text('Settings'), findsWidgets);
    });
  });
}
