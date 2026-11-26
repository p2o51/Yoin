package com.gpo.yoin.data.model

/**
 * Whether a song or a release is marked explicit, as its service says. With a
 * nullable field it is a tri-state: [Explicit], [Clean], or null = unknown
 * (the service didn't say, or Yoin didn't ask). Only [Explicit] is ever shown
 * (the "E" badge after the title, docs/design.md); [Clean] is kept so the clean
 * edition of an album is known as such rather than unknown.
 *
 * - Apple Music: `attributes.contentRating`, "explicit" or "clean"; absent is unknown.
 * - Spotify: a track's `explicit`; an album has no flag of its own, so it is
 *   explicit when any of its tracks is, clean when the whole tracklist is known
 *   and none is ([fromTracks]).
 * - Subsonic / OpenSubsonic: `explicitStatus` on songs and albums ("explicit" /
 *   "clean"; "" or absent is unknown — plain Subsonic servers never send it;
 *   Navidrome fills it from the files' ITUNESADVISORY tag).
 */
enum class ContentRating {
    Explicit,
    Clean,
    ;

    companion object {
        /** Apple Music's `contentRating` ("explicit" / "clean"); anything else, or none, is unknown. */
        fun fromAppleMusic(value: String?): ContentRating? = when (value?.trim()?.lowercase()) {
            "explicit" -> Explicit
            "clean" -> Clean
            else -> null
        }

        /** OpenSubsonic's `explicitStatus` ("explicit" / "clean"); "" or none is unknown. */
        fun fromOpenSubsonic(value: String?): ContentRating? = fromAppleMusic(value)

        /** Spotify's `explicit` flag; null (not sent) is unknown. */
        fun fromExplicitFlag(explicit: Boolean?): ContentRating? = when (explicit) {
            true -> Explicit
            false -> Clean
            null -> null
        }

        /**
         * A release's rating from its tracks' ([ratings]): explicit as soon as one is; clean only when
         * [complete] (every track of the release is in [ratings]) and each is known clean; else unknown.
         */
        fun fromTracks(ratings: List<ContentRating?>, complete: Boolean): ContentRating? = when {
            ratings.any { it == Explicit } -> Explicit
            complete && ratings.isNotEmpty() && ratings.all { it == Clean } -> Clean
            else -> null
        }

        /** The stored name (caches); unknown for null or a name this build doesn't know. */
        fun fromStorage(name: String?): ContentRating? = entries.firstOrNull { it.name == name }
    }
}
