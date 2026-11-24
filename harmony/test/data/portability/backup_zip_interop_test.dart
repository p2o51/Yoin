// A zip iOS wrote (shared/src/commonTest/resources/golden/backup-export-ios.zip,
// the en-UTC export case) reads on HarmonyOS, and HarmonyOS's own zip of the
// same files holds the same entries in the same order.
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:archive/archive.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/portability/backup_files.dart';
import 'package:yoin/data/portability/backup_format.dart';
import 'package:yoin/data/portability/backup_zip.dart';
import 'package:yoin/data/portability/readable_export.dart';

const _golden = '../shared/src/commonTest/resources/golden/';

void main() {
  final expected =
      (jsonDecode(File('${_golden}backup-export.json').readAsStringSync())['cases'] as List)
              .cast<Map<String, dynamic>>()
              .firstWhere((c) => c['name'] == 'en-UTC')['expected']
          as Map<String, dynamic>;

  test('should_readIosZip_when_importingABackupWrittenOnAnIPhone', () {
    final bytes = File('${_golden}backup-export-ios.zip').readAsBytesSync();
    final parsed = BackupZip.read(bytes);
    expect(parsed.manifest.encode(), expected['manifestJson']);
    expect(parsed.readableFiles, (expected['readable'] as List).length);
    final entries = ZipDecoder().decodeBytes(bytes).files.where((f) => f.isFile).toList();
    expect(
      [for (final f in entries) f.name],
      [BackupFormat.manifestPath, BackupFormat.snapshotPath, for (final r in expected['readable'] as List) r['path']],
    );
    expect(utf8.decode(entries[1].content as List<int>), expected['snapshotJson']);
  });

  test('should_writeTheSameEntries_when_zippingTheGoldenFiles', () {
    final readable = [
      for (final r in expected['readable'] as List) BackupTextFile(r['path'] as String, r['text'] as String),
    ];
    final manifest = Uint8List.fromList(utf8.encode(expected['manifestJson'] as String));
    final snapshot = Uint8List.fromList(utf8.encode(expected['snapshotJson'] as String));
    final parsed = BackupParser.parse(manifest, snapshot, readable.length);
    final zip = BackupZip.write(
      BackupExportFiles(
        summary: ExportSummary(manifest: parsed.manifest, readableFiles: readable.length, readFailures: const []),
        manifestBytes: manifest,
        snapshotBytes: snapshot,
        readable: readable,
      ),
    );
    final ios = ZipDecoder().decodeBytes(File('${_golden}backup-export-ios.zip').readAsBytesSync()).files;
    final ours = ZipDecoder().decodeBytes(zip).files;
    expect(
      [for (final f in ours) f.name],
      [
        for (final f in ios)
          if (f.isFile) f.name,
      ],
    );
    for (final f in ours) {
      final other = ios.firstWhere((o) => o.name == f.name);
      expect(f.content, other.content, reason: f.name);
    }
  });
}
