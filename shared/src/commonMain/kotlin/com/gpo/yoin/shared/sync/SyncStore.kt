package com.gpo.yoin.shared.sync

import kotlinx.serialization.Serializable

/*
 * Cloud sync's own bookkeeping: the replica of what is in Drive plus this
 * device's state. Android keeps it in a Room database (`data/sync/SyncDatabase.kt`,
 * noBackupFilesDir/yoin-sync.db); the entities and DAO surface here are the same
 * so the engine is the same code. iOS uses [InMemorySyncStore], persisted as one
 * JSON file outside iCloud/iTunes backup.
 */

/** The replicated state: exactly what this device publishes. */
@Serializable
data class SyncRecordEntity(
    val kind: String,
    val scope: String,
    val key: String,
    val kindVersion: Int,
    val ts: Long,
    val device: String,
    val prevTs: Long?,
    val prevDevice: String?,
    val deleted: Boolean,
    /** Exact JSON object text (unknown keys preserved); null for tombstones. */
    val payload: String?,
    /** [CanonicalJson.hash] of [payload]; null for tombstones. */
    val payloadHash: String?,
) {
    val version: RecordVersion get() = RecordVersion(ts, device)
    val prev: RecordVersion?
        get() = if (prevTs != null && prevDevice != null) RecordVersion(prevTs, prevDevice) else null

    fun toRecord() = SyncRecord(kind, kindVersion, scope, key, version, prev, deleted, payload)
}

/**
 * What this device's domain store holds for a record under the CURRENT binding.
 * A row whose [boundProfileId] is not the scope's current local profile is
 * stale and must be treated as absent.
 */
@Serializable
data class SyncLocalStateEntity(
    val kind: String,
    val scope: String,
    val key: String,
    /** Local profile the row lives under; "" for global kinds. */
    val boundProfileId: String,
    /** Hash of the domain projection as last read back; null when the row is known absent. */
    val localHash: String?,
    val appliedTs: Long?,
    val appliedDevice: String?,
    /** A local change exists in sync_records that has not been uploaded yet. */
    val pending: Boolean,
    /** The version the pending local change was based on (kept until publish). */
    val pendingPrevTs: Long?,
    val pendingPrevDevice: String?,
) {
    val applied: RecordVersion?
        get() = if (appliedTs != null && appliedDevice != null) RecordVersion(appliedTs, appliedDevice) else null

    val pendingPrev: RecordVersion?
        get() = if (pendingPrevTs != null && pendingPrevDevice != null) {
            RecordVersion(pendingPrevTs, pendingPrevDevice)
        } else {
            null
        }
}

/** Injective map local profile <-> sync scope. */
@Serializable
data class SyncBindingEntity(
    val localProfileId: String,
    val syncProfileId: String,
    val provider: String,
    /** sha256 of the account fingerprint this binding was made for; null for Apple Music (explicit links). */
    val fingerprintHash: String?,
    /** [STATE_ACTIVE] or [STATE_PAUSED_MISMATCH]. */
    val state: String,
    /** Fingerprint hash observed when the state became PAUSED_MISMATCH. */
    val observedFingerprintHash: String?,
    val boundAt: Long,
) {
    companion object {
        const val STATE_ACTIVE = "active"
        const val STATE_PAUSED_MISMATCH = "paused_mismatch"
    }
}

/** Every Yoin file seen in Drive (ours and other devices'). */
@Serializable
data class SyncRemoteFileEntity(
    val fileId: String,
    val deviceId: String,
    /** [SyncFileProps.KIND_STATE] / [SyncFileProps.KIND_ARTIFACTS] / [SyncFileProps.KIND_RESET]. */
    val fileKind: String,
    val shard: Int,
    /** Drive `version` merged (or uploaded, for own files) last; -1 = never merged. */
    val version: Long,
    val modifiedTime: Long?,
    val deviceName: String?,
    val writtenAt: Long?,
    val own: Boolean,
)

@Serializable
data class SyncMetaEntity(
    val key: String,
    val value: String,
)

/** Well-known [SyncMetaEntity] keys (identical to Android's). */
object SyncMetaKeys {
    const val DEVICE_ID = "device_id"
    const val ENABLED = "enabled"
    const val GOOGLE_PERMISSION_ID = "google_permission_id"
    const val GOOGLE_EMAIL = "google_email"
    const val GOOGLE_DISPLAY_NAME = "google_display_name"

    /** permissionId the replica content was built against. */
    const val REPLICA_PERMISSION_ID = "replica_permission_id"
    const val EPOCH = "epoch"
    const val LAST_SYNC_AT = "last_sync_at"
    const val LAST_ERROR = "last_error"
    const val LAST_SUMMARY = "last_summary"

    /** "<versionCode>|<certSha1>" for which OAuth was found misconfigured. */
    const val MISCONFIGURED_FOR = "misconfigured_for"

    /** JSON {keys:[...], scope, kind} of a held mass-delete. */
    const val HELD_REVIEW = "held_review"

    /** true once this device has uploaded its state file at least once (for CloudCopyRemoved detection). */
    const val HAS_PUBLISHED = "has_published"

    /**
     * true once this device merged a peer file or uploaded anything in the current epoch: it then
     * holds data from before any newer reset and must stop (not re-upload) when one appears.
     */
    const val PARTICIPATED = "participated"

    /** "<epoch>|<markerFileId>" of a "delete cloud data" that has not finished deleting files yet. */
    const val PENDING_RESET = "pending_reset"

    /** Prefix: "spotify_uid:<localProfileId>" = "<userId>|<refreshTokenHash16>". */
    const val SPOTIFY_UID_PREFIX = "spotify_uid:"

    /** Prefix: "last_uploaded_hash:<fileKind>:<shard>". */
    const val LAST_UPLOADED_HASH_PREFIX = "last_uploaded_hash:"

    /** Prefix: "pending_file_id:<fileKind>:<shard>" = pre-allocated Drive id for our not-yet-created file. */
    const val PENDING_FILE_ID_PREFIX = "pending_file_id:"
}

/**
 * The DAO surface of Android's `SyncDao`, plus [transaction]. Method names and
 * semantics match so [com.gpo.yoin.shared.sync.engine.SyncEngine] is a line-for-line port.
 */
interface SyncStore {
    /** Runs [block] atomically: on a throw every write made inside it is rolled back. Nested calls join. */
    suspend fun <T> transaction(block: suspend () -> T): T

    // ---- records
    suspend fun getRecord(kind: String, scope: String, key: String): SyncRecordEntity?

    suspend fun recordsFor(kind: String, scope: String): List<SyncRecordEntity>

    suspend fun recordsInScope(scope: String): List<SyncRecordEntity>

    suspend fun allRecords(): List<SyncRecordEntity>

    suspend fun recordsOfKind(kind: String): List<SyncRecordEntity>

    suspend fun liveCount(kind: String, scope: String): Int

    suspend fun upsertRecord(record: SyncRecordEntity)

    suspend fun upsertRecords(records: List<SyncRecordEntity>)

    suspend fun clearRecords()

    // ---- local state
    suspend fun getLocalState(kind: String, scope: String, key: String): SyncLocalStateEntity?

    suspend fun localStatesFor(kind: String, scope: String): List<SyncLocalStateEntity>

    suspend fun localStatesInScope(scope: String): List<SyncLocalStateEntity>

    suspend fun pendingLocalStates(): List<SyncLocalStateEntity>

    suspend fun pendingCount(): Int

    suspend fun upsertLocalState(state: SyncLocalStateEntity)

    suspend fun upsertLocalStates(states: List<SyncLocalStateEntity>)

    suspend fun deleteLocalState(kind: String, scope: String, key: String)

    suspend fun deleteLocalStatesInScope(scope: String)

    suspend fun deleteLocalStatesForProfile(localProfileId: String)

    suspend fun clearLocalStates()

    // ---- bindings
    suspend fun bindings(): List<SyncBindingEntity>

    suspend fun bindingFor(localProfileId: String): SyncBindingEntity?

    suspend fun bindingForScope(syncProfileId: String): SyncBindingEntity?

    suspend fun upsertBinding(binding: SyncBindingEntity)

    suspend fun deleteBinding(localProfileId: String)

    // ---- remote files
    suspend fun remoteFiles(): List<SyncRemoteFileEntity>

    suspend fun remoteFile(fileId: String): SyncRemoteFileEntity?

    suspend fun upsertRemoteFile(file: SyncRemoteFileEntity)

    suspend fun deleteRemoteFile(fileId: String)

    suspend fun clearRemoteFiles()

    // ---- meta
    suspend fun meta(key: String): String?

    suspend fun metaWithPrefix(prefix: String): List<SyncMetaEntity>

    suspend fun putMeta(entry: SyncMetaEntity)

    suspend fun deleteMeta(key: String)
}
