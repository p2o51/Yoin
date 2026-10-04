package com.gpo.yoin.ui.memories.emblem

import androidx.compose.ui.graphics.Color
import com.gpo.yoin.ui.memories.showcase.rgb8
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrooveColorsTest {
    private val file = GrooveGolden.load("groove-colors.json")
    private val palettes = file.obj("palettes").mapValues { GrooveGolden.palette(it.value.jsonObject) }

    /** The prototype's neutral tokens (its --outline / --surface / --on-surface-var and the hard-coded tilt tones). */
    private fun neutrals(dark: Boolean) = GrooveNeutrals(
        outline = GrooveGolden.hex(if (dark) "#958e99" else "#7b7581"),
        surface = GrooveGolden.hex(if (dark) "#1d1b20" else "#fdf7fd"),
        onSurfaceVariant = GrooveGolden.hex(if (dark) "#cbc4cf" else "#4a4550"),
        tiltHigh = GrooveGolden.hex(if (dark) "#e6e0e9" else "#ffffff"),
        tiltLow = GrooveGolden.hex(if (dark) "#49454f" else "#1d1b20"),
        dark = dark,
    )

    private fun parse(token: String, dark: Boolean): Color? = when {
        token == "none" -> null
        token == "var(--surface)" -> neutrals(dark).surface
        token == "var(--on-surface-var)" -> neutrals(dark).onSurfaceVariant
        token.startsWith("#") -> GrooveGolden.hex(token)
        token.startsWith("rgba(") -> {
            val parts = token.removePrefix("rgba(").removeSuffix(")").split(",").map { it.trim() }
            Color(parts[0].toInt(), parts[1].toInt(), parts[2].toInt()).copy(alpha = parts[3].toFloat())
        }
        else -> error("unknown colour token $token")
    }

    private fun assertColor(message: String, expected: Color?, actual: Color) {
        if (expected == null) return
        assertArrayEquals(message, expected.rgb8(), actual.rgb8())
        assertEquals("$message alpha", expected.alpha, actual.alpha, 0.002f)
    }

    @Test
    fun should_match_prototype_colours_when_palette_is_sample() {
        var checked = 0
        file.arr("cases").map { it.jsonObject }.forEach { case ->
            val id = case.str("id")
            val dark = case.bool("dark")
            val surface = GrooveGolden.surfaceOf(case.str("on"))
            val kind = GrooveGolden.kindOf(case.str("kind"))
            // unrated6 is built on m2's palette; the "-as-" cases carry their own
            val palette = when {
                case.containsKey("palette") -> GrooveGolden.palette(case.obj("palette"))
                id == "unrated6" -> palettes.getValue("m2")
                else -> palettes.getValue(id)
            }
            val colors = grooveColors(palette, kind, dark, surface, neutrals(dark))
            val expected: JsonObject = case.obj("colours")
            fun c(key: String) = parse(expected.str(key), dark)
            val label = "$id/${if (dark) "dark" else "light"}/${case.str("on")}"
            if (kind == GrooveKind.Unrated) {
                assertColor(
                    "$label ground",
                    if (surface == GrooveSurface.Cover) c("disc") else c("ground"),
                    colors.ground,
                )
                assertColor("$label dot", c("dot"), colors.dot)
                assertColor("$label slot", c("slot"), colors.slot)
                assertColor("$label slotFill", c("slotFill"), colors.slotFill)
                assertColor("$label ink", c("ink"), colors.ink)
                assertColor("$label hair", c("hair"), colors.hairline)
                assertColor("$label hairS", c("hairS"), colors.hairlineStroke)
                val tint = case.obj("tints").obj("hairS")
                val t = colors.tint(colors.hairlineStroke)
                assertColor("$label tint hi", parse(tint.str("hi"), dark), t.high)
                assertColor("$label tint lo", parse(tint.str("lo"), dark), t.low)
            } else {
                assertColor("$label disc", c("disc"), colors.ground)
                assertColor("$label cut", c("cut"), colors.cut)
                assertColor("$label under", c("under"), colors.under)
                assertColor("$label dot", c("dot"), colors.dot)
                assertColor("$label rim", c("rim"), colors.rim)
                if (kind == GrooveKind.Album) {
                    assertColor("$label rim2", c("rim2"), colors.rim2)
                    assertColor("$label lab", c("lab"), colors.label)
                    assertColor("$label labRing", c("labRing"), colors.labelRing)
                } else {
                    assertColor("$label labFill", c("labFill"), colors.labelFill)
                    assertColor("$label labStroke", c("labStroke"), colors.labelStroke)
                }
                assertColor("$label ink", c("ink"), colors.ink)
                assertColor("$label cap", c("cap"), colors.caption)
                assertColor("$label hiT", c("hiT"), colors.tiltHigh)
                assertColor("$label loT", c("loT"), colors.tiltLow)
                assertColor("$label lit", c("lit"), colors.lit)
                assertColor("$label flare", c("flare"), colors.flare)
                val tints = case.obj("tints")
                listOf("cut" to colors.cut, "rim" to colors.rim).forEach { (key, base) ->
                    val t = colors.tint(base)
                    assertColor("$label tint $key hi", parse(tints.obj(key).str("hi"), dark), t.high)
                    assertColor("$label tint $key lo", parse(tints.obj(key).str("lo"), dark), t.low)
                }
            }
            checked++
        }
        assertTrue("checked $checked colour cases", checked >= 20)
    }

    @Test
    fun should_use_only_neutral_tokens_when_unrated() {
        val n = neutrals(dark = false)
        val a = grooveColors(palettes.getValue("m1"), GrooveKind.Unrated, false, GrooveSurface.Cover, n)
        val b = grooveColors(palettes.getValue("m4"), GrooveKind.Unrated, false, GrooveSurface.Cover, n)
        assertEquals("the album's colour is earned, not given", a, b)
        assertEquals(n.surface, a.ground)
        assertEquals(n.outline, a.slot)
        assertEquals(n.onSurfaceVariant, a.ink)
        val neutralSources = listOf(n.outline, n.surface, n.onSurfaceVariant).map { it.rgb8().toList() }
        listOf(a.ground, a.dot, a.tinyDot, a.slot, a.slotFill, a.ink, a.hairline, a.hairlineStroke).forEach {
            assertTrue("unrated colour $it comes from a neutral token", it.rgb8().toList() in neutralSources)
        }
        // the tiny lattice is denser (render: rgba(N, dark ? .95 : .8))
        assertColor("tiny dot", parse("rgba(123,117,129,0.8)", false), a.tinyDot)
    }
}
