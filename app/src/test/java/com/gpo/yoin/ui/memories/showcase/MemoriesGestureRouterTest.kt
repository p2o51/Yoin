package com.gpo.yoin.ui.memories.showcase

import androidx.compose.animation.core.spring
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TestMonotonicFrameClock
import com.gpo.yoin.ui.experience.DismissRule
import com.gpo.yoin.ui.experience.RevealState
import com.gpo.yoin.ui.memories.award.MemoriesAwardLifecycle
import com.gpo.yoin.ui.memories.award.MemoriesAwardRun
import com.gpo.yoin.ui.memories.award.MemoriesAwardTarget
import com.gpo.yoin.ui.navigation.back.MemoriesDismissRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Memories' gesture router (twostate4 `pointermove` / `up`), in px at density 1 so the numbers read as dp:
 * page 915 tall, bar 56 / 450, body 112 / 600, flick back 350, morph 320 (rubber band 0.3×, floor −90).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class MemoriesGestureRouterTest {

    private val height = 915f
    private val rules = MemoriesDismissRules(
        body = DismissRule(commitPx = 112f, flingPxPerSec = 600f, flickBackPxPerSec = 350f),
        bar = DismissRule(commitPx = 56f, flingPxPerSec = 450f, flickBackPxPerSec = 350f),
    )

    private class Fixture(val reveal: RevealState, val diary: MemoriesDiaryState, val router: MemoriesGestureRouter) {
        var dismissed = 0
        var committed = 0
    }

    private fun TestScope.fixture(): Fixture {
        val reveal = RevealState(
            initialFraction = 0f,
            rubberBandCoefficient = 0.3f,
            velocityThresholdFractionPerSec = 1.6f,
            positionThreshold = 0.5f,
            settleSpec = spring(),
        )
        val diary = MemoriesDiaryState(
            initialFraction = 0f,
            morphDistancePx = 320f,
            pullBandPx = 24f,
            flickPxPerSec = 350f,
            rubberBand = DiaryRubberBand,
            rubberBandFloorPx = 90f,
            morphSpec = spring(),
        )
        // releases settle on the page's outer scope, here one on the test frame clock
        val scope = CoroutineScope(coroutineContext + TestMonotonicFrameClock(this))
        val router = MemoriesGestureRouter(reveal, diary, rules, scope)
        router.heightPx = height
        router.cardPresent = true
        return Fixture(reveal, diary, router).also { f ->
            router.onDismissed = { f.dismissed++ }
            router.onCommitted = { f.committed++ }
        }
    }

    @Test
    fun should_route_bar_push_up_to_dismiss_with_bar_rule() = runTest {
        // the bar means Home either way; the card body's push up uses the body rule
        val fromBar = MemoriesVerticalRoute.Dismiss(fromBar = true)
        val fromBody = MemoriesVerticalRoute.Dismiss(fromBar = false)
        assertEquals(fromBar, routeVerticalDrag(MemoriesDragZone.Bar, -8f, true))
        assertEquals(fromBar, routeVerticalDrag(MemoriesDragZone.Bar, 8f, true))
        assertEquals(fromBody, routeVerticalDrag(MemoriesDragZone.Card, -8f, true))
        assertEquals(rules.bar, rules.ruleFor(MemoriesVerticalRoute.Dismiss(fromBar = true)))

        // 60dp up from the bar (68 of finger with the slop): past its 56dp, so the release commits to Home.
        // The page follows from the lock only (no slop jump); the 8dp the slop ate still count.
        val bar = fixture()
        val barDrag = bar.router.begin(MemoriesDragZone.Bar, -8f)
        assertEquals(0f, bar.reveal.fraction)
        bar.router.drag(barDrag, -60f)
        assertEquals(60f / height, bar.reveal.fraction, 1e-5f)
        assertEquals(56f - 8f, bar.router.cornerThresholdPx)
        bar.router.release(barDrag, velocityY = 0f)
        advanceUntilIdle()
        assertEquals(1f, bar.reveal.fraction, 1e-3f)
        assertEquals(1, bar.committed)
        assertEquals(1, bar.dismissed)

        // the same 60dp from the card body is short of 112dp: it springs back
        val body = fixture()
        val bodyDrag = body.router.begin(MemoriesDragZone.Card, -8f)
        body.router.drag(bodyDrag, -60f)
        assertEquals(112f - 8f, body.router.cornerThresholdPx)
        body.router.release(bodyDrag, velocityY = 0f)
        advanceUntilIdle()
        assertEquals(0f, body.reveal.fraction, 1e-3f)
        assertEquals(0, body.dismissed)

        // a 450dp/s flick from the bar commits short of the distance
        val flick = fixture()
        val flicked = flick.router.begin(MemoriesDragZone.Bar, -8f)
        flick.router.drag(flicked, -20f)
        flick.router.release(flicked, velocityY = -460f)
        advanceUntilIdle()
        assertEquals(1, flick.dismissed)
    }

    @Test
    fun should_route_card_pull_down_to_rubber_band() = runTest {
        assertEquals(MemoriesVerticalRoute.CardRubberBand, routeVerticalDrag(MemoriesDragZone.Card, 8f, true))
        // no card face (loading / empty / error): the whole page is q's
        val noCard = routeVerticalDrag(MemoriesDragZone.Card, 8f, cardPresent = false)
        assertEquals(MemoriesVerticalRoute.Dismiss(fromBar = false), noCard)

        val f = fixture()
        val route = f.router.begin(MemoriesDragZone.Card, 8f)
        assertEquals(MemoriesVerticalRoute.CardRubberBand, route)
        // 100dp down moves the card 30dp (0.3×); q never moves
        f.router.drag(route, 100f)
        assertEquals(-30f, f.diary.fraction * 320f, 1e-3f)
        assertEquals(30f, cardRubberBandPx(f.diary.fraction, 320f), 1e-3f)
        assertEquals(0f, f.reveal.fraction)
        // the floor is −90dp
        f.router.drag(route, 10_000f)
        assertEquals(-90f, f.diary.fraction * 320f, 1e-3f)
        // turning back up stops at the card: a card pull never opens the diary
        f.router.drag(route, -20_000f)
        assertEquals(0f, f.diary.fraction, 1e-6f)
        f.router.drag(route, 100f)
        f.router.release(route, velocityY = 0f)
        advanceUntilIdle()
        assertEquals(0f, f.diary.fraction, 1e-3f)
    }

    @Test
    fun should_measure_commit_distance_from_finger_down_without_jumping() = runTest {
        // 105dp of page travel after an 8dp slop is 113dp of finger: commits on the body's 112dp
        val f = fixture()
        val drag = f.router.begin(MemoriesDragZone.Card, -8f)
        f.router.drag(drag, -105f)
        assertEquals(105f / height, f.reveal.fraction, 1e-5f)
        f.router.release(drag, velocityY = 0f)
        advanceUntilIdle()
        assertEquals(1, f.dismissed)
        assertEquals(112f, rules.body.afterSlop(0f).commitPx)
        assertEquals(104f, rules.body.afterSlop(8f).commitPx)
    }

    @Test
    fun should_route_diary_drags_to_its_scroll_unless_pushing_up_from_its_end() {
        // the open diary's text scrolls itself (the router takes nothing) ...
        assertEquals(
            MemoriesVerticalRoute.DiaryScroll,
            routeVerticalDrag(MemoriesDragZone.Diary, -8f, true, diaryLevel = true, diaryAtEnd = false),
        )
        assertFalse(MemoriesVerticalRoute.DiaryScroll.consumes)
        // ... a pull down, even at the end, too (its top hands the overflow to p) ...
        assertEquals(
            MemoriesVerticalRoute.DiaryScroll,
            routeVerticalDrag(MemoriesDragZone.Diary, 8f, true, diaryLevel = true, diaryAtEnd = true),
        )
        // ... but a new push up that starts at its end is Home, on the body rule
        assertEquals(
            MemoriesVerticalRoute.Dismiss(fromBar = false),
            routeVerticalDrag(MemoriesDragZone.Diary, -8f, true, diaryLevel = true, diaryAtEnd = true),
        )
    }

    @Test
    fun should_route_bar_pull_down_in_diary_to_its_handle() = runTest {
        // in the diary the bar is the handle: down scrubs p 1:1, up is still Home on the bar rule
        assertEquals(
            MemoriesVerticalRoute.DiaryHandle,
            routeVerticalDrag(MemoriesDragZone.Bar, 8f, true, diaryLevel = true),
        )
        assertEquals(
            MemoriesVerticalRoute.Dismiss(fromBar = true),
            routeVerticalDrag(MemoriesDragZone.Bar, -8f, true, diaryLevel = true),
        )
        val f = fixture()
        f.diary.snapTo(1f)
        val route = f.router.begin(MemoriesDragZone.Bar, 8f)
        assertEquals(MemoriesVerticalRoute.DiaryHandle, route)
        // 1:1, no band: 200dp of finger is 200dp of morph
        f.router.drag(route, 200f)
        assertEquals(120f, f.diary.fraction * 320f, 1e-3f)
        f.router.release(route, velocityY = 0f)
        advanceUntilIdle()
        assertEquals(0f, f.diary.fraction, 1e-3f)
        assertEquals(0f, f.reveal.fraction)
    }

    @Test
    fun should_lock_axis_after_slop_with_ties_going_vertical() {
        assertNull(decideDragAxis(dx = 5f, dy = 5f, slopPx = 8f))
        assertEquals(MemoriesDragAxis.Horizontal, decideDragAxis(dx = 9f, dy = 2f, slopPx = 8f))
        assertEquals(MemoriesDragAxis.Vertical, decideDragAxis(dx = 2f, dy = -9f, slopPx = 8f))
        assertEquals(MemoriesDragAxis.Vertical, decideDragAxis(dx = 6f, dy = 6f, slopPx = 8f))
    }

    @Test
    fun should_pause_award_beats_when_a_drag_starts() = runTest {
        val f = fixture()
        val awards = MemoriesAwardLifecycle()
        f.router.awards = awards
        var pauses = 0
        val target = object : MemoriesAwardTarget {
            override val hasAward = true
            override fun showPending() = Unit
            override fun playFirst() = object : MemoriesAwardRun {
                override val climaxed = false
                override fun interrupt() = Unit
                override fun pauseBeats() {
                    pauses++
                }
                override fun resumeBeats() = Unit
            }
        }
        awards.register("m1", target)
        awards.start(com.gpo.yoin.ui.memories.award.MemoriesAwardInputs("m1", false, true, true, true))
        f.router.press()
        assertTrue(awards.fingerDown)
        f.router.beginHorizontal()
        assertEquals(1, pauses)
        f.router.lift()
        assertFalse(awards.fingerDown)
    }
}
