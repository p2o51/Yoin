package com.gpo.yoin.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import com.gpo.yoin.data.offline.OfflineKeys
import com.gpo.yoin.data.remote.SubsonicStreamUrls

/**
 * Plays a downloaded song from the download cache, with no network, and every
 * other song through [fallback] (the stream resolver, the stream cache, the
 * network). A song counts as downloaded only once all of it is stored
 * ([isComplete]): a download still running streams as before, so a stored
 * half never meets a transcode of the other half. The download key ignores the
 * streaming quality, so a song kept at 320 plays at 320 whatever Wi‑Fi or
 * mobile data would stream; [onOfflineOpen] tells the player which song opened
 * offline (and under which key), for the format it shows.
 */
@OptIn(UnstableApi::class)
internal class OfflineFirstDataSource(
    downloads: Cache,
    private val fallback: DataSource,
    private val isComplete: (key: String) -> Boolean,
    private val onOfflineOpen: (songRawId: String, key: String) -> Unit,
) : DataSource {

    private val offline: DataSource = CacheDataSource.Factory()
        .setCache(downloads)
        // Read only: nothing is written here but by the downloader, and with no
        // upstream a missing byte is an error rather than a network read.
        .setCacheWriteDataSinkFactory(null)
        .createDataSource()

    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        offline.addTransferListener(transferListener)
        fallback.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        val key = OfflineKeys.of(url)
        if (key != null && isComplete(key)) {
            SubsonicStreamUrls.songIdOf(url)?.let { onOfflineOpen(it, key) }
            current = offline
            return offline.open(dataSpec.buildUpon().setKey(key).build())
        }
        current = fallback
        return fallback.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(current) { "read before open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }

    class Factory(
        private val downloads: Cache,
        private val fallback: DataSource.Factory,
        private val isComplete: (key: String) -> Boolean,
        private val onOfflineOpen: (songRawId: String, key: String) -> Unit,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            OfflineFirstDataSource(downloads, fallback.createDataSource(), isComplete, onOfflineOpen)
    }
}
