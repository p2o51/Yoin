package com.gpo.yoin.ui.detail

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailEnterIntroTest {
    @Test
    fun should_waitForMountAndVisibleCommitBeforeTick_when_contentIsReadyImmediately() =
        runBlocking {
            var now = 0L
            val events = mutableListOf<String>()

            val started = coordinateDetailEnter(
                barHandoff = true,
                nowMillis = { now },
                awaitInitialCommit = {
                    events += "initialCommit"
                    true
                },
                awaitVisualReadyOrTimeout = {
                    events += "ready"
                    true
                },
                delayMillis = { millis ->
                    events += "delay:$millis"
                    now += millis
                },
                awaitBackIdleOrAbort = {
                    events += "backIdle"
                    true
                },
                revealPage = { events += "visible" },
                hidePage = { events += "hidden" },
                awaitPageMount = {
                    events += "mounted"
                    true
                },
                awaitVisibleCommit = {
                    events += "visibleCommit"
                    true
                },
                settlePageWithoutTick = { events += "settledWithoutTick" },
                noteSlideStarted = { events += "tick" },
            )

            assertTrue(started)
            assertEquals(
                listOf(
                    "initialCommit",
                    "ready",
                    "backIdle",
                    "delay:200",
                    "backIdle",
                    "visible",
                    "mounted",
                    "backIdle",
                    "visibleCommit",
                    "backIdle",
                    "tick",
                ),
                events,
            )
        }

    @Test
    fun should_notAddAnotherHold_when_contentBecomesReadyAfterHandoffWindow() = runBlocking {
        var now = 0L
        val events = mutableListOf<String>()

        val started = coordinateDetailEnter(
            barHandoff = true,
            nowMillis = { now },
            awaitInitialCommit = { true },
            awaitVisualReadyOrTimeout = {
                now = 350L
                true
            },
            delayMillis = { events += "unexpectedDelay" },
            awaitBackIdleOrAbort = { true },
            revealPage = { events += "visible" },
            hidePage = { events += "hidden" },
            awaitPageMount = {
                events += "mounted"
                true
            },
            awaitVisibleCommit = {
                events += "visibleCommit"
                true
            },
            settlePageWithoutTick = { events += "settledWithoutTick" },
            noteSlideStarted = { events += "tick" },
        )

        assertTrue(started)
        assertEquals(listOf("visible", "mounted", "visibleCommit", "tick"), events)
    }

    @Test
    fun should_revealOpaqueLoadingFallbackAndCommitBeforeTick_when_readyTimesOut() = runBlocking {
        var now = 0L
        val events = mutableListOf<String>()

        val started = coordinateDetailEnter(
            barHandoff = true,
            nowMillis = { now },
            awaitInitialCommit = { true },
            awaitVisualReadyOrTimeout = {
                now = VISUAL_READY_TIMEOUT_MS
                events += "timeout"
                false
            },
            delayMillis = { events += "unexpectedDelay" },
            awaitBackIdleOrAbort = { true },
            revealPage = { events += "loadingVisible" },
            hidePage = { events += "hidden" },
            awaitPageMount = {
                events += "mounted"
                true
            },
            awaitVisibleCommit = {
                events += "visibleCommit"
                true
            },
            settlePageWithoutTick = { events += "settledWithoutTick" },
            noteSlideStarted = { events += "tick" },
        )

        assertTrue(started)
        assertEquals(
            listOf("timeout", "loadingVisible", "mounted", "visibleCommit", "tick"),
            events,
        )
    }

    @Test
    fun should_abortWithoutRevealOrTick_when_backCommitsDuringVisualWait() = runBlocking {
        var committed = false
        val events = mutableListOf<String>()

        val started = coordinateDetailEnter(
            barHandoff = false,
            nowMillis = { 0L },
            awaitInitialCommit = { true },
            awaitVisualReadyOrTimeout = {
                committed = true
                events += "committed"
                true
            },
            delayMillis = { events += "delay" },
            awaitBackIdleOrAbort = { !committed },
            revealPage = { events += "visible" },
            hidePage = { events += "hidden" },
            awaitPageMount = { true },
            awaitVisibleCommit = { true },
            settlePageWithoutTick = { events += "settledWithoutTick" },
            noteSlideStarted = { events += "tick" },
        )

        assertFalse(started)
        assertEquals(listOf("committed"), events)
    }

    @Test
    fun should_hideWithoutTick_when_backCommitsDuringVisibleCommit() = runBlocking {
        var committed = false
        val events = mutableListOf<String>()

        val started = coordinateDetailEnter(
            barHandoff = false,
            nowMillis = { 0L },
            awaitInitialCommit = { true },
            awaitVisualReadyOrTimeout = { true },
            delayMillis = { events += "delay" },
            awaitBackIdleOrAbort = { !committed },
            revealPage = { events += "visible" },
            hidePage = { events += "hidden" },
            awaitPageMount = {
                events += "mounted"
                true
            },
            awaitVisibleCommit = {
                events += "visibleCommit"
                committed = true
                true
            },
            settlePageWithoutTick = { events += "settledWithoutTick" },
            noteSlideStarted = { events += "tick" },
        )

        assertFalse(started)
        assertEquals(listOf("visible", "mounted", "visibleCommit", "hidden"), events)
    }

    @Test
    fun should_resumeOnceAfterGestureCancel_andTickOnlyOnce() = runBlocking {
        var gestureActive = true
        var tickCount = 0
        val events = mutableListOf<String>()

        val started = coordinateDetailEnter(
            barHandoff = false,
            nowMillis = { 0L },
            awaitInitialCommit = { true },
            awaitVisualReadyOrTimeout = { true },
            delayMillis = { events += "delay" },
            awaitBackIdleOrAbort = {
                if (gestureActive) {
                    events += "waitForCancel"
                    gestureActive = false
                }
                true
            },
            revealPage = { events += "visible" },
            hidePage = { events += "hidden" },
            awaitPageMount = {
                events += "mounted"
                true
            },
            awaitVisibleCommit = {
                events += "visibleCommit"
                true
            },
            settlePageWithoutTick = { events += "settledWithoutTick" },
            noteSlideStarted = { tickCount++ },
        )

        assertTrue(started)
        assertEquals(1, tickCount)
        assertEquals(listOf("waitForCancel", "visible", "mounted", "visibleCommit"), events)
    }

    @Test
    fun should_notTick_when_mountOrCommitHandshakeFails() = runBlocking {
        suspend fun runWith(
            mountSucceeds: Boolean,
            commitSucceeds: Boolean,
        ): Pair<Boolean, List<String>> {
            val events = mutableListOf<String>()
            val result = coordinateDetailEnter(
                barHandoff = false,
                nowMillis = { 0L },
                awaitInitialCommit = { false },
                awaitVisualReadyOrTimeout = { true },
                delayMillis = { events += "delay" },
                awaitBackIdleOrAbort = { true },
                revealPage = { events += "visible" },
                hidePage = { events += "hidden" },
                awaitPageMount = {
                    events += "mount"
                    mountSucceeds
                },
                awaitVisibleCommit = {
                    events += "commit"
                    commitSucceeds
                },
                settlePageWithoutTick = { events += "settledWithoutTick" },
                noteSlideStarted = { events += "tick" },
            )
            return result to events
        }

        val mountFailure = runWith(mountSucceeds = false, commitSucceeds = true)
        val commitFailure = runWith(mountSucceeds = true, commitSucceeds = false)

        assertFalse(mountFailure.first)
        assertEquals(listOf("visible", "mount", "settledWithoutTick"), mountFailure.second)
        assertFalse(commitFailure.first)
        assertEquals(
            listOf("visible", "mount", "commit", "settledWithoutTick"),
            commitFailure.second,
        )
    }
}
