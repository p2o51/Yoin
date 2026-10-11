package com.gpo.yoin.player

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.ActivityContext

/**
 * Keeps the "playing from" context in step with what App Remote reports (its PlayerState and
 * PlayerContext subscriptions). Each call returns the context to show, or null to keep the
 * current one. Owned by [PlaybackManager]; main thread.
 *
 * A context a Yoin play set (album / playlist / artist page, a memory, …) names the queue Yoin
 * handed Spotify, and it stands while Spotify plays that queue. Spotify knows nothing about where
 * such a queue came from whenever it started as a plain `uris` list (an artist page always does;
 * an album or playlist whose context start failed falls back to it), so what it reports then — no
 * context, an empty or blank uri, one Yoin can't name — is no news and never replaces Yoin's.
 *
 * Until Spotify plays a song of Yoin's queue the start is unconfirmed: the first PlayerState and
 * PlayerContext a cold connect delivers describe what Spotify played BEFORE the start, so nothing
 * Spotify reports counts yet. Once confirmed:
 * - an album or playlist Spotify names, and a different one, is shown; an unnamed report while
 *   Spotify is still on Yoin's queue brings Yoin's back, so a stale name doesn't stick;
 * - a song outside Yoin's queue means Spotify moved on (the user took over in the Spotify app, or
 *   it played on past the list): from then on the context follows Spotify, as for playback adopted
 *   from it — the album or playlist it last named on this connection, else none. A song with the
 *   title and artist of one in Yoin's queue is that song (a copy Spotify relinked: another
 *   market's, under another id); one Spotify relinked otherwise still plays from the album or
 *   playlist Spotify names, so Yoin's label stays;
 * - Yoin's Liked Songs starts Spotify's whole collection but mirrors only a window of it: a liked
 *   song past the window, while Spotify still says it plays the collection (`spotify:collection:
 *   tracks`, `spotify:user:<id>:collection`), is still Yoin's start, not a move.
 */
internal class SpotifyContextSync {

    /** The context a Yoin play set, while Spotify plays its queue; null while following Spotify. */
    private var yoinContext: ActivityContext? = null

    /** Spotify has played a song of Yoin's queue since the start. */
    private var confirmed = false

    /** What Spotify last said it plays from, on this connection; null once it drops. */
    private var reported: SpotifyPlaybackContext? = null

    /** A Yoin play set [context] and handed Spotify (or a local player) its queue. */
    fun onYoinStart(context: ActivityContext) {
        yoinContext = context
        confirmed = false
    }

    /** Playback Spotify started on its own was adopted: its context is Spotify's to name. */
    fun followSpotify() {
        yoinContext = null
        confirmed = false
    }

    /** The App Remote connection dropped (host stopped, an error): Spotify may move on unheard. */
    fun onDisconnected() {
        reported = null
    }

    /** Spotify reported playing [track] while Yoin mirrors [queue] for it. */
    fun onPlayerState(current: ActivityContext, track: Track?, queue: List<Track>): ActivityContext? {
        val yoin = yoinContext ?: return null
        track ?: return null
        if (queue.any { queued -> queued.isSameSongAs(track) }) {
            if (!confirmed) {
                confirmed = true
                // Said before the start landed: about what Spotify played before, unless it
                // already names Yoin's own album, playlist or Liked Songs.
                if (!reported.names(yoin)) reported = null
            }
            return null
        }
        // A song from before the start, still playing while the start lands.
        if (!confirmed) return null
        // Past Yoin's window of Liked Songs, still in the collection Yoin started.
        if (yoin is ActivityContext.LikedSongs && reported.names(yoin)) return null
        followSpotify()
        val next = deriveSpotifyActivityContext(reported) ?: ActivityContext.None
        return next.takeUnless { it.namesSameAs(current) }
    }

    /** Spotify reported playing from [context] (App Remote's PlayerContext). */
    fun onReported(current: ActivityContext, context: SpotifyPlaybackContext?): ActivityContext? {
        reported = context
        val yoin = yoinContext
        val next = when {
            yoin == null -> deriveSpotifyActivityContext(context) ?: ActivityContext.None
            !confirmed -> return null
            else -> deriveSpotifyActivityContext(context)?.takeUnless { it.namesSameAs(yoin) } ?: yoin
        }
        return next.takeUnless { it.namesSameAs(current) }
    }
}

/**
 * Spotify's report names [yoin]: the same album or playlist, or (Yoin's Liked Songs) the user's
 * collection, which Spotify calls by more than one uri.
 */
private fun SpotifyPlaybackContext?.names(yoin: ActivityContext): Boolean =
    deriveSpotifyActivityContext(this)?.namesSameAs(yoin) == true ||
        (yoin is ActivityContext.LikedSongs && isSpotifyLikedSongsUri(this?.uri))

/**
 * Spotify's Liked Songs collection: `spotify:collection:tracks` (what Yoin starts), and the forms
 * Spotify reports back, `spotify:collection`, `spotify:user:<id>:collection` and
 * `spotify:user:<id>:collection:tracks`. Not a collection's other shelves (episodes, an artist's
 * liked songs).
 */
internal fun isSpotifyLikedSongsUri(uri: String?): Boolean {
    val parts = uri?.trim()?.split(':') ?: return false
    if (parts.firstOrNull() != "spotify") return false
    val rest = if (parts.getOrNull(1) == "user") {
        if (parts.getOrNull(2).isNullOrBlank()) return false
        parts.drop(3)
    } else {
        parts.drop(1)
    }
    return rest == listOf("collection") || rest == listOf("collection", "tracks")
}

/** The same song: the same id, or (a relinked copy) the same title and artist. */
private fun Track.isSameSongAs(other: Track): Boolean {
    if (id == other.id) return true
    val title = title?.takeIf { it.isNotBlank() } ?: return false
    val artist = artist?.takeIf { it.isNotBlank() } ?: return false
    return title.equals(other.title, ignoreCase = true) && artist.equals(other.artist, ignoreCase = true)
}

/** Map a Spotify "playing from" context to an [ActivityContext]; null if unrecognised. */
internal fun deriveSpotifyActivityContext(context: SpotifyPlaybackContext?): ActivityContext? {
    val uri = context?.uri?.trim().orEmpty()
    val title = (context?.title?.takeIf { it.isNotBlank() }).orEmpty()
    return when {
        uri.startsWith(SPOTIFY_ALBUM_URI_PREFIX) ->
            uri.removePrefix(SPOTIFY_ALBUM_URI_PREFIX).takeIf { it.isNotBlank() }?.let {
                ActivityContext.Album(albumId = MediaId.spotify(it).toString(), albumName = title)
            }
        uri.startsWith(SPOTIFY_PLAYLIST_URI_PREFIX) ->
            uri.removePrefix(SPOTIFY_PLAYLIST_URI_PREFIX).takeIf { it.isNotBlank() }?.let {
                ActivityContext.Playlist(playlistId = MediaId.spotify(it).toString(), playlistName = title)
            }
        else -> null
    }
}

/**
 * Same context: equal, or the same Spotify album or playlist (Spotify's own reading of Yoin's
 * album carries a bare title; Yoin's keeps its artist and cover).
 */
private fun ActivityContext.namesSameAs(other: ActivityContext): Boolean =
    this == other || spotifyContextUri(this)?.let { it == spotifyContextUri(other) } == true

/** URI of the Spotify album / playlist an [ActivityContext] names, to compare against Spotify's. */
private fun spotifyContextUri(context: ActivityContext): String? = when (context) {
    is ActivityContext.Album -> spotifyRawId(context.albumId)?.let { "$SPOTIFY_ALBUM_URI_PREFIX$it" }
    is ActivityContext.Playlist -> spotifyRawId(context.playlistId)?.let { "$SPOTIFY_PLAYLIST_URI_PREFIX$it" }
    else -> null
}

private fun spotifyRawId(id: String): String? =
    MediaId.parseOrNull(id)?.takeIf { it.provider == MediaId.PROVIDER_SPOTIFY }?.rawId

private const val SPOTIFY_ALBUM_URI_PREFIX = "spotify:album:"
private const val SPOTIFY_PLAYLIST_URI_PREFIX = "spotify:playlist:"
