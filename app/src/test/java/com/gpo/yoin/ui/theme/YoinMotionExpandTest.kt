package com.gpo.yoin.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [YoinMotion.expandHorizontally] / [YoinMotion.shrinkHorizontally] pin the
 * content to the edge they are given. Review 2026-10-10: the Artist page's Wide
 * Most Played took Compose's End default, so the column slid in from the page
 * edge (its trailing gap and play counts first) instead of unfolding in place.
 */
@RunWith(RobolectricTestRunner::class)
class YoinMotionExpandTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_unfoldInPlace_when_expandingFromStart() {
        val frames = run(
            startVisible = false,
            enter = YoinMotion.expandHorizontally(
                role = YoinMotionRole.Expressive,
                expandFrom = Alignment.Start
            )
        )

        assertTrue("no mid-animation frame: $frames", frames.isNotEmpty())
        assertTrue("content should stay put, was $frames", frames.all { it.contentOffsetPx == 0f })
    }

    @Test
    fun should_slideInFromTheStartEdge_when_expandFromIsLeftAtItsDefault() {
        val frames = run(
            startVisible = false,
            enter = YoinMotion.expandHorizontally(role = YoinMotionRole.Expressive)
        )

        assertTrue("no mid-animation frame: $frames", frames.isNotEmpty())
        assertTrue("content should trail the clip, was $frames", frames.all { it.contentOffsetPx < 0f })
    }

    @Test
    fun should_foldInPlace_when_shrinkingTowardsStart() {
        val frames = run(
            startVisible = true,
            exit = YoinMotion.shrinkHorizontally(
                role = YoinMotionRole.Expressive,
                shrinkTowards = Alignment.Start
            )
        )

        assertTrue("no mid-animation frame: $frames", frames.isNotEmpty())
        assertTrue("content should stay put, was $frames", frames.all { it.contentOffsetPx == 0f })
    }

    private data class Frame(val widthPx: Int, val contentOffsetPx: Float)

    /** Flips visibility and returns the frames caught between closed and open. */
    private fun run(
        startVisible: Boolean,
        enter: EnterTransition = EnterTransition.None,
        exit: ExitTransition = ExitTransition.None
    ): List<Frame> {
        var visible by mutableStateOf(startVisible)
        var hostX = 0f
        var hostWidth = 0
        var contentX = 0f
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = visible,
                    enter = enter,
                    exit = exit,
                    modifier = Modifier.onGloballyPositioned {
                        hostX = it.positionInRoot().x
                        hostWidth = it.size.width
                    }
                ) {
                    Box(
                        Modifier
                            .size(width = CONTENT_WIDTH, height = 40.dp)
                            .onGloballyPositioned { contentX = it.positionInRoot().x }
                    )
                }
            }
        }
        rule.waitForIdle()
        val fullWidth = with(rule.density) { CONTENT_WIDTH.roundToPx() }
        rule.mainClock.autoAdvance = false

        rule.runOnIdle { visible = !startVisible }
        return List(FRAMES) {
            rule.mainClock.advanceTimeBy(FRAME_MS)
            rule.waitForIdle()
            Frame(hostWidth, contentX - hostX)
        }.filter { it.widthPx in 1 until fullWidth }
    }

    private companion object {
        val CONTENT_WIDTH = 200.dp
        const val FRAME_MS = 16L
        const val FRAMES = 30
    }
}
