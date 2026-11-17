package com.gpo.yoin.data.source

import com.gpo.yoin.data.source.spotify.MadeForYouPlaylist

/**
 * A service that lists the playlists it makes for the user, for Home's Made For You
 * (owner 2026-11-15). Apple Music answers it from `GET /v1/me/recommendations`; Spotify
 * reads its hub over App Remote instead (data/source/spotify/SpotifyMadeForYou.kt), since
 * that goes through the player rather than the source.
 */
interface MadeForYouSource {
    /** The playlists made for the user, in the service's order; empty when it has none. */
    suspend fun madeForYou(): List<MadeForYouPlaylist>
}
