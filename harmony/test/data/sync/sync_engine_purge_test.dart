import 'dart:convert';
import 'dart:io' show gzip;
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/sync/engine/snapshot_codec.dart';
import 'package:yoin/data/sync/engine/sync_engine.dart';
import 'package:yoin/data/sync/sync_contracts.dart';
import 'package:yoin/data/sync/sync_database.dart';

import 'sync_engine_test.dart' show FakeAdapter;

/// Scope purge markers at the engine level (shared SyncEnginePurgeTest.kt):
/// what a replica forgets, refuses and stamps.
class _Sim {
  _Sim(this.id, this.now, {bool descriptors = false})
    : notes = FakeAdapter(),
      accounts = descriptors ? FakeAdapter(kind: SyncKinds.account, perAccount: false) : null;

  final String id;
  int now;
  final FakeAdapter notes;
  final FakeAdapter? accounts;
  final db = SyncDatabase(MemorySyncDbFile());
  late final SyncEngine engine;
  final bindings = <ActiveBinding>[];
  int _version = 0;

  Future<_Sim> open() async {
    await db.open();
    engine = SyncEngine(
      db: db,
      adapters: [notes, ?accounts],
      deviceId: id,
      deviceName: () => 'Device $id',
      clock: () => now,
    );
    return this;
  }

  void bind(String profile, String scope) =>
      bindings.add(ActiveBinding(localProfileId: profile, syncProfileId: scope, provider: 'subsonic'));

  Future<void> capture() => engine.capture(bindings);

  /// capture + apply, then everything this device would upload, as a peer downloads it.
  Future<List<(RemoteFile, SyncEnvelope)>> cycle() async {
    await engine.capture(bindings);
    await engine.apply(bindings);
    return published();
  }

  Future<List<(RemoteFile, SyncEnvelope)>> published() async {
    _version++;
    final out = <(RemoteFile, SyncEnvelope)>[];
    for (final file in engine.outgoingFiles(epoch: 0, writtenAt: now, appVersionCode: 1)) {
      await engine.markUploaded(file);
      out.add((
        RemoteFile(id: '$id-${file.fileKind}-${file.shard}', name: file.fileKind, version: _version),
        SnapshotCodec.decode(file.bytes),
      ));
    }
    return out;
  }

  /// Merges [files]; returns how many records were refused as purged.
  Future<int> pull(List<(RemoteFile, SyncEnvelope)> files) async {
    var purged = 0;
    for (final (file, envelope) in files) {
      purged += (await engine.mergeRemote(file, envelope)).purged;
    }
    return purged;
  }

  SyncRecordEntity? record(String kind, String scope, String key) => db.getRecord(kind, scope, key);

  void putDescriptor(String scope, int ts) => db.upsertRecord(
    SyncRecordEntity(
      kind: SyncKinds.account,
      scope: SyncFormat.globalScope,
      key: scope,
      kindVersion: 1,
      ts: ts,
      device: 'elsewhere',
      deleted: false,
      payload: '{"text":"$scope"}',
      payloadHash: 'h-$scope',
    ),
  );
}

void main() {
  test('should_forgetOnlyThatScopesRecordsAtOrBeforeTheEpoch_when_purged', () async {
    final phone = await _Sim('phone', 1000).open();
    phone.bind('p-a', 's-a');
    phone.bind('p-b', 's-b');
    phone.notes.put('p-a', 'a1', 'note a1', 1000);
    phone.notes.put('p-b', 'b1', 'note b1', 1000);
    await phone.capture();
    phone.putDescriptor('s-a', 1500);
    phone.putDescriptor('s-b', 1500);

    final removed = await phone.engine.applyScopePurge('s-a', 2000);

    expect(removed, 2, reason: 'note + descriptor');
    expect(phone.record('note', 's-a', 'a1'), isNull);
    expect(phone.record(SyncKinds.account, SyncFormat.globalScope, 's-a'), isNull);
    expect(phone.record('note', 's-b', 'b1'), isNotNull);
    expect(phone.record(SyncKinds.account, SyncFormat.globalScope, 's-b'), isNotNull);
    expect(phone.db.localStatesInScope('s-a'), isEmpty);
    expect(phone.engine.purgedScopes(), {'s-a': 2000});
    expect(await phone.engine.applyScopePurge('s-a', 2000), 0, reason: 'idempotent');
    expect(await phone.engine.applyScopePurge('s-a', 1000), 0, reason: 'an older marker changes nothing');
    expect(phone.notes.table('p-a').keys, ['a1'], reason: 'domain rows stay');
  });

  test('should_refuseTheScope_when_aPeerThatDoesntKnowThePurgeStillRelaysIt', () async {
    final old = await _Sim('old', 1000).open();
    old.bind('p', 's-a');
    old.notes.put('p', 'n1', 'one', 1000);
    await old.cycle();
    // A clock far ahead: its later edit is "after" the epoch, but the file still doesn't know the purge.
    old.now = 50000;
    old.notes.put('p', 'n2', 'two', 50000);
    final files = await old.cycle();

    final phone = await _Sim('phone', 5000).open();
    await phone.engine.applyScopePurge('s-a', 3000);
    final purged = await phone.pull(files);

    expect(purged, 2);
    expect(phone.db.recordsInScope('s-a'), isEmpty);
  });

  test('should_acceptNewerVersions_when_theWriterAcknowledgesThePurge', () async {
    final resumed = await _Sim('resumed', 10000).open();
    await resumed.engine.applyScopePurge('s-a', 3000);
    resumed.bind('p', 's-a');
    resumed.notes.put('p', 'n1', 'one', 1000);
    final files = await resumed.cycle();

    final phone = await _Sim('phone', 20000).open();
    await phone.engine.applyScopePurge('s-a', 3000);
    await phone.pull(files);

    expect(phone.record('note', 's-a', 'n1')?.ts, 3001, reason: 'stamped after the epoch by the resumed device');
    expect(files.first.$2.purges, {'s-a': 3000});
  });

  test('should_acceptTheResumedAccountsDescriptor_when_aPeerKnowsThePurge', () async {
    final resumed = await _Sim('resumed', 10000, descriptors: true).open();
    await resumed.engine.applyScopePurge('s-a', 3000);
    // The descriptor's own time (the profile's creation) is long before the purge.
    resumed.accounts!.put(null, 's-a', 'subsonic', 1000);
    resumed.bind('p', 's-a');
    resumed.notes.put('p', 'n1', 'one', 1000);
    final files = await resumed.cycle();

    final peer = await _Sim('peer', 20000, descriptors: true).open();
    await peer.engine.applyScopePurge('s-a', 3000);
    final refused = await peer.pull(files);

    expect(refused, 0);
    expect(peer.record(SyncKinds.account, SyncFormat.globalScope, 's-a')?.ts, 3001);
    expect(peer.record('note', 's-a', 'n1')?.ts, 3001);
  });

  test('should_refuseThePurgedAccountsDescriptor_when_merged', () async {
    final phone = await _Sim('phone', 5000).open();
    await phone.engine.applyScopePurge('s-a', 3000);
    const envelope = SyncEnvelope(
      purges: {'s-b': 1},
      deviceId: 'peer',
      deviceName: 'Peer',
      fileClass: 'state',
      writtenAt: 2000,
      appVersionCode: 1,
      records: [
        WireRecord(kind: SyncKinds.account, key: 's-a', ts: 2000, device: 'peer', payload: {'provider': 'subsonic'}),
        WireRecord(kind: SyncKinds.account, key: 's-b', ts: 2000, device: 'peer', payload: {'provider': 'subsonic'}),
      ],
    );

    final result = await phone.engine.mergeRemote(const RemoteFile(id: 'f', name: 'state', version: 1), envelope);

    expect(result.purged, 1);
    expect(phone.record(SyncKinds.account, SyncFormat.globalScope, 's-a'), isNull);
    expect(phone.record(SyncKinds.account, SyncFormat.globalScope, 's-b'), isNotNull);
  });

  test('should_stampNewVersionsAfterTheEpoch_when_aResumedBindingCapturesOldRows', () async {
    final phone = await _Sim('phone', 10000).open();
    phone.notes.put('p', 'n1', 'one', 1000);
    await phone.engine.applyScopePurge('s-a', 9000);
    phone.bind('p', 's-a');

    await phone.capture();

    expect(phone.record('note', 's-a', 'n1')!.ts, 9001);
    final published = (await phone.published()).first.$2.records;
    expect(published.any((r) => r.key == 'n1' && r.ts == 9001), isTrue);
  });

  test('should_neverPublishPurgedRecords_when_theReplicaIsSerialized', () async {
    final phone = await _Sim('phone', 1000).open();
    phone.bind('p', 's-a');
    phone.notes.put('p', 'n1', 'one', 1000);
    await phone.capture();
    await phone.engine.applyScopePurge('s-a', 2000);

    final files = await phone.published();

    expect(files.any((f) => f.$2.records.any((r) => r.scope == 's-a')), isFalse);
  });

  test('should_publishTheAcknowledgement_when_onlyAPurgeIsNew', () async {
    final phone = await _Sim('phone', 1000).open();
    final before = phone.engine.outgoingFiles(epoch: 0, writtenAt: 1, appVersionCode: 1).first;
    expect(before.bytes, isNot(contains(utf8.encode('purges'))), reason: 'no purges, no field');

    await phone.engine.applyScopePurge('s-a', 2000);
    final after = phone.engine.outgoingFiles(epoch: 0, writtenAt: 1, appVersionCode: 1).first;

    expect(after.contentHash, isNot(before.contentHash));
    expect(SnapshotCodec.decode(after.bytes).purges, {'s-a': 2000});
  });

  test('should_forgetPurgesAndResumePausedBindings_when_theReplicaIsCleared', () async {
    final phone = await _Sim('phone', 1000).open();
    await phone.engine.applyScopePurge('s-a', 2000);
    phone.db.upsertBinding(
      const SyncBindingEntity(
        localProfileId: 'p',
        syncProfileId: 's-a',
        provider: 'subsonic',
        fingerprintHash: 'fp',
        state: SyncBindingEntity.statePurged,
        boundAt: 1000,
      ),
    );

    await phone.engine.clearReplica();

    expect(phone.engine.purgedScopes(), isEmpty);
    expect(phone.db.metaWithPrefix(SyncMetaKeys.purgedScopePrefix), isEmpty);
    expect(phone.db.bindingFor('p')?.state, SyncBindingEntity.stateActive);
  });

  group('codec', () {
    test('should_roundTripThePurgeMarker_when_encoded', () {
      const marker = ScopePurgeMarker(scope: 's-a', epoch: 2000, at: 2001, deviceId: 'dev', deviceName: 'Pixel');
      final bytes = SnapshotCodec.encodePurge(marker);
      final back = SnapshotCodec.decodePurge(bytes);
      expect(back.toJson(), marker.toJson());
      // Android's field order (kotlinx).
      expect(utf8.decode(bytes), '{"scope":"s-a","epoch":2000,"at":2001,"deviceId":"dev","deviceName":"Pixel"}');
    });

    test('should_skipUnknownFieldsButRejectNoScope_when_decodingAPurgeMarker', () {
      Uint8List b(String s) => Uint8List.fromList(utf8.encode(s));
      final withExtra = b('{"scope":"s-a","epoch":5,"at":6,"deviceId":"d","deviceName":"n","future":true}');
      expect(SnapshotCodec.decodePurge(withExtra).scope, 's-a');
      final noScope = b('{"scope":"","epoch":5,"at":6,"deviceId":"d","deviceName":"n"}');
      expect(() => SnapshotCodec.decodePurge(noScope), throwsA(isA<MalformedSyncFileException>()));
    });

    test('should_readAndWritePurges_when_anEnvelopeAcknowledgesThem', () {
      const envelope = SyncEnvelope(
        deviceId: 'h1',
        deviceName: 'Mate',
        fileClass: 'state',
        writtenAt: 9,
        appVersionCode: 1,
        records: [],
        purges: {'s-a': 3000},
      );
      final text = utf8.decode(gzip.decode(SnapshotCodec.encode(envelope)));
      expect(text, endsWith('"records":[],"purges":{"s-a":3000}}'));
      expect(SnapshotCodec.decode(SnapshotCodec.encode(envelope)).purges, {'s-a': 3000});
    });
  });
}

