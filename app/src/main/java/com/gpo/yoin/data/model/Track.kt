package com.gpo.yoin.data.model

data class Track(
    val id: MediaId,
    val title: String?,
    val artist: String?,
    val artistId: MediaId?,
    val album: String?,
    val albumId: MediaId?,
    val coverArt: CoverRef?,
    val durationSec: Int?,
    val trackNumber: Int?,
    val year: Int?,
    val genre: String?,
    val userRating: Int?,
    val isStarred: Boolean = false,
    val extras: Map<String, String> = emptyMap(),
    val addedAt: String? = null,
) {
    companion object {
        /**
         * [extras] key of the track's ISRC (International Standard Recording
         * Code), upper case, where the service names one: Spotify's
         * `external_ids.isrc`, an Apple Music catalog song's `isrc`. What a
         * copy to another service matches first (docs/playlist-transfer.md).
         */
        const val EXTRA_ISRC = "isrc"

        /**
         * [extras] key of every credited artist, when the service lists more
         * than the one in [artist] (Spotify), joined by [joinArtists]. What a
         * playlist backup keeps as the track's artists.
         */
        const val EXTRA_ARTISTS = "artists"

        private const val ARTIST_SEPARATOR = '\u001F'

        fun joinArtists(names: List<String>): String = names.joinToString(ARTIST_SEPARATOR.toString())

        fun splitArtists(joined: String): List<String> =
            joined.split(ARTIST_SEPARATOR).map(String::trim).filter(String::isNotEmpty)
    }

    /** Every credited artist: [EXTRA_ARTISTS] where the service gave them, else [artist]. */
    val creditedArtists: List<String>
        get() = extras[EXTRA_ARTISTS]?.let(::splitArtists)?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(artist?.trim()?.takeIf(String::isNotEmpty))
}
