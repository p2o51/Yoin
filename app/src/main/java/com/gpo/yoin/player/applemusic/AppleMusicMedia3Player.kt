package com.gpo.yoin.player.applemusic

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.apple.android.music.playback.controller.MediaPlayerController
import com.apple.android.music.playback.controller.MediaPlayerControllerFactory
import com.apple.android.music.playback.model.MediaItemType
import com.apple.android.music.playback.model.MediaPlayerException
import com.apple.android.music.playback.model.PlaybackState as ApplePlaybackState
import com.apple.android.music.playback.model.PlayerQueueItem
import com.apple.android.music.playback.queue.CatalogPlaybackQueueItemProvider
import com.apple.android.music.playback.queue.PlaybackQueueInsertionType
import com.apple.android.sdk.authentication.TokenProvider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.gpo.yoin.BuildConfig
import com.gpo.yoin.R
import com.gpo.yoin.data.remote.applemusic.developerTokenExpiry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.bytedeco.javacpp.Pointer

/**
 * MusicKit owns DRM/audio. Media3 exposes only observed state and supported commands.
 *
 * [refreshDeveloperToken] returns a developer token with at least five minutes left; see [MusicKitDeveloperToken].
 */
@UnstableApi
class AppleMusicMedia3Player(
    context: Context,
    developerToken: String,
    userToken: String,
    refreshDeveloperToken: suspend () -> String = { developerToken },
    private val onObservation: (AppleMusicPlaybackObservation) -> Unit
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    init {
        // Required by Apple's SDK sample; the Java factory does not load JNI itself.
        System.loadLibrary("c++_shared")
        System.loadLibrary("appleMusicSDK")
        if (BuildConfig.DEBUG) {
            Log.i(
                "YoinMusicKitMemory",
                "heapMax=${Runtime.getRuntime().maxMemory()} " +
                    "maxPhysicalBytes=${Pointer.maxPhysicalBytes()} maxBytes=${Pointer.maxBytes()} " +
                    "physicalBytes=${Pointer.physicalBytes()} trackedBytes=${Pointer.totalBytes()}"
            )
        }
    }
    private val controller = MediaPlayerControllerFactory.createLocalController(
        appContext,
        object : TokenProvider {
            // Read once, here: later tokens reach MusicKit through musicKitToken.
            override fun getDeveloperToken() = developerToken
            override fun getUserToken() = userToken
        }
    )
    private val scope = MainScope()

    /** Every MusicKit call that sends it to Apple for a song goes through [MusicKitDeveloperToken.run]. */
    private val musicKitToken = MusicKitDeveloperToken(
        installed = developerToken,
        scope = scope,
        fetch = {
            try {
                refreshDeveloperToken()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w("YoinMusicKitToken", "developer token refresh failed: ${e.javaClass.simpleName}")
                throw e
            }
        },
        install = { token ->
            installMusicKitDeveloperToken(appContext, token)
            logDeveloperTokenExpiry("renewed", token)
        }
    ).also { logDeveloperTokenExpiry("created", developerToken) }

    /** The expiry only, never the token. */
    private fun logDeveloperTokenExpiry(what: String, token: String) {
        val left = developerTokenExpiry(token)?.minus(System.currentTimeMillis() / 1000)
        Log.i("YoinMusicKitToken", "developer token $what, expires in ${left}s")
    }
    private val playModes = MusicKitPlayModes(controller)
    private var requestedItems: List<MediaItem> = emptyList()
    private var requestedIndex = 0
    private var desiredPlay = false
    private var queuePrepared = false
    private var preparing = false
    private var failure: String? = null
    private var released = false
    private var published: PlayOrder? = null
    private val playRequest = MusicKitPlayRequest()
    private val stallWatch = MusicKitStallWatch()
    private val endGate = MusicKitEndGate()
    private val startPosition = MusicKitStartPosition()
    private var requestedStartPositionMs = 0L
    private var prepareIssuedEpoch: Long? = null
    private var requestedRepeatMode: Int? = null
    private var observationSequence = 0L
    private var seekingSinceMs: Long? = null

    /** Debug only: the next prepared queue is still to be compared with the request ([logPreparedStart]). */
    private var preparedStartUnchecked = false
    private val listener = object : MediaPlayerController.Listener {
        override fun onPlayerStateRestored(c: MediaPlayerController) {
            if (requestedItems.isEmpty()) c.stop()
            publish()
        }
        override fun onPlaybackStateChanged(c: MediaPlayerController, old: Int, new: Int) {
            publish()
        }
        override fun onPlaybackStateUpdated(c: MediaPlayerController) = publish()
        override fun onBufferingStateChanged(c: MediaPlayerController, buffering: Boolean) = publish()
        override fun onCurrentItemChanged(c: MediaPlayerController, old: PlayerQueueItem?, new: PlayerQueueItem?) =
            publish()
        override fun onItemEnded(c: MediaPlayerController, item: PlayerQueueItem, end: Long) {
            if (BuildConfig.DEBUG) {
                Log.i(
                    "YoinPlaylistProbe",
                    org.json.JSONObject()
                        .put("kind", "apple_item_ended").put("elapsed", SystemClock.elapsedRealtime())
                        .put("item", item.item.subscriptionStoreId).put("end", end)
                        .put("position", c.currentPosition).put("state", c.playbackState).toString()
                )
            }
            val identity = item.identity()
            onPlayerThread {
                endGate.onItemEnded(
                    item = identity,
                    endPositionMs = end,
                    nowMs = SystemClock.elapsedRealtime(),
                    permitted = completionPermitted()
                )
                publish()
            }
        }
        override fun onMetadataUpdated(c: MediaPlayerController, item: PlayerQueueItem) = publish()
        override fun onPlaybackQueueChanged(c: MediaPlayerController, items: MutableList<PlayerQueueItem>) = publish()
        override fun onPlaybackQueueItemsAdded(c: MediaPlayerController, a: Int, b: Int, d: Int) = publish()
        override fun onPlaybackError(c: MediaPlayerController, error: MediaPlayerException) {
            onPlayerThread {
                preparing = false
                playRequest.clear()
                endGate.interrupt()
                startPosition.cancel()
                Log.w(
                    "YoinMusicKitError",
                    musicKitPlaybackErrorDiagnostic(error.type, error.errorCode, error.componentErrorCode)
                )
                failure = appContext.getString(musicKitPlaybackErrorMessage(error.type))
                publish()
            }
        }
        override fun onPlaybackRepeatModeChanged(c: MediaPlayerController, mode: Int) = publish()
        override fun onPlaybackShuffleModeChanged(c: MediaPlayerController, mode: Int) = publish()
    }
    private val prepareTimeout = Runnable {
        if (preparing && !released) {
            preparing = false
            playRequest.clear()
            failure = appContext.getString(R.string.player_apple_music_timeout)
            endGate.invalidate(nativeQueueIds())
            startPosition.cancel()
            controller.stop()
            publish()
        }
    }

    /** See [MusicKitPlayRequest]: MusicKit never reached the requested state; publish what it reports. */
    private val playRequestTimeout = object : Runnable {
        override fun run() {
            if (released || !playRequest.pending) return
            // Still loading, so MusicKit hasn't settled on a state yet (a play
            // that buffers IS playWhenReady + buffering): keep waiting.
            if (controller.isBuffering) {
                handler.postDelayed(this, PLAY_REQUEST_ANSWER_MS)
                return
            }
            playRequest.clear()
            publish("playRequest")
        }
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (!released) {
                musicKitToken.keepFresh()
                watchForStall()
                publish("ticker")
                handler.postDelayed(this, 1000)
            }
        }
    }

    /** Only runs while a native seek is being confirmed, never as an idle playback ticker. */
    private val startPositionCheck = object : Runnable {
        override fun run() {
            if (!released && startPosition.pending) publish("startPosition")
        }
    }

    init {
        controller.addListener(listener)
        handler.post(ticker)
    }

    /** See [MusicKitStallWatch]: a song MusicKit never starts and never fails ends as an error, not a spinner. */
    private fun watchForStall() {
        val stalled = stallWatch.observe(
            nowMs = SystemClock.elapsedRealtime(),
            wantsPlay = desiredPlay && queuePrepared && !preparing && failure == null,
            buffering = controller.isBuffering,
            itemKey = controller.currentItem?.playbackQueueId,
            positionMs = controller.currentPosition,
            bufferedMs = controller.bufferedPosition
        )
        if (!stalled) return
        Log.w(
            "YoinMusicKitError",
            "stalled: buffering ${MUSICKIT_STALL_MS}ms without progress " +
                "(state=${controller.playbackState} item=${controller.currentItem?.item?.subscriptionStoreId})"
        )
        playRequest.clear()
        failure = appContext.getString(R.string.player_apple_music_timeout)
        endGate.interrupt()
        controller.pause()
    }

    fun playCatalogSong(id: String) {
        require(id.matches(Regex("[0-9]+")))
        setMediaItem(MediaItem.Builder().setMediaId("applemusic:$id").build())
        playWhenReady = true
        prepare()
    }

    private fun onPlayerThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }

    private fun PlayerQueueItem.identity(): MusicKitItemIdentity? =
        item?.subscriptionStoreId?.takeIf(String::isNotBlank)?.let { MusicKitItemIdentity(playbackQueueId, it) }

    private fun nativeQueue(): List<MusicKitItemIdentity> {
        val current = controller.currentItem?.identity() ?: return emptyList()
        val seen = hashSetOf(current.queueId)
        return listOf(current) + controller.queueItems.orEmpty().mapNotNull { it.identity() }
            .filter { seen.add(it.queueId) }
    }

    private fun nativeQueueIds(): Set<Long> = nativeQueue().mapTo(mutableSetOf()) { it.queueId }

    private fun completionPermitted(): Boolean = queuePrepared && !preparing && !startPosition.pending &&
        startPosition.confirmed && !musicKitToken.holding && failure == null && desiredPlay &&
        requestedRepeatMode == Player.REPEAT_MODE_OFF && controller.repeatMode == Player.REPEAT_MODE_OFF

    private fun synchronizeNativeState() {
        if (!queuePrepared || musicKitToken.holding || prepareIssuedEpoch != endGate.queueEpoch) return
        val queue = nativeQueue()
        val current = queue.firstOrNull()
        if (endGate.confirmStart(current)) {
            endGate.confirmQueue(queue)
            startPosition.bind(endGate.queueEpoch, endGate.startItem)
        }
        when (
            val action = startPosition.observe(
                queueEpoch = endGate.queueEpoch,
                currentItem = current,
                positionMs = controller.currentPosition,
                canSeek = controller.canSeek(),
                nowMs = SystemClock.elapsedRealtime()
            )
        ) {
            is MusicKitStartPosition.Action.Seek -> controller.seekToPosition(action.positionMs)
            MusicKitStartPosition.Action.Confirmed -> {
                val wasPreparing = preparing
                preparing = false
                seekingSinceMs = null
                handler.removeCallbacks(prepareTimeout)
                if (wasPreparing && desiredPlay) {
                    val epoch = endGate.queueEpoch
                    musicKitToken.run {
                        if (!released && queuePrepared && endGate.queueEpoch == epoch && desiredPlay) controller.play()
                    }
                }
            }
            null -> Unit
        }
        // A user seek that the SDK never confirms must not hold a busy loop or manufacture an end.
        val seekExpired = seekingSinceMs?.let { SystemClock.elapsedRealtime() - it >= SEEK_CONFIRM_TIMEOUT_MS } == true
        if (!preparing && seekExpired) {
            startPosition.cancel()
            seekingSinceMs = null
            endGate.interrupt()
        }
        handler.removeCallbacks(startPositionCheck)
        if (startPosition.pending) handler.postDelayed(startPositionCheck, START_POSITION_CHECK_MS)
        endGate.observe(
            MusicKitEndGate.Sample(
                nowMs = SystemClock.elapsedRealtime(),
                item = current,
                playing = controller.playbackState == ApplePlaybackState.PLAYING,
                buffering = controller.isBuffering,
                stopped = controller.playbackState == ApplePlaybackState.STOPPED,
                positionMs = controller.currentPosition,
                durationMs = controller.duration,
                repeatOff = requestedRepeatMode == Player.REPEAT_MODE_OFF &&
                    controller.repeatMode == Player.REPEAT_MODE_OFF,
                finalItem = queue.size == 1,
                permitProgress = completionPermitted()
            )
        )
    }

    private fun publish(reason: String = "callback") {
        if (released) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { publish(reason) }
            return
        }
        if (!BuildConfig.DEBUG) {
            publishState()
            return
        }
        Trace.beginSection("Yoin.MusicKit.publish.$reason")
        try {
            publishState()
        } finally {
            Trace.endSection()
        }
    }

    private fun publishState() {
        playModes.reapply()
        synchronizeNativeState()
        if (BuildConfig.DEBUG && preparedStartUnchecked && !preparing) logPreparedStart()
        invalidateState()
        val item = controller.currentItem?.item
        onObservation(
            AppleMusicPlaybackObservation(
                error = failure != null,
                catalogId = item?.subscriptionStoreId,
                title = item?.title,
                playing = controller.playbackState == ApplePlaybackState.PLAYING && failure == null,
                positionMs = controller.currentPosition.coerceAtLeast(0),
                durationMs = controller.duration.coerceAtLeast(0),
                stopped = controller.playbackState == ApplePlaybackState.STOPPED,
                queueEpoch = endGate.queueEpoch,
                nativeQueueItemId = controller.currentItem?.playbackQueueId,
                startPositionConfirmed = startPosition.confirmed,
                sequence = ++observationSequence,
                message = failure ?: if (preparing || controller.isBuffering) "Preparing with MusicKit…" else "Ready"
            )
        )
    }

    /**
     * Debug only: what MusicKit made of the queue Yoin prepared. Its catalog request
     * (`CatalogPlaybackQueueItemProvider`, 1.1.1) leaves out every id the storefront doesn't
     * serve, without an error, and reads the start index in that shorter list: Apple Music
     * Songs once started a song eight rows past the tapped one. Yoin hands over no such id
     * (`isUnplayableAppleImport`); a warning here says one got through.
     */
    private fun logPreparedStart() {
        val current = controller.currentItem ?: return
        preparedStartUnchecked = false
        val prepared = controller.playbackQueueIndex.coerceAtLeast(0) + 1 + controller.queueItems.orEmpty().size
        val asked = requestedItems.getOrNull(requestedIndex)?.let(::catalogId)
        val started = current.item?.subscriptionStoreId
        val line = "prepared $prepared of ${requestedItems.size}; start asked=$asked got=$started"
        if (prepared == requestedItems.size && asked == started) {
            Log.d("YoinMusicKitQueue", line)
        } else {
            Log.w("YoinMusicKitQueue", line)
        }
    }

    /**
     * MusicKit exposes the current item, its index in play order and only the items *after*
     * it (getQueueItems). The published playlist is rebuilt in that play order — played items,
     * current, upcoming — so Media3 indices and MusicKit's queue share one coordinate system.
     */
    private fun playOrder(): PlayOrder? {
        val current = controller.currentItem ?: return null
        // As a queue runs out MusicKit can list the current item (or one id twice) among the upcoming ones.
        // Media3 rejects a repeated uid, and that throw — from a MusicKit listener — killed the process.
        val seen = hashSetOf(current.playbackQueueId)
        val listed = controller.queueItems.orEmpty()
        val upcoming = listed.filter { seen.add(it.playbackQueueId) }
        if (BuildConfig.DEBUG && upcoming.size != listed.size) {
            Log.w("YoinMusicKitQueue", "dropped ${listed.size - upcoming.size} repeated queue ids")
        }
        val pool = requestedItems.toMutableList()
        fun claim(queued: PlayerQueueItem): MediaItem {
            val match = pool.indexOfFirst { catalogId(it) == queued.item?.subscriptionStoreId }
            return if (match >= 0) pool.removeAt(match) else queuedMediaItem(queued)
        }
        val currentItem = claim(current)
        val upcomingItems = upcoming.map(::claim)
        // History order isn't exposed; what's left of the request stands in for it.
        val history = pool.take(controller.playbackQueueIndex.coerceAtLeast(0))
        return PlayOrder(
            entries = history.mapIndexed { i, item -> "h$i:${item.mediaId}" to item } +
                ("q${current.playbackQueueId}" to currentItem) +
                upcoming.zip(upcomingItems) { queued, item -> "q${queued.playbackQueueId}" to item },
            currentIndex = history.size,
            upcomingQueueIds = upcoming.map { it.playbackQueueId }
        )
    }

    /** MusicKit substituted an item we didn't request (e.g. a storefront equivalent). */
    private fun queuedMediaItem(queued: PlayerQueueItem): MediaItem {
        val item = queued.item
        val storeId = item?.subscriptionStoreId.orEmpty()
        val artwork = item?.getArtworkUrl(600, 600)
        return MediaItem.Builder().setMediaId("applemusic:$storeId").setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(item?.title)
                .setArtist(item?.artistName)
                .setAlbumTitle(item?.albumTitle)
                .setArtworkUri(artwork?.let(android.net.Uri::parse))
                .setExtras(
                    android.os.Bundle().apply {
                        putString("appleMusicCatalogId", storeId)
                        putString("media_id", "applemusic:$storeId")
                        putString("provider", "applemusic")
                        putString("cover_url", artwork)
                        putInt("duration_secs", ((item?.duration ?: 0) / 1000).toInt())
                    }
                ).build()
        ).build()
    }

    override fun getState(): State {
        val order = if (preparing || !queuePrepared) null else playOrder()
        val entries = order?.entries ?: requestedItems.mapIndexed { i, item -> "r$i:${item.mediaId}" to item }
        val index = order?.currentIndex ?: requestedIndex
        published = order
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
            Player.COMMAND_PREPARE, Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE
        ).apply {
            musicKitTransportCommands(
                canSeek = controller.canSeek(),
                canSkipToPreviousItem = controller.canSkipToPreviousItem(),
                canSkipToNextItem = controller.canSkipToNextItem(),
                canSkipToQueueItem = controller.canSkipToQueueItem()
            ).forEach(::add)
        }.build()
        val playlist = entries.mapIndexed { i, (uid, item) ->
            val durationMs = if (i == index && !preparing) {
                controller.duration
            } else {
                item.mediaMetadata.extras?.getInt("duration_secs", 0)?.toLong()?.times(1000) ?: 0
            }
            MediaItemData.Builder(uid).setMediaItem(item)
                .setDurationUs(if (durationMs > 0) durationMs * 1000 else C.TIME_UNSET)
                .setIsSeekable(controller.canSeek()).build()
        }
        val musicKitPlaying = controller.playbackState == ApplePlaybackState.PLAYING
        val observedPlay = if (preparing) desiredPlay else playRequest.resolve(musicKitPlaying)
        return State.Builder().setAvailableCommands(commands).setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (playlist.isEmpty()) C.INDEX_UNSET else index)
            .setPlaybackState(
                when {
                    failure != null || requestedItems.isEmpty() -> Player.STATE_IDLE
                    endGate.ended -> Player.STATE_ENDED
                    preparing || controller.isBuffering -> Player.STATE_BUFFERING
                    controller.playbackState != ApplePlaybackState.STOPPED -> Player.STATE_READY
                    else -> Player.STATE_IDLE
                }
            )
            .setPlayerError(
                failure?.let {
                    androidx.media3.common.PlaybackException(
                        it,
                        null,
                        androidx.media3.common.PlaybackException.ERROR_CODE_UNSPECIFIED
                    )
                }
            )
            .setPlayWhenReady(
                (if (endGate.ended) desiredPlay else observedPlay) && failure == null,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            )
            .setRepeatMode(controller.repeatMode)
            .setShuffleModeEnabled(controller.shuffleMode != 0)
            .setContentPositionMs(
                when {
                    preparing -> requestedStartPositionMs
                    endGate.ended -> endGate.completedPositionMs
                    else -> controller.currentPosition.coerceAtLeast(0)
                }
            )
            .setContentBufferedPositionMs { controller.bufferedPosition.coerceAtLeast(0) }.build()
    }

    private fun catalogId(item: MediaItem): String = item.mediaMetadata.extras?.getString("appleMusicCatalogId")
        ?: item.mediaId.removePrefix("applemusic:").also { require(it.matches(Regex("[0-9]+"))) }

    override fun handleSetMediaItems(
        items: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        items.forEach(::catalogId)
        playRequest.clear()
        endGate.invalidate(nativeQueueIds())
        startPosition.cancel()
        prepareIssuedEpoch = null
        seekingSinceMs = null
        handler.removeCallbacks(startPositionCheck)
        handler.removeCallbacks(prepareTimeout)
        // pause, not stop: stop() files the playing song's position as the
        // next prepare's start position. The new queue's
        // prepare replaces the old song's source anyway.
        controller.pause()
        musicKitToken.dropWaiting()
        requestedItems = items.toList()
        requestedIndex = startIndex.takeIf { it in items.indices } ?: 0
        requestedStartPositionMs = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs.coerceAtLeast(0)
        queuePrepared = false
        preparing = false
        preparedStartUnchecked = false
        failure = null
        return Futures.immediateVoidFuture()
    }
    override fun handlePrepare(): ListenableFuture<*> {
        if (requestedItems.isNotEmpty() && !queuePrepared) {
            preparing = true
            failure = null
            queuePrepared = true
            handler.removeCallbacks(prepareTimeout)
            val epoch = endGate.beginQueue(requestedItems.drop(requestedIndex).map(::catalogId), nativeQueueIds())
            prepareIssuedEpoch = null
            startPosition.begin(epoch, requestedStartPositionMs)
            val queue = CatalogPlaybackQueueItemProvider.Builder()
                .items(MediaItemType.SONG, *requestedItems.map(::catalogId).toTypedArray())
                .startItemIndex(requestedIndex).build()
            musicKitToken.run {
                if (released || !queuePrepared || endGate.queueEpoch != epoch) return@run
                handler.postDelayed(prepareTimeout, 30_000)
                preparedStartUnchecked = true
                // A new queue replaces everything, Play next included. The plain prepare() is REPLACE, which
                // MusicKit (1.1.1, `queue.b`) refuses with a queue error while an added Up Next sits after a
                // non-last item: after any Play next / Add to queue, tapping a song failed until the queue ran out.
                prepareIssuedEpoch = epoch
                controller.prepare(
                    queue,
                    PlaybackQueueInsertionType.INSERTION_TYPE_CLEAR_AND_REPLACE,
                    // Confirm the requested position before audio can start.
                    false
                )
                publish()
            }
        }
        return Futures.immediateVoidFuture()
    }

    // MusicKit can only append or insert after the current item (Play next); any other index lands at the end.
    override fun handleAddMediaItems(index: Int, items: List<MediaItem>): ListenableFuture<*> {
        val ids = items.map(::catalogId)
        endGate.queueEdited()
        requestedItems = requestedItems + items
        if (queuePrepared) {
            val afterCurrent = published?.let { index == it.currentIndex + 1 } == true
            val added = CatalogPlaybackQueueItemProvider.Builder()
                .items(MediaItemType.SONG, *ids.toTypedArray()).build()
            musicKitToken.run {
                controller.addQueueItems(
                    added,
                    if (afterCurrent) {
                        PlaybackQueueInsertionType.INSERTION_TYPE_AFTER_CURRENT_ITEM
                    } else {
                        PlaybackQueueInsertionType.INSERTION_TYPE_AT_END
                    }
                )
            }
        }
        return Futures.immediateVoidFuture()
    }
    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        endGate.queueEdited()
        val order = published
        if (fromIndex == 0 && toIndex >= (order?.entries?.size ?: requestedItems.size)) {
            playRequest.clear()
            musicKitToken.dropWaiting()
            endGate.invalidate(nativeQueueIds())
            startPosition.cancel()
            controller.stop()
            requestedItems = emptyList()
            queuePrepared = false
            preparing = false
            desiredPlay = false
        } else if (order == null) {
            requestedItems = requestedItems.filterIndexed { i, _ -> i !in fromIndex until toIndex }
        } else {
            // Only upcoming items have queue ids; the current and played items can't be removed.
            val remaining = requestedItems.toMutableList()
            for (i in (fromIndex until toIndex).reversed()) {
                val queueId = order.upcomingQueueIds.getOrNull(i - order.currentIndex - 1) ?: continue
                controller.removeQueueItemWithId(queueId)
                val removed = catalogId(order.entries[i].second)
                remaining.indexOfFirst { catalogId(it) == removed }.takeIf { it >= 0 }?.let(remaining::removeAt)
            }
            requestedItems = remaining
        }
        return Futures.immediateVoidFuture()
    }
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        endGate.interrupt()
        requestedRepeatMode = repeatMode
        playModes.setRepeatMode(repeatMode)
        return Futures.immediateVoidFuture()
    }
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        endGate.interrupt()
        playModes.setShuffleMode(if (shuffleModeEnabled) 1 else 0)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        endGate.interrupt()
        desiredPlay = playWhenReady
        if (playWhenReady && (failure != null || !queuePrepared)) {
            playRequest.clear()
            queuePrepared = false
            handlePrepare()
        } else if (queuePrepared && !preparing) {
            // Published until MusicKit's state catches up (see MusicKitPlayRequest).
            // Arm before calling MusicKit: a synchronous callback must see the request too.
            playRequest.request(playWhenReady, controller.playbackState == ApplePlaybackState.PLAYING)
            handler.removeCallbacks(playRequestTimeout)
            if (playWhenReady) {
                // Resuming can send MusicKit to Apple for the song's stream and key. A pause while it waits wins.
                musicKitToken.run {
                    if (desiredPlay) {
                        controller.play()
                        armPlayRequestTimeout()
                    }
                }
            } else {
                controller.pause()
                armPlayRequestTimeout()
            }
        }
        return Futures.immediateVoidFuture()
    }
    private fun armPlayRequestTimeout() {
        handler.removeCallbacks(playRequestTimeout)
        if (playRequest.pending) handler.postDelayed(playRequestTimeout, PLAY_REQUEST_ANSWER_MS)
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        endGate.interrupt()
        val order = published
        val targetPosition = if (positionMs == C.TIME_UNSET) 0 else positionMs.coerceAtLeast(0)
        when {
            order == null -> if (mediaItemIndex in requestedItems.indices) {
                if (!queuePrepared) requestedIndex = mediaItemIndex
                if (mediaItemIndex == requestedIndex) {
                    requestedStartPositionMs = targetPosition
                    if (queuePrepared) {
                        startPosition.begin(endGate.queueEpoch, targetPosition, endGate.startItem)
                        publish()
                    }
                }
            }
            mediaItemIndex == order.currentIndex -> if (positionMs != C.TIME_UNSET && controller.canSeek()) {
                startPosition.begin(endGate.queueEpoch, targetPosition, controller.currentItem?.identity())
                seekingSinceMs = SystemClock.elapsedRealtime()
                publish()
            }
            // Another song: MusicKit goes to Apple for its stream and key.
            mediaItemIndex > order.currentIndex -> {
                order.upcomingQueueIds.getOrNull(mediaItemIndex - order.currentIndex - 1)
                    ?.let { queueId ->
                        endGate.expectItemChange(controller.currentItem?.identity(), queueId)
                        musicKitToken.run { controller.skipToQueueItemWithId(queueId) }
                    }
            }
            // Played items have no queue ids; MusicKit steps back one item at a time.
            else -> {
                val steps = order.currentIndex - mediaItemIndex
                endGate.expectItemChange(controller.currentItem?.identity(), null)
                musicKitToken.run { repeat(steps) { controller.skipToPreviousItem() } }
            }
        }
        return Futures.immediateVoidFuture()
    }
    override fun handleStop(): ListenableFuture<*> {
        preparing = false
        desiredPlay = false
        playRequest.clear()
        musicKitToken.dropWaiting()
        endGate.invalidate(nativeQueueIds())
        startPosition.cancel()
        prepareIssuedEpoch = null
        requestedStartPositionMs = 0
        seekingSinceMs = null
        handler.removeCallbacks(startPositionCheck)
        handler.removeCallbacks(prepareTimeout)
        queuePrepared = false
        controller.stop()
        return Futures.immediateVoidFuture()
    }
    override fun handleRelease(): ListenableFuture<*> {
        released = true
        endGate.invalidate(nativeQueueIds())
        startPosition.cancel()
        musicKitToken.dropWaiting()
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        controller.removeListener(listener)
        controller.stop()
        controller.release()
        return Futures.immediateVoidFuture()
    }
}

private const val START_POSITION_CHECK_MS = 100L
private const val SEEK_CONFIRM_TIMEOUT_MS = 5_000L

/** How long a play / pause waits for MusicKit to report it before MusicKit's own state is published again. */
private const val PLAY_REQUEST_ANSWER_MS = 2_000L

private data class PlayOrder(
    val entries: List<Pair<String, MediaItem>>,
    val currentIndex: Int,
    val upcomingQueueIds: List<Long>
)

data class AppleMusicPlaybackObservation(
    val error: Boolean = false,
    val catalogId: String? = null,
    val title: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Native observations, independent of Media3's provisional command state. */
    val stopped: Boolean = false,
    val queueEpoch: Long = 0,
    val nativeQueueItemId: Long? = null,
    val startPositionConfirmed: Boolean = false,
    val sequence: Long = 0,
    val message: String = "Not playing"
)
