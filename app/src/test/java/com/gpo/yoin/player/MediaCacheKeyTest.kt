package com.gpo.yoin.player

import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.SubsonicApiFactory
import com.gpo.yoin.testutil.signedWithSalt
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A song the media cache stored in one process is found by the next, whose stream url has a new salt. */
@RunWith(RobolectricTestRunner::class)
class MediaCacheKeyTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var cache: SimpleCache

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        database = StandaloneDatabaseProvider(context)
        cache = SimpleCache(temp.newFolder(), NoOpCacheEvictor(), database)
    }

    @After
    fun cleanup() {
        cache.release()
        database.close()
        server.shutdown()
    }

    private fun stream(salt: String, songId: String = "tr-1", serverUrl: String = server.url("/").toString()): String {
        val credentials = ServerCredentials(serverUrl = serverUrl, username = "qa", password = "pw")
        return signedWithSalt(SubsonicApiFactory.buildStreamUrl(credentials, songId), password = "pw", salt = salt)
    }

    private fun play(url: String): ByteArray {
        val source = mediaCacheDataSourceFactory(cache, OkHttpDataSource.Factory(OkHttpClient())).createDataSource()
        return try {
            source.open(DataSpec(Uri.parse(url)))
            DataSourceUtil.readToEnd(source)
        } finally {
            source.close()
        }
    }

    @Test
    fun should_giveOneKey_when_anotherProcessSignsTheStreamWithANewSalt() {
        val thisProcess = stream(salt = "aaaa1111", serverUrl = "https://music.example.com/navidrome")
        val nextProcess = stream(salt = "bbbb2222", serverUrl = "https://music.example.com/navidrome")
        assertNotEquals(thisProcess, nextProcess)

        val key = mediaCacheKey(DataSpec(Uri.parse(thisProcess)))
        assertEquals(key, mediaCacheKey(DataSpec(Uri.parse(nextProcess))))
        // No credential in the key the media cache's index stores.
        assertNull(key.toHttpUrl().queryParameter("t"))
        assertNull(key.toHttpUrl().queryParameter("s"))
        assertEquals("https://music.example.com/navidrome/rest/stream.view?id=tr-1&u=qa", key)
    }

    @Test
    fun should_keepMedia3sKey_when_urlIsNotASignedSubsonicStream() {
        val other = "https://cdn.example.com/audio/track.mp3"
        assertEquals(other, mediaCacheKey(DataSpec(Uri.parse(other))))
        val named = DataSpec.Builder().setUri(Uri.parse(stream(salt = "aaaa1111"))).setKey("custom").build()
        assertEquals("custom", mediaCacheKey(named))
    }

    @Test
    fun should_playTheStoredSong_when_theNextProcessIsOffline() {
        val song = ByteArray(8_192) { (it % 251).toByte() }
        val body = Buffer().write(song)
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "audio/flac").setBody(body))
        assertArrayEquals(song, play(stream(salt = "aaaa1111")))
        val nextProcess = stream(salt = "bbbb2222")

        // The next process, offline: only the stored bytes can answer its new url.
        server.shutdown()
        assertArrayEquals(song, play(nextProcess))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun should_keepSongsApart_when_songIdsDiffer() {
        val first = ByteArray(1_024) { 1 }
        val second = ByteArray(1_024) { 2 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(first)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(second)))

        assertArrayEquals(first, play(stream(salt = "aaaa1111", songId = "tr-1")))
        assertArrayEquals(second, play(stream(salt = "bbbb2222", songId = "tr-2")))
        assertEquals(2, server.requestCount)
    }
}
