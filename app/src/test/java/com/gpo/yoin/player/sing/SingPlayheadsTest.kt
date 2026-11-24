package com.gpo.yoin.player.sing

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.player.sing.dsp.LiveVocalSeparator
import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's idea of where the listener is before the player's first report: Sing turned on 112.5 s
 * into a song used to plan chunk 0, seek the decoder to 0 s and miss a kept track covering the
 * listener, all from the song-start position.
 */
class SingPlayheadsTest {
    private var nanos = 5_000_000_000L
    private val playheads = SingPlayheads { nanos }
    private val song = SingRequest(MediaId.subsonic("1"), "p", positionMs = 112_500L, playing = true)

    private fun advanceMs(ms: Long) {
        nanos += ms * 1_000_000
    }

    @Test
    fun should_startFromTheRequestPosition_when_noReportYet() {
        playheads.begin(song)
        assertEquals(112_500L, playheads.at(song).positionMs)
        advanceMs(300)
        // Playing: moved on by the time since the job started.
        assertEquals(112_800L, playheads.at(song).positionMs)
        assertTrue(playheads.at(song).playing)
    }

    @Test
    fun should_stayPut_when_requestedPaused() {
        val paused = song.copy(playing = false)
        playheads.begin(paused)
        advanceMs(2_000)
        assertEquals(112_500L, playheads.at(paused).positionMs)
        assertFalse(playheads.at(paused).playing)
    }

    @Test
    fun should_keepAReportFromJustNow_when_itBeatTheJobsStart() {
        playheads.report(song, 112_600L, playing = true)
        advanceMs(100)
        playheads.begin(song)
        assertEquals(112_700L, playheads.at(song).positionMs)
    }

    @Test
    fun should_dropAReportFromAnEarlierGo_when_beginning() {
        // Sing on at 10 s, failed, on again at 112.5 s: the old report (still "playing") is stale.
        playheads.report(song, 10_000L, playing = true)
        advanceMs(60_000)
        playheads.begin(song.copy(positionMs = 112_500L, playing = false))
        advanceMs(500)
        assertEquals(112_500L, playheads.at(song).positionMs)
    }

    @Test
    fun should_notCountTheFirstReportAsASeek_when_itMatchesTheRequest() {
        playheads.begin(song)
        advanceMs(500)
        assertFalse(playheads.report(song, 113_000L, playing = true))
        assertEquals(0, playheads.seekGeneration(song))
        advanceMs(500)
        assertTrue(playheads.report(song, 30_000L, playing = true))
        assertEquals(1, playheads.seekGeneration(song))
    }

    @Test
    fun should_forgetOtherSongs_when_beginning() {
        val other = SingRequest(MediaId.subsonic("2"), "p", positionMs = 0L)
        playheads.report(other, 50_000L, playing = false)
        playheads.begin(song)
        // The other song falls back to its own request's position.
        assertEquals(0L, playheads.at(other).positionMs)
    }

    @Test
    fun should_planTheFirstChunkAtTheListener_when_sungMidSong() {
        val rate = 8_000
        val frames = 132 * rate
        val separator = LiveVocalSeparator(FloatBuffer.allocate(frames), FloatBuffer.allocate(frames), frames, rate)
        val track = SingVocalTrack.inMemory(rate, frames)
        playheads.begin(song)

        val target = playheads.at(song).positionMs + 2_500L
        val chunk = SingReadiness.chunkForTarget(separator, track, target)

        val (from, to) = separator.inputRange(chunk)
        assertTrue("chunk $chunk reads ${from / rate}–${to / rate} s", target * rate / 1000 in from until to)
        assertTrue("not the song's first chunk", chunk > separator.chunkCount / 2)
    }

    @Test
    fun should_beReadyFromAKeptTrack_when_itCoversTheRequestPosition() {
        val rate = 8_000
        val frames = 132 * rate
        val kept = SingVocalTrack.inMemory(rate, frames)
        val from = 110 * rate
        val to = 120 * rate
        kept.writeVocal(from, to, FloatBuffer.allocate(to - from), FloatBuffer.allocate(to - from), sourceOffset = from)

        playheads.begin(song.copy(playing = false))
        assertTrue(SingReadiness.isReadyAt(kept, playheads.at(song).positionMs))
        // From the song's start (the old default) it wasn't: chunk 0 ran first.
        assertFalse(SingReadiness.isReadyAt(kept, playheads.at(SingRequest(MediaId.subsonic("9"), "p")).positionMs))
    }
}
