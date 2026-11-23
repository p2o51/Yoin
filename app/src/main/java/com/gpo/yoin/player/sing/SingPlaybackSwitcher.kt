package com.gpo.yoin.player.sing

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.gpo.yoin.data.model.MediaId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Connects [SingController] to the local player (PlaybackService), while Sing is on.
 *
 * **Live** ([SingSource.Live], the on-device engine): nothing in the queue changes. The current song's
 * vocal track is bound to the mixer ([SingMixControl.liveBinding], with the player's period uid for
 * the song), which takes V out of the playing original once it has placed the stream on the track;
 * the moment it first does — Sing audibly cutting in — goes to [SingController.onStreamHandedOver].
 * Sing off: after the ~750 ms glide back to 1.0 the binding goes. A new song while Sing stays on plays
 * as it is until its own track is Ready, then binds. The playhead goes to the engine every half second
 * and on every seek ([SingController.onPlayhead]), so it separates where the listener is.
 *
 * **Swap** ([SingSource.LocalFile], [SingSource.Remote] — the Lyra server's quad.m4a): the current item
 * is replaced by the same item (id, metadata, extras) pointing at the Sing stream, and the player seeks
 * back to where it was; the mixer expects four channels with the stream's channel map and starts it at
 * gain 1.0. Sing off: after the glide back to 1.0, every Sing item in the queue gets its original url.
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
    private var unbindJob: Job? = null
    private var handoverJob: Job? = null
    private var handoverBinding: SingLiveBinding? = null
    private var tickJob: Job? = null
    private val mixControl get() = controller.mixControl

    fun start() {
        player.addListener(this)
        controller.onCurrentItem(player.currentMediaItem?.let(::requestFor))
        scope.launch { controller.state.collect(::apply) }
    }

    fun release() {
        tickJob?.cancel()
        handoverJob?.cancel()
        unbindJob?.cancel()
        restoreJob?.cancel()
        player.removeListener(this)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // A Sing item replacing its own original is the same song, already being sung.
        if (mediaItem?.singTag() != null && reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        controller.onCurrentItem(mediaItem?.let(::requestFor), singing = mediaItem?.singTag() != null)
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        // A seek: tell the engine at once, so it separates from the new place.
        if (controller.state.value.enabled) reportPlayhead()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (controller.state.value.enabled) reportPlayhead()
    }

    private fun apply(state: SingController.State) {
        if (!state.enabled) {
            tickJob?.cancel()
            tickJob = null
            handoverJob?.cancel()
            if (mixControl.liveBinding != null && unbindJob?.isActive != true) {
                unbindJob = scope.launch {
                    // V glides out (g to 1.0) first; unbound earlier, the mixer would fade V out itself.
                    delay(SingMixControl.CUT_OUT_GLIDE_MS.toLong() + UNBIND_MARGIN_MS)
                    if (!controller.state.value.enabled) mixControl.liveBinding = null
                }
            }
            if (restoreJob?.isActive != true && hasSingItems()) {
                restoreJob = scope.launch {
                    delay(SingMixControl.CUT_OUT_GLIDE_MS.toLong())
                    if (!controller.state.value.enabled) restoreAll()
                }
            }
            return
        }
        restoreJob?.cancel()
        unbindJob?.cancel()
        if (tickJob?.isActive != true) tickJob = scope.launch { tickPlayhead() }
        val request = state.request
        // Bound to a song Sing no longer works on (the track changed): let it go.
        mixControl.liveBinding?.let { if (it.requestKey != request?.key) mixControl.liveBinding = null }
        val status = state.status as? SingStatus.Ready ?: return
        request ?: return
        if (!isLocalOutput() || player.mediaItemCount == 0) return
        val index = player.currentMediaItemIndex
        val current = player.getMediaItemAt(index)
        val source = status.source
        if (source is SingSource.Live) {
            if (requestFor(current)?.key == request.key) bindLive(request, source)
            return
        }
        if (current.singTag()?.source == source) {
            controller.onStreamHandedOver(request.key)
            return
        }
        if (requestFor(current)?.key != request.key) return
        swapIn(index, current, source, request.key)
    }

    /** Binds [source]'s track for [request] (once), and hands over when the mixer first applies it. */
    private fun bindLive(request: SingRequest, source: SingSource.Live) {
        val bound = mixControl.liveBinding
        val binding = if (bound != null && bound.requestKey == request.key && bound.track === source.track) {
            bound
        } else {
            SingLiveBinding(source.track, request.key, currentPeriodUid()).also { mixControl.liveBinding = it }
        }
        if (binding.engaged) {
            // Back on before the old binding went: no new cut-in to wait for.
            mixControl.startAtUnity = false
            controller.onStreamHandedOver(request.key)
            return
        }
        if (handoverJob?.isActive == true && handoverBinding === binding) return
        handoverJob?.cancel()
        handoverBinding = binding
        handoverJob = scope.launch {
            val started = System.nanoTime()
            // Playing: when the mixer first takes V out (the cut-in glide starts there). Paused: once V
            // covers the Ready span at the playhead — the glide still waits for the first removal.
            // The timeout: the mixer couldn't place the stream on the track; V comes in whenever it does.
            var reason: SingReadiness.Handover? = null
            while (isActive && reason == null) {
                reason = SingReadiness.handover(
                    engaged = binding.engaged,
                    playing = player.isPlaying,
                    coveredAtPlayhead = SingReadiness.isReadyAt(binding.track, player.currentPosition),
                    elapsedMs = (System.nanoTime() - started) / 1_000_000,
                    timeoutMs = HANDOVER_TIMEOUT_MS,
                )
                if (reason == null) delay(HANDOVER_POLL_MS)
            }
            Log.d(TAG, "handover: $reason after ${(System.nanoTime() - started) / 1_000_000} ms")
            if (mixControl.liveBinding === binding) controller.onStreamHandedOver(request.key)
        }
    }

    private suspend fun tickPlayhead() {
        while (true) {
            reportPlayhead()
            delay(PLAYHEAD_TICK_MS)
        }
    }

    private fun reportPlayhead() {
        val position = player.currentPosition
        if (position == C.TIME_UNSET) return
        controller.onPlayhead(position, player.isPlaying)
    }

    /** The current item's period uid in the player's timeline, as the audio sink's flushes carry it. */
    private fun currentPeriodUid(): Any? {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null
        return runCatching { timeline.getPeriod(player.currentPeriodIndex, Timeline.Period(), true).uid }.getOrNull()
    }

    private fun swapIn(index: Int, current: MediaItem, source: SingSource, requestKey: String) {
        val uri = when (source) {
            is SingSource.LocalFile -> Uri.fromFile(source.file)
            is SingSource.Remote -> source.uri
            is SingSource.Live -> return
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

        private const val TAG = "YoinSing"
        private const val PLAYHEAD_TICK_MS = 500L
        private const val HANDOVER_POLL_MS = 30L
        private const val HANDOVER_TIMEOUT_MS = 8_000L
        private const val UNBIND_MARGIN_MS = 100L
    }
}
