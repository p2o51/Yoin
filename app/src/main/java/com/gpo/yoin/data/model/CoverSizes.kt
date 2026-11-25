package com.gpo.yoin.data.model

import android.content.Context
import android.content.res.Resources
import android.hardware.display.DisplayManager
import androidx.core.content.edit
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
 *   larger than a display's short side, so that is its size, for the LARGEST
 *   display this device has shown: 1080 px on a 1080×2400 phone → 1200; Pixel
 *   Tablet's 1600 px → 1600; a foldable's inner screen → 1600, folded or not
 *   ([HeroSize]). One value per device, so the url never changes with rotation
 *   or posture.
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

    private val hero = HeroSize(fallback = { systemMetrics()?.let { heroFor(it.widthPixels, it.heightPixels) } ?: FALLBACK_HERO_PX })

    /** See the class doc. */
    val heroPx: Int get() = hero.px

    /**
     * Reads every display this device has (all logical displays and their modes) and the
     * largest hero this device has needed before. Called from the first window and the
     * playback service, before any cover url is built; later calls do nothing.
     */
    fun install(context: Context) {
        if (hero.installed) return
        val app = context.applicationContext
        val displays = runCatching {
            app.getSystemService(DisplayManager::class.java)?.displays.orEmpty().flatMap { display ->
                display.supportedModes.map { it.physicalWidth to it.physicalHeight } +
                    (display.mode.physicalWidth to display.mode.physicalHeight)
            }
        }.getOrDefault(emptyList())
        hero.install(displays, PrefsHeroStore(app))
    }

    /**
     * A display this device shows now ([widthPx] × [heightPx], physical): a foldable that
     * started folded reports its inner screen here once unfolded. Only ever raises the hero.
     */
    fun noteDisplay(widthPx: Int, heightPx: Int) = hero.note(widthPx, heightPx)

    /** See the class doc. */
    val rowPx: Int by lazy { forDisplay(ROW_DP, systemMetrics()?.density ?: FALLBACK_DENSITY) }

    private fun systemMetrics() = runCatching { Resources.getSystem().displayMetrics }
        .getOrNull()
        ?.takeIf { it.widthPixels > 0 && it.heightPixels > 0 && it.density > 0f }

    private class PrefsHeroStore(context: Context) : HeroSize.Store {
        private val prefs = context.getSharedPreferences("cover_sizes", Context.MODE_PRIVATE)
        override fun read(): Int = prefs.getInt(KEY_HERO_PX, 0)
        override fun write(px: Int) = prefs.edit { putInt(KEY_HERO_PX, px) }
    }

    private const val KEY_HERO_PX = "hero_px"
}

/**
 * The device's hero bucket: the largest display short side it has (or has had: a foldable's
 * logical display reports only the panel in use, so a start folded can't see the inner one
 * until it unfolds), kept in [Store] so it holds across postures and restarts. Never lowered.
 */
internal class HeroSize(private val fallback: () -> Int) {
    interface Store {
        fun read(): Int
        fun write(px: Int)
    }

    @Volatile private var current: Int = 0
    @Volatile var installed: Boolean = false
        private set
    private var store: Store? = null

    val px: Int
        get() {
            val known = current
            if (known > 0) return known
            return synchronized(this) { current.takeIf { it > 0 } ?: fallback().also { current = it } }
        }

    /** [displays]: physical (width, height) of every display and mode the device lists. */
    @Synchronized
    fun install(displays: List<Pair<Int, Int>>, store: Store) {
        if (installed) return
        this.store = store
        val fromDisplays = displays.filter { (w, h) -> w > 0 && h > 0 }
            .maxOfOrNull { (w, h) -> CoverSizes.heroFor(w, h) } ?: 0
        val best = maxOf(current, store.read(), fromDisplays)
        if (best > 0) {
            current = best
            store.write(best)
        }
        installed = true
    }

    @Synchronized
    fun note(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        val bucket = CoverSizes.heroFor(widthPx, heightPx)
        if (bucket <= current) return
        current = bucket
        store?.write(bucket)
    }
}
