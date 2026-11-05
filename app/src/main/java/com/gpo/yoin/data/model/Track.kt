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
    /** The file's encoding as the service reports it; null where it doesn't (every service but Subsonic). */
    val audioFormat: AudioFormat? = null,
    /** Which disc of a multi-disc release the song is on, 1-based; null when the service doesn't say. */
    val discNumber: Int? = null,
    /** The song's ReplayGain as the service reports it; null where it doesn't (every service but Subsonic). */
    val replayGain: ReplayGain? = null,
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

        /**
         * [extras] key of the ids of [EXTRA_ARTISTS], in the same order and
         * joined the same way; an artist without an id is an empty entry.
         * Only where the service names each artist's page (OpenSubsonic's
         * `artists`).
         */
        const val EXTRA_ARTIST_IDS = "artistIds"

        private const val ARTIST_SEPARATOR = '\u001F'

        fun joinArtists(names: List<String>): String = names.joinToString(ARTIST_SEPARATOR.toString())

        fun splitArtists(joined: String): List<String> =
            joined.split(ARTIST_SEPARATOR).map(String::trim).filter(String::isNotEmpty)
    }

    /** Every credited artist: [EXTRA_ARTISTS] where the service gave them, else [artist]. */
    val creditedArtists: List<String>
        get() = extras[EXTRA_ARTISTS]?.let(::splitArtists)?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(artist?.trim()?.takeIf(String::isNotEmpty))

    /**
     * Every credited artist with the page each one opens, where the service
     * names them one by one ([EXTRA_ARTIST_IDS]); else the one [artist] and
     * [artistId]. An artist the service gives no id has a null [ArtistCredit.id].
     */
    val artistCredits: List<ArtistCredit>
        get() {
            val names = extras[EXTRA_ARTISTS]?.let(::splitArtists).orEmpty()
            val ids = extras[EXTRA_ARTIST_IDS]?.split(ARTIST_SEPARATOR)
            if (names.isEmpty() || ids == null || ids.size != names.size) {
                return creditedArtists.mapIndexed { index, name ->
                    ArtistCredit(name, artistId.takeIf { index == 0 })
                }
            }
            return names.mapIndexed { index, name ->
                val raw = ids[index].trim()
                ArtistCredit(name, raw.takeIf(String::isNotEmpty)?.let { MediaId(id.provider, it) })
            }
        }
}

/** One credited artist of a [Track] and, when the service names it, that artist's page. */
data class ArtistCredit(val name: String, val id: MediaId?)

/**
 * [tracks] in disc order, then track order within a disc. Songs without a
 * number keep their place relative to each other (stable), after the
 * numbered ones of their disc; without disc numbers this is track order.
 */
fun discTrackOrder(tracks: List<Track>): List<Track> =
    if (tracks.none { it.discNumber != null }) {
        tracks
    } else {
        tracks.sortedWith(
            compareBy<Track, Int?>(nullsLast()) { it.discNumber }
                .thenBy(nullsLast()) { it.trackNumber },
        )
    }
