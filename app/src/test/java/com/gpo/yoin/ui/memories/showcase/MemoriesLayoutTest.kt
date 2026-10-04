package com.gpo.yoin.ui.memories.showcase

import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The container budget against the prototype's own layoutFor / sealFor (golden layout-*.json, written by
 * docs/handoff/memories-showcase/tools/golden-layout.js), plus the ladder, the balance and the placement.
 */
class MemoriesLayoutTest {
    private fun golden(name: String): JsonObject = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader?.getResourceAsStream("memories/golden/$name")) {
            "missing golden fixture memories/golden/$name"
        }.bufferedReader().use { it.readText() },
    ).jsonObject

    private fun JsonObject.f(key: String): Float? =
        (get(key) as? JsonPrimitive)?.takeIf { !it.isString && it.content != "null" }?.content?.toFloat()

    private fun JsonObject.s(key: String): String = getValue(key).jsonPrimitive.content

    private fun assertRow(row: JsonObject) {
        val w = row.f("w")!!
        val h = row.f("h")!!
        val at = "${w.toInt()}×${h.toInt()}"
        val y = memoriesLayoutFor(w.dp, h.dp)
        assertEquals("$at tier", row.s("tier"), y.tier.name.lowercase())
        assertEquals("$at short", row.s("short").toBoolean(), y.short)
        assertEquals("$at cover", row.f("cover")!!, y.cover.value, 0f)
        assertEquals("$at seal", row.f("seal")!!, y.seal.value, 0f)
        assertEquals("$at pill start", row.f("pl")!!, y.bar.pillStart.value, 0f)
        assertEquals("$at dots end", row.f("dr")!!, y.bar.dotsEnd.value, 0f)
        assertEquals("$at bar", row.f("barh")!!, y.barHeight.value, 0f)
        row.f("lp")?.let { assertEquals("$at left page", it, y.leftPage.value, 0f) }
        row.f("measure")?.let { assertEquals("$at measure", it, y.measure.value, 0f) }
        row.f("dcol")?.let { assertEquals("$at diary column", it, y.diaryColumn.value, 0f) }
        row.f("air1")?.let { assertEquals("$at air1", it, y.air1.value, 0f) }
        row.f("minCov")?.let { assertEquals("$at ladder floor", it, y.minCover.value, 0f) }
    }

    /** Phone landscape (H < 480, room for two pages) deviates from the prototype on purpose. */
    private fun JsonObject.isLandscapeDeviation() = f("h")!! < 480f && f("w")!! >= 600f

    @Test
    fun should_match_prototype_table_when_laid_out_in_plan_containers() {
        val table = golden("layout-table.json")
        val rows = table.getValue("table").jsonArray.map { it.jsonObject }
        assertEquals(9, rows.size)
        rows.forEach(::assertRow)
        table.getValue("extra").jsonArray
            .map { it.jsonObject }
            .filterNot { it.isLandscapeDeviation() }
            .forEach(::assertRow)
    }

    @Test
    fun should_match_plan_values_for_each_tier() {
        // PLAN §5 P6, spelled out
        memoriesLayoutFor(412.dp, 915.dp).let {
            assertEquals(MemoriesTier.Phone, it.tier)
            assertEquals(256.dp, it.cover)
            assertEquals(96.dp, it.seal)
        }
        memoriesLayoutFor(375.dp, 667.dp).let { assertEquals(168.dp to 72.dp, it.cover to it.seal) }
        memoriesLayoutFor(600.dp, 728.dp).let {
            assertEquals(MemoriesTier.Medium, it.tier)
            assertEquals(168.dp to 72.dp, it.cover to it.seal)
        }
        memoriesLayoutFor(690.dp, 840.dp).let { assertEquals(260.dp to 98.dp, it.cover to it.seal) }
        memoriesLayoutFor(800.dp, 1280.dp).let {
            assertEquals(MemoriesTier.Medium, it.tier)
            assertEquals(360.dp to 124.dp, it.cover to it.seal)
            assertEquals(104.dp, it.air1)
            assertEquals(640.dp, it.diaryColumn)
        }
        memoriesLayoutFor(860.dp, 800.dp).let { assertEquals(256.dp to 96.dp, it.cover to it.seal) }
        // 900 × 1100: the spread's left page can't hold Medium's 360, so it stays Medium
        memoriesLayoutFor(900.dp, 1100.dp).let {
            assertEquals(MemoriesTier.Medium, it.tier)
            assertEquals(360.dp, it.cover)
        }
        memoriesLayoutFor(1000.dp, 700.dp).let {
            assertEquals(MemoriesTier.Spread, it.tier)
            assertEquals(460.dp to 436.dp, it.leftPage to it.measure)
            assertEquals(300.dp to 120.dp, it.cover to it.seal)
        }
        memoriesLayoutFor(1280.dp, 800.dp).let {
            assertEquals(MemoriesTier.Spread, it.tier)
            assertEquals(589.dp to 560.dp, it.leftPage to it.measure)
            assertEquals(300.dp to 120.dp, it.cover to it.seal)
        }
    }

    @Test
    fun should_never_shrink_exhibit_when_crossing_600_or_900() {
        val rows = golden("layout-sweep.json").getValue("rows").jsonArray.map { it.jsonObject }
        rows.forEach(::assertRow)
        var h = 480f
        while (h <= 1400f) {
            val phone = memoriesLayoutFor(599.dp, h.dp)
            val medium = memoriesLayoutFor(600.dp, h.dp)
            val below = memoriesLayoutFor(899.dp, h.dp)
            val above = memoriesLayoutFor(900.dp, h.dp)
            assertTrue("600 at $h: ${phone.cover} → ${medium.cover}", medium.cover >= phone.cover)
            assertTrue("600 at $h: seal ${phone.seal} → ${medium.seal}", medium.seal >= phone.seal)
            assertTrue("900 at $h: ${below.cover} → ${above.cover}", above.cover >= below.cover)
            h += 10f
        }
        // the landscape deviation keeps the rule for its offer too
        assertTrue(memoriesLayoutFor(600.dp, 400.dp).cover >= memoriesLayoutFor(599.dp, 400.dp).cover)
    }

    @Test
    fun should_follow_cover_down_the_ladder_with_prototype_seal() {
        golden("layout-seal.json").getValue("rows").jsonArray.map { it.jsonObject }.forEach { row ->
            val layout = memoriesLayoutFor(row.f("w")!!.dp, row.f("h")!!.dp)
            val cover = row.f("cover")!!
            val at = "${row.f("w")}×${row.f("h")}"
            assertEquals("seal for $cover at $at", row.f("seal")!!, memoriesSealFor(layout, cover.dp).value, 0f)
        }
        // the two states keep the budget's emblem whatever the cover
        val medium = memoriesLayoutFor(800.dp, 1280.dp)
        assertEquals(124.dp, memoriesSealFor(medium, 200.dp))
    }

    @Test
    fun should_tighten_spacing_before_shrinking_cover() {
        val layout = memoriesLayoutFor(1280.dp, 800.dp)
        val room = 800f - 24f - 64f
        // a citation that fits once the spacing tightens: the cover stays the budget's 300
        val fitsTight = fitSpreadDeck(layout, room, 24f, listOf { _ -> cite(title = 41f, paragraph = 110f) })
        assertEquals(SpreadTightness.Tight, fitsTight.tightness)
        assertEquals(300.dp, fitsTight.cover)
        assertFalse(fitsTight.laddered)
        // a longer one: tight first, then the cover steps down — and only as far as it must
        val long = cite(title = 82f, paragraph = 190f)
        val shrunk = fitSpreadDeck(layout, room, 24f, listOf { _ -> long })
        assertEquals(SpreadTightness.Tight, shrunk.tightness)
        assertTrue("${shrunk.cover}", shrunk.cover < 300.dp && shrunk.cover >= layout.minCover)
        assertTrue(shrunk.laddered)
        val need = 16f + spreadExtent(shrunk.cover.value, shrunk.seal.value, SpreadTightness.Tight, long) + 24f
        assertTrue("need $need fits $room", need <= room + 0.5f)
        // nothing fits at the floor: one more spacing step, the cover never under the floor
        val huge = fitSpreadDeck(layout, room, 24f, listOf { _ -> cite(title = 160f, paragraph = 400f) })
        assertEquals(SpreadTightness.Tight2, huge.tightness)
        assertEquals(layout.minCover, huge.cover)
        // short text: nothing moves
        val plain = fitSpreadDeck(layout, room, 24f, listOf { _ -> cite(title = 41f, paragraph = 54f) })
        assertEquals(SpreadTightness.Normal, plain.tightness)
        assertEquals(300.dp, plain.cover)
    }

    @Test
    fun should_use_one_cover_for_whole_deck() {
        val layout = memoriesLayoutFor(1000.dp, 700.dp)
        val room = 700f - 24f - 64f
        val short = cite(title = 41f, paragraph = 54f)
        val long = cite(title = 82f, paragraph = 160f)
        val deck = fitSpreadDeck(layout, room, 24f, listOf({ _ -> short }, { _ -> long }))
        val alone = fitSpreadDeck(layout, room, 24f, listOf { _ -> short })
        // the tallest card decides for every card: the short one shares the long one's cover and spacing
        assertTrue(deck.cover < alone.cover || deck.tightness != alone.tightness)
        // and every card's cover starts at the same y: the tallest card's content, centred
        val (top, bottom) = deck.spacing.let { it.padTop to maxOf(it.padBottom, 24f) }
        val tallest = spreadExtent(deck.cover.value, deck.seal.value, deck.tightness, long)
        assertEquals(top + maxOf(0f, (room - top - bottom - tallest) / 2f), deck.coverTop.value, 0.5f)
    }

    @Test
    fun should_cap_medium_air_at_120_and_share_rest_from_least_card() {
        // the card with the least slack (150) decides: 30 over the cap, 15 to the top air and 15 to the bottom
        assertEquals(39f to 45f, balanceMedium(24f, 30f, listOf(200f, 150f, 300f)))
        // under the cap nothing moves
        assertEquals(24f to 30f, balanceMedium(24f, 30f, listOf(90f, 400f)))
        assertEquals(24f to 30f, balanceMedium(24f, 30f, emptyList()))
    }

    @Test
    fun should_centre_short_right_page_on_left_optical_line_and_start_long_one_at_cover() {
        // fits (≤ view − 160): centred on (cover top + Go bottom) / 2
        val centred = spreadRightTop(contentHeight = 200f, viewHeight = 700f, coverTop = 100f, goBottom = 560f)
        assertEquals(230f, centred, 0f)
        // long: level with the cover's top
        val long = spreadRightTop(contentHeight = 600f, viewHeight = 700f, coverTop = 100f, goBottom = 560f)
        assertEquals(100f, long, 0f)
        // never closer than 24 to the bar
        val clear = spreadRightTop(contentHeight = 500f, viewHeight = 700f, coverTop = 10f, goBottom = 200f)
        assertEquals(24f, clear, 0f)
    }

    @Test
    fun should_lay_phone_landscape_as_spread_with_lower_floor() {
        val y = memoriesLayoutFor(914.dp, 411.dp)
        assertEquals(MemoriesTier.Spread, y.tier)
        assertTrue(y.landscape)
        assertEquals(88.dp, y.minCover)
        assertEquals(168.dp, y.cover)
        // the emblem stays an award pinned to a small cover, not a second cover
        assertEquals(72.dp, memoriesSealFor(y, 168.dp))
        assertEquals(48.dp, memoriesSealFor(y, 88.dp))
        // a narrow landscape window is still a (short) phone
        assertEquals(MemoriesTier.Phone, memoriesLayoutFor(560.dp, 400.dp).tier)
    }

    @Test
    fun should_enlarge_type_only_where_prototype_does() {
        val phone = MemoriesTypeScale.of(memoriesLayoutFor(412.dp, 915.dp))
        val phoneShort = MemoriesTypeScale.of(memoriesLayoutFor(375.dp, 667.dp))
        val medium = MemoriesTypeScale.of(memoriesLayoutFor(800.dp, 1280.dp))
        val mediumShort = MemoriesTypeScale.of(memoriesLayoutFor(600.dp, 728.dp))
        val spread = MemoriesTypeScale.of(memoriesLayoutFor(1280.dp, 800.dp))
        assertEquals(26f, phone.cardTitleAi.value, 0f)
        assertEquals(24f, phoneShort.cardTitleAi.value, 0f)
        assertEquals(30f, medium.cardTitleAi.value, 0f)
        assertEquals(27f, medium.cardTitleMotif.value, 0f)
        // a short Medium keeps the phone's card sizes but takes the tablet diary
        assertEquals(24f, mediumShort.cardTitleAi.value, 0f)
        assertEquals(17.5f, mediumShort.review.value, 0f)
        assertEquals(16f, phone.review.value, 0f)
        assertEquals(30f, spread.reviewShort.value, 0f)
        assertEquals(520.dp, medium.linerMaxWidth)
        assertEquals(44.dp, spread.linerColumn)
        assertEquals(38f, medium.stat.value, 0f)
    }

    private fun cite(title: Float, paragraph: Float?, album: Float = 46f) =
        SpreadCiteHeights(title = title, paragraph = paragraph, album = album, albumOnlyArtist = false)
}
