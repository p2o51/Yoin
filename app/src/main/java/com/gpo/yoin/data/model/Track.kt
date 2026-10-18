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
    }
}
