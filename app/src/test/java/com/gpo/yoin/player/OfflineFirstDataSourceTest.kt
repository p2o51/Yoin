package com.gpo.yoin.player

import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.offline.OfflineKeys
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.SubsonicApiFactory
import com.gpo.yoin.data.remote.SubsonicStreamUrls
import com.gpo.yoin.testutil.signedWithSalt
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A downloaded song plays from the downloads whatever the stream asks; anything else streams. */
@RunWith(RobolectricTestRunner::class)
class OfflineFirstDataSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var downloads: SimpleCache

    private val stored = ByteArray(4_096) { (it % 251).toByte() }
    private val streamed = ByteArray(1_024) { 7 }

    @Before
    fun setup() {
        database = StandaloneDatabaseProvider(context)
        downloads = SimpleCache(temp.newFolder(), NoOpCacheEvictor(), database)
    }

    @After
    fun cleanup() {
        downloads.release()
        database.close()
    }

    private fun stream(songId: String = "tr-1", salt: String = "aaaa1111"): String {
        val credentials = ServerCredentials(serverUrl = "https://music.example.com", username = "qa", password = "pw")
        return signedWithSalt(SubsonicApiFactory.buildStreamUrl(credentials, songId), password = "pw", salt = salt)
    }

    /** Stores [bytes] under [key] as the downloader would, ending with its length. */
    private fun download(key: String, bytes: ByteArray) {
        val writer = CacheDataSource.Factory()
            .setCache(downloads)
            .setUpstreamDataSourceFactory { ByteArrayDataSource(bytes) }
            .createDataSource()
        writer.open(DataSpec.Builder().setUri(Uri.parse("yoin-offline://song")).setKey(key).build())
        DataSourceUtil.readToEnd(writer)
        writer.close()
    }

    private fun isComplete(key: String): Boolean {
        val length = ContentMetadata.getContentLength(downloads.getContentMetadata(key))
        return length > 0 && downloads.isCached(key, 0, length)
    }

    private fun read(url: String, opened: MutableList<String> = mutableListOf()): ByteArray {
        val source = OfflineFirstDataSource.Factory(
            downloads = downloads,
            fallback = DataSource.Factory { ByteArrayDataSource(streamed) },
            isComplete = ::isComplete,
            onOfflineOpen = { songId, _ -> opened += songId },
        ).createDataSource()
        return try {
            source.open(DataSpec(Uri.parse(url)))
            DataSourceUtil.readToEnd(source)
        } finally {
            source.close()
        }
    }

    @Test
    fun should_readDownload_when_songIsWhollyStored_whateverTheStreamAsks() {
        download(OfflineKeys.of(stream())!!, stored)
        val opened = mutableListOf<String>()
        val transcode = SubsonicStreamUrls.withTranscode(stream(salt = "bbbb2222"), 128, "mp3")

        assertArrayEquals(stored, read(transcode, opened))
        assertEquals(listOf("tr-1"), opened)
    }

    @Test
    fun should_stream_when_songIsNotDownloaded() {
        download(OfflineKeys.of(stream("tr-2"))!!, stored)
        assertArrayEquals(streamed, read(stream("tr-1")))
    }

    @Test
    fun should_seekInDownload_when_openedPastStart() {
        download(OfflineKeys.of(stream())!!, stored)
        val source = OfflineFirstDataSource(downloads, ByteArrayDataSource(streamed), ::isComplete) { _, _ -> }
        source.open(DataSpec.Builder().setUri(Uri.parse(stream())).setPosition(1_000).build())
        val rest = DataSourceUtil.readToEnd(source)
        source.close()
        assertArrayEquals(stored.copyOfRange(1_000, stored.size), rest)
        assertNull(source.uri)
    }
}
