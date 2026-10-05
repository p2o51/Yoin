package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.unit.IntOffset
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-05 device QA (问题 3): when the 5s undo window closed, the undo row
 * collapsed and the next note slid under the finger — a late tap meant for
 * 撤销 sought instead. The window now waits for a finger on the list to lift,
 * and a tap that comes down on a row still gliding into the collapsed row's
 * place is dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteUndoCollapseTest {

    private fun note(id: String, at: Long?) = SongNote(
        id = id,
        trackId = "nq-t1",
        content = "placeholder $id",
        createdAt = 1L,
        updatedAt = 1L,
        title = "Harbour Lights",
        artist = "Night QA Band",
        positionMs = at,
    )

    private val early = note("early", 12_000L)
    private val middle = note("middle", 48_000L)
    private val late = note("late", 83_000L)

    // ── The undo window waits for the finger ──────────────────────────────

    @Test
    fun should_closeTheUndoWindowOnTime_when_noFingerIsOnTheList() = runTest {
        val deletion = NoteDeletionState()
        val committed = mutableListOf<String>()
        deletion.delete(middle) {}
        launch { deletion.runUndoWindow { committed += it } }

        advanceTimeBy(NoteUndoWindowMs - 1)
        runCurrent()
        assertTrue(committed.isEmpty())
        advanceTimeBy(2)
        runCurrent()

        assertEquals(listOf("middle"), committed)
        assertNull(deletion.pending)
    }

    @Test
    fun should_keepTheUndoRowUntilTheFingerLifts_when_theWindowEndsUnderATouch() = runTest {
        val deletion = NoteDeletionState()
        val committed = mutableListOf<String>()
        deletion.delete(middle) {}
        launch { deletion.runUndoWindow { committed += it } }

        deletion.onListHeldChange(true)
        advanceTimeBy(NoteUndoWindowMs + 2_000L)
        runCurrent()
        assertTrue("held: the undo row stays put", committed.isEmpty())
        assertEquals(middle, deletion.pending)

        deletion.onListHeldChange(false)
        runCurrent()
        assertEquals(listOf("middle"), committed)
    }

    @Test
    fun should_restoreTheNote_when_theHeldTapWasOnUndo() = runTest {
        val deletion = NoteDeletionState()
        val committed = mutableListOf<String>()
        deletion.delete(middle) {}
        val window = launch { deletion.runUndoWindow { committed += it } }

        deletion.onListHeldChange(true)
        advanceTimeBy(NoteUndoWindowMs + 300L)
        runCurrent()
        // The finger lifts on 撤销: its click runs before the hold ends (Final pass).
        assertEquals(middle, deletion.undo())
        deletion.onListHeldChange(false)
        runCurrent()

        assertTrue("nothing left to delete", committed.isEmpty())
        assertFalse(window.isActive)
    }

    // ── Rows gliding into the collapsed row's place ───────────────────────

    private val withUndo = listOf(NoteRow.Line(early), NoteRow.Deleted(middle), NoteRow.Line(late))
    private val collapsed = listOf(NoteRow.Line(early), NoteRow.Line(late))
    private val undone = listOf(NoteRow.Line(early), NoteRow.Line(middle), NoteRow.Line(late))

    @Test
    fun should_findWhereTheUndoRowLeft_when_itsNoteIsGoneForGood() {
        assertEquals(1, collapsedUndoRowIndex(withUndo, collapsed))
    }

    @Test
    fun should_seeNoCollapse_when_undoneOrNothingWasPending() {
        assertEquals(-1, collapsedUndoRowIndex(withUndo, undone))
        assertEquals(-1, collapsedUndoRowIndex(undone, collapsed))
        assertEquals(-1, collapsedUndoRowIndex(emptyList(), collapsed))
    }

    @Test
    fun should_dropATapOnAMovedRow_when_itCameDownWhileTheRowsGlide() = runTest {
        val guard = NoteCollapseTapGuard()
        guard.onRows(withUndo, glideMs = 300L, scope = backgroundScope)
        guard.onRows(collapsed, glideMs = 300L, scope = backgroundScope)
        runCurrent()

        guard.onDown()
        assertTrue("late slid up into the undo row's place", guard.ignores(1))
        assertFalse("early never moved", guard.ignores(0))
    }

    @Test
    fun should_answerTaps_when_theGlideHasSettled() = runTest {
        val guard = NoteCollapseTapGuard()
        guard.onRows(withUndo, glideMs = 300L, scope = backgroundScope)
        guard.onRows(collapsed, glideMs = 300L, scope = backgroundScope)
        runCurrent()

        advanceTimeBy(301L)
        runCurrent()
        guard.onDown()

        assertFalse(guard.ignores(1))
    }

    @Test
    fun should_neverGuard_when_theUndoWasTakenBackOrRowsJustChanged() = runTest {
        val guard = NoteCollapseTapGuard()
        guard.onRows(withUndo, glideMs = 300L, scope = backgroundScope)
        guard.onRows(undone, glideMs = 300L, scope = backgroundScope)
        // The store's list re-emits after the delete lands: same rows, new list.
        guard.onRows(undone.toList(), glideMs = 300L, scope = backgroundScope)
        runCurrent()

        guard.onDown()

        assertFalse(guard.ignores(1))
        assertFalse(guard.ignores(2))
    }

    @Test
    fun should_keepGuardingTheSameGesture_when_theGlideEndsBeforeTheLift() = runTest {
        val guard = NoteCollapseTapGuard()
        guard.onRows(withUndo, glideMs = 300L, scope = backgroundScope)
        guard.onRows(collapsed, glideMs = 300L, scope = backgroundScope)
        runCurrent()

        // Down during the glide, a slow lift after it: still aimed at the old row.
        guard.onDown()
        advanceTimeBy(400L)
        runCurrent()

        assertTrue(guard.ignores(1))
    }

    // ── The guard's window, read off the placement spring ─────────────────

    private val placementSpec = YoinMotion.defaultSpatialSpec<IntOffset>(
        role = YoinMotionRole.Expressive,
        expressiveScheme = MotionScheme.expressive(),
    )

    private fun offsetAt(ms: Long, travelPx: Int): IntOffset {
        val converter = IntOffset.VectorConverter
        val glide = placementSpec.vectorize(converter)
        val from: AnimationVector2D = converter.convertToVector(IntOffset(0, travelPx))
        val zero = converter.convertToVector(IntOffset.Zero)
        return converter.convertFromVector(glide.getValueFromNanos(ms * 1_000_000L, from, zero, zero))
    }

    @Test
    fun should_lastExactlyWhileTheRowIsVisiblyDisplaced_when_readOffThePlacementSpring() {
        val travelPx = 96 // the 48dp undo row at the tablet's 2× density
        val glideMs = placementGlideMillis(placementSpec, travelPx)

        assertTrue(offsetAt(glideMs, travelPx) != IntOffset.Zero)
        for (ms in glideMs + 1..glideMs + 1_000L) {
            assertEquals("still at ${ms}ms", IntOffset.Zero, offsetAt(ms, travelPx))
        }
        // A glance, not a wait: a few hundred ms (QA asked for about 300).
        assertTrue("guard $glideMs ms", glideMs in 150L..600L)
    }

    @Test
    fun should_guardLonger_when_theRowsTravelFurther() {
        assertTrue(placementGlideMillis(placementSpec, 192) > placementGlideMillis(placementSpec, 96))
        assertEquals(0L, placementGlideMillis(placementSpec, 0))
    }
}
