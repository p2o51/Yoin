package com.gpo.yoin.shared.portability

import com.gpo.yoin.shared.sync.ApplyOutcome
import com.gpo.yoin.shared.sync.CanonicalJson
import com.gpo.yoin.shared.sync.ConflictPolicy
import com.gpo.yoin.shared.sync.DomainRow
import com.gpo.yoin.shared.sync.RecordVersion
import com.gpo.yoin.shared.sync.SyncAdapter
import com.gpo.yoin.shared.sync.SyncBindingEntity
import com.gpo.yoin.shared.sync.SyncFormat
import com.gpo.yoin.shared.sync.SyncKinds
import com.gpo.yoin.shared.sync.SyncMetaKeys
import com.gpo.yoin.shared.sync.WireRecord
import com.gpo.yoin.shared.sync.adapters.requiredLong
import com.gpo.yoin.shared.sync.adapters.requiredString
import com.gpo.yoin.shared.sync.engine.SyncEngine
import com.gpo.yoin.shared.sync.identity.CloudAccountDescriptor
import com.gpo.yoin.shared.sync.identity.SyncProfile
import com.gpo.yoin.shared.portability.memories.MigrationIds
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonObject

/** Where one account of a backup goes. */
sealed interface ImportTarget {
    /** Merged into this local profile's data. */
    data class IntoProfile(val localProfileId: String) : ImportTarget

    /** Kept in the sync replica as an account only in the cloud (uploaded with the next sync). Needs a replica. */
    data object CloudOnly : ImportTarget

    /** Left in the file (a Memories transfer can still copy from it). */
    data object Skip : ImportTarget
}

/** A local profile an account could be merged into. */
data class ImportProfileOption(val localProfileId: String, val provider: String, val displayName: String)

data class ImportAccountPlan(
    val account: BackupAccount,
    /** Live records per kind (tombstones aren't counted). */
    val counts: Map<String, Int>,
    /** The default: the same account on this device, else [ImportTarget.CloudOnly] when possible, else skip. */
    val target: ImportTarget,
    /** true when [target] is a local profile that is the same account (sync binding or fingerprint). */
    val sameAccount: Boolean,
    /** Profiles of the same service the user may pick instead. */
    val options: List<ImportProfileOption>,
    /** [ImportTarget.CloudOnly] is possible: a sync scope, a replica on this device, and not [wasPurged]. */
    val cloudOnlyPossible: Boolean,
    /**
     * The account was removed from the cloud here (a scope purge marker): a cloud-only import would be refused
     * record by record, so it isn't offered. The UI offers "import into a local account" or "resume sync first".
     */
    val wasPurged: Boolean = false,
)

/** What an import would do; apply it ([BackupMerger.apply]) with the user's choices. */
data class ImportPlan(
    val backup: ParsedBackup,
    val accounts: List<ImportAccountPlan>,
    /** Live global records per kind (settings, translations, About, playlist backups). */
    val globalCounts: Map<String, Int>,
) {
    /** Accounts with no automatic local match: the UI asks (bind to a profile, keep cloud-only, skip). */
    val needsChoice: List<ImportAccountPlan> get() = accounts.filter { !it.sameAccount }
}

data class ImportCounts(
    /** Rows written (new or newer). */
    val written: Int = 0,
    /** Already the same here. */
    val unchanged: Int = 0,
    /** This device holds a newer value (or the sync replica a newer version, e.g. a delete). */
    val keptLocal: Int = 0,
    /** Reviews whose two texts were both kept. */
    val keptBoth: Int = 0,
    /** Tombstones (an import never deletes). */
    val tombstones: Int = 0,
    /** Kinds or payloads this version can't apply, or declined by a policy (e.g. a setting). */
    val skipped: Int = 0,
    /** Kinds whose local table couldn't be read: nothing of them was written. */
    val failedKinds: Set<String> = emptySet(),
) {
    operator fun plus(other: ImportCounts) = ImportCounts(
        written + other.written,
        unchanged + other.unchanged,
        keptLocal + other.keptLocal,
        keptBoth + other.keptBoth,
        tombstones + other.tombstones,
        skipped + other.skipped,
        failedKinds + other.failedKinds,
    )
}

data class ImportResult(
    val byScope: Map<String, ImportCounts>,
    val global: ImportCounts,
    /** Records merged into the sync replica for cloud-only accounts (new or newer versions). */
    val cloudOnlyMerged: Int,
    /** Accounts asked to go cloud-only that can't (removed from the cloud, no replica, not a sync scope). */
    val cloudOnlyRefused: List<String> = emptyList(),
) {
    val changed: Boolean get() = cloudOnlyMerged > 0 || global.written > 0 || byScope.values.any { it.written + it.keptBoth > 0 }
}

/** Plans an import: each account of the backup mapped to a local profile, kept cloud-only, or skipped. */
object ImportPlanner {
    /** [profiles]: every local music account; [replica]: the sync replica, null when sync never ran here. */
    suspend fun plan(backup: ParsedBackup, profiles: List<SyncProfile>, replica: BackupReplica?): ImportPlan {
        val records = backup.snapshot.records
        val live = records.filter { !it.deleted }
        val accounts = accountsOf(backup)
        val locals = profiles.sortedWith(compareBy(SyncProfile::createdAt, SyncProfile::id))
        val scopeOfProfile = locals.associate { profile -> profile.id to BackupScopes.of(profile, replica) }
        val purged = replica?.metaWithPrefix(SyncMetaKeys.PURGED_SCOPE_PREFIX).orEmpty().keys
            .filter { it.startsWith(SyncMetaKeys.PURGED_SCOPE_PREFIX) }
            .mapTo(HashSet()) { it.removePrefix(SyncMetaKeys.PURGED_SCOPE_PREFIX) }
        val taken = HashSet<String>()
        val plans = accounts.map { account ->
            val match = locals.firstOrNull { profile ->
                profile.id !in taken && (
                    scopeOfProfile[profile.id] == account.scope ||
                        account.scope == BackupFormat.LOCAL_SCOPE_PREFIX + profile.id
                    )
            }
            match?.let { taken += it.id }
            val wasPurged = account.scope in purged
            val cloudOnlyPossible = replica != null && BackupFormat.isSyncScope(account.scope) && !wasPurged
            ImportAccountPlan(
                account = account,
                counts = sortedCounts(live.filter { it.scope == account.scope }),
                target = when {
                    match != null -> ImportTarget.IntoProfile(match.id)
                    cloudOnlyPossible -> ImportTarget.CloudOnly
                    else -> ImportTarget.Skip
                },
                sameAccount = match != null,
                options = locals.filter { it.provider == account.provider }
                    .map { ImportProfileOption(it.id, it.provider, it.displayName) },
                cloudOnlyPossible = cloudOnlyPossible,
                wasPurged = wasPurged,
            )
        }
        return ImportPlan(
            backup = backup,
            accounts = plans,
            globalCounts = sortedCounts(live.filter { it.scope == SyncFormat.GLOBAL_SCOPE && it.kind != SyncKinds.ACCOUNT }),
        )
    }

    /** The manifest's accounts, then any other scope the snapshot holds (described by its descriptor, if any). */
    fun accountsOf(backup: ParsedBackup): List<BackupAccount> {
        val listed = backup.manifest.accounts.associateBy { it.scope }
        val scopes = LinkedHashSet<String>()
        backup.manifest.accounts.forEach { scopes += it.scope }
        backup.snapshot.records.forEach { if (it.scope != SyncFormat.GLOBAL_SCOPE) scopes += it.scope }
        val descriptors = backup.snapshot.records
            .filter { it.kind == SyncKinds.ACCOUNT && it.scope == SyncFormat.GLOBAL_SCOPE && !it.deleted && it.payload != null }
            .mapNotNull { CloudAccountDescriptor.fromPayload(it.key, it.payload!!) }
            .associateBy { it.syncProfileId }
        return scopes.map { scope ->
            listed[scope] ?: descriptors[scope].let { descriptor ->
                BackupAccount(
                    scope = scope,
                    provider = descriptor?.provider ?: "unknown",
                    displayName = descriptor?.displayName?.takeIf(String::isNotBlank) ?: scope,
                    hint = descriptor?.hint,
                    storefront = descriptor?.storefront,
                    onDevice = false,
                )
            }
        }
    }

    private fun sortedCounts(records: List<WireRecord>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        records.forEach { counts[it.kind] = (counts[it.kind] ?: 0) + 1 }
        return counts.keys.sorted().associateWith { counts.getValue(it) }
    }
}

/**
 * Applies an [ImportPlan]: merges with the sync engine's rules, straight into the domain, so the engine
 * later publishes the result as ordinary local changes:
 *
 *  - last writer wins by version time (a record's ts against the row's own updatedAt), equal values change
 *    nothing; a version the sync replica already has newer (a delete, say) wins over the backup;
 *  - album reviews (KEEP_BOTH_TEXT): two different texts are both kept under a "<device · date>" divider,
 *    and a text the local review already contains is not added again;
 *  - song / album notes keep their ids; an id owned by another profile here, or a note coming from another
 *    account, gets a [MigrationIds] copy;
 *  - tombstones never delete anything (an import only adds); track→album links ([BackupKinds]) go into the
 *    domain only, never into the replica. Album notes, backup-only in older files, are a sync kind now and
 *    merge like song notes. Payloads are written whole, so keys this version doesn't know survive.
 *
 * Importing the same file twice changes nothing the second time.
 */
class BackupMerger(
    adapters: List<SyncAdapter>,
    private val domain: BackupDomain,
) {
    private val adaptersByKind = adapters.associateBy { it.kind }

    /**
     * Applies [plan], with [choices] (scope -> target) overriding its defaults. [profiles]: every local music
     * account; [replica]: the sync replica (null when sync was never on: cloud-only choices are then refused).
     * Run under the sync lock.
     */
    suspend fun apply(
        plan: ImportPlan,
        choices: Map<String, ImportTarget>,
        profiles: List<SyncProfile>,
        replica: BackupReplica?,
        importGlobal: Boolean,
    ): ImportResult {
        val records = plan.backup.snapshot.records
        val byScope = records.groupBy { it.scope }
        val label = SyncEngine.loserLabel(
            plan.backup.manifest.source.deviceName.ifBlank { plan.backup.snapshot.deviceName },
            plan.backup.manifest.exportedAt,
        )
        val results = LinkedHashMap<String, ImportCounts>()
        val cloudOnly = ArrayList<WireRecord>()
        val refusedCloudOnly = ArrayList<String>()
        val locals = profiles.associateBy { it.id }
        for (account in plan.accounts) {
            val scope = account.account.scope
            when (val target = choices[scope] ?: account.target) {
                is ImportTarget.IntoProfile -> {
                    val profile = locals[target.localProfileId] ?: continue
                    val dstScope = BackupScopes.of(profile, replica)
                    val bound = replica?.bindingFor(profile.id)?.takeIf { it.state == SyncBindingEntity.STATE_ACTIVE }
                    results[scope] = mergeInto(
                        records = byScope[scope].orEmpty(),
                        profileId = profile.id,
                        srcScope = scope,
                        dstScope = dstScope,
                        replicaScope = bound?.syncProfileId,
                        replica = replica,
                        label = label,
                    )
                }
                ImportTarget.CloudOnly -> {
                    if (replica == null || !replica.canMergeImported || !account.cloudOnlyPossible) {
                        refusedCloudOnly += scope
                        continue
                    }
                    // A local profile already syncing this scope would take these through the domain instead.
                    if (replica.bindingForScope(scope) != null) continue
                    cloudOnly += byScope[scope].orEmpty().filter { it.kind !in BackupKinds.ALL }
                    cloudOnly += records.filter {
                        it.kind == SyncKinds.ACCOUNT && it.scope == SyncFormat.GLOBAL_SCOPE && it.key == scope
                    }
                }
                ImportTarget.Skip -> Unit
            }
        }
        val global = if (importGlobal) {
            mergeInto(
                records = byScope[SyncFormat.GLOBAL_SCOPE].orEmpty().filter { it.kind != SyncKinds.ACCOUNT },
                profileId = null,
                srcScope = SyncFormat.GLOBAL_SCOPE,
                dstScope = SyncFormat.GLOBAL_SCOPE,
                replicaScope = SyncFormat.GLOBAL_SCOPE.takeIf { replica != null },
                replica = replica,
                label = label,
            )
        } else {
            ImportCounts()
        }
        var merged = 0
        if (cloudOnly.isNotEmpty() && replica != null && replica.canMergeImported) {
            merged = replica.mergeImported(plan.backup.snapshot.copy(records = cloudOnly))
        }
        return ImportResult(results, global, merged, refusedCloudOnly)
    }

    /** Merges [records] (one scope, or the global scope with [profileId] null) into the domain. */
    private suspend fun mergeInto(
        records: List<WireRecord>,
        profileId: String?,
        srcScope: String,
        dstScope: String,
        replicaScope: String?,
        replica: BackupReplica?,
        label: String,
    ): ImportCounts {
        var counts = ImportCounts()
        val rowsByKind = HashMap<String, MutableMap<String, DomainRow>>()
        val failedKinds = HashSet<String>()
        for (record in records.sortedWith(compareBy({ it.kind }, { it.key }))) {
            if (record.deleted) {
                counts += ImportCounts(tombstones = 1)
                continue
            }
            val payload = record.payload ?: continue
            if (record.kind in BackupKinds.ALL) {
                counts += if (profileId == null) ImportCounts(skipped = 1) else mergeBackupKind(record, payload, profileId)
                continue
            }
            val adapter = adaptersByKind[record.kind]
            if (adapter == null || adapter.kind == SyncKinds.ACCOUNT || adapter.perAccount != (profileId != null) ||
                record.kindVersion > adapter.kindVersion
            ) {
                counts += ImportCounts(skipped = 1)
                continue
            }
            val projection = safeProject(adapter, payload)
            if (projection == null) {
                counts += ImportCounts(skipped = 1)
                continue
            }
            if (adapter.kind in failedKinds) {
                counts += ImportCounts(skipped = 1)
                continue
            }
            val rows = rowsByKind[adapter.kind] ?: try {
                adapter.readAll(profileId).associateByTo(HashMap()) { it.key }.also { rowsByKind[adapter.kind] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never write into a table that can't be read: the guard hashes would all be wrong.
                failedKinds += adapter.kind
                counts += ImportCounts(skipped = 1, failedKinds = setOf(adapter.kind))
                continue
            }
            counts += try {
                mergeRecord(adapter, record, payload, projection, rows, profileId, srcScope, dstScope, replicaScope, replica, label)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportCounts(skipped = 1)
            }
        }
        return counts
    }

    private suspend fun mergeRecord(
        adapter: SyncAdapter,
        record: WireRecord,
        payload: JsonObject,
        projection: JsonObject,
        rows: MutableMap<String, DomainRow>,
        profileId: String?,
        srcScope: String,
        dstScope: String,
        replicaScope: String?,
        replica: BackupReplica?,
        label: String,
    ): ImportCounts {
        var key = record.key
        // Notes (song and album) have global ids: another profile's or another account's go in as copies.
        if (profileId != null && key !in rows && (adapter.kind == SyncKinds.SONG_NOTE || adapter.kind == SyncKinds.ALBUM_NOTE)) {
            val owner = if (adapter.kind == SyncKinds.SONG_NOTE) domain.songNoteOwner(key) else domain.albumNoteOwner(key)
            if (needsCopyId(owner, profileId, srcScope, dstScope)) {
                key = MigrationIds.derive(srcScope, record.key, dstScope)
            }
        }
        val local = rows[key]
        val hash = CanonicalJson.hash(projection)
        val localHash = local?.let { CanonicalJson.hash(it.projection) }
        if (localHash == hash) return ImportCounts(unchanged = 1)
        if (replicaScope != null && replica != null) {
            val known = replica.record(adapter.kind, replicaScope, key)
            if (known != null && RecordVersion(known.ts, known.device) >= RecordVersion(record.ts, record.device)) {
                return ImportCounts(keptLocal = 1)
            }
        }
        var toWrite = payload
        var versionTs = record.ts
        var keptBoth = false
        if (local != null) {
            val localTs = local.rowTs ?: 0L
            if (adapter.conflictPolicy == ConflictPolicy.KEEP_BOTH_TEXT) {
                val localText = local.projection.requiredString(TEXT_FIELD).orEmpty()
                val incoming = projection.requiredString(TEXT_FIELD).orEmpty()
                when {
                    incoming.isBlank() || localText.contains(incoming) -> return ImportCounts(unchanged = 1)
                    localText.isBlank() || incoming.contains(localText) -> Unit
                    else -> {
                        // This device's text stays first; the backup's follows under its "<device · date>".
                        val merged = adapter.mergeConcurrent(
                            winner = local.projection,
                            loser = payload,
                            loserLabel = label,
                        ) ?: return ImportCounts(keptLocal = 1)
                        toWrite = merged
                        versionTs = maxOf(record.ts, localTs)
                        keptBoth = true
                    }
                }
            } else if (record.ts <= localTs) {
                return ImportCounts(keptLocal = 1)
            }
        }
        return when (adapter.apply(profileId, key, toWrite, versionTs, localHash)) {
            is ApplyOutcome.Applied -> {
                rows[key] = DomainRow(key, safeProject(adapter, toWrite) ?: projection, versionTs)
                if (keptBoth) ImportCounts(keptBoth = 1) else ImportCounts(written = 1)
            }
            is ApplyOutcome.Skipped -> ImportCounts(skipped = 1)
        }
    }

    private suspend fun mergeBackupKind(record: WireRecord, payload: JsonObject, profileId: String): ImportCounts =
        when (record.kind) {
            BackupKinds.TRACK_ALBUM_LINK -> {
                val provider = payload.requiredString(BackupFields.PROVIDER) ?: return ImportCounts(skipped = 1)
                val trackId = payload.requiredString(BackupFields.TRACK_ID) ?: return ImportCounts(skipped = 1)
                val albumId = payload.requiredString(BackupFields.ALBUM_ID) ?: return ImportCounts(skipped = 1)
                // A fact this device learned itself stays.
                if (domain.trackAlbumLink(profileId, provider, trackId) != null) return ImportCounts(unchanged = 1)
                domain.putTrackAlbumLink(
                    profileId,
                    BackupTrackAlbumLink(
                        provider = provider,
                        trackId = trackId,
                        albumId = albumId,
                        source = payload.requiredString(BackupFields.SOURCE) ?: BackupTrackAlbumLink.SOURCE_IMPORT,
                        updatedAt = payload.requiredLong(BackupFields.UPDATED_AT) ?: record.ts,
                    ),
                )
                ImportCounts(written = 1)
            }
            else -> ImportCounts(skipped = 1)
        }

    private fun safeProject(adapter: SyncAdapter, payload: JsonObject): JsonObject? = try {
        adapter.project(payload)
    } catch (e: RuntimeException) {
        null
    }

    companion object {
        /**
         * A note goes in under a [MigrationIds] copy id unless it is already this profile's own: when its id
         * belongs to another profile here, or when it comes from another account (a different scope) — the
         * same account's notes keep their ids, so syncing and re-importing find them.
         */
        fun needsCopyId(ownerProfileId: String?, profileId: String, srcScope: String, dstScope: String): Boolean = when {
            ownerProfileId == profileId -> false
            ownerProfileId != null -> true
            else -> srcScope != dstScope
        }

        /** The text field of the one KEEP_BOTH_TEXT kind (album_review). */
        const val TEXT_FIELD = "review"
    }
}
