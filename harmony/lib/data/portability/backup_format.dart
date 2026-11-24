/// The "export / import everything" backup (docs/cloud-sync.md「导出与导入」),
/// ported from :shared `com.gpo.yoin.shared.portability` (BackupFormat.kt). One
/// zip:
///
///   manifest.json          [BackupManifest]: what is inside, per account, plus the snapshot's SHA-256
///   data/snapshot.json     a [SyncEnvelope] (fileClass "backup"): the full record set as WireRecords,
///                          exactly the shape Drive state files use (versions, tombstones, scopes)
///   readable/`account`/`album`.md, readable/global/playlist-backups.md   for people, never read back
///
/// Wire names are Kotlin's @SerialName: the files outlive app versions, and an
/// Android or iOS Yoin reads what this writes (golden vectors in
/// shared/src/commonTest/resources/golden/backup-*.json).
library;

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';

import '../sync/canonical_json.dart';
import '../sync/sync_contracts.dart';

abstract final class BackupFormat {
  static const format = 'yoin-backup';

  /// Bumped only for changes an older importer must refuse; new optional fields don't bump it.
  static const formatVersion = 1;

  static const manifestPath = 'manifest.json';
  static const snapshotPath = 'data/snapshot.json';
  static const readableDir = 'readable/';
  static const readableGlobalDir = 'readable/global/';

  /// [SyncEnvelope.fileClass] of a backup snapshot (never a Drive file class).
  static const snapshotFileClass = 'backup';

  /// Scope of an account that has no sync id (an Apple Music profile never synced): device-local.
  static const localScopePrefix = 'local-';

  /// [BackupSource.location] values.
  static const locationGoogleDrive = 'google-drive';
  static const locationICloud = 'icloud';
  static const locationThisDevice = 'this-device';

  /// Hard limits while reading, against a hostile or corrupt zip.
  static const maxManifestBytes = 1 << 20;
  static const maxSnapshotBytes = 512 << 20;

  /// `yoin-backup-YYYY-MM-DD.zip`, the date in [zone].
  static String fileName(int now, BackupZone zone) => 'yoin-backup-${BackupDates.date(now, zone)}.zip';

  static bool isLocalScope(String scope) => scope.startsWith(localScopePrefix);

  /// A real sync scope (Subsonic/Spotify fingerprint id or Apple Music link), not global and not device-local.
  static bool isSyncScope(String scope) => scope != SyncFormat.globalScope && !isLocalScope(scope);

  /// Lowercase hex SHA-256 (the manifest's snapshot checksum).
  static String sha256Hex(List<int> bytes) => sha256.convert(bytes).toString();
}

/// Record kinds a backup carries besides the synced ones. They never enter the
/// sync replica or Drive: import writes them straight into the domain of the
/// account they are mapped to.
abstract final class BackupKinds {
  /// `track_album_links`, key = provider␟trackId: which album a noted/rated track is on.
  static const trackAlbumLink = 'track_album_link';

  static const all = {trackAlbumLink};
}

/// Payload fields of [BackupKinds] records (and of album notes, which keep the same fields as a sync kind).
abstract final class BackupFields {
  static const albumId = 'albumId';
  static const provider = 'provider';
  static const content = 'content';
  static const createdAt = 'createdAt';
  static const updatedAt = 'updatedAt';
  static const albumName = 'albumName';
  static const artist = 'artist';
  static const trackId = 'trackId';
  static const source = 'source';
}

/// The manifest's JSON could not be read as a manifest.
final class ManifestFormatException implements Exception {
  const ManifestFormatException(this.message);
  final String message;

  @override
  String toString() => 'ManifestFormatException: $message';
}

@immutable
final class BackupManifest {
  const BackupManifest({
    this.format = BackupFormat.format,
    this.formatVersion = BackupFormat.formatVersion,
    this.syncSchema = SyncFormat.schema,
    required this.exportedAt,
    required this.source,
    required this.accounts,
    this.globalCounts = const {},
    required this.snapshot,
  });

  final String format;
  final int formatVersion;

  /// Sync wire schema of the snapshot's records ([SyncFormat.schema]).
  final int syncSchema;
  final int exportedAt;
  final BackupSource source;
  final List<BackupAccount> accounts;

  /// Record counts of the global scope, kind -> live records.
  final Map<String, int> globalCounts;
  final BackupSnapshotInfo snapshot;

  /// encodeDefaults = true, explicitNulls = false, in declaration order.
  JsonMap toJson() => {
    'format': format,
    'formatVersion': formatVersion,
    'syncSchema': syncSchema,
    'exportedAt': exportedAt,
    'source': source.toJson(),
    'accounts': [for (final a in accounts) a.toJson()],
    'globalCounts': globalCounts,
    'snapshot': snapshot.toJson(),
  };

  /// Kotlin's BackupManifestJson: pretty-printed (4 spaces), defaults written, nulls left out.
  String encode() => prettyJson(toJson());

  /// Unknown keys tolerated; throws [ManifestFormatException] for anything kotlinx wouldn't decode.
  static BackupManifest fromJson(Object? json) {
    final m = _object(json, 'manifest');
    return BackupManifest(
      format: _string(m, 'format', fallback: BackupFormat.format),
      formatVersion: _int(m, 'formatVersion', fallback: BackupFormat.formatVersion),
      syncSchema: _int(m, 'syncSchema', fallback: SyncFormat.schema),
      exportedAt: _int(m, 'exportedAt'),
      source: BackupSource.fromJson(m['source']),
      accounts: [for (final a in _list(m, 'accounts')) BackupAccount.fromJson(a)],
      globalCounts: _counts(m, 'globalCounts'),
      snapshot: BackupSnapshotInfo.fromJson(m['snapshot']),
    );
  }

  BackupManifest copyWith({List<BackupAccount>? accounts}) => BackupManifest(
    format: format,
    formatVersion: formatVersion,
    syncSchema: syncSchema,
    exportedAt: exportedAt,
    source: source,
    accounts: accounts ?? this.accounts,
    globalCounts: globalCounts,
    snapshot: snapshot,
  );
}

@immutable
final class BackupSource {
  const BackupSource({
    required this.location,
    required this.deviceId,
    required this.deviceName,
    required this.appVersionName,
    required this.appVersionCode,
  });

  /// Where Cloud sync kept the data: [BackupFormat.locationGoogleDrive], else [BackupFormat.locationThisDevice].
  final String location;
  final String deviceId;
  final String deviceName;
  final String appVersionName;
  final int appVersionCode;

  JsonMap toJson() => {
    'location': location,
    'deviceId': deviceId,
    'deviceName': deviceName,
    'appVersionName': appVersionName,
    'appVersionCode': appVersionCode,
  };

  static BackupSource fromJson(Object? json) {
    final m = _object(json, 'source');
    return BackupSource(
      location: _string(m, 'location'),
      deviceId: _string(m, 'deviceId'),
      deviceName: _string(m, 'deviceName'),
      appVersionName: _string(m, 'appVersionName'),
      appVersionCode: _int(m, 'appVersionCode'),
    );
  }
}

/// A music account in the backup: display metadata only, the same allowlist as the sync account descriptor.
@immutable
final class BackupAccount {
  const BackupAccount({
    required this.scope,
    required this.provider,
    required this.displayName,
    this.hint,
    this.storefront,
    required this.onDevice,
    this.counts = const {},
    this.readableDir,
  });

  /// The sync scope (syncProfileId), or [BackupFormat.localScopePrefix] + local profile id.
  final String scope;
  final String provider;
  final String displayName;

  /// "`username` @ `host[:port]`" for Subsonic.
  final String? hint;
  final String? storefront;

  /// true: a profile on the exporting device; false: only known from Drive (cloud-only there).
  final bool onDevice;

  /// kind -> live records of this account.
  final Map<String, int> counts;

  /// Folder of this account under readable/.
  final String? readableDir;

  BackupAccount copyWith({Map<String, int>? counts, String? readableDir}) => BackupAccount(
    scope: scope,
    provider: provider,
    displayName: displayName,
    hint: hint,
    storefront: storefront,
    onDevice: onDevice,
    counts: counts ?? this.counts,
    readableDir: readableDir ?? this.readableDir,
  );

  JsonMap toJson() => {
    'scope': scope,
    'provider': provider,
    'displayName': displayName,
    'hint': ?hint,
    'storefront': ?storefront,
    'onDevice': onDevice,
    'counts': counts,
    'readableDir': ?readableDir,
  };

  static BackupAccount fromJson(Object? json) {
    final m = _object(json, 'account');
    return BackupAccount(
      scope: _string(m, 'scope'),
      provider: _string(m, 'provider'),
      displayName: _string(m, 'displayName'),
      hint: _optString(m, 'hint'),
      storefront: _optString(m, 'storefront'),
      onDevice: _bool(m, 'onDevice'),
      counts: _counts(m, 'counts'),
      readableDir: _optString(m, 'readableDir'),
    );
  }
}

@immutable
final class BackupSnapshotInfo {
  const BackupSnapshotInfo({
    this.path = BackupFormat.snapshotPath,
    required this.sha256,
    required this.bytes,
    required this.records,
    required this.tombstones,
  });

  final String path;
  final String sha256;
  final int bytes;
  final int records;
  final int tombstones;

  JsonMap toJson() => {'path': path, 'sha256': sha256, 'bytes': bytes, 'records': records, 'tombstones': tombstones};

  static BackupSnapshotInfo fromJson(Object? json) {
    final m = _object(json, 'snapshot');
    return BackupSnapshotInfo(
      path: _string(m, 'path', fallback: BackupFormat.snapshotPath),
      sha256: _string(m, 'sha256'),
      bytes: _int(m, 'bytes'),
      records: _int(m, 'records'),
      tombstones: _int(m, 'tombstones'),
    );
  }
}

// ---- kotlinx-like readers: a missing key takes the default, a wrong type fails.

Map<String, Object?> _object(Object? json, String what) {
  if (json is Map<String, Object?>) return json;
  throw ManifestFormatException('$what is not an object');
}

String _string(Map<String, Object?> m, String key, {String? fallback}) {
  final v = m[key];
  if (v is String) return v;
  if (v == null && fallback != null && !m.containsKey(key)) return fallback;
  throw ManifestFormatException('$key is not a string');
}

String? _optString(Map<String, Object?> m, String key) {
  final v = m[key];
  if (v == null || v is String) return v as String?;
  throw ManifestFormatException('$key is not a string');
}

/// An integral number, or a string Kotlin's toLong() reads ("5"), as kotlinx reads a number.
int _int(Map<String, Object?> m, String key, {int? fallback}) {
  final v = m[key];
  if (v is int) return v;
  if (v is String && RegExp(r'^[+-]?[0-9]+$').hasMatch(v)) {
    final parsed = int.tryParse(v);
    if (parsed != null) return parsed;
  }
  if (v == null && fallback != null && !m.containsKey(key)) return fallback;
  throw ManifestFormatException('$key is not a number');
}

bool _bool(Map<String, Object?> m, String key) {
  final v = m[key];
  if (v is bool) return v;
  throw ManifestFormatException('$key is not a boolean');
}

List<Object?> _list(Map<String, Object?> m, String key) {
  final v = m[key];
  if (v is List) return v;
  throw ManifestFormatException('$key is not a list');
}

Map<String, int> _counts(Map<String, Object?> m, String key) {
  if (!m.containsKey(key)) return const {};
  final v = m[key];
  if (v is! Map) throw ManifestFormatException('$key is not an object');
  return {
    for (final e in v.entries) e.key as String: _int({'n': e.value}, 'n'),
  };
}

/// kotlinx.serialization's prettyPrint (default indent: four spaces): `"key": value`,
/// one entry per line, `{}` / `[]` when empty, strings quoted as kotlinx prints them.
String prettyJson(Object? value, {String indent = '    '}) {
  final out = StringBuffer();
  _writePretty(out, value, indent, 0);
  return out.toString();
}

void _writePretty(StringBuffer out, Object? value, String indent, int depth) {
  switch (value) {
    case final Map<Object?, Object?> map:
      if (map.isEmpty) {
        out.write('{}');
        return;
      }
      out.write('{\n');
      var first = true;
      for (final e in map.entries) {
        if (!first) out.write(',\n');
        first = false;
        out.write(indent * (depth + 1));
        CanonicalJson.writeQuoted(out, e.key as String);
        out.write(': ');
        _writePretty(out, e.value, indent, depth + 1);
      }
      out.write('\n${indent * depth}}');
    case final List<Object?> list:
      if (list.isEmpty) {
        out.write('[]');
        return;
      }
      out.write('[\n');
      for (var i = 0; i < list.length; i++) {
        if (i > 0) out.write(',\n');
        out.write(indent * (depth + 1));
        _writePretty(out, list[i], indent, depth + 1);
      }
      out.write('\n${indent * depth}]');
    default:
      out.write(compactJson(value));
  }
}

/// A time zone the backup writes its dates in (file name, readable Markdown).
/// Kotlin resolves IANA ids with the platform's tz database; Dart has none, so
/// the app writes in the device's own zone ([BackupZone.local]) and fixed
/// zones ("UTC", "+08:00") resolve here, as Kotlin's BackupDates does.
abstract class BackupZone {
  const BackupZone();

  /// The device's zone, with its daylight saving rules (DateTime's local time).
  static const BackupZone local = _LocalZone();

  static const BackupZone utc = FixedBackupZone(0);

  /// "Z", "UTC", "GMT", "UT" and fixed offsets ("+08:00", "-0530", "UTC+8"); null for anything else.
  static FixedBackupZone? fixed(String zoneId) {
    final id = zoneId.trim();
    if (id == 'Z' || id == 'UTC' || id == 'GMT' || id == 'UT') return const FixedBackupZone(0);
    final match = RegExp(r'^(?:UTC|GMT|UT)?([+-])(\d{1,2})(?::?(\d{2}))?$').firstMatch(id);
    if (match == null) return null;
    final total = int.parse(match[2]!) * 3600000 + (match[3] == null ? 0 : int.parse(match[3]!) * 60000);
    if (total > 18 * 3600000) return null;
    return FixedBackupZone(match[1] == '-' ? -total : total);
  }

  /// The UTC offset at [epochMs], in ms.
  int offsetMillis(int epochMs);
}

final class FixedBackupZone extends BackupZone {
  const FixedBackupZone(this.offset);
  final int offset;

  @override
  int offsetMillis(int epochMs) => offset;
}

final class _LocalZone extends BackupZone {
  const _LocalZone();

  @override
  int offsetMillis(int epochMs) => DateTime.fromMillisecondsSinceEpoch(epochMs).timeZoneOffset.inMilliseconds;
}

/// Dates as the backup writes them: "yyyy-MM-dd" and "yyyy-MM-dd HH:mm" in a
/// zone, the text java.time's DateTimeFormatter gives for years 0..9999.
abstract final class BackupDates {
  static String date(int epochMs, BackupZone zone) => _civil(epochMs + zone.offsetMillis(epochMs)).$1;

  static String dateTime(int epochMs, BackupZone zone) {
    final (date, time) = _civil(epochMs + zone.offsetMillis(epochMs));
    return '$date $time';
  }

  /// ("yyyy-MM-dd", "HH:mm") of a local epoch-ms value (Howard Hinnant's civil_from_days).
  static (String, String) _civil(int localMs) {
    final days = _floorDiv(localMs, 86400000);
    final msOfDay = localMs - days * 86400000;
    final z = days + 719468;
    final era = _floorDiv(z, 146097);
    final doe = z - era * 146097;
    final yoe = (doe - doe ~/ 1460 + doe ~/ 36524 - doe ~/ 146096) ~/ 365;
    var y = yoe + era * 400;
    final doy = doe - (365 * yoe + yoe ~/ 4 - yoe ~/ 100);
    final mp = (5 * doy + 2) ~/ 153;
    final d = doy - (153 * mp + 2) ~/ 5 + 1;
    final m = mp < 10 ? mp + 3 : mp - 9;
    if (m <= 2) y += 1;
    final minutes = msOfDay ~/ 60000;
    final date = '${y.toString().padLeft(4, '0')}-${_two(m)}-${_two(d)}';
    return (date, '${_two(minutes ~/ 60)}:${_two(minutes % 60)}');
  }

  static String _two(int value) => value.toString().padLeft(2, '0');

  static int _floorDiv(int a, int b) {
    final q = a ~/ b;
    return (a % b != 0) && ((a < 0) != (b < 0)) ? q - 1 : q;
  }
}
