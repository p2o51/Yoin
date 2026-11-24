import 'dart:convert';
import 'dart:io' show gzip;

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/memory/memory_copy_cache.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/media_id.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/data/sync/adapters/track_rating_sync_adapter.dart';
import 'package:yoin/data/sync/auth/google_oauth.dart';
import 'package:yoin/data/sync/cloud_data_views.dart';
import 'package:yoin/data/sync/cloud_sync_manager.dart';
import 'package:yoin/data/sync/cloud_sync_providers.dart';
import 'package:yoin/data/sync/cloud_sync_state.dart';
import 'package:yoin/data/sync/engine/snapshot_codec.dart';
import 'package:yoin/data/sync/identity/account_binder.dart';
import 'package:yoin/data/sync/identity/account_fingerprints.dart';
import 'package:yoin/data/sync/sync_contracts.dart';
import 'package:yoin/data/sync/sync_database.dart';
import 'package:yoin/playback/local_ratings.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../../support/fake_drive.dart';

class FakeAuth implements GoogleSyncAuthorizer {
  FakeAuth({this.configured = true});

  bool configured;
  GoogleAuthResult next = const GoogleAuthToken('tok');
  int forgets = 0;

  @override
  bool get isConfigured => configured;

  @override
  Future<GoogleAuthResult> authorizeInteractively({String? loginHint}) async => next;

  @override
  void cancelWaiting() {}

  @override
  bool get isWaitingForBrowser => false;

  @override
  void invalidateCached() {}

  @override
  Future<void> forget() async => forgets++;

  @override
  Future<String> token() async => 'tok';

  @override
  Future<void> invalidate(String token) async {}
}

/// One device: its own storage, its own sync store, the shared [drive].
class Phone {
  Phone(this.name, this.drive, {bool configured = true}) : auth = FakeAuth(configured: configured);

  final String name;
  final FakeDrive drive;
  final FakeAuth auth;
  final kv = InMemoryStore();
  final secrets = InMemoryStore();
  final dbFile = MemorySyncDbFile();
  late final repo = ProfileRepository(kv, secrets);
  late CloudSyncManager sync;
  late Profile profile;
  int clock = 1000;

  /// The kinds each sync wrote into this device's storage (onDomainChanged).
  final changedKinds = <String>{};

  Future<void> start({String user = 'alice'}) async {
    profile = await repo.add(
      provider: MediaId.providerSubsonic,
      displayName: 'Home server',
      credentials: SubsonicCredentials(baseUrl: 'https://music.example.com', username: user, password: 'pw'),
    );
    sync = CloudSyncManager(
      db: SyncDatabase(dbFile),
      auth: auth,
      transportFor: (_) => FakeDriveTransport(drive),
      adapters: (db) => yoinSyncAdapters(
        db: db,
        memory: (id) => MemoryStore.shared(kv, id),
        memoryCopy: (id) => MemoryCopyCache.shared(kv, id),
        ratings: StoreTrackRatingAccess(LocalRatingStore(kv)),
        profiles: repo,
        store: kv,
      ),
      binder: (db) => AccountBinder(db: db, profiles: repo.profiles, credentials: (p) => repo.credentials(p.id)),
      deviceName: () async => name,
      packageName: yoinPackageName,
      versionCode: 1,
      clock: () => clock,
      onDomainChanged: changedKinds.addAll,
    );
  }

  MemoryStore get memory => MemoryStore.shared(kv, profile.id);

  MemoryCopyCache get words => MemoryCopyCache.shared(kv, profile.id);

  Future<void> turnOn() async {
    expect(await sync.turnOn(), isA<TurnOnStarted>());
    await sync.syncNowAndWait();
  }

  CloudSyncState get state => sync.state.value;
}

Future<void> settle() => Future<void>.delayed(const Duration(milliseconds: 20));

SongNote note(String id, String text, int at) => SongNote(
  id: id,
  trackId: MediaId.subsonic('t-$id'),
  content: text,
  createdAt: at,
  updatedAt: at,
  title: 'Song',
  artist: 'Band',
);

void main() {
  late FakeDrive drive;
  late Phone a;
  late Phone b;

  setUp(() async {
    drive = FakeDrive();
    a = Phone('Mate 70 Air', drive);
    b = Phone('Pixel 9', drive);
    await a.start();
    await b.start();
  });

  test('should_bringNoteAcross_when_bothDevicesSyncTheSameServer', () async {
    await a.memory.putSongNote(note('n1', 'from Mate', 900));
    await a.turnOn();
    expect(a.state.phase, isA<SyncUpToDate>());
    expect(a.state.accounts.single.status, isA<AccountSyncing>());
    expect(drive.files.values.map((f) => f.name), contains(startsWith('yoin-state-')));

    // The first cycle after turning on brings it down and says so.
    expect(await b.sync.turnOn(), isA<TurnOnStarted>());
    await settle();
    final notes = await b.memory.songNotes();
    expect(notes.single.content, 'from Mate');
    expect(b.state.lastSummaryCount, 1);
    expect(b.state.devices.map((d) => d.name), containsAll(['Pixel 9', 'Mate 70 Air']));
    expect(b.state.account?.email, 'me@example.com');
  });

  test('should_bringAiWordsAcross_when_bothDevicesSyncTheSameServer', () async {
    final album = MediaId.subsonic('al');
    await a.words.put(
      album,
      (_) => const MemoryCopyRow(
        copy: 'Rain on the roof.\nWhat stayed?',
        promptHash: 'narration-v2:42',
        generatedAt: 900,
        title: 'The rain year',
        titlePromptHash: '-7',
      ),
    );
    await a.turnOn();
    expect(await b.sync.turnOn(), isA<TurnOnStarted>());
    await settle();
    // The same row, so the deck there reuses both instead of asking the model again.
    expect(
      await b.words.get(album),
      const MemoryCopyRow(
        copy: 'Rain on the roof.\nWhat stayed?',
        promptHash: 'narration-v2:42',
        generatedAt: 900,
        title: 'The rain year',
        titlePromptHash: '-7',
      ),
    );
    expect(b.changedKinds, containsAll([SyncKinds.memoryCopy, SyncKinds.memoryTitle]));
  });

  test('should_keepEachDevicesHalf_when_titleAndNarrationWrittenApart', () async {
    final album = MediaId.subsonic('al');
    await a.words.put(album, (_) => const MemoryCopyRow(title: 'Night line', titlePromptHash: '3'));
    await b.words.put(album, (_) => const MemoryCopyRow(copy: 'Late trains.', promptHash: 'k', generatedAt: 50));
    await a.turnOn();
    expect(await b.sync.turnOn(), isA<TurnOnStarted>());
    await settle();
    await a.sync.syncNowAndWait();
    const both = MemoryCopyRow(
      copy: 'Late trains.',
      promptHash: 'k',
      generatedAt: 50,
      title: 'Night line',
      titlePromptHash: '3',
    );
    expect(await b.words.get(album), both);
    expect(await a.words.get(album), both);
  });

  test('should_removeNoteEverywhere_when_deletedOnOneDevice', () async {
    for (final id in ['n1', 'n2', 'n3', 'n4']) {
      await a.memory.putSongNote(note(id, id, 900));
    }
    await a.turnOn();
    await b.turnOn();
    await b.memory.deleteSongNote('n1');
    b.clock = 5000;
    await b.sync.syncNowAndWait();
    a.clock = 6000;
    await a.sync.syncNowAndWait();
    expect((await a.memory.songNotes()).map((n) => n.id), unorderedEquals(['n2', 'n3', 'n4']));
  });

  test('should_askBeforeDeletingEverywhere_when_mostNotesVanish', () async {
    for (final id in ['n1', 'n2', 'n3']) {
      await a.memory.putSongNote(note(id, id, 900));
    }
    await a.turnOn();
    for (final id in ['n1', 'n2', 'n3']) {
      await a.memory.deleteSongNote(id);
    }
    await a.sync.syncNowAndWait();
    final phase = a.state.phase;
    expect(phase, isA<SyncNeedsReview>());
    expect((phase as SyncNeedsReview).count, 3);
    expect(phase.accountName, 'Home server');
    a.sync.resolveReview(restore: true);
    await settle();
    await a.sync.syncNowAndWait();
    expect(await a.memory.songNotes(), hasLength(3));
    expect(a.state.phase, isA<SyncUpToDate>());
  });

  test('should_turnOtherDeviceOff_when_cloudDataDeleted', () async {
    await a.turnOn();
    await b.turnOn();
    await a.sync.deleteCloudData();
    expect(a.state.enabled, isFalse);
    expect(drive.files.values.single.appProperties[SyncFileProps.kind], SyncFileProps.kindReset);
    await b.sync.syncNowAndWait();
    expect(b.state.phase, const SyncResetElsewhere('Mate 70 Air'));
    // B never uploads its old data back.
    expect(drive.files.values.where((f) => f.appProperties[SyncFileProps.kind] != SyncFileProps.kindReset), isEmpty);
  });

  test('should_offerUploadAgain_when_everyDevicesCopyVanished', () async {
    await a.memory.putSongNote(note('n1', 'x', 900));
    await a.turnOn();
    drive.files.clear();
    await a.sync.syncNowAndWait();
    expect(a.state.phase, isA<SyncCloudCopyRemoved>());
    a.sync.uploadAgain();
    await settle();
    await a.sync.syncNowAndWait();
    expect(a.state.phase, isA<SyncUpToDate>());
    expect(drive.files, isNotEmpty);
  });

  test('should_showOffline_when_googleUnreachable', () async {
    await a.turnOn();
    drive.failNext = const SyncNetworkError('down');
    await a.sync.syncNowAndWait();
    expect(a.state.phase, isA<SyncOffline>());
  });

  test('should_askToSignInAgain_when_grantRevoked', () async {
    await a.turnOn();
    drive.failNext = const SyncUnauthorized('revoked');
    await a.sync.syncNowAndWait();
    expect(a.state.phase, isA<SyncNeedsReauth>());
  });

  test('should_pauseAccount_when_signedInAsSomeoneElse', () async {
    await a.turnOn();
    // Same local account, now another user on the server.
    await a.secrets.write(
      ProfileRepository.credentialsKey(a.profile.id),
      '{"type":"subsonic","baseUrl":"https://music.example.com","username":"bob","password":"pw"}',
    );
    await a.sync.syncNowAndWait();
    expect(a.state.accounts.single.status, isA<AccountChanged>());
    a.sync.keepAccountAsBefore(a.profile.id);
    await settle();
    await a.sync.syncNowAndWait();
    expect(a.state.accounts.single.status, isA<AccountSyncing>());
  });

  test('should_listDriveOnlyAccount_when_otherDeviceSyncsAnotherServer', () async {
    final c = Phone('Tablet', drive);
    await c.start(user: 'carol');
    await c.turnOn();
    await a.turnOn();
    expect(a.state.cloudOnlyAccounts.single.hint, 'carol @ music.example.com');
    expect(a.state.cloudOnlyAccounts.single.fromDeviceName, 'Tablet');
  });

  test('should_showMisconfigured_when_buildHasNoClient', () async {
    final x = Phone('X', drive, configured: false);
    await x.start();
    final step = await x.sync.turnOn();
    expect(step, isA<TurnOnFailed>());
    expect((step as TurnOnFailed).reason, TurnOnFailed.misconfigured);
    expect(x.state.phase, const SyncMisconfiguredPhase(yoinPackageName));
  });

  test('should_stayOffQuietly_when_signInCanceled', () async {
    a.auth.next = const GoogleAuthCanceled();
    expect(await a.sync.turnOn(), isA<TurnOnCanceled>());
    expect(a.state.enabled, isFalse);
  });

  test('should_forgetSignInButKeepData_when_turnedOff', () async {
    await a.memory.putSongNote(note('n1', 'x', 900));
    await a.turnOn();
    a.sync.turnOff();
    await settle();
    expect(a.state.enabled, isFalse);
    expect(a.auth.forgets, 1);
    expect(await a.memory.songNotes(), hasLength(1));
    expect(drive.files, isNotEmpty);
  });

  group('scope purge markers (another device removed an account from the cloud)', () {
    final alice = AccountFingerprints.deterministicId(
      AccountFingerprints.subsonic('https://music.example.com', 'alice')!,
    );

    /// What Android's removeAccountFromCloud writes (CloudSyncCore / CloudSyncManager).
    void plantMarker(String scope, int epoch, {String id = 'purge-1', bool withProps = true}) {
      drive.files[id] = FakeDriveFile(
        id,
        SyncFileProps.purgeFileName(epoch, 'android'),
        {
          SyncFileProps.kind: SyncFileProps.kindPurge,
          if (withProps) SyncFileProps.epoch: '$epoch',
          if (withProps) SyncFileProps.scope: scope,
          SyncFileProps.deviceId: 'android',
        },
        SnapshotCodec.encodePurge(
          ScopePurgeMarker(scope: scope, epoch: epoch, at: epoch, deviceId: 'android', deviceName: 'Pixel 9'),
        ),
      );
    }

    String dataFiles() => [
      for (final f in drive.files.values)
        if (f.appProperties[SyncFileProps.kind] != SyncFileProps.kindPurge)
          utf8.decode(SnapshotCodec.isGzip(f.bytes) ? gzip.decode(f.bytes) : f.bytes),
    ].join('\n');

    Future<void> syncAll(List<Phone> phones, int at) async {
      for (final p in phones) {
        p.clock = at;
        await p.sync.syncNowAndWait();
      }
    }

    test('should_stopEveryDeviceFromReuploading_when_anAccountIsRemovedFromTheCloud', () async {
      final c = Phone('Tablet', drive);
      await c.start(user: 'bob');
      await a.memory.putSongNote(note('n-alice', "alice's note", 900));
      await c.memory.putSongNote(note('n-bob', "bob's note", 900));
      await a.turnOn();
      await b.turnOn();
      await c.turnOn();
      expect((await b.memory.songNotes()).map((n) => n.id), ['n-alice']);

      plantMarker(alice, 2000);
      await syncAll([a, b, c], 3000);
      await b.memory.putSongNote(note('n-later', 'written after the removal', 3000));
      await syncAll([b, a, c], 4000);

      final contents = dataFiles().replaceAll(RegExp(r'"purges":\{[^}]*\}'), '');
      expect(contents, isNot(contains("alice's note")));
      expect(contents, isNot(contains('written after the removal')));
      expect(contents, isNot(contains(alice)), reason: 'descriptor and records gone too');
      expect(contents, contains("bob's note"), reason: 'other accounts keep syncing');
      expect(dataFiles(), contains('"purges":{"$alice":2000}'), reason: 'files acknowledge the purge');
      expect(a.state.accounts.single.status, isA<AccountRemovedFromCloud>());
      expect(b.state.accounts.single.status, isA<AccountRemovedFromCloud>());
      expect(c.state.accounts.single.status, isA<AccountSyncing>());
      expect((await b.memory.songNotes()).map((n) => n.id), unorderedEquals(['n-alice', 'n-later']));
      expect((await a.memory.songNotes()).map((n) => n.id), ['n-alice'], reason: 'local data stays');
      expect(c.state.cloudOnlyAccounts.where((r) => r.syncProfileId == alice), isEmpty);
    });

    test('should_readTheMarkerFile_when_itsPropertiesAreMissing', () async {
      await a.memory.putSongNote(note('n1', 'first note', 900));
      await a.turnOn();
      plantMarker(alice, 2000, withProps: false);
      await syncAll([a], 3000);
      expect(a.state.accounts.single.status, isA<AccountRemovedFromCloud>());
      expect(dataFiles(), isNot(contains('first note')));
    });

    test('should_keepANewDeviceFromUploadingIt_when_itJoinsAfterTheRemoval', () async {
      await a.memory.putSongNote(note('n1', 'first note', 900));
      await a.turnOn();
      plantMarker(alice, 2000);
      await syncAll([a], 3000);

      await b.memory.putSongNote(note('n-fresh', 'fresh device note', 3500));
      b.clock = 4000;
      await b.turnOn();

      expect(b.state.accounts.single.status, isA<AccountRemovedFromCloud>());
      expect(dataFiles(), isNot(contains('fresh device note')));
      expect((await b.memory.songNotes()).map((n) => n.id), ['n-fresh']);
    });

    test('should_syncAgainWithNewVersions_when_resumedAfterTheRemoval', () async {
      await a.memory.putSongNote(note('n1', 'kept note', 900));
      await a.turnOn();
      await b.turnOn();
      plantMarker(alice, 2000);
      await syncAll([a, b], 3000);
      expect(dataFiles(), isNot(contains('kept note')));

      a.sync.resumeAccountSync(a.profile.id);
      await settle();
      await syncAll([a], 4000);

      expect(a.state.accounts.single.status, isA<AccountSyncing>());
      expect(dataFiles(), contains('kept note'), reason: 're-published after the purge epoch');
      await syncAll([b], 5000);
      expect(
        b.state.accounts.single.status,
        isA<AccountRemovedFromCloud>(),
        reason: 'still removed on the other device until resumed there',
      );
      b.sync.resumeAccountSync(b.profile.id);
      await settle();
      await syncAll([b], 6000);
      expect(b.state.accounts.single.status, isA<AccountSyncing>());
      expect((await b.memory.songNotes()).map((n) => n.content), ['kept note']);
    });

    test('should_ignoreAMarkerFromBeforeTheReset_when_cloudDataWasDeletedSince', () async {
      await a.memory.putSongNote(note('n1', 'one', 900));
      await a.turnOn();
      plantMarker(alice, 2000);
      a.clock = 3000;
      await a.sync.deleteCloudData();

      expect(drive.files.values.map((f) => f.appProperties[SyncFileProps.kind]), [SyncFileProps.kindReset]);
      a.clock = 4000;
      await a.turnOn();
      expect(a.state.accounts.single.status, isA<AccountSyncing>(), reason: 'a reset clears the removal');
      expect(dataFiles(), contains('"one"'));
    });
  });

  group('remove an account from the cloud (from this device)', () {
    final alice = AccountFingerprints.deterministicId(
      AccountFingerprints.subsonic('https://music.example.com', 'alice')!,
    );

    String dataFiles() => [
      for (final f in drive.files.values)
        if (f.appProperties[SyncFileProps.kind] != SyncFileProps.kindPurge)
          utf8.decode(SnapshotCodec.isGzip(f.bytes) ? gzip.decode(f.bytes) : f.bytes),
    ].join('\n');

    List<FakeDriveFile> markers() => [
      for (final f in drive.files.values)
        if (f.appProperties[SyncFileProps.kind] == SyncFileProps.kindPurge) f,
    ];

    test('should_writeAndroidsMarkerAndStopUploading_when_removedHere', () async {
      final c = Phone('Tablet', drive);
      await c.start(user: 'bob');
      await a.memory.putSongNote(note('n-alice', "alice's note", 900));
      await c.memory.putSongNote(note('n-bob', "bob's note", 900));
      await a.turnOn();
      await b.turnOn();
      await c.turnOn();
      expect(a.state.accounts.single.syncProfileId, alice);
      expect(a.state.scopeCounts[alice], const CloudScopeCounts(notes: 1));

      // After every version here (the account descriptor carries the profile's real creation time).
      final at = DateTime(2100).millisecondsSinceEpoch;
      a.clock = at;
      await a.sync.removeAccountFromCloud(alice);

      final marker = markers().single;
      expect(marker.name, SyncFileProps.purgeFileName(at, marker.appProperties[SyncFileProps.deviceId]!));
      expect(marker.appProperties, {
        SyncFileProps.kind: SyncFileProps.kindPurge,
        SyncFileProps.epoch: '$at',
        SyncFileProps.scope: alice,
        SyncFileProps.deviceId: marker.appProperties[SyncFileProps.deviceId],
      });
      final body = SnapshotCodec.decodePurge(marker.bytes);
      expect([body.scope, body.epoch, body.at, body.deviceName], [alice, at, at, 'Mate 70 Air']);
      expect(a.state.accounts.single.status, isA<AccountRemovedFromCloud>());
      expect(a.state.scopeCounts, isNot(contains(alice)));
      // Re-published at once without the account, acknowledging the purge.
      expect(dataFiles(), contains('"purges":{"$alice":$at}'));

      for (final p in [b, c, a]) {
        p.clock = at + 1000;
        await p.sync.syncNowAndWait();
      }
      final contents = dataFiles().replaceAll(RegExp(r'"purges":\{[^}]*\}'), '');
      expect(contents, isNot(contains("alice's note")));
      expect(contents, isNot(contains(alice)));
      expect(contents, contains("bob's note"));
      expect(b.state.accounts.single.status, isA<AccountRemovedFromCloud>());
      expect(c.state.accounts.single.status, isA<AccountSyncing>());
      expect((await a.memory.songNotes()).map((n) => n.id), ['n-alice'], reason: 'local data stays');
      expect((await b.memory.songNotes()).map((n) => n.id), ['n-alice'], reason: 'local data stays');
    });

    test('should_removeAnAccountOnlyInTheCloud_when_noProfileHereHoldsIt', () async {
      final c = Phone('Tablet', drive);
      await c.start(user: 'bob');
      final bob = AccountFingerprints.deterministicId(
        AccountFingerprints.subsonic('https://music.example.com', 'bob')!,
      );
      await c.memory.putSongNote(note('n-bob', "bob's note", 900));
      await c.turnOn();
      await a.turnOn();
      expect(a.state.cloudOnlyAccounts.map((r) => r.syncProfileId), [bob]);
      expect(a.state.scopeCounts[bob], const CloudScopeCounts(notes: 1));
      expect((await a.sync.cloudAccountContent(bob)).notes.single.content, "bob's note");

      a.clock = 5000;
      await a.sync.removeAccountFromCloud(bob);

      expect(a.state.cloudOnlyAccounts, isEmpty);
      expect((await a.sync.cloudAccountContent(bob)).notes, isEmpty);
      c.clock = 6000;
      await c.sync.syncNowAndWait();
      expect(c.state.accounts.single.status, isA<AccountRemovedFromCloud>());
      expect((await c.memory.songNotes()).map((n) => n.id), ['n-bob']);
      expect(dataFiles().replaceAll(RegExp(r'"purges":\{[^}]*\}'), ''), isNot(contains("bob's note")));
    });

    test('should_stampTheMarkerAfterEverythingKnown_when_theClockIsBehind', () async {
      await a.memory.putSongNote(note('n1', 'one', 900));
      await a.turnOn();
      // An earlier marker of the same account (another device, its clock far ahead).
      final ahead = DateTime(2100).millisecondsSinceEpoch;
      drive.files['old-purge'] = FakeDriveFile(
        'old-purge',
        SyncFileProps.purgeFileName(ahead, 'android'),
        {
          SyncFileProps.kind: SyncFileProps.kindPurge,
          SyncFileProps.epoch: '$ahead',
          SyncFileProps.scope: alice,
          SyncFileProps.deviceId: 'android',
        },
        SnapshotCodec.encodePurge(
          ScopePurgeMarker(scope: alice, epoch: ahead, at: ahead, deviceId: 'android', deviceName: 'Pixel 9'),
        ),
      );
      a.clock = 3000;
      await a.sync.removeAccountFromCloud(alice);
      final mine = markers().where((f) => f.id != 'old-purge').single;
      expect(mine.appProperties[SyncFileProps.epoch], '${ahead + 1}');
    });

    test('should_refuseAndWriteNothing_when_googleIsAnotherAccountOrSyncIsOff', () async {
      await a.turnOn();
      drive.permissionId = 'someone-else';
      await expectLater(a.sync.removeAccountFromCloud(alice), throwsA(isA<StateError>()));
      expect(markers(), isEmpty);
      expect(a.state.accounts.single.status, isNot(isA<AccountRemovedFromCloud>()));

      drive.permissionId = 'perm-1';
      a.sync.turnOff();
      await settle();
      await expectLater(a.sync.removeAccountFromCloud(alice), throwsA(isA<StateError>()));
      expect(markers(), isEmpty);
    });

    test('should_computeAndroidsEpoch_when_choosingTheMarkersTime', () {
      RemoteFile marker(String scope, String? epoch) => RemoteFile(
        id: 'm-$scope-$epoch',
        name: 'm',
        version: 1,
        appProperties: {
          SyncFileProps.kind: SyncFileProps.kindPurge,
          SyncFileProps.scope: scope,
          SyncFileProps.epoch: ?epoch,
        },
      );
      int epoch({
        int now = 1000,
        List<int> records = const [],
        List<RemoteFile> markers = const [],
        int? applied,
        int reset = 0,
      }) => CloudSyncManager.purgeEpoch(
        now: now,
        scopeRecordTimes: records,
        markers: markers,
        scope: 's-a',
        appliedEpoch: applied,
        resetEpoch: reset,
      );
      expect(epoch(), 1000, reason: 'the clock');
      expect(epoch(records: [500, 4000]), 4000, reason: 'at (not after) the newest version');
      expect(
        epoch(markers: [marker('s-a', '5000'), marker('s-b', '9000')]),
        5001,
        reason: 'after its own markers only',
      );
      expect(epoch(markers: [marker('s-a', 'x')]), 1000, reason: 'an unreadable epoch counts as 0');
      expect(epoch(applied: 6000), 6001, reason: 'after a purge applied here');
      expect(epoch(reset: 8000), 8000, reason: 'not before the reset epoch');
      expect(epoch(now: 0), 1, reason: 'never 0: strictly after "no marker"');
    });
  });

  group('delete cloud data with album notes (phase 1b)', () {
    AlbumNote albumNote(String id, String text) => AlbumNote(
      id: id,
      albumId: MediaId.subsonic('al-1'),
      content: text,
      createdAt: 900,
      updatedAt: 900,
      albumName: 'Charm',
      artist: 'Clairo',
    );

    test('should_wipeAlbumNotesFromDriveAndKeepThemHere_when_cloudDataDeleted', () async {
      await a.memory.putAlbumNote(albumNote('an-1', 'sticky one'));
      await a.turnOn();
      await b.turnOn();
      expect((await b.memory.allAlbumNotes()).map((n) => n.content), ['sticky one']);
      expect(a.state.scopeCounts.values.single.notes, 1);

      a.clock = 5000;
      await a.sync.deleteCloudData();
      expect(drive.files.values.map((f) => f.appProperties[SyncFileProps.kind]), [SyncFileProps.kindReset]);
      expect(a.state.scopeCounts, isEmpty);

      // B sees the reset, turns off, and never uploads the album note back.
      b.clock = 6000;
      await b.sync.syncNowAndWait();
      expect(b.state.phase, const SyncResetElsewhere('Mate 70 Air'));
      expect(drive.files.values.map((f) => f.appProperties[SyncFileProps.kind]), [SyncFileProps.kindReset]);
      expect((await a.memory.allAlbumNotes()).map((n) => n.id), ['an-1'], reason: 'local data stays');
      expect((await b.memory.allAlbumNotes()).map((n) => n.id), ['an-1'], reason: 'local data stays');

      // Turning on again starts fresh from this device: the album note goes up once more, not as a tombstone.
      a.clock = 7000;
      await a.turnOn();
      final state = drive.files.values.firstWhere(
        (f) => f.appProperties[SyncFileProps.kind] == SyncFileProps.kindState,
      );
      final text = utf8.decode(gzip.decode(state.bytes));
      expect(text, contains('sticky one'));
      expect(text, isNot(contains('"del":true')));
    });
  });
}
