package com.gpo.yoin.ui.home.edit

import com.gpo.yoin.testutil.runFrameClockTest
import com.gpo.yoin.ui.experience.ExperienceSessionStore
import com.gpo.yoin.ui.home.HomeEditSessionHints
import com.gpo.yoin.ui.home.HomeFeedRow
import com.gpo.yoin.ui.home.HomeLayout
import com.gpo.yoin.ui.home.HomeRowPreset
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.homeFeedRows
import com.gpo.yoin.ui.home.unitRange
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edit mode across widths (owner 2026-11-23): one column changes order and visibility only, two
 * columns remember what they show, and a rotated draft — edited or not — keeps its order and takes
 * its pairs from the memory. No "edited layout stays one column in landscape".
 */
class HomeEditWideMemoryTest {

    private val a = HomeSection.Activities
    private val j = HomeSection.JumpBackIn
    private val m = HomeSection.MemoryGrid
    private val l = HomeSection.LyricHighlight
    private val ra = HomeSection.RecentlyAdded
    private val yp = HomeSection.YourPlaylists
    private val rd = HomeSection.Rediscover
    private val widePairs = mapOf(a to j, l to ra, yp to rd)

    private class Harness(scope: CoroutineScope, seed: HomeLayout = HomeLayout.Default) {
        val applied = mutableListOf<HomeLayout>()
        val controller = HomeEditController(
            store = ExperienceSessionStore(),
            scope = scope,
            applyLayout = { applied += it },
            onEditingChanged = {},
            startSession = { HomeEditSessionHints() },
            feedback = RecordingHomeEditFeedback(),
            reducedMotion = { false },
        ).also { it.onVmLayout(seed) }
    }

    private fun HomeLayout.twoColumnRows() = homeFeedRows(enabledSections, pairs, twoColumn = true)

    @Test
    fun should_pairFromMemory_when_rotatedToTwoColumnsAfterAPortraitEdit() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)
        // Portrait: Lyric Cards up beside Recently Added, Rediscover hidden.
        assertTrue(h.controller.move(l, -5))
        assertTrue(h.controller.hide(rd))
        val portrait = h.controller.draft!!

        h.controller.twoColumn = true

        val draft = h.controller.draft!!
        assertEquals(portrait.enabledSections, draft.enabledSections)
        assertEquals(widePairs, draft.widePairs)
        assertEquals(listOf(HomeFeedRow.Halves(a, j), HomeFeedRow.Halves(l, ra)), draft.twoColumnRows().take(2))
    }

    @Test
    fun should_keepOrderAndShowNoHalves_when_rotatedToOneColumnAfterALandscapeEdit() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.twoColumn = true
        h.controller.enter(null, lifted = false)
        assertTrue(h.controller.setHalf(m))
        val landscape = h.controller.draft!!

        h.controller.twoColumn = false

        assertSame(landscape, h.controller.draft)
        val rows = homeFeedRows(landscape.enabledSections, landscape.pairs, twoColumn = false)
        assertTrue(rows.all { it is HomeFeedRow.Full })
        // And back: the pairs it remembered.
        h.controller.twoColumn = true
        assertTrue(HomeFeedRow.Halves(m, HomeSection.MadeForYou) in h.controller.draft!!.twoColumnRows())
    }

    @Test
    fun should_forgetAPairTwoColumnsNoLongerShow_when_editedInTwoColumns() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.twoColumn = true
        h.controller.enter(null, lifted = false)
        assertTrue(h.controller.hide(j))
        assertEquals(mapOf(l to ra, yp to rd), h.controller.draft!!.widePairs)
        assertTrue(h.controller.show(j))
        assertEquals(listOf(HomeFeedRow.Full(a), HomeFeedRow.Full(j)), h.controller.draft!!.twoColumnRows().take(2))
        assertEquals(h.controller.draft, h.applied.last())
    }

    @Test
    fun should_rememberAPairOneColumnHides_when_editedInOneColumn() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)
        assertTrue(h.controller.hide(j))
        assertEquals(widePairs, h.controller.draft!!.widePairs)
        assertTrue(h.controller.show(j))
        h.controller.twoColumn = true
        assertEquals(HomeFeedRow.Halves(a, j), h.controller.draft!!.twoColumnRows().first())
    }

    @Test
    fun should_takeTheOtherWidthsDefault_when_rotatedAfterAReset() = runFrameClockTest { scope ->
        val h = Harness(scope, seed = HomeLayout.Default.withEnabled(rd, false))
        h.controller.enter(null, lifted = false)
        assertTrue(h.controller.reset())
        assertTrue(h.controller.draft!!.isDefault)

        h.controller.twoColumn = true
        assertTrue(h.controller.draft!!.isWideDefault)
        h.controller.twoColumn = false
        assertTrue(h.controller.draft!!.isDefault)
    }

    @Test
    fun should_undoToThisWidthsDefault_when_theStepWasTakenAtTheOtherWidth() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)
        assertTrue(h.controller.hide(m))

        h.controller.twoColumn = true
        assertTrue(h.controller.undo())

        assertTrue(h.controller.draft!!.isWideDefault)
    }

    @Test
    fun should_giveTheReappliedPairOneHeight_when_rotatedMidEdit() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)
        // Portrait: Jump Back In at its phone XL, Activities at its default.
        assertTrue(h.controller.setRows(j, HomeRowPreset.XL))

        h.controller.twoColumn = true

        val draft = h.controller.draft!!
        val row = draft.twoColumnRows().first()
        assertEquals(HomeFeedRow.Halves(a, j), row)
        val units = draft.unitsOf(row)
        assertTrue(units in a.unitRange && units in j.unitRange)
        assertTrue(units <= minOf(a.unitRange.last, j.unitRange.last))
    }
}
