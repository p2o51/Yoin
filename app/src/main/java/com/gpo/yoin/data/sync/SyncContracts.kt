package com.gpo.yoin.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * Cloud sync contracts — the shapes every sync component agrees on.
 * Spec: docs/cloud-sync.md. Keep this file free of Android and I/O.
 */

/** Stable wire names of synced record kinds. Never rename: they are persisted in every user's Drive. */
object SyncKinds {
    const val SONG_NOTE = "song_note"
    const val TRACK_RATING = "track_rating"
    const val ALBUM_RATING = "album_rating"
    const val ALBUM_REVIEW = "album_review"
    const val HOME_LAYOUT = "home_layout"
    const val SETTING = "setting"
    const val LYRICS_TRANSLATION = "lyrics_translation"
    const val ACCOUNT = "account"
    const val MEMORY_COPY = "memory_copy"
    const val MEMORY_TITLE = "memory_title"
    const val SONG_ABOUT = "song_about"
}

/** Keys of [SyncKinds.SETTING] records. */
object SyncSettingKeys {
    const val TRANSLATION_LANGUAGE = "translation_language"
    const val SPOTIFY_CLIENT_ID = "spotify_client_id"
    const val SEAM_TOP_STYLE = "seam_top_style"
}

object SyncFormat {
    const val FORMAT = "yoin-sync"
    const val SCHEMA = 1

    /** Scope of records that belong to no music account. */
    const val GLOBAL_SCOPE = ""

    /** Separator inside composite record keys (U+001F). */
    const val KEY_SEPARATOR = '\u001F'

    /** Artifact records are spread over this many files per device, by [CanonicalJson.stableHash] of the key. */
    const val ARTIFACT_SHARDS = 4

    fun compositeKey(vararg parts: String): String = parts.joinToString(KEY_SEPARATOR.toString())

    fun artifactShard(key: String): Int = CanonicalJson.stableHash(key) % ARTIFACT_SHARDS
}

/** A record version. Total order: [ts], then [device]. */
data class RecordVersion(val ts: Long, val device: String) : Comparable<RecordVersion> {
    override fun compareTo(other: RecordVersion): Int =
        compareValuesBy(this, other, RecordVersion::ts, RecordVersion::device)
}

/** Which per-device file a kind is published in. */
enum class FileClass(val wireName: String) {
    /** Small, frequently changing user data: one file per device. */
    STATE("state"),

    /** Larger, rarely changing derived data: [SyncFormat.ARTIFACT_SHARDS] files per device. */
    ARTIFACTS("artifacts"),
}

enum class ConflictPolicy {
    /** Last writer (by [RecordVersion]) wins. */
    LWW,

    /** LWW, but on a concurrent live-vs-tombstone conflict the live record wins (no silent delete of user text). */
    LWW_LIVE_WINS,

    /** Concurrent edits of one text field are kept both, joined with a device/date divider ([SyncAdapter.mergeConcurrent]). */
    KEEP_BOTH_TEXT,
}

/**
 * One replicated record as the engine sees it. [payload] is the exact JSON
 * object text as received or produced (unknown keys preserved, never
 * re-encoded); null for tombstones.
 */
data class SyncRecord(
    val kind: String,
    val kindVersion: Int,
    val scope: String,
    val key: String,
    val version: RecordVersion,
    val prev: RecordVersion?,
    val deleted: Boolean,
    val payload: String?,
)

// ---- Wire format (gzip JSON in Drive). Explicit @SerialName everywhere; never derive names from classes.

@Serializable
data class WireRecord(
    @SerialName("k") val kind: String,
    @SerialName("kv") val kindVersion: Int = 1,
    @SerialName("s") val scope: String = SyncFormat.GLOBAL_SCOPE,
    @SerialName("id") val key: String,
    @SerialName("ts") val ts: Long,
    @SerialName("d") val device: String,
    @SerialName("pts") val prevTs: Long? = null,
    @SerialName("pd") val prevDevice: String? = null,
    @SerialName("del") val deleted: Boolean = false,
    @SerialName("v") val payload: JsonObject? = null,
)

@Serializable
data class SyncEnvelope(
    @SerialName("format") val format: String = SyncFormat.FORMAT,
    @SerialName("schema") val schema: Int = SyncFormat.SCHEMA,
    @SerialName("epoch") val epoch: Long = 0L,
    @SerialName("deviceId") val deviceId: String,
    @SerialName("deviceName") val deviceName: String,
    @SerialName("fileClass") val fileClass: String,
    @SerialName("shard") val shard: Int = 0,
    @SerialName("writtenAt") val writtenAt: Long,
    @SerialName("appVersionCode") val appVersionCode: Int,
    @SerialName("records") val records: List<WireRecord>,
)

@Serializable
data class ResetMarker(
    @SerialName("epoch") val epoch: Long,
    @SerialName("at") val at: Long,
    @SerialName("deviceId") val deviceId: String,
    @SerialName("deviceName") val deviceName: String,
)

/** Drive appProperties keys/values used to identify Yoin files (never identify files by name). */
object SyncFileProps {
    const val KIND = "yoinKind"
    const val DEVICE_ID = "deviceId"
    const val SHARD = "shard"
    const val SCHEMA = "schema"
    const val EPOCH = "epoch"

    const val KIND_STATE = "state"
    const val KIND_ARTIFACTS = "artifacts"
    const val KIND_RESET = "reset"

    fun stateFileName(deviceId: String) = "yoin-state-$deviceId.json.gz"

    fun artifactFileName(deviceId: String, shard: Int) = "yoin-artifacts-$deviceId-$shard.json.gz"

    fun resetFileName(epoch: Long, deviceId: String) = "yoin-reset-$epoch-$deviceId.json"
}

// ---- Domain bridge

/** A row as it exists in the app's domain DB, reduced to the synced projection. */
data class DomainRow(
    val key: String,
    /** Same shape [SyncAdapter.project] produces from a payload; compared via [CanonicalJson.hash]. */
    val projection: JsonObject,
    /** The row's own last-change time (e.g. updatedAt), or null for timestamp-less kinds (settings). */
    val rowTs: Long?,
)

enum class SkipReason {
    /** The domain row changed since the engine last saw it (user edit mid-cycle); the next capture handles it. */
    CHANGED_LOCALLY,

    /** A row with this id belongs to a different local profile; never re-parent it. */
    OWNED_ELSEWHERE,

    /** An apply policy declined (bootstrap-only setting, unsafe language switch, unknown enum value...). */
    POLICY,

    /** Payload not understood by this app version. */
    UNSUPPORTED,
}

sealed interface ApplyOutcome {
    /** Written (or deleted); [localHash] = hash of the projection read back, null when the row is now absent. */
    data class Applied(val localHash: String?) : ApplyOutcome

    /** Not written; [localHash] = hash of the current local projection, null when no local row exists. */
    data class Skipped(val reason: SkipReason, val localHash: String?) : ApplyOutcome
}

/**
 * Bridge between one synced kind and the domain DB. Implementations must be
 * pure projections: never sync a field that is not listed in the spec's
 * allowlist (credentials, API keys, device-local sync flags...).
 */
interface SyncAdapter {
    val kind: String
    val kindVersion: Int
    val fileClass: FileClass

    /** true: scope = syncProfileId and rows are read/written under the bound local profile. false: global scope. */
    val perAccount: Boolean

    /** Only kinds whose rows the user can delete in-app produce tombstones (song_note). */
    val propagatesDeletes: Boolean

    val conflictPolicy: ConflictPolicy

    /**
     * Every domain row for this scope. [localProfileId] is null for global kinds.
     * Must THROW on any read error — a partially read table must never look like deletions.
     */
    suspend fun readAll(localProfileId: String?): List<DomainRow>

    /** Projection of a replica payload, same shape as [DomainRow.projection]; null if malformed/unusable. */
    fun project(payload: JsonObject): JsonObject?

    /**
     * Write [payload] for [key] under [localProfileId] inside one domain transaction:
     * re-read the row, return [ApplyOutcome.Skipped] with [SkipReason.CHANGED_LOCALLY] if its projection hash
     * differs from [expectedLocalHash] (null expected = "no row should exist yet"), otherwise write
     * (setting updatedAt = [versionTs] where the entity has one) and return the read-back hash.
     */
    suspend fun apply(
        localProfileId: String?,
        key: String,
        payload: JsonObject,
        versionTs: Long,
        expectedLocalHash: String?,
    ): ApplyOutcome

    /** Delete [key] under [localProfileId] (same expected-hash guard). Only called for kinds that propagate deletes, or to purge a scope. */
    suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome

    /** [ConflictPolicy.KEEP_BOTH_TEXT] only: payload keeping both texts, or null when no merge is needed. */
    fun mergeConcurrent(winner: JsonObject, loser: JsonObject, loserLabel: String): JsonObject? = null

    /** Hook to stamp extra, non-projected fields onto a payload this device writes (e.g. account deviceName). */
    fun decoratePayload(payload: JsonObject, deviceName: String): JsonObject = payload
}

/** A local profile currently bound (ACTIVE) to a sync scope. */
data class ActiveBinding(
    val localProfileId: String,
    val syncProfileId: String,
    val provider: String,
)

// ---- Transport (Drive appDataFolder)

data class RemoteFile(
    val id: String,
    val name: String,
    val version: Long,
    val modifiedTime: Long?,
    val size: Long?,
    val appProperties: Map<String, String>,
)

data class GoogleAccountInfo(
    /** Stable Google identity for this user (Drive about.user.permissionId). */
    val permissionId: String,
    val email: String?,
    val displayName: String?,
)

/** Failures the sync manager maps to user-visible states. */
sealed class SyncTransportException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 401 that survived one token refresh, or insufficientPermissions. */
    class Unauthorized(message: String) : SyncTransportException(message)

    /** OAuth client / Drive API not set up for this build (403 accessNotConfigured, DEVELOPER_ERROR). */
    class Misconfigured(message: String) : SyncTransportException(message)

    class StorageFull(message: String) : SyncTransportException(message)

    class RateLimited(message: String, val retryAfterMs: Long?) : SyncTransportException(message)

    class Network(message: String, cause: Throwable? = null) : SyncTransportException(message, cause)

    /** 409 on create with a pre-allocated id: the file already exists. */
    class AlreadyExists(message: String) : SyncTransportException(message)

    /** Anything else from the server (5xx, unexpected 4xx). */
    class Server(val code: Int, message: String) : SyncTransportException(message)
}

/** Supplies OAuth access tokens; [invalidate] drops a token the server rejected. */
interface SyncTokenProvider {
    suspend fun token(): String

    suspend fun invalidate(token: String)
}

interface SyncTransport {
    /** Every file in appDataFolder (all pages). */
    suspend fun list(): List<RemoteFile>

    /** File content, or null when the file no longer exists (404). */
    suspend fun download(fileId: String): ByteArray?

    /** Create with a pre-allocated [fileId] (from [generateIds]); a 409 means it already exists -> callers fall back to [update]. */
    suspend fun create(
        fileId: String,
        name: String,
        appProperties: Map<String, String>,
        mimeType: String,
        bytes: ByteArray,
    ): RemoteFile

    /** Replace content; null when the file no longer exists (404). */
    suspend fun update(fileId: String, bytes: ByteArray): RemoteFile?

    /** true if deleted or already absent. */
    suspend fun delete(fileId: String): Boolean

    suspend fun generateIds(count: Int): List<String>

    suspend fun about(): GoogleAccountInfo
}
