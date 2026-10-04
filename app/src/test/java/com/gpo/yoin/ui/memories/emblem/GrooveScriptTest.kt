package com.gpo.yoin.ui.memories.emblem

import com.gpo.yoin.ui.memories.showcase.MemoryPaletteSamples
import kotlin.math.abs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrooveScriptTest {
    private val file = GrooveGolden.load("groove-beats.json")
    private val models = GrooveGolden.models(file)
    private val cases = file.arr("cases").map { it.jsonObject }

    private fun album(score: Double) = GrooveModel(GrooveKind.Album, score, List(4) { it < 2 }, MemoryPaletteSamples.M1)

    @Test
    fun should_pick_tier_two_when_score_displays_6_0() {
        val edge = album(5.95)
        assertEquals("6.0", edge.scoreText)
        assertEquals(2, edge.tier)
        assertEquals("5.9", album(5.94).scoreText)
        assertEquals(1, album(5.94).tier)
        assertEquals("8.0", album(7.99).scoreText)
        assertEquals(3, album(7.99).tier)
        // display and tier round the same way, so a 10.0 tier never shows "9.9"
        assertEquals("10.0", album(9.95).scoreText)
        assertEquals(4, album(9.95).tier)
        assertEquals("6.1", album(6.05).scoreText)
        assertEquals(0, GrooveModel(GrooveKind.Unrated, null, List(3) { false }, MemoryPaletteSamples.M2).tier)
        // and every prototype sample lands on the prototype's tier
        cases.forEach { case ->
            assertEquals(case.str("id"), case.int("tier"), models.getValue(case.str("id")).tier)
        }
    }

    @Test
    fun should_place_beats_on_spring_extrema_within_1ms() {
        var checked = 0
        cases.filter { it.int("tier") > 0 }.forEach { case ->
            val model = models.getValue(case.str("id"))
            val size = case.int("size").toDouble()
            val script = requireNotNull(grooveScriptFor(model, size))
            val label = "${case.str("id")}@${case.int("size")}"
            assertEquals("$label T", case.num("T"), script.durationS, 1e-12)
            assertBeats(label, case.arr("hapticPlan").map { it.jsonObject }, script.beats, compareFallback = true)
            checked++
        }
        assertTrue(checked > 40)

        // the beats sit on the curve's own extrema: tier 2's CLICK at the disc's overshoot peak,
        // tier 3 / 4's label contact at the label's lowest point
        val t2 = grooveScriptFor(GrooveSamples.tier(7.2, GrooveKind.Album), 96.0)!!
        val click = t2.beats.single { it.primitive == GroovePrimitive.CLICK }.atMs / 1000.0
        listOf(-0.002, 0.002).forEach { d -> assertTrue(t2.disc(click) >= t2.disc(click + d) - 1e-9) }
        val t3 = grooveScriptFor(GrooveSamples.tier(8.6, GrooveKind.Album), 96.0)!!
        val contact = t3.beats.single { it.primitive == GroovePrimitive.CLICK }.atMs / 1000.0
        listOf(-0.002, 0.002).forEach { d -> assertTrue(t3.label(contact) <= t3.label(contact + d) + 1e-9) }
    }

    @Test
    fun should_merge_beats_closer_than_45ms() {
        val merged = mergeCloseBeats(
            listOf(
                GrooveBeat(100, GroovePrimitive.CLICK, 1f, GrooveFallback.Click, "c"),
                GrooveBeat(0, GroovePrimitive.SPIN, 0.5f, GrooveFallback.Tick, "s"),
                GrooveBeat(30, GroovePrimitive.TICK, 0.6f, GrooveFallback.Tick, "t"),
                GrooveBeat(120, GroovePrimitive.LOW_TICK, 0.3f, GrooveFallback.LightTick, "l"),
                GrooveBeat(145, GroovePrimitive.TICK, 0.2f, GrooveFallback.Tick, "late"),
            ),
        )
        assertEquals(
            listOf(
                30 to GroovePrimitive.TICK,
                100 to GroovePrimitive.CLICK,
                145 to GroovePrimitive.TICK,
            ),
            merged.map {
                it.atMs to it.primitive
            },
        )
        // no tier of any sample ever plays two beats inside the window
        cases.filter { it.int("tier") > 0 }.forEach { case ->
            val beats = grooveScriptFor(models.getValue(case.str("id")), case.int("size").toDouble())!!.beats
            beats.zipWithNext().forEach { (a, b) ->
                assertTrue(
                    "${case.str("id")} ${a.atMs}→${b.atMs}",
                    b.atMs - a.atMs >= GrooveScript.MergeWindowMs,
                )
            }
        }
    }

    @Test
    fun should_match_prototype_channels_when_sampled() {
        var rows = 0
        cases.filter { it.containsKey("curves") }.forEach { case ->
            val model = models.getValue(case.str("id"))
            val script = grooveScriptFor(model, case.int("size").toDouble())!!
            val label = case.str("id")
            case.arr("curves").forEach { rowEl ->
                val row = rowEl.jsonArray
                val t = row[0].jsonPrimitive.double
                fun close(what: String, expected: Double, actual: Double) =
                    assertEquals("$label $what @t=$t", expected, actual, 1e-6 + abs(expected) * 1e-9)
                close("disc", row[1].jsonPrimitive.double, script.disc(t))
                close("label", row[2].jsonPrimitive.double, script.label(t))
                row[3].jsonArray.forEachIndexed { j, v -> close("cut[$j]", v.jsonPrimitive.double, script.cut(j, t)) }
                row[4].jsonArray.forEachIndexed { j, v ->
                    close(
                        "cutGlow[$j]",
                        v.jsonPrimitive.double,
                        script.cutGlow(j, t),
                    )
                }
                row[5].jsonArray.forEachIndexed { i, v ->
                    close(
                        "ringGlow[$i]",
                        v.jsonPrimitive.double,
                        script.ringGlow(i, t),
                    )
                }
                close("flare", row[6].jsonPrimitive.double, script.flare(t))
                close("burstScale", row[7].jsonPrimitive.double, script.burstScale(t))
                close("burstAlpha", row[8].jsonPrimitive.double, script.burstAlpha(t))
                rows++
            }
        }
        assertTrue("sampled $rows rows", rows > 400)
    }

    @Test
    fun should_bring_motion_channels_to_rest_when_script_finishes() {
        GrooveSamples.TierScores.forEach { score ->
            listOf(GrooveKind.Album, GrooveKind.Average).forEach { kind ->
                val s = grooveScriptFor(GrooveSamples.tier(score, kind), 96.0)!!
                // the disc, label and cuts are springs that are still by then; the flat flashes (flare) may not be,
                // which is why the award hands whatever is left at T to the settle spring
                val t = s.durationS + 0.4
                assertEquals(0.0, s.disc(t), 1.0)
                assertEquals(1.0, s.label(t), 0.01)
                assertEquals(1.0, s.cut(3, t), 1e-3)
            }
        }
    }
}

internal fun assertBeats(
    label: String,
    expected: List<JsonObject>,
    actual: List<GrooveBeat>,
    compareFallback: Boolean,
) {
    assertEquals("$label beat count ${actual.map { it.atMs to it.primitive }}", expected.size, actual.size)
    expected.zip(actual).forEachIndexed { i, (e, a) ->
        assertEquals("$label beat $i primitive", GrooveGolden.primitiveOf(e.str("p")), a.primitive)
        assertTrue("$label beat $i at ${a.atMs} vs ${e.int("atMs")}", abs(e.int("atMs") - a.atMs) <= 1)
        assertEquals("$label beat $i scale", e.num("scale"), a.scale.toDouble(), 1e-6)
        if (compareFallback) assertEquals("$label beat $i fallback", GrooveGolden.fallbackOf(e["yoin"]), a.fallback)
    }
}
