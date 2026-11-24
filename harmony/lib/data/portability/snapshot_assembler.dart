/// Builds a backup's record set (:shared SnapshotAssembler.kt): what this
/// device's sync engine would publish if it synced right now, plus the
/// accounts it can't sync. Lossless towards the replica — every replica record
/// (all scopes, tombstones, kinds this build doesn't know, Spotify accounts
/// HarmonyOS can't hold) is carried with its version and its payload's unknown
/// keys — and overlaid with the live stores:
///
///  - a row whose projection equals its record keeps the record (and its version);
///  - a row edited since it was last synced, or one the replica never had,
///    becomes a new version from this device, decided the way capture decides it;
///  - a tracked row of a delete-propagating kind that is gone becomes a tombstone.
///
/// With no replica (Cloud sync never turned on) the record set is just the
/// stores' projections. Profiles that aren't bound get the scope a binding
/// would get ([BackupScopes.derived]). Track→album links ride along as
/// [BackupKinds]. Nothing outside the sync allowlist is ever read.
library;

import '../sync/canonical_json.dart';
import '../sync/identity/cloud_account_descriptor.dart';
import '../sync/sync_contracts.dart';
import '../sync/sync_database.dart';
import 'backup_files.dart';
import 'backup_format.dart';
import 'backup_ports.dart';

class SnapshotAssembler {
  SnapshotAssembler({
    required List<SyncAdapter> adapters,
    required BackupReplica? replica,
    required BackupDomain domain,
    required this.deviceId,
    required this.deviceName,
    required this.appVersionCode,
  }) : _adapters = adapters,
       _replica = replica,
       _domain = domain;

  final List<SyncAdapter> _adapters;
  final BackupReplica? _replica;
  final BackupDomain _domain;
  final String deviceId;
  final String deviceName;
  final int appVersionCode;

  static String _k(String kind, String scope, String key) => '$kind\u0000$scope\u0000$key';

  /// [profiles]: every local music account; [now]: the export's time (new versions and writtenAt).
  Future<BackupContent> build(List<PortabilityProfile> profiles, int now) async {
    final replica = _replica;
    final out = <String, WireRecord>{};
    for (final r in replica?.allRecords() ?? const <WireRecord>[]) {
      out[_k(r.kind, r.scope, r.key)] = r;
    }
    final failures = <String>[];
    final accounts = <BackupAccount>[];
    final localProfiles = <String, String>{};
    final usedScopes = <String>{};

    for (final profile in [...profiles]..sort(compareProfiles)) {
      final binding = replica?.bindingFor(profile.id);
      var scope = binding != null && binding.state != SyncBindingEntity.statePausedMismatch
          ? binding.syncProfileId
          : BackupScopes.derived(profile);
      if (!usedScopes.add(scope)) {
        // A second profile of one account: never merge two profiles' rows into one scope.
        scope = '${BackupFormat.localScopePrefix}${profile.id}';
        usedScopes.add(scope);
      }
      final bound = binding?.state == SyncBindingEntity.stateActive && binding?.syncProfileId == scope;
      final states = <String, Map<String, SyncLocalStateEntity>>{};
      if (bound && replica != null) {
        for (final s in replica.localStatesInScope(scope)) {
          if (s.boundProfileId == profile.id) (states[s.kind] ??= {})[s.key] = s;
        }
      }
      for (final adapter in _adapters.where((a) => a.perAccount)) {
        final rows = await _readOrNull(adapter, profile.id, failures);
        if (rows == null) continue;
        _overlay(adapter, scope, rows, states[adapter.kind] ?? const {}, out, now);
      }
      await _addBackupKinds(profile.id, scope, out);
      final descriptor = _descriptorOf(out[_k(SyncKinds.account, SyncFormat.globalScope, scope)]);
      final hint = BackupScopes.subsonicHint(profile) ?? descriptor?.hint;
      if (descriptor == null || descriptor.displayName != profile.displayName) {
        out[_k(SyncKinds.account, SyncFormat.globalScope, scope)] = _descriptorRecord(
          profile,
          scope,
          hint,
          descriptor?.storefront,
          out,
          now,
        );
      }
      accounts.add(
        BackupAccount(
          scope: scope,
          provider: profile.provider,
          displayName: profile.displayName,
          hint: hint,
          storefront: descriptor?.storefront,
          onDevice: true,
        ),
      );
      localProfiles[scope] = profile.id;
    }

    final globalStates = <String, Map<String, SyncLocalStateEntity>>{};
    for (final s in replica?.localStatesInScope(SyncFormat.globalScope) ?? const <SyncLocalStateEntity>[]) {
      if (s.boundProfileId.isEmpty) (globalStates[s.kind] ??= {})[s.key] = s;
    }
    for (final adapter in _adapters.where((a) => !a.perAccount && a.kind != SyncKinds.account)) {
      final rows = await _readOrNull(adapter, null, failures);
      if (rows == null) continue;
      _overlay(adapter, SyncFormat.globalScope, rows, globalStates[adapter.kind] ?? const {}, out, now);
    }

    // Accounts this device only knows from Drive (cloud-only), described by their descriptor if any.
    final descriptors = <String, WireRecord>{
      for (final r in out.values)
        if (r.kind == SyncKinds.account && r.scope == SyncFormat.globalScope && !r.deleted) r.key: r,
    };
    final otherScopes = <String>{
      for (final r in out.values)
        if (r.scope != SyncFormat.globalScope && !usedScopes.contains(r.scope)) r.scope,
      for (final key in descriptors.keys)
        if (!usedScopes.contains(key)) key,
    }.toList()..sort();
    for (final scope in otherScopes) {
      final descriptor = _descriptorOf(descriptors[scope]);
      final name = descriptor?.displayName;
      accounts.add(
        BackupAccount(
          scope: scope,
          provider: descriptor?.provider ?? _providerFromRecords(scope, out.values) ?? 'unknown',
          displayName: name != null && name.trim().isNotEmpty ? name : scope,
          hint: descriptor?.hint,
          storefront: descriptor?.storefront,
          onDevice: false,
        ),
      );
    }

    final purges = replica == null ? const <String, int>{} : replicaPurges(replica);
    final snapshot = SyncEnvelope(
      deviceId: deviceId,
      deviceName: deviceName,
      fileClass: BackupFormat.snapshotFileClass,
      writtenAt: now,
      appVersionCode: appVersionCode,
      records: [...out.values]..sort(compareRecords),
      purges: purges.isEmpty ? null : purges,
    );
    return BackupContent(snapshot: snapshot, accounts: accounts, localProfiles: localProfiles, readFailures: failures);
  }

  Future<List<DomainRow>?> _readOrNull(SyncAdapter adapter, String? profileId, List<String> failures) async {
    try {
      return await adapter.readAll(profileId);
    } catch (_) {
      failures.add(adapter.kind);
      return null;
    }
  }

  void _overlay(
    SyncAdapter adapter,
    String scope,
    List<DomainRow> rows,
    Map<String, SyncLocalStateEntity> states,
    Map<String, WireRecord> out,
    int now,
  ) {
    final present = <String>{};
    for (final row in rows) {
      if (!present.add(row.key)) continue;
      final key = _k(adapter.kind, scope, row.key);
      final record = out[key];
      if (record != null && record.kindVersion > adapter.kindVersion) continue; // newer kind: carried as is
      final rowHash = CanonicalJson.hash(row.projection);
      final recordPayload = record == null || record.deleted ? null : record.payload;
      final recordProjection = recordPayload == null ? null : _safeProject(adapter, recordPayload);
      if (recordProjection != null && CanonicalJson.hash(recordProjection) == rowHash) continue;
      final tracked = states[row.key]?.localHash;
      final int ts;
      if (record == null) {
        ts = row.rowTs ?? now;
      } else if (tracked != null) {
        // Edited since this device last synced it: a new version after the record.
        if (rowHash == tracked) continue;
        ts = _max(now, record.ts + 1);
      } else if (record.deleted && !adapter.propagatesDeletes) {
        // Never synced here: the row's own time against the record's (capture's join rule).
        ts = _max(row.rowTs ?? now, record.ts + 1);
      } else if ((row.rowTs ?? 0) > record.ts) {
        ts = row.rowTs ?? now;
      } else {
        continue;
      }
      out[key] = WireRecord(
        kind: adapter.kind,
        kindVersion: adapter.kindVersion,
        scope: scope,
        key: row.key,
        ts: ts,
        device: deviceId,
        prevTs: record?.ts,
        prevDevice: record?.device,
        payload: adapter.decoratePayload(_mergedPayload(adapter, record, row.projection), deviceName),
      );
    }
    if (!adapter.propagatesDeletes) return;
    for (final MapEntry(key: rowKey, value: state) in states.entries) {
      if (present.contains(rowKey) || state.localHash == null) continue;
      final key = _k(adapter.kind, scope, rowKey);
      final record = out[key];
      if (record == null || record.deleted || record.kindVersion > adapter.kindVersion) continue;
      out[key] = WireRecord(
        kind: adapter.kind,
        kindVersion: adapter.kindVersion,
        scope: scope,
        key: rowKey,
        ts: _max(now, record.ts + 1),
        device: deviceId,
        prevTs: record.ts,
        prevDevice: record.device,
        deleted: true,
      );
    }
  }

  Future<void> _addBackupKinds(String profileId, String scope, Map<String, WireRecord> out) async {
    // Album notes are a sync kind (AlbumNoteSyncAdapter): they come in with the adapters above.
    for (final link in await _domain.trackAlbumLinks(profileId)) {
      final key = SyncFormat.compositeKey([link.provider, link.trackId]);
      out[_k(BackupKinds.trackAlbumLink, scope, key)] = WireRecord(
        kind: BackupKinds.trackAlbumLink,
        scope: scope,
        key: key,
        ts: link.updatedAt,
        device: deviceId,
        payload: {
          BackupFields.provider: link.provider,
          BackupFields.trackId: link.trackId,
          BackupFields.albumId: link.albumId,
          BackupFields.source: link.source,
          BackupFields.updatedAt: link.updatedAt,
        },
      );
    }
  }

  WireRecord _descriptorRecord(
    PortabilityProfile profile,
    String scope,
    String? hint,
    String? storefront,
    Map<String, WireRecord> out,
    int now,
  ) {
    final previous = out[_k(SyncKinds.account, SyncFormat.globalScope, scope)];
    return WireRecord(
      kind: SyncKinds.account,
      key: scope,
      ts: previous == null ? profile.createdAt : _max(now, previous.ts + 1),
      device: deviceId,
      prevTs: previous?.ts,
      prevDevice: previous?.device,
      payload: {
        AccountDescriptorFields.provider: profile.provider,
        AccountDescriptorFields.displayName: profile.displayName,
        AccountDescriptorFields.createdAt: profile.createdAt,
        AccountDescriptorFields.hint: hint,
        AccountDescriptorFields.storefront: storefront,
        AccountDescriptorFields.deviceName: deviceName,
      },
    );
  }

  static CloudAccountDescriptor? _descriptorOf(WireRecord? record) {
    final payload = record == null || record.deleted ? null : record.payload;
    if (payload == null) return null;
    return CloudAccountDescriptor.fromPayload(record!.key, payload);
  }

  static String? _providerFromRecords(String scope, Iterable<WireRecord> records) {
    for (final r in records) {
      if (r.scope == scope && !r.deleted) {
        final provider = r.payload?['provider'];
        return provider is String ? provider : null;
      }
    }
    return null;
  }

  static JsonMap? _safeProject(SyncAdapter adapter, JsonMap payload) {
    try {
      return adapter.project(payload);
    } catch (_) {
      return null;
    }
  }

  /// The record's payload with [projection] written over it (unknown keys survive), as capture builds it.
  static JsonMap _mergedPayload(SyncAdapter adapter, WireRecord? base, JsonMap projection) {
    final existing = base == null || base.deleted ? null : base.payload;
    if (existing == null) return projection;
    final merged = Map<String, Object?>.of(existing);
    for (final key in _safeProject(adapter, existing)?.keys ?? const <String>[]) {
      if (!projection.containsKey(key)) merged.remove(key);
    }
    merged.addAll(projection);
    return merged;
  }
}

int _max(int a, int b) => a > b ? a : b;
