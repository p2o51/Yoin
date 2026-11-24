// The Dart backup export against the golden vectors :shared Kotlin writes and
// checks (shared/src/androidHostTest/.../portability/PortabilityGoldenTest.kt):
// the same snapshot, accounts and names must give the same manifest, snapshot
// and readable Markdown bytes. Regenerate the file only from the Kotlin side.
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/portability/backup_files.dart';
import 'package:yoin/data/portability/backup_format.dart';
import 'package:yoin/data/portability/readable_export.dart';
import 'package:yoin/data/sync/canonical_json.dart';
import 'package:yoin/data/sync/engine/snapshot_codec.dart';

import 'golden_zones.dart';

const _goldenPath = '../shared/src/commonTest/resources/golden/backup-export.json';

final class _Names extends ReadableNames {
  _Names(this.albums, this.tracks);

  final List<Map<String, dynamic>> albums;
  final List<Map<String, dynamic>> tracks;

  @override
  Future<ReadableAlbum?> album(String? localProfileId, String provider, String albumId) async {
    for (final a in albums) {
      if (a['profileId'] == localProfileId && a['provider'] == provider && a['albumId'] == albumId) {
        return ReadableAlbum(a['name'] as String, a['artist'] as String?);
      }
    }
    return null;
  }

  @override
  Future<ReadableTrack?> track(String? localProfileId, String provider, String trackId) async {
    for (final t in tracks) {
      if (t['profileId'] == localProfileId && t['provider'] == provider && t['trackId'] == trackId) {
        return ReadableTrack(t['title'] as String, t['artist'] as String?, t['albumId'] as String?);
      }
    }
    return null;
  }
}

void main() {
  final golden = jsonDecode(File(_goldenPath).readAsStringSync()) as Map<String, dynamic>;
  final input = golden['input'] as Map<String, dynamic>;
  final cases = (golden['cases'] as List).cast<Map<String, dynamic>>();

  test('should_holdThreeCases_when_loadingTheGolden', () => expect(cases, hasLength(3)));

  for (final c in cases) {
    test('should_writeKotlinsBytes_when_exporting ${c['name']}', () async {
      final snapshot = SnapshotCodec.decode(utf8.encode(compactJson(input['snapshot'])));
      final content = BackupContent(
        snapshot: snapshot,
        accounts: [for (final a in input['accounts'] as List) BackupAccount.fromJson(a)],
        localProfiles: (input['localProfiles'] as Map).cast<String, String>(),
        readFailures: (input['readFailures'] as List).cast<String>(),
      );
      final caseInput = c['input'] as Map<String, dynamic>;
      final zone = goldenZone(caseInput['zoneId'] as String);
      final files = await BackupExport.files(
        content,
        source: BackupSource.fromJson(input['source']),
        names: _Names(
          (input['knownAlbums'] as List).cast<Map<String, dynamic>>(),
          (input['knownTracks'] as List).cast<Map<String, dynamic>>(),
        ),
        labels: ReadableLabels.forLanguage(caseInput['language'] as String),
        zone: zone,
      );
      final expected = c['expected'] as Map<String, dynamic>;
      expect(BackupFormat.fileName(snapshot.writtenAt, zone), expected['fileName']);
      expect(utf8.decode(files.manifestBytes), expected['manifestJson']);
      expect(utf8.decode(files.snapshotBytes), expected['snapshotJson']);
      final readable = (expected['readable'] as List).cast<Map<String, dynamic>>();
      expect([for (final f in files.readable) f.path], [for (final r in readable) r['path']]);
      for (final (i, r) in readable.indexed) {
        expect(files.readable[i].text, r['text'], reason: r['path'] as String);
      }
      // What was written reads back.
      final parsed = BackupParser.parse(files.manifestBytes, files.snapshotBytes, files.readable.length);
      expect(parsed.manifest.encode(), expected['manifestJson']);
      expect(parsed.snapshot.records, hasLength(snapshot.records.length));
    });
  }
}
