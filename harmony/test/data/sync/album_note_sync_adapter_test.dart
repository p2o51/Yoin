// AlbumNoteSyncAdapter against Android's wire shape (app/src/test/.../AlbumNoteSyncAdapterTest.kt and
// adapters/AlbumNoteSyncAdapter.kt): the same payload fields, ids as keys, tombstones, and timestamps.
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/media_id.dart';
import 'package:yoin/data/sync/adapters/memory_sync_adapters.dart';
import 'package:yoin/data/sync/adapters/sync_projections.dart';
import 'package:yoin/data/sync/canonical_json.dart';
import 'package:yoin/data/sync/engine/snapshot_codec.dart';
import 'package:yoin/data/sync/engine/sync_engine.dart';
import 'package:yoin/data/sync/sync_contracts.dart';
import 'package:yoin/data/sync/sync_database.dart';
import 'package:yoin/platform/storage/stores.dart';

/// Android's AlbumNoteSyncAdapterTest payload, as an Android phone writes it (plus a field from a newer build).
const _remote = '''
{"albumId":"al-1","provider":"spotify","content":"像 [@Andromeda](yoin:track/spotify:t2) 的白天",
 "createdAt":100,"updatedAt":150,"albumName":"Charm","artist":"Clairo","futureField":true}''';

JsonMap _payload(String json) => (jsonDecode(json) as Map).cast<String, Object?>();

/// An Android state file holding [records] (plain JSON; SnapshotCodec sniffs gzip).
SyncEnvelope _androidFile(List<Map<String, Object?>> records) => SnapshotCodec.decode(
  Uint8List.fromList(
    utf8.encode(
      jsonEncode({
        'format': 'yoin-sync',
        'schema': 1,
        'epoch': 0,
        'deviceId': 'pixel',
        'deviceName': 'Pixel',
        'fileClass': 'state',
        'shard': 0,
        'writtenAt': 200,
        'appVersionCode': 28,
        'records': records,
      }),
    ),
  ),
);

void main() {
  late InMemoryStore kv;
  MemoryStore memory(String id) => MemoryStore.shared(kv, id);
  late AlbumNoteSyncAdapter adapter;

  setUp(() {
    kv = InMemoryStore();
    adapter = AlbumNoteSyncAdapter(memory);
  });

  AlbumNote note(String id, {String content = 'hello'}) => AlbumNote(
    id: id,
    albumId: MediaId('spotify', 'al-1'),
    content: content,
    createdAt: 10,
    updatedAt: 20,
    albumName: 'Charm',
    artist: 'Clairo',
  );

  test('should_declareTheSongNoteContract_forTheAlbumNoteKind', () {
    expect(SyncKinds.albumNote, 'album_note');
    expect(adapter.kind, SyncKinds.albumNote);
    expect(adapter.kindVersion, 1);
    expect(adapter.fileClass, FileClass.state);
    expect(adapter.perAccount, isTrue);
    expect(adapter.propagatesDeletes, isTrue);
    expect(adapter.conflictPolicy, ConflictPolicy.lwwLiveWins);
  });

  test('should_projectAndroidsFields_when_noteRead', () async {
    await memory('p1').putAlbumNote(note('an1'));

    final row = (await adapter.readAll('p1')).single;

    expect(row.key, 'an1');
    expect(row.rowTs, 20);
    expect(row.projection, {
      'albumId': 'al-1',
      'provider': 'spotify',
      'content': 'hello',
      'createdAt': 10,
      'updatedAt': 20,
      'albumName': 'Charm',
      'artist': 'Clairo',
    });
  });

  test('should_readBackSameProjection_when_androidPayloadApplied', () async {
    final remote = _payload(_remote);
    final changes = <MemoryStoreChange>[];
    final sub = memory('p1').changes.listen(changes.add);
    addTearDown(sub.cancel);

    final outcome = await adapter.apply('p1', 'an1', remote, 150, null);

    final expected = hashOrNull(adapter.project(remote));
    expect(outcome, isA<Applied>());
    expect((outcome as Applied).localHash, expected);
    final row = (await adapter.readAll('p1')).single;
    expect(CanonicalJson.hash(row.projection), expected);
    expect(row.key, 'an1');
    expect(row.rowTs, 150);
    final stored = (await memory('p1').albumNotes(MediaId('spotify', 'al-1'))).single;
    expect(stored.content, '像 [@Andromeda](yoin:track/spotify:t2) 的白天');
    expect(stored.createdAt, 100);
    expect(stored.updatedAt, 150);
    expect(stored.albumName, 'Charm');
    await Future<void>.delayed(Duration.zero);
    expect(changes, contains(MemoryStoreChange.albumNotes));
  });

  test('should_keepSeveralNotesPerAlbum', () async {
    await adapter.apply('p1', 'an1', _payload(_remote), 150, null);
    await adapter.apply('p1', 'an2', _payload(_remote), 160, null);

    expect({for (final r in await adapter.readAll('p1')) r.key}, {'an1', 'an2'});
  });

  test('should_skipChangedLocally_when_rowDiffersFromExpectedHash', () async {
    await memory('p1').putAlbumNote(note('an1', content: 'edited here'));
    final localHash = CanonicalJson.hash((await adapter.readAll('p1')).single.projection);

    final outcome = await adapter.apply('p1', 'an1', _payload(_remote), 200, 'stale');

    expect((outcome as Skipped).reason, SkipReason.changedLocally);
    expect(outcome.localHash, localHash);
    expect((await memory('p1').albumNotes(MediaId('spotify', 'al-1'))).single.content, 'edited here');
  });

  test('should_keepEachAccountsNotesApart_when_anotherAccountHoldsTheId', () async {
    await memory('p2').putAlbumNote(note('an1', content: 'theirs'));

    expect(await adapter.apply('p1', 'an1', _payload(_remote), 200, null), isA<Applied>());
    // and a delete from p1's scope never touches p2's row
    _expectApplied(await adapter.delete('p1', 'an1', hashOrNull(adapter.project(_payload(_remote)))));
    expect((await memory('p2').syncRead()).albumNotes.single.content, 'theirs');
    expect((await memory('p1').syncRead()).albumNotes, isEmpty);
  });

  test('should_deleteNote_when_hashMatches_andKeepIt_whenEditedLocally', () async {
    await memory('p1').putAlbumNote(note('an1'));
    final hash = CanonicalJson.hash((await adapter.readAll('p1')).single.projection);

    final stale = await adapter.delete('p1', 'an1', 'older');
    expect((stale as Skipped).reason, SkipReason.changedLocally);
    expect((await memory('p1').syncRead()).albumNotes, hasLength(1));
    _expectApplied(await adapter.delete('p1', 'an1', hash));
    expect((await memory('p1').syncRead()).albumNotes, isEmpty);
    _expectApplied(await adapter.delete('p1', 'gone', null));
  });

  test('should_skipUnsupported_when_payloadMissesRequiredField', () async {
    final broken = _payload('{"albumId":"al-1","provider":"spotify","createdAt":1,"updatedAt":1}');

    final outcome = await adapter.apply('p1', 'an1', broken, 1, null);

    expect((outcome as Skipped).reason, SkipReason.unsupported);
    expect(outcome.localHash, isNull);
    expect(adapter.project(broken), isNull);
    expect((await memory('p1').syncRead()).albumNotes, isEmpty);
    // Timestamps are integral numbers, ids and provider non-empty (Android requiredLong / requiredString).
    expect(adapter.project({..._payload(_remote), 'createdAt': '100'}), isNull);
    expect(adapter.project({..._payload(_remote), 'albumId': ''}), isNull);
    expect(adapter.project({..._payload(_remote), 'provider': ''}), isNull);
  });

  test('should_requireAnAccount_when_called', () {
    expect(() => adapter.readAll(null), throwsArgumentError);
  });

  group('through the engine', () {
    const binding = ActiveBinding(localProfileId: 'p1', syncProfileId: 's-1', provider: 'spotify');
    late SyncEngine engine;
    late SyncDatabase db;
    var now = 1000;

    setUp(() async {
      db = SyncDatabase(MemorySyncDbFile());
      await db.open();
      engine = SyncEngine(db: db, adapters: [adapter], deviceId: 'mate', deviceName: () => 'Mate', clock: () => now);
    });

    test('should_applyAndroidsRecordAndItsTombstone_when_merged', () async {
      final live = {
        'k': 'album_note',
        'kv': 1,
        's': 's-1',
        'id': 'an1',
        'ts': 150,
        'd': 'pixel',
        'del': false,
        'v': _payload(_remote),
      };
      await engine.mergeRemote(const RemoteFile(id: 'f', name: 'f', version: 1), _androidFile([live]));
      await engine.apply([binding]);
      final album = MediaId('spotify', 'al-1');
      expect((await memory('p1').albumNotes(album)).single.id, 'an1');

      // Android deletes it: a tombstone over the version it saw.
      final tombstone = {
        'k': 'album_note',
        'kv': 1,
        's': 's-1',
        'id': 'an1',
        'ts': 300,
        'd': 'pixel',
        'pts': 150,
        'pd': 'pixel',
        'del': true,
      };
      await engine.mergeRemote(const RemoteFile(id: 'f', name: 'f', version: 2), _androidFile([tombstone]));
      await engine.apply([binding]);
      expect(await memory('p1').albumNotes(album), isEmpty);
    });

    test('should_publishAndroidsShape_when_aStickyIsWrittenHere', () async {
      await memory('p1').putAlbumNote(note('an7', content: '**整张** 都好'));
      await engine.capture([binding]);

      final state = SnapshotCodec.decode(engine.outgoingFiles(epoch: 0, writtenAt: now, appVersionCode: 1).first.bytes);
      final record = state.records.singleWhere((r) => r.kind == SyncKinds.albumNote);
      expect(record.scope, 's-1');
      expect(record.key, 'an7');
      expect(record.deleted, isFalse);
      expect(record.payload, {
        'albumId': 'al-1',
        'provider': 'spotify',
        'content': '**整张** 都好',
        'createdAt': 10,
        'updatedAt': 20,
        'albumName': 'Charm',
        'artist': 'Clairo',
      });

      // Deleted here: published as a tombstone (deletes propagate).
      await memory('p1').deleteAlbumNote('an7');
      now = 2000;
      await engine.capture([binding]);
      final after = SnapshotCodec.decode(engine.outgoingFiles(epoch: 0, writtenAt: now, appVersionCode: 1).first.bytes);
      final gone = after.records.singleWhere((r) => r.kind == SyncKinds.albumNote);
      expect(gone.deleted, isTrue);
      expect(gone.payload, isNull);
    });

    test('should_removeTheAccountsStickies_when_itsScopeIsPurged', () async {
      await memory('p1').putAlbumNote(note('an1'));
      await memory('p1').putAlbumNote(note('an2'));
      await engine.capture([binding]);

      final removed = await engine.purgeScopeLocally('s-1', 'p1');

      expect(removed, 2);
      expect((await memory('p1').syncRead()).albumNotes, isEmpty);
    });
  });
}

/// Applied with no row left (a delete).
void _expectApplied(ApplyOutcome outcome) {
  expect(outcome, isA<Applied>());
  expect((outcome as Applied).localHash, isNull);
}
