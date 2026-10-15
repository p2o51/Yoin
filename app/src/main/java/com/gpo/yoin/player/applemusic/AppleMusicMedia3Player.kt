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
import com.apple.android.sdk.authentication.TokenProvider
import com.gpo.yoin.BuildConfig
import com.gpo.yoin.R
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.bytedeco.javacpp.Pointer

/** MusicKit owns DRM/audio. Media3 exposes only observed state and supported commands. */
@UnstableApi
class AppleMusicMedia3Player(
    context: Context,
    developerToken: String,
    userToken: String,
    private val currentDeveloperToken: () -> String = { developerToken },
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
            override fun getDeveloperToken() = currentDeveloperToken()
            override fun getUserToken() = userToken
        }
    )
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

    /**
     * A queue Yoin prepared must start its first song at 0:00. MusicKit
     * (1.1.1, `playback.c.b`) keeps a `savedPlaybackPosition` — written by
     * `stop()` while something plays, and restored with the last session's
     * queue at launch — and its next `preparePlayer()` seeks the NEW queue's
     * start item to it, whatever song that is: a new album then opened at
     * 0:15 or 1:30, wherever the last song stopped (owner 2026-10-05). Set at
     * prepare; spent the first time the start item plays or pauses, or by any
     * deliberate seek.
     */
    private var startFromTop = false

    /** Debug only: the next prepared queue is still to be compared with the request ([logPreparedStart]). */
    private var preparedStartUnchecked = false
    private val listener = object : MediaPlayerController.Listener {
        override fun onPlayerStateRestored(c: MediaPlayerController) {
            if (requestedItems.isEmpty()) c.stop()
            publish()
        }
        override fun onPlaybackStateChanged(c: MediaPlayerController, old: Int, new: Int) {
            if (new != ApplePlaybackState.STOPPED) {
                preparing = false
                rewindRestoredStart()
            }
            publish()
        }
        override fun onPlaybackStateUpdated(c: MediaPlayerController) = publish()
        override fun onBufferingStateChanged(c: MediaPlayerController, buffering: Boolean) = publish()
        override fun onCurrentItemChanged(c: MediaPlayerController, old: PlayerQueueItem?, new: PlayerQueueItem?) =
            publish()
        override fun onItemEnded(c: MediaPlayerController, item: PlayerQueueItem, end: Long) = publish()
        override fun onMetadataUpdated(c: MediaPlayerController, item: PlayerQueueItem) = publish()
        override fun onPlaybackQueueChanged(c: MediaPlayerController, items: MutableList<PlayerQueueItem>) = publish()
        override fun onPlaybackQueueItemsAdded(c: MediaPlayerController, a: Int, b: Int, d: Int) = publish()
        override fun onPlaybackError(c: MediaPlayerController, error: MediaPlayerException) {
            preparing = false
            playRequest.clear()
            Log.w(
                "YoinMusicKitError",
                musicKitPlaybackErrorDiagnostic(error.type, error.errorCode, error.componentErrorCode)
            )
            failure = appContext.getString(musicKitPlaybackErrorMessage(error.type))
            publish()
        }
        override fun onPlaybackRepeatModeChanged(c: MediaPlayerController, mode: Int) = publish()
        override fun onPlaybackShuffleModeChanged(c: MediaPlayerController, mode: Int) = publish()
    }
    private val prepareTimeout = Runnable {
        if (preparing && !released) {
            preparing = false
            playRequest.clear()
            failure = appContext.getString(R.string.player_apple_music_timeout)
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
                watchForStall()
                publish("ticker")
                handler.postDelayed(this, 1000)
            }
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
        // pause, not stop: see startFromTop.
        controller.pause()
    }

    fun playCatalogSong(id: String) {
        require(id.matches(Regex("[0-9]+")))
        setMediaItem(MediaItem.Builder().setMediaId("applemusic:$id").build())
        playWhenReady = true
        prepare()
    }

    /** See [startFromTop]: the start item came up past its first second, so MusicKit carried a stale position. */
    private fun rewindRestoredStart() {
        if (!startFromTop || controller.currentItem == null) return
        startFromTop = false
        if (controller.currentPosition > RestoredStartSlackMs && controller.canSeek()) controller.seekToPosition(0)
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
        if (BuildConfig.DEBUG && preparedStartUnchecked && !preparing) logPreparedStart()
        playModes.reapply()
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
                observedPlay && failure == null,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            )
            .setRepeatMode(controller.repeatMode)
            .setShuffleModeEnabled(controller.shuffleMode != 0)
            .setContentPositionMs(if (preparing) 0 else controller.currentPosition.coerceAtLeast(0))
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
        // pause, not stop: stop() files the playing song's position as the
        // next prepare's start position (see startFromTop). The new queue's
        // prepare replaces the old song's source anyway.
        controller.pause()
        requestedItems = items.toList()
        requestedIndex = startIndex.takeIf { it in items.indices } ?: 0
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
            handler.postDelayed(prepareTimeout, 30_000)
            startFromTop = true
            preparedStartUnchecked = true
            // A new queue replaces everything, Play next included. The plain prepare() is REPLACE, which
            // MusicKit (1.1.1, `queue.b`) refuses with a queue error while an added Up Next sits after a
            // non-last item: after any Play next / Add to queue, tapping a song failed until the queue ran out.
            controller.prepare(
                CatalogPlaybackQueueItemProvider.Builder()
                    .items(MediaItemType.SONG, *requestedItems.map(::catalogId).toTypedArray())
                    .startItemIndex(requestedIndex).build(),
                com.apple.android.music.playback.queue.PlaybackQueueInsertionType.INSERTION_TYPE_CLEAR_AND_REPLACE,
                desiredPlay
            )
        }
        return Futures.immediateVoidFuture()
    }
    // MusicKit can only append or insert after the current item (Play next); any other index lands at the end.
    override fun handleAddMediaItems(index: Int, items: List<MediaItem>): ListenableFuture<*> {
        val ids = items.map(::catalogId)
        requestedItems = requestedItems + items
        if (queuePrepared) {
            val afterCurrent = published?.let { index == it.currentIndex + 1 } == true
            controller.addQueueItems(
                CatalogPlaybackQueueItemProvider.Builder()
                    .items(MediaItemType.SONG, *ids.toTypedArray()).build(),
                if (afterCurrent) {
                    com.apple.android.music.playback.queue.PlaybackQueueInsertionType.INSERTION_TYPE_AFTER_CURRENT_ITEM
                } else {
                    com.apple.android.music.playback.queue.PlaybackQueueInsertionType.INSERTION_TYPE_AT_END
                }
            )
        }
        return Futures.immediateVoidFuture()
    }
    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val order = published
        if (fromIndex == 0 && toIndex >= (order?.entries?.size ?: requestedItems.size)) {
            playRequest.clear()
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
        playModes.setRepeatMode(repeatMode)
        return Futures.immediateVoidFuture()
    }
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        playModes.setShuffleMode(if (shuffleModeEnabled) 1 else 0)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        desiredPlay = playWhenReady
        if (playWhenReady && (failure != null || !queuePrepared)) {
            playRequest.clear()
            queuePrepared = false
            handlePrepare()
        } else if (queuePrepared) {
            // Published until MusicKit's state catches up (see MusicKitPlayRequest).
            // Arm before calling MusicKit: a synchronous callback must see the request too.
            playRequest.request(playWhenReady, controller.playbackState == ApplePlaybackState.PLAYING)
            if (playWhenReady) controller.play() else controller.pause()
            handler.removeCallbacks(playRequestTimeout)
            if (playRequest.pending) handler.postDelayed(playRequestTimeout, PLAY_REQUEST_ANSWER_MS)
        }
        return Futures.immediateVoidFuture()
    }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        // A seek of the user's own (a note's moment, the bar) is never undone.
        startFromTop = false
        val order = published
        when {
            order == null -> if (!queuePrepared && mediaItemIndex in requestedItems.indices) {
                requestedIndex = mediaItemIndex
            }
            mediaItemIndex == order.currentIndex -> if (positionMs != C.TIME_UNSET && controller.canSeek()) {
                controller.seekToPosition(positionMs.coerceAtLeast(0))
            }
            mediaItemIndex > order.currentIndex ->
                order.upcomingQueueIds.getOrNull(mediaItemIndex - order.currentIndex - 1)
                    ?.let(controller::skipToQueueItemWithId)
            // Played items have no queue ids; MusicKit steps back one item at a time.
            else -> repeat(order.currentIndex - mediaItemIndex) { controller.skipToPreviousItem() }
        }
        return Futures.immediateVoidFuture()
    }
    override fun handleStop(): ListenableFuture<*> {
        preparing = false
        desiredPlay = false
        playRequest.clear()
        queuePrepared = false
        controller.stop()
        return Futures.immediateVoidFuture()
    }
    override fun handleRelease(): ListenableFuture<*> {
        released = true
        handler.removeCallbacksAndMessages(null)
        controller.removeListener(listener)
        controller.stop()
        controller.release()
        return Futures.immediateVoidFuture()
    }
}

/** A published playlist: (uid, item) pairs in play order, and the upcoming items' MusicKit queue ids. */
/** A freshly prepared start item further in than this was put there by MusicKit, not by playback. */
private const val RestoredStartSlackMs = 1_000L

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
    val message: String = "Not playing"
)
