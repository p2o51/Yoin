package com.gpo.yoin.debug

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.gpo.yoin.R
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.PlaybackHandle
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.source.applemusic.AppleMusicSource
import com.gpo.yoin.data.source.subsonic.SubsonicMusicSource
import com.gpo.yoin.player.PlaybackService.Companion.yoinNotificationProvider
import com.gpo.yoin.player.applemusic.AppleMusicMedia3Player
import com.gpo.yoin.player.applemusic.AppleMusicPlaybackObservation
import java.io.FileDescriptor
import java.io.PrintWriter
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject

/** Debug-only, in-memory Apple → Subsonic → Apple experiment. It never switches or writes a Profile. */
@UnstableApi
class MixedPlaylistProbeService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operations = Mutex()
    private val epochs = MixedPlaylistEpoch()
    private lateinit var mirror: MixedPlaylistProbePlayer
    private lateinit var local: ExoPlayer
    private var apple: AppleMusicMedia3Player? = null
    private var appleSource: AppleMusicSource? = null
    private var fixtureSource: SubsonicMusicSource? = null
    private var session: MediaSession? = null
    private var commandJob: Job? = null
    private var deadlineJob: Job? = null
    private var tickerJob: Job? = null
    private var nativeApple: AppleMusicPlaybackObservation? = null
    private var nativeAppleAtMs = 0L
    private var activeTicket: MixedPlaylistTicket? = null
    private var label = "mixed-baseline"
    private var phase = "idle"
    private var cycle = 0
    private var runSeconds = 0L
    private var runStartedAtMs = 0L
    private var deadlineMs = 0L
    private var originalActiveProfileId: String? = null
    private var destroyed = false
    private var needsFullBarrier = true
    private var resourcesClosed = false

    private val backendListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (!destroyed) backendChanged()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Match PlaybackService's network transport/focus configuration, without touching its cache or Room.
        local = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(OkHttpClient())))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true).build().also { it.addListener(backendListener) }
        mirror = MixedPlaylistProbePlayer(::execute)
        setMediaNotificationProvider(yoinNotificationProvider())
        val open = PendingIntent.getActivity(
            this,
            7210,
            Intent(this, MixedPlaylistProbeActivity::class.java).putExtra("cmd", "dump"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, mirror).setId("mixed-playlist-probe")
            .setSessionActivity(open).build().also(::addSession)
        tickerJob = scope.launch {
            while (true) {
                delay(1000)
                backendChanged()
                emit("tick")
            }
        }
        emit("service_create")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action != ACTION_COMMAND) return START_NOT_STICKY
        if (resourcesClosed) {
            emit("closed_command_rejected")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        label = intent.getStringExtra("label") ?: label
        when (val command = intent.getStringExtra("cmd") ?: "dump") {
            "start" -> {
                bootstrapNotification()
                configure(intent)
            }
            "dump", "status" -> emit("dump")
            else -> execute(
                MixedPlaylistProbeAction(
                    command,
                    index = intent.getIntExtra("index", C.INDEX_UNSET),
                    positionMs = intent.getLongExtra("position", 0)
                )
            )
        }
        return START_NOT_STICKY
    }

    private fun configure(request: Intent) {
        deadlineJob?.cancel()
        launchOperation("start", "A", wantsPlay = true) { ticket ->
            stopAndConfirm("replace_run")
            apple?.removeListener(backendListener)
            apple?.release()
            apple = null
            appleSource?.dispose()
            fixtureSource?.dispose()
            nativeApple = null
            val container = (application as YoinApplication).container
            withTimeout(15_000) { container.profileStartupDone.first { it } }
            val profiles = withTimeout(15_000) { container.profileManager.profiles.first { it.isNotEmpty() } }
            val selectedId = request.getStringExtra("appleProfileId")
            val profile = profiles.first {
                it.provider == MediaId.PROVIDER_APPLE_MUSIC && (selectedId == null || it.id == selectedId)
            }
            val credentials = requireNotNull(container.profileManager.decodeCredentials(profile))
                as ProfileCredentials.AppleMusic
            val provider = AppleMusicSource(credentials)
            appleSource = provider
            val developerToken = withTimeout(25_000) { provider.refreshPlaybackToken() }
            ensureTicket(ticket)
            val endpoint = (request.getStringExtra("endpoint") ?: "http://127.0.0.1:18765").toHttpUrl()
            require(endpoint.username.isEmpty() && endpoint.password.isEmpty() && endpoint.query == null)
            val fixture = SubsonicMusicSource(ServerCredentials(endpoint.toString(), "fixture", "fixture"))
            fixtureSource = fixture
            val albumId = request.getStringExtra("fixtureAlbumId") ?: "fixture"
            val trackId = request.getStringExtra("fixtureTrackId") ?: "a"
            val album = withTimeout(15_000) { requireNotNull(fixture.library().getAlbum(MediaId.subsonic(albumId))) }
            val fixtureTrack = album.tracks.first { it.id == MediaId.subsonic(trackId) }
            val direct = fixture.playback().handleFor(fixtureTrack) as PlaybackHandle.DirectStream
            val ids = (request.getStringExtra("ids") ?: "1698276083,1698275560")
                .split(',').map(String::trim)
            require(ids.size == 2 && ids.all { it.matches(Regex("[0-9]+")) })
            val appleLabel = "apple:${profile.id.take(8)}"
            val entries = ids.mapIndexed { index, id ->
                val track = Track(
                    id = MediaId(MediaId.PROVIDER_APPLE_MUSIC, id),
                    title = if (id == "1698276083") "Get Up" else if (id == "1698275560") "New Jeans" else id,
                    artist = "NewJeans",
                    artistId = null,
                    album = null,
                    albumId = null,
                    coverArt = null,
                    durationSec = null,
                    trackNumber = null,
                    year = null,
                    genre = null,
                    userRating = null
                )
                val handle = provider.playback().handleFor(track) as PlaybackHandle.ExternalController
                require(handle.type == PlaybackHandle.ControllerType.APPLE_MUSIC_KIT)
                MixedPlaylistProbeEntry(
                    if (index == 0) "A" else "C",
                    MediaId.PROVIDER_APPLE_MUSIC,
                    profile.id,
                    appleLabel,
                    MediaItem.Builder().setMediaId(track.id.toString()).setMediaMetadata(
                        MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
                            .setExtras(android.os.Bundle().apply { putString("appleMusicCatalogId", id) }).build()
                    ).build()
                )
            }
            val middle = MixedPlaylistProbeEntry(
                "B",
                MediaId.PROVIDER_SUBSONIC,
                "fixture-memory",
                "fixture:subsonic",
                MediaItem.Builder().setMediaId(fixtureTrack.id.toString()).setUri(direct.uri)
                    .setMimeType(fixtureTrack.extras["subsonic.contentType"])
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(fixtureTrack.title).setArtist(fixtureTrack.artist).build()
                    ).build(),
                durationMs = (fixtureTrack.durationSec ?: 0) * 1000L
            )
            apple = AppleMusicMedia3Player(
                this, developerToken, credentials.musicUserToken,
                refreshDeveloperToken = { provider.refreshPlaybackToken() },
                onObservation = { observation ->
                    nativeApple = observation
                    nativeAppleAtMs = SystemClock.elapsedRealtime()
                    if (!destroyed) backendChanged()
                }
            ).also { it.addListener(backendListener) }
            mirror.entries = listOf(entries[0], middle, entries[1])
            mirror.index = 0
            mirror.backend = null
            cycle = 0
            runSeconds = request.getLongExtra("runSeconds", 0).coerceIn(0, 3600)
            runStartedAtMs = 0
            deadlineMs = 0
            originalActiveProfileId = container.profileManager.activeProfileId.value
            emit("configured", ticket, "entries" to entryIdentities(mirror.entries))
            startEntry(ticket, 0, true)
        }
    }

    private fun execute(action: MixedPlaylistProbeAction): ListenableFuture<*> {
        if (resourcesClosed) return Futures.immediateFailedFuture<Void>(IllegalStateException("Mixed probe closed"))
        return when (action.name) {
            "next" -> move(1)
            "previous" -> move(-1)
            "play", "resume" -> launchOperation("play", entryId(), wantsPlay = true) { ticket ->
                val backend = mirror.backend
                if (needsFullBarrier || backend == null || backend.playbackState == Player.STATE_IDLE ||
                    backend.playbackState == Player.STATE_ENDED
                ) {
                    startEntry(ticket, mirror.index, true)
                } else {
                    val sequence = nativeApple?.sequence ?: 0
                    backend.play()
                    awaitStarted(ticket, true, sequence, requireNewQueue = false)
                    activeTicket = ticket
                    epochs.arm(ticket)
                    phase = "playing"
                }
            }
            "pause" -> launchOperation("pause", entryId(), wantsPlay = false) {
                quiesce(stop = false, reason = "user_pause")
                phase = "paused"
            }
            "stop", "finish" -> {
                deadlineJob?.cancel()
                launchOperation(action.name, entryId(), wantsPlay = false) {
                    stopAndConfirm("user_${action.name}")
                    needsFullBarrier = true
                    phase = "stopped"
                    mirror.completed = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (action.name == "finish") {
                        emit("finish_confirmed")
                        closeResources()
                        stopSelf()
                    }
                }
            }
            "seek" -> {
                val target = action.index.takeIf { it in mirror.entries.indices } ?: mirror.index
                if (target != mirror.index) {
                    launchOperation("seek_entry", mirror.entries[target].entryId, mirror.wantsPlay) { ticket ->
                        startEntry(ticket, target, mirror.wantsPlay)
                    }
                } else {
                    launchOperation("seek", entryId(), mirror.wantsPlay) { ticket ->
                        val backend = requireNotNull(mirror.backend)
                        backend.seekTo(action.positionMs.coerceAtLeast(0))
                        activeTicket = ticket
                        if (mirror.wantsPlay) epochs.arm(ticket)
                        phase = if (mirror.wantsPlay) "playing" else "paused"
                    }
                }
            }
            else -> {
                emit("unknown_command", fields = arrayOf("command" to action.name))
                Futures.immediateFailedFuture<Void>(IllegalArgumentException("Unsupported mixed probe command"))
            }
        }
    }

    private fun move(direction: Int): ListenableFuture<*> {
        if (mirror.entries.isEmpty()) return Futures.immediateFailedFuture<Void>(IllegalStateException("No queue"))
        val target = (mirror.index + direction).coerceIn(mirror.entries.indices)
        val play = mirror.wantsPlay
        return launchOperation(if (direction > 0) "next" else "previous", mirror.entries[target].entryId, play) {
            startEntry(it, target, play)
        }
    }

    private suspend fun startEntry(ticket: MixedPlaylistTicket, index: Int, play: Boolean) {
        require(index in mirror.entries.indices)
        stopAndConfirm("handoff")
        ensureTicket(ticket)
        val entry = mirror.entries[index]
        val backend = if (entry.provider == MediaId.PROVIDER_APPLE_MUSIC) requireNotNull(apple) else local
        val beforeSequence = nativeApple?.sequence ?: 0
        val beforeQueue = nativeApple?.queueEpoch ?: 0
        mirror.index = index
        mirror.backend = backend
        mirror.completed = false
        activeTicket = ticket
        phase = "starting"
        emit("backend_start", ticket, "nativeQueueBefore" to beforeQueue)
        backend.repeatMode = Player.REPEAT_MODE_OFF
        backend.shuffleModeEnabled = false
        backend.setMediaItems(listOf(entry.item), 0, 0)
        backend.playWhenReady = play
        backend.prepare()
        awaitStarted(ticket, play, beforeSequence, requireNewQueue = true, beforeQueue = beforeQueue)
        ensureTicket(ticket)
        phase = if (play) "playing" else "paused"
        if (play) epochs.arm(ticket)
        if (play && runStartedAtMs == 0L) {
            runStartedAtMs = SystemClock.elapsedRealtime()
            if (runSeconds > 0) {
                deadlineMs = runStartedAtMs + runSeconds * 1000
                deadlineJob = scope.launch {
                    delay(runSeconds * 1000)
                    // The clock never invents completion: finish after C's next genuine natural end.
                    emit("minimum_run_reached")
                }
            }
        }
        emit("backend_confirmed", ticket)
        needsFullBarrier = false
    }

    private suspend fun awaitStarted(
        ticket: MixedPlaylistTicket,
        play: Boolean,
        beforeSequence: Long,
        requireNewQueue: Boolean,
        beforeQueue: Long = 0
    ) = withTimeout(60_000) {
        while (true) {
            ensureTicket(ticket)
            val entry = mirror.entries[mirror.index]
            if (entry.provider == MediaId.PROVIDER_APPLE_MUSIC) {
                val sample = nativeApple
                check(sample?.error != true) { "Native Apple start failed" }
                val identity = sample?.catalogId == entry.item.mediaId.removePrefix("applemusic:")
                if (sample != null && sample.sequence > beforeSequence && identity &&
                    (!requireNewQueue || sample.queueEpoch > beforeQueue) && sample.startPositionConfirmed &&
                    sample.playing == play && (!play || sample.positionMs > 0)
                ) {
                    return@withTimeout
                }
            } else {
                check(local.playerError == null) { "Local start failed" }
                if (local.currentMediaItem?.mediaId == entry.item.mediaId &&
                    local.playbackState == Player.STATE_READY && local.isPlaying == play &&
                    local.playWhenReady == play && (!play || local.currentPosition > 0)
                ) {
                    return@withTimeout
                }
            }
            delay(50)
        }
    }

    private suspend fun stopAndConfirm(reason: String) = quiesce(stop = true, reason = reason)

    private suspend fun quiesce(stop: Boolean, reason: String) {
        phase = if (stop) "stopping" else "pausing"
        val beforeSequence = nativeApple?.sequence ?: 0
        val nativeGate = MixedNativeStopGate(beforeSequence)
        var localStableAt = 0L
        var localPosition = local.currentPosition
        if (stop) {
            apple?.stop()
            local.stopForMixedHandoff()
        } else {
            apple?.pause()
            local.pause()
        }
        emit(if (stop) "stop_requested" else "pause_requested", fields = arrayOf("reason" to reason))
        withTimeout(12_000) {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val sample = nativeApple
                val appleQuiet = apple == null || sample != null && nativeGate.observe(
                    sample.sequence, nativeAppleAtMs, sample.stopped || !stop, sample.playing,
                    sample.error, sample.positionMs
                )
                val localQuiet = !local.isPlaying && !local.playWhenReady &&
                    (!stop || local.playbackState == Player.STATE_IDLE || local.playbackState == Player.STATE_ENDED)
                if (!localQuiet || abs(local.currentPosition - localPosition) > 100) {
                    localStableAt = 0
                    localPosition = local.currentPosition
                } else if (localStableAt == 0L) localStableAt = now
                if (appleQuiet && localStableAt > 0 && now - localStableAt >= 400) break
                delay(50)
            }
        }
        emit(if (stop) "stop_confirmed" else "pause_confirmed", fields = arrayOf("reason" to reason))
    }

    private fun launchOperation(
        name: String,
        targetEntry: String,
        wantsPlay: Boolean,
        action: suspend (MixedPlaylistTicket) -> Unit
    ): ListenableFuture<*> {
        val future = SettableFuture.create<Void>()
        val ticket = epochs.begin(targetEntry)
        commandJob?.cancel(CancellationException("Superseded mixed probe command"))
        mirror.wantsPlay = wantsPlay
        mirror.transitioning = true
        mirror.failureCode = null
        mirror.publish()
        emit("command", ticket, "command" to name)
        commandJob = scope.launch {
            operations.withLock {
                try {
                    ensureTicket(ticket)
                    action(ticket)
                    ensureTicket(ticket)
                    mirror.transitioning = false
                    mirror.publish()
                    emit("command_complete", ticket, "command" to name)
                    future.set(null)
                } catch (timeout: TimeoutCancellationException) {
                    failOperation(ticket, name, "timeout", future)
                } catch (cancelled: CancellationException) {
                    // Keep the mutex through cleanup: an old cancellation cannot Stop a newer backend.
                    needsFullBarrier = true
                    if (!destroyed) {
                        withContext(NonCancellable) {
                            runCatching { stopAndConfirm("cancel_cleanup") }.onFailure {
                                emit("cancel_stop_unconfirmed", ticket, "type" to it.javaClass.simpleName)
                            }
                        }
                    }
                    emit("command_cancelled", ticket, "command" to name)
                    future.cancel(false)
                    throw cancelled
                } catch (failure: Exception) {
                    failOperation(ticket, name, failure.javaClass.simpleName, future)
                }
            }
        }
        commandJob?.invokeOnCompletion { failure ->
            if (failure != null && !future.isDone) future.cancel(false)
        }
        return future
    }

    private suspend fun failOperation(
        ticket: MixedPlaylistTicket,
        name: String,
        code: String,
        future: SettableFuture<Void>
    ) {
        needsFullBarrier = true
        var stopped = false
        if (!destroyed) {
            withContext(NonCancellable) {
                runCatching {
                    stopAndConfirm("failure_cleanup")
                    stopped = true
                }.onFailure {
                    emit("failure_stop_unconfirmed", ticket, "type" to it.javaClass.simpleName)
                }
            }
        }
        if (epochs.isCurrent(ticket)) {
            mirror.wantsPlay = false
            mirror.transitioning = false
            mirror.failureCode = if (stopped) code else "stop_unconfirmed"
            phase = "error"
            mirror.publish()
            if (stopped) stopForeground(STOP_FOREGROUND_REMOVE)
        }
        emit("command_failed", ticket, "command" to name, "type" to code)
        future.setException(IllegalStateException("Mixed probe command failed: $code"))
    }

    private fun backendChanged() {
        mirror.publish()
        if (!mirror.transitioning && mirror.wantsPlay && mirror.backend?.playerError != null) {
            launchOperation("backend_error", entryId(), wantsPlay = false) {
                stopAndConfirm("backend_error")
                needsFullBarrier = true
                mirror.failureCode = "backend_error"
                phase = "error"
            }
            return
        }
        val ticket = activeTicket ?: return
        if (mirror.transitioning || !mirror.wantsPlay || mirror.backend?.playbackState != Player.STATE_ENDED) return
        if (!epochs.claimNaturalEnd(ticket)) return
        emit("natural_end", ticket)
        scope.launch {
            delay(1)
            if (!epochs.isCurrent(ticket) || !mirror.wantsPlay || mirror.transitioning) return@launch
            val atEnd = mirror.index == mirror.entries.lastIndex
            if (atEnd && (runSeconds == 0L || SystemClock.elapsedRealtime() >= deadlineMs)) {
                launchOperation("natural_complete", entryId(), wantsPlay = false) {
                    stopAndConfirm("queue_complete")
                    mirror.completed = true
                    phase = "complete"
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    emit("run_complete")
                }
            } else {
                val next = if (atEnd) 0 else mirror.index + 1
                if (atEnd) cycle++
                launchOperation("natural_next", mirror.entries[next].entryId, wantsPlay = true) {
                    startEntry(it, next, true)
                }
            }
        }
    }

    private suspend fun ensureTicket(ticket: MixedPlaylistTicket) {
        currentCoroutineContext().ensureActive()
        if (!epochs.isCurrent(ticket)) throw CancellationException("Stale mixed probe epoch")
    }

    private fun entryId(): String = mirror.entries.getOrNull(mirror.index)?.entryId ?: "unconfigured"

    private fun bootstrapNotification() {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Mixed playlist debug probe", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(
            NOTIFICATION,
            NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_yoin)
                .setContentTitle("Mixed playlist validation").setContentText("Apple Music → Subsonic → Apple Music")
                .setOngoing(true).build()
        )
    }

    private fun snapshot(ticket: MixedPlaylistTicket? = epochs.current): JSONObject {
        val entry = mirror.entries.firstOrNull { it.entryId == ticket?.entryId }
            ?: mirror.entries.getOrNull(mirror.index)
        val backend = mirror.backend
        val native = nativeApple
        val activeProfile = (application as YoinApplication).container.profileManager.activeProfileId.value
        return JSONObject().put("elapsed", SystemClock.elapsedRealtime()).put("wall", System.currentTimeMillis())
            .put("label", label)
            .put("queueEpoch", ticket?.queueEpoch ?: 0).put("entryId", entry?.entryId ?: "unconfigured")
            .put("provider", entry?.provider).put("profileLabel", entry?.profileLabel)
            .put("profileId", entry?.profileId).put("identity", entry?.item?.mediaId)
            .put("activeEntryId", entryId()).put("cycle", cycle).put("phase", phase)
            .put("runElapsedMs", if (runStartedAtMs > 0) SystemClock.elapsedRealtime() - runStartedAtMs else 0)
            .put("minimumRunSeconds", runSeconds)
            .put("minimumRunReached", deadlineMs > 0 && SystemClock.elapsedRealtime() >= deadlineMs)
            .put("activeProfileUnchanged", originalActiveProfileId == null || originalActiveProfileId == activeProfile)
            .put("backendItem", backend?.currentMediaItem?.mediaId).put("backendState", backend?.playbackState)
            .put("backendPlaying", backend?.isPlaying).put("backendPosition", backend?.currentPosition)
            .put("desiredPlay", mirror.wantsPlay).put("transitioning", mirror.transitioning)
            .put("localState", local.playbackState).put("localPlaying", local.isPlaying)
            .put("localPlayWhenReady", local.playWhenReady).put("localPosition", local.currentPosition)
            .put("appleSequence", native?.sequence).put("appleQueueEpoch", native?.queueEpoch)
            .put("appleNativeQueueItemId", native?.nativeQueueItemId)
            .put("appleStopped", native?.stopped).put("applePlaying", native?.playing)
            .put("appleIdentity", native?.catalogId).put("applePosition", native?.positionMs)
            .put("appleStartConfirmed", native?.startPositionConfirmed).put("appleError", native?.error)
    }

    private fun emit(kind: String, ticket: MixedPlaylistTicket? = epochs.current, vararg fields: Pair<String, Any?>) {
        val data = snapshot(ticket).put("kind", kind)
        fields.forEach { (key, value) -> data.put(key, value ?: JSONObject.NULL) }
        lastDump = data.toString()
        Log.i(TAG, lastDump)
    }

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        // Android invokes this through its DUMP-protected service management path; no credential fields enter it.
        writer.println(lastDump)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        emit("task_removed")
        if (mirror.entries.isEmpty() && !mirror.transitioning) stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        epochs.begin("destroyed")
        deadlineJob?.cancel()
        scope.cancel()
        if (!resourcesClosed) local.stopForMixedHandoff()
        apple?.stop()
        emit("service_destroy")
        closeResources()
        super.onDestroy()
    }

    private fun closeResources() {
        if (resourcesClosed) return
        resourcesClosed = true
        tickerJob?.cancel()
        deadlineJob?.cancel()
        session?.let {
            removeSession(it)
            it.release()
        }
        session = null
        local.removeListener(backendListener)
        local.release()
        apple?.removeListener(backendListener)
        apple?.release()
        apple = null
        appleSource?.dispose()
        appleSource = null
        fixtureSource?.dispose()
        fixtureSource = null
        mirror.backend = null
        mirror.release()
    }

    companion object {
        const val ACTION_COMMAND = "com.gpo.yoin.debug.MIXED_PLAYLIST_PROBE"
        const val TAG = "YoinMixedProbe"
        private const val CHANNEL = "mixed-playlist-probe"
        private const val NOTIFICATION = 7211

        @Volatile var lastDump: String = "{}"
            private set
    }
}

private fun entryIdentities(entries: List<MixedPlaylistProbeEntry>): org.json.JSONArray = org.json.JSONArray().apply {
    entries.forEach { entry ->
        put(
            JSONObject().put("entryId", entry.entryId).put("provider", entry.provider)
                .put("profileLabel", entry.profileLabel).put("identity", entry.item.mediaId)
        )
    }
}
