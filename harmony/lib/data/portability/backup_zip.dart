/// The backup zip on HarmonyOS (Android BackupWriter / BackupReader): the
/// files [BackupExport] built, in their order, as one zip; and a zip back to
/// the entries [BackupParser] checks. Pure Dart (`archive`).
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:archive/archive.dart';

import 'backup_files.dart';
import 'backup_format.dart';

abstract final class BackupZip {
  /// manifest.json, data/snapshot.json, then readable/…, UTF-8, deflated.
  static Uint8List write(BackupExportFiles files) {
    final archive = Archive()
      ..addFile(ArchiveFile(BackupFormat.manifestPath, files.manifestBytes.length, files.manifestBytes))
      ..addFile(ArchiveFile(BackupFormat.snapshotPath, files.snapshotBytes.length, files.snapshotBytes));
    for (final file in files.readable) {
      final bytes = utf8.encode(file.text);
      archive.addFile(ArchiveFile(file.path, bytes.length, bytes));
    }
    return Uint8List.fromList(ZipEncoder().encode(archive)!);
  }

  /// Reads and checks a backup zip. Throws [BackupReadException]: not a zip (or
  /// no manifest) is [BackupErrorKind.notABackup]; an entry over its size limit
  /// or one that doesn't inflate is [BackupErrorKind.corrupt].
  static ParsedBackup read(Uint8List bytes) {
    final Archive archive;
    try {
      archive = ZipDecoder().decodeBytes(bytes);
    } catch (_) {
      throw const BackupReadException(BackupErrorKind.notABackup, 'Not a zip file');
    }
    Uint8List? manifest;
    Uint8List? snapshot;
    var readable = 0;
    for (final entry in archive.files) {
      if (!entry.isFile) continue;
      if (entry.name == BackupFormat.manifestPath) {
        manifest = _content(entry, BackupFormat.maxManifestBytes);
      } else if (entry.name == BackupFormat.snapshotPath) {
        snapshot = _content(entry, BackupFormat.maxSnapshotBytes);
      } else if (entry.name.startsWith(BackupFormat.readableDir)) {
        readable++;
      }
    }
    return BackupParser.parse(manifest, snapshot, readable);
  }

  static Uint8List _content(ArchiveFile entry, int limit) {
    if (entry.size > limit) {
      throw BackupReadException(BackupErrorKind.corrupt, 'Backup entry larger than $limit bytes');
    }
    try {
      final content = entry.content as List<int>;
      if (content.length > limit) {
        throw BackupReadException(BackupErrorKind.corrupt, 'Backup entry larger than $limit bytes');
      }
      return Uint8List.fromList(content);
    } on BackupReadException {
      rethrow;
    } catch (_) {
      throw const BackupReadException(BackupErrorKind.corrupt, "Couldn't read the backup");
    }
  }
}
