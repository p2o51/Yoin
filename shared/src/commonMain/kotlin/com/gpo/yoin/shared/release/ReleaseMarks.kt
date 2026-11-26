package com.gpo.yoin.shared.release

import kotlin.math.roundToInt

/*
 * The marks a release or a song carries next to its name (Android PR #343, owner 2026-11-25 / 26): the
 * Explicit "E" ([ContentRating]) and the release's kind ([ReleaseType]: EP / Single / Compilation), with the
 * pure rules every platform applies to them. Android uses these types directly (its data.model names are
 * typealiases of them); iOS reaches them through [YoinReleaseMarks]; HarmonyOS ports them to Dart and replays
 * shared/src/commonTest/resources/golden/release-marks.json.
 */

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

/**
 * Release kinds a provider can report (Spotify `album_type`, OpenSubsonic
 * `releaseTypes`, Apple Music's flags and name suffix). Only ever set from provider data — never guessed from the
 * track count — so a null means "don't label it".
 */
enum class ReleaseType {
    Album,
    EP,
    Single,
    Compilation,
}

/**
 * The release-kind filter of the artist page's discography (All / Albums / Singles & EPs / Compilations), shared
 * with Library's Albums view (owner 2026-11-26: "EP 和专辑能不能试着分开？"). Kinds come from the service only
 * ([ReleaseType]); an unknown kind is only under [All].
 */
enum class ReleaseKindFilter {
    All,
    Albums,
    SinglesAndEps,
    Compilations,
    ;

    fun accepts(type: ReleaseType?): Boolean = when (this) {
        All -> true
        Albums -> type == ReleaseType.Album
        SinglesAndEps -> type == ReleaseType.Single || type == ReleaseType.EP
        Compilations -> type == ReleaseType.Compilation
    }

    companion object {
        /** The filters worth showing for [types]: none unless the list holds at least two kinds. */
        fun offeredFor(types: List<ReleaseType?>): List<ReleaseKindFilter> {
            val kinds = entries.drop(1).filter { f -> types.any { f.accepts(it) } }
            return if (kinds.size >= 2) listOf(All) + kinds else emptyList()
        }
    }
}

/** Where a mark sits on a cover's band ([ReleaseMarkRules.coverBand]). */
enum class CoverBandRole { Kind, Version, Explicit }

/** A mark to place on the band: its natural (unconstrained) width and its height, in the platform's pixels. */
data class CoverBandItem(val width: Int, val height: Int)

/** One placed mark: [x] from the band's start edge (mirror it for RTL), [y] from the top, at [width]. */
data class CoverBandPlaced(val role: CoverBandRole, val x: Int, val y: Int, val width: Int, val height: Int)

/** The marks the band shows, start to end (kind, version, E), and the band's height. */
data class CoverBandLayout(val band: Int, val start: Int, val placed: List<CoverBandPlaced>)

/** The pure rules behind the marks; [YoinReleaseMarks] is their flat facade. */
object ReleaseMarkRules {

    // ── the E's geometry (Android ExplicitBadge.kt) ──────────────────────────────────────────────────────

    /** Google Sans Flex's cap height (OS/2 sCapHeight 1432 / 2000 em): the badge's side on every title but Now Playing's. */
    const val CAP_HEIGHT_EM = 0.716f

    /** Google Sans Flex's x-height (OS/2 sxHeight 1023 / 2000 em): Now Playing's (hero) badge's side. */
    const val X_HEIGHT_EM = 0.5115f

    /** The knocked-out E's own cap height, as a share of the square. */
    const val LETTER_SHARE = 0.58f

    /** The square's corner, as a share of its side (Apple's badge is about a fifth). */
    const val CORNER_SHARE = 0.2f

    /** The square is never smaller than this (dp), so the E still reads on small text. */
    const val MIN_SIDE_DP = 9f

    /** A hero badge is never taller than this (dp), whatever the title's size. */
    const val HERO_MAX_SIDE_DP = 18f

    /** Space between a title's last glyph and the badge (dp), as the note marker's in a list row. */
    const val GAP_DP = 6f

    /** On a cover's band the E is set as if for a 17sp label: about the tags' cap height, a touch larger. */
    const val BAND_BADGE_TEXT_SP = 17f

    /**
     * The square's side for a title of [fontSize]: its cap height, at least [minSide]; [hero] (Now Playing's
     * big titles): its x-height instead, at most [heroMaxSide] (and still at least [minSide]). All in the
     * platform's pixels (Android: the font size in px, the limits in dp as px).
     */
    fun badgeSide(fontSize: Float, hero: Boolean, minSide: Float, heroMaxSide: Float): Float = if (hero) {
        (fontSize * X_HEIGHT_EM).coerceAtMost(heroMaxSide).coerceAtLeast(minSide)
    } else {
        maxOf(fontSize * CAP_HEIGHT_EM, minSide)
    }

    /**
     * Space between the title and the badge: [minGap], growing with large type (a third of [fontSize]); a [hero]
     * badge, smaller than its title's capitals, sits half its own side away instead (at least [minGap]).
     * Rounded to whole pixels.
     */
    fun badgeGap(fontSize: Float, hero: Boolean, minSide: Float, heroMaxSide: Float, minGap: Float): Int {
        val grown = if (hero) badgeSide(fontSize, true, minSide, heroMaxSide) * 0.5f else fontSize * 0.33f
        return maxOf(grown, minGap).roundToInt()
    }

    /**
     * Whether the badge may follow a title in a [maxWidth] box, leaving the title [titleRoom]: the title keeps at
     * least half the box, or all of itself if it is shorter ([naturalTitle]). On a tile too narrow for that the
     * badge is left out, so the name is never squeezed to a couple of glyphs beside it.
     */
    fun explicitBadgeFits(maxWidth: Int, titleRoom: Int, naturalTitle: Int): Boolean =
        titleRoom >= minOf(naturalTitle, maxWidth / 2)

    // ── release kinds ────────────────────────────────────────────────────────────────────────────────────

    /** Spotify `album_type`: Spotify files EPs under "single"; its own clients show a "single" with 4+ tracks as an EP. */
    fun spotifyReleaseType(albumType: String?, totalTracks: Int?): ReleaseType? = when (albumType?.lowercase()) {
        "album" -> ReleaseType.Album
        "compilation" -> ReleaseType.Compilation
        "single" -> if ((totalTracks ?: 0) >= 4) ReleaseType.EP else ReleaseType.Single
        else -> null
    }

    /** OpenSubsonic `releaseTypes`: the first recognised value wins. */
    fun subsonicReleaseType(releaseTypes: List<String>): ReleaseType? = releaseTypes.firstNotNullOfOrNull { type ->
        when (type.trim().lowercase()) {
            "album" -> ReleaseType.Album
            "ep" -> ReleaseType.EP
            "single" -> ReleaseType.Single
            "compilation" -> ReleaseType.Compilation
            else -> null
        }
    }

    /**
     * Apple Music has no release-type field: a compilation says `isCompilation`, a single `isSingle`, and an EP
     * only by its [name], which Apple ends with " - EP" (a single's with " - Single"). An album that answers
     * `isSingle` false and isn't named an EP is an album; with neither flag nor suffix, unknown. A library album
     * carries none of the flags, so the caller passes its catalog album's (each flag: the resource's own when it
     * has the key, else the catalog's).
     */
    fun appleMusicReleaseType(name: String?, isCompilation: Boolean?, isSingle: Boolean?): ReleaseType? = when {
        isCompilation == true -> ReleaseType.Compilation
        isSingle == true || name?.endsWith(" - Single") == true -> ReleaseType.Single
        name?.endsWith(" - EP") == true -> ReleaseType.EP
        isSingle == false -> ReleaseType.Album
        else -> null
    }

    /** The kind worth a tag beside a cover or a row: an EP or a single (an album is the default). */
    fun showsKindTag(type: ReleaseType?): Boolean = type == ReleaseType.EP || type == ReleaseType.Single

    /** The kind the album page's header names: the real one, Album when unknown. */
    fun headerKind(type: ReleaseType?): ReleaseType = type ?: ReleaseType.Album

    /**
     * [name] without Apple Music's " - EP" / " - Single" when the kind is shown beside it, so it isn't said
     * twice; [name] unchanged otherwise (and when nothing would be left).
     */
    fun nameWithoutKindSuffix(name: String, type: ReleaseType?): String = when (type) {
        ReleaseType.EP -> name.removeSuffix(" - EP")
        ReleaseType.Single -> name.removeSuffix(" - Single")
        else -> name
    }.ifBlank { name }

    // ── the cover's band (Android CoverBandBadges) ───────────────────────────────────────────────────────

    /** How far in from the shape's start the row may begin when it needs more than the cover's width. */
    const val BAND_INSET_FRACTION = 0.08f

    /** Below this (dp) a version tag is dropped rather than cut to an ellipsis. */
    const val BAND_MIN_TAG_WIDTH_DP = 40f

    /** Space between two marks on the band (dp). */
    const val BAND_GAP_DP = 4f

    /** Space kept free at the band's end (dp) for the memory sticker on the cover's top-end corner. */
    const val BAND_STICKER_ROOM_DP = 14f

    /**
     * The marks on the band of a [width] × [height] box whose cover sits in the bottom-end [coverFraction]: start-
     * aligned with the cover's start edge, centred in the band above it, sliding toward the shape's top-start lobe
     * (no further than [BAND_INSET_FRACTION] of the width) only as far as the row needs, and stopping as far short
     * of the end ([stickerRoom] when the cover's top-end corner holds a sticker, else 0). When the band is short
     * the marks are kept in the order E, kind, version; the version may be cut (never below [minTagWidth]), the
     * others are whole or left out, and a mark taller than the band is left out. [gap] between marks.
     */
    fun coverBand(
        width: Int,
        height: Int,
        coverFraction: Float,
        stickerRoom: Int,
        gap: Int,
        minTagWidth: Int,
        kind: CoverBandItem?,
        version: CoverBandItem?,
        explicit: CoverBandItem?,
    ): CoverBandLayout {
        val band = (height * (1f - coverFraction)).roundToInt()
        val coverStart = (width * (1f - coverFraction)).roundToInt()
        val minStart = (width * BAND_INSET_FRACTION).roundToInt()
        val end = width - maxOf(minStart, stickerRoom)
        var room = (end - minStart).coerceAtLeast(0)
        val items = mapOf(CoverBandRole.Kind to kind, CoverBandRole.Version to version, CoverBandRole.Explicit to explicit)
        val widths = mutableMapOf<CoverBandRole, Int>()
        for (role in listOf(CoverBandRole.Explicit, CoverBandRole.Kind, CoverBandRole.Version)) {
            val item = items[role] ?: continue
            val need = if (widths.isEmpty()) 0 else gap
            val left = room - need
            val w = when (role) {
                CoverBandRole.Version -> if (left >= minOf(item.width, minTagWidth)) minOf(item.width, left) else null
                else -> if (item.width <= left) item.width else null
            } ?: continue
            if (item.height > band) continue
            widths[role] = w
            room -= w + need
        }
        val row = listOf(CoverBandRole.Kind, CoverBandRole.Version, CoverBandRole.Explicit).filter { it in widths }
        val total = row.sumOf { widths.getValue(it) } + gap * (row.size - 1).coerceAtLeast(0)
        val start = (end - total).coerceIn(minStart, coverStart.coerceAtLeast(minStart))
        var x = start
        val placed = row.map { role ->
            val w = widths.getValue(role)
            val h = items.getValue(role)!!.height
            CoverBandPlaced(role, x, (band - h) / 2, w, h).also { x += w + gap }
        }
        return CoverBandLayout(band, start, placed)
    }
}
