package com.gpo.yoin.ui.memories.showcase

import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TestMonotonicFrameClock
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card ⇄ diary morph's choreography (twostate4 `renderPages` / `barShown` / `renderV`) and the diary's
 * pull (its nested scroll), in px at density 1 so the numbers read as dp: morph 320, band 24.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class MemoriesMorphTest {

    @Test
    fun should_hold_title_until_p_0_2() {
        // a slow pull never sends the title (and the emblem with it) across the still-visible cover
        assertEquals(0f, morphTitleTravel(0f, frozen = false))
        assertEquals(0f, morphTitleTravel(0.1f, frozen = false))
        assertEquals(0f, morphTitleTravel(0.2f, frozen = false))
        assertEquals(0.5f, morphTitleTravel(0.6f, frozen = false), 1e-6f)
        assertEquals(1f, morphTitleTravel(1f, frozen = false))
        // frozen: the title never travels, it waits on the card
        assertEquals(0f, morphTitleTravel(0.9f, frozen = true))

        // and its twin only takes over between .42 and .78 of that travel
        val card = TextAnchor(cx = 206f, y = 470f, fontPx = 26f)
        val diary = TextAnchor(cx = 150f, y = 110f, fontPx = 22f)
        val (atRest, twinAtRest) = morphTextPair(card, diary, t = 0f)
        assertEquals(0f, atRest.dx)
        assertEquals(1f, atRest.alpha)
        assertEquals(0f, twinAtRest.alpha)
        // the twin waits ON the card copy's spot, scaled to its size
        assertEquals(card.cx - diary.cx, twinAtRest.dx, 1e-4f)
        assertEquals(card.y - diary.y, twinAtRest.dy, 1e-4f)
        assertEquals(26f / 22f, twinAtRest.scale, 1e-5f)
        val (landed, twinLanded) = morphTextPair(card, diary, t = 1f)
        assertEquals(diary.cx - card.cx, landed.dx, 1e-4f)
        assertEquals(0f, landed.alpha)
        assertEquals(0f, twinLanded.dx)
        assertEquals(1f, twinLanded.scale)
        assertEquals(1f, twinLanded.alpha)
    }

    @Test
    fun should_keep_bar_cover_hidden_below_fp_0_62() {
        // whatever the chase says, the 40 never shows before fp .62 (it never flies at 2.4×) ...
        assertEquals(0f, barCoverShown(chase = 1f, fp = 0.3f))
        assertEquals(0f, barCoverShown(chase = 1f, fp = 0.62f))
        // ... and the page's own cover is gone exactly at fp 1, however late the chase is
        assertEquals(1f, barCoverShown(chase = 0f, fp = 1f))
        // inside the window the chase decides
        val mid = barCoverShown(chase = 0.4f, fp = 0.8f)
        assertEquals(0.4f, mid, 1e-6f)
        assertTrue(barCoverShown(chase = 0f, fp = 0.9f) > 0f)
        assertEquals(1f, barCoverTarget(0.95f))

        // the cover's size leads its position: half way, it is already 3/4 of the way to 40dp
        val pose = morphCoverPose(
            fp = 0.5f,
            coverCenter = Offset(206f, 283f),
            barCenter = Offset(84f, 64f),
            coverPx = 256f,
            barCoverPx = 40f,
            parallaxPx = 0f,
            cardCornerPx = 8f,
            barCornerPx = 4f,
        )
        assertEquals((84f - 206f) * 0.5f, pose.dx, 1e-4f)
        assertEquals(1f + (40f / 256f - 1f) * 0.75f, pose.scale, 1e-5f)
        assertEquals(6f, pose.cornerPx, 1e-5f)
    }

    @Test
    fun should_delay_cover_flight_when_frozen() {
        // collapsing from deep in the diary: the cover waits in the bar until the sinking text is gone (p .55)
        assertEquals(1f, morphCoverProgress(p = 0.8f, frozen = true))
        assertEquals(1f, morphCoverProgress(p = 0.55f, frozen = true))
        assertEquals(0.5f, morphCoverProgress(p = 0.275f, frozen = true), 1e-6f)
        assertEquals(0.8f, morphCoverProgress(p = 0.8f, frozen = false), 1e-6f)
        // the text sinks as one block (no stagger) and is gone by .55; scroll-0 blocks rise staggered
        assertEquals(0f, diaryBlockOffset(0.5f, k = 3, lagPx = 400f, staggerPx = 28f, frozen = true))
        assertEquals(200f, diaryFrozenSink(0.5f, lagPx = 400f, frozen = true), 1e-4f)
        assertEquals(0f, diaryBlockAlpha(0.55f, k = 0, frozen = true))
        assertEquals((1f - 0.5f) * (400f + 3 * 28f), diaryBlockOffset(0.5f, 3, 400f, 28f, frozen = false), 1e-4f)
        // k is capped at 6
        assertEquals(diaryBlockOffset(0.3f, 6, 400f, 28f, false), diaryBlockOffset(0.3f, 9, 400f, 28f, false))
    }

    @Test
    fun should_fade_through_without_overlap_when_reduced_motion() {
        var p = 0f
        while (p <= 1f) {
            // one layer of text at a time: the card is gone by .5, the diary starts there
            assertEquals(0f, minOf(reducedCardAlpha(p), reducedDiaryAlpha(p)), 1e-6f)
            p += 0.01f
        }
        assertEquals(1f, reducedCardAlpha(0f))
        assertEquals(1f, reducedDiaryAlpha(1f))
    }

    @Test
    fun should_hand_96_to_native_48_on_the_title_flight() {
        val seal = Offset(300f, 400f)
        val dem = Offset(360f, 120f)
        val start = morphEmblemPose(te = 0f, sealCenter = seal, demCenter = dem, sealPx = 96f, demPx = 48f)
        assertEquals(1f, start.sealAlpha)
        assertEquals(0f, start.demAlpha)
        assertEquals(1f, start.captionAlpha)
        // the 48 rides the same flight at the 96's live size (×2 at the start)
        assertEquals(2f, start.demScale, 1e-6f)
        assertEquals(seal.x - dem.x, start.demDx, 1e-4f)
        val landed = morphEmblemPose(te = 1f, sealCenter = seal, demCenter = dem, sealPx = 96f, demPx = 48f)
        assertEquals(0f, landed.sealAlpha)
        assertEquals(1f, landed.demAlpha)
        assertEquals(0f, landed.demDx)
        assertEquals(1f, landed.demScale)
        assertEquals(0.5f, landed.sealScale, 1e-6f)
        // the caption has left by .4 of the travel
        assertEquals(0f, morphEmblemPose(0.4f, seal, dem, 96f, 48f).captionAlpha, 1e-6f)
    }

    // ---------------------------------------------------------------- the pull past the top (nested scroll)

    private class PullFixture(val diary: MemoriesDiaryState, val deck: MemoriesDiaryDeck, val scroll: ScrollState)

    private fun TestScope.pull(initial: Float = 1f, scrolled: Int = 0): PullFixture {
        val diary = MemoriesDiaryState(
            initialFraction = initial,
            morphDistancePx = 320f,
            pullBandPx = 24f,
            flickPxPerSec = 350f,
            rubberBand = DiaryRubberBand,
            rubberBandFloorPx = 90f,
            morphSpec = spring(),
        )
        val scope = CoroutineScope(coroutineContext + TestMonotonicFrameClock(this))
        val deck = MemoriesDiaryDeck(diary, scope)
        val scroll = ScrollState(scrolled)
        deck.register(MemoryPageMorph(page = 0, deck = deck, scroll = scroll, density = Density(1f)))
        deck.currentPage = { 0 }
        return PullFixture(diary, deck, scroll)
    }

    @Test
    fun should_feed_pull_past_top_to_p_and_close_past_half() = runTest {
        val f = pull()
        f.deck.onFingerDown()
        // the text is at its top: a 200dp pull goes to p, its first 24dp at half speed
        val taken = f.deck.connection.onPostScroll(Offset.Zero, Offset(0f, 200f), NestedScrollSource.UserInput)
        assertEquals(200f, taken.y, 1e-3f)
        assertEquals(132f, f.diary.fraction * 320f, 1e-3f)
        // released short of half: the card
        val kept = f.deck.connection.onPreFling(Velocity.Zero)
        assertEquals(Velocity.Zero, kept)
        advanceUntilIdle()
        assertEquals(0f, f.diary.fraction, 1e-3f)
    }

    @Test
    fun should_stop_fling_at_top_without_moving_p() = runTest {
        val f = pull()
        f.deck.onFingerDown()
        // a fling reaching the top only stops: its leftover never reaches p
        val left = f.deck.connection.onPostScroll(Offset.Zero, Offset(0f, 80f), NestedScrollSource.SideEffect)
        assertEquals(Offset.Zero, left)
        assertEquals(1f, f.diary.fraction)
        // nor does a scroll fling when nothing was pulled
        assertEquals(Velocity.Zero, f.deck.connection.onPreFling(Velocity(0f, 2_000f)))
    }

    @Test
    fun should_give_finger_to_p_before_text_while_collapsing() = runTest {
        // a collapse caught mid-way (p .7): the finger moves p first, either way, before any text scrolls
        val f = pull(initial = 0.7f)
        f.deck.onFingerDown()
        val up = f.deck.connection.onPreScroll(Offset(0f, -50f), NestedScrollSource.UserInput)
        assertEquals(-50f, up.y, 1e-3f)
        assertTrue(f.diary.fraction > 0.7f)
        // reaching p 1 hands the rest to the text
        val rest = f.deck.connection.onPreScroll(Offset(0f, -400f), NestedScrollSource.UserInput)
        assertTrue(rest.y > -400f)
        assertEquals(1f, f.diary.fraction, 1e-6f)
        assertEquals(Offset.Zero, f.deck.connection.onPreScroll(Offset(0f, -10f), NestedScrollSource.UserInput))
        // released at 1 the scroll keeps its fling
        assertEquals(Velocity.Zero, f.deck.connection.onPreFling(Velocity(0f, -3_000f)))
    }

    @Test
    fun should_keep_a_scrolled_pull_in_the_diary_when_flung_inside_the_band() = runTest {
        // the pull started in scrolled text (the scroll ran to the top under the finger): a hard fling
        // released within the first half of the band is reading, not closing
        val f = pull(scrolled = 300)
        f.deck.onFingerDown()
        f.deck.connection.onPostScroll(Offset.Zero, Offset(0f, 20f), NestedScrollSource.UserInput)
        assertEquals(310f, f.diary.fraction * 320f, 1e-3f)
        val consumed = f.deck.connection.onPreFling(Velocity(0f, 3_000f))
        assertEquals(3_000f, consumed.y)
        advanceUntilIdle()
        assertEquals(1f, f.diary.fraction, 1e-3f)
        assertFalse(f.diary.isSettling)
    }

    @Test
    fun should_never_report_end_while_half_open() = runTest {
        // the end belongs to the fully open diary (an unmeasured scroll has no end either)
        val f = pull(initial = 0.6f)
        assertFalse(f.deck.atEnd())
    }
}
