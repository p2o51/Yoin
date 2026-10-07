package com.gpo.yoin.ui.home.edit

import com.gpo.yoin.testutil.runFrameClockTest
import com.gpo.yoin.ui.component.BarEditLeftSlot
import com.gpo.yoin.ui.experience.ExperienceSessionStore
import com.gpo.yoin.ui.experience.HomeSurface
import com.gpo.yoin.ui.home.HomeEditSessionHints
import com.gpo.yoin.ui.home.HomeLayout
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.HomeSectionState
import com.gpo.yoin.ui.navigation.YoinSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeEditControllerTest {

    @Test
    fun should_setEditSurfaceAndAnimateProgress_when_entering() = runFrameClockTest { scope ->
        val h = Harness(scope)

        h.controller.enter(HomeSection.JumpBackIn, lifted = true)

        assertTrue(h.controller.isEditing)
        assertEquals(HomeSurface.Edit, h.store.state.value.homeSurface)
        assertEquals(1f, h.controller.progress.target, 0f)
        assertTrue(h.controller.progress.isAnimating)
        assertEquals(listOf("enter:jump_back_in:true"), h.layer.events)
        assertEquals(1, h.sessions)
        assertTrue(h.controller.sessionHints.showHeaderHint)
        assertTrue(h.controller.draft!!.sameSectionsAs(HomeLayout.Default))

        advanceUntilIdle()
        assertEquals(1f, h.controller.progressReader(), 0f)
        assertFalse(h.controller.progress.isAnimating)
    }

    @Test
    fun should_notApplyLayout_when_enteringAndExiting() = runFrameClockTest { scope ->
        val h = Harness(scope)

        h.controller.enter(null, lifted = false)
        advanceUntilIdle()
        h.controller.commitAndExit()
        advanceUntilIdle()

        assertTrue(h.applied.isEmpty())
        assertEquals(0f, h.controller.progress.value, 0f)
    }

    @Test
    fun should_freezeVm_when_entering() = runFrameClockTest { scope ->
        val h = Harness(scope)

        h.controller.enter(null, lifted = false)
        assertEquals(listOf(true), h.editing)

        h.controller.commitAndExit(HomeEditExitReason.Back)
        assertEquals(listOf(true, false), h.editing)
    }

    @Test
    fun should_snapSurfaceAndProgressSynchronously_when_snapExit() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(HomeSection.Activities, lifted = false)
        advanceTimeBy(80)
        runCurrent()
        assertTrue(h.controller.progress.value > 0f)

        h.controller.snapExit()

        // Same call: no frame in between.
        assertFalse(h.controller.isEditing)
        assertEquals(HomeSurface.Feed, h.store.state.value.homeSurface)
        assertEquals(0f, h.controller.progress.value, 0f)
        assertFalse(h.controller.progress.isAnimating)
        assertEquals(listOf(true, false), h.editing)
        assertEquals("snapExit", h.layer.events.last())
        assertNull(h.controller.draft)
    }

    @Test
    fun should_pushUndoPersistAndNotifyLayer_when_hiding() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)

        assertTrue(h.controller.hide(HomeSection.RecentlyAdded))

        val expected = HomeLayout.Default.withEnabled(HomeSection.RecentlyAdded, false)
        assertEquals(1, h.controller.undoDepth)
        assertEquals(listOf(expected), h.applied)
        assertEquals(expected, h.controller.draft)
        assertEquals(listOf("toggleOff"), h.feedback.events)
        val change = h.layer.changes.single()
        assertEquals(HomeEditChangeKind.Hide, change.kind)
        assertEquals(HomeSection.RecentlyAdded, change.subject)
        assertEquals(HomeLayout.Default, change.previous)
        assertEquals(expected, change.next)
        assertEquals(1, h.controller.trayCount)
        assertTrue(h.controller.canReset)
    }

    @Test
    fun should_returnFalseWithoutSideEffects_when_showingEnabledSection() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)

        assertFalse(h.controller.show(HomeSection.Activities))

        assertTrue(h.applied.isEmpty())
        assertTrue(h.feedback.events.isEmpty())
        assertTrue(h.layer.changes.isEmpty())
        assertEquals(0, h.controller.undoDepth)
    }

    @Test
    fun should_capUndoAtTwenty() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)

        repeat(25) { step ->
            if (step % 2 == 0) {
                assertTrue(h.controller.hide(HomeSection.Activities))
            } else {
                assertTrue(h.controller.show(HomeSection.Activities))
            }
        }

        assertEquals(HomeEditTokens.UndoMax, h.controller.undoDepth)
        repeat(HomeEditTokens.UndoMax) { assertTrue(h.controller.undo()) }
        assertFalse(h.controller.undo())
        // 25 ops, the 5 oldest entries dropped: it bottoms out after the 5th op, a hide.
        assertFalse(h.controller.draft!!.enabledSections.contains(HomeSection.Activities))
    }

    @Test
    fun should_restorePreviousLayout_when_undo() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)
        h.controller.hide(HomeSection.RecentlyAdded)
        h.feedback.clear()

        assertTrue(h.controller.undo())

        assertTrue(h.controller.draft!!.sameSectionsAs(HomeLayout.Default))
        assertTrue(h.applied.last().sameSectionsAs(HomeLayout.Default))
        assertEquals(listOf("click"), h.feedback.events)
        assertEquals(HomeEditChangeKind.Undo, h.layer.changes.last().kind)
        assertEquals(0, h.controller.undoDepth)
    }

    @Test
    fun should_clearUndo_when_exiting() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)
        h.controller.hide(HomeSection.RecentlyAdded)
        assertEquals(1, h.controller.undoDepth)

        h.controller.commitAndExit()
        assertEquals(0, h.controller.undoDepth)

        h.controller.enter(null, lifted = false)
        assertEquals(0, h.controller.undoDepth)
        assertFalse(h.controller.undo())
    }

    @Test
    fun should_deferExit_when_layerIsCarrying() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)
        advanceUntilIdle()
        h.layer.isCarrying = true

        h.controller.commitAndExit(HomeEditExitReason.Done)

        assertTrue(h.controller.isEditing)
        assertEquals(HomeSurface.Edit, h.store.state.value.homeSurface)
        assertEquals("defer:Done", h.layer.events.last())
        assertFalse(h.feedback.events.contains("confirm"))
        assertEquals(1f, h.controller.progress.value, 0f)
        assertNotNull(h.controller.deferredExit)
    }

    @Test
    fun should_exitAfterFinish_when_finishDeferredExit() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)
        advanceUntilIdle()
        h.layer.isCarrying = true
        h.controller.commitAndExit(HomeEditExitReason.Done)
        val deferred = h.controller.deferredExit!!

        h.layer.isCarrying = false
        h.controller.finishDeferredExit(HomeEditExitReason.Done)

        assertFalse(h.controller.isEditing)
        assertEquals(HomeSurface.Feed, h.store.state.value.homeSurface)
        assertEquals(listOf("confirm"), h.feedback.events)
        assertEquals("exit:Done", h.layer.events.last())
        assertTrue(deferred.isCompleted)
        assertNull(h.controller.deferredExit)
        advanceUntilIdle()
        assertEquals(0f, h.controller.progress.value, 0f)
    }

    @Test
    fun should_confirmHapticOnlyForDone() = runFrameClockTest { scope ->
        val h = Harness(scope)
        for (reason in listOf(
            HomeEditExitReason.Back,
            HomeEditExitReason.Blank,
            HomeEditExitReason.Programmatic,
        )) {
            h.controller.enter(null, lifted = false)
            h.controller.commitAndExit(reason)
        }
        assertTrue(h.feedback.events.isEmpty())

        h.controller.enter(null, lifted = false)
        h.controller.barDone()

        assertEquals(listOf("confirm"), h.feedback.events)
        assertFalse(h.controller.isEditing)
    }

    @Test
    fun should_keepEchoHold_until_vmLayoutMatches() = runFrameClockTest { scope ->
        val h = Harness(scope)
        val hidden = HomeLayout.Default.withEnabled(HomeSection.RecentlyAdded, false)
        h.controller.enter(null, lifted = false)
        h.controller.hide(HomeSection.RecentlyAdded)
        h.controller.commitAndExit()

        // The write has not echoed yet: the feed keeps the edited layout.
        assertTrue(h.controller.layoutToRender(HomeLayout.Default).sameSectionsAs(hidden))
        h.controller.onVmLayout(HomeLayout.Default)
        assertTrue(h.controller.layoutToRender(HomeLayout.Default).sameSectionsAs(hidden))

        h.controller.onVmLayout(hidden)
        val vm = HomeLayout.Default.withEnabled(HomeSection.Activities, false)
        assertSame(vm, h.controller.layoutToRender(vm))
    }

    @Test
    fun should_seedDraftFromEchoHold_when_reenteringBeforeEcho() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)
        h.controller.hide(HomeSection.Activities)
        h.controller.commitAndExit()

        h.controller.enter(null, lifted = false)

        assertFalse(h.controller.draft!!.enabledSections.contains(HomeSection.Activities))
    }

    @Test
    fun should_dropEchoHold_when_profileSwitched() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)
        h.controller.hide(HomeSection.Activities)
        h.controller.commitAndExit()

        h.controller.onProfileSwitched()

        assertSame(HomeLayout.Default, h.controller.layoutToRender(HomeLayout.Default))
    }

    @Test
    fun should_resolveLeftSlot_from_undoDepthAndTray() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)
        assertEquals(BarEditLeftSlot.UndoDisabled, h.controller.leftSlot)
        h.controller.snapExit()

        h.controller.onVmLayout(HomeLayout.Default.withEnabled(HomeSection.RecentlyAdded, false))
        h.controller.enter(null, lifted = false)
        assertEquals(BarEditLeftSlot.Add, h.controller.leftSlot)

        h.controller.show(HomeSection.RecentlyAdded)
        assertEquals(BarEditLeftSlot.Undo, h.controller.leftSlot)

        h.controller.undo()
        assertEquals(BarEditLeftSlot.Add, h.controller.leftSlot)
    }

    @Test
    fun should_moveWithinEnabledOrder_when_moveCalled() = runFrameClockTest { scope ->
        val h = Harness(scope)
        // [A, j̶, R, D]: the hidden JBI keeps its absolute slot.
        h.controller.onVmLayout(HomeLayout.Default.withEnabled(HomeSection.JumpBackIn, false))
        h.controller.enter(null, lifted = false)

        assertTrue(h.controller.move(HomeSection.Activities, 1))

        assertEquals(
            listOf(
                HomeSectionState(HomeSection.RecentlyAdded, true),
                HomeSectionState(HomeSection.JumpBackIn, false),
                HomeSectionState(HomeSection.Activities, true),
                HomeSectionState(HomeSection.Rediscover, true),
                HomeSectionState(HomeSection.RecentlyPlayed, true),
                HomeSectionState(HomeSection.YourPlaylists, true),
            ),
            h.controller.draft!!.sections,
        )
        assertEquals(listOf("segmentTick"), h.feedback.events)
        assertEquals(HomeEditChangeKind.Move, h.layer.changes.single().kind)
        assertEquals(HomeSection.Activities, h.layer.changes.single().subject)

        // Already first / last / not enabled: nothing happens.
        assertFalse(h.controller.move(HomeSection.RecentlyAdded, -1))
        assertFalse(h.controller.move(HomeSection.YourPlaylists, 1))
        assertFalse(h.controller.move(HomeSection.JumpBackIn, 1))
        assertEquals(1, h.applied.size)
        assertEquals(1, h.controller.undoDepth)
    }

    @Test
    fun should_confirmOnlyWhenChanged_when_commitOrder() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)
        // A carry commits while its session is still alive.
        h.layer.isCarrying = true

        assertFalse(h.controller.commitOrder(HomeLayout.Default.enabledSections))
        assertTrue(h.feedback.events.isEmpty())
        assertEquals(0, h.controller.undoDepth)

        val order = listOf(
            HomeSection.RecentlyAdded,
            HomeSection.Activities,
            HomeSection.JumpBackIn,
            HomeSection.Rediscover,
            HomeSection.RecentlyPlayed,
            HomeSection.YourPlaylists,
        )
        assertTrue(h.controller.commitOrder(order))

        assertEquals(listOf("confirm"), h.feedback.events)
        assertEquals(order, h.controller.draft!!.enabledSections)
        assertEquals(order, h.applied.single().enabledSections)
        assertEquals(HomeEditChangeKind.Order, h.layer.changes.single().kind)
        assertEquals(1, h.controller.undoDepth)
    }

    @Test
    fun should_healProgress_when_constructedWhileEditing() = runFrameClockTest { scope ->
        val store = ExperienceSessionStore()
        // A previous controller entered edit, then the Activity was recreated.
        store.setHomeSurface(HomeSurface.Edit)
        store.homeEditProgress.snapTo(0.4f)

        val h = Harness(scope, store, seed = null)

        assertTrue(h.controller.isEditing)
        assertEquals(1f, h.controller.progress.value, 0f)
        assertEquals(listOf(true), h.editing)
        assertNull(h.controller.draft)

        val layout = HomeLayout.Default.withEnabled(HomeSection.Rediscover, false)
        h.controller.onVmLayout(layout)
        assertSame(layout, h.controller.draft)
        // Only the first layout seeds it.
        h.controller.onVmLayout(HomeLayout.Default)
        assertSame(layout, h.controller.draft)
    }

    @Test
    fun should_snapProgressToRest_when_constructedOutsideEdit() = runFrameClockTest { scope ->
        val store = ExperienceSessionStore()
        store.homeEditProgress.snapTo(0.4f)

        val h = Harness(scope, store)

        assertFalse(h.controller.isEditing)
        assertEquals(0f, h.controller.progress.value, 0f)
        assertTrue(h.editing.isEmpty())
    }

    @Test
    fun should_ignoreEnter_when_surfaceIsMemories() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.store.setHomeSurface(HomeSurface.Memories)

        h.controller.enter(HomeSection.Activities, lifted = true)

        assertFalse(h.controller.isEditing)
        assertEquals(HomeSurface.Memories, h.store.state.value.homeSurface)
        assertEquals(0, h.sessions)
        assertTrue(h.editing.isEmpty())
        assertTrue(h.layer.events.isEmpty())
        assertFalse(h.controller.progress.isAnimating)
    }

    @Test
    fun should_ignoreEnter_when_libraryIsSelected() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.store.setSelectedSection(YoinSection.LIBRARY)

        h.controller.enter(null, lifted = false)

        assertFalse(h.controller.isEditing)
        assertEquals(HomeSurface.Feed, h.store.state.value.homeSurface)
        assertEquals(0, h.sessions)
    }

    @Test
    fun should_touchAndScrollToTray_when_barAddClicked() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.onVmLayout(HomeLayout.Default.withEnabled(HomeSection.RecentlyAdded, false))
        h.controller.enter(null, lifted = false)
        h.layer.events.clear()

        h.controller.barLeftSlotClick()

        assertEquals(listOf("touch", "scrollToTray"), h.layer.events)
        assertEquals(listOf("click"), h.feedback.events)
        assertTrue(h.applied.isEmpty())
        assertTrue(h.layer.changes.isEmpty())
    }

    @Test
    fun should_undo_when_barUndoClicked() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)
        h.controller.hide(HomeSection.Activities)
        h.layer.events.clear()
        h.feedback.clear()

        h.controller.barLeftSlotClick()

        assertEquals(listOf("touch"), h.layer.events)
        assertEquals(listOf("click"), h.feedback.events)
        assertTrue(h.controller.draft!!.sameSectionsAs(HomeLayout.Default))
    }

    @Test
    fun should_onlyTouch_when_disabledUndoClicked() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)
        assertEquals(BarEditLeftSlot.UndoDisabled, h.controller.leftSlot)
        h.layer.events.clear()

        h.controller.barLeftSlotClick()

        assertEquals(listOf("touch"), h.layer.events)
        assertTrue(h.feedback.events.isEmpty())
        assertTrue(h.applied.isEmpty())
        assertTrue(h.controller.isEditing)
    }

    @Test
    fun should_ignoreLayoutOps_when_carrying() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.onVmLayout(HomeLayout.Default.withEnabled(HomeSection.RecentlyAdded, false))
        h.controller.enter(null, lifted = false)
        h.controller.hide(HomeSection.Activities)
        h.feedback.clear()
        h.layer.events.clear()
        h.layer.isCarrying = true

        assertFalse(h.controller.hide(HomeSection.JumpBackIn))
        assertFalse(h.controller.show(HomeSection.RecentlyAdded))
        assertFalse(h.controller.reset())
        assertFalse(h.controller.undo())
        assertFalse(h.controller.move(HomeSection.JumpBackIn, 1))
        h.controller.barLeftSlotClick()

        assertTrue(h.feedback.events.isEmpty())
        assertEquals(1, h.applied.size)
        assertEquals(1, h.controller.undoDepth)
        assertEquals(listOf("touch"), h.layer.events)
    }

    @Test
    fun should_resetToDefaultKeepingUndo_when_reset() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)
        assertFalse(h.controller.canReset)
        assertFalse(h.controller.reset())

        h.controller.hide(HomeSection.JumpBackIn)
        h.feedback.clear()
        assertTrue(h.controller.reset())

        assertTrue(h.controller.draft!!.isDefault)
        assertFalse(h.controller.canReset)
        assertEquals(listOf("reject"), h.feedback.events)
        assertEquals(HomeEditChangeKind.Reset, h.layer.changes.last().kind)
        assertEquals(2, h.controller.undoDepth)
    }

    @Test
    fun should_resetWithoutWritingSurface_when_surfaceLost() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)
        advanceTimeBy(80)
        runCurrent()
        // Something else took the surface (say Memories) while editing.
        h.store.setHomeSurface(HomeSurface.Memories)

        h.controller.onSurfaceLost()

        assertEquals(HomeSurface.Memories, h.store.state.value.homeSurface)
        assertFalse(h.controller.isEditing)
        assertEquals(0f, h.controller.progress.value, 0f)
        assertEquals(listOf(true, false), h.editing)
        assertEquals("snapExit", h.layer.events.last())
        assertNull(h.controller.draft)
    }

    @Test
    fun should_leaveSurfaceAlone_when_snapExitWhileNotEditing() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.store.setHomeSurface(HomeSurface.Memories)

        // ON_STOP with Memories open.
        h.controller.snapExit()

        assertEquals(HomeSurface.Memories, h.store.state.value.homeSurface)
        assertTrue(h.layer.events.isEmpty())
        assertTrue(h.editing.isEmpty())
    }

    @Test
    fun should_snapTheExitSpring_when_snapExitAfterDone() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)
        advanceUntilIdle()
        h.controller.commitAndExit()
        advanceTimeBy(48)
        runCurrent()
        assertTrue(h.controller.progress.value in 0.01f..0.99f)

        h.controller.snapExit()

        assertEquals(0f, h.controller.progress.value, 0f)
        assertEquals("snapExit", h.layer.events.last())
        // Already out: the session ended once.
        assertEquals(listOf(true, false), h.editing)
    }

    @Test
    fun should_commitChangedOrderFirst_when_snapExitDuringCarry() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)
        val order = HomeLayout.Default.enabledSections.reversed()
        h.layer.isCarrying = true
        h.layer.onAbort = {
            h.controller.commitOrder(order)
            h.layer.isCarrying = false
        }

        h.controller.snapExit()

        assertEquals(order, h.applied.single().enabledSections)
        assertEquals(listOf("abort", "snapExit"), h.layer.events.takeLast(2))
        assertFalse(h.controller.isEditing)
        // The committed order is what the feed keeps until the write echoes.
        assertEquals(order, h.controller.layoutToRender(HomeLayout.Default).enabledSections)
    }

    @Test
    fun should_awaitDeferredExit_when_exitingDuringCarry() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)
        advanceUntilIdle()
        h.layer.isCarrying = true

        val exited = scope.launch { h.controller.commitAndExitAndAwait() }
        advanceUntilIdle()
        assertFalse(exited.isCompleted)
        assertTrue(h.controller.isEditing)

        h.layer.isCarrying = false
        h.controller.finishDeferredExit(HomeEditExitReason.Programmatic)
        runCurrent()
        assertFalse(exited.isCompleted)
        advanceUntilIdle()

        assertTrue(exited.isCompleted)
        assertEquals(0f, h.controller.progress.value, 0f)
    }

    @Test
    fun should_finishDeferredExit_when_layerLeavesMidCarry() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)
        h.layer.isCarrying = true
        h.controller.commitAndExit(HomeEditExitReason.Back)
        val deferred = h.controller.deferredExit!!

        h.controller.detachLayer(h.layer)

        assertNull(h.controller.layer)
        assertFalse(h.controller.isEditing)
        assertTrue(deferred.isCompleted)
    }

    @Test
    fun should_keepNewerLayer_when_olderLayerDetaches() = runFrameClockTest { scope ->
        val h = Harness(scope)
        val newer = FakeLayer()
        h.controller.layer = newer

        h.controller.detachLayer(h.layer)
        assertSame(newer, h.controller.layer)

        h.controller.detachLayer(newer)
        assertNull(h.controller.layer)
    }

    @Test
    fun should_dropEchoHoldAtOnce_when_exitingWithoutChanges() = runFrameClockTest { scope ->
        val h = Harness(scope)
        h.controller.enter(null, lifted = false)
        // Changed, then changed back: the draft ends where the VM is.
        h.controller.hide(HomeSection.Activities)
        h.controller.show(HomeSection.Activities)
        h.controller.commitAndExit()

        // Another writer (say a sync) moves the layout on; the feed follows it.
        val vm = HomeLayout.Default.withEnabled(HomeSection.Rediscover, false)
        assertSame(vm, h.controller.layoutToRender(vm))
    }

    private class Harness(
        scope: CoroutineScope,
        val store: ExperienceSessionStore = ExperienceSessionStore(),
        seed: HomeLayout? = HomeLayout.Default,
    ) {
        val applied = mutableListOf<HomeLayout>()
        val editing = mutableListOf<Boolean>()
        var sessions = 0
        val feedback = RecordingHomeEditFeedback()
        val layer = FakeLayer()
        val controller = HomeEditController(
            store = store,
            scope = scope,
            applyLayout = { applied += it },
            onEditingChanged = { editing += it },
            startSession = {
                sessions++
                HomeEditSessionHints(showHeaderHint = true)
            },
            feedback = feedback,
            reducedMotion = { false },
        ).also { controller ->
            controller.layer = layer
            seed?.let(controller::onVmLayout)
        }
    }

    private class FakeLayer : HomeEditLayer {
        override var isCarrying = false
        val events = mutableListOf<String>()
        val changes = mutableListOf<HomeEditChange>()
        var onAbort: () -> Unit = {}

        override fun onEnter(origin: HomeSection?, lifted: Boolean) {
            events += "enter:${origin?.id}:$lifted"
        }

        override fun onExit(reason: HomeEditExitReason) {
            events += "exit:$reason"
        }

        override fun onSnapExit() {
            events += "snapExit"
        }

        override fun onTouch() {
            events += "touch"
        }

        override fun onLayoutChange(change: HomeEditChange) {
            changes += change
        }

        override fun deferExit(reason: HomeEditExitReason) {
            events += "defer:$reason"
        }

        override fun abortCarry() {
            events += "abort"
            onAbort()
        }

        override fun scrollToTray() {
            events += "scrollToTray"
        }
    }
}
