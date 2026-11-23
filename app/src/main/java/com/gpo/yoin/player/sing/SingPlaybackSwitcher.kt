package com.gpo.yoin.player.sing

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.gpo.yoin.data.model.MediaId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Puts a song's Sing stream in the local player and takes it out again (PlaybackService). Acts only
 * while [SingController] is on — nothing turns it on yet, so today it never touches the queue.
 *
 * Swap in: the current item is replaced by the same item (id, metadata, extras) pointing at the Sing
 * stream — a cached .m4a (file://, through DefaultDataSource) or the Lyra url — and the player seeks
 * back to where it was. The mixer is told to expect four channels with the stream's channel map, and
 * starts it at gain 1.0. Swap out (Sing off): after the 250 ms glide back to 1.0, every Sing item in
 * the queue gets its original url back.
 */
@OptIn(UnstableApi::class)
class SingPlaybackSwitcher(
    private val player: ExoPlayer,
    private val controller: SingController,
    private val scope: CoroutineScope,
    private val profileId: () -> String?,
    private val isLocalOutput: () -> Boolean = { true },
) : Player.Listener {
    /** Marks a Sing item and remembers what it replaced. */
    data class SingTag(val originalUri: Uri?, val originalMimeType: String?, val source: SingSource)

    private var restoreJob: Job? = null

    fun start() {
        player.addListener(this)
        controller.onCurrentItem(player.currentMediaItem?.let(::requestFor))
        scope.launch { controller.state.collect(::apply) }
    }

    fun release() {
        player.removeListener(this)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // A Sing item replacing its own original is the same song, already being sung.
        if (mediaItem?.singTag() != null && reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        controller.onCurrentItem(mediaItem?.let(::requestFor), singing = mediaItem?.singTag() != null)
    }

    private fun apply(state: SingController.State) {
        if (!state.enabled) {
            if (restoreJob?.isActive != true && hasSingItems()) {
                restoreJob = scope.launch {
                    delay(SingMixControl.GLIDE_MS.toLong())
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
        if (current.singTag()?.source == status.source) return
        if (requestFor(current)?.key != request.key) return
        swapIn(index, current, status.source)
    }

    private fun swapIn(index: Int, current: MediaItem, source: SingSource) {
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
    }
}
