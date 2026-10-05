package com.gpo.yoin.ui.home.edit

import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.gpo.yoin.testutil.virtualUptimeMs
import com.gpo.yoin.ui.experience.HomeEditProgress
import com.gpo.yoin.ui.home.HomeLayout
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.HomeSection.Activities
import com.gpo.yoin.ui.home.HomeSection.JumpBackIn
import com.gpo.yoin.ui.home.HomeSection.RecentlyAdded
import com.gpo.yoin.ui.home.HomeSection.Rediscover
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeEditMotionTest {

    private val specs = HomeEditSpecs.create(MotionScheme.expressive(), reduced = false)
    private val feed = listOf(Activities, JumpBackIn, RecentlyAdded, Rediscover)

    /** A motion over snapshot-backed controller state, on the test's virtual clock. */
    private inner class Rig(scope: CoroutineScope, uptimeMs: () -> Long, progress: () -> Float? = { null }) {
        var editing by mutableStateOf(true)
        var reduced by mutableStateOf(false)
        var p by mutableFloatStateOf(1f)
        val motion = HomeEditMotion(
            scope = scope,
            specs = specs,
            style = HomeWiggleStyle(),
            reduced = { reduced },
            progress = { progress() ?: p },
            editing = { editing },
            feedback = HomeEditFeedback.None,
            uptimeMs = uptimeMs,
        )
    }

    private fun TestScope.rig(scope: CoroutineScope, progress: () -> Float? = { null }) =
        Rig(scope, virtualUptimeMs(), progress)

    /** Steps [ms] of 16ms frames, delivering snapshot changes first so snapshotFlows see them. */
    private fun TestScope.frames(ms: Long, each: () -> Unit = {}) {
        var elapsed = 0L
        while (elapsed < ms) {
            Snapshot.sendApplyNotifications()
            advanceTimeBy(16)
            runCurrent()
            each()
            elapsed += 16
        }
    }

    // ── Envelope ──────────────────────────────────────────────────────────

    @Test
    fun should_dropEnvelope_when_6sPassWithoutTouch() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.motion.touch()
        scope.launch { rig.motion.runClock() }

        advanceTimeBy(5_900)
        runCurrent()
        assertEquals(1f, rig.motion.envelope.value, .001f)

        // The fall starts at 6s and settles in about 470ms.
        advanceTimeBy(800)
        runCurrent()
        assertEquals(0f, rig.motion.envelope.value, 0f)
    }

    @Test
    fun should_holdEnvelope_when_pointerDown() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.motion.touch()
        rig.motion.pointerDown = true
        scope.launch { rig.motion.runClock() }

        advanceTimeBy(7_000)
        runCurrent()
        assertEquals(1f, rig.motion.envelope.value, .001f)

        // Lifting restarts the window rather than ending it at once.
        rig.motion.pointerDown = false
        advanceTimeBy(5_900)
        runCurrent()
        assertEquals(1f, rig.motion.envelope.value, .001f)
        advanceTimeBy(800)
        runCurrent()
        assertEquals(0f, rig.motion.envelope.value, 0f)
    }

    @Test
    fun should_holdEnvelope_when_carryActive() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.motion.touch()
        rig.motion.carryActive = true
        scope.launch { rig.motion.runClock() }

        advanceTimeBy(7_000)
        runCurrent()
        assertEquals(HomeEditTokens.DragEnvelope, rig.motion.envelope.value, .001f)
    }

    @Test
    fun should_restoreEnvelope_when_touched() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.motion.touch()
        scope.launch { rig.motion.runClock() }
        advanceTimeBy(6_800)
        runCurrent()
        assertEquals(0f, rig.motion.envelope.value, 0f)

        rig.motion.touch()
        var peak = 0f
        frames(400) { peak = max(peak, rig.motion.envelope.value) }
        // fastSpatial's rise overshoots to about 1.095; that is part of the feel.
        assertEquals(1.095f, peak, .015f)
        frames(200)
        assertEquals(1f, rig.motion.envelope.value, .001f)
    }

    @Test
    fun should_targetSixTenths_when_carryActive() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.motion.touch()
        frames(600)
        assertEquals(1f, rig.motion.envelope.value, 0f)

        rig.motion.carryActive = true
        frames(600)
        assertEquals(.6f, rig.motion.envelope.value, .001f)

        rig.motion.carryActive = false
        frames(600)
        assertEquals(1f, rig.motion.envelope.value, .001f)
    }

    @Test
    fun should_neverRunClock_when_reduced() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.reduced = true
        rig.motion.touch()
        frames(100)
        assertEquals(0f, rig.motion.envelope.value, 0f)
        assertFalse(rig.motion.clockShouldRun)

        // Even with the sway already up, reduced motion parks the clock.
        rig.reduced = false
        rig.motion.touch()
        frames(100)
        assertTrue(rig.motion.clockShouldRun)
        rig.reduced = true
        assertFalse(rig.motion.clockShouldRun)
    }

    @Test
    fun should_parkClock_when_hiddenOrEnvelopeAtRest() = runEditClockTest { scope ->
        val rig = rig(scope)
        assertFalse(rig.motion.clockShouldRun)
        rig.motion.touch()
        assertTrue(rig.motion.clockShouldRun)
        rig.motion.visible = false
        assertFalse(rig.motion.clockShouldRun)
    }

    // ── Entry ─────────────────────────────────────────────────────────────

    @Test
    fun should_kickEachBlockOnce_when_rippleCrosses085() = runEditClockTest { scope ->
        val progress = HomeEditProgress()
        val rig = rig(scope) { progress.value }
        rig.motion.displayed = feed
        rig.motion.onEnter(Activities, lifted = false)
        scope.launch { rig.motion.runEntryEffects() }
        progress.animateTo(scope, 1f, homeEditStageSpec(reduced = false))

        val peaks = feed.associateWith { 0f }.toMutableMap()
        frames(900) { feed.forEach { peaks[it] = max(peaks.getValue(it), abs(rig.motion.blockKick(it))) } }
        // One kick of amplitude 1 peaks at about 1; a second would roughly double it.
        feed.forEach { assertEquals("$it", HomeEditTokens.KickEntry, peaks.getValue(it), .1f) }
    }

    @Test
    fun should_notKickLiftedBlock_when_entering() = runEditClockTest { scope ->
        val progress = HomeEditProgress()
        val rig = rig(scope) { progress.value }
        rig.motion.displayed = feed
        rig.motion.onEnter(JumpBackIn, lifted = true)
        assertEquals(JumpBackIn, rig.motion.heldSection)
        assertFalse(rig.motion.placeholdersAboveOpen)
        scope.launch { rig.motion.runEntryEffects() }
        progress.animateTo(scope, 1f, homeEditStageSpec(reduced = false))

        var liftedPeak = 0f
        var otherPeak = 0f
        frames(600) {
            liftedPeak = max(liftedPeak, abs(rig.motion.blockKick(JumpBackIn)))
            otherPeak = max(otherPeak, abs(rig.motion.blockKick(RecentlyAdded)))
        }
        assertEquals(0f, liftedPeak, 0f)
        assertTrue(otherPeak > .5f)
    }

    @Test
    fun should_kickDeferredBlockOnce_when_itJoinsAfterEntrySettled() = runEditClockTest { scope ->
        val rig = rig(scope)
        // Lifted RA; the empty Activities placeholder above it is held back.
        rig.motion.displayed = listOf(RecentlyAdded)
        rig.motion.onEnter(RecentlyAdded, lifted = true)
        scope.launch { rig.motion.runEntryEffects() }
        frames(400)
        assertEquals(0f, rig.motion.blockKick(RecentlyAdded), 0f)

        // First finger-up: the placeholder joins with P long settled at 1.
        rig.motion.displayed = listOf(Activities, RecentlyAdded)
        var peak = 0f
        var liftedPeak = 0f
        frames(600) {
            peak = max(peak, abs(rig.motion.blockKick(Activities)))
            liftedPeak = max(liftedPeak, abs(rig.motion.blockKick(RecentlyAdded)))
        }
        assertEquals(HomeEditTokens.KickEntry, peak, .1f)
        assertEquals(0f, liftedPeak, 0f)

        // Once only: re-rendering the same blocks kicks nothing more.
        rig.motion.displayed = listOf(RecentlyAdded, Activities)
        var again = 0f
        frames(400) { again = max(again, abs(rig.motion.blockKick(Activities))) }
        assertTrue(again < .05f)
    }

    @Test
    fun should_clearLatch_when_progressReaches06() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.p = 0f
        rig.motion.displayed = listOf(Activities, JumpBackIn)
        rig.motion.onEnter(Activities, lifted = true)
        rig.motion.latchPlate(Activities, Rect(0f, 0f, 96f, 96f), charge = .8f)
        scope.launch { rig.motion.runEntryEffects() }

        rig.p = .5f
        frames(16)
        assertEquals(.8f, rig.motion.plateFrom?.latch)

        rig.p = .6f
        frames(16)
        assertEquals(0f, rig.motion.plateFrom?.latch)

        // Fully grown: the continuous plate takes over.
        rig.p = 1f
        frames(16)
        assertNull(rig.motion.plateFrom)
    }

    @Test
    fun should_dropPlate_when_exitedBackToRest() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.p = .3f
        rig.motion.displayed = listOf(Activities)
        rig.motion.onEnter(Activities, lifted = true)
        rig.motion.latchPlate(Activities, Rect(0f, 0f, 96f, 96f), charge = .8f)
        scope.launch { rig.motion.runEntryEffects() }
        frames(16)

        rig.editing = false
        rig.motion.onExit(HomeEditExitReason.Back)
        // The plate retreats with P instead of holding the latch.
        assertEquals(0f, rig.motion.plateFrom?.latch)
        rig.p = 0f
        frames(16)
        assertNull(rig.motion.plateFrom)
    }

    // ── Hide and show ─────────────────────────────────────────────────────

    @Test
    fun should_removeHidingBlock_onlyIfSerialUnchanged() = runEditClockTest { scope ->
        val rig = rig(scope)
        val base = HomeLayout.Default
        val noActivities = base.withEnabled(Activities, false)
        rig.motion.onLayoutChange(change(1, HomeEditChangeKind.Hide, base, noActivities)) { true }
        assertEquals(setOf(Activities), rig.motion.hiding)

        // A second hide while the first still fades: both leave with the later one.
        frames(112)
        val noJbi = noActivities.withEnabled(JumpBackIn, false)
        rig.motion.onLayoutChange(change(2, HomeEditChangeKind.Hide, noActivities, noJbi)) { true }
        assertEquals(setOf(Activities, JumpBackIn), rig.motion.hiding)

        frames(96)
        assertEquals(0f, rig.motion.hideAlpha(Activities), 0f)
        assertTrue(Activities in rig.motion.hiding)

        frames(300)
        assertEquals(emptySet<HomeSection>(), rig.motion.hiding)
        assertEquals(HomeEditTokens.HideScale, rig.motion.hideScale(JumpBackIn), .001f)
    }

    @Test
    fun should_removeHidingBlock_when_itsFadeEnds() = runEditClockTest { scope ->
        val rig = rig(scope)
        val base = HomeLayout.Default
        val hidden = base.withEnabled(RecentlyAdded, false)
        rig.motion.onLayoutChange(change(1, HomeEditChangeKind.Hide, base, hidden)) { true }
        frames(300)
        assertEquals(emptySet<HomeSection>(), rig.motion.hiding)
        assertEquals(0f, rig.motion.hideAlpha(RecentlyAdded), 0f)
        // Its tray row faded in.
        assertEquals(1f, rig.motion.rowAlpha(RecentlyAdded), 0f)
    }

    @Test
    fun should_kickShown_onlyForShowKindInViewport() = runEditClockTest { scope ->
        val rig = rig(scope)
        val base = HomeLayout.Default
        val hidden = base.withEnabled(RecentlyAdded, false).withEnabled(Rediscover, false)
        val raShown = hidden.withEnabled(RecentlyAdded, true)
        rig.motion.onLayoutChange(change(1, HomeEditChangeKind.Show, hidden, raShown, RecentlyAdded)) { true }
        assertEquals(0f, rig.motion.hideAlpha(RecentlyAdded), 0f)

        var peak = 0f
        frames(400) { peak = max(peak, abs(rig.motion.blockKick(RecentlyAdded))) }
        assertEquals(HomeEditTokens.KickNeighbour, peak, .04f)
        assertEquals(1f, rig.motion.hideAlpha(RecentlyAdded), 0f)
        assertEquals(1f, rig.motion.hideScale(RecentlyAdded), 0f)

        // Shown below the fold: it fades in without a kick.
        rig.motion.onLayoutChange(change(2, HomeEditChangeKind.Show, raShown, base, Rediscover)) { false }
        var offscreenPeak = 0f
        frames(400) { offscreenPeak = max(offscreenPeak, abs(rig.motion.blockKick(Rediscover))) }
        assertEquals(0f, offscreenPeak, 0f)
        assertEquals(1f, rig.motion.hideAlpha(Rediscover), 0f)
    }

    @Test
    fun should_notKick_when_resetOrUndoShows() = runEditClockTest { scope ->
        val rig = rig(scope)
        val base = HomeLayout.Default
        val hidden = base.withEnabled(RecentlyAdded, false)
        rig.motion.onLayoutChange(change(1, HomeEditChangeKind.Reset, hidden, base)) { true }
        assertEquals(0f, rig.motion.hideAlpha(RecentlyAdded), 0f)
        assertEquals(HomeEditTokens.HideScale, rig.motion.hideScale(RecentlyAdded), 0f)

        var peak = 0f
        frames(400) { peak = max(peak, abs(rig.motion.blockKick(RecentlyAdded))) }
        assertEquals(0f, peak, 0f)
        assertEquals(1f, rig.motion.hideAlpha(RecentlyAdded), 0f)

        rig.motion.onLayoutChange(change(2, HomeEditChangeKind.Undo, hidden, base)) { true }
        frames(400) { peak = max(peak, abs(rig.motion.blockKick(RecentlyAdded))) }
        assertEquals(0f, peak, 0f)
    }

    @Test
    fun should_turnBackWithoutSnapOrKick_when_reEnabledMidFade() = runEditClockTest { scope ->
        val rig = rig(scope)
        val base = HomeLayout.Default
        val hidden = base.withEnabled(Activities, false)
        rig.motion.onLayoutChange(change(1, HomeEditChangeKind.Hide, base, hidden)) { true }
        frames(48)
        val alpha = rig.motion.hideAlpha(Activities)
        assertTrue(alpha > .05f && alpha < .95f)

        rig.motion.onLayoutChange(change(2, HomeEditChangeKind.Show, hidden, base, Activities)) { true }
        assertEquals(emptySet<HomeSection>(), rig.motion.hiding)
        assertEquals(alpha, rig.motion.hideAlpha(Activities), 0f)

        var peak = 0f
        var low = alpha
        frames(400) {
            peak = max(peak, abs(rig.motion.blockKick(Activities)))
            low = minOf(low, rig.motion.hideAlpha(Activities))
        }
        assertEquals(0f, peak, 0f)
        // Carried on down a little by its velocity at most, never back to 0.
        assertTrue(low > alpha / 2f)
        assertEquals(1f, rig.motion.hideAlpha(Activities), 0f)
        assertEquals(1f, rig.motion.hideScale(Activities), 0f)
    }

    @Test
    fun should_markShowingOnlyWhileItsFadeInRuns_when_shown() = runEditClockTest { scope ->
        val rig = rig(scope)
        val base = HomeLayout.Default
        val hidden = base.withEnabled(RecentlyAdded, false)
        // Hidden before entry, so no fade-out of its own is still running.
        rig.motion.onLayoutChange(change(1, HomeEditChangeKind.Show, hidden, base, RecentlyAdded)) { false }
        assertEquals(setOf(RecentlyAdded), rig.motion.showing)

        frames(400)
        assertEquals(emptySet<HomeSection>(), rig.motion.showing)

        // Hidden again mid fade-in: it leaves showing at once, for hiding.
        rig.motion.onLayoutChange(change(2, HomeEditChangeKind.Hide, base, hidden)) { false }
        frames(400)
        rig.motion.onLayoutChange(change(3, HomeEditChangeKind.Undo, hidden, base)) { false }
        frames(32)
        assertEquals(setOf(RecentlyAdded), rig.motion.showing)
        rig.motion.onLayoutChange(change(4, HomeEditChangeKind.Undo, base, hidden)) { false }
        assertEquals(emptySet<HomeSection>(), rig.motion.showing)
        assertEquals(setOf(RecentlyAdded), rig.motion.hiding)

        // A snap exit leaves nothing marked.
        frames(400)
        rig.motion.onLayoutChange(change(5, HomeEditChangeKind.Reset, hidden, base)) { false }
        assertEquals(setOf(RecentlyAdded), rig.motion.showing)
        rig.motion.onSnapExit()
        runCurrent()
        assertEquals(emptySet<HomeSection>(), rig.motion.showing)
        assertEquals(1f, rig.motion.hideAlpha(RecentlyAdded), 0f)
    }

    // ── Tray and footer ───────────────────────────────────────────────────

    @Test
    fun should_sequenceTrayThenFooter_when_exiting() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.motion.onEnter(null, lifted = false)
        // The footer leaves at once; the tray fades in.
        assertEquals(0f, rig.motion.footerAlpha.value, 0f)
        assertTrue(rig.motion.trayMounted)
        frames(400)
        assertEquals(1f, rig.motion.trayAlpha.value, 0f)

        rig.editing = false
        rig.motion.onExit(HomeEditExitReason.Done)
        var footerWhileTray = 0f
        var trayGoneAt = -1L
        var elapsed = 0L
        frames(800) {
            elapsed += 16
            if (rig.motion.trayAlpha.value > 0f) {
                footerWhileTray = max(footerWhileTray, rig.motion.footerAlpha.value)
            } else if (trayGoneAt < 0) {
                trayGoneAt = elapsed
            }
        }
        assertEquals(0f, footerWhileTray, 0f)
        assertTrue(trayGoneAt in 1..300)
        assertEquals(1f, rig.motion.footerAlpha.value, 0f)
        assertFalse(rig.motion.trayMounted)
        assertTrue(rig.motion.footerMounted)
    }

    @Test
    fun should_snapTrayAndFooter_when_snapExit() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.motion.displayed = feed
        rig.motion.onEnter(Activities, lifted = true)
        frames(400)
        val base = HomeLayout.Default
        val hidden = base.withEnabled(Rediscover, false)
        rig.motion.onLayoutChange(change(1, HomeEditChangeKind.Hide, base, hidden)) { true }

        rig.editing = false
        rig.motion.onSnapExit()
        runCurrent()
        assertEquals(0f, rig.motion.trayAlpha.value, 0f)
        assertEquals(1f, rig.motion.footerAlpha.value, 0f)
        assertEquals(0f, rig.motion.envelope.value, 0f)
        assertFalse(rig.motion.trayMounted)
        assertEquals(emptySet<HomeSection>(), rig.motion.hiding)
        assertNull(rig.motion.heldSection)
        assertTrue(rig.motion.placeholdersAboveOpen)
    }

    // ── Taps ──────────────────────────────────────────────────────────────

    @Test
    fun should_kickOnlyTheTappedCard_when_cardLevel() = runEditClockTest { scope ->
        val rig = rig(scope)
        rig.motion.registerCard(JumpBackIn, FixedSpot(0, Rect(0f, 0f, 100f, 100f)))
        rig.motion.registerCard(JumpBackIn, FixedSpot(1, Rect(110f, 0f, 210f, 100f)))
        val card = rig.motion.cardAt(JumpBackIn, Offset(150f, 50f))
        assertEquals(1, card)

        rig.motion.impulseCard(JumpBackIn, card!!, HomeEditTokens.KickTap)
        rig.motion.pulseHandle(JumpBackIn)
        var cardPeak = 0f
        var blockPeak = 0f
        var pulsePeak = 0f
        frames(300) {
            cardPeak = max(cardPeak, abs(rig.motion.cardKick(JumpBackIn, 1)))
            blockPeak = max(blockPeak, abs(rig.motion.blockKick(JumpBackIn)))
            pulsePeak = max(pulsePeak, rig.motion.handlePulse(JumpBackIn))
        }
        assertEquals(HomeEditTokens.KickTap, cardPeak, .03f)
        assertEquals(0f, blockPeak, 0f)
        assertEquals(0f, rig.motion.cardKick(JumpBackIn, 0), 0f)
        assertEquals(1.1993f, pulsePeak, .01f)
        assertNotNull(rig.motion.cardAt(JumpBackIn, Offset(10f, 10f)))
    }

    @Test
    fun should_keepTheSurvivingCard_when_anotherCardWithItsIndexUnregisters() = runEditClockTest { scope ->
        // A row resize composes two stops whose cards share indices; the
        // stop that leaves must not take the staying card's tap target along.
        val rig = rig(scope)
        val staying = FixedSpot(0, Rect(0f, 0f, 100f, 100f))
        val leaving = FixedSpot(0, Rect(0f, 0f, 100f, 100f))
        rig.motion.registerCard(JumpBackIn, staying)
        rig.motion.registerCard(JumpBackIn, leaving)

        rig.motion.unregisterCard(JumpBackIn, leaving)

        assertEquals(0, rig.motion.cardAt(JumpBackIn, Offset(50f, 50f)))
    }

    @Test
    fun should_hitTheCardWhereItIsNow_when_itMovedSinceRegistering() = runEditClockTest { scope ->
        val rig = rig(scope)
        val card = FixedSpot(2, Rect(0f, 0f, 100f, 100f))
        rig.motion.registerCard(JumpBackIn, card)

        // Its layer moved it after its last placement (no new onPlaced).
        card.rect = Rect(0f, 200f, 100f, 300f)

        assertNull(rig.motion.cardAt(JumpBackIn, Offset(50f, 50f)))
        assertEquals(2, rig.motion.cardAt(JumpBackIn, Offset(50f, 250f)))
    }

    /** A card spot whose rect the test moves by hand. */
    private class FixedSpot(override val cardIndex: Int, var rect: Rect?) : HomeEditCardSpot {
        override fun rectInBlock(): Rect? = rect
    }

    private fun change(
        serial: Int,
        kind: HomeEditChangeKind,
        previous: HomeLayout,
        next: HomeLayout,
        subject: HomeSection? = null,
    ) = HomeEditChange(serial, kind, previous, next, subject)
}
