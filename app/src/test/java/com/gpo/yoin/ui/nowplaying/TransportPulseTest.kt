package com.gpo.yoin.ui.nowplaying

import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TestMonotonicFrameClock
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D4 §1: option B + the P0 corrections (owner pick 2026-10-05). */
class TransportPulseTest {

    private val now = 100_000L
    private val playing = TransportPulseKey(songId = "a", isPlaying = true, queueIndex = 3)
    private val paused = playing.copy(isPlaying = false)
    private val nextSong = TransportPulseKey(songId = "b", isPlaying = true, queueIndex = 4)
    private val previousSong = TransportPulseKey(songId = "z", isPlaying = true, queueIndex = 2)

    private val playButton = Offset(120f, 900f)
    private val nextButton = Offset(260f, 900f)

    private fun tap(kind: TransportTapKind, center: Offset, agoMs: Long = 400L) =
        TransportTap(centerRoot = center, uptimeMs = now - agoMs, kind = kind)

    private fun resolve(
        previous: TransportPulseKey?,
        current: TransportPulseKey?,
        tap: TransportTap? = null,
        lyricsPrimary: Boolean = false,
        handover: LyricsHandover? = null,
    ) = resolveTransportPulse(previous, current, tap, now, lyricsPrimary, handover)

    @Test
    fun should_notPulse_when_playbackStartsStopsOrNothingChanged() {
        assertEquals(TransportPulse.None, resolve(null, playing).pulse)
        assertEquals(TransportPulse.None, resolve(playing, null).pulse)
        assertEquals(TransportPulse.None, resolve(playing, playing).pulse)
        // A queue edit that moves the current song's index is not a commit.
        assertEquals(TransportPulse.None, resolve(playing, playing.copy(queueIndex = 7)).pulse)
    }

    @Test
    fun should_breatheFromThePlayButton_when_aFreshPlayTapPauses() {
        val decision = resolve(playing, paused, tap(TransportTapKind.PlayPause, playButton))

        assertEquals(TransportPulse.Burst(isPlay = false, focalRoot = playButton), decision.pulse)
        assertTrue(decision.tapUsed)
    }

    @Test
    fun should_breatheFromTheNeutralPoint_when_playPauseCameFromOutside() {
        // Headset / notification: no tap, or only a stale one.
        assertEquals(TransportPulse.Burst(isPlay = true, focalRoot = null), resolve(paused, playing).pulse)
        val stale = resolve(paused, playing, tap(TransportTapKind.PlayPause, playButton, agoMs = 3_500L))
        assertEquals(TransportPulse.Burst(isPlay = true, focalRoot = null), stale.pulse)
        assertFalse(stale.tapUsed)
    }

    @Test
    fun should_ignoreThePlayStateFlicker_when_aSkipIsInFlight() {
        val decision = resolve(playing, paused, tap(TransportTapKind.SkipNext, nextButton))

        assertEquals(TransportPulse.None, decision.pulse)
        // Kept for the song change it is about to cause.
        assertFalse(decision.tapUsed)
    }

    @Test
    fun should_ringFromTheTappedButton_when_theCoverLeadsAFreshSkip() {
        val decision = resolve(playing, nextSong, tap(TransportTapKind.SkipNext, nextButton))

        assertEquals(TransportPulse.Burst(isPlay = true, focalRoot = nextButton), decision.pulse)
        assertTrue(decision.tapUsed)
    }

    @Test
    fun should_neverReuseAnOldButton_when_theSongChangesUntapped() {
        // Auto-advance, notification, queue tap — the last tap is minutes old.
        val stale = tap(TransportTapKind.SkipNext, nextButton, agoMs = 120_000L)
        assertEquals(TransportPulse.None, resolve(playing, nextSong, stale).pulse)
        assertEquals(TransportPulse.None, resolve(playing, nextSong).pulse)
        // A play tap is not a skip's origin.
        val playTap = tap(TransportTapKind.PlayPause, playButton)
        assertEquals(TransportPulse.None, resolve(playing, nextSong, playTap).pulse)
    }

    @Test
    fun should_treatItAsASkip_when_songAndPlayStateChangeTogether() {
        val decision = resolve(playing, nextSong.copy(isPlaying = false), tap(TransportTapKind.SkipNext, nextButton))

        assertEquals(TransportPulse.Burst(isPlay = false, focalRoot = nextButton), decision.pulse)
    }

    @Test
    fun should_runTheLightWithTheLyricFlow_when_theLyricsLead() {
        assertEquals(
            TransportPulse.FlowLight(forward = true),
            resolve(playing, nextSong, tap(TransportTapKind.SkipNext, nextButton), lyricsPrimary = true).pulse,
        )
        // Untapped too: auto-advance, external controls.
        assertEquals(TransportPulse.FlowLight(forward = true), resolve(playing, nextSong, lyricsPrimary = true).pulse)
        assertEquals(TransportPulse.FlowLight(forward = false), resolve(playing, previousSong, lyricsPrimary = true).pulse)
    }

    @Test
    fun should_spendTheSkipTap_when_theLightAnswersIt() {
        val decision = resolve(playing, nextSong, tap(TransportTapKind.SkipNext, nextButton), lyricsPrimary = true)

        assertTrue(decision.tapUsed)
    }

    @Test
    fun should_stayQuiet_when_theOutroAlreadyHandedTheSongOverInPlace() {
        val handover = LyricsHandover(fromSongId = "a", toSongId = "b")

        assertEquals(TransportPulse.None, resolve(playing, nextSong, lyricsPrimary = true, handover = handover).pulse)
        // A hand-over staged for another song doesn't silence this change.
        assertEquals(
            TransportPulse.FlowLight(forward = true),
            resolve(playing, nextSong, lyricsPrimary = true, handover = LyricsHandover("a", "c")).pulse,
        )
    }

    @Test
    fun should_trustATapOnlyForThreeSeconds_when_thePlayerIsSlow() {
        val justInTime = resolve(playing, nextSong, tap(TransportTapKind.SkipNext, nextButton, agoMs = 3_000L))
        val tooLate = resolve(playing, nextSong, tap(TransportTapKind.SkipNext, nextButton, agoMs = 3_001L))

        assertEquals(TransportPulse.Burst(isPlay = true, focalRoot = nextButton), justInTime.pulse)
        assertEquals(TransportPulse.None, tooLate.pulse)
    }

    @Test
    fun should_followTheLyricStreamDirection_when_theQueuePositionMoves() {
        assertTrue(lyricFlowForward(previousQueueIndex = 3, queueIndex = 4))
        assertTrue(lyricFlowForward(previousQueueIndex = 3, queueIndex = 3))
        assertFalse(lyricFlowForward(previousQueueIndex = 3, queueIndex = 2))
    }

    @Test
    fun should_riseFromBelowOntoTheTitle_when_theLightRunsForward() {
        val column = Rect(left = 400f, top = 100f, right = 1200f, bottom = 900f)
        val landingY = column.top + 150f

        val start = flowLightFrame(column, landingY, forward = true, travel = 0f)
        assertEquals(Offset(800f, 900f + 0.25f * 800f), start.center)
        assertEquals(0.25f * 800f, start.radiusY, 1e-3f)
        assertEquals(0.62f * 800f, start.radiusX, 1e-3f)

        val end = flowLightFrame(column, landingY, forward = true, travel = 1f)
        assertEquals(landingY, end.center.y, 1e-3f)
        assertEquals(0.09f * 800f, end.radiusY, 1e-3f)
    }

    @Test
    fun should_sinkFromAbove_when_theLightRunsBackward() {
        val column = Rect(left = 0f, top = 100f, right = 800f, bottom = 900f)

        val start = flowLightFrame(column, landingY = 250f, forward = false, travel = 0f)
        assertEquals(100f - 0.25f * 800f, start.center.y, 1e-3f)
        // The spring's overshoot moves the centre past the title but never
        // squeezes the band thinner than its resting height.
        val overshoot = flowLightFrame(column, landingY = 250f, forward = false, travel = 1.015f)
        assertTrue(overshoot.center.y > 250f)
        assertEquals(0.09f * 800f, overshoot.radiusY, 1e-3f)
    }

    // ── Wave-2 gate fix: a PREVIOUS that only restarts the song ─────────────

    private val prevButton = Offset(40f, 1_000f)

    private fun prevTap(songId: Any? = "a", positionMs: Long = 42_000L) = TransportTap(
        centerRoot = prevButton,
        uptimeMs = now - 400L,
        kind = TransportTapKind.SkipPrevious,
        songId = songId,
        positionMs = positionMs,
    )

    /** A signal on song "a" holding a PREVIOUS tapped 42s in. */
    private fun signalAfterPreviousTap(): NowPlayingTransportSignal = NowPlayingTransportSignal().apply {
        committedSongId = "a"
        recordTap(prevButton, TransportTapKind.SkipPrevious, positionMs = 42_000L)
    }

    @Test
    fun should_readAsARestart_when_thePlayheadJumpsBackOnTheTappedSong() {
        // Spotify past its first seconds, or a one-song queue wrapping onto itself.
        assertTrue(prevTap().restartedInPlace(songId = "a", positionMs = 200L))
        assertTrue(prevTap().restartedInPlace(songId = "a", positionMs = 41_000L))
    }

    @Test
    fun should_notReadAsARestart_when_theSongChangesOrThePlayheadOnlyDrifts() {
        // The previous song arrived: that change is the pulse's to answer.
        assertFalse(prevTap().restartedInPlace(songId = "z", positionMs = 0L))
        // Playback moving on, or a sub-second correction of the playhead.
        assertFalse(prevTap().restartedInPlace(songId = "a", positionMs = 42_250L))
        assertFalse(prevTap().restartedInPlace(songId = "a", positionMs = 41_500L))
        // Unknown song or playhead at the tap: never guessed.
        assertFalse(prevTap(songId = null).restartedInPlace(songId = null, positionMs = 0L))
        assertFalse(prevTap(positionMs = UnknownTapPositionMs).restartedInPlace(songId = "a", positionMs = 0L))
        // Only PREVIOUS restarts.
        assertFalse(prevTap().copy(kind = TransportTapKind.SkipNext).restartedInPlace(songId = "a", positionMs = 0L))
        assertFalse(prevTap().copy(kind = TransportTapKind.PlayPause).restartedInPlace(songId = "a", positionMs = 0L))
    }

    @Test
    fun should_spendThePreviousTapAtOnce_when_thePlayerOnlyRestartsTheSong() {
        val signal = signalAfterPreviousTap()
        assertNotNull(signal.lastTap)

        signal.observePlayhead(42_250L)
        assertNotNull("playback moving on keeps the tap", signal.lastTap)

        signal.observePlayhead(150L)
        assertNull(signal.lastTap)
    }

    @Test
    fun should_breatheFromTheNeutralPoint_when_aHeadsetPauseFollowsARestartedPrevious() {
        val signal = signalAfterPreviousTap()
        signal.observePlayhead(150L)

        val decision = resolve(playing, paused, signal.lastTap)

        // Before the fix the standing PREVIOUS tap swallowed this as a "skip flicker".
        assertEquals(TransportPulse.Burst(isPlay = false, focalRoot = null), decision.pulse)
    }

    @Test
    fun should_notRingFromPrevious_when_anUntappedChangeFollowsARestartedPrevious() {
        val signal = signalAfterPreviousTap()
        signal.observePlayhead(150L)

        // Notification "next", a queue tap: nobody touched a button for this.
        assertEquals(TransportPulse.None, resolve(playing, nextSong, signal.lastTap).pulse)
    }

    @Test
    fun should_keepThePreviousTapForItsRing_when_thePreviousSongArrives() {
        val signal = signalAfterPreviousTap()
        val tapped = signal.lastTap!!
        // The screen moves to the previous song; its playhead starts near zero.
        signal.committedSongId = "z"
        signal.observePlayhead(0L)
        assertEquals(tapped, signal.lastTap)

        val decision = resolveTransportPulse(
            previous = playing,
            current = previousSong,
            tap = signal.lastTap,
            nowUptimeMs = tapped.uptimeMs + 400L,
            lyricsPrimary = false,
            handover = null,
        )
        assertEquals(TransportPulse.Burst(isPlay = true, focalRoot = prevButton), decision.pulse)
        assertTrue(decision.tapUsed)
    }

    // ── 2026-10-05 device QA: Media3 buffers after a skip / restart ─────────

    private val nextBuffering = nextSong.copy(isPlaying = false)

    @Test
    fun should_notBreatheAfterTheLight_when_theSkippedToSongFinishesBuffering() {
        // Tapped NEXT: the change commits paused (buffering) and spends the tap.
        val skip = resolve(playing, nextBuffering, tap(TransportTapKind.SkipNext, nextButton), lyricsPrimary = true)
        assertEquals(TransportPulse.FlowLight(forward = true), skip.pulse)
        assertTrue(skip.tapUsed)
        assertNotNull(skip.settle)

        // ~300ms later the same song starts playing: no tap is left, and it is not a play.
        val resume = resolveTransportPulse(
            previous = nextBuffering,
            current = nextSong,
            tap = null,
            nowUptimeMs = now + 300L,
            lyricsPrimary = true,
            handover = null,
            settle = skip.settle,
        )
        assertEquals(TransportPulse.None, resume.pulse)
        assertNull("absorbed once", resume.settle)
    }

    @Test
    fun should_settleTheResume_when_anUntappedChangeLandsBuffering() {
        // Auto-advance / notification next, cover-primary: no pulse, and the resume stays quiet too.
        val skip = resolve(playing, nextBuffering)
        assertEquals(TransportPulse.None, skip.pulse)

        val resume = resolveTransportPulse(nextBuffering, nextSong, null, now + 500L, false, null, skip.settle)
        assertEquals(TransportPulse.None, resume.pulse)
    }

    @Test
    fun should_settleADipThatCommitsLate_when_theSkipLandedPlaying() {
        val skip = resolve(playing, nextSong, lyricsPrimary = true)
        val dip = resolveTransportPulse(nextSong, nextBuffering, null, now + 200L, true, null, skip.settle)
        assertEquals(TransportPulse.None, dip.pulse)
        assertNotNull("the resume is still owed", dip.settle)
        val resume = resolveTransportPulse(nextBuffering, nextSong, null, now + 500L, true, null, dip.settle)
        assertEquals(TransportPulse.None, resume.pulse)
    }

    @Test
    fun should_breatheAgain_when_theSettleIsSpentExpiredOrForAnotherSong() {
        val settle = PlaySettle.after(songId = "b", nowUptimeMs = now)
        // A pause after the dip window is a real (headset) pause: the neutral breath.
        val afterDip = now + PlaySettleDipWindowMs + 1L
        val pause = resolveTransportPulse(nextSong, nextBuffering, null, afterDip, true, null, settle)
        assertEquals(TransportPulse.Burst(isPlay = false, focalRoot = null), pause.pulse)
        assertNull(pause.settle)
        // A resume later than a tap's validity is not this skip's.
        val expired = resolveTransportPulse(nextBuffering, nextSong, null, now + 3_001L, true, null, settle)
        assertEquals(TransportPulse.Burst(isPlay = true, focalRoot = null), expired.pulse)
        // Owed to another song: not this resume.
        val otherSong = resolveTransportPulse(paused, playing, null, now + 300L, true, null, settle)
        assertEquals(TransportPulse.Burst(isPlay = true, focalRoot = null), otherSong.pulse)
        // A fresh PLAY tap is a play, settle or not.
        val playTap = TransportTap(playButton, now + 100L, TransportTapKind.PlayPause)
        val tapped = resolveTransportPulse(nextBuffering, nextSong, playTap, now + 300L, true, null, settle)
        assertEquals(TransportPulse.Burst(isPlay = true, focalRoot = playButton), tapped.pulse)
    }

    @Test
    fun should_notSettle_when_theSkipStartedPaused() {
        // Paused, then NEXT: Media3 stays paused, so a later play is the user's.
        assertNull(resolve(paused, nextBuffering).settle)
    }

    @Test
    fun should_carryTheSettle_when_nothingCommits() {
        val settle = PlaySettle.after(songId = "b", nowUptimeMs = now)
        assertEquals(settle, resolveTransportPulse(nextBuffering, nextBuffering, null, now, true, null, settle).settle)
        val queueEdit = nextBuffering.copy(queueIndex = 9)
        assertEquals(settle, resolveTransportPulse(nextBuffering, queueEdit, null, now, true, null, settle).settle)
    }

    @Test
    fun should_settleTheDipAndResume_when_aRestartedPreviousBuffers() {
        val signal = signalAfterPreviousTap()
        signal.observePlayhead(150L)
        assertNull(signal.lastTap)
        val settle = signal.playSettle!!
        assertEquals("a", settle.songId)

        // The one-song queue wrapped onto itself; the playhead was seen first, then
        // the seek's buffering dip, then playing again.
        val seenAt = settle.dipUntilUptimeMs - PlaySettleDipWindowMs
        val dip = resolveTransportPulse(playing, paused, signal.lastTap, seenAt + 100L, true, null, settle)
        assertEquals(TransportPulse.None, dip.pulse)
        val resume = resolveTransportPulse(paused, playing, null, seenAt + 400L, true, null, dip.settle)
        assertEquals(TransportPulse.None, resume.pulse)
        assertNull(resume.settle)
    }

    // ── 2026-10-05 device QA (问题 5): a user seek buffers too ──────────────

    /** A signal on song "a", playing, that just saw the user seek. */
    private fun signalAfterSeek(playing: Boolean = true): NowPlayingTransportSignal =
        NowPlayingTransportSignal().apply {
            committedSongId = "a"
            committedPlaying = playing
            recordSeek()
        }

    @Test
    fun should_settleTheDipAndResume_when_theUserSeeksWhilePlaying() {
        val signal = signalAfterSeek()
        val settle = signal.playSettle!!
        assertEquals("a", settle.songId)
        val seekAt = settle.dipUntilUptimeMs - PlaySettleDipWindowMs

        // Media3 buffers at the new spot: paused, then playing again — no breath for either.
        val dip = resolveTransportPulse(playing, paused, null, seekAt + 80L, true, null, settle)
        assertEquals(TransportPulse.None, dip.pulse)
        val resume = resolveTransportPulse(paused, playing, null, seekAt + 450L, true, null, dip.settle)
        assertEquals(TransportPulse.None, resume.pulse)
        assertNull(resume.settle)
    }

    @Test
    fun should_breatheFromTheNeutralPoint_when_aHeadsetPauseFollowsASeekPastTheDipWindow() {
        val settle = signalAfterSeek().playSettle!!
        val seekAt = settle.dipUntilUptimeMs - PlaySettleDipWindowMs

        val pastDip = seekAt + PlaySettleDipWindowMs + 1L
        val pause = resolveTransportPulse(playing, paused, null, pastDip, true, null, settle)

        assertEquals(TransportPulse.Burst(isPlay = false, focalRoot = null), pause.pulse)
        assertNull(pause.settle)
    }

    @Test
    fun should_owePlayNothing_when_theUserSeeksWhilePausedOrBeforeASongIsShown() {
        assertNull(seekPlaySettle(songId = "a", playing = false, nowUptimeMs = now))
        assertNull(seekPlaySettle(songId = null, playing = true, nowUptimeMs = now))
        assertNull(signalAfterSeek(playing = false).playSettle)
    }

    @Test
    fun should_keepASkipsSettle_when_aSeekWhilePausedHasNothingToAdd() {
        val owed = PlaySettle.after(songId = "a", nowUptimeMs = now)
        val signal = NowPlayingTransportSignal().apply {
            committedSongId = "a"
            committedPlaying = false
            playSettle = owed
        }

        signal.recordSeek()

        assertEquals(owed, signal.playSettle)
    }

    // ── Wave-2 gate fix: the light votes a high frame rate for its sweep ────

    private val scheme = MotionScheme.expressive()
    private val role = YoinMotionRole.Expressive
    private val travelSpec = YoinMotion.slowSpatialSpec<Float>(role = role, expressiveScheme = scheme)
    private val inSpec = YoinMotion.fastEffectsSpec<Float>(role = role, expressiveScheme = scheme)
    private val outSpec = YoinMotion.slowEffectsSpec<Float>(role = role, expressiveScheme = scheme)

    private data class LightFrame(val travel: Float, val alpha: Float, val moving: Boolean)

    @OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
    private fun sweep(run: FlowLightRun): List<LightFrame> {
        val frames = mutableListOf<LightFrame>()
        runTest {
            withContext(TestMonotonicFrameClock(this)) {
                val play = launch { run.play(travelSpec, inSpec, outSpec) }
                val sampler = launch {
                    while (true) {
                        withFrameNanos { frames += LightFrame(run.travel.value, run.alpha.value, run.moving) }
                    }
                }
                play.join()
                sampler.cancel()
            }
        }
        return frames
    }

    @Test
    fun should_voteForTheWholeSweep_when_theFlowLightRuns() {
        val run = FlowLightRun()
        assertFalse(run.moving)

        val frames = sweep(run)

        // Every frame the light is on screen or still travelling votes High…
        val visible = frames.filter { it.alpha > 0.001f || it.travel < 0.999f }
        assertTrue(visible.size > 10)
        assertTrue(visible.all { it.moving })
        // …and the vote ends with the sweep.
        assertFalse(run.moving)
        assertEquals(0f, run.alpha.value, 0f)
        assertEquals(1f, run.travel.value, 1e-3f)
    }

    @Test
    fun should_letTheLightGoOnlyPastEightyFivePercent_when_itTravels() {
        val frames = sweep(FlowLightRun())

        val firstFade = frames.zipWithNext().first { (a, b) -> b.alpha < a.alpha - 1e-4f }
        assertTrue(firstFade.first.travel >= FlowLightFadeOutAt)
        assertEquals(1f, frames.maxOf { it.alpha }, 0.02f)
    }
}
