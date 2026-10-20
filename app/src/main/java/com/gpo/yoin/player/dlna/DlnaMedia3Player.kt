package com.gpo.yoin.player.dlna

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A queue-owning Media3 adapter; the renderer pulls the original HTTP stream directly. */
@UnstableApi
class DlnaMedia3Player internal constructor(
    private val transport: DlnaTransport,
    private val onFailure: (String?) -> Unit
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private data class Entry(val uid: Long, val item: MediaItem)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandsMutex = Mutex()
    private var pollJob: Job? = null
    private var device: DlnaDevice? = null
    private var entries = emptyList<Entry>()
    private var nextUid = 0L
    private var index = 0
    private var positionMs = 0L
    private var durationMs = 0L
    private var observedState = "STOPPED"
    private var desiredPlay = false
    private var prepared = false
    private var loading = false
    private var loadStartedMs = 0L
    private var failure: PlaybackException? = null
    private var pollFailures = 0
    private var repeat = Player.REPEAT_MODE_OFF
    private var shuffle = false
    private var shuffledUids = emptyList<Long>()
    private var volumeValue = 1f
    private var seekSupported = true
    private var released = false

    internal suspend fun connect(
        selected: DlnaDevice,
        items: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
        playWhenReady: Boolean
    ) = withContext(Dispatchers.Main.immediate) {
        commandsMutex.withLock {
            check(!released)
            require(items.isNotEmpty() && startIndex in items.indices)
            items.forEach { requireHttpItem(it) }
            check(device == null || device?.id == selected.id) { "Disconnect the current renderer first" }
            device = selected
            entries = items.map { Entry(nextUid++, it) }
            index = startIndex
            rebuildShuffleOrder()
            positionMs = startPositionMs.coerceAtLeast(0)
            desiredPlay = playWhenReady
            seekSupported = true
            loadCurrent()
            if (selected.renderingControl != null) {
                runCatching { transport.volume(selected) }.getOrNull()?.let { volumeValue = it / 100f }
            }
        }
        startPolling()
        invalidateState()
    }

    internal suspend fun disconnect(stopRemote: Boolean) = withContext(Dispatchers.Main.immediate) {
        commandsMutex.withLock {
            // Stop must finish before the caller switches the session back to the local player.
            if (stopRemote) device?.let { transport.stopAndConfirm(it) }
            pollJob?.cancel()
            pollJob = null
            device = null
            desiredPlay = false
            prepared = false
            loading = false
            observedState = "STOPPED"
            failure = null
            onFailure(null)
            invalidateState()
        }
    }

    internal fun hasDevice(): Boolean = device != null

    internal fun connectionFailed() {
        reportFailure()
        startPolling()
        invalidateState()
    }

    override fun getState(): State {
        val available = Player.Commands.Builder().addAll(
            Player.COMMAND_RELEASE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_VOLUME
        )
        if (device != null) {
            available.addAll(
                Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_PREPARE,
                Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_SET_SHUFFLE_MODE
            )
            if (seekSupported) {
                available.addAll(
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD
                )
            }
            if (device?.renderingControl != null) {
                available.addAll(
                    Player.COMMAND_SET_VOLUME,
                    Player.COMMAND_GET_DEVICE_VOLUME,
                    Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                    Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS
                )
            }
        }
        val playlist = entries.mapIndexed { itemIndex, entry ->
            val duration = if (itemIndex == index && durationMs > 0) durationMs else metadataDuration(entry.item)
            MediaItemData.Builder(entry.uid).setMediaItem(entry.item)
                .setDurationUs(if (duration > 0) duration * 1000 else C.TIME_UNSET)
                .setIsSeekable(seekSupported).build()
        }
        val timeline = State.Builder().setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (entries.isEmpty()) C.INDEX_UNSET else index).build().timeline
        return State.Builder().setAvailableCommands(available.build())
            .setPlaylist(
                DlnaTimeline(timeline, shuffledIndices()),
                Tracks.EMPTY,
                entries.getOrNull(index)?.item?.mediaMetadata ?: MediaMetadata.EMPTY
            )
            .setCurrentMediaItemIndex(if (entries.isEmpty()) C.INDEX_UNSET else index)
            .setPlaybackState(
                when {
                    failure != null || !prepared || entries.isEmpty() -> Player.STATE_IDLE
                    loading || observedState == "TRANSITIONING" -> Player.STATE_BUFFERING
                    observedState == "NO_MEDIA_PRESENT" -> Player.STATE_ENDED
                    observedState == "STOPPED" && durationMs > 0 && positionMs >= durationMs - 1000 ->
                        Player.STATE_ENDED
                    else -> Player.STATE_READY
                }
            )
            .setPlayerError(failure)
            // PLAYING comes only from a renderer observation, never a successful SOAP Play alone.
            .setPlayWhenReady(
                failure == null && (observedState == "PLAYING" || loading && desiredPlay),
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            )
            .setContentPositionMs(positionMs.coerceAtLeast(0))
            .setContentBufferedPositionMs { positionMs.coerceAtLeast(0) }
            .setDeviceInfo(
                DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(100).build()
            )
            .setDeviceVolume((volumeValue * 100).toInt())
            .setRepeatMode(repeat).setShuffleModeEnabled(shuffle).setVolume(volumeValue).build()
    }

    override fun handleSetMediaItems(
        items: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        items.forEach(::requireHttpItem)
        return command {
            if (prepared) device?.let { transport.stop(it) }
            entries = items.map { Entry(nextUid++, it) }
            index = startIndex.takeIf { it in items.indices } ?: 0
            rebuildShuffleOrder()
            positionMs = startPositionMs.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
            durationMs = 0
            prepared = false
            loading = false
            observedState = "STOPPED"
            clearFailure()
        }
    }

    override fun handlePrepare(): ListenableFuture<*> = command {
        if (entries.isNotEmpty() && !prepared) loadCurrent()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = command {
        desiredPlay = playWhenReady
        if (entries.isEmpty()) return@command
        val selected = requireNotNull(device)
        if (!prepared || failure != null) {
            loadCurrent()
        } else {
            if (playWhenReady) transport.play(selected) else transport.pause(selected)
            observe()
        }
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = command {
        require(mediaItemIndex in entries.indices)
        val target = positionMs.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        if (mediaItemIndex != index || !prepared) {
            index = mediaItemIndex
            this.positionMs = target
            loadCurrent()
        } else {
            seek(target)
            this.positionMs = target
            observe()
        }
    }

    override fun handleStop(): ListenableFuture<*> = command {
        device?.let { transport.stopAndConfirm(it) }
        desiredPlay = false
        prepared = false
        loading = false
        observedState = "STOPPED"
        clearFailure()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        repeat = repeatMode
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (shuffleModeEnabled && !shuffle) rebuildShuffleOrder()
        shuffle = shuffleModeEnabled
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> = command {
        val selected = requireNotNull(device)
        transport.setVolume(selected, (volume.coerceIn(0f, 1f) * 100).toInt())
        volumeValue = transport.volume(selected) / 100f
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> =
        handleSetVolume(deviceVolume.coerceIn(0, 100) / 100f)

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> = handleSetVolume(volumeValue + 0.01f)

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> = handleSetVolume(volumeValue - 0.01f)

    override fun handleAddMediaItems(index: Int, items: List<MediaItem>): ListenableFuture<*> {
        items.forEach(::requireHttpItem)
        return command {
            val at = index.coerceIn(0, entries.size)
            val added = items.map { Entry(nextUid++, it) }
            val currentUid = entries.getOrNull(this.index)?.uid
            val playNext = at == this.index + 1
            entries = entries.toMutableList().apply { addAll(at, added) }
            if (at <= this.index && entries.size > items.size) this.index += items.size
            val order = shuffledUids.toMutableList()
            val afterCurrent = order.indexOf(currentUid) + 1
            if (playNext && afterCurrent > 0) {
                order.addAll(afterCurrent, added.map(Entry::uid))
            } else {
                order.addAll(added.map(Entry::uid).shuffled())
            }
            shuffledUids = order
        }
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> = command {
        val currentUid = entries.getOrNull(index)?.uid
        val moved = entries.subList(fromIndex, toIndex).toList()
        entries = entries.toMutableList().apply {
            subList(fromIndex, toIndex).clear()
            addAll(newIndex.coerceIn(0, size), moved)
        }
        index = entries.indexOfFirst { it.uid == currentUid }.coerceAtLeast(0)
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> = command {
        val currentUid = entries.getOrNull(index)?.uid
        val oldOrder = if (shuffle) shuffledUids else entries.map(Entry::uid)
        val orderPosition = oldOrder.indexOf(currentUid)
        entries = entries.toMutableList().apply { subList(fromIndex, toIndex).clear() }
        val remaining = entries.mapTo(hashSetOf(), Entry::uid)
        shuffledUids = shuffledUids.filter { it in remaining }
        val preserved = entries.indexOfFirst { it.uid == currentUid }
        val successor = oldOrder.drop(orderPosition + 1).firstOrNull { it in remaining }
        index = preserved.takeIf { it >= 0 }
            ?: entries.indexOfFirst { it.uid == successor }.takeIf { it >= 0 }
            ?: fromIndex.coerceAtMost((entries.size - 1).coerceAtLeast(0))
        if (preserved < 0) {
            positionMs = 0
            if (entries.isEmpty()) {
                device?.let { transport.stop(it) }
                prepared = false
                desiredPlay = false
                loading = false
                observedState = "STOPPED"
            } else if (prepared) {
                loadCurrent()
            }
        }
    }

    override fun handleRelease(): ListenableFuture<*> {
        // Releasing a session must not implicitly issue an unconfirmed remote stop or resume local audio.
        released = true
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    private suspend fun loadCurrent() {
        val selected = requireNotNull(device)
        val item = entries.getOrNull(index)?.item ?: return
        loading = true
        prepared = true
        loadStartedMs = SystemClock.elapsedRealtime()
        observedState = "STOPPED"
        durationMs = metadataDuration(item)
        clearFailure()
        invalidateState()
        val metadata = item.mediaMetadata
        transport.setUri(
            selected,
            DlnaMetadata(
                id = item.mediaId,
                title = metadata.title?.toString().orEmpty(),
                artist = metadata.artist?.toString(),
                album = metadata.albumTitle?.toString(),
                artwork = metadata.artworkUri?.toString(),
                uri = requireNotNull(item.localConfiguration).uri.toString(),
                mimeType = item.localConfiguration?.mimeType,
                durationMs = durationMs.takeIf { it > 0 }
            )
        )
        if (positionMs > 0) seek(positionMs)
        if (desiredPlay) transport.play(selected)
        observe()
    }

    private suspend fun seek(target: Long) {
        try {
            transport.seek(requireNotNull(device), target)
        } catch (error: DlnaCommandException) {
            if (error.code == 710 || error.code == 711) seekSupported = false
            throw error
        }
    }

    private suspend fun observe() {
        val selected = device ?: return
        val info = transport.transportInfo(selected)
        if (info.status == "ERROR_OCCURRED") throw IOException("Renderer cannot play this stream")
        val position = transport.position(selected)
        val expectedUri = entries.getOrNull(index)?.item?.localConfiguration?.uri?.toString()
        if (!position.trackUri.isNullOrBlank() && expectedUri != null && position.trackUri != expectedUri) {
            prepared = false
            desiredPlay = false
            loading = false
            throw IOException("Playback changed on the renderer")
        }
        val priorPosition = positionMs
        val priorState = observedState
        position.durationMs?.takeIf { it > 0 }?.let { durationMs = it }
        position.positionMs?.let { positionMs = it }
        observedState = info.state
        if (info.state == "PLAYING" || info.state == "PAUSED_PLAYBACK" || !desiredPlay) loading = false
        if (loading && SystemClock.elapsedRealtime() - loadStartedMs > 20_000) {
            throw IOException("Renderer did not start playback")
        }
        if (DlnaQueueEnd.reached(priorState, info.state, priorPosition, positionMs, durationMs, desiredPlay)) {
            val next = when {
                repeat == Player.REPEAT_MODE_ONE -> index
                else -> nextIndex()
            }
            if (next != null) {
                index = next
                positionMs = 0
                loadCurrent()
            } else {
                desiredPlay = false
                loading = false
                positionMs = durationMs
            }
        } else if (info.state == "STOPPED" && !loading) {
            // An external Stop at the middle of a song is not a request for the next track.
            desiredPlay = false
        }
        pollFailures = 0
        clearFailure()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (device != null) {
                delay(1000)
                commandsMutex.withLock {
                    if (prepared) {
                        try {
                            observe()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            pollFailures++
                            if (pollFailures >= 3) reportFailure()
                        }
                        invalidateState()
                    }
                }
            }
        }
    }

    private fun command(operation: suspend () -> Unit): ListenableFuture<*> {
        val future = SettableFuture.create<Void>()
        scope.launch {
            commandsMutex.withLock {
                try {
                    check(!released)
                    operation()
                    future.set(null)
                } catch (cancelled: CancellationException) {
                    future.cancel(false)
                    throw cancelled
                } catch (_: Exception) {
                    reportFailure()
                    future.setException(DlnaCommandException("Playback"))
                } finally {
                    invalidateState()
                }
            }
        }
        return future
    }

    private fun reportFailure() {
        loading = false
        failure = PlaybackException(
            "Cannot control the speaker. Check its connection and stream access.",
            null,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        )
        onFailure(failure?.message)
    }

    private fun clearFailure() {
        failure = null
        onFailure(null)
    }

    private fun requireHttpItem(item: MediaItem) {
        require(DlnaProtocol.httpUri(item.localConfiguration?.uri?.toString().orEmpty()) != null) {
            "DLNA requires an HTTP stream"
        }
    }

    private fun metadataDuration(item: MediaItem): Long =
        item.mediaMetadata.extras?.getInt("duration_secs", 0)?.toLong()?.times(1000) ?: 0

    private fun rebuildShuffleOrder() {
        val current = entries.getOrNull(index)?.uid
        shuffledUids = listOfNotNull(current) + entries.map(Entry::uid).filter { it != current }.shuffled()
    }

    private fun shuffledIndices(): List<Int> {
        val indices = entries.mapIndexed { i, entry -> entry.uid to i }.toMap()
        return shuffledUids.mapNotNull(indices::get)
    }

    private fun nextIndex(): Int? {
        val order = if (shuffle) shuffledIndices() else entries.indices.toList()
        val cursor = order.indexOf(index)
        return order.getOrNull(cursor + 1) ?: if (repeat == Player.REPEAT_MODE_ALL) order.firstOrNull() else null
    }
}

/** Media3 controls, queue previews and natural completion all use this same stable permutation. */
@UnstableApi
private class DlnaTimeline(private val delegate: Timeline, private val order: List<Int>) : Timeline() {
    override fun getWindowCount(): Int = delegate.windowCount
    override fun getPeriodCount(): Int = delegate.periodCount
    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
        delegate.getWindow(windowIndex, window, defaultPositionProjectionUs)
    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
        delegate.getPeriod(periodIndex, period, setIds)
    override fun getIndexOfPeriod(uid: Any): Int = delegate.getIndexOfPeriod(uid)
    override fun getUidOfPeriod(periodIndex: Int): Any = delegate.getUidOfPeriod(periodIndex)
    override fun getFirstWindowIndex(shuffleModeEnabled: Boolean): Int =
        if (shuffleModeEnabled) order.firstOrNull() ?: C.INDEX_UNSET else delegate.getFirstWindowIndex(false)
    override fun getLastWindowIndex(shuffleModeEnabled: Boolean): Int =
        if (shuffleModeEnabled) order.lastOrNull() ?: C.INDEX_UNSET else delegate.getLastWindowIndex(false)

    override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int {
        if (!shuffleModeEnabled) return delegate.getNextWindowIndex(windowIndex, repeatMode, false)
        if (repeatMode == Player.REPEAT_MODE_ONE) return windowIndex
        return order.getOrNull(order.indexOf(windowIndex) + 1)
            ?: if (repeatMode == Player.REPEAT_MODE_ALL) getFirstWindowIndex(true) else C.INDEX_UNSET
    }

    override fun getPreviousWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int {
        if (!shuffleModeEnabled) return delegate.getPreviousWindowIndex(windowIndex, repeatMode, false)
        if (repeatMode == Player.REPEAT_MODE_ONE) return windowIndex
        return order.getOrNull(order.indexOf(windowIndex) - 1)
            ?: if (repeatMode == Player.REPEAT_MODE_ALL) getLastWindowIndex(true) else C.INDEX_UNSET
    }
}

/** Conservative completion: an arbitrary external Stop must never silently advance Yoin's queue. */
internal object DlnaQueueEnd {
    fun reached(
        previous: String,
        current: String,
        oldPositionMs: Long,
        positionMs: Long,
        durationMs: Long,
        wantsPlay: Boolean
    ): Boolean = wantsPlay && previous == "PLAYING" && current == "STOPPED" && durationMs > 0 &&
        max(oldPositionMs, positionMs) >= (durationMs - 2000).coerceAtLeast(0)
}
