package com.gpo.yoin.data.source.spotify

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track

/**
 * [Track.extras] key: the kind of item the Spotify URI behind a track named
 * (`track`, `episode`, `local`, …). Set on tracks built from what App Remote
 * reports — Spotify can be playing a podcast episode or a local file, whose
 * ids are not track ids. Tracks read from the Web API are always tracks and
 * carry no such extra.
 */
const val EXTRA_SPOTIFY_URI_TYPE = "spotify.uriType"

private const val URI_TYPE_TRACK = "track"
private val SPOTIFY_ID = Regex("[A-Za-z0-9]+")

/** The kind segment of a Spotify URI ("track" for `spotify:track:…`), or null when [uri] isn't one. */
fun spotifyUriType(uri: String?): String? {
    val parts = uri?.split(':') ?: return null
    if (parts.size < 3 || parts[0] != "spotify") return null
    return parts[1].takeIf(String::isNotBlank)
}

/**
 * The `spotify:track:` URI to ask about [track]'s Liked Songs state, or null
 * when there is nothing to ask: not a Spotify track, an episode or a local
 * file App Remote reported, or no real id (App Remote's unknown sentinel).
 */
fun spotifyTrackUriOrNull(track: Track): String? {
    if (track.id.provider != MediaId.PROVIDER_SPOTIFY) return null
    val type = track.extras[EXTRA_SPOTIFY_URI_TYPE]
    if (type != null && type != URI_TYPE_TRACK) return null
    val rawId = track.id.rawId.takeIf(SPOTIFY_ID::matches) ?: return null
    return "spotify:$URI_TYPE_TRACK:$rawId"
}
