package com.gpo.yoin.shared.backup

import com.gpo.yoin.shared.transfer.MatchTrack

/*
 * Port of Android's PlaylistSnapshots (data/backup/PlaylistBackupModels.kt). Android reads a `Playlist`
 * of `Track`s; the iOS app has its own models, so a playlist comes in as [SnapshotPlaylist]: exactly the
 * fields Android's snapshot reads, under the same rules.
 */

/** One playlist entry as its service lists it. [id] is `provider:rawId` (Android `MediaId.toString`). */
data class SnapshotTrack(
    val id: String,
    val title: String?,
    /** Every credited artist (Android `Track.creditedArtists`): the service's list, else the one artist. */
    val artists: List<String>,
    val album: String?,
    val durationSec: Int?,
    val isrc: String?,
)

/** An entry the service couldn't read (Android `PlaylistUnreadableItem`); [reason] is an [UnexportedReason]. */
data class SnapshotUnreadable(
    val position: Int,
    val title: String?,
    val artist: String?,
    val reason: UnexportedReason,
)

/** A playlist read whole from its service. [id] is `provider:rawId`. */
data class SnapshotPlaylist(
    val id: String,
    val name: String,
    /** The service's description (Android `Playlist.comment`). */
    val comment: String?,
    /** When it was made or joined the library, ISO-8601 (Android `Playlist.libraryAddedAt`). */
    val createdAt: String?,
    val tracks: List<SnapshotTrack>,
    val unreadable: List<SnapshotUnreadable> = emptyList(),
)

/** Turns a playlist read from its service into a backup, and a backup back into tracks to match. */
object PlaylistSnapshots {
    /** Versions a backup keeps, the current one included (owner 2026-10-19: only the latest few). */
    const val KEEP_VERSIONS = 5

    /** The start of [playlist]'s cloud backup, made [now]: read whole from [profileId] ([profileLabel]) on [provider]. */
    fun backup(
        id: String,
        playlist: SnapshotPlaylist,
        provider: String,
        profileId: String?,
        profileLabel: String,
        now: Long,
    ): PlaylistBackupRecord = PlaylistBackupRecord(
        id = id,
        name = playlist.name,
        sourceProvider = provider,
        sourceProfileLabel = profileLabel,
        sourcePlaylistId = playlist.id,
        sourceProfileId = profileId,
        playlistCreatedAt = playlist.createdAt?.takeIf(String::isNotBlank),
        firstBackedUpAt = now,
        lastBackedUpAt = now,
        lastChangedAt = now,
        sourceGoneAt = null,
        content = content(playlist),
        restores = emptyList(),
        updatedAt = now,
    )

    /**
     * [backup] brought up to date with [playlist] as read [now]: the contents and name replaced,
     * lastChangedAt moved only when they differ, and following again if it had been found gone.
     */
    fun refreshed(
        backup: PlaylistBackupRecord,
        playlist: SnapshotPlaylist,
        profileId: String?,
        profileLabel: String,
        now: Long,
    ): PlaylistBackupRecord {
        val content = content(playlist)
        val changed = content != backup.content || playlist.name != backup.name
        // What it held until now becomes the newest earlier version (only when the songs changed).
        val versions = if (content != backup.content) {
            (listOf(BackupVersion(since = backup.lastChangedAt, until = now, content = backup.content)) + backup.versions)
                .take(KEEP_VERSIONS - 1)
        } else {
            backup.versions
        }
        return backup.copy(
            versions = versions,
            name = playlist.name,
            sourceProfileLabel = profileLabel,
            sourceProfileId = profileId ?: backup.sourceProfileId,
            playlistCreatedAt = playlist.createdAt?.takeIf(String::isNotBlank) ?: backup.playlistCreatedAt,
            lastBackedUpAt = now,
            lastChangedAt = if (changed) now else backup.lastChangedAt,
            sourceGoneAt = null,
            content = content,
            updatedAt = maxOf(now, backup.updatedAt + 1),
        )
    }

    /**
     * What [playlist] holds, as a backup keeps it. Every track keeps its title, credited artists, album,
     * length, ISRC and id; a track with no title can't be matched anywhere, so it joins
     * [PlaylistBackupContent.unexported] as unavailable, next to what the service itself couldn't read.
     */
    fun content(playlist: SnapshotPlaylist): PlaylistBackupContent {
        val tracks = ArrayList<BackupTrack>(playlist.tracks.size)
        val untitled = ArrayList<BackupUnexported>()
        playlist.tracks.forEachIndexed { index, track ->
            val title = track.title?.trim().orEmpty()
            if (title.isEmpty()) {
                untitled += BackupUnexported(
                    position = index,
                    artist = track.artists.firstOrNull(),
                    reason = UnexportedReason.Unavailable.wireName,
                )
            } else {
                tracks += BackupTrack(
                    title = title,
                    artists = track.artists.map(String::trim).filter(String::isNotEmpty),
                    album = track.album?.trim()?.takeIf(String::isNotEmpty),
                    durationSec = track.durationSec?.takeIf { it > 0 },
                    isrc = track.isrc?.trim()?.takeIf(String::isNotEmpty),
                    sourceTrackId = track.id,
                )
            }
        }
        val unreadable = playlist.unreadable.map { item ->
            BackupUnexported(position = item.position, title = item.title, artist = item.artist, reason = item.reason.wireName)
        }
        return PlaylistBackupContent(
            description = playlist.comment?.trim()?.takeIf(String::isNotEmpty),
            tracks = tracks,
            // Untitled tracks' places are among the read tracks, the service's among all entries: the service's
            // list is the one ordered by the playlist as the user sees it, so it leads.
            unexported = unreadable.sortedBy { it.position } + untitled,
        )
    }

    /** [track] as a [MatchTrack] for a restore: the artists joined with ", " (the matcher splits credits on it). */
    fun toMatchTrack(track: BackupTrack): MatchTrack = MatchTrack(
        id = track.sourceTrackId,
        title = track.title,
        artist = track.artists.joinToString(", ").takeIf(String::isNotEmpty),
        album = track.album,
        durationSec = track.durationSec,
        isrc = track.isrc,
    )

    /** The provider part of a `provider:rawId` id; null when [id] has none. */
    fun providerOf(id: String): String? = id.indexOf(':').takeIf { it in 1 until id.length - 1 }?.let { id.substring(0, it) }

    /** The raw part of a `provider:rawId` id; null when [id] has none. */
    fun rawIdOf(id: String): String? = id.indexOf(':').takeIf { it in 1 until id.length - 1 }?.let { id.substring(it + 1) }
}
