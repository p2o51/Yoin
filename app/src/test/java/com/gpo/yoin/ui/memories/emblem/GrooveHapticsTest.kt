package com.gpo.yoin.ui.memories.emblem

import com.gpo.yoin.ui.memories.showcase.MemoryPaletteSamples
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrooveHapticsTest {
    private val file = GrooveGolden.load("groove-beats.json")
    private val models = GrooveGolden.models(file)
    private val cases = file.arr("cases").map { it.jsonObject }.filter { it.int("tier") > 0 }
    private val constants = file.obj("constants")

    private fun JsonObject.award(key: String) = obj("award").arr(key).map { it.jsonObject }

    @Test
    fun should_match_prototype_award_beats_in_every_mode() {
        assertEquals(constants.int("NOD_MS"), GrooveBeatTimes.NodMs)
        assertEquals(constants.int("NOD_RE_MS"), GrooveBeatTimes.NodAgainMs)
        assertEquals(constants.int("REVEAL_MS"), GrooveBeatTimes.RevealMs)
        cases.forEach { case ->
            val model = models.getValue(case.str("id"))
            val size = case.int("size").toDouble()
            val label = "${case.str("id")}@${case.int("size")}"
            // tier 4's later ring ticks have no View stand-in in the app (PLAN: the fallback keeps only the first);
            // twostate4 still names performTick there, so fallbacks are checked separately below
            assertBeats(
                "$label first",
                case.award("first"),
                awardBeats(model, GrooveBeatMode.First, size),
                compareFallback = false,
            )
            assertBeats(
                "$label replay",
                case.award("replay"),
                awardBeats(model, GrooveBeatMode.Replay, size),
                compareFallback = false,
            )
            assertBeats(
                "$label nod",
                case.award("nod"),
                awardBeats(model, GrooveBeatMode.Nod, size),
                compareFallback = false,
            )
            assertBeats(
                "$label reduced first",
                case.award("reducedFirst"),
                awardBeats(model, GrooveBeatMode.First, size, reducedMotion = true),
                compareFallback = false,
            )
            assertBeats(
                "$label reduced replay",
                case.award("reducedReplay"),
                awardBeats(model, GrooveBeatMode.Replay, size, reducedMotion = true),
                compareFallback = false,
            )
            assertBeats(
                "$label reduced nod",
                case.award("reducedNod"),
                awardBeats(model, GrooveBeatMode.Nod, size, reducedMotion = true),
                compareFallback = false,
            )
        }
    }

    @Test
    fun should_cap_tier_four_at_seven_beats() {
        val dense = GrooveModel(GrooveKind.Album, 10.0, List(30) { true }, MemoryPaletteSamples.M4)
        val golden = cases.filter { it.int("tier") == 4 }
            .map { models.getValue(it.str("id")) to it.int("size").toDouble() }
        val tierFour = golden + listOf(dense to 124.0, dense to 96.0, dense to 72.0)
        tierFour.forEach { (model, size) ->
            val beats = awardBeats(model, GrooveBeatMode.First, size)
            val ticks = beats.filter { it.primitive == GroovePrimitive.TICK }
            assertTrue("≤ 7 beats at $size: ${beats.map { it.atMs to it.primitive }}", beats.size <= 7)
            assertTrue("≤ 4 ring ticks", ticks.size <= 4)
            assertTrue("ring ticks at .35", ticks.all { it.scale == 0.35f })
            assertTrue(
                "ring ticks ≥ 90ms apart",
                ticks.zipWithNext().all { (a, b) -> b.atMs - a.atMs >= GrooveBeatTimes.RingTickSpacingMs },
            )
            assertFalse("no QUICK_RISE", beats.any { it.primitive == GroovePrimitive.QUICK_RISE })
            assertEquals(listOf(GroovePrimitive.SPIN), beats.take(1).map { it.primitive })
            assertEquals(listOf(GroovePrimitive.THUD, GroovePrimitive.CLICK), beats.takeLast(2).map { it.primitive })
        }
    }

    @Test
    fun should_keep_only_climax_when_replaying() {
        GrooveSamples.TierScores.forEachIndexed { i, score ->
            val tier = i + 1
            listOf(GrooveKind.Album, GrooveKind.Average).forEach { kind ->
                val model = GrooveSamples.tier(score, kind)
                val first = awardBeats(model, GrooveBeatMode.First)
                val replay = awardBeats(model, GrooveBeatMode.Replay)
                val climax = when (tier) {
                    1 -> listOf(GroovePrimitive.LOW_TICK)
                    4 -> listOf(GroovePrimitive.THUD, GroovePrimitive.CLICK)
                    else -> listOf(GroovePrimitive.CLICK)
                }
                assertEquals("tier $tier $kind", climax, replay.map { it.primitive })
                // the climax keeps its own time and strength
                replay.forEach { r ->
                    assertTrue(
                        first.any {
                            it.atMs == r.atMs && it.primitive == r.primitive && it.scale == r.scale
                        },
                    )
                }
            }
        }
    }

    @Test
    fun should_halve_strongest_beat_when_nodding() {
        GrooveSamples.TierScores.forEach { score ->
            val model = GrooveSamples.tier(score, GrooveKind.Album)
            val first = awardBeats(model, GrooveBeatMode.First, 48.0)
            val strongest = strongestBeat(first)
            val nod = awardBeats(model, GrooveBeatMode.Nod, 48.0).single()
            assertEquals(strongest.primitive, nod.primitive)
            assertEquals(Math.round(strongest.scale * 50) / 100f, nod.scale, 1e-6f)
            assertEquals(GrooveBeatTimes.NodMs, nod.atMs)
            assertEquals(GrooveBeatTimes.NodAgainMs, awardBeats(model, GrooveBeatMode.NodAgain, 48.0).single().atMs)
            assertEquals(
                GrooveBeatTimes.RevealMs,
                awardBeats(model, GrooveBeatMode.Nod, 48.0, reducedMotion = true).single().atMs,
            )
        }
        // ties go to THUD: tier 4's THUD and CLICK are both 1.0
        assertEquals(
            GroovePrimitive.THUD,
            awardBeats(GrooveSamples.tier(10.0, GrooveKind.Album), GrooveBeatMode.Nod).single().primitive,
        )
        assertEquals(
            0.38f,
            awardBeats(GrooveSamples.tier(7.2, GrooveKind.Album), GrooveBeatMode.Nod).single().scale,
            1e-6f,
        )
        assertTrue(awardBeats(GrooveSamples.M2, GrooveBeatMode.Nod).isEmpty())
    }

    @Test
    fun should_skip_quick_rise_when_falling_back_to_view_haptics() {
        val tierFour = GrooveSamples.tier(10.0, GrooveKind.Average)
        val raw = grooveScriptFor(tierFour, 96.0)!!.beats
        assertTrue("the raw plan still has its QUICK_RISE", raw.any { it.primitive == GroovePrimitive.QUICK_RISE })
        assertTrue(fallbackBeats(raw).none { it.primitive == GroovePrimitive.QUICK_RISE })

        val played = fallbackBeats(awardBeats(tierFour, GrooveBeatMode.First))
        assertEquals(
            "only the first ring tick survives as a View tick",
            1,
            played.count {
                it.primitive == GroovePrimitive.TICK
            },
        )
        assertEquals(GrooveFallback.LightTick, played.single { it.primitive == GroovePrimitive.TICK }.fallback)
        assertEquals(
            listOf(GrooveFallback.Tick, GrooveFallback.LightTick, GrooveFallback.Confirm, GrooveFallback.Click),
            played.map { it.fallback },
        )
        // PLAN mapping: TICK → performTick, LOW_TICK → performLightTick, CLICK → performClick, THUD → performConfirm
        assertEquals(GrooveFallback.Tick, GroovePrimitive.TICK.defaultFallback)
        assertEquals(GrooveFallback.LightTick, GroovePrimitive.LOW_TICK.defaultFallback)
        assertEquals(GrooveFallback.Click, GroovePrimitive.CLICK.defaultFallback)
        assertEquals(GrooveFallback.Confirm, GroovePrimitive.THUD.defaultFallback)
        assertEquals(null, GroovePrimitive.QUICK_RISE.defaultFallback)
    }

    @Test
    fun should_delay_from_previous_primitive_end_when_composing() {
        val beats = listOf(
            GrooveBeat(0, GroovePrimitive.SPIN, 0.8f, GrooveFallback.Tick, "spin"),
            GrooveBeat(40, GroovePrimitive.TICK, 0.35f, null, "tick inside the spin"),
            GrooveBeat(356, GroovePrimitive.TICK, 0.35f, GrooveFallback.LightTick, "tick"),
            GrooveBeat(878, GroovePrimitive.THUD, 1f, GrooveFallback.Confirm, "thud"),
        )
        val schedule = composeSchedule(beats, intArrayOf(64, 10, 10, 46))
        // spin plays 0–64; the 40ms tick starts right after it; 356 = 74 + 282; 878 = 366 + 512
        assertEquals(listOf(0, 0, 282, 512), schedule.map { it.delayMs })
    }
}
