/// Envelope <-> bytes (Android engine/SnapshotCodec.kt). State/artifact files
/// are gzip JSON; reset and scope purge markers are plain JSON. The same files Android writes.
library;

import 'dart:convert';
import 'dart:io' show gzip;
import 'dart:typed_data';

import '../canonical_json.dart';
import '../sync_contracts.dart';
import '../sync_database.dart';

/// A Drive file that is not a readable Yoin sync file. Callers skip the file;
/// seen versions must not advance.
sealed class SyncFormatException implements Exception {
  const SyncFormatException(this.message);
  final String message;

  @override
  String toString() => '$runtimeType: $message';
}

/// Written by a newer Yoin: skip the file and surface "update Yoin" for [deviceName].
final class SyncSchemaTooNewException extends SyncFormatException {
  SyncSchemaTooNewException(this.schema, this.deviceId, this.deviceName)
    : super('Sync file schema $schema is newer than supported ${SyncFormat.schema}');

  final int schema;
  final String? deviceId;
  final String? deviceName;
}

final class MalformedSyncFileException extends SyncFormatException {
  const MalformedSyncFileException(super.message);
}

abstract final class SnapshotCodec {
  static Uint8List encode(SyncEnvelope envelope) =>
      Uint8List.fromList(gzip.encode(utf8.encode(compactJson(envelope.toJson()))));

  /// Accepts gzip (sniffed by magic bytes) or plain JSON. A single record that
  /// does not decode is dropped instead of failing the whole file.
  static SyncEnvelope decode(Uint8List bytes) {
    final root = _parseRoot(bytes);
    final format = root['format'];
    if (format != SyncFormat.format) throw MalformedSyncFileException('Not a Yoin sync file (format=$format)');
    final schema = root['schema'];
    if (schema is! int) throw const MalformedSyncFileException('Sync file without schema');
    if (schema > SyncFormat.schema) {
      throw SyncSchemaTooNewException(schema, root['deviceId'] as String?, root['deviceName'] as String?);
    }
    if (schema < 1) throw MalformedSyncFileException('Invalid sync schema $schema');
    final records = root['records'];
    if (records is! List) throw const MalformedSyncFileException('Sync file without records');
    final deviceId = root['deviceId'];
    final deviceName = root['deviceName'];
    final fileClass = root['fileClass'];
    final writtenAt = root['writtenAt'];
    final appVersionCode = root['appVersionCode'];
    final epoch = root['epoch'] ?? 0;
    final shard = root['shard'] ?? 0;
    if (deviceId is! String ||
        deviceName is! String ||
        fileClass is! String ||
        writtenAt is! int ||
        appVersionCode is! int ||
        epoch is! int ||
        shard is! int) {
      throw const MalformedSyncFileException('Unreadable sync file header');
    }
    final purges = root['purges'];
    if (purges != null && (purges is! Map || purges.values.any((v) => v is! int))) {
      throw const MalformedSyncFileException('Unreadable scope purges');
    }
    return SyncEnvelope(
      format: format as String,
      schema: schema,
      epoch: epoch,
      deviceId: deviceId,
      deviceName: deviceName,
      fileClass: fileClass,
      shard: shard,
      writtenAt: writtenAt,
      appVersionCode: appVersionCode,
      records: [for (final r in records) ?WireRecord.tryParse(r)],
      purges: purges == null ? null : {for (final e in (purges as Map).entries) '${e.key}': e.value as int},
    );
  }

  static Uint8List encodeReset(ResetMarker marker) => Uint8List.fromList(utf8.encode(compactJson(marker.toJson())));

  static ResetMarker decodeReset(Uint8List bytes) {
    final root = _parseRoot(bytes);
    final epoch = root['epoch'];
    final at = root['at'];
    final deviceId = root['deviceId'];
    final deviceName = root['deviceName'];
    if (epoch is! int || at is! int || deviceId is! String || deviceName is! String) {
      throw const MalformedSyncFileException('Unreadable reset marker');
    }
    return ResetMarker(epoch: epoch, at: at, deviceId: deviceId, deviceName: deviceName);
  }

  static Uint8List encodePurge(ScopePurgeMarker marker) =>
      Uint8List.fromList(utf8.encode(compactJson(marker.toJson())));

  /// Throws [MalformedSyncFileException] when the bytes are not a scope purge
  /// marker or name no scope. Unknown fields are skipped.
  static ScopePurgeMarker decodePurge(Uint8List bytes) {
    final root = _parseRoot(bytes);
    final scope = root['scope'];
    final epoch = root['epoch'];
    final at = root['at'];
    final deviceId = root['deviceId'];
    final deviceName = root['deviceName'];
    if (scope is! String || epoch is! int || at is! int || deviceId is! String || deviceName is! String) {
      throw const MalformedSyncFileException('Unreadable scope purge marker');
    }
    if (scope.isEmpty) throw const MalformedSyncFileException('Scope purge marker without a scope');
    return ScopePurgeMarker(scope: scope, epoch: epoch, at: at, deviceId: deviceId, deviceName: deviceName);
  }

  static bool isGzip(Uint8List bytes) => bytes.length >= 2 && bytes[0] == 0x1f && bytes[1] == 0x8b;

  static Map<String, Object?> _parseRoot(Uint8List bytes) {
    final String text;
    try {
      text = utf8.decode(isGzip(bytes) ? gzip.decode(bytes) : bytes);
    } on FormatException {
      throw const MalformedSyncFileException('Corrupt sync file');
    } catch (_) {
      throw const MalformedSyncFileException('Corrupt gzip sync file');
    }
    final Object? root;
    try {
      root = jsonDecode(text);
    } on FormatException {
      throw const MalformedSyncFileException('Sync file is not JSON');
    }
    if (root is! Map<String, Object?>) throw const MalformedSyncFileException('Sync file is not a JSON object');
    return root;
  }
}

/// A live record without a payload stays live with a null payload: callers
/// must reject it, never treat it as a delete.
SyncRecordEntity wireToEntity(WireRecord wire) {
  final livePayload = wire.deleted ? null : wire.payload;
  final prevKnown = wire.prevTs != null && wire.prevDevice != null;
  return SyncRecordEntity(
    kind: wire.kind,
    scope: wire.scope,
    key: wire.key,
    kindVersion: wire.kindVersion,
    ts: wire.ts,
    device: wire.device,
    prevTs: prevKnown ? wire.prevTs : null,
    prevDevice: prevKnown ? wire.prevDevice : null,
    deleted: wire.deleted,
    payload: livePayload == null ? null : compactJson(livePayload),
    payloadHash: livePayload == null ? null : CanonicalJson.hash(livePayload),
  );
}

WireRecord entityToWire(SyncRecordEntity e) => WireRecord(
  kind: e.kind,
  kindVersion: e.kindVersion,
  scope: e.scope,
  key: e.key,
  ts: e.ts,
  device: e.device,
  prevTs: e.prevTs,
  prevDevice: e.prevDevice,
  deleted: e.deleted,
  payload: e.deleted ? null : parseJsonObject(e.payload),
);
