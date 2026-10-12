package com.gpo.yoin.player.applemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 4 (Apple Music): after a tap on PAUSE the session published MusicKit's
 * not-yet-updated PLAYING, the controller dropped its masked pause, and the
 * pill flipped PAUSE → PLAY → PAUSE. What gets published must never step back
 * to the old state while MusicKit catches up.
 */
class MusicKitPlayRequestTest {

    @Test
    fun should_keepPublishingPause_when_musicKitStillReportsPlayingAfterPause() {
        val request = MusicKitPlayRequest()
        request.request(play = false, musicKitPlaying = true)

        assertTrue(request.pending)
        // The session's publish right after the command, and a callback that
        // arrives before MusicKit's state has moved.
        assertFalse(request.resolve(musicKitPlaying = true))
        assertFalse(request.resolve(musicKitPlaying = true))
        // MusicKit pauses: the request is answered.
        assertFalse(request.resolve(musicKitPlaying = false))
        assertFalse(request.pending)
    }

    @Test
    fun should_keepPublishingPlay_when_musicKitPassesThroughAnotherStateFirst() {
        val request = MusicKitPlayRequest()
        request.request(play = true, musicKitPlaying = false)

        // Paused, then a transitional non-playing report: still on its way.
        assertTrue(request.resolve(musicKitPlaying = false))
        assertTrue(request.resolve(musicKitPlaying = false))
        assertTrue(request.pending)
        assertTrue(request.resolve(musicKitPlaying = true))
        assertFalse(request.pending)
    }

    @Test
    fun should_neverStepBackToTheOldState_when_pauseIsAnsweredLate() {
        val request = MusicKitPlayRequest()
        request.request(play = false, musicKitPlaying = true)
        val published = listOf(true, true, false, false).map { request.resolve(musicKitPlaying = it) }

        assertEquals(listOf(false, false, false, false), published)
    }

    @Test
    fun should_followMusicKit_when_itAlreadyReportsTheRequestedState() {
        val request = MusicKitPlayRequest()
        request.request(play = false, musicKitPlaying = false)

        assertFalse(request.pending)
        assertTrue(request.resolve(musicKitPlaying = true))
    }

    @Test
    fun should_keepLatestRequest_when_pauseReversesAPlayBeforeMusicKitAnswers() {
        val request = MusicKitPlayRequest()
        request.request(play = true, musicKitPlaying = false)
        request.request(play = false, musicKitPlaying = false)

        assertFalse(request.resolve(musicKitPlaying = false))
        assertTrue(request.pending)
        request.request(play = false, musicKitPlaying = false)
        assertTrue(request.pending)
        // The first command arrives late, followed by the requested pause.
        assertFalse(request.resolve(musicKitPlaying = true))
        assertFalse(request.resolve(musicKitPlaying = false))
        assertFalse(request.pending)
    }

    @Test
    fun should_keepLatestRequest_when_playReversesAPauseBeforeMusicKitAnswers() {
        val request = MusicKitPlayRequest()
        request.request(play = false, musicKitPlaying = true)
        request.request(play = true, musicKitPlaying = true)

        assertTrue(request.resolve(musicKitPlaying = true))
        assertTrue(request.pending)
        assertTrue(request.resolve(musicKitPlaying = false))
        assertTrue(request.resolve(musicKitPlaying = true))
        assertFalse(request.pending)
    }

    @Test
    fun should_followMusicKitAfterTimeout_when_reversedCommandsNeverLeaveCurrentState() {
        val request = MusicKitPlayRequest()
        request.request(play = true, musicKitPlaying = false)
        request.request(play = false, musicKitPlaying = false)
        assertFalse(request.resolve(musicKitPlaying = false))
        request.clear()

        assertFalse(request.pending)
        assertTrue(request.resolve(musicKitPlaying = true))
    }

    @Test
    fun should_followMusicKitAgain_when_theRequestHasBeenAnswered() {
        val request = MusicKitPlayRequest()
        request.request(play = true, musicKitPlaying = false)
        request.resolve(musicKitPlaying = true)

        // A pause of MusicKit's own (focus loss, headset) shows straight away.
        assertFalse(request.resolve(musicKitPlaying = false))
    }

    @Test
    fun should_followMusicKit_when_theWaitIsGivenUp() {
        val request = MusicKitPlayRequest()
        request.request(play = true, musicKitPlaying = false)
        request.clear()

        assertFalse(request.pending)
        assertFalse(request.resolve(musicKitPlaying = false))
    }
}
