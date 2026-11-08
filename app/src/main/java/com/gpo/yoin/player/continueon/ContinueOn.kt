package com.gpo.yoin.player.continueon

import android.content.Context
import android.net.ConnectivityManager
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
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Continue On (Android 17 cross-device handoff, docs/continue-on.md), both ends.
 *
 * Handing off: while something plays, Yoin's windows offer the hand-off
 * ([offered]) and give the system a [ContinueOnCursor] when it asks
 * ([handoffEntries]), with a short-lived local pause channel.
 *
 * Taking over: a window started with a cursor hands it to [receive], which
 * shows a small card ([prompt]) instead of playing at once (owner
 * 2026-11-08). "Continue here" ([confirm]) asks the other device to pause,
 * switches to the same account here, reads the songs back and starts from
 * where the other device stopped, then opens Now Playing.
 */
class ContinueOn internal constructor(
    private val context: Context,
    private val playback: PlaybackManager,
    private val profiles: ProfileManager,
    private val accountKey: suspend (Profile) -> String?,
    private val requestNowPlaying: () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val pauseServer = ContinueOnPauseServer(scope, onPause = ::pauseForHandoff)

    /** The active account's cross-device id, kept ready: the system asks for a hand-off on the main thread. */
    @Volatile private var activeAccountKey: String? = null

    init {
        scope.launch {
            profiles.activeProfile.collectLatest { profile ->
                activeAccountKey = null
                // Another account's pause port must not stop this one's playback.
                pauseServer.close()
                activeAccountKey = profile?.let { runCatching { accountKey(it) }.getOrNull() }
            }
        }
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
     * request's data over later.
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
        val network = connectivity.activeNetwork ?: return emptyList()
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return emptyList()
        val local = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!local) return emptyList()
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

    /** The other device took over: pause here and say where it stopped (null: another song plays now). */
    private suspend fun pauseForHandoff(trackId: String): Long? {
        val state = playback.playbackState.value
        val same = state.currentTrack?.id?.rawId == trackId
        val position = playback.livePositionMs()
        playback.pause()
        Log.i(TAG, "paused for a hand-off (same track: $same)")
        return position.takeIf { same }
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
        // 1. The other device stops first, and says exactly where.
        val spotify = cursor.provider == MediaId.PROVIDER_SPOTIFY
        val answer = if (!spotify && cursor.playing) {
            cursor.pause?.let { ContinueOnPauseClient.requestPause(it, cursor.trackId) }
                ?: ContinueOnPauseClient.Answer.Unreachable
        } else {
            null
        }
        val pausedAt = (answer as? ContinueOnPauseClient.Answer.Paused)?.positionMs
        // 2. The same account here.
        if (profiles.activeProfileId.value != profileId) profiles.switchTo(profileId)
        if (profiles.activeProfileId.value != profileId) return null
        val source = withTimeoutOrNull(SOURCE_WAIT_MS) {
            profiles.activeSource.first { it != null && it.profileId.let { id -> id == null || id == profileId } }
        } ?: return null
        // 3. The songs, read again from this device's copy of the account.
        val queue = restoreQueue(source, cursor) ?: return null
        val track = queue.tracks[queue.index]
        val durationMs = (track.durationSec ?: 0) * 1_000L
        val position = pausedAt ?: cursor.positionAt(System.currentTimeMillis(), durationMs)
        // 4. Play from there, as the other device did.
        playback.play(
            tracks = queue.tracks,
            startIndex = queue.index,
            source = source,
            activityContext = queue.activityContext,
            explicitStart = true,
            startPositionMs = position,
        )
        playback.setPlayMode(PlayMode.of(cursor.repeatMode, cursor.shuffle))
        requestNowPlaying()
        // Spotify starts from the top: it moves to the spot once it plays the song. A song paused on the
        // other device is held here too, once it is loaded (a start always plays).
        val seek = position.takeIf { spotify && it > 0L }
        if (seek != null || !cursor.playing) afterStart(track.id, spotify, seek, pause = !cursor.playing)
        return if (answer == ContinueOnPauseClient.Answer.Unreachable) TakeOver.OtherStillPlaying else TakeOver.Paused
    }

    private fun afterStart(trackId: MediaId, spotify: Boolean, seekMs: Long?, pause: Boolean) {
        scope.launch {
            withTimeoutOrNull(START_WAIT_MS) {
                playback.playbackState.first { state ->
                    state.currentTrack?.id == trackId &&
                        (!spotify || state.connectionPhase == ConnectionPhase.Ready)
                }
            } ?: return@launch
            seekMs?.let(playback::seekTo)
            if (pause) playback.pause()
        }
    }

    private suspend fun matchProfile(cursor: ContinueOnCursor): Profile? {
        val candidates = profiles.profiles.first().filter { it.provider == cursor.provider }
        val keys = candidates.associateWith { runCatching { accountKey(it) }.getOrNull() }
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
     * The queue the other device was playing: its album, playlist or Liked songs read in one go when it
     * came from one and the song is in it, else the song and what came next, read one by one.
     */
    private suspend fun restoreQueue(source: MusicSource, cursor: ContinueOnCursor): RestoredQueue? {
        val provider = cursor.provider
        val activityContext = cursor.context.toActivityContext(provider)
        val whole = runCatching { readContext(source, cursor) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        whole?.indexOfFirst { it.id.rawId == cursor.trackId }?.takeIf { it >= 0 }?.let { index ->
            return RestoredQueue(whole, index, activityContext)
        }
        val ids = cursor.queueIds.drop(cursor.index).take(MAX_SONG_READS)
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
