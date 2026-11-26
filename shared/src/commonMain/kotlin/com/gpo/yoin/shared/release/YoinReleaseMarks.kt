package com.gpo.yoin.shared.release

/*
 * The facade over the release marks (Android PR #343, owner 2026-11-25 / 26): the Explicit "E" read from each
 * service, a release's kind (EP / Single / Compilation) read from each service, Apple Music's " - EP" /
 * " - Single" hidden where the kind is shown, Library's kind filter, the E's size and whether it fits after a
 * title, and the marks on a cover's band. Android, iOS (Swift, through YoinShared) and the HarmonyOS Dart port all
 * answer exactly as shared/src/commonTest/resources/golden/release-marks.json says.
 *
 * Swift calls it as `YoinReleaseMarks.shared.…`. Plain types only, every argument passed (Swift sees no Kotlin
 * defaults), and no lists of nullables: a release's tracks are given as counts. The platforms keep the drawing
 * (the square with its knocked-out E, the tags, the band), the strings and VoiceOver / TalkBack.
 */
object YoinReleaseMarks {

    // ── constants ───────────────────────────────────────────────────────────────────────────────────────

    const val capHeightEm = ReleaseMarkRules.CAP_HEIGHT_EM
    const val xHeightEm = ReleaseMarkRules.X_HEIGHT_EM
    const val letterShare = ReleaseMarkRules.LETTER_SHARE
    const val cornerShare = ReleaseMarkRules.CORNER_SHARE
    const val minSideDp = ReleaseMarkRules.MIN_SIDE_DP
    const val heroMaxSideDp = ReleaseMarkRules.HERO_MAX_SIDE_DP
    const val gapDp = ReleaseMarkRules.GAP_DP
    const val bandBadgeTextSp = ReleaseMarkRules.BAND_BADGE_TEXT_SP
    const val bandInsetFraction = ReleaseMarkRules.BAND_INSET_FRACTION
    const val bandMinTagWidthDp = ReleaseMarkRules.BAND_MIN_TAG_WIDTH_DP
    const val bandGapDp = ReleaseMarkRules.BAND_GAP_DP
    const val bandStickerRoomDp = ReleaseMarkRules.BAND_STICKER_ROOM_DP

    // ── Explicit / Clean ────────────────────────────────────────────────────────────────────────────────

    /** Apple Music `attributes.contentRating` ("explicit" / "clean"); anything else is unknown (null). */
    fun contentRatingFromAppleMusic(value: String?): ContentRating? = ContentRating.fromAppleMusic(value)

    /** OpenSubsonic `explicitStatus` ("explicit" / "clean"; "" or absent is unknown). */
    fun contentRatingFromOpenSubsonic(value: String?): ContentRating? = ContentRating.fromOpenSubsonic(value)

    /** Spotify's track `explicit`; null (not sent) is unknown. */
    fun contentRatingFromExplicitFlag(explicit: Boolean?): ContentRating? = ContentRating.fromExplicitFlag(explicit)

    /**
     * A release's rating from its [loadedTracks] tracks, [explicitTracks] of them explicit and [cleanTracks] clean
     * (the rest unknown): explicit as soon as one is; clean only when [complete] and every one is clean.
     */
    fun albumContentRating(explicitTracks: Int, cleanTracks: Int, loadedTracks: Int, complete: Boolean): ContentRating? =
        ContentRating.fromTracks(
            List(explicitTracks) { ContentRating.Explicit } +
                List(cleanTracks) { ContentRating.Clean } +
                List((loadedTracks - explicitTracks - cleanTracks).coerceAtLeast(0)) { null },
            complete,
        )

    /**
     * Whether [loadedTracks] are a release's whole tracklist: Spotify's `total_tracks` ([totalTracks]), else the
     * embedded page's `total` ([pageTotal]); unknown totals are never complete.
     */
    fun tracklistComplete(loadedTracks: Int, totalTracks: Int?, pageTotal: Int?): Boolean {
        val total = totalTracks ?: pageTotal
        return total != null && loadedTracks >= total
    }

    /** A stored [ContentRating] name (caches); unknown for null or a name this build doesn't know. */
    fun contentRatingFromStorage(name: String?): ContentRating? = ContentRating.fromStorage(name)

    // ── release kinds ───────────────────────────────────────────────────────────────────────────────────

    /** Spotify `album_type` (+ `total_tracks`: a "single" of 4+ tracks is an EP). */
    fun spotifyReleaseType(albumType: String?, totalTracks: Int?): ReleaseType? =
        ReleaseMarkRules.spotifyReleaseType(albumType, totalTracks)

    /** OpenSubsonic `releaseTypes`: the first recognised value wins. */
    fun subsonicReleaseType(releaseTypes: List<String>): ReleaseType? = ReleaseMarkRules.subsonicReleaseType(releaseTypes)

    /** Apple Music: `isCompilation`, `isSingle` or the name's " - Single" / " - EP" (see [ReleaseMarkRules]). */
    fun appleMusicReleaseType(name: String?, isCompilation: Boolean?, isSingle: Boolean?): ReleaseType? =
        ReleaseMarkRules.appleMusicReleaseType(name, isCompilation, isSingle)

    /** A stored [ReleaseType] name (caches); unknown for null or a name this build doesn't know. */
    fun releaseTypeFromStorage(name: String?): ReleaseType? = ReleaseType.entries.firstOrNull { it.name == name }

    /** An EP or a single wears its kind as a tag (an album is the default). */
    fun showsKindTag(type: ReleaseType?): Boolean = ReleaseMarkRules.showsKindTag(type)

    /** The kind the album page's header names: the real one, Album when unknown. */
    fun headerKind(type: ReleaseType?): ReleaseType = ReleaseMarkRules.headerKind(type)

    /** [name] without Apple Music's " - EP" / " - Single" for an EP / a single. */
    fun nameWithoutKindSuffix(name: String, type: ReleaseType?): String = ReleaseMarkRules.nameWithoutKindSuffix(name, type)

    // ── Library's kind filter ───────────────────────────────────────────────────────────────────────────

    fun filterAccepts(filter: ReleaseKindFilter, type: ReleaseType?): Boolean = filter.accepts(type)

    /**
     * The filters worth showing for the shelf's known kinds [types] (unknown kinds left out: only All has them):
     * none unless it holds at least two kinds, else All first.
     */
    fun offeredFilters(types: List<ReleaseType>): List<ReleaseKindFilter> = ReleaseKindFilter.offeredFor(types)

    // ── the E ───────────────────────────────────────────────────────────────────────────────────────────

    /** See [ReleaseMarkRules.badgeSide]. */
    fun badgeSide(fontSize: Float, hero: Boolean, minSide: Float, heroMaxSide: Float): Float =
        ReleaseMarkRules.badgeSide(fontSize, hero, minSide, heroMaxSide)

    /** See [ReleaseMarkRules.badgeGap]. */
    fun badgeGap(fontSize: Float, hero: Boolean, minSide: Float, heroMaxSide: Float, minGap: Float): Int =
        ReleaseMarkRules.badgeGap(fontSize, hero, minSide, heroMaxSide, minGap)

    /** The title keeps at least half the box, or all of itself if shorter; else the badge is left out. */
    fun explicitBadgeFits(maxWidth: Int, titleRoom: Int, naturalTitle: Int): Boolean =
        ReleaseMarkRules.explicitBadgeFits(maxWidth, titleRoom, naturalTitle)

    // ── the cover's band ────────────────────────────────────────────────────────────────────────────────

    /** See [ReleaseMarkRules.coverBand]. */
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
    ): CoverBandLayout = ReleaseMarkRules.coverBand(
        width, height, coverFraction, stickerRoom, gap, minTagWidth, kind, version, explicit,
    )
}
