/// Import from a backup (:shared BackupImport.kt): plan where each account
/// goes ([ImportPlanner]) and merge with the sync engine's rules
/// ([BackupMerger]). docs/cloud-sync.md「导出与导入」.
library;

import 'package:flutter/foundation.dart';

import '../sync/adapters/sync_projections.dart';
import '../sync/canonical_json.dart';
import '../sync/engine/sync_engine.dart';
import '../sync/identity/cloud_account_descriptor.dart';
import '../sync/sync_contracts.dart';
import '../sync/sync_database.dart';
import 'backup_files.dart';
import 'backup_format.dart';
import 'backup_ports.dart';
import 'memories/transfer_rules.dart' show MigrationIds;

/// Where one account of a backup goes.
sealed class ImportTarget {
  const ImportTarget();

  static const cloudOnly = ImportCloudOnly._();
  static const skip = ImportSkip._();
}

/// Merged into this local profile's data.
final class ImportIntoProfile extends ImportTarget {
  const ImportIntoProfile(this.localProfileId);
  final String localProfileId;

  @override
  bool operator ==(Object other) => other is ImportIntoProfile && other.localProfileId == localProfileId;

  @override
  int get hashCode => localProfileId.hashCode;
}

/// Kept in the sync replica as an account only in the cloud (uploaded with the next sync). Needs a replica.
final class ImportCloudOnly extends ImportTarget {
  const ImportCloudOnly._();
}

/// Left in the file (a Memories transfer can still copy from it).
final class ImportSkip extends ImportTarget {
  const ImportSkip._();
}

/// A local profile an account could be merged into.
@immutable
final class ImportProfileOption {
  const ImportProfileOption(this.localProfileId, this.provider, this.displayName);
  final String localProfileId;
  final String provider;
  final String displayName;
}

@immutable
final class ImportAccountPlan {
  const ImportAccountPlan({
    required this.account,
    required this.counts,
    required this.target,
    required this.sameAccount,
    required this.options,
    required this.cloudOnlyPossible,
    this.wasPurged = false,
  });

  final BackupAccount account;

  /// Live records per kind (tombstones aren't counted).
  final Map<String, int> counts;

  /// The default: the same account on this device, else cloud-only when possible, else skip.
  final ImportTarget target;

  /// true when [target] is a local profile that is the same account (sync binding or fingerprint).
  final bool sameAccount;

  /// Profiles of the same service the user may pick instead.
  final List<ImportProfileOption> options;

  /// [ImportCloudOnly] is possible: a sync scope, a replica on this device, and not [wasPurged].
  final bool cloudOnlyPossible;

  /// The account was removed from the cloud here: cloud-only isn't offered.
  final bool wasPurged;
}

/// What an import would do; apply it ([BackupMerger.apply]) with the user's choices.
@immutable
final class ImportPlan {
  const ImportPlan({required this.backup, required this.accounts, required this.globalCounts});

  final ParsedBackup backup;
  final List<ImportAccountPlan> accounts;

  /// Live global records per kind (settings, translations, About, playlist backups).
  final Map<String, int> globalCounts;
}

@immutable
final class ImportCounts {
  const ImportCounts({
    this.written = 0,
    this.unchanged = 0,
    this.keptLocal = 0,
    this.keptBoth = 0,
    this.tombstones = 0,
    this.skipped = 0,
    this.failedKinds = const {},
  });

  /// Rows written (new or newer).
  final int written;

  /// Already the same here.
  final int unchanged;

  /// This device holds a newer value (or the sync replica a newer version, e.g. a delete).
  final int keptLocal;

  /// Reviews whose two texts were both kept.
  final int keptBoth;

  /// Tombstones (an import never deletes).
  final int tombstones;

  /// Kinds or payloads this version can't apply, or declined by a policy (e.g. a setting).
  final int skipped;

  /// Kinds whose local table couldn't be read: nothing of them was written.
  final Set<String> failedKinds;

  ImportCounts operator +(ImportCounts other) => ImportCounts(
    written: written + other.written,
    unchanged: unchanged + other.unchanged,
    keptLocal: keptLocal + other.keptLocal,
    keptBoth: keptBoth + other.keptBoth,
    tombstones: tombstones + other.tombstones,
    skipped: skipped + other.skipped,
    failedKinds: {...failedKinds, ...other.failedKinds},
  );
}

@immutable
final class ImportResult {
  const ImportResult({
    required this.byScope,
    required this.global,
    required this.cloudOnlyMerged,
    this.cloudOnlyRefused = const [],
  });

  final Map<String, ImportCounts> byScope;
  final ImportCounts global;

  /// Records merged into the sync replica for cloud-only accounts (new or newer versions).
  final int cloudOnlyMerged;

  /// Accounts asked to go cloud-only that can't (removed from the cloud, no replica, not a sync scope).
  final List<String> cloudOnlyRefused;

  bool get changed =>
      cloudOnlyMerged > 0 || global.written > 0 || byScope.values.any((c) => c.written + c.keptBoth > 0);
}

/// Plans an import: each account of the backup mapped to a local profile, kept cloud-only, or skipped.
abstract final class ImportPlanner {
  /// [profiles]: every local music account; [replica]: the sync replica, null when sync never ran here.
  static ImportPlan plan(ParsedBackup backup, List<PortabilityProfile> profiles, BackupReplica? replica) {
    final live = [
      for (final r in backup.snapshot.records)
        if (!r.deleted) r,
    ];
    final accounts = accountsOf(backup);
    final locals = [...profiles]..sort(compareProfiles);
    final scopeOfProfile = {for (final p in locals) p.id: BackupScopes.of(p, replica)};
    final purged = {
      for (final key in replica?.metaWithPrefix(SyncMetaKeys.purgedScopePrefix).keys ?? const <String>[])
        if (key.startsWith(SyncMetaKeys.purgedScopePrefix)) key.substring(SyncMetaKeys.purgedScopePrefix.length),
    };
    final taken = <String>{};
    final plans = <ImportAccountPlan>[];
    for (final account in accounts) {
      PortabilityProfile? match;
      for (final p in locals) {
        if (taken.contains(p.id)) continue;
        if (scopeOfProfile[p.id] == account.scope || account.scope == '${BackupFormat.localScopePrefix}${p.id}') {
          match = p;
          break;
        }
      }
      if (match != null) taken.add(match.id);
      final wasPurged = purged.contains(account.scope);
      final cloudOnlyPossible = replica != null && BackupFormat.isSyncScope(account.scope) && !wasPurged;
      plans.add(
        ImportAccountPlan(
          account: account,
          counts: BackupExport.sortedCounts([
            for (final r in live)
              if (r.scope == account.scope) r.kind,
          ]),
          target: match != null
              ? ImportIntoProfile(match.id)
              : cloudOnlyPossible
              ? ImportTarget.cloudOnly
              : ImportTarget.skip,
          sameAccount: match != null,
          options: [
            for (final p in locals)
              if (p.provider == account.provider) ImportProfileOption(p.id, p.provider, p.displayName),
          ],
          cloudOnlyPossible: cloudOnlyPossible,
          wasPurged: wasPurged,
        ),
      );
    }
    return ImportPlan(
      backup: backup,
      accounts: plans,
      globalCounts: BackupExport.sortedCounts([
        for (final r in live)
          if (r.scope == SyncFormat.globalScope && r.kind != SyncKinds.account) r.kind,
      ]),
    );
  }

  /// The manifest's accounts, then any other scope the snapshot holds (described by its descriptor, if any).
  static List<BackupAccount> accountsOf(ParsedBackup backup) {
    final listed = {for (final a in backup.manifest.accounts) a.scope: a};
    final scopes = <String>{
      for (final a in backup.manifest.accounts) a.scope,
      for (final r in backup.snapshot.records)
        if (r.scope != SyncFormat.globalScope) r.scope,
    };
    final descriptors = <String, CloudAccountDescriptor>{};
    for (final r in backup.snapshot.records) {
      if (r.kind != SyncKinds.account || r.scope != SyncFormat.globalScope || r.deleted || r.payload == null) continue;
      final d = CloudAccountDescriptor.fromPayload(r.key, r.payload!);
      if (d != null) descriptors[d.syncProfileId] = d;
    }
    return [
      for (final scope in scopes)
        listed[scope] ??
            BackupAccount(
              scope: scope,
              provider: descriptors[scope]?.provider ?? 'unknown',
              displayName: (descriptors[scope]?.displayName.trim().isNotEmpty ?? false)
                  ? descriptors[scope]!.displayName
                  : scope,
              hint: descriptors[scope]?.hint,
              storefront: descriptors[scope]?.storefront,
              onDevice: false,
            ),
    ];
  }
}

/// Applies an [ImportPlan]: merges with the sync engine's rules, straight into
/// the stores, so the engine later publishes the result as ordinary local changes:
///
///  - last writer wins by version time (a record's ts against the row's own
///    updatedAt), equal values change nothing; a version the sync replica already
///    has newer (a delete, say) wins over the backup;
///  - album reviews (keepBothText): two different texts are both kept under a
///    "`device` · `date`" divider, and a text the local review already contains is
///    not added again;
///  - song / album notes keep their ids; an id owned by another profile here, or
///    a note coming from another account, gets a [MigrationIds] copy;
///  - tombstones never delete anything (an import only adds); track→album links
///    go into the stores only, never into the replica. Payloads are written whole.
///
/// Importing the same file twice changes nothing the second time.
class BackupMerger {
  BackupMerger(List<SyncAdapter> adapters, this._domain) : _adaptersByKind = {for (final a in adapters) a.kind: a};

  final Map<String, SyncAdapter> _adaptersByKind;
  final BackupDomain _domain;

  /// The text field of the one keepBothText kind (album_review).
  static const textField = 'review';

  /// A note goes in under a [MigrationIds] copy id unless it is already this profile's own.
  static bool needsCopyId(String? ownerProfileId, String profileId, String srcScope, String dstScope) {
    if (ownerProfileId == profileId) return false;
    if (ownerProfileId != null) return true;
    return srcScope != dstScope;
  }

  /// Applies [plan], with [choices] (scope -> target) overriding its defaults. Run under the sync lock.
  Future<ImportResult> apply(
    ImportPlan plan,
    Map<String, ImportTarget> choices,
    List<PortabilityProfile> profiles,
    BackupReplica? replica, {
    required bool importGlobal,
  }) async {
    final records = plan.backup.snapshot.records;
    final byScope = <String, List<WireRecord>>{};
    for (final r in records) {
      (byScope[r.scope] ??= []).add(r);
    }
    final deviceName = plan.backup.manifest.source.deviceName;
    final label = SyncEngine.loserLabel(
      deviceName.trim().isEmpty ? plan.backup.snapshot.deviceName : deviceName,
      plan.backup.manifest.exportedAt,
    );
    final results = <String, ImportCounts>{};
    final cloudOnly = <WireRecord>[];
    final refused = <String>[];
    final locals = {for (final p in profiles) p.id: p};
    for (final account in plan.accounts) {
      final scope = account.account.scope;
      switch (choices[scope] ?? account.target) {
        case ImportIntoProfile(:final localProfileId):
          final profile = locals[localProfileId];
          if (profile == null) continue;
          final dstScope = BackupScopes.of(profile, replica);
          final binding = replica?.bindingFor(profile.id);
          final bound = binding?.state == SyncBindingEntity.stateActive ? binding : null;
          results[scope] = await _mergeInto(
            records: byScope[scope] ?? const [],
            profileId: profile.id,
            srcScope: scope,
            dstScope: dstScope,
            replicaScope: bound?.syncProfileId,
            replica: replica,
            label: label,
          );
        case ImportCloudOnly():
          if (replica == null || !replica.canMergeImported || !account.cloudOnlyPossible) {
            refused.add(scope);
            continue;
          }
          // A local profile already syncing this scope would take these through the stores instead.
          if (replica.bindingForScope(scope) != null) continue;
          cloudOnly.addAll([
            for (final r in byScope[scope] ?? const <WireRecord>[])
              if (!BackupKinds.all.contains(r.kind)) r,
          ]);
          cloudOnly.addAll([
            for (final r in records)
              if (r.kind == SyncKinds.account && r.scope == SyncFormat.globalScope && r.key == scope) r,
          ]);
        case ImportSkip():
          break;
      }
    }
    final global = importGlobal
        ? await _mergeInto(
            records: [
              for (final r in byScope[SyncFormat.globalScope] ?? const <WireRecord>[])
                if (r.kind != SyncKinds.account) r,
            ],
            profileId: null,
            srcScope: SyncFormat.globalScope,
            dstScope: SyncFormat.globalScope,
            replicaScope: replica != null ? SyncFormat.globalScope : null,
            replica: replica,
            label: label,
          )
        : const ImportCounts();
    var merged = 0;
    if (cloudOnly.isNotEmpty && replica != null && replica.canMergeImported) {
      final s = plan.backup.snapshot;
      merged = await replica.mergeImported(
        SyncEnvelope(
          format: s.format,
          schema: s.schema,
          epoch: s.epoch,
          deviceId: s.deviceId,
          deviceName: s.deviceName,
          fileClass: s.fileClass,
          shard: s.shard,
          writtenAt: s.writtenAt,
          appVersionCode: s.appVersionCode,
          records: cloudOnly,
          purges: s.purges,
        ),
      );
    }
    return ImportResult(byScope: results, global: global, cloudOnlyMerged: merged, cloudOnlyRefused: refused);
  }

  /// Merges [records] (one scope, or the global scope with [profileId] null) into the stores.
  Future<ImportCounts> _mergeInto({
    required List<WireRecord> records,
    required String? profileId,
    required String srcScope,
    required String dstScope,
    required String? replicaScope,
    required BackupReplica? replica,
    required String label,
  }) async {
    var counts = const ImportCounts();
    final rowsByKind = <String, Map<String, DomainRow>>{};
    final failedKinds = <String>{};
    final sorted = stableSorted(records, (a, b) {
      final kind = a.kind.compareTo(b.kind);
      return kind != 0 ? kind : a.key.compareTo(b.key);
    });
    for (final record in sorted) {
      if (record.deleted) {
        counts += const ImportCounts(tombstones: 1);
        continue;
      }
      final payload = record.payload;
      if (payload == null) continue;
      if (BackupKinds.all.contains(record.kind)) {
        counts += profileId == null
            ? const ImportCounts(skipped: 1)
            : await _mergeBackupKind(record, payload, profileId);
        continue;
      }
      final adapter = _adaptersByKind[record.kind];
      if (adapter == null ||
          adapter.kind == SyncKinds.account ||
          adapter.perAccount != (profileId != null) ||
          record.kindVersion > adapter.kindVersion) {
        counts += const ImportCounts(skipped: 1);
        continue;
      }
      final projection = _safeProject(adapter, payload);
      if (projection == null) {
        counts += const ImportCounts(skipped: 1);
        continue;
      }
      if (failedKinds.contains(adapter.kind)) {
        counts += const ImportCounts(skipped: 1);
        continue;
      }
      var rows = rowsByKind[adapter.kind];
      if (rows == null) {
        try {
          rows = {for (final row in await adapter.readAll(profileId)) row.key: row};
          rowsByKind[adapter.kind] = rows;
        } catch (_) {
          // Never write into a table that can't be read: the guard hashes would all be wrong.
          failedKinds.add(adapter.kind);
          counts += ImportCounts(skipped: 1, failedKinds: {adapter.kind});
          continue;
        }
      }
      try {
        counts += await _mergeRecord(
          adapter,
          record,
          payload,
          projection,
          rows,
          profileId,
          srcScope,
          dstScope,
          replicaScope,
          replica,
          label,
        );
      } catch (_) {
        counts += const ImportCounts(skipped: 1);
      }
    }
    return counts;
  }

  Future<ImportCounts> _mergeRecord(
    SyncAdapter adapter,
    WireRecord record,
    JsonMap payload,
    JsonMap projection,
    Map<String, DomainRow> rows,
    String? profileId,
    String srcScope,
    String dstScope,
    String? replicaScope,
    BackupReplica? replica,
    String label,
  ) async {
    var key = record.key;
    // Notes (song and album) have global ids: another profile's or another account's go in as copies.
    if (profileId != null &&
        !rows.containsKey(key) &&
        (adapter.kind == SyncKinds.songNote || adapter.kind == SyncKinds.albumNote)) {
      final owner = adapter.kind == SyncKinds.songNote
          ? await _domain.songNoteOwner(key)
          : await _domain.albumNoteOwner(key);
      if (needsCopyId(owner, profileId, srcScope, dstScope)) key = MigrationIds.derive(srcScope, record.key, dstScope);
    }
    final local = rows[key];
    final hash = CanonicalJson.hash(projection);
    final localHash = local == null ? null : CanonicalJson.hash(local.projection);
    if (localHash == hash) return const ImportCounts(unchanged: 1);
    if (replicaScope != null && replica != null) {
      final known = replica.record(adapter.kind, replicaScope, key);
      if (known != null &&
          RecordVersion(known.ts, known.device).compareTo(RecordVersion(record.ts, record.device)) >= 0) {
        return const ImportCounts(keptLocal: 1);
      }
    }
    var toWrite = payload;
    var versionTs = record.ts;
    var keptBoth = false;
    if (local != null) {
      final localTs = local.rowTs ?? 0;
      if (adapter.conflictPolicy == ConflictPolicy.keepBothText) {
        final localText = requiredString(local.projection, textField) ?? '';
        final incoming = requiredString(projection, textField) ?? '';
        if (incoming.trim().isEmpty || localText.contains(incoming)) return const ImportCounts(unchanged: 1);
        if (localText.trim().isNotEmpty && !incoming.contains(localText)) {
          // This device's text stays first; the backup's follows under its "`device` · `date`".
          final merged = adapter.mergeConcurrent(local.projection, payload, label);
          if (merged == null) return const ImportCounts(keptLocal: 1);
          toWrite = merged;
          versionTs = record.ts > localTs ? record.ts : localTs;
          keptBoth = true;
        }
      } else if (record.ts <= localTs) {
        return const ImportCounts(keptLocal: 1);
      }
    }
    final outcome = await adapter.apply(profileId, key, toWrite, versionTs, localHash);
    switch (outcome) {
      case Applied():
        rows[key] = DomainRow(key: key, projection: _safeProject(adapter, toWrite) ?? projection, rowTs: versionTs);
        return keptBoth ? const ImportCounts(keptBoth: 1) : const ImportCounts(written: 1);
      case Skipped():
        return const ImportCounts(skipped: 1);
    }
  }

  Future<ImportCounts> _mergeBackupKind(WireRecord record, JsonMap payload, String profileId) async {
    if (record.kind != BackupKinds.trackAlbumLink) return const ImportCounts(skipped: 1);
    final provider = requiredString(payload, BackupFields.provider);
    final trackId = requiredString(payload, BackupFields.trackId);
    final albumId = requiredString(payload, BackupFields.albumId);
    if (provider == null || trackId == null || albumId == null) return const ImportCounts(skipped: 1);
    // A fact this device learned itself stays.
    if (await _domain.trackAlbumLink(profileId, provider, trackId) != null) return const ImportCounts(unchanged: 1);
    await _domain.putTrackAlbumLink(
      profileId,
      BackupTrackAlbumLink(
        provider: provider,
        trackId: trackId,
        albumId: albumId,
        source: requiredString(payload, BackupFields.source) ?? BackupTrackAlbumLink.sourceImport,
        updatedAt: requiredLong(payload, BackupFields.updatedAt) ?? record.ts,
      ),
    );
    return const ImportCounts(written: 1);
  }

  static JsonMap? _safeProject(SyncAdapter adapter, JsonMap payload) {
    try {
      return adapter.project(payload);
    } catch (_) {
      return null;
    }
  }
}
