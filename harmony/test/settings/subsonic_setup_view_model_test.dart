import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/subsonic_setup_screen.dart';
import 'package:yoin/settings/subsonic_setup_view_model.dart';

import '../support/fake_music_source.dart';

void main() {
  late InMemoryStore store;
  late FakeMusicSource source;
  SubsonicCredentials? built;

  final overrides = [
    keyValueStoreProvider.overrideWith((ref) => store),
    secretStoreProvider.overrideWith((ref) => store),
    musicSourceFactoryProvider.overrideWith((ref) => (credentials) {
          built = credentials as SubsonicCredentials;
          return source;
        }),
  ];

  ProviderContainer container() {
    final c = ProviderContainer(overrides: overrides);
    c.listen(subsonicSetupViewModelProvider, (_, _) {});
    return c;
  }

  setUp(() {
    store = InMemoryStore();
    source = FakeMusicSource();
    built = null;
  });

  group('view model', () {
    test('should_sayAddressRequired_when_addressBlank', () async {
      final c = container();
      addTearDown(c.dispose);
      await c.read(subsonicSetupViewModelProvider.notifier).test(address: ' ', username: 'u', password: 'p');
      final state = c.read(subsonicSetupViewModelProvider);
      expect(state.addressError, SetupError.addressRequired);
      expect(state.status, isA<SetupIdle>());
      expect(built, isNull);
    });

    test('should_sayInvalidAddress_when_addressHasNoHost', () async {
      final c = container();
      addTearDown(c.dispose);
      await c.read(subsonicSetupViewModelProvider.notifier).connect(address: 'ftp://x', username: 'u', password: 'p');
      expect(c.read(subsonicSetupViewModelProvider).addressError, SetupError.checkAddress);
      expect(built, isNull);
    });

    test('should_clearAddressError_when_addressEdited', () async {
      final c = container();
      addTearDown(c.dispose);
      final vm = c.read(subsonicSetupViewModelProvider.notifier);
      await vm.test(address: '', username: 'u', password: 'p');
      vm.onAddressChanged();
      expect(c.read(subsonicSetupViewModelProvider).addressError, isNull);
    });

    test('should_sayUsernameRequired_when_usernameBlank', () async {
      final c = container();
      addTearDown(c.dispose);
      await c.read(subsonicSetupViewModelProvider.notifier).test(address: 'a.test', username: '  ', password: 'p');
      final status = c.read(subsonicSetupViewModelProvider).status as SetupFailed;
      expect(status.error, SetupError.usernameRequired);
    });

    test('should_reportReachableWithoutSaving_when_testAnswered', () async {
      final c = container();
      addTearDown(c.dispose);
      await c.read(subsonicSetupViewModelProvider.notifier).test(address: 'a.test', username: 'u', password: 'p');
      expect(c.read(subsonicSetupViewModelProvider).status, isA<SetupReachable>());
      expect(c.read(subsonicSetupViewModelProvider).connected, isFalse);
      expect(await c.read(profileRepositoryProvider).activeProfile(), isNull);
      expect(source.disposed, isTrue);
    });

    test('should_saveAndActivate_when_connectAnswered', () async {
      final c = container();
      addTearDown(c.dispose);
      await c
          .read(subsonicSetupViewModelProvider.notifier)
          .connect(address: 'music.example.com/', username: ' gpo ', password: 'pw');

      expect(c.read(subsonicSetupViewModelProvider).connected, isTrue);
      expect(built!.baseUrl, 'https://music.example.com');
      expect(built!.username, 'gpo');
      final profile = await c.read(profileRepositoryProvider).activeProfile();
      expect(profile!.displayName, 'gpo @ music.example.com');
      expect(source.disposed, isTrue);
    });

    test('should_reportUnreachable_when_networkFails', () async {
      source = _ThrowingPing(const SourceNetworkException('refused'));
      final c = container();
      addTearDown(c.dispose);
      await c.read(subsonicSetupViewModelProvider.notifier).connect(address: 'x.example', username: 'u', password: 'p');
      final status = c.read(subsonicSetupViewModelProvider).status as SetupFailed;
      expect(status.error, SetupError.unreachable);
      expect(await c.read(profileRepositoryProvider).activeProfile(), isNull);
    });

    test('should_passServerWords_when_serverRefuses', () async {
      source = _ThrowingPing(const SourceAuthException('Wrong username or password'));
      final c = container();
      addTearDown(c.dispose);
      await c.read(subsonicSetupViewModelProvider.notifier).test(address: 'x.example', username: 'u', password: 'p');
      final status = c.read(subsonicSetupViewModelProvider).status as SetupFailed;
      expect(status.error, SetupError.serverError);
      expect(status.serverMessage, 'Wrong username or password');
    });

    test('should_rebuildActiveSource_when_pageClosedWhileSaving', () async {
      final repo = _GatedRepository(store);
      final c = ProviderContainer(overrides: [...overrides, profileRepositoryProvider.overrideWithValue(repo)]);
      addTearDown(c.dispose);
      c.listen(activeSourceProvider, (_, _) {});
      expect(await c.read(activeSourceProvider.future), isNull);

      final page = c.listen(subsonicSetupViewModelProvider, (_, _) {});
      final connecting = c
          .read(subsonicSetupViewModelProvider.notifier)
          .connect(address: 'music.example.com', username: 'gpo', password: 'pw');
      await repo.entered.future;
      // Back during Connecting…: the page's view model goes away.
      page.close();
      await Future<void>.delayed(Duration.zero);
      expect(c.exists(subsonicSetupViewModelProvider), isFalse);

      repo.gate.complete();
      await connecting;
      expect(await c.read(activeSourceProvider.future), isNotNull);
      expect((await repo.activeProfile())!.displayName, 'gpo @ music.example.com');
    });

    test('should_keepHttpScheme_when_typed', () {
      expect(SubsonicSetupViewModel.normalizeAddress('http://mini.local:4533//'), 'http://mini.local:4533');
    });
  });

  group('screen', () {
    Future<void> pump(WidgetTester tester) async {
      // A phone-tall window, so the whole form is on screen.
      tester.view.physicalSize = const Size(800, 1800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(ProviderScope(
        overrides: overrides,
        child: const MaterialApp(home: SubsonicSetupScreen()),
      ));
      await tester.pumpAndSettle();
    }

    FilledButton connectButton(WidgetTester tester) => tester.widget(find.widgetWithText(FilledButton, 'Connect'));

    testWidgets('should_disableTestAndConnect_when_fieldsEmpty', (tester) async {
      await pump(tester);
      expect(find.text('Connect'), findsWidgets); // the group heading and the button
      expect(connectButton(tester).onPressed, isNull);
      expect(tester.widget<OutlinedButton>(find.widgetWithText(OutlinedButton, 'Test')).onPressed, isNull);
    });

    testWidgets('should_sayServerFound_when_testAnswered', (tester) async {
      await pump(tester);
      final fields = find.byType(TextField);
      await tester.enterText(fields.at(0), 'music.example.com');
      await tester.enterText(fields.at(1), 'gpo');
      await tester.enterText(fields.at(2), 'pw');
      await tester.pump();
      expect(connectButton(tester).onPressed, isNotNull);

      await tester.tap(find.widgetWithText(OutlinedButton, 'Test'));
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await tester.pumpAndSettle();
      expect(find.text('Server found'), findsOneWidget);
    });

    testWidgets('should_revealPassword_when_toggleTapped', (tester) async {
      await pump(tester);
      TextField password() => tester.widget(find.byType(TextField).at(2));
      expect(password().obscureText, isTrue);
      await tester.tap(find.byTooltip('Show Password'));
      await tester.pump();
      expect(password().obscureText, isFalse);
    });
  });
}

class _ThrowingPing extends FakeMusicSource {
  _ThrowingPing(this.error);
  final Object error;
  @override
  Future<bool> ping() async => throw error;
}

/// Holds add() until [gate] completes, so a test can close the page mid-save.
class _GatedRepository extends ProfileRepository {
  _GatedRepository(InMemoryStore store) : super(store, store);
  final entered = Completer<void>();
  final gate = Completer<void>();

  @override
  Future<Profile> add({required String provider, required String displayName, required ProfileCredentials credentials}) async {
    entered.complete();
    await gate.future;
    return super.add(provider: provider, displayName: displayName, credentials: credentials);
  }
}
