package com.gpo.yoin.data.repository

import com.gpo.yoin.data.local.ActivityEntityLastSeen
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEventDao
import com.gpo.yoin.data.local.AlbumLastPlayed
import com.gpo.yoin.data.local.PlayHistoryDao
import com.gpo.yoin.data.model.MediaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * When this profile last opened or played each artist, album and playlist of
 * one provider, from Yoin's own records: epoch ms keyed by raw id. Library's
 * Recents sorts by it. A new device has no records, and Recents then falls
 * back to Recently added.
 */
data class LibraryRecents(
    val albums: Map<String, Long> = emptyMap(),
    val artists: Map<String, Long> = emptyMap(),
    val playlists: Map<String, Long> = emptyMap()
) {
    companion object {
        val None = LibraryRecents()
    }
}

/** [LibraryRecents] for one profile and provider, again whenever a visit or a play is recorded. */
fun interface LibraryRecentsSource {
    fun observe(profileId: String, provider: String): Flow<LibraryRecents>

    companion object {
        /** No records at all: previews and tests. */
        val None = LibraryRecentsSource { _, _ -> flowOf(LibraryRecents.None) }
    }
}

/**
 * Reads Room only: activity_events' visits and plays of an album, artist or
 * playlist (an artist page opened, an album played as an album), and
 * play_history's last play of each album, whatever the song was played from.
 * Both tables are already filtered by profile and provider; nothing new is
 * stored.
 */
class RoomLibraryRecentsSource(
    private val activityEventDao: ActivityEventDao,
    private val playHistoryDao: PlayHistoryDao
) : LibraryRecentsSource {
    override fun observe(profileId: String, provider: String): Flow<LibraryRecents> = combine(
        activityEventDao.observeEntityLastSeen(profileId, provider),
        playHistoryDao.observeAlbumLastPlayed(profileId, provider)
    ) { seen, played -> libraryRecentsOf(provider, seen, played) }
        .distinctUntilChanged()
}

/**
 * Folds the rows into one time per entity, the latest. A stored id may be a
 * raw id or a legacy `provider:rawId` ([MediaId.storedRawId]); both name the
 * same entity.
 */
internal fun libraryRecentsOf(
    provider: String,
    seen: List<ActivityEntityLastSeen>,
    played: List<AlbumLastPlayed>
): LibraryRecents {
    val albums = HashMap<String, Long>()
    val artists = HashMap<String, Long>()
    val playlists = HashMap<String, Long>()
    fun MutableMap<String, Long>.offer(storedId: String, at: Long) {
        val rawId = MediaId.storedRawId(provider, storedId)
        if (rawId.isBlank()) return
        merge(rawId, at, ::maxOf)
    }
    seen.forEach { row ->
        when (row.entityType) {
            ActivityEntityType.ALBUM.name -> albums.offer(row.entityId, row.lastAt)
            ActivityEntityType.ARTIST.name -> artists.offer(row.entityId, row.lastAt)
            ActivityEntityType.PLAYLIST.name -> playlists.offer(row.entityId, row.lastAt)
        }
    }
    played.forEach { row -> albums.offer(row.albumId, row.lastPlayedAt) }
    return LibraryRecents(albums = albums, artists = artists, playlists = playlists)
}
