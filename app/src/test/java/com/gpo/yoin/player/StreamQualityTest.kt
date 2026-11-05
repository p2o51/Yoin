package com.gpo.yoin.player

import android.media.AudioDeviceInfo
import android.net.Uri
import androidx.media3.datasource.DataSpec
import com.gpo.yoin.data.remote.OpenSubsonicExtension
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.SubsonicApiFactory
import com.gpo.yoin.data.remote.SubsonicResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StreamQualityTest {
    private val credentials = ServerCredentials(serverUrl = "https://music.example.com", username = "u", password = "p")
    private val navidrome = SubsonicServerTraits(transcodeFormat = "mp3", timeOffset = true)
    private val settings = StreamQualitySettings(wifi = StreamQuality.Original, mobile = StreamQuality.Kbps192)

    private fun spec(songId: String, position: Long = 0L) =
        DataSpec.Builder()
            .setUri(Uri.parse(SubsonicApiFactory.buildStreamUrl(credentials, songId)))
            .setPosition(position)
            .build()

    @Test
    fun should_useWifiChoice_when_networkUnmetered() {
        assertEquals(StreamQuality.Original, settings.forNetwork(unmetered = true))
    }

    @Test
    fun should_useMobileChoice_when_networkMetered() {
        // A phone's hotspot is metered too.
        assertEquals(StreamQuality.Kbps192, settings.forNetwork(unmetered = false))
    }

    @Test
    fun should_streamFileOnWifiAndFollowOutputOnMobile_when_nothingChosen() {
        val defaults = StreamQualitySettings()
        assertEquals(StreamQuality.Original, defaults.forNetwork(unmetered = true))
        assertEquals(StreamQuality.Auto, defaults.forNetwork(unmetered = false))
    }

    @Test
    fun should_followOutput_when_qualityAuto() {
        assertEquals(StreamQuality.Original, StreamQuality.Auto.forOutput(AudioOutputKind.Wired))
        assertEquals(StreamQuality.Kbps320, StreamQuality.Auto.forOutput(AudioOutputKind.Bluetooth))
        assertEquals(StreamQuality.Kbps192, StreamQuality.Auto.forOutput(AudioOutputKind.Speaker))
    }

    @Test
    fun should_keepFixedChoice_when_qualityNotAuto() {
        assertEquals(StreamQuality.Kbps128, StreamQuality.Kbps128.forOutput(AudioOutputKind.Wired))
    }

    @Test
    fun should_preferWired_when_wiredAndBluetoothConnected() {
        val both = listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        assertEquals(AudioOutputKind.Wired, outputKindOf(both))
    }

    @Test
    fun should_readBluetooth_when_onlyBluetoothHeadphones() {
        val types = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLE_HEADSET)
        assertEquals(AudioOutputKind.Bluetooth, outputKindOf(types))
    }

    @Test
    fun should_readSpeaker_when_onlySpeakerOrDock() {
        val types = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_DOCK)
        assertEquals(AudioOutputKind.Speaker, outputKindOf(types))
    }

    @Test
    fun should_transcodeAt320_when_autoOnBluetoothOverMobile() {
        val auto = StreamQualitySettings(wifi = StreamQuality.Original, mobile = StreamQuality.Auto)
        val resolver = SubsonicStreamResolver(
            settings = { auto },
            isUnmetered = { false },
            traitsFor = { navidrome },
            outputKind = { AudioOutputKind.Bluetooth },
        )
        val url = resolver.resolveDataSpec(spec("s1")).uri.toString().toHttpUrl()
        assertEquals("320", url.queryParameter("maxBitRate"))
    }

    @Test
    fun should_planOriginal_when_qualityOriginal() {
        assertFalse(StreamDelivery.plan(StreamQuality.Original, 900) { navidrome }.transcoded)
    }

    @Test
    fun should_planServersFormat_when_qualityHasBitRate() {
        assertEquals(StreamDelivery(128, "mp3"), StreamDelivery.plan(StreamQuality.Kbps128, 900) { navidrome })
        assertEquals(
            StreamDelivery(320, "mp3"),
            StreamDelivery.plan(StreamQuality.Kbps320, null) { SubsonicServerTraits.Plain },
        )
    }

    @Test
    fun should_playFile_when_fileAlreadyUnderCap() {
        assertEquals(StreamDelivery.Original, StreamDelivery.plan(StreamQuality.Kbps320, 320) { navidrome })
    }

    @Test
    fun should_notAskServer_when_qualityOriginal() {
        var asked = 0
        val resolver = SubsonicStreamResolver({ settings }, { true }, { asked++; navidrome })
        resolver.resolveDataSpec(spec("s1"))
        assertEquals(0, asked)
    }

    @Test
    fun should_askMp3WithOffset_when_navidromeHasTranscodeOffset() {
        // Constant bit rate, so a transcode seeks with one timeOffset request.
        val traits = SubsonicServerTraits.from(
            SubsonicResponseBody(
                status = "ok",
                type = "navidrome",
                openSubsonicExtensions = listOf(OpenSubsonicExtension("transcodeOffset", listOf(1))),
            ),
        )
        assertEquals(SubsonicServerTraits("mp3", timeOffset = true), traits)
    }

    @Test
    fun should_notOffset_when_serverListsNoTranscodeOffset() {
        val traits = SubsonicServerTraits.from(SubsonicResponseBody(status = "ok", type = "gonic"))
        assertEquals(SubsonicServerTraits("mp3", timeOffset = false), traits)
    }

    @Test
    fun should_returnSpecUnchanged_when_originalOnWifi() {
        val resolver = SubsonicStreamResolver({ settings }, { true }, { navidrome })
        val spec = spec("s1")
        assertSame(spec, resolver.resolveDataSpec(spec))
    }

    @Test
    fun should_rewriteToTranscode_when_onMobileData() {
        val resolver = SubsonicStreamResolver({ settings }, { false }, { navidrome }, { 1_000 })
        val url = resolver.resolveDataSpec(spec("s1")).uri.toString().toHttpUrl()
        assertEquals("192", url.queryParameter("maxBitRate"))
        assertEquals("mp3", url.queryParameter("format"))
        assertEquals("true", url.queryParameter("estimateContentLength"))
    }

    @Test
    fun should_notAskForEstimatedLength_when_serverListsNoBitRate() {
        // Navidrome estimates from the listed bit rate; for an MP4 it can't read it lists 0.
        val resolver = SubsonicStreamResolver({ settings }, { false }, { navidrome }, { null })
        val url = resolver.resolveDataSpec(spec("s1")).uri.toString().toHttpUrl()
        assertEquals("192", url.queryParameter("maxBitRate"))
        assertNull(url.queryParameter("estimateContentLength"))
    }

    @Test
    fun should_keepSongsStream_when_networkChangesMidSong() {
        var unmetered = false
        val resolver = SubsonicStreamResolver({ settings }, { unmetered }, { navidrome })
        resolver.resolveDataSpec(spec("s1"))
        unmetered = true
        // A seek reopens the playing song: it stays on its transcode.
        val reopened = resolver.resolveDataSpec(spec("s1", position = 4_000L)).uri.toString().toHttpUrl()
        assertEquals("192", reopened.queryParameter("maxBitRate"))
        // The next song opens on the new network.
        assertNull(resolver.resolveDataSpec(spec("s2")).uri.toString().toHttpUrl().queryParameter("maxBitRate"))
    }

    @Test
    fun should_chooseAgain_when_songNoLongerKept() {
        var unmetered = false
        val resolver = SubsonicStreamResolver({ settings }, { unmetered }, { navidrome })
        resolver.resolveDataSpec(spec("s1"))
        unmetered = true
        resolver.retainOnly(setOf("s2"))
        assertNull(resolver.deliveryFor("s1"))
        assertFalse(resolver.resolveDataSpec(spec("s1")).uri.toString().contains("maxBitRate"))
    }

    @Test
    fun should_startServerAtSecond_when_mp3TranscodeReopensMidStream() {
        val mp3 = SubsonicServerTraits(transcodeFormat = "mp3", timeOffset = true)
        val resolver = SubsonicStreamResolver({ settings }, { false }, { mp3 }, { 1_000 })
        // 192 kbps = 24 000 bytes a second: byte 2 400 000 is 100 s in.
        val resolved = resolver.resolveDataSpec(spec("s1", position = 2_400_000L))
        assertEquals("100", resolved.uri.toString().toHttpUrl().queryParameter("timeOffset"))
        assertEquals(0L, resolved.position)
    }

    @Test
    fun should_keepBytePosition_when_fileBitRateUnknown() {
        // The server may send less than the cap: a byte is no time then.
        val resolver = SubsonicStreamResolver({ settings }, { false }, { navidrome })
        val resolved = resolver.resolveDataSpec(spec("s1", position = 2_400_000L))
        assertNull(resolved.uri.toString().toHttpUrl().queryParameter("timeOffset"))
        assertEquals(2_400_000L, resolved.position)
    }

    @Test
    fun should_notOffset_when_serverLacksTranscodeOffset() {
        val plain = SubsonicServerTraits(transcodeFormat = "mp3", timeOffset = false)
        val resolver = SubsonicStreamResolver({ settings }, { false }, { plain })
        val resolved = resolver.resolveDataSpec(spec("s1", position = 2_400_000L))
        assertNull(resolved.uri.toString().toHttpUrl().queryParameter("timeOffset"))
        assertEquals(2_400_000L, resolved.position)
    }

    @Test
    fun should_leaveOtherUrlsAlone_when_notSubsonicStream() {
        val resolver = SubsonicStreamResolver({ settings }, { false }, { navidrome })
        val spec = DataSpec(Uri.parse("https://cdn.example.com/a.mp3"))
        assertSame(spec, resolver.resolveDataSpec(spec))
    }

    @Test
    fun should_computeWholeSeconds_when_convertingBytePosition() {
        assertEquals(0, SubsonicStreamResolver.timeOffsetSeconds(0L, 192))
        assertEquals(0, SubsonicStreamResolver.timeOffsetSeconds(23_999L, 192))
        assertEquals(1, SubsonicStreamResolver.timeOffsetSeconds(24_000L, 192))
        assertTrue(SubsonicStreamResolver.timeOffsetSeconds(40_000_000L, 320) == 1000)
    }
}
