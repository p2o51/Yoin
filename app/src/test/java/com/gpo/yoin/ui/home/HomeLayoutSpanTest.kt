package com.gpo.yoin.ui.home

import com.gpo.yoin.data.home.HomeSectionPref
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two-column pairs (large-screen Home, stage 1): span persistence, pairing and the handle's transitions. */
class HomeLayoutSpanTest {

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

    private fun HomeLayout.halves(): Set<HomeSection> = sections.filter { it.half }.map { it.section }.toSet()

    private fun HomeLayout.rows(displayed: List<HomeSection> = enabledSections) =
        homeFeedRows(displayed, pairs, twoColumn = true)

    @Test
    fun should_pairTheOwnersDefault_when_wideDefault() {
        assertEquals(listOf(a, j, m, f, l, ra, rp, yp, rd, op), wide.enabledSections)
        assertEquals(
            listOf(
                HomeFeedRow.Halves(a, j),
                HomeFeedRow.Full(m),
                HomeFeedRow.Full(f),
                HomeFeedRow.Halves(l, ra),
                HomeFeedRow.Full(rp),
                HomeFeedRow.Halves(yp, rd),
                HomeFeedRow.Full(op),
            ),
            wide.rows(),
        )
    }

    @Test
    fun should_showWideDefault_when_neverCustomizedInTwoColumns() {
        assertTrue(HomeLayout.Default.forTwoColumn(true).isWideDefault)
        assertSame(HomeLayout.Default, HomeLayout.Default.forTwoColumn(false))
        val custom = HomeLayout.Default.withEnabled(rd, false)
        assertSame(custom, custom.forTwoColumn(true))
    }

    @Test
    fun should_stackInStoredOrder_when_oneColumn() {
        assertEquals(wide.enabledSections.map { HomeFeedRow.Full(it) }, homeFeedRows(wide.enabledSections, wide.pairs, false))
    }

    @Test
    fun should_renderPartnerFullWidth_when_itsPartnerHasNoContent() {
        // Made For You off-Spotify is simply not displayed; Lyric Cards without Nano neither.
        val shown = wide.enabledSections - l
        assertEquals(HomeFeedRow.Full(ra), wide.rows(shown)[3])
        // The pairing is still stored: when Lyric Cards comes back the pair is back.
        assertTrue(l in wide.halves() && ra in wide.halves())
        assertEquals(HomeFeedRow.Halves(l, ra), wide.rows()[3])
    }

    @Test
    fun should_roundTripSpanThroughConfig_when_persisted() {
        val prefs = wide.toPrefs()
        assertEquals(
            JsonObject(mapOf("span" to JsonPrimitive("half"))),
            prefs.first { it.id == a.id }.config,
        )
        assertNull(prefs.first { it.id == m.id }.config)
        assertEquals(wide.sections, HomeLayout.reconcile(prefs).sections)
    }

    @Test
    fun should_keepRowsBesideSpan_when_bothSet() {
        val layout = wide.withRows(j, HomeRowPreset.XL)
        val pref = layout.toPrefs().first { it.id == j.id }
        assertEquals(
            JsonObject(mapOf("rows" to JsonPrimitive("xl"), "span" to JsonPrimitive("half"))),
            pref.config,
        )
        assertEquals(layout.sections, HomeLayout.reconcile(layout.toPrefs()).sections)
    }

    @Test
    fun should_keepUnknownSpanValue_when_readByThisBuild() {
        val odd = JsonObject(mapOf("span" to JsonPrimitive("third")))
        // Rediscover between it and Jump Back In: the default memory's Activities | JBI doesn't show.
        val layout = HomeLayout.reconcile(listOf(HomeSectionPref(a.id, true, odd), HomeSectionPref(rd.id, true)))
        assertFalse(layout.sections.first().half)
        assertEquals(odd, layout.toPrefs().first().config)
    }

    @Test
    fun should_dropOrphanHalf_when_reconciled() {
        val half = JsonObject(mapOf("span" to JsonPrimitive("half")))
        val layout = HomeLayout.reconcile(
            listOf(HomeSectionPref(a.id, true, half), HomeSectionPref(m.id, true), HomeSectionPref(j.id, true, half)),
        )
        assertTrue(layout.halves().isEmpty())
    }

    @Test
    fun should_pairHalvesTwoByTwo_when_runIsOdd() {
        val pairs = homeSpanPairs(listOf(a to true, j to true, m to true, f to false, l to true, ra to true))
        assertEquals(mapOf(a to j, l to ra), pairs)
    }

    @Test
    fun should_pullNextFullSectionUp_when_fullTurnsHalf() {
        val layout = wide.withHalf(m)
        assertEquals(HomeFeedRow.Halves(m, f), layout.rows()[1])
        assertEquals(wide.enabledSections, layout.enabledSections)
    }

    @Test
    fun should_takeFirstOfNextPair_when_fullTurnsHalfBeforeAPair() {
        val layout = wide.withHalf(f)
        val rows = layout.rows()
        assertEquals(HomeFeedRow.Halves(f, l), rows[2])
        assertEquals(HomeFeedRow.Full(ra), rows[3])
    }

    @Test
    fun should_doNothing_when_lastSectionTurnsHalf() {
        assertSame(wide, wide.withHalf(op))
        assertSame(wide, wide.withHalf(a))
    }

    @Test
    fun should_putPartnerOnOwnRowAfter_when_leftHalfTurnsFull() {
        val layout = wide.withFull(a)
        assertEquals(listOf(HomeFeedRow.Full(a), HomeFeedRow.Full(j)), layout.rows().take(2))
    }

    @Test
    fun should_moveAheadOfOldPartner_when_rightHalfTurnsFull() {
        val layout = wide.withFull(j)
        assertEquals(listOf(HomeFeedRow.Full(j), HomeFeedRow.Full(a)), layout.rows().take(2))
        assertSame(wide, wide.withFull(m))
    }

    @Test
    fun should_breakPair_when_memberHidden() {
        val layout = wide.withEnabled(j, false)
        assertFalse(a in layout.halves())
        assertFalse(j in layout.halves())
        // Still remembered: shown again, back in its slot beside Activities, the pair is back
        // (a two-column edit forgets it: HomeLayoutWideMemoryTest).
        assertEquals(mapOf(a to j, l to ra, yp to rd), layout.widePairs)
        assertEquals(HomeFeedRow.Halves(a, j), layout.withEnabled(j, true).rows().first())
    }

    @Test
    fun should_breakPair_when_reorderSplitsIt() {
        val layout = wide.moved(m, 1)
        assertEquals(listOf(a, m, j), layout.enabledSections.take(3))
        assertTrue(a !in layout.halves() && j !in layout.halves())
    }

    @Test
    fun should_resetToWideDefault_when_twoColumns() {
        val custom = wide.withFull(a)
        assertTrue(custom.reset(twoColumn = true).isWideDefault)
        assertTrue(custom.reset(twoColumn = false).isDefault)
        assertTrue(wide.isAnyDefault)
        assertTrue(wide.isDefaultFor(true))
        assertFalse(wide.isDefaultFor(false))
    }

    @Test
    fun should_writeOrderAndPairsTogether_when_carryDropsBeside() {
        // Made For You put beside All Memories; Activities | JBI split by lifting JBI to after Made For You.
        val order = listOf(a, m, f, j, l, ra, rp, yp, rd, op)
        val next = wide.withArrangement(order, mapOf(m to f, l to ra, yp to rd))
        assertEquals(order, next.enabledSections)
        assertEquals(setOf(m, f, l, ra, yp, rd), next.halves())
        assertEquals(
            listOf(
                HomeFeedRow.Full(a),
                HomeFeedRow.Halves(m, f),
                HomeFeedRow.Full(j),
                HomeFeedRow.Halves(l, ra),
                HomeFeedRow.Full(rp),
                HomeFeedRow.Halves(yp, rd),
                HomeFeedRow.Full(op),
            ),
            next.rows(),
        )
    }

    @Test
    fun should_returnSame_when_arrangementUnchanged() {
        assertSame(wide, wide.withArrangement(wide.enabledSections, wide.pairs))
    }

    @Test
    fun should_keepUnseenPair_when_arrangementOmitsIt() {
        // Without Gemini Nano the feed never shows Lyric Cards: its pair with Recently Added stays stored.
        val shown = wide.enabledSections - l
        val next = wide.withArrangement(shown, mapOf(a to j, yp to rd), unseen = setOf(l))
        assertSame(wide, next)
    }

    @Test
    fun should_pairWithPrevious_when_bothFull() {
        val next = wide.withBesidePrevious(f)
        assertEquals(mapOf(a to j, m to f, l to ra, yp to rd), next.pairs)
        // Beside a half: no.
        assertSame(wide, wide.withBesidePrevious(m))
        assertSame(wide, wide.withBesidePrevious(rp))
        // First, or already a half: no.
        assertSame(wide, wide.withBesidePrevious(a))
        assertSame(wide, wide.withBesidePrevious(j))
    }

}
