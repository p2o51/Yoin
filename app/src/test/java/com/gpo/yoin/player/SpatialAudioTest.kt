package com.gpo.yoin.player

import android.media.AudioFormat
import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpatialAudioTest {

    private val ready = SpatialAudioState(
        supported = true,
        enabled = true,
        available = true,
        spatializableChannels = setOf(6, 8),
    )

    @Test
    fun stereoAndUnknownAreNotMultichannel() {
        assertEquals(SpatialPlayback.NotMultichannel, spatialPlaybackOf(null, ready))
        assertEquals(SpatialPlayback.NotMultichannel, spatialPlaybackOf(1, ready))
        assertEquals(SpatialPlayback.NotMultichannel, spatialPlaybackOf(2, ready))
    }

    @Test
    fun fivePointOneIsSpatializedWhereTheSystemCan() {
        assertEquals(SpatialPlayback.Spatialized, spatialPlaybackOf(6, ready))
        assertEquals(SpatialPlayback.Spatialized, spatialPlaybackOf(8, ready))
    }

    @Test
    fun aLayoutTheOutputCantSpatializeIsFoldedDown() {
        assertEquals(SpatialPlayback.DownmixedOutput, spatialPlaybackOf(4, ready))
        val speaker = ready.copy(available = false, spatializableChannels = emptySet())
        assertEquals(SpatialPlayback.DownmixedOutput, spatialPlaybackOf(6, speaker))
    }

    @Test
    fun yoinsSwitchWinsOverTheSystem() {
        assertEquals(
            SpatialPlayback.DownmixedByChoice,
            spatialPlaybackOf(6, ready.copy(mode = SpatialAudioMode.Off)),
        )
    }

    @Test
    fun pixelTabletWithTheSystemSwitchOff() {
        // #121 QA: mCapableSpatLevel 1 (multichannel), isSpatializerEnabled false.
        val tablet = SpatialAudioState(supported = true, enabled = false)
        assertEquals(SpatialPlayback.DownmixedSystemOff, spatialPlaybackOf(6, tablet))
    }

    @Test
    fun noSpatializerBeforeAndroid13() {
        assertEquals(SpatialPlayback.DownmixedUnsupported, spatialPlaybackOf(6, SpatialAudioState()))
    }

    @Test
    fun modeMapsToMedia3Behaviour() {
        assertEquals(C.SPATIALIZATION_BEHAVIOR_AUTO, musicAudioAttributes(SpatialAudioMode.Auto).spatializationBehavior)
        assertEquals(C.SPATIALIZATION_BEHAVIOR_NEVER, musicAudioAttributes(SpatialAudioMode.Off).spatializationBehavior)
        assertEquals(C.USAGE_MEDIA, musicAudioAttributes(SpatialAudioMode.Auto).usage)
        assertEquals(C.AUDIO_CONTENT_TYPE_MUSIC, musicAudioAttributes(SpatialAudioMode.Auto).contentType)
    }

    @Test
    fun modeKeysRoundTrip() {
        SpatialAudioMode.entries.forEach { assertEquals(it, SpatialAudioMode.fromKey(it.key)) }
        assertNull(SpatialAudioMode.fromKey("nope"))
    }

    @Test
    fun channelMasksAreTheUsualLayouts() {
        assertEquals(AudioFormat.CHANNEL_OUT_5POINT1, multichannelMaskOf(6))
        assertEquals(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, multichannelMaskOf(8))
        (3..8).forEach { assertEquals(it, Integer.bitCount(multichannelMaskOf(it)!!)) }
        assertNull(multichannelMaskOf(2))
        assertNull(multichannelMaskOf(12))
    }
}
