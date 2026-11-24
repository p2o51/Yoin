// The Dart scope purge ("remove this account from the cloud") against the
// shared golden vectors, which :shared Kotlin writes and checks
// (shared/src/androidHostTest/.../sync/SyncPurgeGoldenTest.kt). A failure here
// means the port drifted from Android / iOS; regenerate the file only from the
// Kotlin side.
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/sync/engine/snapshot_codec.dart';
import 'package:yoin/data/sync/engine/sync_engine.dart';
import 'package:yoin/data/sync/sync_contracts.dart';
import 'package:yoin/data/sync/sync_database.dart';

import 'sync_engine_test.dart' show FakeAdapter;

const _goldenPath = '../shared/src/commonTest/resources/golden/sync-purge.json';

Future<(SyncDatabase, SyncEngine, FakeAdapter)> _device(int now) async {
  final db = SyncDatabase(MemorySyncDbFile());
  await db.open();
  final notes = FakeAdapter(kind: SyncKinds.songNote);
  final engine = SyncEngine(db: db, adapters: [notes], deviceId: 'phone', deviceName: () => 'Phone', clock: () => now);
  return (db, engine, notes);
}

List<String> _replica(SyncDatabase db) {
  final records = db.allRecords()
    ..sort((a, b) {
      final kind = a.kind.compareTo(b.kind);
      if (kind != 0) return kind;
      final scope = a.scope.compareTo(b.scope);
      return scope != 0 ? scope : a.key.compareTo(b.key);
    });
  return [
    for (final r in records) [r.kind, r.scope, r.key, r.ts, r.device, r.deleted].join('|'),
  ];
}

Uint8List _bytes(Object? json) => Uint8List.fromList(utf8.encode(jsonEncode(json)));

Map<String, int> _epochs(Object? json) => {for (final e in (json as Map).entries) e.key as String: e.value as int};

void main() {
  final golden = jsonDecode(File(_goldenPath).readAsStringSync()) as Map<String, dynamic>;
  List<Map<String, dynamic>> section(String name) => (golden[name] as List).cast<Map<String, dynamic>>();

  test('should_holdEverySection_when_loadingTheGolden', () {
    for (final name in ['markerCodec', 'markerDecode', 'merge', 'apply', 'stamp']) {
      expect(section(name), isNotEmpty, reason: name);
    }
  });

  group('markerCodec', () {
    for (final (index, v) in section('markerCodec').indexed) {
      test('should_writeKotlinsBytes_when_encodingMarker$index', () {
        final m = v['marker'] as Map<String, dynamic>;
        final marker = ScopePurgeMarker(
          scope: m['scope'] as String,
          epoch: m['epoch'] as int,
          at: m['at'] as int,
          deviceId: m['deviceId'] as String,
          deviceName: m['deviceName'] as String,
        );
        expect(utf8.decode(SnapshotCodec.encodePurge(marker)), v['json']);
        expect(SyncFileProps.purgeFileName(marker.epoch, marker.deviceId), v['fileName']);
        final back = SnapshotCodec.decodePurge(SnapshotCodec.encodePurge(marker));
        expect(
          [back.scope, back.epoch, back.at, back.deviceId, back.deviceName],
          [m['scope'], m['epoch'], m['at'], m['deviceId'], m['deviceName']],
        );
      });
    }
  });

  group('markerDecode', () {
    for (final (index, v) in section('markerDecode').indexed) {
      test('should_matchKotlin_when_decodingMarker$index ${v['text']}', () {
        ScopePurgeMarker? marker;
        try {
          marker = SnapshotCodec.decodePurge(Uint8List.fromList(utf8.encode(v['text'] as String)));
        } on MalformedSyncFileException {
          marker = null;
        }
        expect(
          marker == null
              ? null
              : {
                  'scope': marker.scope,
                  'epoch': marker.epoch,
                  'at': marker.at,
                  'deviceId': marker.deviceId,
                  'deviceName': marker.deviceName,
                },
          v['marker'],
        );
      });
    }
  });

  group('merge', () {
    for (final v in section('merge')) {
      test('should_takeWhatKotlinTakes_when_${v['name']}', () async {
        final (db, engine, _) = await _device(100000);
        for (final MapEntry(:key, :value) in _epochs(v['applied']).entries) {
          await engine.applyScopePurge(key, value);
        }
        final envelope = SnapshotCodec.decode(_bytes(v['envelope']));
        final result = await engine.mergeRemote(
          const RemoteFile(id: 'peer-state', name: 'yoin-state-peer.json.gz', version: 1),
          envelope,
        );
        expect(result.received, v['received'], reason: 'received');
        expect(result.purged, v['purged'], reason: 'purged');
        expect(_replica(db), v['replica']);
      });
    }
  });

  group('apply', () {
    for (final v in section('apply')) {
      test('should_forgetWhatKotlinForgets_when_applyingPurgesOneByOne', () async {
        final (db, engine, _) = await _device(100000);
        await engine.mergeRemote(
          const RemoteFile(id: 'peer-state', name: 'yoin-state-peer.json.gz', version: 1),
          SnapshotCodec.decode(_bytes(v['seed'])),
        );
        expect(_replica(db), v['replicaBefore']);
        for (final (index, call) in (v['calls'] as List).cast<Map<String, dynamic>>().indexed) {
          final removed = await engine.applyScopePurge(call['scope'] as String, call['epoch'] as int);
          expect(removed, call['removed'], reason: 'call $index removed');
          expect(engine.purgedScopes(), _epochs(call['purgedScopes']), reason: 'call $index purgedScopes');
          expect(_replica(db), call['replica'], reason: 'call $index replica');
        }
      });
    }
  });

  group('stamp', () {
    for (final v in section('stamp')) {
      test('should_stampLikeKotlin_when_${v['name']}', () async {
        final now = v['now'] as int;
        final (db, engine, notes) = await _device(now);
        final scope = v['scope'] as String;
        for (final row in (v['rows'] as List).cast<Map<String, dynamic>>()) {
          notes.put('p', row['key'] as String, 'note ${row['key']}', row['rowTs'] as int);
        }
        for (final MapEntry(:key, :value) in _epochs(v['applied']).entries) {
          await engine.applyScopePurge(key, value);
        }
        await engine.capture([const ActiveBinding(localProfileId: 'p', syncProfileId: 's-a', provider: 'subsonic')]);
        final versions = <String, int>{
          for (final row in (v['rows'] as List).cast<Map<String, dynamic>>())
            row['key'] as String: db.getRecord(SyncKinds.songNote, scope, row['key'] as String)!.ts,
        };
        expect(versions, _epochs(v['versions']));
        final state = engine
            .outgoingFiles(epoch: 0, writtenAt: now, appVersionCode: 1)
            .firstWhere((f) => f.fileKind == SyncFileProps.kindState);
        final published = SnapshotCodec.decode(state.bytes).purges;
        expect(published, v['publishedPurges'] == null ? isNull : _epochs(v['publishedPurges']));
      });
    }
  });
}
