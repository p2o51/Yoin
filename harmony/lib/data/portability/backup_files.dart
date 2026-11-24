/// A backup's files: building them from a record set ([BackupExport]) and
/// checking what was read out of a zip ([BackupParser]). Port of :shared
/// BackupFiles.kt; the zip itself is `backup_zip.dart`.
library;

import 'dart:convert';

import 'package:flutter/foundation.dart';

import '../sync/canonical_json.dart';
import '../sync/engine/snapshot_codec.dart';
import '../sync/sync_contracts.dart';
import 'backup_format.dart';
import 'readable_export.dart';

/// Everything one backup holds, before it is written out.
@immutable
final class BackupContent {
  const BackupContent({
    required this.snapshot,
    required this.accounts,
    required this.localProfiles,
    this.readFailures = const [],
  });

  /// All records, sorted by (kind, scope, key); fileClass = [BackupFormat.snapshotFileClass].
  final SyncEnvelope snapshot;

  /// One per scope: this device's profiles first (oldest first), then accounts only known from Drive.
  final List<BackupAccount> accounts;

  /// Local profile id behind each on-device account's scope.
  final Map<String, String> localProfiles;

  /// Kinds whose domain table couldn't be read (their replica copy, if any, was used instead).
  final List<String> readFailures;
}

/// What an export wrote.
@immutable
final class ExportSummary {
  const ExportSummary({required this.manifest, required this.readableFiles, required this.readFailures});

  final BackupManifest manifest;

  /// Readable Markdown files written.
  final int readableFiles;

  /// Kinds whose domain table couldn't be read (their last synced copy went in instead).
  final List<String> readFailures;
}

/// The files of one backup, in zip order: manifest, snapshot, then [readable]. UTF-8, no other entries.
@immutable
final class BackupExportFiles {
  const BackupExportFiles({
    required this.summary,
    required this.manifestBytes,
    required this.snapshotBytes,
    required this.readable,
  });

  final ExportSummary summary;
  final Uint8List manifestBytes;
  final Uint8List snapshotBytes;
  final List<BackupTextFile> readable;

  BackupManifest get manifest => summary.manifest;
}

/// A backup that was read and checked: its manifest and its snapshot's records.
@immutable
final class ParsedBackup {
  const ParsedBackup({required this.manifest, required this.snapshot, required this.readableFiles});

  final BackupManifest manifest;
  final SyncEnvelope snapshot;

  /// Readable Markdown files present (informational; never read).
  final int readableFiles;
}

/// Why a file couldn't be imported (:shared YoinBackupErrorKind).
enum BackupErrorKind {
  /// Not a zip, or no Yoin manifest in it.
  notABackup,

  /// Written by a newer Yoin (backup format or sync schema): update to import it.
  tooNew,

  /// The snapshot doesn't match the manifest's SHA-256: damaged or edited.
  checksumMismatch,

  corrupt,

  /// Anything else (the file couldn't be read at all, say).
  other,
}

final class BackupReadException implements Exception {
  const BackupReadException(this.kind, this.message, {this.formatVersion = 0});

  final BackupErrorKind kind;
  final String message;

  /// The newer format or schema, for [BackupErrorKind.tooNew].
  final int formatVersion;

  @override
  String toString() => 'BackupReadException(${kind.name}): $message';
}

/// Builds the files of a backup from its [BackupContent] (the zip is `backup_zip.dart`'s).
abstract final class BackupExport {
  static Future<BackupExportFiles> files(
    BackupContent content, {
    required BackupSource source,
    required ReadableNames names,
    required ReadableLabels labels,
    required BackupZone zone,
  }) async {
    final snapshot = content.snapshot;
    final snapshotBytes = Uint8List.fromList(utf8.encode(compactJson(snapshot.toJson())));
    final (readable, accounts) = await ReadableExport.render(
      accounts: content.accounts,
      localProfiles: content.localProfiles,
      records: snapshot.records,
      names: names,
      labels: labels,
      zone: zone,
    );
    final live = [
      for (final r in snapshot.records)
        if (!r.deleted) r,
    ];
    final kindsByScope = <String, List<String>>{};
    for (final r in live) {
      if (r.scope != SyncFormat.globalScope) (kindsByScope[r.scope] ??= []).add(r.kind);
    }
    final manifest = BackupManifest(
      exportedAt: snapshot.writtenAt,
      source: source,
      accounts: [for (final a in accounts) a.copyWith(counts: sortedCounts(kindsByScope[a.scope] ?? const []))],
      globalCounts: sortedCounts([
        for (final r in live)
          if (r.scope == SyncFormat.globalScope) r.kind,
      ]),
      snapshot: BackupSnapshotInfo(
        sha256: BackupFormat.sha256Hex(snapshotBytes),
        bytes: snapshotBytes.length,
        records: snapshot.records.length,
        tombstones: snapshot.records.where((r) => r.deleted).length,
      ),
    );
    final manifestBytes = Uint8List.fromList(utf8.encode(manifest.encode()));
    return BackupExportFiles(
      summary: ExportSummary(manifest: manifest, readableFiles: readable.length, readFailures: content.readFailures),
      manifestBytes: manifestBytes,
      snapshotBytes: snapshotBytes,
      readable: readable,
    );
  }

  /// kind -> count, keys in sorted order (the manifest lists them sorted).
  static Map<String, int> sortedCounts(Iterable<String> kinds) {
    final counts = <String, int>{};
    for (final k in kinds) {
      counts[k] = (counts[k] ?? 0) + 1;
    }
    final keys = counts.keys.toList()..sort();
    return {for (final k in keys) k: counts[k]!};
  }
}

/// Checks what was read out of a backup zip and decodes it (:shared BackupParser).
abstract final class BackupParser {
  /// [manifestBytes] / [snapshotBytes]: the two entries' bytes (null when the zip has no such entry);
  /// [readableFiles]: how many entries sit under [BackupFormat.readableDir]. Throws [BackupReadException].
  static ParsedBackup parse(Uint8List? manifestBytes, Uint8List? snapshotBytes, int readableFiles) {
    if (manifestBytes == null) {
      throw const BackupReadException(BackupErrorKind.notABackup, 'No ${BackupFormat.manifestPath}');
    }
    final BackupManifest manifest;
    try {
      manifest = BackupManifest.fromJson(jsonDecode(utf8.decode(manifestBytes, allowMalformed: true)));
    } on FormatException {
      throw const BackupReadException(BackupErrorKind.notABackup, 'Unreadable manifest');
    } on ManifestFormatException {
      throw const BackupReadException(BackupErrorKind.notABackup, 'Unreadable manifest');
    }
    if (manifest.format != BackupFormat.format) {
      throw const BackupReadException(BackupErrorKind.notABackup, 'Not a Yoin backup');
    }
    if (manifest.formatVersion > BackupFormat.formatVersion) {
      throw BackupReadException(
        BackupErrorKind.tooNew,
        'Backup format ${manifest.formatVersion} is newer than this Yoin',
        formatVersion: manifest.formatVersion,
      );
    }
    if (snapshotBytes == null) {
      throw const BackupReadException(BackupErrorKind.corrupt, 'No ${BackupFormat.snapshotPath}');
    }
    if (BackupFormat.sha256Hex(snapshotBytes).toLowerCase() != manifest.snapshot.sha256.toLowerCase()) {
      throw const BackupReadException(BackupErrorKind.checksumMismatch, "Backup data doesn't match its checksum");
    }
    final SyncEnvelope snapshot;
    try {
      snapshot = SnapshotCodec.decode(snapshotBytes);
    } on SyncSchemaTooNewException catch (e) {
      throw BackupReadException(BackupErrorKind.tooNew, e.message, formatVersion: e.schema);
    } on MalformedSyncFileException {
      throw const BackupReadException(BackupErrorKind.corrupt, 'Unreadable snapshot');
    }
    if (snapshot.fileClass != BackupFormat.snapshotFileClass) {
      throw const BackupReadException(BackupErrorKind.corrupt, 'Not a backup snapshot');
    }
    return ParsedBackup(manifest: manifest, snapshot: snapshot, readableFiles: readableFiles);
  }
}

/// [list] sorted by [compare], equal elements kept in order (Kotlin's sortedBy is stable, Dart's sort isn't).
List<T> stableSorted<T>(Iterable<T> list, int Function(T a, T b) compare) {
  final indexed = [for (final (i, e) in list.indexed) (i, e)];
  indexed.sort((a, b) {
    final c = compare(a.$2, b.$2);
    return c != 0 ? c : a.$1.compareTo(b.$1);
  });
  return [for (final e in indexed) e.$2];
}

/// Kotlin's compareValues: null first, then natural order.
int compareNullable(String? a, String? b) {
  if (a == b) return 0;
  if (a == null) return -1;
  if (b == null) return 1;
  return a.compareTo(b);
}

/// Records by (kind, scope, key), as every backup lists them.
int compareRecords(WireRecord a, WireRecord b) {
  final kind = a.kind.compareTo(b.kind);
  if (kind != 0) return kind;
  final scope = a.scope.compareTo(b.scope);
  return scope != 0 ? scope : a.key.compareTo(b.key);
}
