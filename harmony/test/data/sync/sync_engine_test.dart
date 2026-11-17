import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/sync/canonical_json.dart';
import 'package:yoin/data/sync/engine/snapshot_codec.dart';
import 'package:yoin/data/sync/engine/sync_engine.dart';
import 'package:yoin/data/sync/sync_contracts.dart';
import 'package:yoin/data/sync/sync_database.dart';

/// A domain table of text rows: key -> {text}, rowTs = when written.
class FakeAdapter extends SyncAdapter {
  FakeAdapter({
    this.kind = 'note',
    this.perAccount = true,
    this.propagatesDeletes = true,
    this.conflictPolicy = ConflictPolicy.lwwLiveWins,
  });

  @override
  final String kind;
  @override
  int get kindVersion => 1;
  @override
  FileClass get fileClass => FileClass.state;
  @override
  final bool perAccount;
  @override
  final bool propagatesDeletes;
  @override
  final ConflictPolicy conflictPolicy;

  /// profile ('' for global) -> key -> (text, ts)
  final Map<String, Map<String, (String, int)>> rows = {};

  Map<String, (String, int)> table(String? profile) => rows.putIfAbsent(profile ?? '', () => {});

  void put(String? profile, String key, String text, int ts) => table(profile)[key] = (text, ts);

  JsonMap _projection(String text) => {'text': text};

  @override
  Future<List<DomainRow>> readAll(String? localProfileId) async => [
    for (final e in table(localProfileId).entries)
      DomainRow(key: e.key, projection: _projection(e.value.$1), rowTs: e.value.$2),
  ];

  @override
  JsonMap? project(JsonMap payload) => payload['text'] is String ? _projection(payload['text'] as String) : null;

  String? _hash(String? profile, String key) {
    final row = table(profile)[key];
    return row == null ? null : CanonicalJson.hash(_projection(row.$1));
  }

  @override
  Future<ApplyOutcome> apply(
    String? localProfileId,
    String key,
    JsonMap payload,
    int versionTs,
    String? expected,
  ) async {
    final current = _hash(localProfileId, key);
    final text = payload['text'];
    if (text is! String) return Skipped(SkipReason.unsupported, current);
    if (current != expected) return Skipped(SkipReason.changedLocally, current);
    put(localProfileId, key, text, versionTs);
    return Applied(_hash(localProfileId, key));
  }

  @override
  Future<ApplyOutcome> delete(String? localProfileId, String key, String? expected) async {
    final current = _hash(localProfileId, key);
    if (current == null) return const Applied(null);
    if (current != expected) return Skipped(SkipReason.changedLocally, current);
    table(localProfileId).remove(key);
    return const Applied(null);
  }

  @override
  JsonMap? mergeConcurrent(JsonMap winner, JsonMap loser, String loserLabel) {
    if (conflictPolicy != ConflictPolicy.keepBothText) return null;
    return {...winner, 'text': '${winner['text']}\n\n— $loserLabel —\n${loser['text']}'};
  }
}

class Device {
  Device(this.id, {List<FakeAdapter>? adapters}) : adapters = adapters ?? [FakeAdapter()] {
    db = SyncDatabase(MemorySyncDbFile());
  }

  final String id;
  final List<FakeAdapter> adapters;
  late final SyncDatabase db;
  late final SyncEngine engine;
  int now = 1000;

  FakeAdapter get notes => adapters.first;

  static const binding = ActiveBinding(localProfileId: 'p', syncProfileId: 's-1', provider: 'subsonic');

  Future<void> open() async {
    await db.open();
    engine = SyncEngine(db: db, adapters: adapters, deviceId: id, deviceName: () => 'Device $id', clock: () => now);
  }

  /// capture + apply, like one local cycle.
  Future<void> cycle() async {
    await engine.capture([binding]);
    await engine.apply([binding]);
  }

  /// This device's state file as a peer downloads it.
  Future<(RemoteFile, SyncEnvelope)> publish({int version = 1}) async {
    final files = engine.outgoingFiles(epoch: 0, writtenAt: now, appVersionCode: 1);
    final state = files.first;
    for (final f in files) {
      await engine.markUploaded(f);
    }
    return (RemoteFile(id: 'file-$id', name: 'state', version: version), SnapshotCodec.decode(state.bytes));
  }

  Future<void> merge((RemoteFile, SyncEnvelope) file) => engine.mergeRemote(file.$1, file.$2);
}

Future<(Device, Device)> pair({List<FakeAdapter> Function()? adapters}) async {
  final a = Device('A', adapters: adapters?.call());
  final b = Device('B', adapters: adapters?.call());
  await a.open();
  await b.open();
  return (a, b);
}

void main() {
  test('should_publishNewRowAsPending_when_captured', () async {
    final (a, _) = await pair();
    a.notes.put('p', 'n1', 'hello', 500);
    final result = await a.engine.capture([Device.binding]);
    expect(result.totalNewVersions, 1);
    expect(a.engine.hasPendingChanges(), isTrue);
    final record = a.db.getRecord('note', 's-1', 'n1')!;
    expect(record.version, const RecordVersion(500, 'A'));
    expect(parseJsonObject(record.payload), {'text': 'hello'});
    await a.publish();
    await Future<void>.delayed(Duration.zero);
    expect(a.engine.hasPendingChanges(), isFalse);
  });

  test('should_applyPeerRow_when_peerFileMerged', () async {
    final (a, b) = await pair();
    a.notes.put('p', 'n1', 'hello', 500);
    await a.cycle();
    await b.merge(await a.publish());
    await b.cycle();
    expect(b.notes.table('p')['n1']!.$1, 'hello');
    // Nothing new to publish from B: it adopted A's version.
    expect(b.engine.hasPendingChanges(), isFalse);
  });

  test('should_takeNewerVersion_when_peerEditsAfterUs', () async {
    final (a, b) = await pair();
    a.notes.put('p', 'n1', 'v1', 500);
    await a.cycle();
    await b.merge(await a.publish());
    await b.cycle();
    b.now = 2000;
    b.notes.put('p', 'n1', 'v2', 2000);
    await b.cycle();
    await a.merge(await b.publish(version: 2));
    await a.cycle();
    expect(a.notes.table('p')['n1']!.$1, 'v2');
  });

  test('should_keepBothTexts_when_reviewEditedOnTwoDevicesAtOnce', () async {
    final (a, b) = await pair(
      adapters: () => [FakeAdapter(kind: 'review', conflictPolicy: ConflictPolicy.keepBothText)],
    );
    a.notes.put('p', 'r', 'base', 500);
    await a.cycle();
    await b.merge(await a.publish());
    await b.cycle();
    // Both edit before seeing the other.
    a.now = 3000;
    a.notes.put('p', 'r', 'from A', 3000);
    await a.cycle();
    b.now = 4000;
    b.notes.put('p', 'r', 'from B', 4000);
    await b.cycle();
    await b.merge(await a.publish(version: 2));
    await b.cycle();
    final merged = b.notes.table('p')['r']!.$1;
    expect(merged, startsWith('from B\n\n— Device A · 1970-01-01 —\nfrom A'));
  });

  test('should_deleteOnPeer_when_noteDeletedHere', () async {
    final (a, b) = await pair();
    a.notes.put('p', 'n1', 'x', 500);
    a.notes.put('p', 'n2', 'y', 500);
    a.notes.put('p', 'n3', 'z', 500);
    a.notes.put('p', 'n4', 'w', 500);
    await a.cycle();
    await b.merge(await a.publish());
    await b.cycle();
    a.now = 2000;
    a.notes.table('p').remove('n1');
    final capture = await a.engine.capture([Device.binding]);
    expect(capture.totalTombstones, 1);
    await b.merge(await a.publish(version: 2));
    final applied = await b.engine.apply([Device.binding]);
    expect(applied.totalRemoved, 1);
    expect(b.notes.table('p').containsKey('n1'), isFalse);
  });

  test('should_holdForReview_when_mostNotesVanishAtOnce', () async {
    final (a, _) = await pair();
    for (final k in ['n1', 'n2', 'n3', 'n4']) {
      a.notes.put('p', k, k, 500);
    }
    await a.cycle();
    await a.publish();
    a.now = 2000;
    for (final k in ['n1', 'n2', 'n3']) {
      a.notes.table('p').remove(k);
    }
    final capture = await a.engine.capture([Device.binding]);
    expect(capture.totalTombstones, 0);
    expect(capture.heldReview?.keys, ['n1', 'n2', 'n3']);
    // Restore here: the next apply brings them back.
    await a.engine.resolveHeldReview(restore: true);
    await a.engine.apply([Device.binding]);
    expect(a.notes.table('p').keys, containsAll(['n1', 'n2', 'n3', 'n4']));
    expect(a.engine.heldReview(), isNull);
  });

  test('should_writeTombstones_when_reviewResolvedAsDelete', () async {
    final (a, _) = await pair();
    for (final k in ['n1', 'n2', 'n3']) {
      a.notes.put('p', k, k, 500);
    }
    await a.cycle();
    await a.publish();
    a.now = 2000;
    a.notes.table('p').clear();
    await a.engine.capture([Device.binding]);
    expect(a.engine.heldReview()?.count, 3);
    expect(await a.engine.resolveHeldReview(restore: false), 3);
    expect(a.db.recordsFor('note', 's-1').every((r) => r.deleted), isTrue);
  });

  test('should_keepLiveNote_when_deletedHereButEditedConcurrentlyElsewhere', () async {
    final (a, b) = await pair();
    for (final k in ['n1', 'n2', 'n3', 'n4', 'n5']) {
      a.notes.put('p', k, k, 500);
    }
    await a.cycle();
    await b.merge(await a.publish());
    await b.cycle();
    // B deletes n1 and publishes nothing yet; A edits n1 later.
    b.now = 2000;
    b.notes.table('p').remove('n1');
    await b.engine.capture([Device.binding]);
    a.now = 3000;
    a.notes.put('p', 'n1', 'edited', 3000);
    await a.cycle();
    await b.merge(await a.publish(version: 2));
    await b.cycle();
    expect(b.notes.table('p')['n1']?.$1, 'edited');
  });

  test('should_rebindWithoutTombstones_when_neverDeletedRowsVanish', () async {
    final (a, _) = await pair(
      adapters: () => [FakeAdapter(kind: 'rating', propagatesDeletes: false, conflictPolicy: ConflictPolicy.lww)],
    );
    a.notes.put('p', 'r1', '5', 500);
    a.notes.put('p', 'r2', '7', 500);
    await a.cycle();
    await a.publish();
    a.notes.table('p').clear();
    final capture = await a.engine.capture([Device.binding]);
    expect(capture.domainResets, ['s-1']);
    expect(capture.totalTombstones, 0);
    await a.engine.apply([Device.binding]);
    expect(a.notes.table('p').keys, containsAll(['r1', 'r2']));
  });

  test('should_carryUnknownKinds_when_republishing', () async {
    final (a, b) = await pair();
    final foreign = SyncEnvelope(
      deviceId: 'Z',
      deviceName: 'Pixel',
      fileClass: 'artifacts',
      writtenAt: 1,
      appVersionCode: 99,
      records: const [
        WireRecord(kind: 'lyrics_translation', key: 'k', ts: 5, device: 'Z', payload: {'lines': 'x'}),
      ],
    );
    final result = await a.engine.mergeRemote(const RemoteFile(id: 'z', name: 'z', version: 1), foreign);
    expect(result.opaque, 1);
    final files = a.engine.outgoingFiles(epoch: 0, writtenAt: 1, appVersionCode: 1);
    final shard = SyncEngine.artifactShard('k');
    final artifact = SnapshotCodec.decode(files[1 + shard].bytes);
    expect(artifact.records.single.kind, 'lyrics_translation');
    expect(artifact.records.single.payload, {'lines': 'x'});
    expect(b.engine.hasPendingChanges(), isFalse);
  });

  test('should_tripValve_when_threeOfFourOrAll', () {
    expect(SyncEngine.valveTrips(3, 4), isTrue);
    expect(SyncEngine.valveTrips(2, 4), isFalse);
    expect(SyncEngine.valveTrips(2, 2), isTrue);
    expect(SyncEngine.valveTrips(1, 10), isFalse);
  });

  test('should_restoreTables_when_transactionThrows', () async {
    final db = SyncDatabase(MemorySyncDbFile());
    await db.open();
    db.putMeta('a', '1');
    await expectLater(
      db.transaction(() async {
        db.putMeta('a', '2');
        throw StateError('boom');
      }),
      throwsStateError,
    );
    expect(db.meta('a'), '1');
  });

  test('should_readBackSnapshot_when_reopened', () async {
    final file = MemorySyncDbFile();
    final db = SyncDatabase(file);
    await db.open();
    db.putMeta('device_id', 'abc');
    db.upsertBinding(
      const SyncBindingEntity(
        localProfileId: 'p',
        syncProfileId: 's',
        provider: 'subsonic',
        state: 'active',
        boundAt: 1,
      ),
    );
    await db.flush();
    final reopened = SyncDatabase(file);
    await reopened.open();
    expect(reopened.meta('device_id'), 'abc');
    expect(reopened.bindingFor('p')?.syncProfileId, 's');
  });
}
