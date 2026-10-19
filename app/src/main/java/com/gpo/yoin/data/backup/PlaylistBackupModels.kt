package com.gpo.yoin.data.backup

import com.gpo.yoin.data.local.PlaylistBackup
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.PlaylistUnreadableItem
import com.gpo.yoin.data.model.Track
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Playlist backups (docs/playlist-transfer.md §7): a playlist kept as plain
 * metadata, to be restored into any profile later. Explicit @SerialName
 * everywhere: the JSON lives in Room and in the user's Drive (sync kind
 * playlist_backup), so field names are a wire format.
 */

/** What a backup holds besides its list columns: [PlaylistBackup.contentJson]. */
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
    /** The track's [MediaId] on the service it was backed up from. */
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

        fun of(reason: PlaylistUnreadableItem.Reason): UnexportedReason = when (reason) {
            PlaylistUnreadableItem.Reason.LocalFile -> LocalFile
            PlaylistUnreadableItem.Reason.Episode -> Episode
            PlaylistUnreadableItem.Reason.Video -> Video
            PlaylistUnreadableItem.Reason.Unavailable -> Unavailable
        }
    }
}

/** One restore of a backup: when, into which profile, and what came of it. Newest last in the stored list. */
@Serializable
data class PlaylistRestoreRecord(
    @SerialName("at") val at: Long,
    @SerialName("targetProvider") val targetProvider: String,
    /** The target profile's name at the time. */
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

/** A backup as the app reads it: the row's columns with its JSON decoded. */
data class PlaylistBackupRecord(
    val id: String,
    val name: String,
    val sourceProvider: String,
    val sourceProfileLabel: String,
    val sourcePlaylistId: String,
    /** This device's profile that follows the playlist; null when none here does. */
    val sourceProfileId: String?,
    /** The service's own date for the playlist (ISO-8601), when it gives one. */
    val playlistCreatedAt: String?,
    val firstBackedUpAt: Long,
    val lastBackedUpAt: Long,
    val lastChangedAt: Long,
    /** When the playlist was found gone; null while it is still followed. */
    val sourceGoneAt: Long?,
    val content: PlaylistBackupContent,
    val restores: List<PlaylistRestoreRecord>,
    val updatedAt: Long,
) {
    val trackCount: Int get() = content.tracks.size
    val unexportedCount: Int get() = content.unexported.size

    /** Still backed up as it changes: the playlist is there. */
    val following: Boolean get() = sourceGoneAt == null
}

object PlaylistBackupCodec {
    /** Encodes defaults so every device writes the same bytes for the same backup (sync hashes them). */
    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    fun encodeContent(content: PlaylistBackupContent): String =
        json.encodeToString(PlaylistBackupContent.serializer(), content)

    fun decodeContent(text: String): PlaylistBackupContent =
        json.decodeFromString(PlaylistBackupContent.serializer(), text)

    fun encodeRestores(restores: List<PlaylistRestoreRecord>): String =
        json.encodeToString(RESTORES, restores)

    fun decodeRestores(text: String): List<PlaylistRestoreRecord> = json.decodeFromString(RESTORES, text)

    fun toEntity(record: PlaylistBackupRecord): PlaylistBackup = PlaylistBackup(
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
        trackCount = record.trackCount,
        unexportedCount = record.unexportedCount,
        contentJson = encodeContent(record.content),
        restoresJson = encodeRestores(record.restores),
        updatedAt = record.updatedAt,
    )

    /** Null when the row's JSON can't be read (a newer, incompatible shape). */
    fun fromEntity(row: PlaylistBackup): PlaylistBackupRecord? = runCatching {
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
        )
    }.getOrNull()

    private val RESTORES = kotlinx.serialization.builtins.ListSerializer(PlaylistRestoreRecord.serializer())
}

/** Turns a playlist read from its service into a backup, and a backup back into tracks to match. */
object PlaylistSnapshots {
    /**
     * The start of [playlist]'s cloud backup, made [now]: read whole from the
     * profile [profileId] ([profileLabel]) on [provider].
     */
    fun backup(
        id: String,
        playlist: Playlist,
        provider: String,
        profileId: String?,
        profileLabel: String,
        now: Long,
    ): PlaylistBackupRecord = PlaylistBackupRecord(
        id = id,
        name = playlist.name,
        sourceProvider = provider,
        sourceProfileLabel = profileLabel,
        sourcePlaylistId = playlist.id.toString(),
        sourceProfileId = profileId,
        playlistCreatedAt = playlist.libraryAddedAt?.takeIf(String::isNotBlank),
        firstBackedUpAt = now,
        lastBackedUpAt = now,
        lastChangedAt = now,
        sourceGoneAt = null,
        content = content(playlist),
        restores = emptyList(),
        updatedAt = now,
    )

    /**
     * [backup] brought up to date with [playlist] as read [now]: the
     * contents and name replaced, [PlaylistBackupRecord.lastChangedAt] moved
     * only when they differ, and following again if it had been found gone.
     */
    fun refreshed(
        backup: PlaylistBackupRecord,
        playlist: Playlist,
        profileId: String?,
        profileLabel: String,
        now: Long,
    ): PlaylistBackupRecord {
        val content = content(playlist)
        val changed = content != backup.content || playlist.name != backup.name
        return backup.copy(
            name = playlist.name,
            sourceProfileLabel = profileLabel,
            sourceProfileId = profileId ?: backup.sourceProfileId,
            playlistCreatedAt = playlist.libraryAddedAt?.takeIf(String::isNotBlank) ?: backup.playlistCreatedAt,
            lastBackedUpAt = now,
            lastChangedAt = if (changed) now else backup.lastChangedAt,
            sourceGoneAt = null,
            content = content,
            updatedAt = maxOf(now, backup.updatedAt + 1),
        )
    }

    /**
     * What [playlist] holds, as a backup keeps it. Every track keeps its
     * title, credited artists, album, length, ISRC and id; a track with no
     * title can't be matched anywhere, so it joins
     * [PlaylistBackupContent.unexported] as unavailable, next to what the
     * service itself couldn't read.
     */
    fun content(playlist: Playlist): PlaylistBackupContent {
        val tracks = ArrayList<BackupTrack>(playlist.tracks.size)
        val untitled = ArrayList<BackupUnexported>()
        playlist.tracks.forEachIndexed { index, track ->
            val title = track.title?.trim().orEmpty()
            if (title.isEmpty()) {
                untitled += BackupUnexported(
                    position = index,
                    artist = track.artist,
                    reason = UnexportedReason.Unavailable.wireName,
                )
            } else {
                tracks += BackupTrack(
                    title = title,
                    artists = track.creditedArtists,
                    album = track.album?.trim()?.takeIf(String::isNotEmpty),
                    durationSec = track.durationSec?.takeIf { it > 0 },
                    isrc = track.extras[Track.EXTRA_ISRC]?.trim()?.takeIf(String::isNotEmpty),
                    sourceTrackId = track.id.toString(),
                )
            }
        }
        val unreadable = playlist.unreadable.map { item ->
            BackupUnexported(
                position = item.position,
                title = item.title,
                artist = item.artist,
                reason = UnexportedReason.of(item.reason).wireName,
            )
        }
        return PlaylistBackupContent(
            description = playlist.comment?.trim()?.takeIf(String::isNotEmpty),
            tracks = tracks,
            // Untitled tracks' places are among the read tracks, the service's among all entries: the service's
            // list is the one ordered by the playlist as the user sees it, so it leads.
            unexported = unreadable.sortedBy { it.position } + untitled,
        )
    }

    /**
     * [track] as a [Track] for [com.gpo.yoin.data.transfer.PlaylistTransfer.resolve]:
     * the artists joined with ", " (the matcher splits credits on it), the
     * ISRC in [Track.EXTRA_ISRC].
     */
    fun toTrack(track: BackupTrack): Track = Track(
        id = MediaId.parseOrNull(track.sourceTrackId) ?: MediaId(MediaId.PROVIDER_SUBSONIC, track.sourceTrackId),
        title = track.title,
        artist = track.artists.joinToString(", ").takeIf(String::isNotEmpty),
        artistId = null,
        album = track.album,
        albumId = null,
        coverArt = null,
        durationSec = track.durationSec,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
        extras = buildMap {
            track.isrc?.let { put(Track.EXTRA_ISRC, it) }
            if (track.artists.size > 1) put(Track.EXTRA_ARTISTS, Track.joinArtists(track.artists))
        },
    )
}
