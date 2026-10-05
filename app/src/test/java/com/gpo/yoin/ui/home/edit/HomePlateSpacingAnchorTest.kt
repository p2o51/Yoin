package com.gpo.yoin.ui.home.edit

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The V1 plate grows the feed's spacing 18 → 32dp with P; a block lifted by the entering long-press
 * must stay under the finger while it does (HomePlateSpacingAnchor). The list is modelled at 1px/dp:
 * the held block sits [Gaps] gaps below the first visible item, whose place the list keeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomePlateSpacingAnchorTest {

    private var p = 0f
    private var scrolled = 0f
    private val anchor = HomePlateSpacingAnchor()
    private val look = HomePlateLook(HomePlateVariant.V1, progress = { anchor.progress(p) })

    /** The spacing the list lays out with, in whole px (Density 1). */
    private fun laidOutSpacing(): Int = look.spacing(Rest).value.roundToInt()

    /** The held block's top: the first visible item's content plus the gaps, less the anchor's scroll. */
    private fun heldTop(): Float = AboveHeight + Gaps * laidOutSpacing() - scrolled

    private fun TestScope.startHold(clock: BroadcastFrameClock, gaps: Int = Gaps) = launch(clock) {
        anchor.hold(
            live = { p },
            spacingPx = { progress -> look.spacingAt(Rest, progress).value.roundToInt() },
            gapsAbove = { gaps },
            scrollBy = { px -> scrolled += px },
        )
    }

    @Test
    fun should_keepHeldBlockUnderFinger_when_spacingGrowsDuringHold() = runTest {
        val clock = BroadcastFrameClock()
        val start = heldTop()
        val hold = startHold(clock)
        runCurrent()
        assertTrue(anchor.holding)

        var frame = 0L
        for (next in listOf(.05f, .2f, .45f, .7f, .9f, .99f, 1f)) {
            p = next
            // Before the frame the spacing still reads the last sample: nothing has moved yet.
            assertEquals(start, heldTop(), 0f)
            clock.sendFrame(++frame * FrameNanos)
            runCurrent()
            assertEquals(start, heldTop(), 0f)
        }
        // The feed spread around the held block: the blocks above went up by the whole growth.
        assertEquals(32f, look.spacing(Rest).value, .001f)
        assertEquals(Gaps * (32f - 18f), scrolled, 0f)

        hold.cancelAndJoin()
        assertFalse(anchor.holding)
        assertEquals(start, heldTop(), 0f)
    }

    @Test
    fun should_followLiveProgress_when_notHolding() = runTest {
        p = .5f
        assertFalse(anchor.holding)
        assertEquals(.5f, anchor.progress(p), 0f)
        assertEquals(25f, look.spacing(Rest).value, .001f)

        val clock = BroadcastFrameClock()
        val hold = startHold(clock)
        runCurrent()
        // Mid-hold P moves on before the frame: the spacing waits for the frame's sample.
        p = .8f
        assertEquals(.5f, anchor.progress(p), 0f)
        clock.sendFrame(FrameNanos)
        runCurrent()
        assertEquals(.8f, anchor.progress(p), 0f)

        // The hand-back: the finger lifted with P where the sample was, so nothing jumps.
        hold.cancelAndJoin()
        assertEquals(.8f, anchor.progress(p), 0f)
    }

    @Test
    fun should_notScroll_when_heldBlockIsTheFirstVisibleItem() = runTest {
        val clock = BroadcastFrameClock()
        val hold = startHold(clock, gaps = 0)
        runCurrent()
        p = 1f
        clock.sendFrame(FrameNanos)
        runCurrent()

        assertEquals(0f, scrolled, 0f)
        hold.cancelAndJoin()
    }

    @Test
    fun should_scrollGrowthTimesGaps_when_computingAnchorScroll() {
        assertEquals(28f, anchorScrollPx(from = 18, to = 32, gaps = 2), 0f)
        assertEquals(-14f, anchorScrollPx(from = 32, to = 18, gaps = 1), 0f)
        assertEquals(0f, anchorScrollPx(from = 18, to = 32, gaps = 0), 0f)
        assertEquals(0f, anchorScrollPx(from = 18, to = 18, gaps = 3), 0f)
    }

    private companion object {
        val Rest = 18.dp
        const val Gaps = 2
        const val AboveHeight = 420f
        const val FrameNanos = 8_333_333L
    }
}
