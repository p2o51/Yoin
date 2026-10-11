package com.gpo.yoin.ui.memories

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.ui.navigation.trackCoverArtId

/**
 * The "playing from" context of a play started on this memory (a diary row or a note).
 *
 * [MemoryEntry.entityId] is the RAW id — the coordinator strips the provider prefix — while every
 * consumer of an [ActivityContext] id reads a full [MediaId] string: Spotify's start plan builds
 * `spotify:album:<raw>` from it (a raw id there dropped the album context and Spotify played a bare
 * `uris` list, so neither app showed "Playing from"), and Now Playing's label opens the album /
 * playlist / artist page with it. So the ids are recombined here, spelled as the memory's own
 * "open album" does.
 */
internal fun MemoryEntry.toPlaybackActivityContext(): ActivityContext {
    val firstSong = playbackSongs.firstOrNull()
    val coverArtId = playbackSongs.firstNotNullOfOrNull(::trackCoverArtId)

    return when (entityType) {
        MemoryEntityType.ALBUM -> ActivityContext.Album(
            albumId = entityMediaId(),
            albumName = title,
            artistName = firstSong?.artist,
            artistId = firstSong?.artistId?.toString(),
            // A cover storage key, not an entity id: the raw id is what Subsonic serves art by.
            coverArtId = coverArtId ?: entityId
        )

        MemoryEntityType.PLAYLIST -> ActivityContext.Playlist(
            playlistId = entityMediaId(),
            playlistName = title,
            // Prefer the playlist's own art. The memory's cover is a resolved
            // URL — a valid storage key on Spotify only; Subsonic resolved
            // URLs embed a rotating token, so those keep the track fallback.
            coverArtId = coverArtUrl.takeIf { entityProvider == MediaId.PROVIDER_SPOTIFY }
                ?: coverArtId
        )

        MemoryEntityType.SONG -> ActivityContext.None
    }
}

private fun MemoryEntry.entityMediaId(): String = "$entityProvider:$entityId"
