// Import from a backup (plan + merge) on HarmonyOS's real sync adapters and
// stores, against the golden vectors :shared Kotlin writes and checks
// (shared/src/commonTest/resources/golden/backup-import.json). A failure means
// the port drifted from Android / iOS; regenerate only from the Kotlin side.
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/portability/backup_files.dart';
import 'package:yoin/data/portability/backup_import.dart';
import 'package:yoin/data/portability/backup_ports.dart';
import 'package:yoin/data/portability/harmony_stores.dart';
import 'package:yoin/data/sync/engine/sync_engine.dart';
import 'package:yoin/data/sync/sync_database.dart';

import 'portability_device.dart';

const _goldenPath = '../shared/src/commonTest/resources/golden/backup-import.json';

PortabilityProfile _profile(Map<String, dynamic> p) => PortabilityProfile(
  id: p['id'] as String,
  provider: p['provider'] as String,
  displayName: p['displayName'] as String,
  createdAt: p['createdAt'] as int,
  subsonicServerUrl: p['subsonicServerUrl'] as String?,
  subsonicUsername: p['subsonicUsername'] as String?,
  spotifyUserId: p['spotifyUserId'] as String?,
);

ImportTarget _target(Map<String, dynamic> t) => switch (t['kind']) {
  'INTO_PROFILE' => ImportIntoProfile(t['profileId'] as String),
  'CLOUD_ONLY' => ImportTarget.cloudOnly,
  _ => ImportTarget.skip,
};

Map<String, Object?> _targetJson(ImportTarget t) => switch (t) {
  ImportIntoProfile(:final localProfileId) => {'kind': 'INTO_PROFILE', 'profileId': localProfileId},
  ImportCloudOnly() => {'kind': 'CLOUD_ONLY'},
  ImportSkip() => {'kind': 'SKIP'},
};

Map<String, Object?> _planJson(ImportPlan plan) => {
  'accounts': [
    for (final a in plan.accounts)
      {
        'scope': a.account.scope,
        'provider': a.account.provider,
        'displayName': a.account.displayName,
        'counts': a.counts,
        'target': _targetJson(a.target),
        'sameAccount': a.sameAccount,
        'options': [for (final o in a.options) o.localProfileId],
        'cloudOnlyPossible': a.cloudOnlyPossible,
        'wasPurged': a.wasPurged,
      },
  ],
  'globalCounts': plan.globalCounts,
};

Map<String, Object?> _countsJson(ImportCounts c) => {
  'written': c.written,
  'unchanged': c.unchanged,
  'keptLocal': c.keptLocal,
  'keptBoth': c.keptBoth,
  'tombstones': c.tombstones,
  'skipped': c.skipped,
  'failedKinds': c.failedKinds.toList()..sort(),
};

Map<String, Object?> _resultJson(ImportResult r) => {
  'byScope': {for (final e in r.byScope.entries) e.key: _countsJson(e.value)},
  'global': _countsJson(r.global),
  'cloudOnlyMerged': r.cloudOnlyMerged,
  'cloudOnlyRefused': r.cloudOnlyRefused,
  'changed': r.changed,
};

void main() {
  final golden = jsonDecode(File(_goldenPath).readAsStringSync()) as Map<String, dynamic>;
  final backupJson = golden['backup'] as Map<String, dynamic>;
  final cases = (golden['cases'] as List).cast<Map<String, dynamic>>();

  ParsedBackup backup() => BackupParser.parse(
    Uint8List.fromList(utf8.encode(backupJson['manifestJson'] as String)),
    Uint8List.fromList(utf8.encode(backupJson['snapshotJson'] as String)),
    0,
  );

  test('should_readKotlinsBackup_when_parsingTheGoldenFile', () {
    final parsed = backup();
    expect(parsed.manifest.encode(), backupJson['manifestJson']);
    expect(parsed.snapshot.records, isNotEmpty);
  });

  for (final c in cases) {
    test('should_matchKotlin_when_importing ${c['name']}', () async {
      final input = c['input'] as Map<String, dynamic>;
      final expected = c['expected'] as Map<String, dynamic>;
      final profiles = [for (final p in input['profiles'] as List) _profile(p as Map<String, dynamic>)];
      final device = PortabilityDevice(profiles: profiles);
      await device.seed(input['domain'] as Map<String, dynamic>);
      expect(await device.domain(), input['domain'], reason: 'seeded rows read back');
      final adapters = await device.adapters();
      BackupReplica? replica;
      final replicaInput = input['replica'] as Map<String, dynamic>?;
      if (replicaInput != null) {
        final db = await device.db();
        for (final (key, value) in [
          for (final e in (replicaInput['meta'] as Map).entries) (e.key as String, e.value as String),
        ]) {
          db.putMeta(key, value);
        }
        for (final b in (replicaInput['bindings'] as List).cast<Map<String, dynamic>>()) {
          db.upsertBinding(
            SyncBindingEntity(
              localProfileId: b['localProfileId'] as String,
              syncProfileId: b['syncProfileId'] as String,
              provider: profiles.firstWhere((p) => p.id == b['localProfileId']).provider,
              state: b['state'] as String,
              boundAt: 1,
            ),
          );
        }
        final engine = SyncEngine(
          db: db,
          adapters: adapters,
          deviceId: 'dev-tablet',
          deviceName: () => 'Pixel Tablet',
          clock: () => 50000,
        );
        replica = DatabaseBackupReplica(db, engine);
      }
      final choices = {
        for (final e in (input['choices'] as Map).entries) e.key as String: _target(e.value as Map<String, dynamic>),
      };
      final merger = BackupMerger(adapters, HarmonyBackupDomain(device.stores));
      final parsed = backup();
      final plan = ImportPlanner.plan(parsed, profiles, replica);
      expect(_planJson(plan), expected['plan']);
      final result = await merger.apply(plan, choices, profiles, replica, importGlobal: input['importGlobal'] as bool);
      expect(_resultJson(result), expected['result']);
      if (input['applyTwice'] == true) {
        final second = await merger.apply(
          ImportPlanner.plan(parsed, profiles, replica),
          choices,
          profiles,
          replica,
          importGlobal: true,
        );
        expect(_resultJson(second), expected['secondResult']);
      }
      expect(await device.domain(), expected['domain']);
      if (expected['replicaRecords'] != null) {
        final db = await device.db();
        final records = db.allRecords()
          ..sort((a, b) {
            final kind = a.kind.compareTo(b.kind);
            if (kind != 0) return kind;
            final scope = a.scope.compareTo(b.scope);
            return scope != 0 ? scope : a.key.compareTo(b.key);
          });
        expect([
          for (final r in records)
            {'kind': r.kind, 'scope': r.scope, 'key': r.key, 'ts': r.ts, 'device': r.device, 'payload': r.payload},
        ], expected['replicaRecords']);
      }
    });
  }
}
