package com.gpo.yoin.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.Song
import com.gpo.yoin.data.remote.SubsonicApiFactory
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PlaybackManager(
    private val context: Context,
    private val repository: YoinRepository,
    private val castManager: CastManager? = null,
) {
    private var controller: MediaController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionUpdateJob: Job? = null
    private var connectJob: Job? = null
    private val pendingCommands = mutableListOf<(MediaController) -> Unit>()
    private var lastRecordedSongId: String? = null
    private var currentActivityContext: ActivityContext = ActivityContext.None

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    init {
        // Observe cast state and reflect in PlaybackState
        castManager?.let { cm ->
            scope.launch {
                cm.castState.collect { state ->
                    _playbackState.value = _playbackState.value.copy(
                        isCasting = state is CastState.Connected,
                        castDeviceName = (state as? CastState.Connected)?.deviceName,
                    )
                }
            }
        }
    }

    suspend fun connect() {
        if (controller != null) return
        connectInBackground()
        connectJob?.join()
    }

    fun connectInBackground() {
        if (controller != null || connectJob?.isActive == true) return
        _playbackState.value = _playbackState.value.copy(
            controllerReady = false,
            connectionErrorMessage = null,
        )
        connectJob = scope.launch {
            try {
                val built = buildController()
                controller = built
                built.addListener(playerListener)
                castManager?.setLocalPlayerProvider { controller }
                syncState()
                startPositionUpdates()
                flushPendingCommands(built)
            } catch (error: Throwable) {
                _playbackState.value = _playbackState.value.copy(
                    controllerReady = false,
                    connectionErrorMessage = error.message ?: "Unable to initialize playback",
                )
            } finally {
                connectJob = null
            }
        }
    }

    fun disconnect() {
        pendingCommands.clear()
        connectJob?.cancel()
        connectJob = null
        positionUpdateJob?.cancel()
        positionUpdateJob = null
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        _playbackState.value = PlaybackState()
    }

    // ── Playback controls ───────────────────────────────────────────────

    fun play(
        songs: List<Song>,
        startIndex: Int = 0,
        credentials: ServerCredentials,
        activityContext: ActivityContext = ActivityContext.None,
    ) {
        lastRecordedSongId = null
        currentActivityContext = activityContext
        executeOrQueue { player ->
            val items = songs.map { it.toMediaItem(credentials) }
            player.setMediaItems(items, startIndex, 0L)
            player.prepare()
            player.play()
        }
    }

    fun playSingle(
        song: Song,
        credentials: ServerCredentials,
        activityContext: ActivityContext = ActivityContext.None,
    ) {
        play(listOf(song), 0, credentials, activityContext)
    }

    fun pause() {
        executeOrQueue { it.pause() }
    }

    fun resume() {
        executeOrQueue { it.play() }
    }

    fun skipNext() {
        executeOrQueue { player ->
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
            }
        }
    }

    fun skipPrevious() {
        executeOrQueue { player ->
            if (player.hasPreviousMediaItem()) {
                player.seekToPreviousMediaItem()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        executeOrQueue { it.seekTo(positionMs) }
    }

    fun setRepeatMode(mode: Int) {
        executeOrQueue { it.repeatMode = mode }
    }

    fun toggleShuffle() {
        executeOrQueue { player ->
            player.shuffleModeEnabled = !player.shuffleModeEnabled
        }
    }

    // ── Queue management ────────────────────────────────────────────────

    fun addToQueue(song: Song, credentials: ServerCredentials) {
        executeOrQueue { player ->
            player.addMediaItem(song.toMediaItem(credentials))
        }
    }

    fun clearQueue() {
        executeOrQueue { it.clearMediaItems() }
    }

    fun skipToQueueItem(index: Int) {
        lastRecordedSongId = null
        executeOrQueue { player ->
            if (index in 0 until player.mediaItemCount) {
                player.seekToDefaultPosition(index)
            }
        }
    }

    // ── Internal ────────────────────────────────────────────────────────

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncState()
            if (isPlaying) startPositionUpdates() else stopPositionUpdates()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncState()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            syncState()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            syncState()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            syncState()
        }
    }

    private fun syncState() {
        val player = controller ?: return
        val queue = buildList {
            for (i in 0 until player.mediaItemCount) {
                add(player.getMediaItemAt(i).toSong())
            }
        }
        val currentIndex = player.currentMediaItemIndex
        val currentItem = player.currentMediaItem?.toSong()
        val resolvedCurrentSong = when {
            currentItem != null -> currentItem
            currentIndex in queue.indices -> queue[currentIndex]
            player.mediaItemCount == 0 -> null
            else -> _playbackState.value.currentSong
        }
        _playbackState.value = PlaybackState(
            currentSong = resolvedCurrentSong,
            isPlaying = player.isPlaying,
            position = player.currentPosition.coerceAtLeast(0L),
            duration = player.duration.coerceAtLeast(0L),
            bufferedPosition = player.bufferedPosition.coerceAtLeast(0L),
            queue = queue,
            currentIndex = currentIndex,
            repeatMode = player.repeatMode,
            shuffleEnabled = player.shuffleModeEnabled,
            audioSessionId = PlaybackService.audioSessionId.value,
            controllerReady = true,
            connectionErrorMessage = null,
        )

        val shouldRecord = player.isPlaying &&
            resolvedCurrentSong != null &&
            resolvedCurrentSong.id != lastRecordedSongId
        if (shouldRecord) {
            val song = checkNotNull(resolvedCurrentSong)
            lastRecordedSongId = song.id
            scope.launch {
                repository.recordPlay(
                    song = song,
                    durationMs = player.duration.coerceAtLeast(0L).takeIf { it > 0L }
                        ?: ((song.duration ?: 0) * 1_000L),
                    completedPercent = 0f,
                    activityContext = currentActivityContext,
                )
            }
        }
    }

    private fun startPositionUpdates() {
        if (positionUpdateJob?.isActive == true) return
        positionUpdateJob = scope.launch {
            while (isActive) {
                val player = controller
                if (player != null && player.isPlaying) {
                    _playbackState.value = _playbackState.value.copy(
                        position = player.currentPosition.coerceAtLeast(0L),
                        bufferedPosition = player.bufferedPosition.coerceAtLeast(0L),
                    )
                }
                delay(POSITION_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    private fun executeOrQueue(command: (MediaController) -> Unit) {
        val player = controller
        if (player != null) {
            command(player)
        } else {
            pendingCommands += command
            connectInBackground()
        }
    }

    private fun flushPendingCommands(player: MediaController) {
        val commands = pendingCommands.toList()
        pendingCommands.clear()
        commands.forEach { it(player) }
    }

    companion object {
        private const val POSITION_UPDATE_INTERVAL_MS = 250L

        private const val EXTRA_SONG_ID = "song_id"
        private const val EXTRA_ALBUM = "album"
        private const val EXTRA_ALBUM_ID = "album_id"
        private const val EXTRA_ARTIST_ID = "artist_id"
        private const val EXTRA_COVER_ART = "cover_art"
        private const val EXTRA_DURATION = "duration_secs"
        private const val EXTRA_TRACK = "track"
        private const val EXTRA_YEAR = "year"
        private const val EXTRA_GENRE = "genre"
        private const val EXTRA_STARRED = "starred"
        private const val EXTRA_USER_RATING = "user_rating"

        internal fun Song.toMediaItem(credentials: ServerCredentials): MediaItem {
            val streamUrl = SubsonicApiFactory.buildStreamUrl(credentials, id)
            val artworkUri = coverArt?.let {
                Uri.parse(SubsonicApiFactory.buildCoverArtUrl(credentials, it))
            }

            val extras = Bundle().apply {
                putString(EXTRA_SONG_ID, id)
                putString(EXTRA_ALBUM, album)
                putString(EXTRA_ALBUM_ID, albumId)
                putString(EXTRA_ARTIST_ID, artistId)
                putString(EXTRA_COVER_ART, coverArt)
                duration?.let { putInt(EXTRA_DURATION, it) }
                track?.let { putInt(EXTRA_TRACK, it) }
                year?.let { putInt(EXTRA_YEAR, it) }
                putString(EXTRA_GENRE, genre)
                putString(EXTRA_STARRED, starred)
                userRating?.let { putInt(EXTRA_USER_RATING, it) }
            }

            val metadata = MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artworkUri)
                .setTrackNumber(track)
                .setExtras(extras)
                .build()

            return MediaItem.Builder()
                .setMediaId(id)
                .setUri(streamUrl)
                .setMediaMetadata(metadata)
                .build()
        }

        internal fun MediaItem.toSong(): Song {
            val extras = mediaMetadata.extras
            return Song(
                id = mediaId,
                title = mediaMetadata.title?.toString(),
                artist = mediaMetadata.artist?.toString(),
                album = extras?.getString(EXTRA_ALBUM),
                albumId = extras?.getString(EXTRA_ALBUM_ID),
                artistId = extras?.getString(EXTRA_ARTIST_ID),
                coverArt = extras?.getString(EXTRA_COVER_ART),
                duration = extras?.takeIf { it.containsKey(EXTRA_DURATION) }
                    ?.getInt(EXTRA_DURATION),
                track = extras?.takeIf { it.containsKey(EXTRA_TRACK) }
                    ?.getInt(EXTRA_TRACK),
                year = extras?.takeIf { it.containsKey(EXTRA_YEAR) }
                    ?.getInt(EXTRA_YEAR),
                genre = extras?.getString(EXTRA_GENRE),
                starred = extras?.getString(EXTRA_STARRED),
                userRating = extras?.takeIf { it.containsKey(EXTRA_USER_RATING) }
                    ?.getInt(EXTRA_USER_RATING),
            )
        }
    }

    private suspend fun buildController(): MediaController {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java),
        )
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        return suspendCancellableCoroutine { continuation ->
            Futures.addCallback(
                future,
                object : FutureCallback<MediaController> {
                    override fun onSuccess(result: MediaController) {
                        continuation.resume(result)
                    }

                    override fun onFailure(t: Throwable) {
                        continuation.resumeWithException(t)
                    }
                },
                MoreExecutors.directExecutor(),
            )
        }
    }
}
