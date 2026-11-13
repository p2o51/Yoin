package com.gpo.yoin.player.continueon

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.player.ConnectionPhase
import com.gpo.yoin.player.PlayMode
import com.gpo.yoin.player.PlaybackManager
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Continue On (Android 17 cross-device handoff, docs/continue-on.md), both ends.
 *
 * Handing off: while something plays, Yoin's windows offer the hand-off
 * ([offered]) and give the system a [ContinueOnCursor] when it asks
 * ([handoffEntries]), with a local pause channel that stays while the song
 * does.
 *
 * Taking over: a window started with a cursor hands it to [receive], which
 * shows a small card ([prompt]) instead of playing at once (owner
 * 2026-11-08). "Continue here" ([confirm]) switches to the same account
 * here, asks the other device to pause, reads the songs back and starts
 * from the song and the spot the other device stopped at, then opens Now
 * Playing.
 */
class ContinueOn internal constructor(
    private val context: Context,
    private val playback: PlaybackManager,
    private val profiles: ProfileManager,
    private val accountKey: suspend (Profile) -> String?,
    private val requestNowPlaying: () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Asks the device that handed off to pause, each socket passed to `bind` before it connects. */
    private val askToPause: suspend (
        channel: ContinueOnCursor.PauseChannel,
        trackId: String,
        bind: (Socket) -> Unit,
    ) -> ContinueOnPauseClient.Answer = { channel, trackId, bind ->
        ContinueOnPauseClient.requestPause(channel, trackId, bind = bind)
    },
) {
    // The system reads a cursor from a window as it goes to the background and may hand it over much
    // later: the port stays for as long as this device still has a song the other one could take over.
    private val pauseServer = ContinueOnPauseServer(
        scope,
        onPause = ::pauseForHandoff,
        stillNeeded = { offered.value },
    )

    /** The active account's cross-device id, kept ready: the system asks for a hand-off on the main thread. */
    @Volatile private var activeAccountKey: String? = null

    init {
        scope.launch {
            profiles.activeProfile.collectLatest { profile ->
                activeAccountKey = null
                // Another account's pause port must not stop this one's playback.
                pauseServer.close()
                activeAccountKey = profile?.let { keyOf(it) }
            }
        }
    }

    /**
     * [accountKey] off the main thread: it decrypts the account's stored credentials (a file and the
     * Keystore), and the first of these runs as the shell first draws. Null when it can't be read.
     */
    private suspend fun keyOf(profile: Profile): String? = try {
        withContext(io) { accountKey(profile) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "no account key for ${profile.provider}", error)
        null
    }

    /**
     * Whether a window should offer the hand-off now: a song is loaded on this device (not cast to a
     * TV or a speaker, nor a Spotify Connect device elsewhere, which the other device reaches itself).
     */
    val offered: StateFlow<Boolean> = combine(playback.playbackState, profiles.activeProfile) { state, profile ->
        profile != null && state.currentTrack != null && !state.isCasting && state.spotifyDevice == null
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)

    private val _prompt = MutableStateFlow<ContinueOnPrompt?>(null)

    /** The card a received hand-off shows, until the person answers it. */
    val prompt: StateFlow<ContinueOnPrompt?> = _prompt.asStateFlow()

    private var promptJob: Job? = null

    // ---- Handing off -------------------------------------------------------------------------

    /**
     * The cursor for a hand-off the system is asking for, flattened for its extras; null when nothing
     * is playing here. [activeRequest] is true when the person has just picked this hand-off on the
     * other device; the pause channel is open for either kind, since the system may hand a background
     * request's data over later (how much later is its own business: the channel then says which song
     * this device has got to).
     */
    internal fun handoffEntries(activeRequest: Boolean): Map<String, Any>? {
        val state = playback.playbackState.value
        val track = state.currentTrack ?: return null
        val profile = profiles.activeProfile.value ?: return null
        if (!offered.value) return null
        val provider = track.id.provider
        val queue = state.queue
        val (queueIds, index) = if (state.currentIndex in queue.indices && queue[state.currentIndex].id == track.id) {
            ContinueOnCursor.queueWindow(queue.map { it.id.rawId }, state.currentIndex)
        } else {
            listOf(track.id.rawId) to 0
        }
        // Spotify needs no pause from here: playing on the other device moves Spotify Connect over,
        // and a pause sent through this phone's App Remote would land on the device now playing.
        val pause = if (state.playWhenReady && provider != MediaId.PROVIDER_SPOTIFY) openPauseChannel() else null
        val cursor = ContinueOnCursor(
            provider = provider,
            accountKey = activeAccountKey,
            trackId = track.id.rawId,
            title = track.title,
            artist = track.artist,
            album = track.album,
            cover = CoverRef.toStorageKey(track.coverArt),
            queueIds = queueIds.ifEmpty { listOf(track.id.rawId) },
            index = index.coerceAtLeast(0),
            positionMs = playback.livePositionMs(),
            capturedAtMs = System.currentTimeMillis(),
            playing = state.playWhenReady,
            repeatMode = state.repeatMode,
            shuffle = state.shuffleEnabled,
            context = playback.currentActivityContext.value.toCursorContext(),
            deviceName = Build.MODEL,
            pause = pause,
        )
        if (activeRequest) {
            Log.i(TAG, "handing off ${track.id} for ${profile.provider} (pause channel: ${pause != null})")
        }
        return cursor.toEntries()
    }

    private fun openPauseChannel(): ContinueOnCursor.PauseChannel? {
        val hosts = localAddresses().ifEmpty { return null }
        val (port, token) = pauseServer.open() ?: return null
        return ContinueOnCursor.PauseChannel(hosts, port, token)
    }

    /** This device's addresses on the Wi-Fi or Ethernet it is on (Continue On needs both devices on one). */
    private fun localAddresses(): List<String> = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val network = connectivity.localNetwork() ?: return emptyList()
        connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
            .map { it.address }
            .filter { address ->
                !address.isLoopbackAddress && !address.isLinkLocalAddress &&
                    (address is Inet4Address || address is Inet6Address)
            }
            // IPv4 first: what a home router is surest to route between its own devices.
            .sortedBy { if (it is Inet4Address) 0 else 1 }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .take(MAX_HOSTS)
    }.getOrDefault(emptyList())

    /**
     * The other device took over: pause here and say which song this device is on by now (the cursor it
     * holds may name an earlier one) and where in it; null with no song loaded.
     */
    private suspend fun pauseForHandoff(trackId: String): PausedAt? {
        val track = playback.playbackState.value.currentTrack
        val position = playback.livePositionMs()
        playback.pause()
        Log.i(TAG, "paused for a hand-off (same track: ${track?.id?.rawId == trackId})")
        return track?.let { PausedAt(it.id.rawId, position) }
    }

    /**
     * Ties [socket] to the Wi-Fi or Ethernet this device is on before it connects: with a VPN up here,
     * the connection would otherwise go into its tunnel and never reach the other device.
     */
    private fun bindToLocalNetwork(socket: Socket) {
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.localNetwork()?.bindSocket(socket) }
            .onFailure { Log.w(TAG, "pause socket not tied to the local network", it) }
    }

    // ---- Taking over -------------------------------------------------------------------------

    /** A window was started with a hand-off: show its card (a newer hand-off replaces an older one). */
    fun receive(read: (String) -> Any?): Boolean {
        val cursor = ContinueOnCursor.fromEntries(read) ?: return false
        promptJob?.cancel()
        _prompt.value = ContinueOnPrompt(cursor, ContinueOnPrompt.Account.Looking, coverUrl = null)
        promptJob = scope.launch {
            val profile = runCatching { matchProfile(cursor) }.getOrNull()
            val account = if (profile == null) {
                ContinueOnPrompt.Account.Missing
            } else {
                ContinueOnPrompt.Account.Found(profile.id, profile.displayName)
            }
            val coverUrl = profile?.let { found ->
                CoverRef.fromStorageKey(cursor.cover)?.let { ref ->
                    runCatching { profiles.withSourceFor(found.id) { it.resolveCoverUrl(ref, COVER_PX) } }.getOrNull()
                }
            }
            update(cursor) { it.copy(account = account, coverUrl = coverUrl) }
        }
        return true
    }

    /** "Not now": the card goes, and the other device plays on. */
    fun dismiss() {
        promptJob?.cancel()
        _prompt.value = null
    }

    /** "Continue here". */
    fun confirm() {
        val prompt = _prompt.value ?: return
        if (prompt.stage != ContinueOnPrompt.Stage.Asking) return
        val account = prompt.account as? ContinueOnPrompt.Account.Found ?: return
        val cursor = prompt.cursor
        promptJob?.cancel()
        _prompt.value = prompt.copy(stage = ContinueOnPrompt.Stage.Starting)
        promptJob = scope.launch {
            val outcome = try {
                takeOver(cursor, account.profileId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "hand-off failed", error)
                null
            }
            when (outcome) {
                null -> update(cursor) { it.copy(stage = ContinueOnPrompt.Stage.Failed) }
                TakeOver.Paused -> _prompt.value = null
                TakeOver.OtherStillPlaying -> {
                    update(cursor) { it.copy(stage = ContinueOnPrompt.Stage.OtherStillPlaying) }
                    delay(STILL_PLAYING_NOTE_MS)
                    update(cursor) { null }
                }
            }
        }
    }

    private enum class TakeOver { Paused, OtherStillPlaying }

    private suspend fun takeOver(cursor: ContinueOnCursor, profileId: String): TakeOver? {
        // 1. The same account here. Before the other device is asked to stop: a switch that fails
        // leaves it playing.
        if (profiles.activeProfileId.value != profileId) {
            profiles.switchTo(profileId)
            // The card tells a switch that failed. Left in ProfileManager, its error would open Settings on
            // a switch Settings never started (Home's switcher acknowledges its own the same way).
            val switched = profiles.switchingState.value
            if (switched is ProfileManager.SwitchState.Error && switched.profileId == profileId) {
                profiles.acknowledgeSwitchError()
            }
        }
        if (profiles.activeProfileId.value != profileId) return null
        val source = withTimeoutOrNull(SOURCE_WAIT_MS) {
            profiles.activeSource.first { it != null && it.profileId.let { id -> id == null || id == profileId } }
        } ?: return null
        // 2. The other device stops, and says which song it is on by now and exactly where: the system
        // may have read this cursor long before (as the window there went to the background).
        val spotify = cursor.provider == MediaId.PROVIDER_SPOTIFY
        val answer = if (!spotify && cursor.playing) {
            cursor.pause?.let { askToPause(it, cursor.trackId, ::bindToLocalNetwork) }
                ?: ContinueOnPauseClient.Answer.Unreachable
        } else {
            null
        }
        val paused = answer as? ContinueOnPauseClient.Answer.Paused
        val playingId = paused?.trackId ?: cursor.trackId
        // 3. The songs, read again from this device's copy of the account.
        val queue = restoreQueue(source, cursor, playingId) ?: return null
        // The account may have changed meanwhile: this hand-off belongs to the one it chose.
        if (profiles.activeProfileId.value != profileId || profiles.activeSource.value !== source) return null
        val track = queue.tracks[queue.index]
        val durationMs = (track.durationSec ?: 0) * 1_000L
        // Where the other device said it stopped, when the song that starts here is the one it stopped in.
        val position = paused?.positionMs?.takeIf { track.id.rawId == playingId }
            ?: cursor.positionAt(System.currentTimeMillis(), durationMs)
        // 4. Play from there, as the other device did.
        playback.play(
            tracks = queue.tracks,
            startIndex = queue.index,
            source = source,
            activityContext = queue.activityContext,
            explicitStart = true,
            startPositionMs = position,
            startPaused = !cursor.playing,
        )
        playback.setPlayMode(PlayMode.of(cursor.repeatMode, cursor.shuffle))
        requestNowPlaying()
        // Spotify starts from the top and always plays: it moves to the spot, and a song paused on the
        // other device pauses, once it plays the song.
        if (spotify && (position > 0L || !cursor.playing)) {
            afterSpotifyStart(track.id, position, pause = !cursor.playing)
        }
        return if (answer == ContinueOnPauseClient.Answer.Unreachable) TakeOver.OtherStillPlaying else TakeOver.Paused
    }

    private fun afterSpotifyStart(trackId: MediaId, positionMs: Long, pause: Boolean) {
        // Dispatched (not immediate), so it looks only after the start play() posted to the main thread.
        scope.launch(Dispatchers.Main) {
            withTimeoutOrNull(START_WAIT_MS) {
                // This start, not a state from before it: the song near its top, as a fresh start reports it
                // (the same song already playing here at another spot must not take the seek).
                playback.playbackState.first { state ->
                    state.currentTrack?.id == trackId &&
                        state.connectionPhase == ConnectionPhase.Ready &&
                        state.position < FRESH_START_MS
                }
            } ?: return@launch
            if (positionMs > 0L) playback.seekTo(positionMs)
            if (pause) playback.pause()
        }
    }

    private suspend fun matchProfile(cursor: ContinueOnCursor): Profile? {
        val candidates = profiles.profiles.first().filter { it.provider == cursor.provider }
        val keys = candidates.associateWith { keyOf(it) }
        cursor.accountKey?.let { key -> keys.entries.firstOrNull { it.value == key }?.let { return it.key } }
        // The account's id isn't known on one side (Spotify or Apple Music not syncing there): the only
        // account of that service here that could be it, else the one in use.
        val open = if (cursor.accountKey != null) candidates.filter { keys[it] == null } else candidates
        return open.singleOrNull() ?: open.firstOrNull { it.id == profiles.activeProfileId.value }
    }

    private fun update(cursor: ContinueOnCursor, change: (ContinueOnPrompt) -> ContinueOnPrompt?) {
        val current = _prompt.value ?: return
        if (current.cursor !== cursor) return
        _prompt.value = change(current)
    }

    private data class RestoredQueue(val tracks: List<Track>, val index: Int, val activityContext: ActivityContext)

    /**
     * The queue the other device was playing, from [trackId] on (the song it is on by now, which need not
     * be the cursor's any more): its album, playlist or Liked songs read in one go when it came from one
     * and the song is in it, else the song and what came next, read one by one. A song that can't be read
     * here falls back to the cursor's own.
     */
    private suspend fun restoreQueue(source: MusicSource, cursor: ContinueOnCursor, trackId: String): RestoredQueue? {
        val whole = runCatching { readContext(source, cursor) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        return restoreFrom(source, cursor, whole, trackId)
            ?: if (trackId != cursor.trackId) restoreFrom(source, cursor, whole, cursor.trackId) else null
    }

    private suspend fun restoreFrom(
        source: MusicSource,
        cursor: ContinueOnCursor,
        whole: List<Track>?,
        trackId: String,
    ): RestoredQueue? {
        val provider = cursor.provider
        val activityContext = cursor.context.toActivityContext(provider)
        whole?.indexOfFirst { it.id.rawId == trackId }?.takeIf { it >= 0 }?.let { index ->
            return RestoredQueue(whole, index, activityContext)
        }
        val ids = cursor.queueFrom(trackId).take(MAX_SONG_READS)
        val permits = Semaphore(PARALLEL_READS)
        val tracks = coroutineScope {
            ids.map { raw ->
                async {
                    permits.withPermit {
                        runCatching { source.library().getTrack(MediaId(provider, raw)) }
                            .onFailure { if (it is CancellationException) throw it }
                            .getOrNull()
                    }
                }
            }.awaitAll()
        }
        // The song itself must be there; a later one that can't be read is left out.
        if (tracks.firstOrNull() == null) return null
        return RestoredQueue(tracks.filterNotNull(), 0, activityContext)
    }

    private suspend fun readContext(source: MusicSource, cursor: ContinueOnCursor): List<Track>? {
        val context = cursor.context ?: return null
        val library = source.library()
        fun id(): MediaId? = context.id?.let { rawContextId(it, cursor.provider) }?.let { MediaId(cursor.provider, it) }
        return when (context.kind) {
            ContinueOnCursor.Context.Kind.Album -> id()?.let { library.getAlbum(it)?.tracks }
            ContinueOnCursor.Context.Kind.Playlist -> id()?.let { library.getPlaylistInFull(it)?.tracks }
            ContinueOnCursor.Context.Kind.LikedSongs -> library.getStarred().tracks
            ContinueOnCursor.Context.Kind.Artist -> null
        }
    }

    companion object {
        private const val TAG = "YoinContinueOn"
        private const val MAX_HOSTS = 3
        private const val COVER_PX = 300

        /** Read one by one, a queue stops at the song and the ones after it (a hand-off is about now). */
        private const val MAX_SONG_READS = 30
        private const val PARALLEL_READS = 6
        private const val SOURCE_WAIT_MS = 10_000L
        private const val START_WAIT_MS = 15_000L
        private const val FRESH_START_MS = 3_000L
        private const val STILL_PLAYING_NOTE_MS = 6_000L
    }
}

/** What a received hand-off's card shows. */
data class ContinueOnPrompt(
    val cursor: ContinueOnCursor,
    val account: Account,
    val coverUrl: String?,
    val stage: Stage = Stage.Asking,
) {
    val title: String? get() = cursor.title
    val artist: String? get() = cursor.artist
    val album: String? get() = cursor.album
    val deviceName: String? get() = cursor.deviceName
    val positionMs: Long get() = cursor.positionMs
    val playing: Boolean get() = cursor.playing

    sealed interface Account {
        /** Still matching the other device's account to one here. */
        data object Looking : Account

        data class Found(val profileId: String, val name: String) : Account

        /** This device has no copy of that account. */
        data object Missing : Account
    }

    enum class Stage {
        Asking,
        Starting,

        /** Playing here, but the other device couldn't be reached to pause. */
        OtherStillPlaying,

        /** The songs couldn't be read here. */
        Failed,
    }
}

/**
 * The Wi-Fi or Ethernet this device is on itself, never a VPN laid over it. A VPN is the network in use
 * and reports the transport under it as its own, but its addresses are the tunnel's, which nothing on
 * the local network reaches, and a connection made through it never comes out there.
 */
internal fun ConnectivityManager.localNetwork(): Network? {
    fun local(network: Network): Boolean {
        val capabilities = getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                )
    }
    activeNetwork?.takeIf(::local)?.let { return it }
    // One look as a hand-off is asked for or answered; a NetworkCallback would have to stay registered.
    @Suppress("DEPRECATION")
    val networks = allNetworks
    return networks.firstOrNull(::local)
}

/** The raw id in an [ActivityContext] id, which pages store either raw or as a full MediaId string. */
internal fun rawContextId(id: String, provider: String): String = id.removePrefix("$provider:")

internal fun ActivityContext.toCursorContext(): ContinueOnCursor.Context? = when (this) {
    ActivityContext.None -> null
    is ActivityContext.Album -> ContinueOnCursor.Context(ContinueOnCursor.Context.Kind.Album, albumId, albumName)
    is ActivityContext.Playlist ->
        ContinueOnCursor.Context(ContinueOnCursor.Context.Kind.Playlist, playlistId, playlistName)
    is ActivityContext.Artist -> ContinueOnCursor.Context(ContinueOnCursor.Context.Kind.Artist, artistId, artistName)
    is ActivityContext.LikedSongs -> ContinueOnCursor.Context(ContinueOnCursor.Context.Kind.LikedSongs, null, name)
}

internal fun ContinueOnCursor.Context?.toActivityContext(provider: String): ActivityContext {
    val context = this ?: return ActivityContext.None
    // As the detail pages keep it: the full MediaId string (Spotify's context sync reads its provider from it).
    val id = context.id?.let { rawContextId(it, provider) }?.takeIf { it.isNotBlank() }
        ?.let { MediaId(provider, it).toString() }
    return when (context.kind) {
        ContinueOnCursor.Context.Kind.Album ->
            id?.let { ActivityContext.Album(albumId = it, albumName = context.name.orEmpty()) } ?: ActivityContext.None
        ContinueOnCursor.Context.Kind.Playlist ->
            id?.let { ActivityContext.Playlist(playlistId = it, playlistName = context.name.orEmpty()) }
                ?: ActivityContext.None
        ContinueOnCursor.Context.Kind.Artist ->
            id?.let { ActivityContext.Artist(artistId = it, artistName = context.name.orEmpty()) }
                ?: ActivityContext.None
        ContinueOnCursor.Context.Kind.LikedSongs ->
            context.name?.let { ActivityContext.LikedSongs(name = it) } ?: ActivityContext.LikedSongs()
    }
}
