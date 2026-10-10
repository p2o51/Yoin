package com.gpo.yoin.player

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.ActivityContext

/**
 * One way to start a Yoin queue on Spotify through the Web API
 * (`PUT /me/player/play`). [spotifyStartAttempts] lists them best first;
 * the player tries each until one succeeds and only then falls back to App
 * Remote's bare `play` + `queue` path.
 *
 * Why not App Remote's `queue(uri)` loop for every list: Spotify's queue is
 * the user's own, persistent queue. Every Yoin play used to append the rest
 * of the list to it, and those leftovers then played before the next album
 * the user started — songs "out of order" on every play (owner, 2026-10-05).
 * A `uris` start is a temporary context and leaves the queue alone.
 */
internal sealed interface SpotifyStartAttempt {
    /** An album or playlist context, starting at [offsetUri] or [offsetPosition]. */
    data class Context(
        val contextUri: String,
        val offsetUri: String? = null,
        val offsetPosition: Int? = null,
    ) : SpotifyStartAttempt

    /** The user's Liked Songs collection ("playing from Liked Songs"). */
    data class LikedSongs(val offsetUri: String) : SpotifyStartAttempt

    /** A plain list, played as a temporary context. */
    data class Tracks(val uris: List<String>, val offsetPosition: Int) : SpotifyStartAttempt
}

/**
 * Liked Songs as a context. Device QA 2026-10-05: `spotify:user:<id>:collection`
 * with a URI offset is accepted by the Web API (2xx) but leaves Spotify with
 * nothing playing; this URI starts at the tapped song.
 */
internal const val SPOTIFY_LIKED_SONGS_CONTEXT_URI = "spotify:collection:tracks"

/** Most URIs one `uris` start carries; the window keeps at least a little history for "previous" ([startWindow]). */
internal const val SPOTIFY_START_MAX_URIS = 100
internal const val SPOTIFY_START_HISTORY = 20

internal fun Track.spotifyTrackUri(): String? =
    id.takeIf { it.provider == MediaId.PROVIDER_SPOTIFY }?.let { "spotify:track:${it.rawId}" }

/**
 * The Web API starts to try for playing [tracks] from [startIndex], best first.
 *
 * - Album: the album context, started at the tapped track's URI. A position
 *   can drift from Yoin's list (relinked or market-unavailable tracks, a
 *   shuffled list); a URI cannot.
 * - Playlist: the raw playlist offset Yoin can prove (playlists may repeat a
 *   track, so a position is exact there), else the URI. A shuffled list has
 *   no meaningful position, so it always uses the URI.
 * - Liked Songs: the collection context, then the plain list.
 * - Artist / none: the plain list. Spotify's artist context is artist radio,
 *   not the order Yoin shows.
 *
 * Empty when the start track isn't a Spotify track.
 */
internal fun spotifyStartAttempts(
    activityContext: ActivityContext,
    tracks: List<Track>,
    startIndex: Int,
    shuffled: Boolean,
    playlistOffset: (playlistId: MediaId, visibleIndex: Int) -> Int?,
): List<SpotifyStartAttempt> {
    val startUri = tracks.getOrNull(startIndex)?.spotifyTrackUri() ?: return emptyList()
    val list = tracksAttempt(tracks, startIndex)
    val context = when (activityContext) {
        is ActivityContext.Album -> spotifyId(activityContext.albumId)?.let { id ->
            SpotifyStartAttempt.Context(contextUri = "spotify:album:${id.rawId}", offsetUri = startUri)
        }

        is ActivityContext.Playlist -> spotifyId(activityContext.playlistId)?.let { id ->
            val position = if (shuffled) null else playlistOffset(id, startIndex)
            SpotifyStartAttempt.Context(
                contextUri = "spotify:playlist:${id.rawId}",
                offsetUri = startUri.takeIf { position == null },
                offsetPosition = position,
            )
        }

        is ActivityContext.LikedSongs -> SpotifyStartAttempt.LikedSongs(offsetUri = startUri)
        is ActivityContext.Artist, ActivityContext.None -> null
    }
    return listOfNotNull(context, list)
}

private fun spotifyId(raw: String): MediaId? =
    MediaId.parseOrNull(raw)?.takeIf { it.provider == MediaId.PROVIDER_SPOTIFY }

/** [tracks] as a `uris` start: the [startWindow] around [startIndex]. */
private fun tracksAttempt(tracks: List<Track>, startIndex: Int): SpotifyStartAttempt.Tracks? {
    val window = startWindow(tracks.size, startIndex)
    val uris = tracks.slice(window).map { it.spotifyTrackUri() ?: return null }
    return SpotifyStartAttempt.Tracks(uris = uris, offsetPosition = startIndex - window.first)
}
