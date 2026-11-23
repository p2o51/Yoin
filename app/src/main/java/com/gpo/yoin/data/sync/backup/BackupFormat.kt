package com.gpo.yoin.data.sync.backup

import com.gpo.yoin.data.sync.SyncEnvelope
import com.gpo.yoin.data.sync.SyncFormat
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The "export / import everything" backup (docs/cloud-sync.md「导出与导入」): one zip,
 *
 *   manifest.json          BackupManifest — what is inside, per account, plus the snapshot's SHA-256
 *   data/snapshot.json     a SyncEnvelope (fileClass "backup"): the full record set as WireRecords,
 *                          exactly the shape Drive state files use (versions, tombstones, scopes)
 *   readable/<account>/<album>.md, readable/global/playlist-backups.md   for people, never read back
 *
 * Explicit @SerialName everywhere: the files outlive app versions.
 */
object BackupFormat {
    const val FORMAT = "yoin-backup"

    /** Bumped only for changes an older importer must refuse; new optional fields don't bump it. */
    const val FORMAT_VERSION = 1

    const val MANIFEST_PATH = "manifest.json"
    const val SNAPSHOT_PATH = "data/snapshot.json"
    const val READABLE_DIR = "readable/"
    const val READABLE_GLOBAL_DIR = "readable/global/"

    /** [SyncEnvelope.fileClass] of a backup snapshot (never a Drive file class). */
    const val SNAPSHOT_FILE_CLASS = "backup"

    /** Scope of an account that has no sync id (a Spotify / Apple Music profile never synced): device-local. */
    const val LOCAL_SCOPE_PREFIX = "local-"

    /** [BackupSource.location] values. */
    const val LOCATION_GOOGLE_DRIVE = "google-drive"
    const val LOCATION_THIS_DEVICE = "this-device"

    /** Hard limits while reading, against a hostile or corrupt zip. */
    const val MAX_MANIFEST_BYTES = 1L shl 20
    const val MAX_SNAPSHOT_BYTES = 512L shl 20

    private val FILE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** `yoin-backup-YYYY-MM-DD.zip`, the date in [zone]. */
    fun fileName(now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "yoin-backup-${FILE_DATE.format(Instant.ofEpochMilli(now).atZone(zone))}.zip"

    fun isLocalScope(scope: String): Boolean = scope.startsWith(LOCAL_SCOPE_PREFIX)

    /** A real sync scope (Subsonic/Spotify fingerprint id or Apple Music link), not global and not device-local. */
    fun isSyncScope(scope: String): Boolean = scope != SyncFormat.GLOBAL_SCOPE && !isLocalScope(scope)

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * Record kinds a backup carries besides the synced ones. They never enter the sync replica or Drive:
 * import writes them straight into the domain of the account they are mapped to.
 *
 * Album notes were one of these until they became a sync kind ([SyncKinds.ALBUM_NOTE], notes revamp):
 * same name and payload, so an older backup's `album_note` records import through the sync adapter like
 * any other synced kind, once each.
 */
object BackupKinds {
    /** `track_album_links`, key = provider␟trackId: which album a noted/rated track is on. */
    const val TRACK_ALBUM_LINK = "track_album_link"

    val ALL: Set<String> = setOf(TRACK_ALBUM_LINK)
}

/** Payload fields of [BackupKinds] records (and of album notes, which keep the same fields as a sync kind). */
internal object BackupFields {
    const val ALBUM_ID = "albumId"
    const val PROVIDER = "provider"
    const val CONTENT = "content"
    const val CREATED_AT = "createdAt"
    const val UPDATED_AT = "updatedAt"
    const val ALBUM_NAME = "albumName"
    const val ARTIST = "artist"
    const val TRACK_ID = "trackId"
    const val SOURCE = "source"
}

@Serializable
data class BackupManifest(
    @SerialName("format") val format: String = BackupFormat.FORMAT,
    @SerialName("formatVersion") val formatVersion: Int = BackupFormat.FORMAT_VERSION,
    /** Sync wire schema of the snapshot's records ([SyncFormat.SCHEMA]). */
    @SerialName("syncSchema") val syncSchema: Int = SyncFormat.SCHEMA,
    @SerialName("exportedAt") val exportedAt: Long,
    @SerialName("source") val source: BackupSource,
    @SerialName("accounts") val accounts: List<BackupAccount>,
    /** Record counts of the global scope, kind -> live records. */
    @SerialName("globalCounts") val globalCounts: Map<String, Int> = emptyMap(),
    @SerialName("snapshot") val snapshot: BackupSnapshotInfo,
)

@Serializable
data class BackupSource(
    /** [BackupFormat.LOCATION_GOOGLE_DRIVE] when Cloud sync was on, else [BackupFormat.LOCATION_THIS_DEVICE]. */
    @SerialName("location") val location: String,
    @SerialName("deviceId") val deviceId: String,
    @SerialName("deviceName") val deviceName: String,
    @SerialName("appVersionName") val appVersionName: String,
    @SerialName("appVersionCode") val appVersionCode: Int,
)

/** A music account in the backup: display metadata only, the same allowlist as the sync account descriptor. */
@Serializable
data class BackupAccount(
    /** The sync scope (syncProfileId), or [BackupFormat.LOCAL_SCOPE_PREFIX] + local profile id. */
    @SerialName("scope") val scope: String,
    @SerialName("provider") val provider: String,
    @SerialName("displayName") val displayName: String,
    /** "<username> @ <host[:port]>" for Subsonic. */
    @SerialName("hint") val hint: String? = null,
    @SerialName("storefront") val storefront: String? = null,
    /** true: a profile on the exporting device; false: only known from Drive (cloud-only there). */
    @SerialName("onDevice") val onDevice: Boolean,
    /** kind -> live records of this account. */
    @SerialName("counts") val counts: Map<String, Int> = emptyMap(),
    /** Folder of this account under readable/. */
    @SerialName("readableDir") val readableDir: String? = null,
)

@Serializable
data class BackupSnapshotInfo(
    @SerialName("path") val path: String = BackupFormat.SNAPSHOT_PATH,
    @SerialName("sha256") val sha256: String,
    @SerialName("bytes") val bytes: Long,
    @SerialName("records") val records: Int,
    @SerialName("tombstones") val tombstones: Int,
)
