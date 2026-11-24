// Export all data and Import from backup's view model over the real
// portability service (in-memory stores, Cloud sync never on).
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/portability/backup_import.dart';
import 'package:yoin/data/sync/cloud_sync_state.dart';
import 'package:yoin/data/sync/sync_contracts.dart';
import 'package:yoin/data/sync/sync_database.dart';
import 'package:yoin/settings/sync/backup_view_model.dart';

import '../../data/portability/portability_device.dart';
import 'cloud_sync_screen_test.dart' show FakeCloudSync;
import 'portability_fixtures.dart';

void main() {
  late Directory cache;
  late FakeDocumentFiles files;
  late FakeCloudSync sync;

  setUp(() async {
    cache = await Directory.systemTemp.createTemp('yoin_backup_vm');
    files = FakeDocumentFiles();
    sync = FakeCloudSync();
  });

  tearDown(() async {
    if (await cache.exists()) await cache.delete(recursive: true);
  });

  Future<ProviderContainer> containerFor(PortabilityDevice device, {SyncDatabase? replica}) async {
    final container = ProviderContainer(
      overrides: portabilityOverrides(
        portability: await portabilityOf(device, replica: replica),
        sync: sync,
        files: files,
        cache: cache,
      ),
    );
    addTearDown(container.dispose);
    // The page keeps the flow alive while it shows.
    container.listen(backupProvider, (_, _) {});
    return container;
  }

  /// Alice's phone exports through the flow; the zip the picker kept.
  Future<Uint8List> exportFromPhone() async {
    final phone = PortabilityDevice(profiles: [alice]);
    await seedAlice(phone);
    final container = await containerFor(phone);
    await container.read(backupProvider.notifier).export(language: 'en');
    return files.saved.values.single;
  }

  test('should_saveAndSummarize_when_exportFinishes', () async {
    final phone = PortabilityDevice(profiles: [alice]);
    await seedAlice(phone);
    final container = await containerFor(phone);
    final export = container.read(backupProvider.notifier).export(language: 'en');
    expect(container.read(backupProvider), isA<BackupExporting>().having((s) => s.fromCloud, 'fromCloud', isFalse));
    await export;
    final state = container.read(backupProvider) as BackupExported;
    expect(state.fileName, 'yoin-backup-2026-11-23.zip');
    expect(files.saved.keys.single, state.fileName);
    expect(state.bytes, files.saved.values.single.length);
    expect(state.summary.manifest.accounts.single.displayName, 'Alice');
  });

  test('should_fail_when_thePickerKeepsNothing', () async {
    files.save = false;
    final container = await containerFor(PortabilityDevice(profiles: [alice]));
    await container.read(backupProvider.notifier).export(language: 'en');
    expect(container.read(backupProvider), isA<BackupExportFailed>());
  });

  test('should_reviewWithTheSameAccountChosen_when_ownBackupRead', () async {
    final bytes = await exportFromPhone();
    final container = await containerFor(PortabilityDevice(profiles: [alice]));
    await container.read(backupProvider.notifier).read(bytes);
    final review = container.read(backupProvider) as BackupReview;
    final account = review.plan.accounts.single;
    expect(account.sameAccount, isTrue);
    expect(review.choiceOf(account), const ImportIntoProfile('p1'));
    expect(importTargetOptions(account), [const ImportIntoProfile('p1'), ImportTarget.skip]);
    expect(review.canImport, isTrue);
    expect(review.intoCloud, isFalse);
  });

  test('should_tellWhy_when_fileIsNotABackup', () async {
    final container = await containerFor(PortabilityDevice(profiles: [alice]));
    await container.read(backupProvider.notifier).read(Uint8List.fromList([1, 2, 3, 4]));
    expect(
      container.read(backupProvider),
      isA<BackupReadFailed>().having((s) => s.reason, 'reason', BackupReadFailure.notABackup),
    );
  });

  test('should_importWithoutSyncing_when_cloudSyncOff', () async {
    final bytes = await exportFromPhone();
    final tablet = PortabilityDevice(profiles: [alice]);
    final container = await containerFor(tablet);
    final vm = container.read(backupProvider.notifier);
    await vm.read(bytes);
    final importing = vm.confirmImport();
    expect(container.read(backupProvider), isA<BackupImporting>());
    expect(vm.busy, isTrue);
    await importing;
    final done = container.read(backupProvider) as BackupImported;
    expect(done.result.changed, isTrue);
    expect(done.result.byScope.values.single.written, greaterThan(0));
    expect(sync.calls, isNot(contains('syncNow')));
    expect((await tablet.stores.memory('p1').syncRead()).songNotes.single.content, 'rain');
  });

  test('should_syncAfterwards_when_importChangedSomethingWithSyncOn', () async {
    final bytes = await exportFromPhone();
    sync.value = const CloudSyncState(phase: SyncUpToDate());
    final container = await containerFor(PortabilityDevice(profiles: [alice]));
    final vm = container.read(backupProvider.notifier);
    await vm.read(bytes);
    expect((container.read(backupProvider) as BackupReview).intoCloud, isTrue);
    await vm.confirmImport();
    expect(sync.calls, contains('syncNow'));
  });

  test('should_refuseToImport_when_everyAccountSkippedAndNoGlobalData', () async {
    final bytes = await exportFromPhone();
    final tablet = PortabilityDevice(profiles: [alice]);
    final container = await containerFor(tablet);
    final vm = container.read(backupProvider.notifier);
    await vm.read(bytes);
    final scope = (container.read(backupProvider) as BackupReview).plan.accounts.single.account.scope;
    vm.choose(scope, ImportTarget.skip);
    final review = container.read(backupProvider) as BackupReview;
    expect(review.choices[scope], ImportTarget.skip);
    expect(review.canImport, isFalse);
    await vm.confirmImport();
    expect(container.read(backupProvider), isA<BackupReview>());
    expect((await tablet.stores.memory('p1').syncRead()).songNotes, isEmpty);
  });

  test('should_showSpotifyWithSkipOnly_when_backupHasOneAndNoSyncStoreHere', () async {
    // A backup made with sync on, carrying a Spotify account only in the cloud.
    final phone = PortabilityDevice(profiles: [alice]);
    final db = SyncDatabase(MemorySyncDbFile());
    await db.open();
    db.upsertRecord(
      SyncRecordEntity(
        kind: SyncKinds.account,
        scope: '',
        key: 's-carol',
        kindVersion: 1,
        ts: 1,
        device: 'phone',
        deleted: false,
        payload: '{"provider":"spotify","displayName":"Carol"}',
      ),
    );
    db.upsertRecord(
      SyncRecordEntity(
        kind: SyncKinds.songNote,
        scope: 's-carol',
        key: 'c1',
        kindVersion: 1,
        ts: 1,
        device: 'phone',
        deleted: false,
        payload:
            '{"trackId":"sp1","provider":"spotify","content":"cloud","createdAt":1,"updatedAt":1,"title":"T","artist":"A","positionMs":null}',
      ),
    );
    final exported = await (await portabilityOf(phone, replica: db)).export(language: 'en');

    final container = await containerFor(PortabilityDevice(profiles: [alice]));
    final vm = container.read(backupProvider.notifier);
    await vm.read(exported.bytes);
    final review = container.read(backupProvider) as BackupReview;
    final carol = review.plan.accounts.firstWhere((a) => a.account.provider == 'spotify');
    expect(carol.account.displayName, 'Carol');
    expect(importTargetOptions(carol), [ImportTarget.skip]);
    expect(review.choiceOf(carol), ImportTarget.skip);
    // Not one of its options: ignored.
    vm.choose(carol.account.scope, ImportTarget.cloudOnly);
    expect((container.read(backupProvider) as BackupReview).choiceOf(carol), ImportTarget.skip);
  });
}
