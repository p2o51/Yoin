// The import review and the Transfer Memories review, driven by taps.
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/portability/backup_ports.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/sync/backup_screens.dart';
import 'package:yoin/settings/sync/cloud_sync_screen.dart';
import 'package:yoin/settings/sync/transfer_screens.dart';
import 'package:yoin/settings/sync/transfer_view_model.dart';

import '../../data/portability/portability_device.dart';
import '../../support/fake_music_source.dart';
import 'cloud_sync_screen_test.dart' show FakeCloudSync;
import 'portability_fixtures.dart';

const _carl = PortabilityProfile(
  id: 'p9',
  provider: 'subsonic',
  displayName: 'Carl',
  createdAt: 500,
  subsonicServerUrl: 'https://carl.example.com',
  subsonicUsername: 'carl',
);

Track _track(String id, String title) =>
    Track(id: MediaId('subsonic', id), title: title, artist: 'Artist', albumId: MediaId('subsonic', 'bal1'));

/// Lets the flow's real async work (the sync store, file reads) finish, then the springs settle: a busy
/// step's loading indicator never stops on its own.
Future<void> settle(WidgetTester tester) async {
  for (var i = 0; i < 10; i++) {
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await tester.pump(const Duration(milliseconds: 16));
  }
  await tester.pumpAndSettle(
    const Duration(milliseconds: 100),
    EnginePhase.sendSemanticsUpdate,
    const Duration(seconds: 5),
  );
}

void main() {
  late Directory cache;
  late FakeDocumentFiles files;
  late FakeCloudSync sync;
  late List<String> messages;
  late Uint8List carlBackup;
  late PortabilityDevice aliceAndBob;

  setUpAll(() async {
    aliceAndBob = PortabilityDevice(profiles: [alice, bob]);
    await seedAlice(aliceAndBob);
    final phone = PortabilityDevice(profiles: [_carl]);
    await phone.stores
        .memory('p9')
        .putSongNote(
          SongNote(
            id: 'c1',
            trackId: MediaId('subsonic', 'c1'),
            content: 'from carl',
            createdAt: 1,
            updatedAt: 1,
            title: 'Carl Song',
            artist: 'Carl',
          ),
        );
    carlBackup = (await (await portabilityOf(phone)).export(language: 'en')).bytes;
  });

  setUp(() async {
    cache = await Directory.systemTemp.createTemp('yoin_portability_ui');
    files = FakeDocumentFiles();
    sync = FakeCloudSync();
    messages = [];
  });

  tearDown(() async {
    if (await cache.exists()) await cache.delete(recursive: true);
  });

  Future<void> pump(WidgetTester tester, PortabilityDevice device, Widget home, {FakeCatalog? catalog}) async {
    tester.view.physicalSize = const Size(800, 3000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final store = InMemoryStore();
    final portability = await tester.runAsync(() => portabilityOf(device));
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          keyValueStoreProvider.overrideWithValue(store),
          secretStoreProvider.overrideWithValue(store),
          musicSourceFactoryProvider.overrideWithValue((_) => FakeMusicSource()),
          ...portabilityOverrides(
            portability: portability!,
            sync: sync,
            files: files,
            cache: cache,
            messages: messages,
            catalog: catalog,
          ),
        ],
        child: MaterialApp(home: home),
      ),
    );
    await settle(tester);
  }

  testWidgets('should_importIntoTheChosenAccount_when_aDestinationIsPicked', (tester) async {
    // Carl's backup: no Carl here, so it would be skipped unless the user picks an account.
    final tablet = PortabilityDevice(profiles: [alice, bob]);
    await pump(tester, tablet, BackupScreen.import(carlBackup));

    expect(find.text('Review import'), findsOneWidget);
    expect(find.text('Verified'), findsOneWidget);
    expect(find.text('Merge into this device'), findsOneWidget);
    expect(find.text('Carl'), findsOneWidget);
    // Not the same account here: Into Alice, Into Bob, Skip; Skip by default, so nothing to import yet.
    expect(find.text('Into Alice'), findsOneWidget);
    expect(find.text('Into Bob'), findsOneWidget);
    expect(find.text('Keep in the cloud only'), findsNothing);
    // Global data always goes along (Android, iOS): counts only, nothing to switch off.
    expect(find.byType(Switch), findsNothing);
    final confirm = find.widgetWithText(FilledButton, 'Merge and import');
    expect(tester.widget<FilledButton>(confirm).onPressed, isNull);

    await tester.tap(find.text('Into Bob'));
    await tester.pumpAndSettle();
    expect(tester.widget<FilledButton>(confirm).onPressed, isNotNull);
    await tester.tap(confirm);
    await settle(tester);
    expect(find.text('Merged into this device.'), findsOneWidget);
    final notes = await tester.runAsync(() => tablet.stores.memory('p2').syncRead());
    expect(notes!.songNotes.single.content, 'from carl');
  });

  testWidgets('should_sayWhatIsWrong_when_theFileIsNotABackup', (tester) async {
    await pump(tester, PortabilityDevice(profiles: [alice]), BackupScreen.import(Uint8List.fromList([1, 2, 3])));
    expect(find.text("This isn't a Yoin backup file."), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'Done'), findsOneWidget);
  });

  testWidgets('should_copyThePickedCandidate_when_anUnsureSongIsResolved', (tester) async {
    final device = aliceAndBob;
    const args = TransferArgs(sourceProfileId: 'p1', targetProfileId: 'p2');
    await pump(
      tester,
      device,
      const TransferScreen(args: args),
      catalog: FakeCatalog([_track('b1', 'Song One (Live)'), _track('b2', 'Song One (Acoustic)')]),
    );
    expect(find.text('Review what will be copied'), findsOneWidget);
    expect(find.text('Step 3 of 5: Review summary'), findsOneWidget);

    await tester.tap(find.widgetWithText(FilledButton, 'Start matching'));
    await settle(tester);
    expect(find.text('Step 4 of 5: Match tracks'), findsOneWidget);
    expect(find.text('Choose a match'), findsOneWidget);
    expect(find.text('Song One (Acoustic)'), findsNothing);

    // An unsure song opens its candidates; picking one checks it.
    await tester.tap(find.text('Choose a match'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Song One (Acoustic)'));
    await tester.pumpAndSettle();
    expect(find.text('→ Song One (Acoustic)'), findsOneWidget);

    await tester.tap(find.widgetWithText(FilledButton, 'Start copying'));
    await settle(tester);
    expect(find.text('Copied to Bob'), findsOneWidget);
    final notes = await tester.runAsync(() => device.stores.memory('p2').syncRead());
    expect(notes!.songNotes.single.trackId, MediaId('subsonic', 'b2'));

    await tester.tap(find.widgetWithText(FilledButton, 'Done'));
    await tester.pumpAndSettle();
    expect(messages, ['Copy complete. Alice keeps all its data.']);
  });

  testWidgets('should_offerExportImportAndTransfer_when_cloudSyncOff', (tester) async {
    await pump(tester, PortabilityDevice(profiles: [alice]), const CloudSyncScreen());
    expect(find.text('Backup file'), findsOneWidget);
    expect(find.text('Download all data'), findsOneWidget);
    expect(find.text('Import from backup'), findsOneWidget);
    expect(find.text('Transfer Memories'), findsOneWidget);
    // Nothing picked: the page stays.
    await tester.tap(find.text('Import from backup'));
    await settle(tester);
    expect(find.byType(BackupScreen), findsNothing);
  });
}
