package com.gpo.yoin.ui.home

import com.gpo.yoin.data.home.HomeLayoutDocument
import com.gpo.yoin.data.home.HomeSectionPref
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One order for both widths, the two-column pairs remembered apart (owner 2026-11-23): one column
 * edits order and visibility only, two columns re-apply the memory to that order, and every pair
 * shown is one row height no taller than the smaller of its two sections' maxima.
 */
class HomeLayoutWideMemoryTest {

    private val a = HomeSection.Activities
    private val j = HomeSection.JumpBackIn
    private val m = HomeSection.MemoryGrid
    private val f = HomeSection.MadeForYou
    private val l = HomeSection.LyricHighlight
    private val ra = HomeSection.RecentlyAdded
    private val rp = HomeSection.RecentlyPlayed
    private val yp = HomeSection.YourPlaylists
    private val rd = HomeSection.Rediscover
    private val op = HomeSection.OtherProfiles

    private val wide = HomeLayout.WideDefault
    private val widePairs = mapOf(a to j, l to ra, yp to rd)

    private fun HomeLayout.twoColumnRows() = homeFeedRows(enabledSections, pairs, twoColumn = true)

    private fun HomeLayout.halves(): Set<HomeSection> = sections.filter { it.half }.map { it.section }.toSet()

    /** Every shown pair is one row height, inside both sections' ranges (so at most the smaller maximum). */
    private fun HomeLayout.assertPairsShareOneHeight() {
        for (row in twoColumnRows().filterIsInstance<HomeFeedRow.Halves>()) {
            val units = unitsOf(row)
            assertTrue("$row at $units", units in row.left.unitRange && units in row.right.unitRange)
            assertTrue(units <= minOf(row.left.unitRange.last, row.right.unitRange.last))
        }
    }

    // ── One column edits order only; two columns re-apply the memory ───────────

    @Test
    fun should_rememberTheWideDefaultPairs_when_neverCustomized() {
        assertEquals(widePairs, HomeLayout.Default.widePairs)
        // In the catalog order only Activities | Jump Back In stand together.
        assertEquals(mapOf(a to j), HomeLayout.Default.pairs)
        assertTrue(HomeLayout.Default.isDefault)
        assertFalse(HomeLayout.Default.isWideDefault)
    }

    @Test
    fun should_pairInTwoColumns_when_aPortraitReorderPutsRememberedPartnersSideBySide() {
        // A phone-width edit of the never-customized layout: Lyric Cards moved up before Recently Added.
        val portrait = HomeLayout.Default.moved(l, 2)
        assertEquals(listOf(a, j, l, ra), portrait.enabledSections.take(4))
        assertEquals(widePairs, portrait.widePairs)
        assertEquals(listOf(HomeFeedRow.Halves(a, j), HomeFeedRow.Halves(l, ra)), portrait.twoColumnRows().take(2))
        // Not the one-column layout pinned in landscape.
        assertSame(portrait, portrait.forTwoColumn(true))
        portrait.assertPairsShareOneHeight()
    }

    @Test
    fun should_breakAPairButRememberIt_when_aPortraitReorderSplitsIt() {
        val split = wide.moved(m, 1)
        assertEquals(listOf(a, m, j), split.enabledSections.take(3))
        assertTrue(a !in split.halves() && j !in split.halves())
        assertEquals(widePairs, split.widePairs)
        // Moved back (still in one column), the pair is back.
        val back = split.moved(m, 2)
        assertEquals(HomeFeedRow.Halves(a, j), back.twoColumnRows().first())
    }

    @Test
    fun should_showBothFull_when_aPortraitReorderTurnsAPairAround() {
        // Jump Back In before Activities: never shown as JBI | Activities, nor against the order.
        val turned = wide.withEnabledOrder(listOf(j, a) + wide.enabledSections.drop(2))
        assertEquals(listOf(HomeFeedRow.Full(j), HomeFeedRow.Full(a)), turned.twoColumnRows().take(2))
        assertEquals(widePairs, turned.widePairs)
    }

    @Test
    fun should_breakAPairButRememberIt_when_oneSideIsHiddenInPortrait() {
        val hidden = wide.withEnabled(ra, false)
        assertFalse(l in hidden.halves())
        assertEquals(widePairs, hidden.widePairs)
        // Shown again it goes back to its slot, right after Lyric Cards: the pair is back.
        assertTrue(HomeFeedRow.Halves(l, ra) in hidden.withEnabled(ra, true).twoColumnRows())
    }

    @Test
    fun should_showOrderOnly_when_aLandscapeEditIsShownInOneColumn() {
        // Two columns: Made For You beside All Memories, Activities | JBI split.
        val order = listOf(a, m, f, j, l, ra, rp, yp, rd, op)
        val landscape = wide.withArrangement(order, mapOf(m to f, l to ra, yp to rd))
        assertEquals(mapOf(m to f, l to ra, yp to rd), landscape.widePairs)
        // One column: the same order, every section its own row.
        assertSame(landscape, landscape.shownAt(false))
        assertEquals(order.map { HomeFeedRow.Full(it) }, homeFeedRows(landscape.enabledSections, landscape.pairs, false))
    }

    @Test
    fun should_forgetPairsTwoColumnsDontShow_when_rememberingShownPairs() {
        val split = wide.moved(m, 1)
        val remembered = split.rememberingShownPairs()
        assertEquals(mapOf(l to ra, yp to rd), remembered.widePairs)
        assertSame(remembered, remembered.rememberingShownPairs())
        // Moved back now, the forgotten pair stays two full rows.
        assertEquals(listOf(HomeFeedRow.Full(a), HomeFeedRow.Full(j)), remembered.moved(m, 2).twoColumnRows().take(2))
    }

    @Test
    fun should_rememberWhatTwoColumnsShow_when_aWideOpRuns() {
        assertEquals(mapOf(l to ra, yp to rd), wide.withFull(a).widePairs)
        assertEquals(mapOf(a to j, m to f, l to ra, yp to rd), wide.withHalf(m).widePairs)
        assertEquals(mapOf(a to j, m to f, l to ra, yp to rd), wide.withBesidePrevious(f).widePairs)
    }

    @Test
    fun should_keepTheMemory_when_aCarryDropChangesNothingShown() {
        val split = wide.moved(m, 1)
        assertSame(split, split.withArrangement(split.enabledSections, split.pairs))
    }

    @Test
    fun should_mapEachDefaultToTheOther_when_shownAtTheOtherWidth() {
        assertTrue(HomeLayout.Default.shownAt(true).isWideDefault)
        assertTrue(wide.shownAt(false).isDefault)
        val custom = HomeLayout.Default.withEnabled(rd, false)
        assertSame(custom, custom.shownAt(true))
        assertSame(custom, custom.shownAt(false))
    }

    @Test
    fun should_notBeDefault_when_onlyTheMemoryDiffers() {
        val sameOrderOtherMemory = HomeLayout.Default.copy(widePairs = mapOf(a to j))
        assertFalse(sameOrderOtherMemory.isDefault)
        assertFalse(sameOrderOtherMemory.sameSectionsAs(HomeLayout.Default))
        assertTrue(sameOrderOtherMemory.reset().isDefault)
    }

    // ── Equal heights ───────────────────────────────────────────────────────

    @Test
    fun should_shareOneHeightCappedAtTheSmallerMax_when_aPairIsReappliedAfterRotation() {
        // Jump Back In at 4 units (max 4) re-paired with Activities (max 3) after a portrait edit: the row is 3.
        val portrait = HomeLayout.Default.withRows(j, HomeRowPreset.XL).withRows(a, HomeRowPreset.XL)
            .withEnabled(rd, false)
        val row = portrait.twoColumnRows().first()
        assertEquals(HomeFeedRow.Halves(a, j), row)
        assertEquals(3, portrait.unitsOf(row))
        portrait.assertPairsShareOneHeight()
    }

    @Test
    fun should_capAPairsResizeAtTheSmallerMax_when_theRowIsResized() {
        val resized = wide.withUnits(j, 4)
        val row = resized.twoColumnRows().first()
        assertEquals(3, resized.unitsOf(row))
        assertEquals(1..3, homeRowUnitRange(a, j))
    }

    @Test
    fun should_offerTheIntersection_when_twoRangesMeet() {
        assertTrue((1..3).meetsUnits(2..4))
        assertEquals(2..3, (1..3).intersectUnits(2..4))
        assertFalse((1..2).meetsUnits(3..4))
        // Every pair of today's sections can share a height.
        for (x in HomeSection.entries) for (y in HomeSection.entries) assertTrue("$x $y", homeCanPair(x, y))
    }

    @Test
    fun should_showTwoFullRows_when_theRangesNeverMeet() {
        val order = listOf(a, j, m)
        assertEquals(mapOf(a to j), homeWidePairsAt(order, mapOf(a to j)))
        assertEquals(emptyMap<HomeSection, HomeSection>(), homeWidePairsAt(order, mapOf(a to j), canPair = { _, _ -> false }))
    }

    // ── Stored format ───────────────────────────────────────────────────────

    @Test
    fun should_roundTripTheMemory_when_storedAsADocument() {
        val split = wide.moved(m, 1).withRows(j, HomeRowPreset.S)
        val document = split.toDocument()
        assertEquals(
            Json.parseToJsonElement(
                """{"pairs":[["activities","jump_back_in"],["lyric_highlight","recently_added"],["your_playlists","rediscover"]]}""",
            ),
            document.wide,
        )
        // The flags stay what two columns show, for older readers.
        assertNull(document.sections.first { it.id == a.id }.config?.get("span"))
        val read = HomeLayout.fromDocument(document)
        assertTrue(read.sameSectionsAs(split))
        assertEquals(split.sections, read.sections)
    }

    @Test
    fun should_writeAnEmptyMemory_when_nothingIsRemembered() {
        val none = wide.withFull(a).withFull(l).withFull(yp)
        assertEquals(JsonObject(mapOf("pairs" to JsonArray(emptyList()))), none.wideDocument())
        // Read back it stays empty: not the wide default's pairs.
        assertEquals(emptyMap<HomeSection, HomeSection>(), HomeLayout.fromDocument(none.toDocument()).widePairs)
    }

    @Test
    fun should_deriveTheMemoryFromTheFlags_when_anOldDocumentHasPairs() {
        // An older build's landscape edit: only All Memories | Made For You, stored as span flags.
        val old = wide.withFull(a).withFull(l).withFull(yp).withHalf(m)
        val read = HomeLayout.reconcile(old.toPrefs())
        assertEquals(mapOf(m to f), read.widePairs)
        assertEquals(old.sections, read.sections)
    }

    @Test
    fun should_useTheWideDefaultPairs_when_anOldDocumentHasNoPairs() {
        // An older build's (or iOS / HarmonyOS's) one-column edit: no span flags at all.
        val prefs = HomeLayout.Default.withEnabled(rd, false).toPrefs().map { HomeSectionPref(it.id, it.enabled) }
        val read = HomeLayout.reconcile(prefs)
        assertEquals(widePairs, read.widePairs)
        assertEquals(HomeFeedRow.Halves(a, j), read.twoColumnRows().first())
        read.assertPairsShareOneHeight()
    }

    @Test
    fun should_keepWhatItCannotRead_when_theMemoryNamesUnknownSections() {
        val wideJson = Json.parseToJsonElement(
            """{"pairs":[["activities","jump_back_in"],["future_shelf","rediscover"],["memories","your_playlists"],
               ["activities"],["made_for_you","made_for_you"],["jump_back_in","memory_grid"]],"columns":3}""",
        ).jsonObject
        val read = HomeLayout.reconcile(HomeLayout.Default.toPrefs(), wideJson)
        // The second pair naming Jump Back In loses; the retired and malformed ones drop.
        assertEquals(mapOf(a to j), read.widePairs)
        assertEquals(
            Json.parseToJsonElement(
                """{"columns":3,"pairs":[["activities","jump_back_in"],["future_shelf","rediscover"]]}""",
            ),
            read.wideDocument(),
        )
    }

    @Test
    fun should_addTheWideDefaultPair_when_aSectionIsAppendedAndItsPartnerIsFree() {
        // A layout stored before Jump Back In existed (as a future section would be appended).
        val prefs = HomeLayout.Default.toPrefs().filterNot { it.id == j.id }
        val stored = JsonObject(mapOf("pairs" to Json.parseToJsonElement("""[["lyric_highlight","recently_added"]]""")))
        val read = HomeLayout.fromDocument(HomeLayoutDocument(prefs, stored))
        assertEquals(mapOf(l to ra, a to j), read.widePairs)
    }

    @Test
    fun should_readTheOldDocument_when_thereIsNoWideKey() {
        val document = HomeLayoutDocument(listOf(HomeSectionPref(a.id, true, JsonObject(mapOf("span" to JsonPrimitive("half"))))))
        val read = HomeLayout.fromDocument(document)
        // Activities' lone half has no partner; with no pair shown, the wide default's are remembered.
        assertEquals(widePairs, read.widePairs)
        assertEquals(HomeFeedRow.Halves(a, j), read.twoColumnRows().first())
    }
}
