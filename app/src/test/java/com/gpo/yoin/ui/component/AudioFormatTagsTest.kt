package com.gpo.yoin.ui.component

import com.gpo.yoin.data.model.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioFormatTagsTest {
    private val hiRes = AudioFormat("flac", 3000, 24, 96000, 2)
    private val surround = AudioFormat("flac", 2116, 16, 44100, 6)

    @Test
    fun should_showDepthAndRate_when_lossless() {
        assertEquals(listOf("FLAC 24/96"), audioFormatTags(hiRes))
        assertEquals(listOf("FLAC 16/44.1", "5.1"), audioFormatTags(surround))
    }

    @Test
    fun should_showBitRate_when_lossy() {
        assertEquals(listOf("MP3 320"), audioFormatTags(AudioFormat("mp3", 320)))
    }

    @Test
    fun should_showCodecOnly_when_nothingElseKnown() {
        assertEquals(listOf("FLAC"), audioFormatTags(AudioFormat("flac")))
        assertEquals(emptyList<String>(), audioFormatTags(null))
    }

    @Test
    fun should_nameLayouts_when_moreThanTwoChannels() {
        assertNull(channelLayoutLabel(2))
        assertEquals("5.1", channelLayoutLabel(6))
        assertEquals("7.1", channelLayoutLabel(8))
    }

    @Test
    fun should_showArrivingFormat_when_transcoding() {
        val decoded = AudioFormat("opus", sampleRateHz = 48000, channels = 2)
        assertEquals(listOf("Opus 192"), playingFormatTags(surround, decoded, "opus", 192))
        assertEquals("FLAC 16/44.1", transcodedFrom(surround, 192))
    }

    @Test
    fun should_takeDecoderChannels_when_playingFile() {
        val decoded = AudioFormat("flac", sampleRateHz = 44100, channels = 6)
        val serverWithoutChannels = surround.copy(channels = null)
        assertEquals(listOf("FLAC 16/44.1", "5.1"), playingFormatTags(serverWithoutChannels, decoded, null, null))
        assertNull(transcodedFrom(surround, null))
    }

    @Test
    fun should_trustDecoder_when_serverTranscodedOnItsOwn() {
        val decoded = AudioFormat("mp3", 320, channels = 2)
        assertEquals(listOf("MP3 320"), playingFormatTags(hiRes, decoded, null, null))
    }

    @Test
    fun should_useServerWord_when_decoderSilent() {
        assertEquals(listOf("FLAC 24/96"), playingFormatTags(hiRes, null, null, null))
    }
}
