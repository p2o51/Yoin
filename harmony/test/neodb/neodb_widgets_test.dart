import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/neodb/neodb_config.dart';
import 'package:yoin/data/sync/adapters/memory_sync_adapters.dart';
import 'package:yoin/neodb/neodb_lines.dart';
import 'package:yoin/neodb/neodb_providers.dart';
import 'package:yoin/neodb/neodb_settings_screen.dart';
import 'package:yoin/platform/storage/stores.dart';

void main() {
  late InMemoryStore kv;
  late InMemoryStore secrets;
  final album = MediaId.subsonic('al1');

  setUp(() {
    kv = InMemoryStore();
    secrets = InMemoryStore();
  });

  Future<void> pump(WidgetTester tester, Widget child, {List<String>? messages}) async {
    tester.view.physicalSize = const Size(420, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          keyValueStoreProvider.overrideWithValue(kv),
          secretStoreProvider.overrideWithValue(secrets),
          memoryStoreProvider.overrideWith((ref) async => MemoryStore.shared(kv, 'p1')),
          neoDbMessengerProvider.overrideWithValue((message, {actionLabel, onAction}) => messages?.add(message)),
        ],
        child: MaterialApp(home: Material(child: child)),
      ),
    );
    await tester.pumpAndSettle();
  }

  group('Settings › NeoDB', () {
    testWidgets('should_saveTokenAndSayItSyncs_when_tokenPasted', (tester) async {
      await pump(
        tester,
        const Column(
          children: [
            NeoDbSettingsRow(),
            Expanded(child: NeoDbSettingsScreen(inPane: true)),
          ],
        ),
      );
      expect(find.text('Sync album ratings and reviews'), findsOneWidget);
      expect(find.text('Sign in'), findsOneWidget);
      expect(find.text('Sign out'), findsNothing);

      await tester.tap(find.byKey(const ValueKey('neodb-use-token')));
      await tester.pumpAndSettle();
      final save = find.byKey(const ValueKey('neodb-save-token'));
      expect(tester.widget<OutlinedButton>(save).onPressed, isNull);
      await tester.enterText(
        find.descendant(of: find.byKey(const ValueKey('neodb-token')), matching: find.byType(EditableText)),
        ' tok123 ',
      );
      await tester.pumpAndSettle();
      await tester.tap(save);
      await tester.pumpAndSettle();

      expect(secrets.values[NeoDbConfigStore.tokenKey], 'tok123');
      expect(kv.values[NeoDbConfigStore.instanceKey], 'https://neodb.social');
      expect(kv.values.values.any((v) => v.contains('tok123')), isFalse, reason: 'the token stays in the secret store');
      expect(find.text('Album ratings and reviews sync'), findsOneWidget);
      expect(find.text('Sign in again'), findsOneWidget);
      // Saved: the same token can't be saved again.
      expect(tester.widget<OutlinedButton>(save).onPressed, isNull);

      await tester.tap(find.byKey(const ValueKey('neodb-sign-out')));
      await tester.pumpAndSettle();
      expect(secrets.values[NeoDbConfigStore.tokenKey], isNull);
      expect(find.text('Sync album ratings and reviews'), findsOneWidget);
      // The instance stays.
      expect(kv.values[NeoDbConfigStore.instanceKey], 'https://neodb.social');
    });

    testWidgets('should_sayUrlInvalid_when_instanceHasNoHost', (tester) async {
      final messages = <String>[];
      await pump(tester, const NeoDbSettingsScreen(inPane: true), messages: messages);
      await tester.enterText(
        find.descendant(of: find.byKey(const ValueKey('neodb-instance')), matching: find.byType(EditableText)),
        'bad host',
      );
      await tester.tap(find.byKey(const ValueKey('neodb-sign-in')));
      await tester.pumpAndSettle();
      expect(messages, ['Invalid NeoDB URL. Include a valid host.']);
    });
  });

  group('the rate sheet line', () {
    testWidgets('should_offerSignInAndVoices_when_signedOut', (tester) async {
      var voices = 0;
      await pump(tester, AlbumNeoDbLine(albumId: album, unsaved: false, onVoices: () => voices++));
      expect(find.text('NeoDB'), findsOneWidget);
      expect(find.text('Sign in to sync'), findsOneWidget);
      await tester.tap(find.text('What others say'));
      expect(voices, 1);
    });

    testWidgets('should_saySynced_when_signedInAndNothingWaits', (tester) async {
      await kv.write(NeoDbConfigStore.instanceKey, 'https://neodb.social');
      await secrets.write(NeoDbConfigStore.tokenKey, 'tok');
      final store = MemoryStore.shared(kv, 'p1');
      await store.setAlbumRating(album, 7, nowMs: 1);
      await store.markRatingPushed(album, sentRating: 7);
      await pump(tester, AlbumNeoDbLine(albumId: album, unsaved: false, onVoices: () {}));
      expect(find.text('Synced'), findsOneWidget);

      // Words typed and not yet kept: no stale "Synced".
      await pump(tester, AlbumNeoDbLine(albumId: album, unsaved: true, onVoices: () {}));
      expect(find.text('Synced'), findsNothing);
    });
  });

  group('the Memories diary line', () {
    testWidgets('should_onlyOfferVoices_when_signedOut', (tester) async {
      await pump(tester, MemoryDiaryNeoDbRow(albumId: album, albumName: 'Blue', interactive: true));
      expect(find.text('What others say'), findsOneWidget);
      expect(find.text('Sync to NeoDB'), findsNothing);
    });

    testWidgets('should_offerSync_when_changeWaits', (tester) async {
      await kv.write(NeoDbConfigStore.instanceKey, 'https://neodb.social');
      await secrets.write(NeoDbConfigStore.tokenKey, 'tok');
      await MemoryStore.shared(kv, 'p1').setAlbumReview(album, 'Words', nowMs: 1);
      await pump(tester, MemoryDiaryNeoDbRow(albumId: album, albumName: 'Blue', interactive: true));
      expect(find.text('Sync to NeoDB'), findsOneWidget);
      expect(find.byKey(const ValueKey('diary-neodb-sync')), findsOneWidget);
    });
  });

  test('should_keepNeoDbFlags_when_cloudSyncAppliesScore', () async {
    MemoryStore memory(String id) => MemoryStore.shared(kv, id);
    final store = memory('p');
    await store.setAlbumReview(album, 'Draft', nowMs: 1);

    await AlbumRatingSyncAdapter(
      memory,
    ).apply('p', albumKey('subsonic', 'al1'), {'albumId': 'al1', 'provider': 'subsonic', 'rating': 8}, 100, null);

    final row = (await store.albumRatingRow(album))!;
    expect(row.rating, 8);
    // Another device's score isn't this device's change to push; the waiting words still are.
    expect(row.ratingNeedsSync, isFalse);
    expect(row.reviewNeedsSync, isTrue);
  });
}
