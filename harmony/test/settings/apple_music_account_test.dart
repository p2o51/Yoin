import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/applemusic/apple_music_name_region.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/platform/applemusic/apple_music_auth.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/add_account_sheet.dart';
import 'package:yoin/settings/apple_music_account_screen.dart';
import 'package:yoin/settings/apple_music_account_view_model.dart';
import 'package:yoin/settings/apple_music_setup_view_model.dart';
import 'package:yoin/settings/settings_view_model.dart';

import '../support/library_fake_source.dart';

class _FakeAuth implements AppleMusicAuth {
  _FakeAuth({this.failure});
  final AppleMusicAuthFailure? failure;
  final asked = <String>[];

  @override
  Future<AppleMusicAuthResult> authorize(String endpoint) async {
    asked.add(endpoint);
    if (failure case final f?) throw AppleMusicAuthException(f);
    return const AppleMusicAuthResult(musicUserToken: 'mut-new', storefront: 'jp');
  }
}

const _endpoint = 'https://tokens.example.com/token';

/// [values] that refuses to keep a name region.
class _RegionRefusingStore implements KeyValueStore {
  _RegionRefusingStore(this.values);
  final KeyValueStore values;

  @override
  Future<String?> read(String key) => values.read(key);

  @override
  Future<void> write(String key, String? value) {
    if (key.startsWith('apple_music.name_region.')) throw StateError('disk full');
    return values.write(key, value);
  }
}

void main() {
  late InMemoryStore kv;
  late InMemoryStore secrets;
  late ProfileRepository repo;
  late Profile apple;
  late _FakeAuth auth;

  setUp(() async {
    kv = InMemoryStore();
    secrets = InMemoryStore();
    repo = ProfileRepository(kv, secrets);
    await repo.add(
      provider: MediaId.providerSubsonic,
      displayName: 'Home',
      credentials: const SubsonicCredentials(baseUrl: 'https://m.test', username: 'u', password: 'p'),
    );
    apple = await repo.add(
      provider: MediaId.providerAppleMusic,
      displayName: 'Apple Music',
      credentials: const AppleMusicCredentials(endpoint: _endpoint, musicUserToken: 'mut-old'),
    );
    auth = _FakeAuth();
  });

  List<Override> overrides() => [
    keyValueStoreProvider.overrideWithValue(kv),
    secretStoreProvider.overrideWithValue(secrets),
    profileRepositoryProvider.overrideWithValue(repo),
    appleMusicAuthProvider.overrideWithValue(auth),
    activeSourceProvider.overrideWith((ref) async => LibraryFakeSource()),
  ];

  ProviderContainer container() {
    final c = ProviderContainer(overrides: overrides());
    addTearDown(c.dispose);
    c.listen(appleMusicAccountProvider(apple.id), (_, _) {});
    return c;
  }

  Future<void> settle() async {
    for (var i = 0; i < 20; i++) {
      await pumpEventQueue();
    }
  }

  group('view model', () {
    test('should_loadStoredServiceAndRegion_when_opened', () async {
      await AppleMusicNameRegionStore(kv).write(apple.id, AppleMusicNameRegion.original);
      final c = container();
      await settle();
      final s = c.read(appleMusicAccountProvider(apple.id));
      expect(s.loaded, isTrue);
      expect(s.endpoint, _endpoint);
      expect(s.connected, isTrue);
      expect(s.nameRegion, AppleMusicNameRegion.original);
      expect(s.account!.isActive, isTrue);
    });

    test('should_keepRegionForThisAccount_when_regionPicked', () async {
      final c = container();
      await settle();
      await c.read(appleMusicAccountProvider(apple.id).notifier).setNameRegion(AppleMusicNameRegion.japan);
      expect(c.read(appleMusicAccountProvider(apple.id)).nameRegion, AppleMusicNameRegion.japan);
      expect(await AppleMusicNameRegionStore(kv).read(apple.id), AppleMusicNameRegion.japan);
    });

    test('should_goBackToStoredRegion_when_itCannotBeKept', () async {
      final c = ProviderContainer(
        overrides: [
          keyValueStoreProvider.overrideWithValue(_RegionRefusingStore(kv)),
          secretStoreProvider.overrideWithValue(secrets),
          profileRepositoryProvider.overrideWithValue(repo),
          appleMusicAuthProvider.overrideWithValue(auth),
          activeSourceProvider.overrideWith((ref) async => LibraryFakeSource()),
        ],
      );
      addTearDown(c.dispose);
      c.listen(appleMusicAccountProvider(apple.id), (_, _) {});
      await settle();
      await c.read(appleMusicAccountProvider(apple.id).notifier).setNameRegion(AppleMusicNameRegion.japan);
      expect(c.read(appleMusicAccountProvider(apple.id)).nameRegion, AppleMusicNameRegion.off);
      expect(await AppleMusicNameRegionStore(kv).read(apple.id), AppleMusicNameRegion.off);
    });

    test('should_replaceTokenAndKeepName_when_reconnected', () async {
      final c = container();
      await settle();
      await c.read(appleMusicAccountProvider(apple.id).notifier).reconnect(_endpoint);
      final stored = await repo.credentials(apple.id) as AppleMusicCredentials;
      expect(stored.musicUserToken, 'mut-new');
      expect((await repo.profiles()).firstWhere((p) => p.id == apple.id).displayName, 'Apple Music');
      expect(auth.asked, [_endpoint]);
      expect(c.read(appleMusicAccountProvider(apple.id)).status, isA<AppleSetupIdle>());
      // The storefront it signed in to shows under the status, as on Android.
      expect(c.read(appleMusicAccountProvider(apple.id)).storefront, 'jp');
    });

    test('should_keepOldTokenAndSayFailed_when_signInFails', () async {
      auth = _FakeAuth(failure: AppleMusicAuthFailure.cancelled);
      final c = container();
      await settle();
      await c.read(appleMusicAccountProvider(apple.id).notifier).reconnect(_endpoint);
      expect((await repo.credentials(apple.id) as AppleMusicCredentials).musicUserToken, 'mut-old');
      expect(c.read(appleMusicAccountProvider(apple.id)).status, isA<AppleSetupFailed>());
    });

    test('should_refuseNonHttpsService_when_reconnecting', () async {
      final c = container();
      await settle();
      await c.read(appleMusicAccountProvider(apple.id).notifier).reconnect('http://tokens.example.com');
      expect(c.read(appleMusicAccountProvider(apple.id)).endpointError, AppleSetupError.endpointInvalid);
      expect(auth.asked, isEmpty);
    });

    test('should_manageAppleMusicAccounts_when_listed', () {
      final card = accountCardFor(
        apple,
        const AppleMusicCredentials(endpoint: _endpoint, musicUserToken: 'x'),
        isActive: true,
        avatarShape: 0,
      );
      expect(card.canManage, isTrue);
      expect(card.tapOpensPage, isTrue);
    });
  });

  group('page', () {
    testWidgets('should_showSongNamesAndStoreChoice_when_regionTapped', (tester) async {
      final c = ProviderContainer(overrides: overrides());
      addTearDown(c.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: c,
          child: MaterialApp(home: AppleMusicAccountScreen(profileId: apple.id)),
        ),
      );
      await tester.runAsync(settle);
      await tester.pumpAndSettle();
      expect(find.text('Account'), findsOneWidget);
      expect(find.text('Reconnect Apple Music'), findsOneWidget);
      expect(find.text('Connected'), findsOneWidget);
      await tester.scrollUntilVisible(
        find.text('From a specific region'),
        200,
        scrollable: find.descendant(of: find.byType(ListView), matching: find.byType(Scrollable)).first,
      );
      expect(find.text('Song names'), findsOneWidget);
      await tester.ensureVisible(find.text('From a specific region'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('From a specific region'), warnIfMissed: true);
      await tester.pumpAndSettle();
      expect(find.text('Choose a region'), findsOneWidget);
      await tester.tap(find.text('Japan'));
      await tester.pumpAndSettle();
      expect(c.read(appleMusicAccountProvider(apple.id)).nameRegion, AppleMusicNameRegion.japan);
      expect(find.text("Names from Apple Music's Japan storefront"), findsOneWidget);
      await tester.runAsync(settle);
      await tester.pumpAndSettle();
      expect(await tester.runAsync(() => AppleMusicNameRegionStore(kv).read(apple.id)), AppleMusicNameRegion.japan);
    });
  });

  group('add account sheet', () {
    testWidgets('should_listServicesWithLocalLater_when_opened', (tester) async {
      AddAccountService? picked;
      await tester.pumpWidget(
        ProviderScope(
          child: MaterialApp(
            home: Builder(
              builder: (context) => Scaffold(
                body: TextButton(
                  onPressed: () async => picked = await showAddAccountSheet(context),
                  child: const Text('Add'),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('Add'));
      await tester.pumpAndSettle();
      expect(find.text('Add an account'), findsOneWidget);
      expect(find.text('Navidrome, Airsonic and other servers'), findsOneWidget);
      expect(find.text('Preview'), findsOneWidget);
      expect(find.text('Coming later'), findsOneWidget);
      // Files on this device: listed, not offered.
      await tester.tap(find.text('Files on this device'));
      await tester.pumpAndSettle();
      expect(find.text('Add an account'), findsOneWidget);
      await tester.tap(find.text('Your Apple Music library and catalog'));
      await tester.pumpAndSettle();
      expect(picked, AddAccountService.appleMusic);
      expect(find.text('Add an account'), findsNothing);
    });
  });
}
