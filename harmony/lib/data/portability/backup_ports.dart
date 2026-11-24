/// The small ports the backup / transfer core reads and writes through
/// (:shared BackupPorts.kt). Everything else (the zip, the file pickers, the
/// stores, the UI) is HarmonyOS's own: `harmony_portability.dart`.
library;

import 'package:flutter/foundation.dart';

import '../sync/engine/snapshot_codec.dart';
import '../sync/engine/sync_engine.dart';
import '../sync/identity/account_fingerprints.dart';
import '../sync/sync_contracts.dart';
import '../sync/sync_database.dart';
import 'backup_format.dart';

/// A local music account as the core sees it (:shared SyncProfile).
@immutable
final class PortabilityProfile {
  const PortabilityProfile({
    required this.id,
    required this.provider,
    required this.displayName,
    required this.createdAt,
    this.subsonicServerUrl,
    this.subsonicUsername,
    this.spotifyUserId,
  });

  final String id;
  final String provider;
  final String displayName;
  final int createdAt;
  final String? subsonicServerUrl;
  final String? subsonicUsername;

  /// The Spotify user id this device resolved before (none on HarmonyOS, which has no Spotify).
  final String? spotifyUserId;
}

/// Oldest first, then by id (every list of local profiles the core walks).
int compareProfiles(PortabilityProfile a, PortabilityProfile b) {
  final byTime = a.createdAt.compareTo(b.createdAt);
  return byTime != 0 ? byTime : a.id.compareTo(b.id);
}

/// The sync replica (`yoin-sync.json.gz`) as backups and transfers see it.
/// Callers hold the sync lock while they use it; a device where Cloud sync
/// never ran passes no replica at all.
abstract interface class BackupReplica {
  List<WireRecord> allRecords();

  List<WireRecord> recordsInScope(String scope);

  List<WireRecord> recordsOfKind(String kind);

  WireRecord? record(String kind, String scope, String key);

  SyncBindingEntity? bindingFor(String localProfileId);

  SyncBindingEntity? bindingForScope(String syncProfileId);

  List<SyncLocalStateEntity> localStatesInScope(String scope);

  /// sync_meta rows whose key starts with [prefix]: key -> value.
  Map<String, String> metaWithPrefix(String prefix);

  /// false when nothing can be merged into the replica (no sync engine here): cloud-only imports are refused.
  bool get canMergeImported;

  /// Merges a backup's cloud-only records like a peer's file; the records inserted, replaced or kept both.
  Future<int> mergeImported(SyncEnvelope envelope);
}

/// [BackupReplica] over the [SyncDatabase], merging through [engine] when given.
final class DatabaseBackupReplica implements BackupReplica {
  DatabaseBackupReplica(this._db, [this._engine]);

  final SyncDatabase _db;
  final SyncEngine? _engine;

  @override
  List<WireRecord> allRecords() => [for (final r in _db.allRecords()) entityToWire(r)];

  @override
  List<WireRecord> recordsInScope(String scope) => [for (final r in _db.recordsInScope(scope)) entityToWire(r)];

  @override
  List<WireRecord> recordsOfKind(String kind) => [for (final r in _db.recordsOfKind(kind)) entityToWire(r)];

  @override
  WireRecord? record(String kind, String scope, String key) {
    final r = _db.getRecord(kind, scope, key);
    return r == null ? null : entityToWire(r);
  }

  @override
  SyncBindingEntity? bindingFor(String localProfileId) => _db.bindingFor(localProfileId);

  @override
  SyncBindingEntity? bindingForScope(String syncProfileId) => _db.bindingForScope(syncProfileId);

  @override
  List<SyncLocalStateEntity> localStatesInScope(String scope) => _db.localStatesInScope(scope);

  @override
  Map<String, String> metaWithPrefix(String prefix) => {for (final e in _db.metaWithPrefix(prefix)) e.key: e.value};

  @override
  bool get canMergeImported => _engine != null;

  @override
  Future<int> mergeImported(SyncEnvelope envelope) async {
    final engine = _engine ?? (throw StateError('No sync engine'));
    final result = await engine.mergeImported(envelope);
    return result.inserted + result.replaced + result.keptBoth;
  }
}

/// `track_album_links`: which album a song is on, as a backup carries it ([BackupKinds.trackAlbumLink]).
@immutable
final class BackupTrackAlbumLink {
  const BackupTrackAlbumLink({
    required this.provider,
    required this.trackId,
    required this.albumId,
    required this.source,
    required this.updatedAt,
  });

  static const sourcePlay = 'play';
  static const sourceDetail = 'detail';
  static const sourceImport = 'import';

  final String provider;
  final String trackId;
  final String albumId;

  /// [sourcePlay], [sourceDetail] or [sourceImport].
  final String source;
  final int updatedAt;
}

/// What backups read and write in the app's own data besides the sync adapters.
abstract interface class BackupDomain {
  Future<List<BackupTrackAlbumLink>> trackAlbumLinks(String localProfileId);

  Future<BackupTrackAlbumLink?> trackAlbumLink(String localProfileId, String provider, String trackId);

  Future<void> putTrackAlbumLink(String localProfileId, BackupTrackAlbumLink link);

  /// The local profile that owns the song note with this id, or null when there is none.
  Future<String?> songNoteOwner(String id);

  /// The local profile that owns the album note with this id, or null when there is none.
  Future<String?> albumNoteOwner(String id);
}

/// Which scope a local profile's data has in backups and transfers: its sync
/// binding's scope, else the one a binding would get (Subsonic fingerprint),
/// else the device-local [BackupFormat.localScopePrefix] + profile id. No network.
abstract final class BackupScopes {
  static String of(PortabilityProfile profile, BackupReplica? replica) {
    final binding = replica?.bindingFor(profile.id);
    if (binding != null && binding.state != SyncBindingEntity.statePausedMismatch) return binding.syncProfileId;
    return derived(profile);
  }

  /// The scope a binding would give [profile].
  static String derived(PortabilityProfile profile) {
    final fingerprint = _subsonicFingerprint(profile);
    if (fingerprint != null) return AccountFingerprints.deterministicId(fingerprint);
    if (profile.provider == 'spotify') {
      final userId = profile.spotifyUserId;
      if (userId != null && userId.isNotEmpty) {
        return AccountFingerprints.deterministicId(AccountFingerprints.spotify(userId));
      }
    }
    return '${BackupFormat.localScopePrefix}${profile.id}';
  }

  /// "user @ host[:port]" of a Subsonic profile, else null.
  static String? subsonicHint(PortabilityProfile profile) {
    final url = profile.subsonicServerUrl;
    final user = profile.subsonicUsername;
    if (url == null || user == null) return null;
    return AccountFingerprints.subsonicHint(url, user);
  }

  /// "host[:port]" of a Subsonic "user @ host[:port]" hint, lowercased; null for anything else.
  static String? serverKeyOf(String? hint) {
    if (hint == null) return null;
    final at = hint.indexOf(' @ ');
    if (at < 0) return null;
    final key = hint.substring(at + 3).trim().toLowerCase();
    return key.isEmpty ? null : key;
  }

  static String? _subsonicFingerprint(PortabilityProfile profile) {
    final url = profile.subsonicServerUrl;
    final user = profile.subsonicUsername;
    if (url == null || user == null) return null;
    return AccountFingerprints.subsonic(url, user);
  }
}

/// Scope purges this device applied: scope -> epoch (sync_meta `purged_scope:<scope>`).
Map<String, int> replicaPurges(BackupReplica replica) => {
  for (final e in replica.metaWithPrefix(SyncMetaKeys.purgedScopePrefix).entries)
    if (e.key.startsWith(SyncMetaKeys.purgedScopePrefix) && int.tryParse(e.value) != null)
      e.key.substring(SyncMetaKeys.purgedScopePrefix.length): int.parse(e.value),
};
