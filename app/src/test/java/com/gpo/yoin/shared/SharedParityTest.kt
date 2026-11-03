package com.gpo.yoin.shared

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.shared.detail.ScrapbookTilt
import com.gpo.yoin.shared.reference.RefDualPaneMetrics
import com.gpo.yoin.shared.reference.RefFastScrollMath
import com.gpo.yoin.shared.reference.RefLyricFrame
import com.gpo.yoin.shared.reference.RefLyricWindowMetrics
import com.gpo.yoin.shared.reference.RefNpGap
import com.gpo.yoin.shared.reference.RefStageColumnMetrics
import com.gpo.yoin.shared.reference.refComputeAlbumSpectrum
import com.gpo.yoin.shared.reference.refLockTone
import com.gpo.yoin.shared.reference.refResolveDualPaneBudget
import com.gpo.yoin.shared.reference.refResolveLandscapeCover
import com.gpo.yoin.shared.reference.refResolveStageColumnBudget
import com.gpo.yoin.shared.reference.refScrapHash
import com.gpo.yoin.shared.reference.refSqueezeGaps
import com.gpo.yoin.shared.reference.refStageColumnBottomInset
import com.gpo.yoin.ui.component.FastScrollMath
import com.gpo.yoin.ui.component.FastScrollSection
import com.gpo.yoin.ui.detail.computeAlbumSpectrum
import com.gpo.yoin.ui.detail.lockTone
import com.gpo.yoin.ui.nowplaying.DualPaneMetrics
import com.gpo.yoin.ui.nowplaying.LyricFrame
import com.gpo.yoin.ui.nowplaying.LyricWindowMetrics
import com.gpo.yoin.ui.nowplaying.NowPlayingEnlargedSpec
import com.gpo.yoin.ui.nowplaying.NpGap
import com.gpo.yoin.ui.nowplaying.StageColumnMetrics
import com.gpo.yoin.ui.nowplaying.nowPlayingEnlargedSpec
import com.gpo.yoin.ui.nowplaying.resolveDualPaneBudget
import com.gpo.yoin.ui.nowplaying.resolveLandscapeCover
import com.gpo.yoin.ui.nowplaying.resolveStageColumnBudget
import com.gpo.yoin.ui.nowplaying.squeezeGaps
import com.gpo.yoin.ui.nowplaying.stageColumnBottomInset
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The algorithms that moved to :shared against the Android code they replaced (test-only copies in
 * com.gpo.yoin.shared.reference, verbatim from 69bfd77a0). Every comparison is exact: Dp values by
 * their Float bits (`toString` of the data classes), colours and indices as ints.
 */
class SharedParityTest {

    // ── Now Playing budget ───────────────────────────────────────────────

    private fun metricsAt(scale: Float, transport: Dp, pills: Dp) = StageColumnMetrics(
        tabText = 20.dp * scale,
        transport = transport * scale,
        hero = 70.dp * scale,
        heroOneLine = 44.dp * scale,
        pills = pills,
        oneLineContent = 32.dp * scale,
        lyric = LyricWindowMetrics(activeLine = 32.dp * scale, inactiveLine = 28.dp * scale),
    )

    private fun StageColumnMetrics.ref() = RefStageColumnMetrics(
        tabText = tabText,
        transport = transport,
        hero = hero,
        heroOneLine = heroOneLine,
        pills = pills,
        oneLineContent = oneLineContent,
        lyric = RefLyricWindowMetrics(lyric.activeLine, lyric.inactiveLine),
    )

    /** Data-class text with the Ref prefix dropped: same fields, same Float digits. */
    private fun Any?.text(): String = toString().replace("Ref", "")

    @Test
    fun should_resolve_identical_stage_columns_when_sweeping_windows_fonts_and_forms() {
        var checked = 0
        val widths = (280..1280 step 23).map { it.dp } + listOf(320.dp, 360.dp, 393.dp, 402.dp, 411.43f.dp, 412.dp, 800.dp)
        val heights = (380..1400 step 17).map { it.dp } + listOf(640.dp, 700.dp, 720.5f.dp, 778.dp, 778.6667f.dp, 843.dp)
        val fonts = listOf(1f, 1.15f, 1.3f, 2f)
        for (scale in fonts) {
            for ((transport, pills) in listOf(116.dp to 44.dp, 140.dp to 48.dp)) {
                val m = metricsAt(scale, transport, pills)
                for (w in widths) {
                    val enlargedOptions: List<NowPlayingEnlargedSpec?> = listOf(null, nowPlayingEnlargedSpec(w))
                    for (enlarged in enlargedOptions) {
                        for (previous in listOf(null, LyricFrame.OneLine, LyricFrame.List)) {
                            for (h in heights) {
                                val actual = resolveStageColumnBudget(w, h, m, enlarged, previous)
                                val expected = refResolveStageColumnBudget(
                                    w, h, m.ref(), enlarged, previous?.let { RefLyricFrame.valueOf(it.name) },
                                )
                                assertEquals("w=$w h=$h scale=$scale enlarged=$enlarged previous=$previous", expected.text(), actual.text())
                                checked++
                            }
                        }
                    }
                }
            }
        }
        println("SharedParityTest: $checked stage-column budgets identical")
    }

    @Test
    fun should_resolve_identical_dual_pane_and_landscape_budgets_when_sweeping_windows() {
        var checked = 0
        for (rowWidth in (600..1600 step 37).map { it.dp }) {
            for (contentHeight in (360..1200 step 19).map { it.dp }) {
                for ((transport, title) in listOf(116.dp to 36.dp, 150.dp to 52.dp)) {
                    val metrics = DualPaneMetrics(transport, 26.dp + title, 26.dp + title * 2, 44.dp, 24.dp)
                    val actual = resolveDualPaneBudget(rowWidth, contentHeight, 48.dp, metrics)
                    val expected = refResolveDualPaneBudget(
                        rowWidth, contentHeight, 48.dp,
                        RefDualPaneMetrics(metrics.transport, metrics.titleOneLine, metrics.titleTwoLines, metrics.pills, metrics.navBottom),
                    )
                    assertEquals("dual $rowWidth × $contentHeight", expected.text(), actual.text())
                    checked++
                }
                assertEquals(refResolveLandscapeCover(rowWidth, contentHeight).text(), resolveLandscapeCover(rowWidth, contentHeight).text())
                checked++
            }
        }
        for (inset in listOf(0.dp, 8.dp, 16.dp, 24.dp, 48.dp)) {
            assertEquals(refStageColumnBottomInset(inset), stageColumnBottomInset(inset))
        }
        val random = Random(7)
        repeat(2000) {
            val gaps = List(random.nextInt(0, 5)) { NpGap(random.nextInt(0, 160).dp, random.nextInt(0, 80).dp) }
            val upper = List(random.nextInt(0, 5)) { NpGap(random.nextInt(0, 60).dp, random.nextInt(0, 40).dp) }
            val deficit = (random.nextFloat() * 300f - 20f).dp
            val actual = squeezeGaps(deficit, gaps, upper)
            val expected = refSqueezeGaps(deficit, gaps.map { RefNpGap(it.nominal, it.min) }, upper.map { RefNpGap(it.nominal, it.min) })
            assertEquals(expected.text(), actual.text())
            gaps.forEach { g ->
                val f = random.nextFloat() * 1.4f - 0.2f
                assertEquals(RefNpGap(g.nominal, g.min).at(f), g.at(f))
            }
            checked++
        }
        println("SharedParityTest: $checked dual-pane / landscape / squeeze results identical")
    }

    // ── Album spectrum ───────────────────────────────────────────────────

    @Test
    fun should_build_identical_spectra_when_reading_random_and_structured_covers() {
        val random = Random(51)
        val n = 28
        val covers = mutableListOf<IntArray>()
        repeat(40) { covers += IntArray(n * n) { (0xFF shl 24) or random.nextInt(0x1000000) } }
        repeat(40) {
            val k = random.nextInt(1, 7)
            val palette = IntArray(k) { (0xFF shl 24) or random.nextInt(0x1000000) }
            val split = IntArray(k) { random.nextInt(n) }.sorted()
            covers += IntArray(n * n) { i ->
                val x = i % n
                palette[split.indexOfLast { it <= x }.coerceAtLeast(0)]
            }
        }
        repeat(20) {
            val a = (0xFF shl 24) or random.nextInt(0x1000000)
            val b = (0xFF shl 24) or random.nextInt(0x1000000)
            covers += IntArray(n * n) { i ->
                val t = (i % n + i / n) / (2f * n)
                val r = (((a shr 16) and 0xFF) * (1 - t) + ((b shr 16) and 0xFF) * t).toInt()
                val g = (((a shr 8) and 0xFF) * (1 - t) + ((b shr 8) and 0xFF) * t).toInt()
                val bl = ((a and 0xFF) * (1 - t) + (b and 0xFF) * t).toInt()
                (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
        covers += IntArray(n * n) { 0xFF808080.toInt() }
        covers += IntArray(n * n) { 0xFF000000.toInt() }
        covers += IntArray(n * n) { 0xFFFFFFFF.toInt() }
        covers.forEachIndexed { index, pixels ->
            val expected = refComputeAlbumSpectrum(pixels, n)
            val actual = computeAlbumSpectrum(pixels, n)
            assertArrayEquals("cover $index target", expected.target, actual.target)
            assertArrayEquals("cover $index colour", expected.color, actual.color.map { it.toArgb() }.toIntArray())
        }
        repeat(5000) {
            val argb = (0xFF shl 24) or random.nextInt(0x1000000)
            assertEquals(refLockTone(argb), lockTone(argb))
        }
        println("SharedParityTest: ${covers.size} spectra and 5000 tone locks identical")
    }

    // ── Fast scroller ────────────────────────────────────────────────────

    @Test
    fun should_map_identically_when_sweeping_the_fast_scroller() {
        val random = Random(3)
        var checked = 0
        for (itemCount in listOf(0, 1, 2, 5, 37, 100, 101, 350, 1000, 4097)) {
            for (perLine in listOf(1, 2, 3, 5)) {
                for (lps in listOf(0f, 0.5f, 3f, 7.25f, 12f, 40f)) {
                    val lines = FastScrollMath.lineCount(itemCount, perLine)
                    assertEquals(RefFastScrollMath.lineCount(itemCount, perLine), lines)
                    assertEquals(RefFastScrollMath.isWorthShowing(itemCount, perLine, lps), FastScrollMath.isWorthShowing(itemCount, perLine, lps))
                    for (step in 0..40) {
                        val f = step / 40f
                        val p = step * lines / 40f
                        assertEquals(RefFastScrollMath.handleFraction(p, lines, lps), FastScrollMath.handleFraction(p, lines, lps))
                        assertEquals(RefFastScrollMath.positionFor(f, lines, lps), FastScrollMath.positionFor(f, lines, lps))
                        assertEquals(RefFastScrollMath.jumpIndex(f, itemCount, perLine, lps), FastScrollMath.jumpIndex(f, itemCount, perLine, lps))
                        assertEquals(RefFastScrollMath.labelIndex(f, itemCount, perLine), FastScrollMath.labelIndex(f, itemCount, perLine))
                        checked += 4
                    }
                }
                if (itemCount > 0) {
                    val sections = mutableListOf<FastScrollSection>()
                    var at = 0
                    while (at < itemCount) {
                        val letter = ('A' + random.nextInt(26)).toString()
                        sections += FastScrollSection(label = "$letter${random.nextInt(3)}", startIndex = at, tickLabel = letter)
                        at += random.nextInt(1, 30)
                    }
                    val ticks = FastScrollMath.ticks(sections, perLine)
                    val refTicks = RefFastScrollMath.ticks(sections, perLine)
                    assertEquals(refTicks.map { it.label to it.startIndex }, ticks.map { it.label to it.startIndex })
                    assertArrayEquals(RefFastScrollMath.tickRuns(sections), FastScrollMath.tickRuns(sections))
                    for (index in -1..itemCount) {
                        assertEquals(RefFastScrollMath.sectionAt(sections, index), FastScrollMath.sectionAt(sections, index))
                    }
                    val centers = FastScrollMath.tickCenters(ticks, itemCount, perLine, 800f, 32f)
                    val refCenters = RefFastScrollMath.tickCenters(refTicks, itemCount, perLine, 800f, 32f)
                    assertArrayEquals(refCenters, centers, 0f)
                    assertArrayEquals(RefFastScrollMath.placeTicks(refCenters, 18f), FastScrollMath.placeTicks(centers, 18f), 0f)
                    checked += sections.size
                }
            }
        }
        for (viewport in listOf(0, 500, 1920, 2400)) {
            assertEquals(
                RefFastScrollMath.screenLines(viewport, 48, 96, 12, 180.5f),
                FastScrollMath.screenLines(viewport, 48, 96, 12, 180.5f),
            )
        }
        println("SharedParityTest: $checked fast-scroller mappings identical")
    }

    // ── Scrapbook tilt hash ──────────────────────────────────────────────

    @Test
    fun should_hash_identically_when_tilting_scrapbook_pieces() {
        val random = Random(11)
        val samples = listOf("", "al1", "al1/cover", "spotify:album:4aawyAB9vmqN3uQ7FjRGTy/12/tk", "余韻/receipt", "🎵/heart") +
            List(2000) { String(CharArray(random.nextInt(0, 40)) { (random.nextInt(0x20, 0xD7FF)).toChar() }) }
        samples.forEach { assertEquals(it, refScrapHash(it), ScrapbookTilt.hash(it)) }
    }
}
