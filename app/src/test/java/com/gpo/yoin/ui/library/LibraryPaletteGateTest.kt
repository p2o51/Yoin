package com.gpo.yoin.ui.library

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.gpo.yoin.ui.component.AlbumCard
import com.gpo.yoin.ui.component.ExpressiveBackdropPaletteCache
import com.gpo.yoin.ui.theme.YoinTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Library's album grid takes its covers' colours (owner 2026-11-26) through Home's scroll gate
 * ([libraryColorGate]): no cover is read for its colour while the grid scrolls, and the covers on
 * screen are read once it rests. The grid is Library's (3 columns on a phone, [AlbumCard] with the Bun
 * backdrop); covers come from a fake image loader, so the palette passes are real but nothing is
 * fetched.
 *
 * [measureFrameTimes] (only with `YOIN_MEASURE_LIBRARY_PALETTE=1`) prints rough Robolectric frame
 * times for the grid tint-less (before), gated (after) and ungated (search's way); a device trace is
 * the real check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class LibraryPaletteGateTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var originalLoader: ImageLoader? = null
    private var loader: ImageLoader? = null

    /** Palette reads (the backdrop's request: software bitmap) vs. the covers themselves. */
    private val paletteReads = AtomicInteger()

    private enum class Mode { Off, Gated, Ungated }

    @Before
    fun answerWithCovers() {
        ExpressiveBackdropPaletteCache.clearForTest()
        originalLoader = SingletonImageLoader.get(context)
        loader = ImageLoader.Builder(context).memoryCache(null).diskCache(null).components {
            add(
                Interceptor { chain ->
                    if (!chain.request.allowHardware) paletteReads.incrementAndGet()
                    val seed = chain.request.data.toString().hashCode()
                    SuccessResult(cover(seed).asImage(), chain.request, DataSource.NETWORK)
                },
            )
        }.build().also(SingletonImageLoader::setUnsafe)
    }

    @After
    fun restore() {
        originalLoader?.let(SingletonImageLoader::setUnsafe)
        loader?.shutdown()
        ExpressiveBackdropPaletteCache.clearForTest()
    }

    /** A 200px cover in four colour blocks, different for every album, so each palette pass has work. */
    private fun cover(seed: Int): Bitmap {
        val size = 200
        val colours = IntArray(4) { i ->
            val h = ((seed ushr (i * 7)) and 0x1FF) % 360
            android.graphics.Color.HSVToColor(floatArrayOf(h.toFloat(), 0.4f + 0.15f * i, 0.35f + 0.15f * i))
        }
        val pixels = IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            colours[(if (x < size / 2) 0 else 1) + (if (y < size / 2) 0 else 2)]
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private lateinit var gridState: LazyGridState
    private lateinit var scope: CoroutineScope

    private fun showGrid(mode: () -> Mode, run: () -> Int = { 0 }) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            YoinTheme {
                key(run()) {
                    val state = rememberLazyGridState()
                    gridState = state
                    scope = rememberCoroutineScope()
                    val gate = libraryColorGate(state)
                    val current = mode()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items((0 until AlbumCount).toList(), key = { it }) { i ->
                            AlbumCard(
                                coverArtUrl = "test://library/$current/${run()}/cover/$i",
                                title = "Album $i",
                                subtitle = "Artist $i",
                                onClick = {},
                                extractBackdropColors = when (current) {
                                    Mode.Off -> false
                                    Mode.Gated -> gate.value
                                    Mode.Ungated -> true
                                },
                                fixedWidth = null,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }

    /** One frame's wall time on the main thread (composition, layout, draw), in ms. */
    private fun frame(): Double {
        val start = System.nanoTime()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        return (System.nanoTime() - start) / 1e6
    }

    /** Frames while background reads land between them (the device's other cores). */
    private fun frames(count: Int, gapMs: Long = 0): List<Double> = List(count) {
        if (gapMs > 0) Thread.sleep(gapMs)
        frame()
    }

    private fun fling(frames: Int) {
        val step = 48f * context.resources.displayMetrics.density
        scope.launch {
            gridState.scroll {
                repeat(frames) {
                    scrollBy(step)
                    withFrameNanos { }
                }
            }
        }
    }

    @Test
    fun should_readNoCoverWhileTheGridScrolls_andTheScreenfulOnceItRests() {
        showGrid(mode = { Mode.Gated })
        // The first screen rests: its covers are read.
        frames(40, gapMs = 5)
        val firstScreen = paletteReads.get()
        assertTrue("the first screen's covers are read, was $firstScreen", firstScreen >= 9)

        fling(90)
        frames(90)
        assertEquals("no cover read while scrolling", firstScreen, paletteReads.get())

        frames(60, gapMs = 5)
        val rested = paletteReads.get() - firstScreen
        val visible = gridState.layoutInfo.visibleItemsInfo.size
        // The screen's covers, and the row or two the grid composes ahead of the viewport (prefetch).
        assertTrue("the resting screen's covers are read ($rested for $visible on screen)", rested in 1..visible + 6)
    }

    @Test
    fun measureFrameTimes() {
        assumeTrue(System.getenv("YOIN_MEASURE_LIBRARY_PALETTE") == "1")
        var run by mutableIntStateOf(0)
        val order = listOf(Mode.Off, Mode.Gated, Mode.Ungated)
        val rounds = 3
        val results = mutableMapOf<Mode, MutableList<Pair<List<Double>, List<Double>>>>()
        val reads = mutableMapOf<Mode, MutableList<Pair<Int, Int>>>()
        showGrid(mode = { order[run % order.size] }, run = { run })
        repeat(rounds * order.size) { index ->
            run = index
            val mode = order[index % order.size]
            ExpressiveBackdropPaletteCache.clearForTest()
            frames(40, gapMs = 5)
            val before = paletteReads.get()
            fling(240)
            val scrolling = frames(240)
            val duringScroll = paletteReads.get() - before
            val settling = frames(90, gapMs = 5)
            val afterRest = paletteReads.get() - before - duringScroll
            results.getOrPut(mode) { mutableListOf() } += scrolling to settling
            reads.getOrPut(mode) { mutableListOf() } += duringScroll to afterRest
        }
        fun stats(values: List<Double>): String {
            val sorted = values.sorted()
            fun p(q: Double) = sorted[((sorted.size - 1) * q).toInt()]
            return "mean %.2f  p50 %.2f  p95 %.2f  max %.2f".format(sorted.average(), p(0.5), p(0.95), sorted.last())
        }
        println("Library palette frame times (Robolectric, ms/frame, rounds 2–$rounds of $rounds):")
        order.forEach { mode ->
            // Round 1 warms the JIT; the rest are pooled.
            val kept = results.getValue(mode).drop(1)
            val r = reads.getValue(mode).drop(1)
            println("  $mode scroll:  ${stats(kept.flatMap { it.first })}  palette reads ${r.map { it.first }}")
            println("  $mode settle:  ${stats(kept.flatMap { it.second })}  palette reads ${r.map { it.second }}")
        }
    }

    private companion object {
        const val AlbumCount = 600
    }
}
