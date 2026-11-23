package com.gpo.yoin.player.sing

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import com.gpo.yoin.data.model.MediaId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Puts a song's Sing stream in the local player and takes it out again (PlaybackService).
 *
 * Swap in: for a remote Lyra `quad.m4a`, prefetches the faststart header + a few seconds around the
 * current playhead into ExoPlayer's stream cache on [ioDispatcher] while the original stereo track
 * keeps playing uninterrupted (button keeps spinning quietly). Once buffered, swaps the current item
 * in at the exact live playhead at gain 1.0 (inaudible cut-in), signals [SingController.onStreamHandedOver]
 * so the UI capsule expands in sync, and the mixer glides smoothly along [SingRampCurve.EaseOutCubic].
 *
 * Swap out (Sing off): waits for the ~750 ms ease-in-out glide back to 1.0 before restoring every Sing
 * item in the queue to its original stereo URL.
 */
@OptIn(UnstableApi::class)
class SingPlaybackSwitcher(
    private val player: ExoPlayer,
    private val controller: SingController,
    private val scope: CoroutineScope,
    private val profileId: () -> String?,
    private val isLocalOutput: () -> Boolean = { true },
    private val prebufferDataSourceFactory: DataSource.Factory? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Player.Listener {
    /** Marks a Sing item and remembers what it replaced. */
    data class SingTag(val originalUri: Uri?, val originalMimeType: String?, val source: SingSource)

    private var restoreJob: Job? = null
    private var swapJob: Job? = null
    private var pendingSwapKey: String? = null

    fun start() {
        player.addListener(this)
        controller.onCurrentItem(player.currentMediaItem?.let(::requestFor))
        scope.launch { controller.state.collect(::apply) }
    }

    fun release() {
        swapJob?.cancel()
        swapJob = null
        restoreJob?.cancel()
        restoreJob = null
        player.removeListener(this)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // A Sing item replacing its own original is the same song, already being sung.
        if (mediaItem?.singTag() != null && reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        controller.onCurrentItem(mediaItem?.let(::requestFor), singing = mediaItem?.singTag() != null)
    }

    private fun apply(state: SingController.State) {
        if (!state.enabled) {
            swapJob?.cancel()
            swapJob = null
            pendingSwapKey = null
            if (restoreJob?.isActive != true && hasSingItems()) {
                restoreJob = scope.launch {
                    delay(SingMixControl.CUT_OUT_GLIDE_MS.toLong())
                    if (!controller.state.value.enabled) restoreAll()
                }
            }
            return
        }
        restoreJob?.cancel()
        val status = state.status as? SingStatus.Ready ?: return
        val request = state.request ?: return
        if (!isLocalOutput() || player.mediaItemCount == 0) return
        val index = player.currentMediaItemIndex
        val current = player.getMediaItemAt(index)
        if (current.singTag()?.source == status.source) {
            controller.onStreamHandedOver(request.key)
            return
        }
        if (requestFor(current)?.key != request.key) return

        val swapKey = "${request.key}:${status.source}"
        if (pendingSwapKey == swapKey && swapJob?.isActive == true) return
        swapJob?.cancel()
        pendingSwapKey = swapKey

        val source = status.source
        if (source is SingSource.Remote && prebufferDataSourceFactory != null) {
            val initialPosMs = player.currentPosition.coerceAtLeast(0L)
            swapJob = scope.launch {
                runCatching {
                    prebufferRemoteQuad(prebufferDataSourceFactory, source.uri, initialPosMs)
                }
                pendingSwapKey = null
                val latest = controller.state.value
                if (!latest.enabled || latest.request?.key != request.key) return@launch
                if (!isLocalOutput() || player.mediaItemCount == 0) return@launch
                val liveIndex = player.currentMediaItemIndex
                val liveItem = player.getMediaItemAt(liveIndex)
                if (requestFor(liveItem)?.key != request.key) return@launch
                swapIn(liveIndex, liveItem, source, request.key)
            }
        } else {
            pendingSwapKey = null
            swapIn(index, current, source, request.key)
        }
    }

    private suspend fun prebufferRemoteQuad(
        factory: DataSource.Factory,
        uri: Uri,
        positionMs: Long,
    ) = withContext(ioDispatcher) {
        // 1. Warm the faststart moov header + opening frames (64 KB).
        prefetchRange(factory, uri, offset = 0L, length = PREBUFFER_HEADER_BYTES)
        // 2. Warm ~6 seconds around the live playhead (256 kbps 4ch AAC ≈ 32 KB/s).
        val approxOffset = ((positionMs / 1000L) * APPROX_QUAD_BYTES_PER_SEC)
            .coerceAtLeast(PREBUFFER_HEADER_BYTES)
        prefetchRange(factory, uri, offset = approxOffset, length = PREBUFFER_WINDOW_BYTES)
    }

    private fun prefetchRange(factory: DataSource.Factory, uri: Uri, offset: Long, length: Long) {
        val dataSource = factory.createDataSource()
        val buf = ByteArray(16 * 1024)
        try {
            dataSource.open(DataSpec(uri, offset, length))
            var remaining = length
            while (remaining > 0L) {
                val read = dataSource.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (read == C.RESULT_END_OF_INPUT || read <= 0) break
                remaining -= read
            }
        } finally {
            runCatching { dataSource.close() }
        }
    }

    private fun swapIn(index: Int, current: MediaItem, source: SingSource, requestKey: String) {
        val uri = when (source) {
            is SingSource.LocalFile -> Uri.fromFile(source.file)
            is SingSource.Remote -> source.uri
        }
        val original = current.singTag()
        val item = current.buildUpon()
            .setUri(uri)
            .setMimeType(SingSource.MIME_TYPE)
            .setTag(
                SingTag(
                    originalUri = original?.originalUri ?: current.localConfiguration?.uri,
                    originalMimeType = original?.originalMimeType ?: current.localConfiguration?.mimeType,
                    source = source,
                ),
            )
            .build()
        val position = player.currentPosition
        controller.mixControl.channelMap = source.channelMap
        controller.mixControl.expectQuad = true
        player.replaceMediaItem(index, item)
        player.seekTo(index, position)
        controller.onStreamHandedOver(requestKey)
    }

    private fun restoreAll() {
        val position = player.currentPosition
        val currentIndex = player.currentMediaItemIndex
        var touchedCurrent = false
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            val tag = item.singTag() ?: continue
            player.replaceMediaItem(
                i,
                item.buildUpon().setUri(tag.originalUri).setMimeType(tag.originalMimeType).setTag(null).build(),
            )
            if (i == currentIndex) touchedCurrent = true
        }
        if (touchedCurrent) player.seekTo(currentIndex, position)
        controller.mixControl.expectQuad = false
    }

    private fun hasSingItems(): Boolean = (0 until player.mediaItemCount).any { player.getMediaItemAt(it).singTag() != null }

    private fun requestFor(item: MediaItem): SingRequest? {
        val id = MediaId.parseOrNull(item.mediaMetadata.extras?.getString(EXTRA_MEDIA_ID) ?: item.mediaId) ?: return null
        val profile = profileId() ?: return null
        val position = if (player.currentMediaItem?.mediaId == item.mediaId) player.currentPosition else 0L
        return SingRequest(
            songId = id,
            profileId = profile,
            streamUri = (item.singTag()?.originalUri ?: item.localConfiguration?.uri)?.toString(),
            positionMs = position.takeIf { it != C.TIME_UNSET } ?: 0L,
        )
    }

    private fun MediaItem.singTag(): SingTag? = localConfiguration?.tag as? SingTag

    /** The current song's request, for whoever turns Sing on. */
    fun currentRequest(): SingRequest? = player.currentMediaItem?.let(::requestFor)

    companion object {
        /** PlaybackManager's extras key for the neutral MediaId string. */
        const val EXTRA_MEDIA_ID = "media_id"

        private const val PREBUFFER_HEADER_BYTES = 64L * 1024L
        private const val PREBUFFER_WINDOW_BYTES = 224L * 1024L
        private const val APPROX_QUAD_BYTES_PER_SEC = 32L * 1024L
    }
}
