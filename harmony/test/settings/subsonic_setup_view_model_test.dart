import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/service_intro.dart';
import 'package:yoin/settings/subsonic_setup_screen.dart';
import 'package:yoin/settings/subsonic_setup_view_model.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';
import 'package:yoin/ui/component/user_message.dart';

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

    test('should_sayTimeoutLine_when_serverTooSlow', () async {
      source = _ThrowingPing(const SourceNetworkException('slow', kind: NetworkFailureKind.timeout));
      final c = container();
      addTearDown(c.dispose);
      await c.read(subsonicSetupViewModelProvider.notifier).test(address: 'x.example', username: 'u', password: 'p');
      final status = c.read(subsonicSetupViewModelProvider).status as SetupFailed;
      expect(status.error, SetupError.unreachable);
      expect(status.line, UserLine.timeout);
    });

    test('should_saySecureLine_when_tlsFails', () async {
      source = _ThrowingPing(const SourceNetworkException('tls', kind: NetworkFailureKind.tls));
      final c = container();
      addTearDown(c.dispose);
      await c.read(subsonicSetupViewModelProvider.notifier).test(address: 'x.example', username: 'u', password: 'p');
      expect((c.read(subsonicSetupViewModelProvider).status as SetupFailed).line, UserLine.ssl);
    });

    test('should_refuseSixthAccount_when_fiveKept', () async {
      await _fillAccounts(store);
      final c = container();
      addTearDown(c.dispose);
      await _settle();
      expect(c.read(subsonicSetupViewModelProvider).canAddProfile, isFalse);

      await c.read(subsonicSetupViewModelProvider.notifier).connect(address: 'x.example', username: 'u', password: 'p');
      final status = c.read(subsonicSetupViewModelProvider).status as SetupFailed;
      expect(status.error, SetupError.accountLimit);
      // Refused before the server is even asked; nothing saved.
      expect(built, isNull);
      expect(await c.read(profileRepositoryProvider).profiles(), hasLength(ProfileRepository.maxProfiles));
    });

    test('should_sayLimit_when_sixthArrivesWhileConnecting', () async {
      final c = container();
      addTearDown(c.dispose);
      await _settle();
      expect(c.read(subsonicSetupViewModelProvider).canAddProfile, isTrue);
      // Five accounts land while the page is open (another page, cloud sync's Add).
      await _fillAccounts(store);

      await c.read(subsonicSetupViewModelProvider.notifier).connect(address: 'x.example', username: 'u', password: 'p');
      final state = c.read(subsonicSetupViewModelProvider);
      expect((state.status as SetupFailed).error, SetupError.accountLimit);
      expect(state.canAddProfile, isFalse);
      expect(state.connected, isFalse);
    });

    test('should_allowSaving_when_managingAtLimit', () async {
      await _fillAccounts(store);
      final id = (await ProfileRepository(store, store).profiles()).first.id;
      final c = ProviderContainer(overrides: overrides);
      addTearDown(c.dispose);
      c.listen(subsonicSetupProvider(id), (_, _) {});
      await _settle();
      expect(c.read(subsonicSetupProvider(id)).canAddProfile, isTrue);
    });

    test('should_keepHttpScheme_when_typed', () {
      expect(SubsonicSetupViewModel.normalizeAddress('http://mini.local:4533//'), 'http://mini.local:4533');
    });
  });

  group('screen', () {
    Future<void> pump(WidgetTester tester, {Size size = const Size(800, 1800)}) async {
      // A phone-tall window by default, so the whole form is on screen.
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(ProviderScope(
        overrides: overrides,
        child: MaterialApp(
          home: YoinWindowScope(info: YoinWindowInfo(size.width, size.height), child: const SubsonicSetupScreen()),
        ),
      ));
      await tester.pumpAndSettle();
    }

    FilledButton connectButton(WidgetTester tester) => tester.widget(find.widgetWithText(FilledButton, 'Connect'));

    Future<void> fillForm(WidgetTester tester) async {
      final fields = find.byType(TextField);
      await tester.enterText(fields.at(0), 'music.example.com');
      await tester.enterText(fields.at(1), 'gpo');
      await tester.enterText(fields.at(2), 'pw');
      await tester.pump();
    }

    testWidgets('should_pitchWhatYouGet_when_adding', (tester) async {
      await pump(tester);
      expect(find.text('What you get'), findsOneWidget);
      for (final line in [
        'Playback',
        'Stream directly from your server',
        'Favorites and ratings',
        'Synced with your server',
        'Playlists',
        'Browse and edit your playlists',
        'Cast and cache',
        'Cast to speakers and cache recent plays',
      ]) {
        expect(find.text(line), findsOneWidget, reason: line);
      }
      // Pitch first, then what you'll need, then the form.
      final whatYouGet = tester.getTopLeft(find.text('What you get')).dy;
      expect(whatYouGet, lessThan(tester.getTopLeft(find.text("You'll need")).dy));
      expect(tester.getTopLeft(find.text("You'll need")).dy, lessThan(tester.getTopLeft(find.byType(TextField).first).dy));
      expect(find.byType(SetupColumns), findsNothing);
    });

    testWidgets('should_sayTimeoutLine_when_testTimesOut', (tester) async {
      source = _ThrowingPing(const SourceNetworkException('slow', kind: NetworkFailureKind.timeout));
      await pump(tester);
      await fillForm(tester);
      await tester.tap(find.widgetWithText(OutlinedButton, 'Test'));
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await tester.pumpAndSettle();
      expect(find.text('The server is taking too long. Try again.'), findsOneWidget);
    });

    testWidgets('should_disableConnectAndSayLimit_when_fiveAccountsKept', (tester) async {
      await tester.runAsync(() => _fillAccounts(store));
      await pump(tester);
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await tester.pumpAndSettle();
      await fillForm(tester);
      expect(find.text('Account limit reached'), findsOneWidget);
      expect(connectButton(tester).onPressed, isNull);
      // Test still reaches the server.
      expect(tester.widget<OutlinedButton>(find.widgetWithText(OutlinedButton, 'Test')).onPressed, isNotNull);
    });

    testWidgets('should_splitIntoColumns_when_landscapeHandset', (tester) async {
      await pump(tester, size: const Size(900, 400));
      expect(find.byType(SetupColumns), findsOneWidget);
      // The service on the left, the form on the right, "What you get" after the form.
      final need = tester.getTopLeft(find.text("You'll need"));
      final address = tester.getTopLeft(find.byType(TextField).first);
      expect(need.dx, lessThan(address.dx));
      final hero = need;
      await tester.dragUntilVisible(find.text('What you get'), find.byType(ListView), const Offset(0, -200));
      expect(tester.getTopLeft(find.text('What you get')).dx, greaterThan(hero.dx));
      // The short side's name a step down (Android compactTitle in landscape).
      final text = Theme.of(tester.element(find.byType(SetupColumns))).textTheme;
      final names = tester.widgetList<Text>(find.text('Subsonic'));
      expect(names.any((t) => t.style?.fontSize == text.headlineLarge!.fontSize), isTrue);
      expect(names.any((t) => t.style?.fontSize == text.displaySmall!.fontSize), isFalse);
    });

    testWidgets('should_splitIntoColumns_when_wideWindow', (tester) async {
      await pump(tester, size: const Size(1280, 800));
      expect(find.byType(SetupColumns), findsOneWidget);
    });

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

/// Lets the view model's async reads land.
Future<void> _settle() => Future<void>.delayed(const Duration(milliseconds: 10));

/// Five accounts kept: the limit.
Future<void> _fillAccounts(InMemoryStore store) async {
  final repo = ProfileRepository(store, store);
  for (var i = 0; i < ProfileRepository.maxProfiles; i++) {
    await repo.add(
      provider: 'subsonic',
      displayName: 'u$i @ s$i.example',
      credentials: SubsonicCredentials(baseUrl: 'https://s$i.example', username: 'u$i', password: 'p'),
    );
  }
}
