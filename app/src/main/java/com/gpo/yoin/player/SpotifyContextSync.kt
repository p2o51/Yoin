package com.gpo.yoin.player

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.ActivityContext

/**
 * What the "playing from" context becomes when Spotify reports [reported]; null keeps [current].
 *
 * [startedInYoin] says [current] is the context a Yoin play set (album / playlist / artist page,
 * a memory, …), not one read from Spotify. Spotify knows nothing about where such a queue came
 * from whenever it was started as a plain `uris` list (an artist page always is; an album or
 * playlist whose context start failed falls back to it), so what it reports then — no context,
 * an empty or blank uri, or one Yoin can't name — is no news and leaves Yoin's context alone.
 * Only a context Yoin can name, and a different one, replaces it: Spotify has moved on.
 *
 * A context read from Spotify (adopted, externally started playback) follows Spotify as before:
 * the named context, or none.
 */
internal fun reconcileSpotifyContext(
    current: ActivityContext,
    startedInYoin: Boolean,
    reported: SpotifyPlaybackContext?
): ActivityContext? {
    val named = deriveSpotifyActivityContext(reported)
        ?: return if (startedInYoin || current == ActivityContext.None) null else ActivityContext.None
    return named.takeUnless { spotifyContextUri(current) == spotifyContextUri(named) }
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
