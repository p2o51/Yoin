package com.gpo.yoin.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubsonicStreamUrlsTest {
    private val credentials =
        ServerCredentials(serverUrl = "https://music.example.com/nd", username = "u", password = "p")
    private val original = SubsonicApiFactory.buildStreamUrl(credentials, "song-1")

    @Test
    fun should_addMaxBitRateFormatAndLength_when_transcoding() {
        val url = SubsonicStreamUrls.withTranscode(original, 192, "mp3", estimateContentLength = true).toHttpUrl()
        assertEquals("192", url.queryParameter("maxBitRate"))
        assertEquals("mp3", url.queryParameter("format"))
        assertEquals("true", url.queryParameter("estimateContentLength"))
        assertNull(url.queryParameter("timeOffset"))
        // The signature and the song stay.
        val base = original.toHttpUrl()
        listOf("id", "u", "t", "s", "v", "c", "f").forEach { key ->
            assertEquals(key, base.queryParameter(key), url.queryParameter(key))
        }
    }

    @Test
    fun should_buildSameUrlAsFactory_when_factoryGivesBitRateAndFormat() {
        val fromFactory = SubsonicApiFactory
            .buildStreamUrl(credentials, "song-1", maxBitRate = 320, format = "mp3")
            .toHttpUrl()
        val rewritten = SubsonicStreamUrls.withTranscode(original, 320, "mp3").toHttpUrl()
        assertEquals(fromFactory.queryParameter("maxBitRate"), rewritten.queryParameter("maxBitRate"))
        assertEquals(fromFactory.queryParameter("format"), rewritten.queryParameter("format"))
    }

    @Test
    fun should_startMidSong_when_timeOffsetGiven() {
        val url = SubsonicStreamUrls.withTranscode(original, 192, "mp3", timeOffsetSeconds = 95).toHttpUrl()
        assertEquals("95", url.queryParameter("timeOffset"))
    }

    @Test
    fun should_replaceEarlierTranscode_when_rewrittenAgain() {
        val first = SubsonicStreamUrls.withTranscode(original, 320, "mp3", timeOffsetSeconds = 10)
        val second = SubsonicStreamUrls.withTranscode(first, 128, "opus").toHttpUrl()
        assertEquals(listOf("128"), second.queryParameterValues("maxBitRate"))
        assertEquals(listOf("opus"), second.queryParameterValues("format"))
        assertNull(second.queryParameter("timeOffset"))
    }

    @Test
    fun should_stripTranscode_when_originalAsked() {
        val transcoded = SubsonicStreamUrls.withTranscode(original, 192, "mp3", estimateContentLength = true)
        val back = SubsonicStreamUrls.withTranscode(transcoded, null, "opus").toHttpUrl()
        assertNull(back.queryParameter("maxBitRate"))
        assertNull(back.queryParameter("format"))
        assertNull(back.queryParameter("estimateContentLength"))
    }

    @Test
    fun should_leaveUrlAlone_when_notASubsonicStream() {
        val cover = SubsonicApiFactory.buildCoverArtUrl(credentials, "c1")
        assertEquals(cover, SubsonicStreamUrls.withTranscode(cover, 192, "mp3"))
        val elsewhere = "https://cdn.example.com/a.mp3"
        assertEquals(elsewhere, SubsonicStreamUrls.withTranscode(elsewhere, 192, "mp3"))
        assertNull(SubsonicStreamUrls.songIdOf(cover))
    }

    @Test
    fun should_readSongId_when_streamUrl() {
        assertEquals("song-1", SubsonicStreamUrls.songIdOf(original))
    }

    @Test
    fun should_askExtensionsOnSameServer_when_streamUrlGiven() {
        val url = SubsonicStreamUrls.extensionsUrl(original)!!.toHttpUrl()
        assertEquals(listOf("nd", "rest", "getOpenSubsonicExtensions.view"), url.pathSegments)
        assertNull(url.queryParameter("id"))
        assertEquals(original.toHttpUrl().queryParameter("t"), url.queryParameter("t"))
    }

    @Test
    fun should_keyServerByBase_when_streamUrlGiven() {
        assertEquals("https://music.example.com/nd", SubsonicStreamUrls.serverKey(original))
    }
}
