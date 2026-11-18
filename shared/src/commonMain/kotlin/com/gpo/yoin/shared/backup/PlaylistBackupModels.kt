package com.gpo.yoin.shared.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/*
 * Port of Android's data/backup/PlaylistBackupModels (docs/playlist-transfer.md §7): a playlist kept as
 * plain metadata, to be restored into any account later. Explicit @SerialName everywhere and the same
 * Json settings as Android: the JSON lives on disk and in the user's Drive (sync kind playlist_backup),
 * so field names, their order and how defaults and nulls are written are a wire format. Android and iOS
 * devices following the same playlist write the same bytes.
 */

/** What a backup holds besides its list columns: [PlaylistBackupRow.contentJson]. */
@Serializable
data class PlaylistBackupContent(
    @SerialName("description") val description: String? = null,
    @SerialName("tracks") val tracks: List<BackupTrack> = emptyList(),
    @SerialName("unexported") val unexported: List<BackupUnexported> = emptyList(),
)

/** One track of a backup, in playlist order. Metadata only: no audio, no cover. */
@Serializable
data class BackupTrack(
    @SerialName("title") val title: String,
    @SerialName("artists") val artists: List<String> = emptyList(),
    @SerialName("album") val album: String? = null,
    @SerialName("durationSec") val durationSec: Int? = null,
    @SerialName("isrc") val isrc: String? = null,
    /** The track's id on the service it was backed up from, `provider:rawId` (Android `MediaId.toString`). */
    @SerialName("sourceId") val sourceTrackId: String,
)

/** An entry of the playlist that couldn't be backed up, and why. */
@Serializable
data class BackupUnexported(
    /** The entry's place among all of the playlist's entries. */
    @SerialName("position") val position: Int,
    @SerialName("title") val title: String? = null,
    @SerialName("artist") val artist: String? = null,
    /** An [UnexportedReason.wireName]; unknown names read as [UnexportedReason.Unavailable]. */
    @SerialName("reason") val reason: String,
) {
    val reasonKind: UnexportedReason get() = UnexportedReason.fromWire(reason)
}

enum class UnexportedReason(val wireName: String) {
    LocalFile("local_file"),
    Episode("episode"),
    Video("video"),
    Unavailable("unavailable"),
    ;

    companion object {
        fun fromWire(name: String): UnexportedReason = entries.firstOrNull { it.wireName == name } ?: Unavailable
    }
}

/** An earlier version of a backed-up playlist: what it held until [until], when it changed. */
@Serializable
data class BackupVersion(
    /** When this version began (the backup's lastChangedAt at the time). */
    @SerialName("since") val since: Long,
    /** When the playlist changed away from it. */
    @SerialName("until") val until: Long,
    @SerialName("content") val content: PlaylistBackupContent,
)

/** One restore of a backup: when, into which account, and what came of it. Newest last in the stored list. */
@Serializable
data class PlaylistRestoreRecord(
    @SerialName("at") val at: Long,
    @SerialName("targetProvider") val targetProvider: String,
    /** The target account's name at the time. */
    @SerialName("targetLabel") val targetLabel: String,
    /** Songs written to the new playlist. */
    @SerialName("added") val added: Int,
    /** Songs with a candidate the user left unchecked, or whose write failed. */
    @SerialName("skipped") val skipped: Int,
    /** Songs the target had nothing for. */
    @SerialName("notFound") val notFound: Int,
    /** No playlist was made. */
    @SerialName("failed") val failed: Boolean = false,
)

/**
 * A backup as it is stored (Android's `playlist_backups` row, less the derived trackCount /
 * unexportedCount): the list columns, and the snapshot, its versions and restore history as the exact
 * JSON text written or received (so a newer device's extra fields survive a round trip and every device
 * hashes the row alike). [sourceProfileId] is this device's own key of the account that follows the
 * playlist; it never syncs.
 */
data class PlaylistBackupRow(
    val id: String,
    val name: String,
    /** The provider of the service it came from (Android `MediaId.provider`). */
    val sourceProvider: String,
    /** The account's name when it was last backed up. */
    val sourceProfileLabel: String,
    /** The playlist's id on that service, `provider:rawId`. */
    val sourcePlaylistId: String,
    val sourceProfileId: String?,
    /** When the playlist was made or joined the library, as its service says (ISO-8601). */
    val playlistCreatedAt: String?,
    val firstBackedUpAt: Long,
    val lastBackedUpAt: Long,
    val lastChangedAt: Long,
    /** When the playlist was found gone from its service; null while it is there. */
    val sourceGoneAt: Long?,
    val contentJson: String,
    val versionsJson: String,
    val restoresJson: String,
    /** The last change to the row: a backup, the playlist found gone, or a restore added to its history. */
    val updatedAt: Long,
)

/** A backup as the app reads it: the row's columns with its JSON decoded. */
data class PlaylistBackupRecord(
    val id: String,
    val name: String,
    val sourceProvider: String,
    val sourceProfileLabel: String,
    val sourcePlaylistId: String,
    /** This device's account that follows the playlist; null when none here does. */
    val sourceProfileId: String?,
    val playlistCreatedAt: String?,
    val firstBackedUpAt: Long,
    val lastBackedUpAt: Long,
    val lastChangedAt: Long,
    val sourceGoneAt: Long?,
    val content: PlaylistBackupContent,
    val restores: List<PlaylistRestoreRecord>,
    val updatedAt: Long,
    /** Earlier versions, newest first. */
    val versions: List<BackupVersion> = emptyList(),
) {
    val trackCount: Int get() = content.tracks.size
    val unexportedCount: Int get() = content.unexported.size

    /** Still backed up as it changes: the playlist is there. */
    val following: Boolean get() = sourceGoneAt == null

    /** This record with the contents of its version that began at [since]; itself when there is none. */
    fun atVersion(since: Long?): PlaylistBackupRecord {
        val version = since?.let { s -> versions.firstOrNull { it.since == s } } ?: return this
        return copy(content = version.content)
    }

    /** Marked gone from its service [now]: it stops following, and stays. */
    fun markedGone(now: Long): PlaylistBackupRecord = copy(sourceGoneAt = now, updatedAt = maxOf(now, updatedAt + 1))
}

object PlaylistBackupCodec {
    /** Encodes defaults so every device writes the same bytes for the same backup (sync hashes them). */
    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    private val RESTORES = ListSerializer(PlaylistRestoreRecord.serializer())
    private val VERSIONS = ListSerializer(BackupVersion.serializer())

    fun encodeContent(content: PlaylistBackupContent): String =
        json.encodeToString(PlaylistBackupContent.serializer(), content)

    fun decodeContent(text: String): PlaylistBackupContent =
        json.decodeFromString(PlaylistBackupContent.serializer(), text)

    fun decodeContentOrNull(text: String): PlaylistBackupContent? = runCatching { decodeContent(text) }.getOrNull()

    fun encodeVersions(versions: List<BackupVersion>): String = json.encodeToString(VERSIONS, versions)

    fun decodeVersions(text: String): List<BackupVersion> = json.decodeFromString(VERSIONS, text)

    fun decodeVersionsOrNull(text: String): List<BackupVersion>? = runCatching { decodeVersions(text) }.getOrNull()

    fun encodeRestores(restores: List<PlaylistRestoreRecord>): String = json.encodeToString(RESTORES, restores)

    fun decodeRestores(text: String): List<PlaylistRestoreRecord> = json.decodeFromString(RESTORES, text)

    fun decodeRestoresOrNull(text: String): List<PlaylistRestoreRecord>? =
        runCatching { decodeRestores(text) }.getOrNull()

    fun toRow(record: PlaylistBackupRecord): PlaylistBackupRow = PlaylistBackupRow(
        id = record.id,
        name = record.name,
        sourceProvider = record.sourceProvider,
        sourceProfileLabel = record.sourceProfileLabel,
        sourcePlaylistId = record.sourcePlaylistId,
        sourceProfileId = record.sourceProfileId,
        playlistCreatedAt = record.playlistCreatedAt,
        firstBackedUpAt = record.firstBackedUpAt,
        lastBackedUpAt = record.lastBackedUpAt,
        lastChangedAt = record.lastChangedAt,
        sourceGoneAt = record.sourceGoneAt,
        contentJson = encodeContent(record.content),
        versionsJson = encodeVersions(record.versions),
        restoresJson = encodeRestores(record.restores),
        updatedAt = record.updatedAt,
    )

    /** Null when the row's JSON can't be read (a newer, incompatible shape). */
    fun fromRow(row: PlaylistBackupRow): PlaylistBackupRecord? = runCatching {
        PlaylistBackupRecord(
            id = row.id,
            name = row.name,
            sourceProvider = row.sourceProvider,
            sourceProfileLabel = row.sourceProfileLabel,
            sourcePlaylistId = row.sourcePlaylistId,
            sourceProfileId = row.sourceProfileId,
            playlistCreatedAt = row.playlistCreatedAt,
            firstBackedUpAt = row.firstBackedUpAt,
            lastBackedUpAt = row.lastBackedUpAt,
            lastChangedAt = row.lastChangedAt,
            sourceGoneAt = row.sourceGoneAt,
            content = decodeContent(row.contentJson),
            restores = decodeRestores(row.restoresJson),
            updatedAt = row.updatedAt,
            versions = decodeVersions(row.versionsJson),
        )
    }.getOrNull()

    /**
     * [row] with [record] added to the end of its restore history, made [now] (Android
     * `PlaylistBackupStore.recordRestore`): only the restores and updatedAt change, the other JSON stays
     * as it was. updatedAt only moves forward, even under a clock that went back since the last change.
     */
    fun withRestore(row: PlaylistBackupRow, record: PlaylistRestoreRecord, now: Long): PlaylistBackupRow {
        val history = decodeRestoresOrNull(row.restoresJson).orEmpty()
        return row.copy(restoresJson = encodeRestores(history + record), updatedAt = maxOf(now, row.updatedAt + 1))
    }

    /** [row] followed on this device by [profileId] (null: by no account here); the synced columns stay. */
    fun withProfile(row: PlaylistBackupRow, profileId: String?): PlaylistBackupRow = row.copy(sourceProfileId = profileId)
}
