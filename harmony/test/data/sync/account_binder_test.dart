import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/media_id.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/sync/cloud_sync_state.dart';
import 'package:yoin/data/sync/identity/account_binder.dart';
import 'package:yoin/data/sync/identity/account_fingerprints.dart';
import 'package:yoin/data/sync/identity/cloud_account_descriptor.dart';
import 'package:yoin/data/sync/sync_contracts.dart';
import 'package:yoin/data/sync/sync_database.dart';

void main() {
  late SyncDatabase db;
  late List<Profile> profiles;
  late Map<String, ProfileCredentials> credentials;

  AccountBinder binder() => AccountBinder(
    db: db,
    profiles: () async => profiles,
    credentials: (p) async => credentials[p.id],
    clock: () => 1,
  );

  SubsonicCredentials subsonic(String user, [String url = 'https://music.example.com']) =>
      SubsonicCredentials(baseUrl: url, username: user, password: 'pw');

  setUp(() async {
    db = SyncDatabase(MemorySyncDbFile());
    await db.open();
    profiles = [];
    credentials = {};
  });

  test('should_bindToFingerprintScope_when_subsonicAccountSeenFirstTime', () async {
    profiles = [const Profile(id: 'p1', provider: MediaId.providerSubsonic, displayName: 'Home', createdAt: 1)];
    credentials = {'p1': subsonic('alice')};
    final snapshot = await binder().refresh(const []);
    final scope = AccountFingerprints.deterministicId(
      AccountFingerprints.subsonic('https://music.example.com', 'alice')!,
    );
    expect(snapshot.active.single.syncProfileId, scope);
    expect(snapshot.newlyBound, hasLength(1));
    expect(snapshot.rows.single.status, isA<AccountSyncing>());
  });

  test('should_syncOnlyTheOldest_when_sameAccountAddedTwice', () async {
    profiles = [
      const Profile(id: 'p1', provider: MediaId.providerSubsonic, displayName: 'Home', createdAt: 1),
      const Profile(id: 'p2', provider: MediaId.providerSubsonic, displayName: 'Home again', createdAt: 2),
    ];
    credentials = {'p1': subsonic('alice'), 'p2': subsonic('ALICE', 'http://music.example.com/')};
    final snapshot = await binder().refresh(const []);
    expect(snapshot.active.single.localProfileId, 'p1');
    expect(snapshot.rows.last.status, const AccountDuplicateOf('Home'));
  });

  test('should_pauseThenResume_when_userChangesAndChangesBack', () async {
    profiles = [const Profile(id: 'p1', provider: MediaId.providerSubsonic, displayName: 'Home', createdAt: 1)];
    credentials = {'p1': subsonic('alice')};
    await binder().refresh(const []);
    credentials = {'p1': subsonic('bob')};
    var snapshot = await binder().refresh(const []);
    expect(snapshot.active, isEmpty);
    expect(snapshot.rows.single.status, isA<AccountChanged>());
    credentials = {'p1': subsonic('alice')};
    snapshot = await binder().refresh(const []);
    expect(snapshot.rows.single.status, isA<AccountSyncing>());
  });

  test('should_bindToNewScope_when_startedFresh', () async {
    profiles = [const Profile(id: 'p1', provider: MediaId.providerSubsonic, displayName: 'Home', createdAt: 1)];
    credentials = {'p1': subsonic('alice')};
    final old = (await binder().refresh(const [])).active.single.syncProfileId;
    credentials = {'p1': subsonic('bob')};
    await binder().refresh(const []);
    final plan = await binder().prepareStartFresh('p1');
    expect(plan?.oldScope, old);
    expect(await binder().completeStartFresh(plan!), isTrue);
    final snapshot = await binder().refresh(const []);
    expect(snapshot.active.single.syncProfileId, isNot(old));
  });

  test('should_waitForChoice_when_appleMusicAccount', () async {
    profiles = [
      const Profile(id: 'a1', provider: MediaId.providerAppleMusic, displayName: 'Apple Music', createdAt: 1),
    ];
    credentials = {'a1': const AppleMusicCredentials(endpoint: 'https://t', musicUserToken: 'x')};
    const cloud = CloudAccountDescriptor(
      syncProfileId: 'a-1',
      provider: MediaId.providerAppleMusic,
      displayName: 'Apple Music',
      deviceName: 'iPhone',
    );
    var snapshot = await binder().refresh(const [cloud]);
    expect(snapshot.active, isEmpty);
    final status = snapshot.rows.single.status as AccountNotSynced;
    expect(status.linkCandidates.single.syncProfileId, 'a-1');
    // Linking needs the descriptor in the replica.
    expect(await binder().link('a1', 'a-1'), isFalse);
    db.upsertRecord(
      const SyncRecordEntity(
        kind: SyncKinds.account,
        scope: '',
        key: 'a-1',
        kindVersion: 1,
        ts: 1,
        device: 'z',
        deleted: false,
        payload: '{"provider":"applemusic","displayName":"Apple Music"}',
      ),
    );
    expect(await binder().link('a1', 'a-1'), isTrue);
    snapshot = await binder().refresh(const [cloud]);
    expect(snapshot.active.single.syncProfileId, 'a-1');
    expect(snapshot.cloudOnly, isEmpty);
  });

  test('should_startOwnScope_when_appleMusicStartsSyncing', () async {
    profiles = [
      const Profile(id: 'a1', provider: MediaId.providerAppleMusic, displayName: 'Apple Music', createdAt: 1),
    ];
    credentials = {'a1': const AppleMusicCredentials(endpoint: 'https://t', musicUserToken: 'x')};
    final bound = await binder().startSyncing('a1');
    expect(bound?.syncProfileId, startsWith(AccountBinder.appleScopePrefix));
  });

  test('should_unbindAndKeepRecords_when_profileRemoved', () async {
    profiles = [const Profile(id: 'p1', provider: MediaId.providerSubsonic, displayName: 'Home', createdAt: 1)];
    credentials = {'p1': subsonic('alice')};
    await binder().refresh(const []);
    profiles = [];
    final snapshot = await binder().refresh(const []);
    expect(snapshot.active, isEmpty);
    expect(db.bindings(), isEmpty);
  });

  test('should_askToReconnect_when_credentialsMissing', () async {
    profiles = [const Profile(id: 'p1', provider: MediaId.providerSubsonic, displayName: 'Home', createdAt: 1)];
    final snapshot = await binder().refresh(const []);
    expect(snapshot.rows.single.status, isA<AccountNeedsReconnect>());
  });
}
