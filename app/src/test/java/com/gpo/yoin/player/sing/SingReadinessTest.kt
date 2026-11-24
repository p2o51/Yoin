package com.gpo.yoin.player.sing

import com.gpo.yoin.player.sing.SingReadiness.Handover
import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingReadinessTest {
    /**
     * The live separator's geometry, roughly: a chunk every [STEP] ms of audio on a fixed grid; k
     * chunks from the one under a start make V final from the grid point at or before it, to k steps
     * on less one overlap.
     */
    private fun gridCoverEnd(playheadMs: Long, phaseMs: Long = 0L): (Long, Int) -> Long = { startMs, k ->
        val at = playheadMs + startMs - phaseMs
        val gridStart = Math.floorDiv(at, STEP) * STEP + phaseMs
        gridStart + k * STEP - OVERLAP - playheadMs
    }

    @Test
    fun should_planFewestChunks_when_vMustCoverReadySpanAtArrival() {
        for (phase in longArrayOf(0, 400, 900, 1_500)) {
            val perChunk = 1_300.0
            val cover = gridCoverEnd(playheadMs = 60_000, phaseMs = phase)
            val lead = SingReadiness.planLead(perChunk, SingReadiness.READY_AHEAD_MS.toLong(), coverEndMs = cover)
            val arrive = (lead.chunks * perChunk).toLong()
            // Starts just behind where the playhead will be, and V then covers the Ready span from there.
            assertEquals(arrive - SingReadiness.PLAN_MARGIN_MS, lead.leadMs)
            assertTrue(cover(lead.leadMs, lead.chunks) - arrive >= SingReadiness.READY_AHEAD_MS)
            // One chunk fewer wouldn't do.
            val fewer = lead.chunks - 1
            if (fewer >= 1) {
                val fewerArrive = (fewer * perChunk).toLong()
                assertTrue(cover(fewerArrive - SingReadiness.PLAN_MARGIN_MS, fewer) - fewerArrive < SingReadiness.READY_AHEAD_MS)
            }
            // Pixel Tablet while playing: 3–4 chunks, ~4–5 s, never the old 8 s span's 6–7.
            assertTrue("phase $phase: ${lead.chunks} chunks", lead.chunks in 3..4)
        }
    }

    @Test
    fun should_leadFurther_when_measuredChunksAreSlower() {
        val cover = gridCoverEnd(playheadMs = 30_000)
        val idle = SingReadiness.planLead(650.0, 4_000, coverEndMs = cover)
        val playing = SingReadiness.planLead(1_300.0, 4_000, coverEndMs = cover)
        assertTrue("${idle.leadMs} < ${playing.leadMs}", idle.leadMs < playing.leadMs)
        // The idle figure would aim ~2 s short: the playhead would be past V's start when it's done.
        assertTrue(playing.leadMs - idle.leadMs >= 1_500)
    }

    @Test
    fun should_needOneChunk_when_vAlreadyCoversAhead() {
        val lead = SingReadiness.planLead(1_200.0, 4_000) { _, _ -> Long.MAX_VALUE / 4 }
        assertEquals(1, lead.chunks)
        assertEquals(1_200L - SingReadiness.PLAN_MARGIN_MS, lead.leadMs)
    }

    @Test
    fun should_aimAsFarAsAllowed_when_noPlanWithinMaxLead() {
        // 12 s a chunk: two chunks don't cover the span, three would take longer than the longest lead.
        val lead = SingReadiness.planLead(12_000.0, 4_000) { startMs, k -> startMs + k * 1_800L }
        assertEquals(SingReadiness.MAX_LEAD_MS, lead.leadMs)
    }

    @Test
    fun should_handOver_when_engagedPausedAndCoveredOrTimedOut() {
        // Playing: only the mixer's first removal (or the timeout) — the glide starts there.
        assertEquals(Handover.Engaged, SingReadiness.handover(engaged = true, playing = true, coveredAtPlayhead = true, elapsedMs = 10))
        assertNull(SingReadiness.handover(engaged = false, playing = true, coveredAtPlayhead = true, elapsedMs = 2_000))
        assertEquals(Handover.Timeout, SingReadiness.handover(engaged = false, playing = true, coveredAtPlayhead = false, elapsedMs = 8_000))
        // Paused: nothing can engage; V covering the Ready span at the playhead is enough.
        assertEquals(Handover.PausedCoverage, SingReadiness.handover(engaged = false, playing = false, coveredAtPlayhead = true, elapsedMs = 0))
        assertNull(SingReadiness.handover(engaged = false, playing = false, coveredAtPlayhead = false, elapsedMs = 7_999))
        assertEquals(Handover.Timeout, SingReadiness.handover(engaged = false, playing = false, coveredAtPlayhead = false, elapsedMs = 8_000))
    }

    @Test
    fun should_beReady_when_vCoversReadySpanFromPlayhead() {
        val rate = 44_100
        val frames = rate * 20
        val track = SingVocalTrack.inMemory(rate, frames)
        val zeros = FloatBuffer.allocate(frames)
        // V in for 5..10 s.
        track.writeVocal(5 * rate, 10 * rate, zeros, zeros)
        assertFalse(SingReadiness.isReadyAt(track, 4_000))
        assertTrue(SingReadiness.isReadyAt(track, 5_100))
        assertTrue(SingReadiness.isReadyAt(track, 5_900))
        assertFalse(SingReadiness.isReadyAt(track, 6_100))
        // Near the end of the song the span is cut short by it.
        track.writeVocal(17 * rate, frames, zeros, zeros)
        assertTrue(SingReadiness.isReadyAt(track, 18_000))
        assertTrue(SingReadiness.isReadyAt(track, 25_000))
    }

    @Test
    fun should_planOnMeasuredChunks_when_averagingLive() {
        // The first chunk timed replaces any guess; later ones move the average 30 % of the way.
        assertEquals(1_280.0, OnDeviceSingStreamProvider.liveAverage(null, 1_280.0), 0.0)
        assertEquals(1_000.0 * 0.7 + 1_300.0 * 0.3, OnDeviceSingStreamProvider.liveAverage(1_000.0, 1_300.0), 1e-9)
        var average: Double? = 650.0
        repeat(8) { average = OnDeviceSingStreamProvider.liveAverage(average, 1_290.0) }
        assertTrue("$average", average!! > 1_250.0)
    }

    private companion object {
        const val STEP = 1_858L
        const val OVERLAP = 1_115L
    }
}
