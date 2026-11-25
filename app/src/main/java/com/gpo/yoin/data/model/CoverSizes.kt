package com.gpo.yoin.data.model

import android.content.res.Resources
import kotlin.math.ceil
import kotlin.math.min

/**
 * The px edge a cover is requested at (`resolveCoverUrl(ref, size)`), for the
 * places that asked for the original before: the size it is shown at (layout
 * dp × density), rounded UP to one of a few [BUCKETS] so the server can keep
 * a few resized copies and screens share cache entries. Never smaller than
 * shown, so nothing is upscaled.
 *
 * Two classes:
 * - [heroPx]: every surface that shows the playing song's or a page's main
 *   cover (Now Playing, the playback theme and its neighbour edges, the mini
 *   pill, album / playlist / artist heroes, the session artwork). They share
 *   ONE url per cover, so the theme's read warms Now Playing, the neighbours'
 *   read prefetches the next song's hero, and a skip reuses the theme already
 *   computed for the cover that just played. A square cover can never be shown
 *   larger than the display's short side, so that is its size: 1080 px on a
 *   1080×2400 phone → 1200; Pixel Tablet's 1600 px → 1600.
 * - [rowPx]: list rows (queue, track sheet header, playlist picker, an
 *   artist's songs and releases): [ROW_DP] at the display's density.
 *
 * Apple Music and Spotify ignore the size: Apple keeps the one 600 px url its
 * screens share (no more bytes than before), Spotify serves what it lists. A Subsonic
 * server serves its original when that is smaller than the size asked
 * (Navidrome does not upscale), so a small original costs what it did.
 */
object CoverSizes {
    /** Bucket edges in px. */
    val BUCKETS: IntArray = intArrayOf(160, 320, 480, 800, 1200, 1600)

    /** List-row covers are drawn at 40–56 dp; one size for all of them keeps one entry per song. */
    const val ROW_DP: Float = 56f

    /** Where display metrics can't be read (JVM tests): a phone's hero bucket. */
    private const val FALLBACK_HERO_PX = 1200
    private const val FALLBACK_DENSITY = 2.625f

    /** [px] rounded up to the next bucket; the largest bucket above it. */
    fun bucketUp(px: Int): Int = BUCKETS.firstOrNull { it >= px } ?: BUCKETS.last()

    /** The bucket for a cover drawn [dp] wide at [density]. */
    fun forDisplay(dp: Float, density: Float): Int = bucketUp(ceil(dp * density).toInt())

    /** The hero bucket for a display [widthPx] × [heightPx]: its short side, rounded up. */
    fun heroFor(widthPx: Int, heightPx: Int): Int = bucketUp(min(widthPx, heightPx))

    /** See the class doc. Read once: the display's short side doesn't change on rotation. */
    val heroPx: Int by lazy {
        displayMetrics()?.let { heroFor(it.widthPixels, it.heightPixels) } ?: FALLBACK_HERO_PX
    }

    /** See the class doc. */
    val rowPx: Int by lazy { forDisplay(ROW_DP, displayMetrics()?.density ?: FALLBACK_DENSITY) }

    private fun displayMetrics() = runCatching { Resources.getSystem().displayMetrics }
        .getOrNull()
        ?.takeIf { it.widthPixels > 0 && it.heightPixels > 0 && it.density > 0f }
}
