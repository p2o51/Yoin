package com.gpo.yoin.data.model

data class Album(
    val id: MediaId,
    val name: String,
    val artist: String?,
    val artistId: MediaId?,
    val coverArt: CoverRef?,
    val songCount: Int?,
    val durationSec: Int?,
    val year: Int?,
    val genre: String?,
    val isStarred: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val addedAt: String? = null,
    /** What kind of release this is, when the provider says; null = unknown. */
    val releaseType: ReleaseType? = null,
    /**
     * When the album joined the user's library, as the provider dates it
     * (ISO-8601): Subsonic's `AlbumID3.created`, a saved Spotify album's
     * `added_at`, an Apple Music library album's `dateAdded`. Library sorts
     * Recently added by it. Not [addedAt], which on Subsonic is the star time
     * Home's Recently Added reads. Null where the provider gives no date.
     */
    val libraryAddedAt: String? = null,
    /**
     * Which version of the release this is, as the service names it
     * (OpenSubsonic `version`, from the files' ALBUMVERSION tag: "Deluxe",
     * "全景声"). Library data, shown as it comes, never translated. Null when
     * the service doesn't say.
     */
    val version: String? = null,
    /**
     * The release date down to the precision the service gives (Spotify
     * `release_date` + `release_date_precision`, Apple Music `releaseDate`,
     * OpenSubsonic `releaseDate` / `originalReleaseDate`). [year] stays as the
     * year every other surface reads; when both are known they agree. Null
     * when the service gives no date.
     */
    val releaseDate: ReleaseDate? = null,
    /**
     * The record label(s) as the service names them (Spotify `label`, Apple Music `recordLabel`,
     * OpenSubsonic `recordLabels`): the scrapbook's credits line shows them before asking any AI.
     * Library data, never translated; empty when the service doesn't say.
     */
    val recordLabels: List<String> = emptyList(),
    /**
     * The first release's date when the service gives it apart from this release's (OpenSubsonic
     * `originalReleaseDate`): a reissue's 首版 on the credits line. Null when unknown.
     */
    val originalReleaseDate: ReleaseDate? = null,
    /**
     * The service's own editorial notes on the album, as plain text (Apple Music `editorialNotes`:
     * `standard`, else `short`, HTML stripped). The scrapbook's About paragraph uses it before asking
     * any AI. Null when the service has none.
     */
    val editorialNotes: String? = null,
    /**
     * Explicit or clean as the service marks the release (Apple Music's own rating; on Spotify
     * derived from the tracks, [ContentRating.fromTracks]); null = unknown.
     */
    val contentRating: ContentRating? = null,
) {
    /** The service marks the release explicit: it wears the "E" badge after its name. */
    val isExplicit: Boolean
        get() = contentRating == ContentRating.Explicit

    /**
     * [name] without the " (version)" a server appends to it (Navidrome's
     * Subsonic.AppendAlbumVersion, on by default), for where [version] is shown
     * beside it as a tag; [name] itself where it doesn't end that way.
     */
    val nameWithoutVersion: String
        get() = albumNameWithoutVersion(name, version)
}

/** [name] without a trailing " ([version])"; [name] unchanged unless it ends exactly so. */
fun albumNameWithoutVersion(name: String, version: String?): String {
    val v = version?.trim()?.takeIf(String::isNotEmpty) ?: return name
    val suffix = " ($v)"
    if (!name.endsWith(suffix)) return name
    return name.removeSuffix(suffix).trimEnd().ifEmpty { name }
}

/**
 * Release kinds a provider can report (Spotify `album_type`, OpenSubsonic
 * `releaseTypes`, Apple Music's flags and " - EP" / " - Single"). Only ever set from provider data — never
 * guessed from the track count — so a null means "don't label it". The type and the readers live in :shared
 * (`com.gpo.yoin.shared.release`, golden release-marks.json).
 */
typealias ReleaseType = com.gpo.yoin.shared.release.ReleaseType

/**
 * A release date at the precision the service gave it: the year always, then
 * the month, then the day ([day] is never set without [month]). Build one with
 * [of] or [parseIso], which drop what isn't a real date (Spotify's "0000", a
 * 13th month, February 30th) instead of guessing.
 */
data class ReleaseDate(val year: Int, val month: Int? = null, val day: Int? = null) {
    init {
        require(year in 1..9999) { "year $year out of range" }
        require(month == null || month in 1..12) { "month $month out of range" }
        require(day == null || (month != null && day in 1..daysIn(year, month))) { "day $day out of range" }
    }

    companion object {
        /**
         * The date from its parts, kept as far as they make a real date: an
         * invalid month drops the month and day, an invalid day drops the day.
         * Null without a usable year (null, 0, negative).
         */
        fun of(year: Int?, month: Int? = null, day: Int? = null): ReleaseDate? {
            val y = year?.takeIf { it in 1..9999 } ?: return null
            val m = month?.takeIf { it in 1..12 } ?: return ReleaseDate(y)
            val d = day?.takeIf { it in 1..daysIn(y, m) }
            return ReleaseDate(y, m, d)
        }

        /**
         * "2023-10-27", "2023-10" or "2023" (Spotify, Apple Music), each at
         * its own precision; longer text after the day (a time) is ignored.
         * [precision] is Spotify's `release_date_precision` ("year", "month",
         * "day"): it caps the precision read from the text. Null when the text
         * holds no usable year.
         */
        fun parseIso(text: String?, precision: String? = null): ReleaseDate? {
            val parts = text?.trim()?.takeIf(String::isNotEmpty)?.take(10)?.split('-') ?: return null
            val year = parts.getOrNull(0)?.takeIf { it.length == 4 }?.toIntOrNull()
            val month = parts.getOrNull(1)?.takeIf { it.length == 2 }?.toIntOrNull()
            val day = parts.getOrNull(2)?.takeIf { it.length == 2 }?.toIntOrNull()
            return when (precision?.trim()?.lowercase()) {
                "year" -> of(year)
                "month" -> of(year, month)
                else -> of(year, month, day)
            }
        }

        private fun daysIn(year: Int, month: Int): Int = java.time.YearMonth.of(year, month).lengthOfMonth()
    }
}

/** The more precise of [this] and [other]; [this] when they are as precise (or [other] is null). */
fun ReleaseDate?.orMorePrecise(other: ReleaseDate?): ReleaseDate? {
    if (this == null) return other
    if (other == null) return this
    return if (other.precisionRank() > precisionRank()) other else this
}

private fun ReleaseDate.precisionRank(): Int = when {
    day != null -> 2
    month != null -> 1
    else -> 0
}
