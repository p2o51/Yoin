package com.gpo.yoin.ui.memories.showcase

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPaletteTest {
    private val golden: JsonObject = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader?.getResourceAsStream("memories/golden/memory-palette.json"))
            .bufferedReader().use { it.readText() },
    ).jsonObject

    private fun hex(token: String) = Color(0xFF000000 or token.removePrefix("#").toLong(16))

    private fun JsonObject.s(key: String) = getValue(key).jsonPrimitive.content

    private fun palette(o: JsonObject) = MemoryPalette(
        hex(o.s("base")),
        hex(o.s("accent")),
        hex(o.s("deep")),
        hex(o.s("soft")),
    )

    private fun rgba(token: String): Pair<IntArray, Float> {
        if (token.startsWith("#")) return hex(token).rgb8() to 1f
        val p = token.removePrefix("rgba(").removeSuffix(")").split(",").map { it.trim() }
        return intArrayOf(p[0].toInt(), p[1].toInt(), p[2].toInt()) to p[3].toFloat()
    }

    private fun assertTone(message: String, expected: String, actual: Color) {
        val (rgb, alpha) = rgba(expected)
        assertArrayEquals(message, rgb, actual.rgb8())
        assertEquals("$message alpha", alpha, actual.alpha, 0.002f)
    }

    @Test
    fun should_lift_dark_highlight_to_at_least_on_surface_luminance() {
        val onSurfaceDark = Color(0xFFE7E0E8)
        val samples = with(MemoryPaletteSamples) { listOf(M1, M2, M3, M4) } +
            // deep, dark and near-grey bases too: the lift must hold for any album
            listOf(Color(0xFF0B0B2A), Color(0xFF3A0000), Color(0xFF202020), Color(0xFF6A6A6A), Color(0xFF00FF00)).map {
                MemoryPalette(it, Color(0xFFE2C27A), it, Color(0xFFEFEFEF))
            }
        samples.forEach { p ->
            val dark = p.tones(dark = true)
            assertTrue(
                "hl Y ${dark.highlight.luminance()} ≥ on-surface Y ${onSurfaceDark.luminance()} for base ${p.base}",
                dark.highlight.luminance() >= onSurfaceDark.luminance() - 0.004f,
            )
            assertTrue("the dark ink stays dimmer than the lit line", dark.ink.luminance() < dark.highlight.luminance())
        }
    }

    @Test
    fun should_match_prototype_tones_when_palette_is_sample() {
        golden.getValue("cases").jsonArray.map { it.jsonObject }.forEach { case ->
            val p = palette(case.getValue("palette").jsonObject)
            val id = case.s("id")
            listOf(false, true).forEach { dark ->
                val e = case.getValue(if (dark) "dark" else "light").jsonObject
                val t = p.tones(dark)
                val label = "$id/${if (dark) "dark" else "light"}"
                assertTone("$label ink", e.s("ink"), t.ink)
                assertTone("$label hl", e.s("hl"), t.highlight)
                assertTone("$label btn", e.s("btn"), t.button)
                assertTone("$label btnInk", e.s("btnInk"), t.onButton)
                assertTone("$label dot", e.s("dot"), t.dot)
                assertTone("$label tint", e.s("tint"), t.tint)
            }
            assertTone("$id liftHue .92", case.s("liftHue92"), liftHue(p.base, 0.92))
        }
    }

    @Test
    fun should_round_like_the_prototype_when_mixing() {
        // mix('#3b2d8f', '#d9d2f4', .6) = #9a90cc in the prototype
        assertArrayEquals(hex("#9a90cc").rgb8(), mixSrgb(hex("#3b2d8f"), hex("#d9d2f4"), 0.6).rgb8())
        assertEquals(0.38, toFixed(0.375, 2), 0.0)
        assertEquals(6.0, toFixed(5.95, 1), 0.0)
    }

    @Test
    fun should_hold_backdrop_base_inside_ink_lightness_when_building_palette() {
        fun hslLightness(c: Color) = (maxOf(c.red, c.green, c.blue) + minOf(c.red, c.green, c.blue)) / 2f
        // a pale extracted swatch is pulled down to ink, a near-black one lifted; a mid tone stays as is
        val pale = MemoryPalette.fromBackdrop(hex("#e8d9a0"), hex("#9cc7e8"))
        assertEquals(0.46f, hslLightness(pale.base), 0.01f)
        val dark = MemoryPalette.fromBackdrop(hex("#0a0a14"), hex("#9cc7e8"))
        assertEquals(0.22f, hslLightness(dark.base), 0.01f)
        val m1 = MemoryPalette.fromBackdrop(hex("#3b2d8f"), hex("#e2c27a"))
        assertArrayEquals(hex("#3b2d8f").rgb8(), m1.base.rgb8())
        // deep / soft sit where the fixtures' hand-picked anchors do: darker than base, lighter than base
        assertTrue(m1.deep.luminance() < m1.base.luminance())
        assertTrue(m1.soft.luminance() > 0.6f)
    }
}
