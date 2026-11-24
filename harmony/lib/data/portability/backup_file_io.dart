/// Getting a backup zip out of the app and back in: written to the cache,
/// then the system's document picker keeps it where the user chooses (Android
/// SAF CreateDocument); a picked file is copied into the cache, read and
/// deleted (OpenDocument).
library;

import 'dart:io';
import 'dart:typed_data';

import '../../platform/share/document_files.dart';
import 'memories_portability.dart';

/// Writes [export] as cacheDir/shared/`fileName` and asks where to keep it.
/// False when there is no cache folder, the write failed or the user cancelled.
Future<bool> saveBackupFile(
  BackupExportResult export, {
  required Future<Directory?> Function() cache,
  required DocumentFiles files,
}) async {
  File? file;
  try {
    final dir = await cache();
    if (dir == null) return false;
    final folder = Directory('${dir.path}/shared');
    await folder.create(recursive: true);
    file = File('${folder.path}/${export.fileName}');
    await file.writeAsBytes(export.bytes, flush: true);
    return await files.saveFile(file.path, name: export.fileName);
  } catch (_) {
    return false;
  } finally {
    try {
      await file?.delete();
    } catch (_) {
      // Left in the cache: the system clears it.
    }
  }
}

/// The bytes of a file the user picks, or null when nothing was picked (or it couldn't be read).
Future<Uint8List?> pickBackupFile(DocumentFiles files) async {
  final path = await files.pickFile();
  if (path == null) return null;
  final file = File(path);
  try {
    return await file.readAsBytes();
  } catch (_) {
    return null;
  } finally {
    try {
      await file.delete();
    } catch (_) {
      // Left in the cache: the system clears it.
    }
  }
}
