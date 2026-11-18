package com.gpo.yoin.shared

import com.gpo.yoin.shared.detail.ScrapbookTilt
import com.gpo.yoin.shared.detail.computeAlbumSpectrum
import com.gpo.yoin.shared.library.FastScrollMath
import com.gpo.yoin.shared.nowplaying.LyricWindowMetrics
import com.gpo.yoin.shared.nowplaying.StageColumnMetrics
import com.gpo.yoin.shared.nowplaying.resolveStageColumnBudget
import com.gpo.yoin.shared.palette.Palette
import dynamiccolor.ColorSpec
import dynamiccolor.DynamicScheme
import hct.Hct
import scheme.SchemeExpressive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared algorithms give the same bits on every target: each test folds a deterministic sweep
 * of results into an FNV-1a digest and checks it against the value the JVM (Android host test)
 * produced. Runs on the Android host and on the iOS simulator (`./gradlew :shared:allTests`).
 * The constants were recorded from the JVM run; a change that moves any result changes them.
 */
class CrossPlatformDigestTest {

    private class Digest {
        private var h = 0xcbf29ce484222325uL
        fun add(v: Long) {
            for (i in 0 until 8) {
                h = h xor ((v ushr (i * 8)) and 0xFF).toULong()
                h *= 0x100000001b3uL
            }
        }
        fun add(v: Int) = add(v.toLong())
        fun add(v: Float) = add(v.toRawBits())
        fun value(): String = h.toString(16)
    }

    @Test
    fun should_produce_the_jvm_digest_when_building_expressive_schemes() {
        val d = Digest()
        val random = Random(1)
        repeat(64) {
            val seed = (0xFF shl 24) or random.nextInt(0x1000000)
            for (dark in listOf(false, true)) {
                val s = SchemeExpressive(Hct.fromInt(seed), dark, 0.0, ColorSpec.SpecVersion.SPEC_2025, DynamicScheme.Platform.PHONE)
                listOf(s.primary, s.onPrimary, s.primaryContainer, s.secondary, s.tertiary, s.tertiaryContainer, s.surface,
                    s.surfaceContainer, s.surfaceContainerHighest, s.onSurfaceVariant, s.outline, s.outlineVariant).forEach(d::add)
            }
        }
        assertEquals(MCU_DIGEST, d.value())
    }

    @Test
    fun should_produce_the_jvm_digest_when_running_palette() {
        val d = Digest()
        val random = Random(2)
        repeat(24) { image ->
            val w = 112
            val h = 112
            val blobs = List(5) { FloatArray(6) { random.nextFloat() } }
            val pixels = IntArray(w * h) { i ->
                val x = (i % w) / w.toFloat()
                val y = (i / w) / h.toFloat()
                var r = 0.3f + 0.4f * x
                var g = 0.2f + 0.5f * y
                var b = 0.6f - 0.3f * x * y
                for (blob in blobs) {
                    val dx = x - blob[0]
                    val dy = y - blob[1]
                    val wgt = 1f / (1f + 40f * (dx * dx + dy * dy))
                    r += (blob[3] - r) * wgt
                    g += (blob[4] - g) * wgt
                    b += (blob[5] - b) * wgt
                }
                val noise = (random.nextFloat() - 0.5f) * 0.1f * (image % 3)
                fun c(v: Float) = ((v + noise).coerceIn(0f, 1f) * 255f).toInt()
                (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
            }
            for (filters in listOf(listOf(Palette.DEFAULT_FILTER), emptyList())) {
                val p = Palette.generate(pixels, 16, filters)
                p.swatches.forEach { d.add(it.rgb); d.add(it.population); it.hsl.forEach(d::add) }
                listOf(p.dominantSwatch, p.vibrantSwatch, p.lightVibrantSwatch, p.darkVibrantSwatch, p.mutedSwatch,
                    p.lightMutedSwatch, p.darkMutedSwatch).forEach { d.add(it?.rgb ?: 0) }
            }
            Palette.scaleDownPixels(pixels + pixels + pixels + pixels, 224, 224).forEach(d::add)
        }
        assertEquals(PALETTE_DIGEST, d.value())
    }

    @Test
    fun should_produce_the_jvm_digest_when_resolving_layouts_and_spectra() {
        val d = Digest()
        val metrics = StageColumnMetrics(20f, 116f, 70f, 44f, 44f, 32f, LyricWindowMetrics(32f, 28f))
        for (w in 300..900 step 31) {
            for (h in 400..1300 step 29) {
                val b = resolveStageColumnBudget(w.toFloat(), h + 0.6667f, metrics)
                listOf(b.stageWidth, b.rest.cover, b.rest.topBar, b.rest.lyricSlot, b.rest.controls, b.rest.hero,
                    b.rest.lower, b.rest.upper, b.focus?.cover ?: -1f, b.focus?.lyricSlot ?: -1f).forEach(d::add)
                d.add(b.rest.lyricFrame.ordinal)
            }
        }
        val random = Random(3)
        repeat(12) {
            val pixels = IntArray(28 * 28) { (0xFF shl 24) or random.nextInt(0x1000000) }
            val s = computeAlbumSpectrum(pixels, 28)
            s.color.forEach(d::add)
            s.target.forEach(d::add)
        }
        for (step in 0..100) {
            d.add(FastScrollMath.handleFraction(step * 3.3f, 300, 7.25f))
            d.add(FastScrollMath.positionFor(step / 100f, 300, 7.25f))
            d.add(FastScrollMath.jumpIndex(step / 100f, 1000, 3, 7.25f))
        }
        listOf("al1/cover", "余韻/receipt", "spotify:album:x/3/tk").forEach { d.add(ScrapbookTilt.hash(it)) }
        assertEquals(LAYOUT_DIGEST, d.value())
    }

    private companion object {
        const val MCU_DIGEST = "994ba1e28d7dda25"
        const val PALETTE_DIGEST = "b50191778b7406a6"
        const val LAYOUT_DIGEST = "9baf36d54deed55c"
    }
}
