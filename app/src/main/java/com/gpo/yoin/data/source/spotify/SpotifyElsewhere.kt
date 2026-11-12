package com.gpo.yoin.data.source.spotify

/**
 * What a signed-in Spotify account that is not the one in use is playing
 * (owner 2026-11-10): Home's header shows it for a moment, and a tap switches
 * to that account. Read with `GET /me/player` and the account's own stored
 * token, never through App Remote, so Spotify is not woken to find out.
 */
data class SpotifyElsewhere(
    val profileId: String,
    val trackTitle: String,
    val artistName: String,
    val coverUrl: String?,
    val isPlaying: Boolean,
)

/** The track in [this] reply as [profileId]'s, or null when nothing (or an episode, an ad) is in it. */
internal fun SpotifyPlaybackStateResponse.toElsewhere(profileId: String): SpotifyElsewhere? {
    val track = item ?: return null
    if (track.type != null && track.type != "track") return null
    return SpotifyElsewhere(
        profileId = profileId,
        trackTitle = track.name,
        artistName = track.artists.joinToString(", ") { it.name },
        coverUrl = track.album?.images?.pillImageUrl(),
        isPlaying = isPlaying,
    )
}

/** One account's answer to show, if any: one playing first, else the first paused one. */
internal fun pickSpotifyElsewhere(answers: List<SpotifyElsewhere>): SpotifyElsewhere? =
    answers.firstOrNull { it.isPlaying } ?: answers.firstOrNull()

/** The smallest image still sharp at the pill's 28dp (Spotify lists 640, 300 and 64 px). */
private fun List<SpotifyImageObject>.pillImageUrl(): String? =
    filter { (it.width ?: 0) >= PILL_MIN_IMAGE_PX }.minByOrNull { it.width ?: Int.MAX_VALUE }?.url
        ?: firstOrNull()?.url

private const val PILL_MIN_IMAGE_PX = 120
