import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/media_id.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/platform/applemusic/apple_music_auth.dart';
import 'package:yoin/settings/apple_music_setup_screen.dart';
import 'package:yoin/settings/apple_music_setup_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';

/// A sign-in that returns a token, or throws a chosen failure. Records the
/// endpoint it was asked to authorise.
class _FakeAuth implements AppleMusicAuth {
  _FakeAuth.success() : failure = null, storefront = 'jp';
  _FakeAuth.failing(this.failure) : storefront = '';

  final AppleMusicAuthFailure? failure;
  final String storefront;
  String? askedEndpoint;

  @override
  Future<AppleMusicAuthResult> authorize(String endpoint) async {
    askedEndpoint = endpoint;
    final f = failure;
    if (f != null) throw AppleMusicAuthException(f);
    return AppleMusicAuthResult(musicUserToken: 'mut-xyz', storefront: storefront);
  }
}

void main() {
  late InMemoryStore store;

  overridesWith(AppleMusicAuth auth) => [
        keyValueStoreProvider.overrideWith((ref) => store),
        secretStoreProvider.overrideWith((ref) => store),
        appleMusicAuthProvider.overrideWithValue(auth),
      ];

  ProviderContainer container(AppleMusicAuth auth) {
    final c = ProviderContainer(overrides: overridesWith(auth));
    c.listen(appleMusicSetupViewModelProvider, (_, _) {});
    return c;
  }

  setUp(() => store = InMemoryStore());

  group('view model', () {
    test('should_sayEndpointRequired_when_endpointBlank', () async {
      final auth = _FakeAuth.success();
      final c = container(auth);
      addTearDown(c.dispose);
      await c.read(appleMusicSetupViewModelProvider.notifier).connect('   ');
      final state = c.read(appleMusicSetupViewModelProvider);
      expect(state.endpointError, AppleSetupError.endpointRequired);
      expect(state.status, isA<AppleSetupIdle>());
      expect(auth.askedEndpoint, isNull);
      expect(await c.read(profileRepositoryProvider).activeProfile(), isNull);
    });

    test('should_sayInvalidEndpoint_when_notHttps', () async {
      final auth = _FakeAuth.success();
      final c = container(auth);
      addTearDown(c.dispose);
      await c.read(appleMusicSetupViewModelProvider.notifier).connect('ftp://token.example/token');
      expect(c.read(appleMusicSetupViewModelProvider).endpointError, AppleSetupError.endpointInvalid);
      expect(auth.askedEndpoint, isNull);
    });

    test('should_allowLoopbackHttp_when_localService', () async {
      final auth = _FakeAuth.success();
      final c = container(auth);
      addTearDown(c.dispose);
      await c.read(appleMusicSetupViewModelProvider.notifier).connect('http://127.0.0.1:8787/token');
      expect(auth.askedEndpoint, 'http://127.0.0.1:8787/token');
      expect(c.read(appleMusicSetupViewModelProvider).connected, isTrue);
    });

    test('should_saveAccountAndActivate_when_authorizeSucceeds', () async {
      final auth = _FakeAuth.success();
      final c = container(auth);
      addTearDown(c.dispose);
      await c.read(appleMusicSetupViewModelProvider.notifier).connect(' $yoinAppleMusicTokenServiceUrl ');

      expect(auth.askedEndpoint, yoinAppleMusicTokenServiceUrl);
      final state = c.read(appleMusicSetupViewModelProvider);
      expect(state.connected, isTrue);
      expect(state.status, isA<AppleSetupSaving>());

      final repo = c.read(profileRepositoryProvider);
      final profile = await repo.activeProfile();
      expect(profile, isNotNull);
      expect(profile!.provider, MediaId.providerAppleMusic);
      expect(profile.displayName, 'Apple Music');
      final creds = await repo.credentials(profile.id);
      expect(creds, isA<AppleMusicCredentials>());
      expect((creds! as AppleMusicCredentials).endpoint, yoinAppleMusicTokenServiceUrl);
      expect((creds as AppleMusicCredentials).musicUserToken, 'mut-xyz');
    });

    test('should_reportFailureWithoutSaving_when_authorizeIsCancelled', () async {
      final auth = _FakeAuth.failing(AppleMusicAuthFailure.cancelled);
      final c = container(auth);
      addTearDown(c.dispose);
      await c.read(appleMusicSetupViewModelProvider.notifier).connect(yoinAppleMusicTokenServiceUrl);
      final status = c.read(appleMusicSetupViewModelProvider).status as AppleSetupFailed;
      expect(status.failure, AppleMusicAuthFailure.cancelled);
      expect(c.read(appleMusicSetupViewModelProvider).connected, isFalse);
      expect(await c.read(profileRepositoryProvider).activeProfile(), isNull);
    });

    test('should_reportFailure_when_pageCannotLoad', () async {
      final auth = _FakeAuth.failing(AppleMusicAuthFailure.pageFailed);
      final c = container(auth);
      addTearDown(c.dispose);
      await c.read(appleMusicSetupViewModelProvider.notifier).connect(yoinAppleMusicTokenServiceUrl);
      final status = c.read(appleMusicSetupViewModelProvider).status as AppleSetupFailed;
      expect(status.failure, AppleMusicAuthFailure.pageFailed);
    });

    test('should_clearError_when_endpointEdited', () async {
      final c = container(_FakeAuth.failing(AppleMusicAuthFailure.authFailed));
      addTearDown(c.dispose);
      final vm = c.read(appleMusicSetupViewModelProvider.notifier);
      await vm.connect(yoinAppleMusicTokenServiceUrl);
      expect(c.read(appleMusicSetupViewModelProvider).status, isA<AppleSetupFailed>());
      vm.onEndpointChanged();
      expect(c.read(appleMusicSetupViewModelProvider).status, isA<AppleSetupIdle>());
    });

    test('should_rebuildActiveSource_when_pageClosedWhileSaving', () async {
      final repo = _GatedRepository(store);
      final c = ProviderContainer(
        overrides: [...overridesWith(_FakeAuth.success()), profileRepositoryProvider.overrideWithValue(repo)],
      );
      addTearDown(c.dispose);
      c.listen(activeSourceProvider, (_, _) {});
      // activeSource needs a factory to (not) build; it stays null with no profile.
      expect(await c.read(activeSourceProvider.future), isNull);

      final page = c.listen(appleMusicSetupViewModelProvider, (_, _) {});
      final connecting = c.read(appleMusicSetupViewModelProvider.notifier).connect(yoinAppleMusicTokenServiceUrl);
      await repo.entered.future;
      page.close();
      await Future<void>.delayed(Duration.zero);
      expect(c.exists(appleMusicSetupViewModelProvider), isFalse);

      repo.gate.complete();
      await connecting;
      expect((await repo.activeProfile())!.provider, MediaId.providerAppleMusic);
    });
  });

  group('screen', () {
    Future<void> pump(WidgetTester tester, AppleMusicAuth auth) async {
      tester.view.physicalSize = const Size(800, 1800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(ProviderScope(
        overrides: overridesWith(auth),
        child: const MaterialApp(home: AppleMusicSetupScreen()),
      ));
      await tester.pumpAndSettle();
    }

    FilledButton connectButton(WidgetTester tester) =>
        tester.widget(find.widgetWithText(FilledButton, 'Connect Apple Music'));

    testWidgets('should_prefillTokenServiceAndEnableConnect_when_opened', (tester) async {
      await pump(tester, _FakeAuth.success());
      expect(find.text(yoinAppleMusicTokenServiceUrl), findsOneWidget);
      expect(connectButton(tester).onPressed, isNotNull);
    });

    testWidgets('should_showAuthorizePrompt_when_connectTapped', (tester) async {
      // An auth that never returns, so the authorizing status stays on screen.
      await pump(tester, _StuckAuth());
      await tester.tap(find.widgetWithText(FilledButton, 'Connect Apple Music'));
      await tester.pump();
      expect(find.text('Complete authorization in Apple Music.'), findsOneWidget);
    });
  });
}

/// An authorize() that never completes.
class _StuckAuth implements AppleMusicAuth {
  @override
  Future<AppleMusicAuthResult> authorize(String endpoint) => Completer<AppleMusicAuthResult>().future;
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
