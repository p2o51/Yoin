import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/memory/memory_copy_cache.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/media_id.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/data/sync/adapters/track_rating_sync_adapter.dart';
import 'package:yoin/data/sync/auth/google_oauth.dart';
import 'package:yoin/data/sync/cloud_sync_manager.dart';
import 'package:yoin/data/sync/cloud_sync_providers.dart';
import 'package:yoin/data/sync/cloud_sync_state.dart';
import 'package:yoin/data/sync/identity/account_binder.dart';
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
}
