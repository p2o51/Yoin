package com.gpo.yoin.data.sync.engine

import androidx.room.withTransaction
import com.gpo.yoin.data.sync.ActiveBinding
import com.gpo.yoin.data.sync.ApplyOutcome
import com.gpo.yoin.data.sync.CanonicalJson
import com.gpo.yoin.data.sync.ConflictPolicy
import com.gpo.yoin.data.sync.DomainRow
import com.gpo.yoin.data.sync.FileClass
import com.gpo.yoin.data.sync.RecordVersion
import com.gpo.yoin.data.sync.RemoteFile
import com.gpo.yoin.data.sync.SkipReason
import com.gpo.yoin.data.sync.SyncAdapter
import com.gpo.yoin.data.sync.SyncDao
import com.gpo.yoin.data.sync.SyncDatabase
import com.gpo.yoin.data.sync.SyncEnvelope
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.data.sync.SyncLocalStateEntity
import com.gpo.yoin.data.sync.SyncMetaEntity
import com.gpo.yoin.data.sync.SyncMetaKeys
import com.gpo.yoin.data.sync.SyncRecordEntity
import com.gpo.yoin.data.sync.SyncRemoteFileEntity
import com.gpo.yoin.data.sync.WireRecord
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The sync engine: pure replica logic over [SyncDatabase] and the domain
 * [SyncAdapter]s (spec §3–§4 steps 3, 5–8). No network, no Android UI.
 *
 * Not thread-safe: the caller serializes every call (CloudSyncManager's Mutex).
 * Replica writes are atomic per remote file (merge) and per scope (capture);
 * domain writes go through the adapters' own transactions, and the
 * expected-hash guard plus crash repair make the two databases converge after
 * an interruption.
 */
class SyncEngine(
    private val db: SyncDatabase,
    adapters: List<SyncAdapter>,
    private val deviceId: String,
    private val deviceName: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao: SyncDao = db.syncDao()
    private val adapters: List<SyncAdapter> = adapters.toList()
    private val adaptersByKind: Map<String, SyncAdapter> = this.adapters.associateBy { it.kind }

    init {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        require(adaptersByKind.size == this.adapters.size) { "Duplicate sync adapter kinds" }
    }

    // ------------------------------------------------------------------ merge (spec §4.3 + §4.6)

    /**
     * Merges every record of a downloaded remote file into the replica and records the file's seen
     * version in the same transaction, so a failed merge is retried on the next cycle.
     *
     * @throws SyncSchemaTooNewException / [MalformedSyncFileException] for envelopes this app cannot read.
     */
    suspend fun mergeRemote(file: RemoteFile, envelope: SyncEnvelope): MergeResult {
        if (envelope.format != SyncFormat.FORMAT) {
            throw MalformedSyncFileException("Not a Yoin sync file (format=${envelope.format})")
        }
        if (envelope.schema > SyncFormat.SCHEMA) {
            throw SyncSchemaTooNewException(envelope.schema, envelope.deviceId, envelope.deviceName)
        }
        require(envelope.deviceId != deviceId) { "This device's own files are never merged as remote" }
        return db.withTransaction {
            val acc = MergeAccumulator()
            val names = DeviceNames(envelope)
            for (wire in envelope.records) mergeRecord(wire, names, acc)
            dao.upsertRemoteFile(
                SyncRemoteFileEntity(
                    fileId = file.id,
                    deviceId = envelope.deviceId,
                    fileKind = envelope.fileClass,
                    shard = envelope.shard,
                    version = file.version,
                    modifiedTime = file.modifiedTime,
                    deviceName = envelope.deviceName,
                    writtenAt = envelope.writtenAt,
                    own = false,
                ),
            )
            acc.build(file.id, envelope)
        }
    }

    private suspend fun mergeRecord(wire: WireRecord, names: DeviceNames, acc: MergeAccumulator) {
        acc.received++
        val structurallyValid = wire.kind.isNotEmpty() && wire.key.isNotEmpty() && wire.device.isNotEmpty() &&
            (wire.deleted || wire.payload != null)
        if (!structurallyValid) {
            acc.malformed++
            return
        }
        val incoming = wire.toEntity()
        val adapter = supportedAdapter(incoming)
        if (adapter == null) acc.opaque++

        val current = dao.getRecord(incoming.kind, incoming.scope, incoming.key)
        if (current == null) {
            dao.upsertRecord(incoming)
            acc.inserted++
            acc.changed.add(incoming.scope, incoming.kind)
            return
        }
        if (incoming.version == current.version) {
            // §3: same version, different payload (or kind version) -> deterministic winner, never an edit.
            val identical = sameContent(incoming, current) && incoming.kindVersion == current.kindVersion
            if (!identical && tieBreakPrefers(incoming, current)) {
                dao.upsertRecord(incoming)
                dao.getLocalState(incoming.kind, incoming.scope, incoming.key)?.let { state ->
                    // Re-apply the winning payload (expected-hash guarded); the version did not move.
                    dao.upsertLocalState(state.copy(appliedTs = null, appliedDevice = null).clearPending())
                }
                acc.replaced++
                acc.changed.add(incoming.scope, incoming.kind)
            }
            return
        }

        val state = dao.getLocalState(incoming.kind, incoming.scope, incoming.key)
        // Not concurrent with our pending change: an older version of our own relayed by a peer (our versions
        // form one chain), or a version derived from exactly our current one (the peer already saw it, e.g.
        // after an upload whose markUploaded was lost).
        val historyOrSuccessor = incoming.device == deviceId || incoming.prev == current.version
        if (adapter != null && state != null && state.pending && current.device == deviceId && !historyOrSuccessor) {
            val base = state.pendingPrev
            if ((base == null || incoming.version > base) && !sameContent(incoming, current)) {
                resolveConcurrent(adapter, current, incoming, state, names, acc)
                return
            }
        }
        if (incoming.version > current.version) {
            dao.upsertRecord(incoming)
            if (state != null && state.pending) {
                dao.upsertLocalState(state.clearPending())
                if (!historyOrSuccessor && !sameContent(incoming, current)) acc.localChangesDropped++
            }
            acc.replaced++
            acc.changed.add(incoming.scope, incoming.kind)
        }
    }

    /** [local] is this device's unpublished change, [incoming] a remote version it was not based on. */
    private suspend fun resolveConcurrent(
        adapter: SyncAdapter,
        local: SyncRecordEntity,
        incoming: SyncRecordEntity,
        state: SyncLocalStateEntity,
        names: DeviceNames,
        acc: MergeAccumulator,
    ) {
        acc.conflicts++
        val lwwWinner = if (local.version > incoming.version) local else incoming

        if (adapter.conflictPolicy == ConflictPolicy.KEEP_BOTH_TEXT && !local.deleted && !incoming.deleted) {
            val loser = if (lwwWinner === local) incoming else local
            val merged = mergeTexts(
                adapter = adapter,
                winner = parsePayload(lwwWinner.payload),
                loser = parsePayload(loser.payload),
                loserName = names.nameOf(loser.device),
                loserTs = loser.ts,
            )
            if (merged != null) {
                val version = RecordVersion(maxOf(local.ts, incoming.ts) + 1, deviceId)
                dao.upsertRecord(localRecord(adapter, local.scope, local.key, version, lwwWinner.version, merged))
                // applied stays at the old version, so apply writes the merged text into the domain.
                dao.upsertLocalState(state.withPending(lwwWinner.version))
                acc.keptBoth++
                acc.changed.add(local.scope, local.kind)
                return
            }
        }

        val liveVersusTombstone = local.deleted != incoming.deleted
        val winner = when {
            adapter.conflictPolicy == ConflictPolicy.LWW_LIVE_WINS && liveVersusTombstone ->
                if (local.deleted) incoming else local
            else -> lwwWinner
        }
        if (winner === lwwWinner) {
            if (winner === incoming) {
                dao.upsertRecord(incoming)
                dao.upsertLocalState(state.clearPending())
                acc.localChangesDropped++
                acc.replaced++
                acc.changed.add(incoming.scope, incoming.kind)
            }
            // else: our pending change is newer and simply stays.
            return
        }

        // Live beat a newer tombstone: re-issue the live payload above both versions, so every device
        // (including one that already got the tombstone) converges on it.
        val newer = lwwWinner
        val version = RecordVersion(maxOf(local.ts, incoming.ts) + 1, deviceId)
        val payload = parsePayload(winner.payload)?.let { adapter.decoratePayload(it, deviceName()) }
        val reissued = if (payload != null) {
            localRecord(adapter, local.scope, local.key, version, newer.version, payload)
        } else {
            winner.copy(ts = version.ts, device = version.device, prevTs = newer.ts, prevDevice = newer.device)
        }
        dao.upsertRecord(reissued)
        // Domain already holds our live row -> nothing to apply; a remote live winner still has to be applied.
        val applied = if (winner === local && state.applied == local.version) version else state.applied
        dao.upsertLocalState(
            state.withPending(newer.version).copy(appliedTs = applied?.ts, appliedDevice = applied?.device),
        )
        if (winner === incoming) acc.localChangesDropped++
        acc.replaced++
        acc.changed.add(local.scope, local.kind)
    }

    // ------------------------------------------------------------------ capture (spec §4.5)

    /**
     * Diffs every domain row against the replica for each active binding (and the global scope),
     * writing new versions, join reconciliations and valve-guarded tombstones.
     */
    suspend fun capture(bindings: List<ActiveBinding>): CaptureResult {
        val acc = CaptureAccumulator()
        val now = clock()
        val name = deviceName()
        val names = DeviceNames(null)
        for ((scope, targets) in targets(bindings).groupBy { it.scope }) {
            val reads = LinkedHashMap<Target, List<DomainRow>>()
            for (target in targets) {
                try {
                    reads[target] = target.adapter.readAll(target.localProfileId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // An unread table must never look like deletions: skip this (kind, scope) only.
                    acc.failures += KindScopeFailure(target.adapter.kind, scope, e)
                }
            }
            if (reads.isEmpty()) continue
            val boundProfileId = targets.first().boundProfileId
            val scopeAcc = CaptureAccumulator()
            try {
                db.withTransaction { captureScope(scope, boundProfileId, reads, now, name, names, scopeAcc) }
                acc.absorb(scopeAcc)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An adapter hook that throws (decoratePayload, mergeConcurrent) rolled this scope back; the
                // other scopes keep syncing instead of every later scope failing on every cycle.
                for (target in reads.keys) acc.failures += KindScopeFailure(target.adapter.kind, scope, e)
            }
        }
        dropOrphanedHeldReview()
        return acc.build(loadHeldReview())
    }

    /**
     * A held review whose keys no longer have local state (the profile was removed and unbound) can never
     * be re-evaluated by the valve; drop it so no "notes disappeared" prompt lingers for a gone account.
     * Paused or credential-less bindings keep their local state, so their reviews stay.
     */
    private suspend fun dropOrphanedHeldReview() {
        val held = loadHeldReview() ?: return
        val tracked = dao.localStatesFor(held.kind, held.scope)
            .filter { it.localHash != null }
            .mapTo(HashSet()) { it.key }
        if (held.keys.none { it in tracked }) dao.deleteMeta(SyncMetaKeys.HELD_REVIEW)
    }

    private suspend fun captureScope(
        scope: String,
        boundProfileId: String,
        reads: Map<Target, List<DomainRow>>,
        now: Long,
        name: String,
        names: DeviceNames,
        acc: CaptureAccumulator,
    ) {
        // Local state recorded under another local profile describes an old binding: treat as absent.
        val states = ArrayList<SyncLocalStateEntity>()
        for (state in dao.localStatesInScope(scope)) {
            if (state.boundProfileId == boundProfileId) {
                states += state
            } else {
                dao.deleteLocalState(state.kind, state.scope, state.key)
            }
        }
        var statesByKind: Map<String, Map<String, SyncLocalStateEntity>> =
            states.groupBy { it.kind }.mapValues { (_, list) -> list.associateBy { it.key } }
        val recordsByKind = reads.keys.associate { target ->
            target.adapter.kind to dao.recordsFor(target.adapter.kind, scope).associateBy { it.key }
        }

        // Spec §4.5(d) names the per-account kinds only: global kinds never tombstone, so a global "reset"
        // would just drop every global local state (thousands of translations) and pending flags for nothing.
        val domainReset = reads.any { (target, rows) ->
            val adapter = target.adapter
            adapter.perAccount && !adapter.propagatesDeletes &&
                missingRows(
                    adapter = adapter,
                    rows = rows,
                    states = statesByKind[adapter.kind].orEmpty(),
                    records = recordsByKind[adapter.kind].orEmpty(),
                ) >= DOMAIN_RESET_MIN_MISSING
        }
        if (domainReset) {
            // Rows of kinds that can't be deleted vanished: the domain DB was recreated. Rebind, never tombstone.
            dao.deleteLocalStatesInScope(scope)
            statesByKind = emptyMap()
            acc.domainResets += scope
        }

        for ((target, rows) in reads) {
            val kind = target.adapter.kind
            captureKind(
                target = target,
                rows = rows,
                states = statesByKind[kind].orEmpty(),
                records = recordsByKind[kind].orEmpty(),
                now = now,
                name = name,
                names = names,
                acc = acc,
            )
        }
    }

    private fun missingRows(
        adapter: SyncAdapter,
        rows: List<DomainRow>,
        states: Map<String, SyncLocalStateEntity>,
        records: Map<String, SyncRecordEntity>,
    ): Int {
        val present = rows.mapTo(HashSet()) { it.key }
        return states.values.count { state ->
            state.localHash != null && state.key !in present && records[state.key].isSupportedBy(adapter)
        }
    }

    private suspend fun captureKind(
        target: Target,
        rows: List<DomainRow>,
        states: Map<String, SyncLocalStateEntity>,
        records: Map<String, SyncRecordEntity>,
        now: Long,
        name: String,
        names: DeviceNames,
        acc: CaptureAccumulator,
    ) {
        val adapter = target.adapter
        val kind = adapter.kind
        val scope = target.scope
        val boundProfileId = target.boundProfileId
        val present = HashSet<String>()

        for (row in rows) {
            if (!present.add(row.key)) continue
            val record = records[row.key]
            if (!record.isSupportedBy(adapter)) continue // newer kind version: carried, never captured
            val state = states[row.key]
            val hash = CanonicalJson.hash(row.projection)

            if (state != null) {
                // (a) tracked row
                if (hash == state.localHash) continue
                if (record != null && !record.deleted && projectedHash(adapter, record) == hash) {
                    // Crash repair: the domain already holds the replica value.
                    dao.upsertLocalState(
                        state.copy(localHash = hash, appliedTs = record.ts, appliedDevice = record.device),
                    )
                    acc.repaired++
                    continue
                }
                val base = if (state.pending) state.pendingPrev else record?.version
                val version = RecordVersion(maxOf(now, (record?.ts ?: Long.MIN_VALUE) + 1), deviceId)
                val edited = mergedPayload(adapter, record, row.projection)
                // The domain never showed the replica's version (merged this cycle before capture, or an apply
                // skipped as CHANGED_LOCALLY): the edit is concurrent with it, so text kinds keep both.
                val unseen = record?.takeIf {
                    adapter.conflictPolicy == ConflictPolicy.KEEP_BOTH_TEXT &&
                        !it.deleted &&
                        it.version != state.applied
                }
                val keptBoth = unseen?.let {
                    mergeTexts(
                        adapter = adapter,
                        winner = edited,
                        loser = parsePayload(it.payload),
                        loserName = names.nameOf(it.device),
                        loserTs = it.ts,
                    )
                }
                if (unseen != null && keptBoth != null) {
                    dao.upsertRecord(localRecord(adapter, scope, row.key, version, unseen.version, keptBoth))
                    // applied = null: the next apply writes the merged text over the edited row (hash-guarded).
                    dao.upsertLocalState(
                        localState(kind, scope, row.key, boundProfileId, hash, null, pending = true, base),
                    )
                    acc.keptBoth++
                    acc.newVersions.add(scope, kind)
                    continue
                }
                val payload = adapter.decoratePayload(edited, name)
                dao.upsertRecord(localRecord(adapter, scope, row.key, version, base, payload))
                dao.upsertLocalState(
                    localState(kind, scope, row.key, boundProfileId, hash, version, pending = true, base),
                )
                acc.newVersions.add(scope, kind)
                continue
            }

            if (record != null) {
                reconcileJoin(adapter, target, row, hash, record, name, names, acc)
                continue
            }

            // (c) brand new row
            val version = RecordVersion(row.rowTs ?: now, deviceId)
            val payload = adapter.decoratePayload(row.projection, name)
            dao.upsertRecord(localRecord(adapter, scope, row.key, version, null, payload))
            dao.upsertLocalState(localState(kind, scope, row.key, boundProfileId, hash, version, pending = true, null))
            acc.newVersions.add(scope, kind)
        }

        // (d) tracked rows that are gone
        val candidates = ArrayList<Pair<SyncLocalStateEntity, SyncRecordEntity>>()
        for (state in states.values) {
            if (state.key in present || state.localHash == null) continue
            val record = records[state.key]
            if (!record.isSupportedBy(adapter)) continue
            if (!adapter.propagatesDeletes) {
                dao.deleteLocalState(kind, scope, state.key) // re-applied from the replica
                acc.droppedLocalStates++
                continue
            }
            when {
                record == null -> dao.deleteLocalState(kind, scope, state.key)
                record.deleted -> dao.upsertLocalState(
                    state.copy(localHash = null, appliedTs = record.ts, appliedDevice = record.device),
                )
                adapter.conflictPolicy == ConflictPolicy.LWW_LIVE_WINS && record.version != state.applied -> {
                    // The delete hit an older copy; the replica holds a live version this device never applied
                    // (e.g. a peer's edit merged this cycle). Concurrent live beats tombstone: restore it.
                    dao.deleteLocalState(kind, scope, state.key)
                    acc.droppedLocalStates++
                }
                else -> candidates += state to record
            }
        }
        if (adapter.propagatesDeletes) applyValve(adapter, scope, candidates, now, acc)
    }

    /**
     * (b) A row exists but this binding never saw it: adopt, let the cloud win, or win by the row's own
     * timestamp. Never a tombstone.
     */
    private suspend fun reconcileJoin(
        adapter: SyncAdapter,
        target: Target,
        row: DomainRow,
        hash: String,
        record: SyncRecordEntity,
        name: String,
        names: DeviceNames,
        acc: CaptureAccumulator,
    ) {
        val kind = adapter.kind
        val scope = target.scope
        if (!record.deleted && projectedHash(adapter, record) == hash) {
            dao.upsertLocalState(
                localState(kind, scope, row.key, target.boundProfileId, hash, record.version, pending = false, null),
            )
            acc.adopted++
            return
        }

        val candidateTs = row.rowTs ?: 0L
        val canWin = when {
            record.deleted && !adapter.propagatesDeletes -> true // a tombstone means nothing for this kind
            else -> candidateTs > record.ts
        }

        if (adapter.conflictPolicy == ConflictPolicy.KEEP_BOTH_TEXT && !record.deleted) {
            val cloud = parsePayload(record.payload)
            val local = mergedPayload(adapter, record, row.projection)
            val localWinsLww = canWin
            val merged = mergeTexts(
                adapter = adapter,
                winner = if (localWinsLww) local else cloud,
                loser = if (localWinsLww) cloud else local,
                loserName = if (localWinsLww) names.nameOf(record.device) else name,
                loserTs = if (localWinsLww) record.ts else candidateTs,
            )
            if (merged != null) {
                val version = RecordVersion(maxOf(candidateTs, record.ts) + 1, deviceId)
                dao.upsertRecord(localRecord(adapter, scope, row.key, version, record.version, merged))
                // applied = null: the next apply writes the merged text over the local row (guarded by its hash).
                dao.upsertLocalState(
                    localState(kind, scope, row.key, target.boundProfileId, hash, null, pending = true, record.version),
                )
                acc.keptBoth++
                acc.newVersions.add(scope, kind)
                return
            }
        }

        if (canWin) {
            val meaninglessTombstone = record.deleted && !adapter.propagatesDeletes
            val ts = if (meaninglessTombstone) maxOf(candidateTs, record.ts + 1) else candidateTs
            val version = RecordVersion(ts, deviceId)
            val payload = adapter.decoratePayload(mergedPayload(adapter, record, row.projection), name)
            dao.upsertRecord(localRecord(adapter, scope, row.key, version, record.version, payload))
            dao.upsertLocalState(
                localState(kind, scope, row.key, target.boundProfileId, hash, version, pending = true, record.version),
            )
            acc.newVersions.add(scope, kind)
        } else {
            // The cloud wins (value or tombstone): apply overwrites/deletes the row, guarded by its current hash.
            dao.upsertLocalState(
                localState(kind, scope, row.key, target.boundProfileId, hash, null, pending = false, null),
            )
            acc.cloudWins++
        }
    }

    private suspend fun applyValve(
        adapter: SyncAdapter,
        scope: String,
        candidates: List<Pair<SyncLocalStateEntity, SyncRecordEntity>>,
        now: Long,
        acc: CaptureAccumulator,
    ) {
        val kind = adapter.kind
        val held = loadHeldReview()
        val heldKeys = if (held != null && held.kind == kind && held.scope == scope) held.keys.toSet() else null
        // Keys the user is being asked about stay held until they answer: adding notes (which lowers the
        // ratio) or a later small delete must never turn the held mass delete into tombstones silently.
        val (stillHeld, fresh) = candidates.partition { heldKeys != null && it.first.key in heldKeys }
        val tripped = fresh.isNotEmpty() && valveTrips(candidates.size, dao.liveCount(kind, scope))
        val holding = if (tripped) candidates else stillHeld
        if (!tripped) {
            for ((state, record) in fresh) {
                writeTombstone(adapter, state, record, now)
                acc.tombstones.add(scope, kind)
            }
        }
        acc.heldTombstones += holding.size
        when {
            holding.isNotEmpty() && (held == null || heldKeys != null) ->
                saveHeldReview(HeldReview(kind, scope, holding.map { it.first.key }.sorted()))
            holding.isEmpty() && heldKeys != null -> dao.deleteMeta(SyncMetaKeys.HELD_REVIEW)
        }
    }

    private suspend fun writeTombstone(
        adapter: SyncAdapter,
        state: SyncLocalStateEntity,
        record: SyncRecordEntity,
        now: Long,
    ) {
        val base = if (state.pending) state.pendingPrev else record.version
        val version = RecordVersion(maxOf(now, record.ts + 1), deviceId)
        dao.upsertRecord(
            SyncRecordEntity(
                kind = record.kind,
                scope = record.scope,
                key = record.key,
                kindVersion = adapter.kindVersion,
                ts = version.ts,
                device = version.device,
                prevTs = base?.ts,
                prevDevice = base?.device,
                deleted = true,
                payload = null,
                payloadHash = null,
            ),
        )
        dao.upsertLocalState(
            state.copy(localHash = null, appliedTs = version.ts, appliedDevice = version.device).withPending(base),
        )
    }

    // ------------------------------------------------------------------ apply (spec §4.7)

    /** Writes replica records the domain DB does not hold yet, for active bindings and global kinds. */
    suspend fun apply(bindings: List<ActiveBinding>): ApplyResult {
        val acc = ApplyAccumulator()
        for (target in targets(bindings)) {
            val adapter = target.adapter
            val kind = adapter.kind
            val records = dao.recordsFor(kind, target.scope)
            if (records.isEmpty()) continue
            val states = HashMap<String, SyncLocalStateEntity>()
            for (state in dao.localStatesFor(kind, target.scope)) {
                if (state.boundProfileId == target.boundProfileId) {
                    states[state.key] = state
                } else {
                    dao.deleteLocalState(state.kind, state.scope, state.key)
                }
            }
            var failed = false
            for (record in records.sortedBy { it.key }) {
                if (!record.isSupportedBy(adapter)) {
                    acc.unsupported++
                    continue
                }
                val state = states[record.key]
                if (state != null && state.applied == record.version) continue
                try {
                    applyRecord(adapter, target, record, state, acc)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Isolate the record: one payload the adapter chokes on must not block every record
                    // sorted after it, cycle after cycle. Its local state is untouched, so it is retried.
                    if (!failed) acc.failures += KindScopeFailure(kind, target.scope, e)
                    failed = true
                }
            }
        }
        return acc.build()
    }

    private suspend fun applyRecord(
        adapter: SyncAdapter,
        target: Target,
        record: SyncRecordEntity,
        state: SyncLocalStateEntity?,
        acc: ApplyAccumulator,
    ) {
        val expected = state?.localHash
        val outcome = when {
            record.deleted && !adapter.propagatesDeletes -> ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, expected)
            record.deleted -> adapter.delete(target.localProfileId, record.key, expected)
            else -> {
                val payload = parsePayload(record.payload)
                if (payload == null) {
                    ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, expected)
                } else {
                    adapter.apply(target.localProfileId, record.key, payload, record.ts, expected)
                }
            }
        }
        when (outcome) {
            is ApplyOutcome.Applied -> {
                dao.upsertLocalState(appliedState(adapter, target, record, state, outcome.localHash))
                if (!record.deleted) {
                    acc.appliedLive.add(target.scope, adapter.kind)
                } else if (expected != null) {
                    acc.removed.add(target.scope, adapter.kind)
                }
            }
            is ApplyOutcome.Skipped -> {
                if (outcome.reason == SkipReason.CHANGED_LOCALLY) {
                    acc.skippedChangedLocally++ // the next capture sees the edit
                } else {
                    // OWNED_ELSEWHERE: this profile holds no such row. Recording a hash here (e.g. the owner's
                    // row) would make the next capture see "row missing" and tombstone it on every device.
                    val localHash = if (outcome.reason == SkipReason.OWNED_ELSEWHERE) null else outcome.localHash
                    dao.upsertLocalState(appliedState(adapter, target, record, state, localHash))
                    acc.skipped[outcome.reason] = (acc.skipped[outcome.reason] ?: 0) + 1
                }
            }
        }
    }

    private fun appliedState(
        adapter: SyncAdapter,
        target: Target,
        record: SyncRecordEntity,
        state: SyncLocalStateEntity?,
        localHash: String?,
    ) = SyncLocalStateEntity(
        kind = adapter.kind,
        scope = target.scope,
        key = record.key,
        boundProfileId = target.boundProfileId,
        localHash = localHash,
        appliedTs = record.ts,
        appliedDevice = record.device,
        pending = state?.pending ?: false,
        pendingPrevTs = state?.pendingPrevTs,
        pendingPrevDevice = state?.pendingPrevDevice,
    )

    // ------------------------------------------------------------------ publish (spec §4.8)

    /**
     * Serializes the whole replica (all scopes, tombstones and opaque records) into one state file and
     * [SyncFormat.ARTIFACT_SHARDS] artifact shards, records sorted by (kind, scope, key).
     */
    suspend fun outgoingFiles(epoch: Long, writtenAt: Long, appVersionCode: Int): List<OutgoingFile> {
        val sorted = dao.allRecords().sortedWith(compareBy<SyncRecordEntity>({ it.kind }, { it.scope }, { it.key }))
        val state = ArrayList<SyncRecordEntity>()
        val shards = List(SyncFormat.ARTIFACT_SHARDS) { ArrayList<SyncRecordEntity>() }
        for (record in sorted) {
            when (fileClassOf(record.kind)) {
                FileClass.STATE -> state += record
                FileClass.ARTIFACTS -> shards[SyncFormat.artifactShard(record.key)] += record
            }
        }
        val name = deviceName()
        return buildList {
            add(outgoingFile(FileClass.STATE, 0, state, epoch, writtenAt, appVersionCode, name))
            shards.forEachIndexed { shard, records ->
                add(outgoingFile(FileClass.ARTIFACTS, shard, records, epoch, writtenAt, appVersionCode, name))
            }
        }
    }

    private fun outgoingFile(
        fileClass: FileClass,
        shard: Int,
        records: List<SyncRecordEntity>,
        epoch: Long,
        writtenAt: Long,
        appVersionCode: Int,
        name: String,
    ): OutgoingFile {
        val wires = records.map { it.toWire() }
        val contentHash = CanonicalJson.hash(
            JsonArray(wires.map { SyncJson.json.encodeToJsonElement(WireRecord.serializer(), it) }),
        )
        val envelope = SyncEnvelope(
            epoch = epoch,
            deviceId = deviceId,
            deviceName = name,
            fileClass = fileClass.wireName,
            shard = shard,
            writtenAt = writtenAt,
            appVersionCode = appVersionCode,
            records = wires,
        )
        return OutgoingFile(
            fileKind = fileClass.wireName,
            shard = shard,
            bytes = SnapshotCodec.encode(envelope),
            contentHash = contentHash,
            includedVersions = records.associate { RecordKey(it.kind, it.scope, it.key) to it.version },
        )
    }

    /**
     * After an HTTP 200 for [file]: clears pending only where the replica still holds the exact version
     * that was serialized (a change made after serialization stays pending), and remembers the hash.
     */
    suspend fun markUploaded(file: OutgoingFile) {
        db.withTransaction {
            for (state in dao.pendingLocalStates()) {
                val uploaded = file.includedVersions[RecordKey(state.kind, state.scope, state.key)] ?: continue
                val current = dao.getRecord(state.kind, state.scope, state.key) ?: continue
                if (current.version == uploaded) dao.upsertLocalState(state.clearPending())
            }
            dao.putMeta(SyncMetaEntity(uploadedHashKey(file.fileKind, file.shard), file.contentHash))
        }
    }

    suspend fun lastUploadedHash(fileKind: String, shard: Int): String? = dao.meta(uploadedHashKey(fileKind, shard))

    /**
     * Additive helper: forget every last-uploaded hash so the next publish re-uploads all files
     * (e.g. "Upload again" after the Drive copy vanished).
     */
    suspend fun clearUploadedHashes() {
        for (entry in dao.metaWithPrefix(SyncMetaKeys.LAST_UPLOADED_HASH_PREFIX)) {
            if (entry.key.startsWith(SyncMetaKeys.LAST_UPLOADED_HASH_PREFIX)) dao.deleteMeta(entry.key)
        }
    }

    // ------------------------------------------------------------------ held review (valve)

    suspend fun heldReview(): HeldReview? = loadHeldReview()

    /**
     * [restore] = true: forget the held rows' local state so the next apply restores them here.
     * false: write the held tombstones now (delete on all devices). Returns the number of keys handled.
     */
    suspend fun resolveHeldReview(restore: Boolean): Int = db.withTransaction {
        val held = loadHeldReview() ?: return@withTransaction 0
        val adapter = adaptersByKind[held.kind]
        var handled = 0
        if (adapter != null) {
            val now = clock()
            for (key in held.keys) {
                val state = dao.getLocalState(held.kind, held.scope, key) ?: continue
                if (restore) {
                    dao.deleteLocalState(held.kind, held.scope, key)
                    handled++
                } else {
                    val record = dao.getRecord(held.kind, held.scope, key) ?: continue
                    if (record.deleted || state.localHash == null || !record.isSupportedBy(adapter)) continue
                    writeTombstone(adapter, state, record, now)
                    handled++
                }
            }
        }
        dao.deleteMeta(SyncMetaKeys.HELD_REVIEW)
        handled
    }

    // ------------------------------------------------------------------ maintenance

    /**
     * Deletes [localProfileId]'s synced domain rows of [scope] (no tombstones: other devices keep them)
     * and forgets the scope's local state. Rows edited since they were last synced are kept.
     * Returns the number of rows deleted.
     */
    suspend fun purgeScopeLocally(scope: String, localProfileId: String): Int {
        require(scope != SyncFormat.GLOBAL_SCOPE) { "Only per-account scopes can be purged" }
        var deleted = 0
        for (adapter in adapters.filter { it.perAccount }) {
            for (state in dao.localStatesFor(adapter.kind, scope)) {
                val expected = state.localHash ?: continue
                if (state.boundProfileId != localProfileId) continue
                val outcome = adapter.delete(localProfileId, state.key, expected)
                if (outcome is ApplyOutcome.Applied) {
                    // Forget each row as soon as it is gone: if the purge is interrupted (a throw, a kill) and
                    // the binding resumes, no local state may point at a purged row, or capture tombstones it.
                    dao.deleteLocalState(adapter.kind, scope, state.key)
                    deleted++
                }
            }
        }
        dao.deleteLocalStatesInScope(scope)
        return deleted
    }

    /**
     * Drops records, local state and remote-file bookkeeping, plus the replica-derived held review and
     * last-uploaded hashes. Bindings and the rest of sync_meta stay.
     */
    suspend fun clearReplica() {
        db.withTransaction {
            dao.clearRecords()
            dao.clearLocalStates()
            dao.clearRemoteFiles()
            dao.deleteMeta(SyncMetaKeys.HELD_REVIEW)
            clearUploadedHashes()
        }
    }

    suspend fun hasPendingChanges(): Boolean = dao.pendingCount() > 0

    // ------------------------------------------------------------------ helpers

    private data class Target(val adapter: SyncAdapter, val scope: String, val localProfileId: String?) {
        val boundProfileId: String get() = localProfileId ?: ""
    }

    private fun targets(bindings: List<ActiveBinding>): List<Target> {
        val usable = bindings
            .filter { it.syncProfileId != SyncFormat.GLOBAL_SCOPE && it.localProfileId.isNotEmpty() }
            .distinctBy { it.syncProfileId }
            .distinctBy { it.localProfileId }
        return adapters.flatMap { adapter ->
            if (adapter.perAccount) {
                usable.map { Target(adapter, it.syncProfileId, it.localProfileId) }
            } else {
                listOf(Target(adapter, SyncFormat.GLOBAL_SCOPE, null))
            }
        }
    }

    private fun supportedAdapter(record: SyncRecordEntity): SyncAdapter? =
        adaptersByKind[record.kind]?.takeIf { record.kindVersion <= it.kindVersion }

    /** null (no record) counts as supported. */
    private fun SyncRecordEntity?.isSupportedBy(adapter: SyncAdapter): Boolean =
        this == null || kindVersion <= adapter.kindVersion

    private fun fileClassOf(kind: String): FileClass =
        adaptersByKind[kind]?.fileClass ?: if (kind in KNOWN_ARTIFACT_KINDS) FileClass.ARTIFACTS else FileClass.STATE

    private fun safeProject(adapter: SyncAdapter, payload: JsonObject): JsonObject? = try {
        adapter.project(payload)
    } catch (e: RuntimeException) {
        null
    }

    private fun projectedHash(adapter: SyncAdapter, record: SyncRecordEntity): String? =
        parsePayload(record.payload)?.let { safeProject(adapter, it) }?.let(CanonicalJson::hash)

    /**
     * The replica payload with the domain projection written over it: unknown keys survive, and a
     * projected key the domain no longer has is removed.
     */
    private fun mergedPayload(adapter: SyncAdapter, base: SyncRecordEntity?, projection: JsonObject): JsonObject {
        val existing = base?.takeUnless { it.deleted }?.let { parsePayload(it.payload) } ?: return projection
        val merged = LinkedHashMap<String, JsonElement>(existing)
        safeProject(adapter, existing)?.keys?.forEach { key -> if (key !in projection) merged.remove(key) }
        merged.putAll(projection)
        return JsonObject(merged)
    }

    /** KEEP_BOTH_TEXT: the adapter's merged payload (decorated), or null when nothing needs merging. */
    private fun mergeTexts(
        adapter: SyncAdapter,
        winner: JsonObject?,
        loser: JsonObject?,
        loserName: String,
        loserTs: Long,
    ): JsonObject? {
        if (winner == null || loser == null) return null
        val winnerProjection = safeProject(adapter, winner)
        if (winnerProjection != null && winnerProjection == safeProject(adapter, loser)) return null
        val merged = adapter.mergeConcurrent(winner, loser, loserLabel(loserName, loserTs)) ?: return null
        return adapter.decoratePayload(merged, deviceName())
    }

    private fun localRecord(
        adapter: SyncAdapter,
        scope: String,
        key: String,
        version: RecordVersion,
        prev: RecordVersion?,
        payload: JsonObject,
    ) = SyncRecordEntity(
        kind = adapter.kind,
        scope = scope,
        key = key,
        kindVersion = adapter.kindVersion,
        ts = version.ts,
        device = version.device,
        prevTs = prev?.ts,
        prevDevice = prev?.device,
        deleted = false,
        payload = payloadText(payload),
        payloadHash = CanonicalJson.hash(payload),
    )

    private fun localState(
        kind: String,
        scope: String,
        key: String,
        boundProfileId: String,
        localHash: String?,
        applied: RecordVersion?,
        pending: Boolean,
        pendingPrev: RecordVersion?,
    ) = SyncLocalStateEntity(
        kind = kind,
        scope = scope,
        key = key,
        boundProfileId = boundProfileId,
        localHash = localHash,
        appliedTs = applied?.ts,
        appliedDevice = applied?.device,
        pending = pending,
        pendingPrevTs = if (pending) pendingPrev?.ts else null,
        pendingPrevDevice = if (pending) pendingPrev?.device else null,
    )

    private suspend fun loadHeldReview(): HeldReview? {
        val text = dao.meta(SyncMetaKeys.HELD_REVIEW) ?: return null
        return try {
            SyncJson.decodeFromString(HeldReview.serializer(), text)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private suspend fun saveHeldReview(review: HeldReview) {
        dao.putMeta(SyncMetaEntity(SyncMetaKeys.HELD_REVIEW, SyncJson.encodeToString(HeldReview.serializer(), review)))
    }

    /** Resolves device names for conflict labels: this device, the file being merged, then known remote files. */
    private inner class DeviceNames(private val envelope: SyncEnvelope?) {
        private var known: Map<String, String>? = null

        suspend fun nameOf(device: String): String {
            if (device == deviceId) return deviceName().ifBlank { UNKNOWN_DEVICE_NAME }
            if (envelope != null && device == envelope.deviceId && envelope.deviceName.isNotBlank()) {
                return envelope.deviceName
            }
            val names = known ?: dao.remoteFiles()
                .mapNotNull { file -> file.deviceName?.takeIf { it.isNotBlank() }?.let { file.deviceId to it } }
                .toMap()
                .also { known = it }
            return names[device] ?: UNKNOWN_DEVICE_NAME
        }
    }

    private class MergeAccumulator {
        var received = 0
        var inserted = 0
        var replaced = 0
        var conflicts = 0
        var keptBoth = 0
        var localChangesDropped = 0
        var opaque = 0
        var malformed = 0
        val changed = ScopeKindCounter()

        fun build(fileId: String, envelope: SyncEnvelope) = MergeResult(
            fileId = fileId,
            deviceId = envelope.deviceId,
            deviceName = envelope.deviceName,
            received = received,
            inserted = inserted,
            replaced = replaced,
            conflicts = conflicts,
            keptBoth = keptBoth,
            localChangesDropped = localChangesDropped,
            opaque = opaque,
            malformed = malformed,
            changedByScope = changed.snapshot(),
        )
    }

    private class CaptureAccumulator {
        val newVersions = ScopeKindCounter()
        val tombstones = ScopeKindCounter()
        var keptBoth = 0
        var adopted = 0
        var repaired = 0
        var cloudWins = 0
        var droppedLocalStates = 0
        val domainResets = ArrayList<String>()
        var heldTombstones = 0
        val failures = ArrayList<KindScopeFailure>()

        /** Adds a committed scope's counts. */
        fun absorb(other: CaptureAccumulator) {
            newVersions.addAll(other.newVersions)
            tombstones.addAll(other.tombstones)
            keptBoth += other.keptBoth
            adopted += other.adopted
            repaired += other.repaired
            cloudWins += other.cloudWins
            droppedLocalStates += other.droppedLocalStates
            domainResets += other.domainResets
            heldTombstones += other.heldTombstones
            failures += other.failures
        }

        fun build(heldReview: HeldReview?) = CaptureResult(
            newVersions = newVersions.snapshot(),
            tombstonesCreated = tombstones.snapshot(),
            keptBoth = keptBoth,
            adopted = adopted,
            repaired = repaired,
            cloudWins = cloudWins,
            droppedLocalStates = droppedLocalStates,
            domainResets = domainResets.toList(),
            heldTombstones = heldTombstones,
            heldReview = heldReview,
            failures = failures.toList(),
        )
    }

    private class ApplyAccumulator {
        val appliedLive = ScopeKindCounter()
        val removed = ScopeKindCounter()
        var skippedChangedLocally = 0
        val skipped = LinkedHashMap<SkipReason, Int>()
        var unsupported = 0
        val failures = ArrayList<KindScopeFailure>()

        fun build() = ApplyResult(
            appliedLive = appliedLive.snapshot(),
            removed = removed.snapshot(),
            skippedChangedLocally = skippedChangedLocally,
            skipped = skipped.toMap(),
            unsupported = unsupported,
            failures = failures.toList(),
        )
    }

    companion object {
        /** Missing rows of one never-deleted kind in one scope that mean "the domain DB was recreated". */
        const val DOMAIN_RESET_MIN_MISSING = 2

        /** Kinds published in artifact shards even when this build has no adapter for them. */
        val KNOWN_ARTIFACT_KINDS: Set<String> = setOf(SyncKinds.LYRICS_TRANSLATION)

        private const val UNKNOWN_DEVICE_NAME = "Another device"

        private val LABEL_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)

        /** Valve (spec §4.5): >= 3 and >= 50% of live records, or all of >= 2 live records. */
        fun valveTrips(candidates: Int, live: Int): Boolean =
            (candidates >= 3 && candidates * 2 >= live) || (live >= 2 && candidates >= live)

        /**
         * Label handed to [SyncAdapter.mergeConcurrent]: "<loser device name> · yyyy-MM-dd" (UTC date of the
         * losing version), so every device that merges the same pair produces identical text.
         */
        fun loserLabel(deviceName: String, ts: Long): String = try {
            "$deviceName · ${LABEL_DATE.format(Instant.ofEpochMilli(ts))}"
        } catch (e: DateTimeException) {
            deviceName
        } catch (e: ArithmeticException) {
            deviceName
        }

        private fun uploadedHashKey(fileKind: String, shard: Int) =
            "${SyncMetaKeys.LAST_UPLOADED_HASH_PREFIX}$fileKind:$shard"

        private fun sameContent(a: SyncRecordEntity, b: SyncRecordEntity): Boolean =
            a.deleted == b.deleted && a.payloadHash == b.payloadHash

        /** §3 tie rule: higher kindVersion, then the larger canonical payload hash (tombstones lowest). */
        private fun tieBreakPrefers(incoming: SyncRecordEntity, current: SyncRecordEntity): Boolean {
            if (incoming.kindVersion != current.kindVersion) return incoming.kindVersion > current.kindVersion
            return (incoming.payloadHash ?: "") > (current.payloadHash ?: "")
        }

        private fun SyncLocalStateEntity.clearPending() =
            copy(pending = false, pendingPrevTs = null, pendingPrevDevice = null)

        private fun SyncLocalStateEntity.withPending(base: RecordVersion?) =
            copy(pending = true, pendingPrevTs = base?.ts, pendingPrevDevice = base?.device)
    }
}
