/// Cloud sync contracts — the shapes every sync component agrees on
/// (Android data/sync/SyncContracts.kt). The wire format is shared with the
/// Android app: a HarmonyOS device and an Android phone signed in to the same
/// Google account read each other's files in Drive's appDataFolder. Never
/// rename a wire name. Spec: docs/cloud-sync.md (repo root).
library;

import 'package:flutter/foundation.dart';

/// Stable wire names of synced record kinds.
abstract final class SyncKinds {
  static const songNote = 'song_note';
  static const trackRating = 'track_rating';
  static const albumRating = 'album_rating';
  static const albumReview = 'album_review';
  static const homeLayout = 'home_layout';
  static const setting = 'setting';
  static const lyricsTranslation = 'lyrics_translation';
  static const account = 'account';
  static const memoryCopy = 'memory_copy';
  static const memoryTitle = 'memory_title';
  static const songAbout = 'song_about';
  static const albumTitle = 'album_title';
  static const playlistBackup = 'playlist_backup';
}

abstract final class SyncFormat {
  static const format = 'yoin-sync';
  static const schema = 1;

  /// Scope of records that belong to no music account.
  static const globalScope = '';

  /// Separator inside composite record keys (U+001F).
  static const keySeparator = '\u001F';

  /// Artifact records are spread over this many files per device.
  static const artifactShards = 4;

  static String compositeKey(List<String> parts) => parts.join(keySeparator);
}

/// A record version. Total order: [ts], then [device].
@immutable
final class RecordVersion implements Comparable<RecordVersion> {
  const RecordVersion(this.ts, this.device);

  final int ts;
  final String device;

  @override
  int compareTo(RecordVersion other) {
    final byTs = ts.compareTo(other.ts);
    return byTs != 0 ? byTs : device.compareTo(other.device);
  }

  bool operator >(RecordVersion other) => compareTo(other) > 0;

  @override
  bool operator ==(Object other) => other is RecordVersion && other.ts == ts && other.device == device;

  @override
  int get hashCode => Object.hash(ts, device);

  @override
  String toString() => 'v($ts,$device)';
}

/// Which per-device file a kind is published in.
enum FileClass {
  /// Small, frequently changing user data: one file per device.
  state('state'),

  /// Larger, rarely changing derived data: [SyncFormat.artifactShards] files per device.
  artifacts('artifacts');

  const FileClass(this.wireName);
  final String wireName;
}

enum ConflictPolicy {
  /// Last writer (by [RecordVersion]) wins.
  lww,

  /// LWW, but on a concurrent live-vs-tombstone conflict the live record wins.
  lwwLiveWins,

  /// Concurrent edits of one text field are kept both, joined with a device/date divider.
  keepBothText,
}

/// A JSON object payload (decoded, key order as written).
typedef JsonMap = Map<String, Object?>;

/// A row as it exists in the app's own storage, reduced to the synced projection.
@immutable
final class DomainRow {
  const DomainRow({required this.key, required this.projection, required this.rowTs});

  final String key;

  /// Same shape [SyncAdapter.project] produces from a payload; compared by hash.
  final JsonMap projection;

  /// The row's own last-change time, or null for timestamp-less kinds.
  final int? rowTs;
}

enum SkipReason {
  /// The domain row changed since the engine last saw it; the next capture handles it.
  changedLocally,

  /// A row with this id belongs to a different local profile; never re-parent it.
  ownedElsewhere,

  /// An apply policy declined.
  policy,

  /// Payload not understood by this app version.
  unsupported,
}

sealed class ApplyOutcome {
  const ApplyOutcome();
}

/// Written (or deleted); [localHash] = hash of the projection read back, null when the row is now absent.
final class Applied extends ApplyOutcome {
  const Applied(this.localHash);
  final String? localHash;
}

/// Not written; [localHash] = hash of the current local projection, null when no local row exists.
final class Skipped extends ApplyOutcome {
  const Skipped(this.reason, this.localHash);
  final SkipReason reason;
  final String? localHash;
}

/// Bridge between one synced kind and the app's storage. Implementations are
/// pure projections: never sync a field that is not on the allowlist
/// (credentials, API keys, device-local flags...).
abstract class SyncAdapter {
  String get kind;
  int get kindVersion;
  FileClass get fileClass;

  /// true: scope = syncProfileId and rows live under the bound local profile. false: global scope.
  bool get perAccount;

  /// Only kinds whose rows the user can delete in-app produce tombstones.
  bool get propagatesDeletes;

  ConflictPolicy get conflictPolicy;

  /// Every domain row for this scope. [localProfileId] is null for global kinds.
  /// Must THROW on any read error — a partially read table must never look like deletions.
  Future<List<DomainRow>> readAll(String? localProfileId);

  /// Projection of a replica payload, same shape as [DomainRow.projection]; null if unusable.
  JsonMap? project(JsonMap payload);

  /// Write [payload] for [key] under [localProfileId] in one step: re-read the
  /// row, answer [Skipped] with [SkipReason.changedLocally] when its
  /// projection hash differs from [expectedLocalHash] (null = "no row should
  /// exist yet"), otherwise write and answer the read-back hash.
  Future<ApplyOutcome> apply(
    String? localProfileId,
    String key,
    JsonMap payload,
    int versionTs,
    String? expectedLocalHash,
  );

  /// Delete [key] under [localProfileId] (same expected-hash guard).
  Future<ApplyOutcome> delete(String? localProfileId, String key, String? expectedLocalHash);

  /// [ConflictPolicy.keepBothText] only: a payload keeping both texts, or null when no merge is needed.
  JsonMap? mergeConcurrent(JsonMap winner, JsonMap loser, String loserLabel) => null;

  /// Stamps extra, non-projected fields onto a payload this device writes.
  JsonMap decoratePayload(JsonMap payload, String deviceName) => payload;
}

/// A local profile currently bound (ACTIVE) to a sync scope.
@immutable
final class ActiveBinding {
  const ActiveBinding({required this.localProfileId, required this.syncProfileId, required this.provider});

  final String localProfileId;
  final String syncProfileId;
  final String provider;

  @override
  bool operator ==(Object other) =>
      other is ActiveBinding &&
      other.localProfileId == localProfileId &&
      other.syncProfileId == syncProfileId &&
      other.provider == provider;

  @override
  int get hashCode => Object.hash(localProfileId, syncProfileId, provider);

  @override
  String toString() => 'ActiveBinding($localProfileId -> $syncProfileId)';
}

// ---- Wire format (gzip JSON in Drive).

/// One record in a sync file. Keys as Android's WireRecord @SerialName.
@immutable
final class WireRecord {
  const WireRecord({
    required this.kind,
    this.kindVersion = 1,
    this.scope = SyncFormat.globalScope,
    required this.key,
    required this.ts,
    required this.device,
    this.prevTs,
    this.prevDevice,
    this.deleted = false,
    this.payload,
  });

  final String kind;
  final int kindVersion;
  final String scope;
  final String key;
  final int ts;
  final String device;
  final int? prevTs;
  final String? prevDevice;
  final bool deleted;
  final JsonMap? payload;

  /// kotlinx with encodeDefaults = true, explicitNulls = false: every
  /// defaulted field written, nulls left out, in declaration order.
  JsonMap toJson() => {
    'k': kind,
    'kv': kindVersion,
    's': scope,
    'id': key,
    'ts': ts,
    'd': device,
    if (prevTs != null) 'pts': prevTs,
    if (prevDevice != null) 'pd': prevDevice,
    'del': deleted,
    if (payload != null) 'v': payload,
  };

  /// null when [json] is not a readable record (it is dropped, not the file).
  static WireRecord? tryParse(Object? json) {
    if (json is! Map) return null;
    final kind = json['k'];
    final key = json['id'];
    final ts = json['ts'];
    final device = json['d'];
    if (kind is! String || key is! String || ts is! int || device is! String) return null;
    final kv = json['kv'] ?? 1;
    final scope = json['s'] ?? SyncFormat.globalScope;
    final pts = json['pts'];
    final pd = json['pd'];
    final del = json['del'] ?? false;
    final v = json['v'];
    if (kv is! int || scope is! String || del is! bool) return null;
    if (pts != null && pts is! int) return null;
    if (pd != null && pd is! String) return null;
    if (v != null && v is! Map<String, Object?>) return null;
    return WireRecord(
      kind: kind,
      kindVersion: kv,
      scope: scope,
      key: key,
      ts: ts,
      device: device,
      prevTs: pts as int?,
      prevDevice: pd as String?,
      deleted: del,
      payload: v as JsonMap?,
    );
  }
}

@immutable
final class SyncEnvelope {
  const SyncEnvelope({
    this.format = SyncFormat.format,
    this.schema = SyncFormat.schema,
    this.epoch = 0,
    required this.deviceId,
    required this.deviceName,
    required this.fileClass,
    this.shard = 0,
    required this.writtenAt,
    required this.appVersionCode,
    required this.records,
    this.purges,
  });

  final String format;
  final int schema;
  final int epoch;
  final String deviceId;
  final String deviceName;
  final String fileClass;
  final int shard;
  final int writtenAt;
  final int appVersionCode;
  final List<WireRecord> records;

  /// Scope purges the writer had applied when it wrote this file:
  /// syncProfileId -> epoch (Android SyncEnvelope.purges). A reader that knows
  /// a purge of scope S takes S's records only from files that acknowledge it
  /// (a resumed account); everything else is a stale relay. Null from older
  /// builds and when the writer knows none; not written then, so the bytes
  /// stay as before.
  final Map<String, int>? purges;

  JsonMap toJson() => {
    'format': format,
    'schema': schema,
    'epoch': epoch,
    'deviceId': deviceId,
    'deviceName': deviceName,
    'fileClass': fileClass,
    'shard': shard,
    'writtenAt': writtenAt,
    'appVersionCode': appVersionCode,
    'records': [for (final r in records) r.toJson()],
    'purges': ?purges,
  };
}

@immutable
final class ResetMarker {
  const ResetMarker({required this.epoch, required this.at, required this.deviceId, required this.deviceName});

  final int epoch;
  final int at;
  final String deviceId;
  final String deviceName;

  JsonMap toJson() => {'epoch': epoch, 'at': at, 'deviceId': deviceId, 'deviceName': deviceName};
}

/// "Remove this account from the cloud" (one scope, all devices; Android
/// ScopePurgeMarker): records of [scope] whose version ts is at or before
/// [epoch] are dropped from every replica that sees the marker, and no device
/// that knows it publishes them again (docs/cloud-sync.md「从云端移除一个账号」).
@immutable
final class ScopePurgeMarker {
  const ScopePurgeMarker({
    required this.scope,
    required this.epoch,
    required this.at,
    required this.deviceId,
    required this.deviceName,
  });

  final String scope;
  final int epoch;
  final int at;
  final String deviceId;
  final String deviceName;

  JsonMap toJson() => {'scope': scope, 'epoch': epoch, 'at': at, 'deviceId': deviceId, 'deviceName': deviceName};
}

/// Drive appProperties keys/values that identify Yoin files (never by name).
abstract final class SyncFileProps {
  static const kind = 'yoinKind';
  static const deviceId = 'deviceId';
  static const shard = 'shard';
  static const schema = 'schema';
  static const epoch = 'epoch';

  /// The scope a [kindPurge] marker removes.
  static const scope = 'scope';

  static const kindState = 'state';
  static const kindArtifacts = 'artifacts';
  static const kindReset = 'reset';

  /// A [ScopePurgeMarker]. Older builds ignore this kind.
  static const kindPurge = 'purge';

  static String stateFileName(String deviceId) => 'yoin-state-$deviceId.json.gz';

  static String artifactFileName(String deviceId, int shard) => 'yoin-artifacts-$deviceId-$shard.json.gz';

  static String resetFileName(int epoch, String deviceId) => 'yoin-reset-$epoch-$deviceId.json';

  static String purgeFileName(int epoch, String deviceId) => 'yoin-purge-$epoch-$deviceId.json';
}

// ---- Transport (Drive appDataFolder)

@immutable
final class RemoteFile {
  const RemoteFile({
    required this.id,
    required this.name,
    required this.version,
    this.modifiedTime,
    this.size,
    this.appProperties = const {},
  });

  final String id;
  final String name;
  final int version;
  final int? modifiedTime;
  final int? size;
  final Map<String, String> appProperties;
}

@immutable
final class GoogleAccountInfo {
  const GoogleAccountInfo({required this.permissionId, this.email, this.displayName});

  /// Stable Google identity for this user (Drive about.user.permissionId).
  final String permissionId;
  final String? email;
  final String? displayName;
}

/// Failures the sync manager maps to user-visible states.
sealed class SyncTransportException implements Exception {
  const SyncTransportException(this.message);
  final String message;

  @override
  String toString() => '$runtimeType: $message';
}

/// 401 that survived one token refresh, insufficient permissions, or a revoked grant.
final class SyncUnauthorized extends SyncTransportException {
  const SyncUnauthorized(super.message);
}

/// OAuth client / Drive API not set up for this build.
final class SyncMisconfigured extends SyncTransportException {
  const SyncMisconfigured(super.message);
}

final class SyncStorageFull extends SyncTransportException {
  const SyncStorageFull(super.message);
}

final class SyncRateLimited extends SyncTransportException {
  const SyncRateLimited(super.message, {this.retryAfterMs});
  final int? retryAfterMs;
}

final class SyncNetworkError extends SyncTransportException {
  const SyncNetworkError(super.message);
}

/// 409 on create with a pre-allocated id: the file already exists.
final class SyncAlreadyExists extends SyncTransportException {
  const SyncAlreadyExists(super.message);
}

/// Anything else from the server (5xx, unexpected 4xx).
final class SyncServerError extends SyncTransportException {
  const SyncServerError(this.code, String message) : super(message);
  final int code;
}

/// Supplies OAuth access tokens; [invalidate] drops a token the server rejected.
abstract interface class SyncTokenProvider {
  Future<String> token();

  Future<void> invalidate(String token);
}

abstract interface class SyncTransport {
  /// Every file in appDataFolder (all pages).
  Future<List<RemoteFile>> list();

  /// File content, or null when the file no longer exists (404).
  Future<Uint8List?> download(String fileId);

  /// Create with a pre-allocated [fileId]; throws [SyncAlreadyExists] on 409.
  Future<RemoteFile> create({
    required String fileId,
    required String name,
    required Map<String, String> appProperties,
    required String mimeType,
    required Uint8List bytes,
  });

  /// Replace content; null when the file no longer exists (404).
  Future<RemoteFile?> update(String fileId, Uint8List bytes);

  /// true if deleted or already absent.
  Future<bool> delete(String fileId);

  Future<List<String>> generateIds(int count);

  Future<GoogleAccountInfo> about();
}
