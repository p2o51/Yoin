package com.gpo.yoin.data.sync.backup

import com.gpo.yoin.data.local.AlbumNote
import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.local.TrackAlbumLink
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.sync.ApplyOutcome
import com.gpo.yoin.data.sync.CanonicalJson
import com.gpo.yoin.data.sync.ConflictPolicy
import com.gpo.yoin.data.sync.DomainRow
import com.gpo.yoin.data.sync.RecordVersion
import com.gpo.yoin.data.sync.SyncAdapter
import com.gpo.yoin.data.sync.SyncBindingEntity
import com.gpo.yoin.data.sync.SyncDao
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.data.sync.WireRecord
import com.gpo.yoin.data.sync.adapters.requiredLong
import com.gpo.yoin.data.sync.adapters.requiredString
import com.gpo.yoin.data.sync.engine.SyncEngine
import com.gpo.yoin.data.sync.identity.CloudAccountDescriptor
import com.gpo.yoin.data.transfer.memories.MigrationIds
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
    /** [ImportTarget.CloudOnly] is possible: a sync scope and a replica on this device. */
    val cloudOnlyPossible: Boolean,
)

/** What an import would do; [apply] it with the user's choices. */
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
) {
    operator fun plus(other: ImportCounts) = ImportCounts(
        written + other.written,
        unchanged + other.unchanged,
        keptLocal + other.keptLocal,
        keptBoth + other.keptBoth,
        tombstones + other.tombstones,
        skipped + other.skipped,
    )
}

data class ImportResult(
    val byScope: Map<String, ImportCounts>,
    val global: ImportCounts,
    /** Records merged into the sync replica for cloud-only accounts (new or newer versions). */
    val cloudOnlyMerged: Int,
) {
    val changed: Boolean get() = cloudOnlyMerged > 0 || global.written > 0 || byScope.values.any { it.written + it.keptBoth > 0 }
}

/**
 * Imports a backup. [plan] maps each account to a local profile (same account by sync binding or
 * fingerprint), keeps it cloud-only (sync scopes, with a replica) or leaves it in the file. [apply] merges
 * with the sync engine's rules, straight into the domain, so the engine later publishes the result as
 * ordinary local changes:
 *
 *  - last writer wins by version time (a record's ts against the row's own updatedAt), equal values change
 *    nothing; a version the sync replica already has newer (a delete, say) wins over the backup;
 *  - album reviews (KEEP_BOTH_TEXT): two different texts are both kept under a "<device · date>" divider,
 *    and a text the local review already contains is not added again;
 *  - song / album notes keep their ids; an id owned by another profile here gets a [MigrationIds] copy;
 *  - tombstones never delete anything (an import only adds); album notes and track→album links
 *    ([BackupKinds]) go into the domain only, never into the replica.
 *
 * Importing the same file twice changes nothing the second time.
 */
class BackupImporter(
    private val db: YoinDatabase,
    private val adapters: List<SyncAdapter>,
    private val profiles: suspend () -> List<Profile>,
    private val decodeCredentials: (Profile) -> ProfileCredentials?,
) {
    private val adaptersByKind = adapters.associateBy { it.kind }

    suspend fun plan(backup: ParsedBackup, replica: SyncDao?): ImportPlan {
        val records = backup.snapshot.records
        val live = records.filter { !it.deleted }
        val accounts = accountsOf(backup)
        val locals = profiles().sortedWith(compareBy(Profile::createdAt, Profile::id))
        val scopeOfProfile = locals.associate { profile ->
            profile.id to AccountScopes.of(profile, runCatching { decodeCredentials(profile) }.getOrNull(), replica)
        }
        val taken = HashSet<String>()
        val plans = accounts.map { account ->
            val match = locals.firstOrNull { profile ->
                profile.id !in taken && (
                    scopeOfProfile[profile.id] == account.scope ||
                        account.scope == BackupFormat.LOCAL_SCOPE_PREFIX + profile.id
                    )
            }
            match?.let { taken += it.id }
            val cloudOnlyPossible = replica != null && BackupFormat.isSyncScope(account.scope)
            ImportAccountPlan(
                account = account,
                counts = live.filter { it.scope == account.scope }.groupingBy { it.kind }.eachCount().toSortedMap(),
                target = when {
                    match != null -> ImportTarget.IntoProfile(match.id)
                    cloudOnlyPossible -> ImportTarget.CloudOnly
                    else -> ImportTarget.Skip
                },
                sameAccount = match != null,
                options = locals.filter { it.provider == account.provider }
                    .map { ImportProfileOption(it.id, it.provider, it.displayName) },
                cloudOnlyPossible = cloudOnlyPossible,
            )
        }
        return ImportPlan(
            backup = backup,
            accounts = plans,
            globalCounts = live.filter { it.scope == SyncFormat.GLOBAL_SCOPE && it.kind != SyncKinds.ACCOUNT }
                .groupingBy { it.kind }.eachCount().toSortedMap(),
        )
    }

    /**
     * Applies [plan], with [choices] (scope -> target) overriding its defaults. [replica]/[engine] are the
     * sync replica (null when sync was never on: cloud-only choices are then skipped). Run under the sync
     * lock (CloudSyncManager.withReplica).
     */
    suspend fun apply(
        plan: ImportPlan,
        choices: Map<String, ImportTarget> = emptyMap(),
        replica: SyncDao? = null,
        engine: SyncEngine? = null,
        importGlobal: Boolean = true,
    ): ImportResult {
        val records = plan.backup.snapshot.records
        val byScope = records.groupBy { it.scope }
        val label = SyncEngine.loserLabel(
            plan.backup.manifest.source.deviceName.ifBlank { plan.backup.snapshot.deviceName },
            plan.backup.manifest.exportedAt,
        )
        val results = LinkedHashMap<String, ImportCounts>()
        val cloudOnly = ArrayList<WireRecord>()
        val locals = profiles().associateBy { it.id }
        for (account in plan.accounts) {
            val scope = account.account.scope
            when (val target = choices[scope] ?: account.target) {
                is ImportTarget.IntoProfile -> {
                    val profile = locals[target.localProfileId] ?: continue
                    val dstScope = AccountScopes.of(profile, runCatching { decodeCredentials(profile) }.getOrNull(), replica)
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
                    if (replica == null || engine == null || !BackupFormat.isSyncScope(scope)) continue
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
        if (cloudOnly.isNotEmpty() && engine != null) {
            val result = engine.mergeImported(plan.backup.snapshot.copy(records = cloudOnly))
            merged = result.inserted + result.replaced + result.keptBoth
        }
        return ImportResult(results, global, merged)
    }

    /** Merges [records] (one scope, or the global scope with [profileId] null) into the domain. */
    private suspend fun mergeInto(
        records: List<WireRecord>,
        profileId: String?,
        srcScope: String,
        dstScope: String,
        replicaScope: String?,
        replica: SyncDao?,
        label: String,
    ): ImportCounts {
        var counts = ImportCounts()
        val rowsByKind = HashMap<String, MutableMap<String, DomainRow>>()
        for (record in records.sortedWith(compareBy({ it.kind }, { it.key }))) {
            if (record.deleted) {
                counts += ImportCounts(tombstones = 1)
                continue
            }
            val payload = record.payload ?: continue
            if (record.kind in BackupKinds.ALL) {
                counts += if (profileId == null) ImportCounts(skipped = 1) else mergeBackupKind(record, payload, profileId, srcScope, dstScope)
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
            val rows = rowsByKind.getOrPut(adapter.kind) {
                try {
                    adapter.readAll(profileId).associateByTo(HashMap()) { it.key }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Never write into a table that can't be read: the guard hashes would all be wrong.
                    null
                } ?: HashMap()
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
        replica: SyncDao?,
        label: String,
    ): ImportCounts {
        var key = record.key
        if (adapter.kind == SyncKinds.SONG_NOTE && profileId != null && key !in rows) {
            val owner = db.syncDomainDao().noteById(key)
            if (owner != null && owner.profileId != profileId) key = MigrationIds.derive(srcScope, record.key, dstScope)
        }
        val local = rows[key]
        val hash = CanonicalJson.hash(projection)
        val localHash = local?.let { CanonicalJson.hash(it.projection) }
        if (localHash == hash) return ImportCounts(unchanged = 1)
        if (replicaScope != null && replica != null) {
            val known = replica.getRecord(adapter.kind, replicaScope, key)
            if (known != null && known.version >= RecordVersion(record.ts, record.device)) {
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
        return when (val outcome = adapter.apply(profileId, key, toWrite, versionTs, localHash)) {
            is ApplyOutcome.Applied -> {
                rows[key] = DomainRow(key, safeProject(adapter, toWrite) ?: projection, versionTs)
                if (keptBoth) ImportCounts(keptBoth = 1) else ImportCounts(written = 1)
            }
            is ApplyOutcome.Skipped -> ImportCounts(skipped = 1)
        }
    }

    private suspend fun mergeBackupKind(
        record: WireRecord,
        payload: JsonObject,
        profileId: String,
        srcScope: String,
        dstScope: String,
    ): ImportCounts {
        val dao = db.syncDomainDao()
        return when (record.kind) {
            BackupKinds.ALBUM_NOTE -> {
                val note = albumNoteOf(record.key, profileId, payload) ?: return ImportCounts(skipped = 1)
                var id = note.id
                var existing = dao.albumNoteById(id)
                if (existing != null && existing.profileId != profileId) {
                    id = MigrationIds.derive(srcScope, note.id, dstScope)
                    existing = dao.albumNoteById(id)
                }
                val incoming = note.copy(id = id)
                when {
                    existing == null -> {
                        dao.writeAlbumNote(incoming)
                        ImportCounts(written = 1)
                    }
                    existing == incoming -> ImportCounts(unchanged = 1)
                    incoming.updatedAt > existing.updatedAt -> {
                        dao.writeAlbumNote(incoming)
                        ImportCounts(written = 1)
                    }
                    else -> ImportCounts(keptLocal = 1)
                }
            }
            BackupKinds.TRACK_ALBUM_LINK -> {
                val provider = payload.requiredString(BackupFields.PROVIDER) ?: return ImportCounts(skipped = 1)
                val trackId = payload.requiredString(BackupFields.TRACK_ID) ?: return ImportCounts(skipped = 1)
                val albumId = payload.requiredString(BackupFields.ALBUM_ID) ?: return ImportCounts(skipped = 1)
                // A fact this device learned itself stays.
                if (dao.trackAlbumLink(profileId, provider, trackId) != null) return ImportCounts(unchanged = 1)
                dao.upsertTrackAlbumLink(
                    TrackAlbumLink(
                        profileId = profileId,
                        provider = provider,
                        trackId = trackId,
                        albumId = albumId,
                        source = payload.requiredString(BackupFields.SOURCE) ?: TrackAlbumLink.SOURCE_IMPORT,
                        updatedAt = payload.requiredLong(BackupFields.UPDATED_AT) ?: record.ts,
                    ),
                )
                ImportCounts(written = 1)
            }
            else -> ImportCounts(skipped = 1)
        }
    }

    private fun accountsOf(backup: ParsedBackup): List<BackupAccount> {
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

    private fun safeProject(adapter: SyncAdapter, payload: JsonObject): JsonObject? = try {
        adapter.project(payload)
    } catch (e: RuntimeException) {
        null
    }

    internal companion object {
        /** The text field of the one KEEP_BOTH_TEXT kind (album_review). */
        const val TEXT_FIELD = "review"

        fun albumNoteOf(id: String, profileId: String, payload: JsonObject): AlbumNote? = AlbumNote(
            id = id,
            profileId = profileId,
            albumId = payload.requiredString(BackupFields.ALBUM_ID)?.takeIf(String::isNotEmpty) ?: return null,
            provider = payload.requiredString(BackupFields.PROVIDER)?.takeIf(String::isNotEmpty) ?: return null,
            content = payload.requiredString(BackupFields.CONTENT) ?: return null,
            createdAt = payload.requiredLong(BackupFields.CREATED_AT) ?: return null,
            updatedAt = payload.requiredLong(BackupFields.UPDATED_AT) ?: return null,
            albumName = payload.requiredString(BackupFields.ALBUM_NAME).orEmpty(),
            artist = payload.requiredString(BackupFields.ARTIST).orEmpty(),
        )
    }
}
