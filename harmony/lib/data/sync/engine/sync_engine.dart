/// The sync engine: pure replica logic over [SyncDatabase] and the domain
/// [SyncAdapter]s (Android engine/SyncEngine.kt, spec §3–§4). No network, no UI.
///
/// Not re-entrant: the caller serializes every call (CloudSyncManager's lock).
/// Replica writes are atomic per remote file (merge) and per scope (capture);
/// domain writes go through the adapters, and the expected-hash guard plus
/// crash repair make the two stores converge after an interruption.
library;

import 'package:flutter/foundation.dart';

import '../canonical_json.dart';
import '../sync_contracts.dart';
import '../sync_database.dart';
import 'snapshot_codec.dart';
import 'sync_engine_results.dart';

class SyncEngine {
  SyncEngine({
    required SyncDatabase db,
    required List<SyncAdapter> adapters,
    required this.deviceId,
    required String Function() deviceName,
    int Function()? clock,
  }) : _db = db,
       _adapters = List.unmodifiable(adapters),
       _deviceName = deviceName,
       _clock = clock ?? _now,
       _byKind = {for (final a in adapters) a.kind: a} {
    if (deviceId.trim().isEmpty) throw ArgumentError('deviceId must not be blank');
    if (_byKind.length != _adapters.length) throw ArgumentError('Duplicate sync adapter kinds');
  }

  static int _now() => DateTime.now().millisecondsSinceEpoch;

  final SyncDatabase _db;
  final List<SyncAdapter> _adapters;
  final Map<String, SyncAdapter> _byKind;
  final String deviceId;
  final String Function() _deviceName;
  final int Function() _clock;

  /// Missing rows of one never-deleted kind in one scope that mean "the domain store was recreated".
  static const domainResetMinMissing = 2;

  /// Kinds published in artifact shards even when this build has no adapter
  /// for them. HarmonyOS carries Android's AI translations and playlist
  /// backups without applying them; About/Ask has its adapter now and stays
  /// listed so a replica without it (an adapter list in a test) shards alike.
  static const knownArtifactKinds = {SyncKinds.lyricsTranslation, SyncKinds.songAbout, SyncKinds.playlistBackup};

  static const _unknownDeviceName = 'Another device';

  /// Valve (spec §4.5): >= 3 and >= 50% of live records, or all of >= 2 live records.
  static bool valveTrips(int candidates, int live) =>
      (candidates >= 3 && candidates * 2 >= live) || (live >= 2 && candidates >= live);

  /// "`loser device name` · yyyy-MM-dd" (UTC date of the losing version), so
  /// every device that merges the same pair produces identical text.
  static String loserLabel(String deviceName, int ts) {
    try {
      final d = DateTime.fromMillisecondsSinceEpoch(ts, isUtc: true);
      if (d.year < 0 || d.year > 9999) return deviceName;
      final date =
          '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
      return '$deviceName · $date';
    } catch (_) {
      return deviceName;
    }
  }

  static String _uploadedHashKey(String fileKind, int shard) =>
      '${SyncMetaKeys.lastUploadedHashPrefix}$fileKind:$shard';

  static bool _sameContent(SyncRecordEntity a, SyncRecordEntity b) =>
      a.deleted == b.deleted && a.payloadHash == b.payloadHash;

  /// §3 tie rule: higher kindVersion, then the larger canonical payload hash (tombstones lowest).
  static bool _tieBreakPrefers(SyncRecordEntity incoming, SyncRecordEntity current) {
    if (incoming.kindVersion != current.kindVersion) return incoming.kindVersion > current.kindVersion;
    return (incoming.payloadHash ?? '').compareTo(current.payloadHash ?? '') > 0;
  }

  // ------------------------------------------------------------------ merge

  /// Merges every record of a downloaded remote file into the replica and
  /// records the file's seen version in the same transaction.
  Future<MergeResult> mergeRemote(RemoteFile file, SyncEnvelope envelope) {
    if (envelope.format != SyncFormat.format) {
      throw MalformedSyncFileException('Not a Yoin sync file (format=${envelope.format})');
    }
    if (envelope.schema > SyncFormat.schema) {
      throw SyncSchemaTooNewException(envelope.schema, envelope.deviceId, envelope.deviceName);
    }
    if (envelope.deviceId == deviceId) throw ArgumentError("This device's own files are never merged as remote");
    return _db.transaction(() async {
      final acc = _MergeAccumulator();
      final names = _DeviceNames(this, envelope);
      final purges = _PurgeFilter(_loadPurges(), envelope.purges ?? const {});
      for (final wire in envelope.records) {
        _mergeRecord(wire, names, purges, acc);
      }
      _db.upsertRemoteFile(
        SyncRemoteFileEntity(
          fileId: file.id,
          deviceId: envelope.deviceId,
          fileKind: envelope.fileClass,
          shard: envelope.shard,
          version: file.version,
          modifiedTime: file.modifiedTime,
          deviceName: envelope.deviceName,
          writtenAt: envelope.writtenAt,
          own: false,
        ),
      );
      return acc.build(file.id, envelope);
    });
  }

  void _mergeRecord(WireRecord wire, _DeviceNames names, _PurgeFilter purges, _MergeAccumulator acc) {
    acc.received++;
    final valid =
        wire.kind.isNotEmpty && wire.key.isNotEmpty && wire.device.isNotEmpty && (wire.deleted || wire.payload != null);
    if (!valid) {
      acc.malformed++;
      return;
    }
    if (purges.refuses(wire.kind, wire.scope, wire.key, wire.ts)) {
      // Removed from the cloud: a device that hasn't seen the marker (or an older build) still relays it.
      acc.purged++;
      return;
    }
    final incoming = wireToEntity(wire);
    final adapter = _supportedAdapter(incoming);
    if (adapter == null) acc.opaque++;

    final current = _db.getRecord(incoming.kind, incoming.scope, incoming.key);
    if (current == null) {
      _db.upsertRecord(incoming);
      acc.inserted++;
      acc.changed.add(incoming.scope, incoming.kind);
      return;
    }
    if (incoming.version == current.version) {
      // Same version, different payload (or kind version) -> deterministic winner, never an edit.
      final identical = _sameContent(incoming, current) && incoming.kindVersion == current.kindVersion;
      if (!identical && _tieBreakPrefers(incoming, current)) {
        _db.upsertRecord(incoming);
        final state = _db.getLocalState(incoming.kind, incoming.scope, incoming.key);
        if (state != null) {
          // Re-apply the winning payload (expected-hash guarded); the version did not move.
          _db.upsertLocalState(state.copyWith(appliedTs: null, appliedDevice: null).clearPending());
        }
        acc.replaced++;
        acc.changed.add(incoming.scope, incoming.kind);
      }
      return;
    }

    final state = _db.getLocalState(incoming.kind, incoming.scope, incoming.key);
    // Not concurrent with our pending change: an older version of our own relayed by a peer, or a version
    // derived from exactly our current one.
    final historyOrSuccessor = incoming.device == deviceId || incoming.prev == current.version;
    if (adapter != null && state != null && state.pending && current.device == deviceId && !historyOrSuccessor) {
      final base = state.pendingPrev;
      if ((base == null || incoming.version > base) && !_sameContent(incoming, current)) {
        _resolveConcurrent(adapter, current, incoming, state, names, acc);
        return;
      }
    }
    if (incoming.version > current.version) {
      _db.upsertRecord(incoming);
      if (state != null && state.pending) {
        _db.upsertLocalState(state.clearPending());
        if (!historyOrSuccessor && !_sameContent(incoming, current)) acc.localChangesDropped++;
      }
      acc.replaced++;
      acc.changed.add(incoming.scope, incoming.kind);
    }
  }

  /// [local] is this device's unpublished change, [incoming] a remote version it was not based on.
  void _resolveConcurrent(
    SyncAdapter adapter,
    SyncRecordEntity local,
    SyncRecordEntity incoming,
    SyncLocalStateEntity state,
    _DeviceNames names,
    _MergeAccumulator acc,
  ) {
    acc.conflicts++;
    final lwwWinner = local.version > incoming.version ? local : incoming;

    if (adapter.conflictPolicy == ConflictPolicy.keepBothText && !local.deleted && !incoming.deleted) {
      final loser = identical(lwwWinner, local) ? incoming : local;
      final merged = _mergeTexts(
        adapter,
        winner: parseJsonObject(lwwWinner.payload),
        loser: parseJsonObject(loser.payload),
        loserName: names.nameOf(loser.device),
        loserTs: loser.ts,
      );
      if (merged != null) {
        final version = RecordVersion(_max(local.ts, incoming.ts) + 1, deviceId);
        _db.upsertRecord(_localRecord(adapter, local.scope, local.key, version, lwwWinner.version, merged));
        // applied stays at the old version, so apply writes the merged text into the domain.
        _db.upsertLocalState(state.withPending(lwwWinner.version));
        acc.keptBoth++;
        acc.changed.add(local.scope, local.kind);
        return;
      }
    }

    final liveVersusTombstone = local.deleted != incoming.deleted;
    final winner = adapter.conflictPolicy == ConflictPolicy.lwwLiveWins && liveVersusTombstone
        ? (local.deleted ? incoming : local)
        : lwwWinner;
    if (identical(winner, lwwWinner)) {
      if (identical(winner, incoming)) {
        _db.upsertRecord(incoming);
        _db.upsertLocalState(state.clearPending());
        acc.localChangesDropped++;
        acc.replaced++;
        acc.changed.add(incoming.scope, incoming.kind);
      }
      // else: our pending change is newer and simply stays.
      return;
    }

    // Live beat a newer tombstone: re-issue the live payload above both versions.
    final newer = lwwWinner;
    final version = RecordVersion(_max(local.ts, incoming.ts) + 1, deviceId);
    final winnerPayload = parseJsonObject(winner.payload);
    final payload = winnerPayload == null ? null : adapter.decoratePayload(winnerPayload, _deviceName());
    final reissued = payload != null
        ? _localRecord(adapter, local.scope, local.key, version, newer.version, payload)
        : winner.copyWith(ts: version.ts, device: version.device, prevTs: newer.ts, prevDevice: newer.device);
    _db.upsertRecord(reissued);
    // Domain already holds our live row -> nothing to apply; a remote live winner still has to be applied.
    final applied = identical(winner, local) && state.applied == local.version ? version : state.applied;
    _db.upsertLocalState(
      state.withPending(newer.version).copyWith(appliedTs: applied?.ts, appliedDevice: applied?.device),
    );
    if (identical(winner, incoming)) acc.localChangesDropped++;
    acc.replaced++;
    acc.changed.add(local.scope, local.kind);
  }

  // ------------------------------------------------------------------ capture

  /// Diffs every domain row against the replica for each active binding (and
  /// the global scope), writing new versions, join reconciliations and
  /// valve-guarded tombstones.
  Future<CaptureResult> capture(List<ActiveBinding> bindings) async {
    final acc = _CaptureAccumulator();
    _purgeFloors = _loadPurges();
    final now = _clock();
    final name = _deviceName();
    final names = _DeviceNames(this, null);
    final byScope = <String, List<_Target>>{};
    for (final t in _targets(bindings)) {
      byScope.putIfAbsent(t.scope, () => []).add(t);
    }
    for (final entry in byScope.entries) {
      final scope = entry.key;
      final targets = entry.value;
      final reads = <_Target, List<DomainRow>>{};
      for (final target in targets) {
        try {
          reads[target] = await target.adapter.readAll(target.localProfileId);
        } catch (e) {
          // An unread table must never look like deletions: skip this (kind, scope) only.
          acc.failures.add(KindScopeFailure(target.adapter.kind, scope, e));
        }
      }
      if (reads.isEmpty) continue;
      final boundProfileId = targets.first.boundProfileId;
      final scopeAcc = _CaptureAccumulator();
      try {
        await _db.transaction(() async => _captureScope(scope, boundProfileId, reads, now, name, names, scopeAcc));
        acc.absorb(scopeAcc);
      } catch (e) {
        // An adapter hook that throws rolled this scope back; the other scopes keep syncing.
        for (final target in reads.keys) {
          acc.failures.add(KindScopeFailure(target.adapter.kind, scope, e));
        }
      }
    }
    _dropOrphanedHeldReview();
    return acc.build(_loadHeldReview());
  }

  /// A held review whose keys no longer have local state can never be
  /// re-evaluated by the valve; drop it.
  void _dropOrphanedHeldReview() {
    final held = _loadHeldReview();
    if (held == null) return;
    final tracked = {
      for (final s in _db.localStatesFor(held.kind, held.scope))
        if (s.localHash != null) s.key,
    };
    if (!held.keys.any(tracked.contains)) _db.deleteMeta(SyncMetaKeys.heldReview);
  }

  void _captureScope(
    String scope,
    String boundProfileId,
    Map<_Target, List<DomainRow>> reads,
    int now,
    String name,
    _DeviceNames names,
    _CaptureAccumulator acc,
  ) {
    // Local state recorded under another local profile describes an old binding: treat as absent.
    final states = <SyncLocalStateEntity>[];
    for (final state in _db.localStatesInScope(scope)) {
      if (state.boundProfileId == boundProfileId) {
        states.add(state);
      } else {
        _db.deleteLocalState(state.kind, state.scope, state.key);
      }
    }
    var statesByKind = <String, Map<String, SyncLocalStateEntity>>{};
    for (final s in states) {
      statesByKind.putIfAbsent(s.kind, () => {})[s.key] = s;
    }
    final recordsByKind = {
      for (final target in reads.keys)
        target.adapter.kind: {for (final r in _db.recordsFor(target.adapter.kind, scope)) r.key: r},
    };

    // Only per-account kinds count: global kinds never tombstone.
    final domainReset = reads.entries.any((e) {
      final adapter = e.key.adapter;
      return adapter.perAccount &&
          !adapter.propagatesDeletes &&
          _missingRows(
                adapter,
                e.value,
                statesByKind[adapter.kind] ?? const {},
                recordsByKind[adapter.kind] ?? const {},
              ) >=
              domainResetMinMissing;
    });
    if (domainReset) {
      // Rows of kinds that can't be deleted vanished: the store was recreated. Rebind, never tombstone.
      _db.deleteLocalStatesInScope(scope);
      statesByKind = {};
      acc.domainResets.add(scope);
    }

    for (final e in reads.entries) {
      final kind = e.key.adapter.kind;
      _captureKind(
        e.key,
        e.value,
        statesByKind[kind] ?? const {},
        recordsByKind[kind] ?? const {},
        now,
        name,
        names,
        acc,
      );
    }
  }

  int _missingRows(
    SyncAdapter adapter,
    List<DomainRow> rows,
    Map<String, SyncLocalStateEntity> states,
    Map<String, SyncRecordEntity> records,
  ) {
    final present = {for (final r in rows) r.key};
    return states.values
        .where((s) => s.localHash != null && !present.contains(s.key) && _isSupportedBy(records[s.key], adapter))
        .length;
  }

  void _captureKind(
    _Target target,
    List<DomainRow> rows,
    Map<String, SyncLocalStateEntity> states,
    Map<String, SyncRecordEntity> records,
    int now,
    String name,
    _DeviceNames names,
    _CaptureAccumulator acc,
  ) {
    final adapter = target.adapter;
    final kind = adapter.kind;
    final scope = target.scope;
    final boundProfileId = target.boundProfileId;
    final present = <String>{};

    for (final row in rows) {
      if (!present.add(row.key)) continue;
      final record = records[row.key];
      if (!_isSupportedBy(record, adapter)) continue; // newer kind version: carried, never captured
      final state = states[row.key];
      final hash = CanonicalJson.hash(row.projection);

      if (state != null) {
        // (a) tracked row
        if (hash == state.localHash) continue;
        if (record != null && !record.deleted && _projectedHash(adapter, record) == hash) {
          // Crash repair: the domain already holds the replica value.
          _db.upsertLocalState(state.copyWith(localHash: hash, appliedTs: record.ts, appliedDevice: record.device));
          acc.repaired++;
          continue;
        }
        final base = state.pending ? state.pendingPrev : record?.version;
        final version = _stamped(kind, scope, row.key, _max(now, record == null ? now : record.ts + 1));
        final edited = _mergedPayload(adapter, record, row.projection);
        // The domain never showed the replica's version: the edit is concurrent with it, so text kinds keep both.
        final unseen =
            record != null &&
                adapter.conflictPolicy == ConflictPolicy.keepBothText &&
                !record.deleted &&
                record.version != state.applied
            ? record
            : null;
        final keptBoth = unseen == null
            ? null
            : _mergeTexts(
                adapter,
                winner: edited,
                loser: parseJsonObject(unseen.payload),
                loserName: names.nameOf(unseen.device),
                loserTs: unseen.ts,
              );
        if (unseen != null && keptBoth != null) {
          _db.upsertRecord(_localRecord(adapter, scope, row.key, version, unseen.version, keptBoth));
          // applied = null: the next apply writes the merged text over the edited row (hash-guarded).
          _db.upsertLocalState(_localState(kind, scope, row.key, boundProfileId, hash, null, true, base));
          acc.keptBoth++;
          acc.newVersions.add(scope, kind);
          continue;
        }
        final payload = adapter.decoratePayload(edited, name);
        _db.upsertRecord(_localRecord(adapter, scope, row.key, version, base, payload));
        _db.upsertLocalState(_localState(kind, scope, row.key, boundProfileId, hash, version, true, base));
        acc.newVersions.add(scope, kind);
        continue;
      }

      if (record != null) {
        _reconcileJoin(adapter, target, row, hash, record, name, names, acc);
        continue;
      }

      // (c) brand new row
      final version = _stamped(kind, scope, row.key, row.rowTs ?? now);
      final payload = adapter.decoratePayload(row.projection, name);
      _db.upsertRecord(_localRecord(adapter, scope, row.key, version, null, payload));
      _db.upsertLocalState(_localState(kind, scope, row.key, boundProfileId, hash, version, true, null));
      acc.newVersions.add(scope, kind);
    }

    // (d) tracked rows that are gone
    final candidates = <(SyncLocalStateEntity, SyncRecordEntity)>[];
    for (final state in states.values) {
      if (present.contains(state.key) || state.localHash == null) continue;
      final record = records[state.key];
      if (!_isSupportedBy(record, adapter)) continue;
      if (!adapter.propagatesDeletes) {
        _db.deleteLocalState(kind, scope, state.key); // re-applied from the replica
        acc.droppedLocalStates++;
        continue;
      }
      if (record == null) {
        _db.deleteLocalState(kind, scope, state.key);
      } else if (record.deleted) {
        _db.upsertLocalState(state.copyWith(localHash: null, appliedTs: record.ts, appliedDevice: record.device));
      } else if (adapter.conflictPolicy == ConflictPolicy.lwwLiveWins && record.version != state.applied) {
        // The delete hit an older copy; concurrent live beats tombstone: restore it.
        _db.deleteLocalState(kind, scope, state.key);
        acc.droppedLocalStates++;
      } else {
        candidates.add((state, record));
      }
    }
    if (adapter.propagatesDeletes) _applyValve(adapter, scope, candidates, now, acc);
  }

  /// (b) A row exists but this binding never saw it: adopt, let the cloud win,
  /// or win by the row's own timestamp. Never a tombstone.
  void _reconcileJoin(
    SyncAdapter adapter,
    _Target target,
    DomainRow row,
    String hash,
    SyncRecordEntity record,
    String name,
    _DeviceNames names,
    _CaptureAccumulator acc,
  ) {
    final kind = adapter.kind;
    final scope = target.scope;
    if (!record.deleted && _projectedHash(adapter, record) == hash) {
      _db.upsertLocalState(_localState(kind, scope, row.key, target.boundProfileId, hash, record.version, false, null));
      acc.adopted++;
      return;
    }

    final candidateTs = row.rowTs ?? 0;
    final canWin = record.deleted && !adapter.propagatesDeletes ? true : candidateTs > record.ts;

    if (adapter.conflictPolicy == ConflictPolicy.keepBothText && !record.deleted) {
      final cloud = parseJsonObject(record.payload);
      final local = _mergedPayload(adapter, record, row.projection);
      final merged = _mergeTexts(
        adapter,
        winner: canWin ? local : cloud,
        loser: canWin ? cloud : local,
        loserName: canWin ? names.nameOf(record.device) : name,
        loserTs: canWin ? record.ts : candidateTs,
      );
      if (merged != null) {
        final version = _stamped(kind, scope, row.key, _max(candidateTs, record.ts) + 1);
        _db.upsertRecord(_localRecord(adapter, scope, row.key, version, record.version, merged));
        _db.upsertLocalState(
          _localState(kind, scope, row.key, target.boundProfileId, hash, null, true, record.version),
        );
        acc.keptBoth++;
        acc.newVersions.add(scope, kind);
        return;
      }
    }

    if (canWin) {
      final meaninglessTombstone = record.deleted && !adapter.propagatesDeletes;
      final ts = meaninglessTombstone ? _max(candidateTs, record.ts + 1) : candidateTs;
      final version = _stamped(kind, scope, row.key, ts);
      final payload = adapter.decoratePayload(_mergedPayload(adapter, record, row.projection), name);
      _db.upsertRecord(_localRecord(adapter, scope, row.key, version, record.version, payload));
      _db.upsertLocalState(
        _localState(kind, scope, row.key, target.boundProfileId, hash, version, true, record.version),
      );
      acc.newVersions.add(scope, kind);
    } else {
      // The cloud wins (value or tombstone): apply overwrites/deletes the row, guarded by its current hash.
      _db.upsertLocalState(_localState(kind, scope, row.key, target.boundProfileId, hash, null, false, null));
      acc.cloudWins++;
    }
  }

  void _applyValve(
    SyncAdapter adapter,
    String scope,
    List<(SyncLocalStateEntity, SyncRecordEntity)> candidates,
    int now,
    _CaptureAccumulator acc,
  ) {
    final kind = adapter.kind;
    final held = _loadHeldReview();
    final heldKeys = held != null && held.kind == kind && held.scope == scope ? held.keys.toSet() : null;
    // Keys the user is being asked about stay held until they answer.
    final stillHeld = [
      for (final c in candidates)
        if (heldKeys != null && heldKeys.contains(c.$1.key)) c,
    ];
    final fresh = [
      for (final c in candidates)
        if (!(heldKeys != null && heldKeys.contains(c.$1.key))) c,
    ];
    final tripped = fresh.isNotEmpty && valveTrips(candidates.length, _db.liveCount(kind, scope));
    final holding = tripped ? candidates : stillHeld;
    if (!tripped) {
      for (final (state, record) in fresh) {
        _writeTombstone(adapter, state, record, now);
        acc.tombstones.add(scope, kind);
      }
    }
    acc.heldTombstones += holding.length;
    if (holding.isNotEmpty && (held == null || heldKeys != null)) {
      _db.putMeta(
        SyncMetaKeys.heldReview,
        HeldReview(kind: kind, scope: scope, keys: [for (final h in holding) h.$1.key]..sort()).encode(),
      );
    } else if (holding.isEmpty && heldKeys != null) {
      _db.deleteMeta(SyncMetaKeys.heldReview);
    }
  }

  void _writeTombstone(SyncAdapter adapter, SyncLocalStateEntity state, SyncRecordEntity record, int now) {
    final base = state.pending ? state.pendingPrev : record.version;
    final version = _stamped(record.kind, record.scope, record.key, _max(now, record.ts + 1));
    _db.upsertRecord(
      SyncRecordEntity(
        kind: record.kind,
        scope: record.scope,
        key: record.key,
        kindVersion: adapter.kindVersion,
        ts: version.ts,
        device: version.device,
        prevTs: base?.ts,
        prevDevice: base?.device,
        deleted: true,
      ),
    );
    _db.upsertLocalState(
      state.copyWith(localHash: null, appliedTs: version.ts, appliedDevice: version.device).withPending(base),
    );
  }

  // ------------------------------------------------------------------ apply

  /// Writes replica records the domain does not hold yet, for active bindings and global kinds.
  Future<ApplyResult> apply(List<ActiveBinding> bindings) async {
    final acc = _ApplyAccumulator();
    for (final target in _targets(bindings)) {
      final adapter = target.adapter;
      final kind = adapter.kind;
      final records = _db.recordsFor(kind, target.scope);
      if (records.isEmpty) continue;
      final states = <String, SyncLocalStateEntity>{};
      for (final state in _db.localStatesFor(kind, target.scope)) {
        if (state.boundProfileId == target.boundProfileId) {
          states[state.key] = state;
        } else {
          _db.deleteLocalState(state.kind, state.scope, state.key);
        }
      }
      var failed = false;
      records.sort((a, b) => a.key.compareTo(b.key));
      for (final record in records) {
        if (!_isSupportedBy(record, adapter)) {
          acc.unsupported++;
          continue;
        }
        final state = states[record.key];
        if (state != null && state.applied == record.version) continue;
        try {
          await _applyRecord(adapter, target, record, state, acc);
        } catch (e) {
          // Isolate the record: its local state is untouched, so it is retried.
          if (!failed) acc.failures.add(KindScopeFailure(kind, target.scope, e));
          failed = true;
        }
      }
    }
    return acc.build();
  }

  Future<void> _applyRecord(
    SyncAdapter adapter,
    _Target target,
    SyncRecordEntity record,
    SyncLocalStateEntity? state,
    _ApplyAccumulator acc,
  ) async {
    final expected = state?.localHash;
    final ApplyOutcome outcome;
    if (record.deleted && !adapter.propagatesDeletes) {
      outcome = Skipped(SkipReason.unsupported, expected);
    } else if (record.deleted) {
      outcome = await adapter.delete(target.localProfileId, record.key, expected);
    } else {
      final payload = parseJsonObject(record.payload);
      outcome = payload == null
          ? Skipped(SkipReason.unsupported, expected)
          : await adapter.apply(target.localProfileId, record.key, payload, record.ts, expected);
    }
    switch (outcome) {
      case Applied(:final localHash):
        _db.upsertLocalState(_appliedState(adapter, target, record, state, localHash));
        if (!record.deleted) {
          acc.appliedLive.add(target.scope, adapter.kind);
        } else if (expected != null) {
          acc.removed.add(target.scope, adapter.kind);
        }
      case Skipped(:final reason, :final localHash):
        if (reason == SkipReason.changedLocally) {
          acc.skippedChangedLocally++; // the next capture sees the edit
        } else {
          // OWNED_ELSEWHERE: recording the owner's hash here would make the next capture tombstone it.
          final hash = reason == SkipReason.ownedElsewhere ? null : localHash;
          _db.upsertLocalState(_appliedState(adapter, target, record, state, hash));
          acc.skipped[reason] = (acc.skipped[reason] ?? 0) + 1;
        }
    }
  }

  SyncLocalStateEntity _appliedState(
    SyncAdapter adapter,
    _Target target,
    SyncRecordEntity record,
    SyncLocalStateEntity? state,
    String? localHash,
  ) => SyncLocalStateEntity(
    kind: adapter.kind,
    scope: target.scope,
    key: record.key,
    boundProfileId: target.boundProfileId,
    localHash: localHash,
    appliedTs: record.ts,
    appliedDevice: record.device,
    pending: state?.pending ?? false,
    pendingPrevTs: state?.pendingPrevTs,
    pendingPrevDevice: state?.pendingPrevDevice,
  );

  // ------------------------------------------------------------------ publish

  /// Serializes the whole replica (all scopes, tombstones and opaque records)
  /// into one state file and [SyncFormat.artifactShards] artifact shards,
  /// records sorted by (kind, scope, key).
  List<OutgoingFile> outgoingFiles({required int epoch, required int writtenAt, required int appVersionCode}) {
    final sorted = _db.allRecords()
      ..sort((a, b) {
        final byKind = a.kind.compareTo(b.kind);
        if (byKind != 0) return byKind;
        final byScope = a.scope.compareTo(b.scope);
        return byScope != 0 ? byScope : a.key.compareTo(b.key);
      });
    final state = <SyncRecordEntity>[];
    final shards = List.generate(SyncFormat.artifactShards, (_) => <SyncRecordEntity>[]);
    for (final record in sorted) {
      switch (_fileClassOf(record.kind)) {
        case FileClass.state:
          state.add(record);
        case FileClass.artifacts:
          shards[artifactShard(record.key)].add(record);
      }
    }
    final name = _deviceName();
    final loaded = _loadPurges();
    final purges = loaded.isEmpty ? null : loaded;
    return [
      _outgoingFile(FileClass.state, 0, state, epoch, writtenAt, appVersionCode, name, purges),
      for (var shard = 0; shard < shards.length; shard++)
        _outgoingFile(FileClass.artifacts, shard, shards[shard], epoch, writtenAt, appVersionCode, name, purges),
    ];
  }

  static int artifactShard(String key) => CanonicalJson.stableHash(key) % SyncFormat.artifactShards;

  OutgoingFile _outgoingFile(
    FileClass fileClass,
    int shard,
    List<SyncRecordEntity> records,
    int epoch,
    int writtenAt,
    int appVersionCode,
    String name,
    Map<String, int>? purges,
  ) {
    final wires = [for (final r in records) entityToWire(r)];
    final recordsJson = [for (final w in wires) w.toJson()];
    // Acknowledged purges are part of what the file says: a new one is published even with no record change.
    final contentHash = CanonicalJson.hash(purges == null ? recordsJson : {'records': recordsJson, 'purges': purges});
    final envelope = SyncEnvelope(
      epoch: epoch,
      deviceId: deviceId,
      deviceName: name,
      fileClass: fileClass.wireName,
      shard: shard,
      writtenAt: writtenAt,
      appVersionCode: appVersionCode,
      records: wires,
      purges: purges,
    );
    return OutgoingFile(
      fileKind: fileClass.wireName,
      shard: shard,
      bytes: SnapshotCodec.encode(envelope),
      contentHash: contentHash,
      includedVersions: {for (final r in records) RecordKey(r.kind, r.scope, r.key): r.version},
    );
  }

  /// After an HTTP 200 for [file]: clears pending only where the replica still
  /// holds the exact version that was serialized, and remembers the hash.
  Future<void> markUploaded(OutgoingFile file) => _db.transaction(() async {
    for (final state in _db.pendingLocalStates()) {
      final uploaded = file.includedVersions[RecordKey(state.kind, state.scope, state.key)];
      if (uploaded == null) continue;
      final current = _db.getRecord(state.kind, state.scope, state.key);
      if (current == null) continue;
      if (current.version == uploaded) _db.upsertLocalState(state.clearPending());
    }
    _db.putMeta(_uploadedHashKey(file.fileKind, file.shard), file.contentHash);
  });

  String? lastUploadedHash(String fileKind, int shard) => _db.meta(_uploadedHashKey(fileKind, shard));

  /// Forget every last-uploaded hash so the next publish re-uploads all files.
  void clearUploadedHashes() {
    for (final entry in _db.metaWithPrefix(SyncMetaKeys.lastUploadedHashPrefix)) {
      _db.deleteMeta(entry.key);
    }
  }

  // ------------------------------------------------------------------ held review

  HeldReview? heldReview() => _loadHeldReview();

  /// [restore] = true: forget the held rows' local state so the next apply
  /// restores them here. false: write the held tombstones now (delete on all
  /// devices). Returns the number of keys handled.
  Future<int> resolveHeldReview({required bool restore}) => _db.transaction(() async {
    final held = _loadHeldReview();
    if (held == null) return 0;
    final adapter = _byKind[held.kind];
    var handled = 0;
    if (adapter != null) {
      final now = _clock();
      for (final key in held.keys) {
        final state = _db.getLocalState(held.kind, held.scope, key);
        if (state == null) continue;
        if (restore) {
          _db.deleteLocalState(held.kind, held.scope, key);
          handled++;
        } else {
          final record = _db.getRecord(held.kind, held.scope, key);
          if (record == null || record.deleted || state.localHash == null || !_isSupportedBy(record, adapter)) continue;
          _writeTombstone(adapter, state, record, now);
          handled++;
        }
      }
    }
    _db.deleteMeta(SyncMetaKeys.heldReview);
    return handled;
  });

  // ------------------------------------------------------------------ maintenance

  /// Deletes [localProfileId]'s synced domain rows of [scope] (no tombstones:
  /// other devices keep them) and forgets the scope's local state. Rows edited
  /// since they were last synced are kept. Returns the number of rows deleted.
  Future<int> purgeScopeLocally(String scope, String localProfileId) async {
    if (scope == SyncFormat.globalScope) throw ArgumentError('Only per-account scopes can be purged');
    var deleted = 0;
    for (final adapter in _adapters.where((a) => a.perAccount)) {
      for (final state in _db.localStatesFor(adapter.kind, scope)) {
        final expected = state.localHash;
        if (expected == null || state.boundProfileId != localProfileId) continue;
        final outcome = await adapter.delete(localProfileId, state.key, expected);
        if (outcome is Applied) {
          // Forget each row as soon as it is gone, so an interrupted purge never tombstones it later.
          _db.deleteLocalState(adapter.kind, scope, state.key);
          deleted++;
        }
      }
    }
    _db.deleteLocalStatesInScope(scope);
    return deleted;
  }

  /// Drops records, local state and remote-file bookkeeping, plus the held
  /// review, last-uploaded hashes and scope purges (they belong to the Drive
  /// the replica came from, so bindings paused by one sync again). Bindings
  /// and the rest of meta stay.
  Future<void> clearReplica() => _db.transaction(() async {
    _db.clearRecords();
    _db.clearLocalStates();
    _db.clearRemoteFiles();
    _db.deleteMeta(SyncMetaKeys.heldReview);
    clearUploadedHashes();
    for (final entry in _db.metaWithPrefix(SyncMetaKeys.purgedScopePrefix)) {
      _db.deleteMeta(entry.key);
    }
    _db.updateBindingStates(SyncBindingEntity.statePurged, SyncBindingEntity.stateActive);
  });

  // ------------------------------------------------------------------ scope purge (docs/cloud-sync.md)

  /// Scope purges applied on this device: syncProfileId -> epoch.
  Map<String, int> purgedScopes() => _loadPurges();

  /// Applies a scope purge marker (Android SyncEngine.applyScopePurge):
  /// forgets every replica record of [scope] (and its account descriptor)
  /// with their local state, and remembers [epoch]. From then on merges take
  /// the scope's records only from files that acknowledge the purge
  /// ([SyncEnvelope.purges]) and only after the epoch, this device's own
  /// files acknowledge it, and new local versions of the scope are stamped
  /// after it. Domain rows stay. Idempotent; an epoch not newer than the one
  /// already applied changes nothing. Returns the records dropped.
  Future<int> applyScopePurge(String scope, int epoch) {
    if (scope == SyncFormat.globalScope) throw ArgumentError('Only per-account scopes can be removed from the cloud');
    return _db.transaction(() async {
      final metaKey = '${SyncMetaKeys.purgedScopePrefix}$scope';
      final known = int.tryParse(_db.meta(metaKey) ?? '');
      if (known != null && known >= epoch) return 0;
      _db.putMeta(metaKey, '$epoch');
      var removed = 0;
      // Everything held now came from files that didn't know the purge (or predates it), whatever its
      // timestamp says: a device whose clock runs ahead must not keep the account alive.
      for (final record in _db.recordsInScope(scope)) {
        _db.deleteRecord(record.kind, scope, record.key);
        _db.deleteLocalState(record.kind, scope, record.key);
        removed++;
      }
      // Local state left without a record would read as a tracked row on a resumed binding.
      for (final state in _db.localStatesInScope(scope)) {
        if (_db.getRecord(state.kind, scope, state.key) == null) _db.deleteLocalState(state.kind, scope, state.key);
      }
      if (_db.getRecord(SyncKinds.account, SyncFormat.globalScope, scope) != null) {
        _db.deleteRecord(SyncKinds.account, SyncFormat.globalScope, scope);
        _db.deleteLocalState(SyncKinds.account, SyncFormat.globalScope, scope);
        removed++;
      }
      if (_loadHeldReview()?.scope == scope) _db.deleteMeta(SyncMetaKeys.heldReview);
      return removed;
    });
  }

  Map<String, int> _loadPurges() {
    final out = <String, int>{};
    for (final entry in _db.metaWithPrefix(SyncMetaKeys.purgedScopePrefix)) {
      final scope = entry.key.substring(SyncMetaKeys.purgedScopePrefix.length);
      final epoch = int.tryParse(entry.value);
      if (scope.isNotEmpty && epoch != null) out[scope] = epoch;
    }
    return out;
  }

  /// Purges known when the current capture started (the engine is single-threaded by contract).
  Map<String, int> _purgeFloors = const {};

  /// A new local version of a record at [ts], moved after its purge epoch if
  /// it has one. The epoch is the one of the scope the record is purged with
  /// ([purgeScopeOf]): an account descriptor follows its account, or a resumed
  /// account's descriptor would stay refused.
  RecordVersion _stamped(String kind, String scope, String key, int ts) {
    final epoch = _purgeFloors[purgeScopeOf(kind, scope, key)];
    return RecordVersion(epoch != null && ts <= epoch ? epoch + 1 : ts, deviceId);
  }

  /// The scope a record belongs to for purging: its own, or the account a descriptor describes.
  static String purgeScopeOf(String kind, String scope, String key) =>
      scope == SyncFormat.globalScope && kind == SyncKinds.account ? key : scope;

  bool hasPendingChanges() => _db.pendingCount() > 0;

  // ------------------------------------------------------------------ helpers

  List<_Target> _targets(List<ActiveBinding> bindings) {
    final seenScopes = <String>{};
    final seenProfiles = <String>{};
    final usable = <ActiveBinding>[];
    for (final b in bindings) {
      if (b.syncProfileId == SyncFormat.globalScope || b.localProfileId.isEmpty) continue;
      if (!seenScopes.add(b.syncProfileId)) continue;
      if (!seenProfiles.add(b.localProfileId)) continue;
      usable.add(b);
    }
    return [
      for (final adapter in _adapters)
        if (adapter.perAccount)
          for (final b in usable) _Target(adapter, b.syncProfileId, b.localProfileId)
        else
          _Target(adapter, SyncFormat.globalScope, null),
    ];
  }

  SyncAdapter? _supportedAdapter(SyncRecordEntity record) {
    final adapter = _byKind[record.kind];
    return adapter != null && record.kindVersion <= adapter.kindVersion ? adapter : null;
  }

  /// null (no record) counts as supported.
  static bool _isSupportedBy(SyncRecordEntity? record, SyncAdapter adapter) =>
      record == null || record.kindVersion <= adapter.kindVersion;

  FileClass _fileClassOf(String kind) =>
      _byKind[kind]?.fileClass ?? (knownArtifactKinds.contains(kind) ? FileClass.artifacts : FileClass.state);

  static JsonMap? _safeProject(SyncAdapter adapter, JsonMap payload) {
    try {
      return adapter.project(payload);
    } catch (_) {
      return null;
    }
  }

  String? _projectedHash(SyncAdapter adapter, SyncRecordEntity record) {
    final payload = parseJsonObject(record.payload);
    if (payload == null) return null;
    final projection = _safeProject(adapter, payload);
    return projection == null ? null : CanonicalJson.hash(projection);
  }

  /// The replica payload with the domain projection written over it: unknown
  /// keys survive, and a projected key the domain no longer has is removed.
  JsonMap _mergedPayload(SyncAdapter adapter, SyncRecordEntity? base, JsonMap projection) {
    final existing = base == null || base.deleted ? null : parseJsonObject(base.payload);
    if (existing == null) return projection;
    final merged = <String, Object?>{...existing};
    final projectedKeys = _safeProject(adapter, existing)?.keys ?? const <String>[];
    for (final key in projectedKeys) {
      if (!projection.containsKey(key)) merged.remove(key);
    }
    merged.addAll(projection);
    return merged;
  }

  /// keepBothText: the adapter's merged payload (decorated), or null when nothing needs merging.
  JsonMap? _mergeTexts(
    SyncAdapter adapter, {
    required JsonMap? winner,
    required JsonMap? loser,
    required String loserName,
    required int loserTs,
  }) {
    if (winner == null || loser == null) return null;
    final winnerProjection = _safeProject(adapter, winner);
    if (winnerProjection != null) {
      final loserProjection = _safeProject(adapter, loser);
      if (loserProjection != null && jsonEquals(winnerProjection, loserProjection)) return null;
    }
    final merged = adapter.mergeConcurrent(winner, loser, loserLabel(loserName, loserTs));
    if (merged == null) return null;
    return adapter.decoratePayload(merged, _deviceName());
  }

  SyncRecordEntity _localRecord(
    SyncAdapter adapter,
    String scope,
    String key,
    RecordVersion version,
    RecordVersion? prev,
    JsonMap payload,
  ) => SyncRecordEntity(
    kind: adapter.kind,
    scope: scope,
    key: key,
    kindVersion: adapter.kindVersion,
    ts: version.ts,
    device: version.device,
    prevTs: prev?.ts,
    prevDevice: prev?.device,
    deleted: false,
    payload: compactJson(payload),
    payloadHash: CanonicalJson.hash(payload),
  );

  static SyncLocalStateEntity _localState(
    String kind,
    String scope,
    String key,
    String boundProfileId,
    String? localHash,
    RecordVersion? applied,
    bool pending,
    RecordVersion? pendingPrev,
  ) => SyncLocalStateEntity(
    kind: kind,
    scope: scope,
    key: key,
    boundProfileId: boundProfileId,
    localHash: localHash,
    appliedTs: applied?.ts,
    appliedDevice: applied?.device,
    pending: pending,
    pendingPrevTs: pending ? pendingPrev?.ts : null,
    pendingPrevDevice: pending ? pendingPrev?.device : null,
  );

  HeldReview? _loadHeldReview() => HeldReview.decode(_db.meta(SyncMetaKeys.heldReview));

  static int _max(int a, int b) => a > b ? a : b;
}

@immutable
final class _Target {
  const _Target(this.adapter, this.scope, this.localProfileId);

  final SyncAdapter adapter;
  final String scope;
  final String? localProfileId;

  String get boundProfileId => localProfileId ?? '';

  @override
  bool operator ==(Object other) =>
      other is _Target &&
      identical(other.adapter, adapter) &&
      other.scope == scope &&
      other.localProfileId == localProfileId;

  @override
  int get hashCode => Object.hash(adapter, scope, localProfileId);
}

/// [_known] = purges applied here; [_acknowledged] = purges the file's writer
/// had applied. A record of a purged scope is taken only from a writer that
/// knew that purge (or a newer one), and only after its epoch.
final class _PurgeFilter {
  const _PurgeFilter(this._known, this._acknowledged);

  final Map<String, int> _known;
  final Map<String, int> _acknowledged;

  bool refuses(String kind, String scope, String key, int ts) {
    if (_known.isEmpty) return false;
    final purgedScope = SyncEngine.purgeScopeOf(kind, scope, key);
    final epoch = _known[purgedScope];
    if (epoch == null) return false;
    final acked = _acknowledged[purgedScope];
    if (acked == null) return true;
    return acked < epoch || ts <= epoch;
  }
}

/// Resolves device names for conflict labels: this device, the file being
/// merged, then known remote files.
final class _DeviceNames {
  _DeviceNames(this._engine, this._envelope);

  final SyncEngine _engine;
  final SyncEnvelope? _envelope;
  Map<String, String>? _known;

  String nameOf(String device) {
    if (device == _engine.deviceId) {
      final name = _engine._deviceName();
      return name.trim().isEmpty ? SyncEngine._unknownDeviceName : name;
    }
    final envelope = _envelope;
    if (envelope != null && device == envelope.deviceId && envelope.deviceName.trim().isNotEmpty) {
      return envelope.deviceName;
    }
    final names = _known ??= {
      for (final f in _engine._db.remoteFiles())
        if (f.deviceName != null && f.deviceName!.trim().isNotEmpty) f.deviceId: f.deviceName!,
    };
    return names[device] ?? SyncEngine._unknownDeviceName;
  }
}

class _MergeAccumulator {
  int received = 0;
  int inserted = 0;
  int replaced = 0;
  int conflicts = 0;
  int keptBoth = 0;
  int localChangesDropped = 0;
  int opaque = 0;
  int malformed = 0;
  int purged = 0;
  final changed = ScopeKindCounter();

  MergeResult build(String fileId, SyncEnvelope envelope) => MergeResult(
    fileId: fileId,
    deviceId: envelope.deviceId,
    deviceName: envelope.deviceName,
    received: received,
    inserted: inserted,
    replaced: replaced,
    conflicts: conflicts,
    keptBoth: keptBoth,
    localChangesDropped: localChangesDropped,
    opaque: opaque,
    malformed: malformed,
    changedByScope: changed.snapshot(),
    purged: purged,
  );
}

class _CaptureAccumulator {
  final newVersions = ScopeKindCounter();
  final tombstones = ScopeKindCounter();
  int keptBoth = 0;
  int adopted = 0;
  int repaired = 0;
  int cloudWins = 0;
  int droppedLocalStates = 0;
  final domainResets = <String>[];
  int heldTombstones = 0;
  final failures = <KindScopeFailure>[];

  void absorb(_CaptureAccumulator other) {
    newVersions.addAll(other.newVersions);
    tombstones.addAll(other.tombstones);
    keptBoth += other.keptBoth;
    adopted += other.adopted;
    repaired += other.repaired;
    cloudWins += other.cloudWins;
    droppedLocalStates += other.droppedLocalStates;
    domainResets.addAll(other.domainResets);
    heldTombstones += other.heldTombstones;
    failures.addAll(other.failures);
  }

  CaptureResult build(HeldReview? heldReview) => CaptureResult(
    newVersions: newVersions.snapshot(),
    tombstonesCreated: tombstones.snapshot(),
    keptBoth: keptBoth,
    adopted: adopted,
    repaired: repaired,
    cloudWins: cloudWins,
    droppedLocalStates: droppedLocalStates,
    domainResets: List.unmodifiable(domainResets),
    heldTombstones: heldTombstones,
    heldReview: heldReview,
    failures: List.unmodifiable(failures),
  );
}

class _ApplyAccumulator {
  final appliedLive = ScopeKindCounter();
  final removed = ScopeKindCounter();
  int skippedChangedLocally = 0;
  final skipped = <SkipReason, int>{};
  int unsupported = 0;
  final failures = <KindScopeFailure>[];

  ApplyResult build() => ApplyResult(
    appliedLive: appliedLive.snapshot(),
    removed: removed.snapshot(),
    skippedChangedLocally: skippedChangedLocally,
    skipped: Map.unmodifiable(skipped),
    unsupported: unsupported,
    failures: List.unmodifiable(failures),
  );
}
