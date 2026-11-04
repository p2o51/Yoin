package com.gpo.yoin.player

import com.gpo.yoin.data.model.ReplayGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGainTest {
    private val flac = ReplayGain(trackGainDb = -9.6f, trackPeak = 0.9886f)
    private val butterfly = ReplayGain(trackGainDb = 1.0f, trackPeak = 0.335f)
    private val albumTagged = ReplayGain(trackGainDb = -3f, albumGainDb = -5f, trackPeak = 0.5f, albumPeak = 0.6f)

    private fun settings(mode: ReplayGainMode, preamp: Float = 0f) = ReplayGainSettings(mode = mode, preampDb = preamp)

    @Test
    fun should_applyTrackGain_when_modeIsTrack() {
        val applied = appliedReplayGain(albumTagged, settings(ReplayGainMode.Track))
        assertEquals(-3f, applied.db, 1e-4f)
        assertEquals(dbToLinear(-3f), applied.linear, 1e-6f)
        assertEquals(ReplayGainSource.Track, applied.source)
    }

    @Test
    fun should_applyAlbumGain_when_modeIsAlbumAndSongHasIt() {
        val applied = appliedReplayGain(albumTagged, settings(ReplayGainMode.Album))
        assertEquals(-5f, applied.db, 1e-4f)
        assertEquals(ReplayGainSource.Album, applied.source)
    }

    @Test
    fun should_fallBackToTrackGain_when_modeIsAlbumAndAlbumGainMissing() {
        val applied = appliedReplayGain(flac, settings(ReplayGainMode.Album))
        assertEquals(-9.6f, applied.db, 1e-4f)
        assertEquals(ReplayGainSource.AlbumFromTrack, applied.source)
    }

    @Test
    fun should_addPreamp_when_set() {
        val applied = appliedReplayGain(flac, settings(ReplayGainMode.Track, preamp = 4f))
        assertEquals(-5.6f, applied.db, 1e-4f)
        assertFalse(applied.peakLimited)
    }

    @Test
    fun should_limitGainToThePeak_when_peakTimesGainPassesFullScale() {
        // +1 dB + 4 dB on a 0.335 peak is ×1.78: well under 1/0.335 = ×2.99, no limit.
        assertFalse(appliedReplayGain(butterfly, settings(ReplayGainMode.Track, preamp = 4f)).peakLimited)
        // +12 dB would be ×3.98: held at ×2.99 so the peak lands at 1.0.
        val loud = appliedReplayGain(butterfly, settings(ReplayGainMode.Track, preamp = 11f))
        assertTrue(loud.peakLimited)
        assertEquals(1f, loud.linear * 0.335f, 1e-5f)
        assertEquals(linearToDb(1f / 0.335f), loud.db, 1e-4f)
    }

    @Test
    fun should_limitWithTheTracksPeak_when_albumGainIsUsed() {
        val applied = appliedReplayGain(
            ReplayGain(albumGainDb = 6f, trackPeak = 0.9f, albumPeak = 0.99f),
            settings(ReplayGainMode.Album),
        )
        assertTrue(applied.peakLimited)
        assertEquals(1f / 0.9f, applied.linear, 1e-5f)
    }

    @Test
    fun should_notLimit_when_noPeakIsKnown() {
        val applied = appliedReplayGain(ReplayGain(trackGainDb = 6f), settings(ReplayGainMode.Track))
        assertFalse(applied.peakLimited)
        assertEquals(dbToLinear(6f), applied.linear, 1e-5f)
    }

    @Test
    fun should_leaveVolumeAlone_when_songHasNoTags() {
        val preamp = settings(ReplayGainMode.Album, preamp = 4f)
        assertEquals(AppliedReplayGain.Unity, appliedReplayGain(null, preamp))
        assertEquals(AppliedReplayGain.Unity, appliedReplayGain(ReplayGain(fallbackGainDb = -6f), preamp))
        assertEquals(AppliedReplayGain.Unity, appliedReplayGain(ReplayGain(albumGainDb = -6f), settings(ReplayGainMode.Track, 4f)))
    }

    @Test
    fun should_leaveVolumeAlone_when_modeIsOff() {
        assertEquals(AppliedReplayGain.Unity, appliedReplayGain(flac, settings(ReplayGainMode.Off, preamp = 4f)))
    }

    @Test
    fun should_levelTheSurroundAlbum_when_modeIsAlbum() {
        // 《再生（全景声）》: track gains only, about 10 dB apart; by album they meet.
        val s = settings(ReplayGainMode.Album, preamp = 4f)
        val flacLoudness = -9f + appliedReplayGain(flac, s).db
        val butterflyLoudness = -19f + appliedReplayGain(butterfly, s).db
        assertEquals(flacLoudness, butterflyLoudness, 1.5f)
    }

    @Test
    fun should_defaultToOffWithFourDbPreamp_when_nothingIsStored() {
        assertEquals(ReplayGainMode.Off, ReplayGainSettings().mode)
        assertEquals(4f, ReplayGainSettings().preampDb, 0f)
    }
}
