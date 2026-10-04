package com.gpo.yoin.ui.memories.emblem

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrooveGeometryTest {
    private val file = GrooveGolden.load("groove-geom.json")
    private val models = GrooveGolden.models(file)

    /** Golden numbers are rounded to 1e-6; render probes to 2 decimals (the prototype's `f()`). */
    private val eps = 2e-6
    private val probeEps = 0.0051

    private fun cases(sizes: Set<Int>): List<JsonObject> =
        file.arr("geometry").map { it.jsonObject }.filter { it.int("size") in sizes }

    @Test
    fun should_match_prototype_rings_when_size_is_96_72_48_44_40() {
        val sizes = setOf(96, 72, 48, 44, 40)
        val checked = cases(sizes)
        assertTrue("expected golden cases for every size", checked.map { it.int("size") }.toSet() == sizes)
        checked.forEach { case ->
            val model = models.getValue(case.str("id"))
            val size = case.int("size").toDouble()
            val surface = GrooveGolden.surfaceOf(case.str("on"))
            val label = "${case.str("id")}@${case.int("size")}/${case.str("on")}"
            val g = grooveGeometry(model.trackRated, size, model.kind, surface)
            val e = case.obj("geom")
            assertEquals("$label tiny", e.bool("tiny"), g.tiny)
            assertEquals("$label big", e.bool("big"), g.big)
            assertEquals("$label rimR", e.num("rimR"), g.rimRadius, eps)
            assertEquals("$label L", e.num("L"), g.labelRadius, eps)
            assertEquals("$label gOut", e.num("gOut"), g.grooveOuter, eps)
            assertEquals("$label gIn", e.num("gIn"), g.grooveInner, eps)
            assertEquals("$label pitch", e.num("pitch"), g.pitch, eps)
            assertEquals("$label dw", e.num("dw"), g.dotDiameter, eps)
            assertEquals("$label sw", e.num("sw"), g.cutWidth, eps)
            assertEquals("$label rimW", e.num("rimW"), g.rimWidth, eps)
            assertEquals("$label rim2At", e.num("rim2At"), g.rim2At, eps)
            val rings = e.arr("rings")
            assertEquals("$label ring count", rings.size, g.rings.size)
            rings.forEachIndexed { i, el ->
                val r = el.jsonObject
                val ring = g.rings[i]
                assertEquals("$label ring $i a", r.int("a"), ring.firstTrack)
                assertEquals("$label ring $i b", r.int("b"), ring.endTrack)
                assertEquals("$label ring $i rad", r.num("rad"), ring.radius, eps)
                val runs = r.arr("runs")
                assertEquals("$label ring $i runs", runs.size, ring.runs.size)
                runs.forEachIndexed { j, run ->
                    val (a0, a1) = run.jsonArray.map { it.jsonPrimitive.double }
                    assertEquals("$label ring $i run $j start", a0, ring.runs[j].startDegrees, eps)
                    assertEquals("$label ring $i run $j end", a1, ring.runs[j].endDegrees, eps)
                }
            }
        }
    }

    @Test
    fun should_caption_only_the_track_average_when_large() {
        val rated = List(10) { it < 5 }
        // owner, 2026-10-05: an album score stands alone; "Avg." stays under a track average
        assertFalse(grooveGeometry(rated, 96.0, GrooveKind.Album, GrooveSurface.Cover).showsCaption)
        assertFalse(grooveGeometry(rated, 124.0, GrooveKind.Album, GrooveSurface.Cover).showsCaption)
        assertTrue(grooveGeometry(rated, 96.0, GrooveKind.Average, GrooveSurface.Cover).showsCaption)
        assertFalse(grooveGeometry(rated, 72.0, GrooveKind.Average, GrooveSurface.Cover).showsCaption)
        // "Unrated" is the word on the mould, not a caption
        assertTrue(grooveGeometry(rated, 96.0, GrooveKind.Unrated, GrooveSurface.Cover).showsUnratedWord)
    }

    @Test
    fun should_match_prototype_render_constants_when_drawn() {
        cases(setOf(124, 96, 72, 48, 44, 40)).forEach { case ->
            val model = models.getValue(case.str("id"))
            val label = "${case.str("id")}@${case.int("size")}/${case.str("on")}"
            val g =
                grooveGeometry(
                    model.trackRated,
                    case.int("size").toDouble(),
                    model.kind,
                    GrooveGolden.surfaceOf(case.str("on")),
                )
            val p = case.obj("probe")
            val dots = p.arr("dots")
            assertEquals("$label dot rings", dots.size, g.rings.size)
            dots.forEachIndexed { i, d ->
                assertEquals("$label dot gap $i", d.jsonObject.num("gap"), g.dotGap(g.rings[i]), probeEps)
                assertEquals("$label dot offset $i", d.jsonObject.num("offset"), g.dotOffset(g.rings[i]), probeEps)
            }
            if (p.isNull("score")) {
                assertTrue("$label has no score", model.kind == GrooveKind.Unrated)
            } else {
                val s = p.obj("score")
                assertEquals("$label score size", s.num("size"), g.scoreFontSize, probeEps)
                assertEquals("$label score weight", s.int("weight"), if (model.kind == GrooveKind.Album) 690 else 640)
                assertEquals("$label score wdth", s.int("wdth"), if (model.scoreText.length >= 4) 76 else 92)
            }
            // owner, 2026-10-05: the prototype's "Album" caption is gone; only "Avg." and "Unrated" remain
            val caps = if (model.kind == GrooveKind.Album) {
                emptyList()
            } else {
                p.arr("caps").map { it.jsonPrimitive.double }
            }
            val expectedCaps = when {
                model.kind != GrooveKind.Unrated && g.showsCaption -> listOf(g.captionFontSize)
                g.showsUnratedWord -> listOf(g.unratedFontSize)
                else -> emptyList()
            }
            assertEquals("$label caption count", caps.size, expectedCaps.size)
            caps.zip(expectedCaps).forEach { (e, a) -> assertEquals("$label caption size", e, a, probeEps) }
            assertEquals("$label lit rings", p.int("rglowCount"), if (g.hasLitLayers) g.rings.size else 0)
            assertEquals(
                "$label lit cuts",
                p.int("glowCount"),
                if (g.hasLitLayers) g.rings.sumOf { it.runs.size } else 0,
            )
            if (!p.isNull("flareWidth")) assertEquals("$label flare", p.num("flareWidth"), g.flareWidth, probeEps)
            if (!p.isNull("burstWidth")) assertEquals("$label burst", p.num("burstWidth"), g.burstWidth, probeEps)
            assertEquals("$label spindle", !p.isNull("spindleRadius"), g.showsSpindle)
            if (g.showsSpindle) assertEquals("$label spindle r", p.num("spindleRadius"), g.spindleRadius, probeEps)
            // owner, 2026-10-05: the unrated mould's 4.8s ripple (the probe's `ripple`) is gone; the disc is static
            val tintRings = when (model.kind) {
                GrooveKind.Unrated -> if (g.surface == GrooveSurface.Cover && !g.tiny) 1 else 0
                else -> 1 + g.rings.count { it.index < 2 && it.runs.isNotEmpty() }
            }
            assertEquals("$label tinted lines", p.int("tintCount"), tintRings)
        }
    }

    @Test
    fun should_match_prototype_award_layout_when_measured_on_cover() {
        file.arr("layout").map { it.jsonObject }.forEach { case ->
            val model = models.getValue(case.str("id"))
            val size = case.int("size").toDouble()
            val label = "${case.str("id")}@${case.int("size")}"
            val layout = grooveLayout(model, size)
            assertEquals("$label nRings", case.int("nRings"), layout.ringCount)
            assertEquals("$label tiny", case.bool("tiny"), layout.tiny)
            assertEquals("$label ratedRings", case.arr("ratedRings").map { it.jsonPrimitive.int }, layout.ratedRings)
            assertEquals(
                "$label cuts",
                case.arr("cuts").map { it.jsonObject.int("k") to it.jsonObject.int("ring") },
                layout.cuts.map { it.k to it.ring },
            )
            assertEquals(
                "$label ring order",
                layout.ratedRings.indices.toList(),
                layout.ratedRings.map {
                    grooveGeometry(
                        model.trackRated,
                        size,
                        model.kind,
                        GrooveSurface.Cover,
                    ).ringOrder(it)
                },
            )
        }
    }
}
