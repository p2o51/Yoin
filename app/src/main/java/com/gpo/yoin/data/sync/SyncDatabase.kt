package com.gpo.yoin.data.sync

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import java.io.File

/**
 * Cloud sync's own database: the replica of what is in Drive plus this
 * device's bookkeeping. Lives in noBackupFilesDir so Auto Backup never
 * restores it (a restored deviceId or applied-state would corrupt sync), and
 * so the app's main [com.gpo.yoin.data.local.YoinDatabase] schema never
 * changes for sync.
 */
@Database(
    entities = [
        SyncRecordEntity::class,
        SyncLocalStateEntity::class,
        SyncBindingEntity::class,
        SyncRemoteFileEntity::class,
        SyncMetaEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun syncDao(): SyncDao

    companion object {
        const val FILE_NAME = "yoin-sync.db"

        fun build(context: Context): SyncDatabase = Room.databaseBuilder(
            context.applicationContext,
            SyncDatabase::class.java,
            File(context.applicationContext.noBackupFilesDir, FILE_NAME).absolutePath,
        ).build()

        fun inMemory(context: Context): SyncDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, SyncDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}

/** The replicated state: exactly what this device publishes. */
@Entity(tableName = "sync_records", primaryKeys = ["kind", "scope", "key"])
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
 * What this device's domain DB holds for a record under the CURRENT binding.
 * A row whose [boundProfileId] is not the scope's current local profile is
 * stale and must be treated as absent.
 */
@Entity(tableName = "sync_local_state", primaryKeys = ["kind", "scope", "key"])
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
@Entity(
    tableName = "sync_bindings",
    indices = [Index(value = ["syncProfileId"], unique = true)],
)
data class SyncBindingEntity(
    @PrimaryKey val localProfileId: String,
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
@Entity(tableName = "sync_remote_files")
data class SyncRemoteFileEntity(
    @PrimaryKey val fileId: String,
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

@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/** Well-known [SyncMetaEntity] keys. */
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

@Dao
interface SyncDao {
    // ---- records
    @Query("SELECT * FROM sync_records WHERE kind = :kind AND scope = :scope AND `key` = :key")
    suspend fun getRecord(kind: String, scope: String, key: String): SyncRecordEntity?

    @Query("SELECT * FROM sync_records WHERE kind = :kind AND scope = :scope")
    suspend fun recordsFor(kind: String, scope: String): List<SyncRecordEntity>

    @Query("SELECT * FROM sync_records WHERE scope = :scope")
    suspend fun recordsInScope(scope: String): List<SyncRecordEntity>

    @Query("SELECT * FROM sync_records")
    suspend fun allRecords(): List<SyncRecordEntity>

    @Query("SELECT * FROM sync_records WHERE kind = :kind")
    suspend fun recordsOfKind(kind: String): List<SyncRecordEntity>

    @Query("SELECT COUNT(*) FROM sync_records WHERE kind = :kind AND scope = :scope AND deleted = 0")
    suspend fun liveCount(kind: String, scope: String): Int

    @Upsert
    suspend fun upsertRecord(record: SyncRecordEntity)

    @Upsert
    suspend fun upsertRecords(records: List<SyncRecordEntity>)

    @Query("DELETE FROM sync_records")
    suspend fun clearRecords()

    // ---- local state
    @Query("SELECT * FROM sync_local_state WHERE kind = :kind AND scope = :scope AND `key` = :key")
    suspend fun getLocalState(kind: String, scope: String, key: String): SyncLocalStateEntity?

    @Query("SELECT * FROM sync_local_state WHERE kind = :kind AND scope = :scope")
    suspend fun localStatesFor(kind: String, scope: String): List<SyncLocalStateEntity>

    @Query("SELECT * FROM sync_local_state WHERE scope = :scope")
    suspend fun localStatesInScope(scope: String): List<SyncLocalStateEntity>

    @Query("SELECT * FROM sync_local_state WHERE pending = 1")
    suspend fun pendingLocalStates(): List<SyncLocalStateEntity>

    @Query("SELECT COUNT(*) FROM sync_local_state WHERE pending = 1")
    suspend fun pendingCount(): Int

    @Upsert
    suspend fun upsertLocalState(state: SyncLocalStateEntity)

    @Upsert
    suspend fun upsertLocalStates(states: List<SyncLocalStateEntity>)

    @Query("DELETE FROM sync_local_state WHERE kind = :kind AND scope = :scope AND `key` = :key")
    suspend fun deleteLocalState(kind: String, scope: String, key: String)

    @Query("DELETE FROM sync_local_state WHERE scope = :scope")
    suspend fun deleteLocalStatesInScope(scope: String)

    @Query("DELETE FROM sync_local_state WHERE boundProfileId = :localProfileId")
    suspend fun deleteLocalStatesForProfile(localProfileId: String)

    @Query("DELETE FROM sync_local_state")
    suspend fun clearLocalStates()

    // ---- bindings
    @Query("SELECT * FROM sync_bindings")
    suspend fun bindings(): List<SyncBindingEntity>

    @Query("SELECT * FROM sync_bindings WHERE localProfileId = :localProfileId")
    suspend fun bindingFor(localProfileId: String): SyncBindingEntity?

    @Query("SELECT * FROM sync_bindings WHERE syncProfileId = :syncProfileId")
    suspend fun bindingForScope(syncProfileId: String): SyncBindingEntity?

    @Upsert
    suspend fun upsertBinding(binding: SyncBindingEntity)

    @Query("DELETE FROM sync_bindings WHERE localProfileId = :localProfileId")
    suspend fun deleteBinding(localProfileId: String)

    // ---- remote files
    @Query("SELECT * FROM sync_remote_files")
    suspend fun remoteFiles(): List<SyncRemoteFileEntity>

    @Query("SELECT * FROM sync_remote_files WHERE fileId = :fileId")
    suspend fun remoteFile(fileId: String): SyncRemoteFileEntity?

    @Upsert
    suspend fun upsertRemoteFile(file: SyncRemoteFileEntity)

    @Query("DELETE FROM sync_remote_files WHERE fileId = :fileId")
    suspend fun deleteRemoteFile(fileId: String)

    @Query("DELETE FROM sync_remote_files")
    suspend fun clearRemoteFiles()

    // ---- meta
    @Query("SELECT value FROM sync_meta WHERE `key` = :key")
    suspend fun meta(key: String): String?

    @Query("SELECT * FROM sync_meta WHERE `key` LIKE :prefix || '%'")
    suspend fun metaWithPrefix(prefix: String): List<SyncMetaEntity>

    @Upsert
    suspend fun putMeta(entry: SyncMetaEntity)

    @Query("DELETE FROM sync_meta WHERE `key` = :key")
    suspend fun deleteMeta(key: String)
}
