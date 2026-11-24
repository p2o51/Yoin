/// Export all data, import from a backup and Transfer Memories on HarmonyOS
/// (Android data/sync/backup/MemoriesPortability, :shared YoinBackup): the
/// ported core over this device's stores, its sync store (when Cloud sync
/// ever ran here) and the backup zip. Works with sync off: local accounts and
/// backup files need neither Google nor the sync store.
library;

import 'dart:math';

import 'package:flutter/foundation.dart';

import '../../platform/storage/stores.dart';
import '../sync/engine/snapshot_codec.dart';
import '../sync/engine/sync_engine.dart';
import '../sync/identity/cloud_account_descriptor.dart';
import '../sync/sync_contracts.dart';
import '../sync/sync_database.dart';
import 'backup_files.dart';
import 'backup_format.dart';
import 'backup_import.dart';
import 'backup_ports.dart';
import 'backup_zip.dart';
import 'harmony_stores.dart';
import 'memories/memory_items.dart';
import 'memories/memory_matching.dart';
import 'memories/memory_transfer_applier.dart';
import 'memories/transfer_models.dart';
import 'memories/transfer_rules.dart';
import 'readable_export.dart';
import 'snapshot_assembler.dart';

/// What a portability job runs with, under the sync lock.
@immutable
final class PortabilityContext {
  const PortabilityContext({
    required this.replica,
    required this.deviceId,
    required this.deviceName,
    required this.syncOn,
  });

  /// The sync store, when Cloud sync ever ran on this device.
  final SyncDatabase? replica;

  /// The sync device id, null when there is no sync store.
  final String? deviceId;
  final String deviceName;

  /// Cloud sync is on now (Google Drive).
  final bool syncOn;
}

/// Runs a job while no sync runs (CloudSyncManager.runPortability).
typedef PortabilityRunner = Future<T> Function<T>(Future<T> Function(PortabilityContext context) body);

/// One export: the zip's bytes, its file name and what went in.
@immutable
final class BackupExportResult {
  const BackupExportResult({required this.fileName, required this.bytes, required this.summary});

  final String fileName;
  final Uint8List bytes;
  final ExportSummary summary;
}

enum TransferSourceKind { localProfile, cloudAccount, backupAccount }

/// An account Memories can be copied from.
@immutable
final class TransferSource {
  const TransferSource(this.kind, this.account);
  final TransferSourceKind kind;
  final TransferAccount account;
}

/// A transfer before matching (:shared YoinTransferPlan): what will be copied,
/// which songs and albums need a counterpart on the target, and the matches /
/// picks earlier runs left in the ledger.
@immutable
final class TransferPlan {
  const TransferPlan({
    required this.source,
    required this.target,
    required this.items,
    required this.tracksToMatch,
    required this.albumsToMatch,
    required this.reusedTracks,
    required this.reusedAlbums,
    required this.previouslyCopied,
    required this.toCopy,
    required this.ledger,
  });

  final TransferAccount source;
  final TransferAccount target;
  final MemoryItems items;
  final List<TrackToMatch> tracksToMatch;
  final List<AlbumToMatch> albumsToMatch;

  /// Matched or Picked by an earlier run: passed back as they are.
  final List<TrackPick> reusedTracks;
  final List<AlbumPick> reusedAlbums;

  /// Items a previous run already copied: skipped this time.
  final int previouslyCopied;

  /// Items whose ids carry over or were matched before: copied for sure (more once matching picks).
  final TransferCounts toCopy;
  final Map<String, String> ledger;

  bool get needsMatching => tracksToMatch.isNotEmpty || albumsToMatch.isNotEmpty;
}

class MemoriesPortability {
  MemoriesPortability({
    required this.stores,
    required this.profiles,
    required this.adapters,
    required this.run,
    required this.appVersionName,
    required this.appVersionCode,
    this.zone = BackupZone.local,
    int Function()? clock,
    Random? random,
  }) : _clock = clock ?? (() => DateTime.now().millisecondsSinceEpoch),
       _random = random ?? Random.secure();

  final HarmonyMemoryStores stores;

  /// Every local music account.
  final Future<List<PortabilityProfile>> Function() profiles;

  /// The sync adapters over a sync store (the replica, or a scratch one when there is none).
  final List<SyncAdapter> Function(SyncDatabase db) adapters;
  final PortabilityRunner run;
  final String appVersionName;
  final int appVersionCode;
  final BackupZone zone;
  final int Function() _clock;
  final Random _random;

  /// Where a device without a sync store keeps the id it signs its backups with.
  static const deviceIdKey = 'portability_device_id.v1';

  KeyValueStore get _kv => stores.store;

  Future<String> _deviceId(PortabilityContext context) async {
    final id = context.deviceId;
    if (id != null) return id;
    final stored = await _kv.read(deviceIdKey);
    if (stored != null && stored.isNotEmpty) return stored;
    final fresh = List.generate(16, (_) => _random.nextInt(16).toRadixString(16)).join();
    await _kv.write(deviceIdKey, fresh);
    return fresh;
  }

  Future<List<SyncAdapter>> _adapters(PortabilityContext context) async {
    final replica = context.replica;
    if (replica != null) return adapters(replica);
    final scratch = SyncDatabase(MemorySyncDbFile());
    await scratch.open();
    return adapters(scratch);
  }

  BackupReplica? _replica(
    PortabilityContext context,
    List<SyncAdapter> adapters,
    String deviceId, {
    bool merge = true,
  }) {
    final db = context.replica;
    if (db == null) return null;
    final engine = merge
        ? SyncEngine(
            db: db,
            adapters: adapters,
            deviceId: deviceId,
            deviceName: () => context.deviceName,
            clock: _clock,
          )
        : null;
    return DatabaseBackupReplica(db, engine);
  }

  // ---- export

  /// Everything this device would sync now, plus accounts it can't (Spotify
  /// ones from Drive are carried as they are), as a zip. [language] picks the
  /// readable labels ("zh…" = Chinese).
  Future<BackupExportResult> export({required String language}) => run((context) async {
    final now = _clock();
    final deviceId = await _deviceId(context);
    final adapters = await _adapters(context);
    final content = await SnapshotAssembler(
      adapters: adapters,
      replica: _replica(context, adapters, deviceId, merge: false),
      domain: HarmonyBackupDomain(stores),
      deviceId: deviceId,
      deviceName: context.deviceName,
      appVersionCode: appVersionCode,
    ).build(await profiles(), now);
    final files = await BackupExport.files(
      content,
      source: BackupSource(
        location: context.syncOn ? BackupFormat.locationGoogleDrive : BackupFormat.locationThisDevice,
        deviceId: deviceId,
        deviceName: context.deviceName,
        appVersionName: appVersionName,
        appVersionCode: appVersionCode,
      ),
      names: HarmonyReadableNames(stores),
      labels: ReadableLabels.forLanguage(language),
      zone: zone,
    );
    return BackupExportResult(
      fileName: BackupFormat.fileName(now, zone),
      bytes: BackupZip.write(files),
      summary: files.summary,
    );
  });

  // ---- import

  /// Reads and checks a backup zip; throws [BackupReadException].
  ParsedBackup read(Uint8List bytes) => BackupZip.read(bytes);

  /// Where each account of [backup] would go by default, and what the user may choose instead.
  Future<ImportPlan> planImport(ParsedBackup backup) => run((context) async {
    final deviceId = await _deviceId(context);
    final adapters = await _adapters(context);
    return ImportPlanner.plan(backup, await profiles(), _replica(context, adapters, deviceId));
  });

  /// Applies [plan] with [choices] overriding its defaults; importing the same file again changes nothing.
  Future<ImportResult> applyImport(ImportPlan plan, Map<String, ImportTarget> choices, {required bool importGlobal}) =>
      run((context) async {
        final deviceId = await _deviceId(context);
        final adapters = await _adapters(context);
        return BackupMerger(
          adapters,
          HarmonyBackupDomain(stores),
        ).apply(plan, choices, await profiles(), _replica(context, adapters, deviceId), importGlobal: importGlobal);
      });

  // ---- transfer Memories

  /// Accounts Memories can be copied from: this device's accounts, then (sync on) accounts only in the cloud.
  Future<List<TransferSource>> transferSources() => run((context) async {
    final replica = context.replica == null ? null : DatabaseBackupReplica(context.replica!);
    final locals = [...await profiles()]..sort(compareProfiles);
    final local = [for (final p in locals) TransferSource(TransferSourceKind.localProfile, _accountOf(p, replica))];
    if (replica == null || !context.syncOn) return local;
    final localScopes = {for (final s in local) s.account.scope};
    final cloud = <TransferSource>[];
    for (final r in replica.recordsOfKind(SyncKinds.account)) {
      if (r.deleted || r.scope != SyncFormat.globalScope || localScopes.contains(r.key) || r.payload == null) continue;
      final d = CloudAccountDescriptor.fromPayload(r.key, r.payload!);
      if (d == null) continue;
      cloud.add(
        TransferSource(
          TransferSourceKind.cloudAccount,
          TransferAccount(
            scope: d.syncProfileId,
            provider: d.provider,
            displayName: d.displayName.trim().isEmpty ? d.syncProfileId : d.displayName,
            serverKey: BackupScopes.serverKeyOf(d.hint),
          ),
        ),
      );
    }
    return [...local, ...cloud];
  });

  /// The accounts of a backup file, as transfer sources.
  List<TransferSource> transferSourcesOf(ParsedBackup backup) => [
    for (final a in ImportPlanner.accountsOf(backup))
      TransferSource(
        TransferSourceKind.backupAccount,
        TransferAccount(
          scope: a.scope,
          provider: a.provider,
          displayName: a.displayName,
          serverKey: BackupScopes.serverKeyOf(a.hint),
        ),
      ),
  ];

  /// What [source] holds: a local account's stores, the sync store (cloud-only), or a backup's snapshot.
  /// Album Memories only: HarmonyOS keeps Yoin's AI words on albums (Android's only kind today).
  Future<MemoryItems> memoryItems(TransferSource source, {ParsedBackup? backup}) async {
    final items = switch (source.kind) {
      TransferSourceKind.localProfile => await memoryItemsOfProfile(
        stores,
        source.account.localProfileId ?? (throw ArgumentError('No local profile')),
      ),
      TransferSourceKind.cloudAccount => await run(
        (context) async => MemoryItems.fromRecords(
          context.replica?.recordsInScope(source.account.scope).map(entityToWire).toList() ??
              (throw StateError('Cloud sync has never run on this device')),
          source.account.scope,
        ),
      ),
      TransferSourceKind.backupAccount => MemoryItems.fromRecords(
        (backup ?? (throw ArgumentError('No backup'))).snapshot.records,
        source.account.scope,
      ),
    };
    return items.copyWith(
      memoryTitles: [
        for (final t in items.memoryTitles)
          if (t.entityType == TransferRules.entityAlbum) t,
      ],
      memoryCopies: [
        for (final c in items.memoryCopies)
          if (c.entityType == TransferRules.entityAlbum) c,
      ],
    );
  }

  /// [profileId] as a transfer target.
  Future<TransferAccount> transferAccountOf(String profileId) => run((context) async {
    final profile = (await profiles()).firstWhere(
      (p) => p.id == profileId,
      orElse: () => throw ArgumentError('No such profile'),
    );
    return _accountOf(profile, context.replica == null ? null : DatabaseBackupReplica(context.replica!));
  });

  /// What a copy from [source] into [target] (a local account) needs: the songs and albums to match.
  Future<TransferPlan> planTransfer(TransferAccount source, MemoryItems items, TransferAccount target) => run((
    context,
  ) async {
    final targetProfile = target.localProfileId ?? (throw ArgumentError('Memories are copied into a local profile'));
    if (source.localProfileId == targetProfile) throw ArgumentError('Source and target are the same profile');
    final ledger = await _ledger(context).read(source.scope, targetProfile);
    final pending = TransferRules.pendingItems(items, ledger);
    final (tracks, albums) = TransferRules.needsMatching(source, target, pending);
    final reusedTracks = <TrackPick>[];
    for (final ref in tracks) {
      final earlier = _reused(ledger[TransferRules.trackKey(ref)]);
      if (earlier != null) {
        reusedTracks.add(TrackPick(ref, earlier.$1, TrackTarget(provider: earlier.$2.provider, id: earlier.$2.id)));
      }
    }
    final reusedAlbums = <AlbumPick>[];
    for (final ref in albums) {
      final earlier = _reused(ledger[TransferRules.albumKey(ref)]);
      if (earlier != null) {
        final facts = items.albumFacts[ref];
        reusedAlbums.add(
          AlbumPick(
            ref,
            earlier.$1,
            AlbumTarget(
              provider: earlier.$2.provider,
              id: earlier.$2.id,
              name: facts?.name ?? '',
              artist: facts?.artist,
            ),
          ),
        );
      }
    }
    final trackRefs = {for (final p in reusedTracks) p.source};
    final albumRefs = {for (final p in reusedAlbums) p.source};
    return TransferPlan(
      source: source,
      target: target,
      items: items,
      tracksToMatch: [
        for (final ref in tracks)
          if (!trackRefs.contains(ref)) _trackToMatch(ref, items),
      ],
      albumsToMatch: [
        for (final ref in albums)
          if (!albumRefs.contains(ref))
            AlbumToMatch(source: ref, name: items.albumFacts[ref]?.name, artist: items.albumFacts[ref]?.artist),
      ],
      reusedTracks: reusedTracks,
      reusedAlbums: reusedAlbums,
      previouslyCopied: TransferRules.countItems(items) - TransferRules.countItems(pending),
      toCopy: TransferRules.mappableCounts(source, target, pending, trackRefs.contains, albumRefs.contains),
      ledger: ledger,
    );
  });

  /// Copies [plan] into its target with the matches [tracks] / [albums] (reused ones included; a song or
  /// album left out, uncertain or unmatched isn't copied and is retried next time), and records the run.
  Future<TransferResult> applyTransfer(TransferPlan plan, List<TrackPick> tracks, List<AlbumPick> albums) =>
      run((context) async {
        final outcome = await MemoryTransferApplier(HarmonyTransferStore(stores)).apply(
          source: plan.source,
          target: plan.target,
          items: plan.items,
          tracks: tracks,
          albums: albums,
          ledger: plan.ledger,
          clock: _clock,
        );
        await _ledger(context).write(plan.source.scope, plan.target.localProfileId!, outcome.ledgerEntries);
        return outcome.result;
      });

  /// What [plan]'s copy would hold with [tracks] / [albums] chosen (the review's summary).
  static TransferCounts countsWith(TransferPlan plan, List<TrackPick> tracks, List<AlbumPick> albums) {
    final pending = TransferRules.pendingItems(plan.items, plan.ledger);
    final trackRefs = {
      for (final p in tracks)
        if (p.status.copies && p.chosen != null) p.source,
    };
    final albumRefs = {
      for (final p in albums)
        if (p.status.copies && p.chosen != null) p.source,
    };
    return TransferRules.mappableCounts(plan.source, plan.target, pending, trackRefs.contains, albumRefs.contains);
  }

  // ---- helpers

  TransferLedger _ledger(PortabilityContext context) =>
      context.replica != null ? DatabaseTransferLedger(context.replica!) : KeyValueTransferLedger(_kv);

  TransferAccount _accountOf(PortabilityProfile profile, BackupReplica? replica) => TransferAccount(
    scope: BackupScopes.of(profile, replica),
    provider: profile.provider,
    displayName: profile.displayName,
    serverKey: BackupScopes.serverKeyOf(BackupScopes.subsonicHint(profile)),
    localProfileId: profile.id,
  );

  /// A song / album match an earlier run stored in the ledger (`matched|` or `chosen|`), else null.
  static (MatchStatus, TargetRef)? _reused(String? earlier) {
    if (earlier == null) return null;
    final MatchStatus status;
    if (earlier.startsWith(TransferLedger.chosen)) {
      status = MatchStatus.picked;
    } else if (earlier.startsWith(TransferLedger.matched)) {
      status = MatchStatus.matched;
    } else {
      return null;
    }
    final target = TransferRules.parseTarget(earlier.substring(earlier.indexOf('|') + 1));
    return target == null ? null : (status, target);
  }

  static TrackToMatch _trackToMatch(SourceRef ref, MemoryItems items) {
    final facts = items.trackFacts[ref];
    SourceNote? note;
    for (final n in items.songNotes) {
      if (n.track == ref && n.title.trim().isNotEmpty) {
        note = n;
        break;
      }
    }
    String? nonBlank(String? s) => s == null || s.trim().isEmpty ? null : s;
    return TrackToMatch(
      source: ref,
      title: facts?.title ?? note?.title,
      artist: nonBlank(facts?.artist) ?? nonBlank(note?.artist),
      album: facts?.album,
      albumId: facts?.albumId ?? TransferRules.linkedAlbum(ref, items),
      durationSec: facts?.durationSec,
    );
  }
}
