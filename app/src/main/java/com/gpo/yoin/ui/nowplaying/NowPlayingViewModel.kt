package com.gpo.yoin.ui.nowplaying

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.R
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.model.Lyrics as SourceLyrics
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.data.model.YoinDevice
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.ServiceFeatureCatalog
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.spotify.SpotifyAuthException
import com.gpo.yoin.player.CastManager
import com.gpo.yoin.player.CastState
import com.gpo.yoin.player.ConnectionPhase
import com.gpo.yoin.player.PlaybackManager
import com.gpo.yoin.player.PlaybackState
import com.gpo.yoin.data.model.Track
import androidx.media3.common.Player
import com.gpo.yoin.ui.common.UiText
import com.gpo.yoin.ui.component.AddToPlaylistRow
import com.gpo.yoin.ui.component.NoteDraftState
import com.gpo.yoin.ui.component.NoteSaveRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Sentinel id a Spotify App Remote track gets when its uri is blank
 *  (mirror of SpotifyAppRemotePlayer's fallback). Such a track can't be saved. */
private const val REMOTE_UNKNOWN_TRACK_ID = "spotify-remote-unknown"

/** Settle time before prefetching the next song's lyrics (skips cancel it). */
private const val UP_NEXT_PREFETCH_DELAY_MS = 4_000L

/** A failed prefetch (network, provider timeout) retries this many times… */
private const val UP_NEXT_PREFETCH_RETRIES = 2

/** …after this long, times the attempt number. */
private const val UP_NEXT_PREFETCH_RETRY_MS = 15_000L

/** Prefetched lyrics kept per song id (the next song, plus one just passed). */
private const val PREFETCHED_LYRICS_KEPT = 3

/**
 * How long a skip-previous direction outlives the song change it caused —
 * ample for the new cover to compose and take it (it reads the direction once,
 * as it enters), far short of any later auto-advance.
 */
internal const val SKIP_DIRECTION_HOLD_MS = 1_000L

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModel(
    private val playbackManager: PlaybackManager,
    private val repository: YoinRepository,
    private val castManager: CastManager,
    private val onPlaylistMutated: () -> Unit = {},
    private val lyricHintStore: LyricHintStore = LyricHintStore.InMemory(),
) : ViewModel() {

    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    private val _lyricsLoading = MutableStateFlow(false)

    /**
     * The song the lyrics flows currently describe. Playback state and the
     * lyrics reset arrive through different flows, so for a frame the UI can
     * see the NEW song id next to the OLD song's lyrics. The lyrics view
     * animates per song, so that frame would show the old lines in the new
     * song's slot; uiState masks lyrics whose owner isn't the playing song.
     */
    private val _lyricsOwnerSongId = MutableStateFlow<String?>(null)

    /**
     * Lyrics fetched ahead of time for upcoming songs, by song id — timed or
     * not. An entry is applied (and dropped) the moment its song starts, so
     * the song opens with its lyrics already there instead of a loading beat.
     * Kept apart from [_upNextSongId] on purpose: when the queue's next song
     * changes (or runs out) at the very song change that consumes an entry,
     * the entry must still be there for the starting song.
     */
    private val _prefetchedLyrics = MutableStateFlow<Map<String, UpNextLyrics>>(emptyMap())

    /** The song that plays next, as far as the player lets us know ([resolvedNextTrack]). */
    private val _upNextSongId = MutableStateFlow<String?>(null)

    private data class PreloadState(
        val upNextSongId: String?,
        val lyricsBySongId: Map<String, UpNextLyrics>,
    ) {
        /**
         * The next song's TIMED lyrics, for the lyrics view's outro: they rise
         * in during the current song's last line and hand over without a
         * loading beat. Untimed lyrics are preloaded too but not choreographed.
         */
        val upNextTimed: UpNextLyrics?
            get() = upNextSongId?.let(lyricsBySongId::get)
                ?.takeIf { entry -> entry.lines.any { it.startMs != null } }
    }

    private val preloadFlow: Flow<PreloadState> = combine(
        _upNextSongId,
        _prefetchedLyrics,
        ::PreloadState,
    )
    private val _showLyricsTranslation = MutableStateFlow(false)
    private val _lyricsActionInFlight = MutableStateFlow<LyricsAction?>(null)
    private val _currentLyricsProviderName = MutableStateFlow<String?>(null)
    private val _currentLyricsProviderSongId = MutableStateFlow<String?>(null)
    private val _lyricsSearchState = MutableStateFlow(LyricsSearchState())
    val lyricsSearchState: StateFlow<LyricsSearchState> = _lyricsSearchState.asStateFlow()
    private var pendingLyricsTranslationSwitchOffer:
        YoinRepository.LyricsTranslationProviderSwitchOffer? = null
    private val _lyricsTranslationSwitchOffers =
        MutableSharedFlow<LyricsTranslationSwitchOfferUi>(extraBufferCapacity = 1)
    val lyricsTranslationSwitchOffers: SharedFlow<LyricsTranslationSwitchOfferUi> =
        _lyricsTranslationSwitchOffers.asSharedFlow()
    private val _isStarred = MutableStateFlow(false)
    private data class LibraryActionKey(val profileId: String, val trackId: MediaId)
    private val _libraryActions = MutableStateFlow<Map<LibraryActionKey, Any>>(emptyMap())

    // Transient ask-bar state drives the fullscreen About UI animation — NOT
    // persisted. See [AskBarState].
    private val _askState = MutableStateFlow<AskBarState>(AskBarState.Idle)
    val askState: StateFlow<AskBarState> = _askState.asStateFlow()
    private var askRequestId = 0L

    // `aboutFetchError` / `aboutLoading` are UI-only overlays on top of the
    // observed Room flow. Room observer always has the ground truth list;
    // these two only surface the transient loading / error states that a
    // Flow of entries can't express.
    private val _aboutLoading = MutableStateFlow(false)
    private val _aboutError = MutableStateFlow<AboutUiState?>(null)

    // Now Playing stage + selected detail page. Default = Compact/Lyrics so
    // that a cold Now Playing open preserves today's behaviour. YoinNavHost
    // owns back priority and asks this model to step stages down before it
    // dismisses the shell overlay.
    private val _stageMode = MutableStateFlow(NowPlayingStageMode.Compact)
    val stageMode: StateFlow<NowPlayingStageMode> = _stageMode.asStateFlow()

    private val _detailPage = MutableStateFlow(NowPlayingDetailPage.Lyrics)
    val detailPage: StateFlow<NowPlayingDetailPage> = _detailPage.asStateFlow()

    // Medium / Wide windows: side panel (false) or the Full state (true —
    // the enlarged phone on Medium, the two columns on Wide), 断点交接 §3.4.
    // Held here — not in the composition — so a detail Activity recreated by
    // rotation keeps the user's choice (MainActivity never recreates; the
    // model survives either way).
    private val _mediumFullscreen = MutableStateFlow(false)
    val mediumFullscreen: StateFlow<Boolean> = _mediumFullscreen.asStateFlow()

    private val currentSongId: StateFlow<MediaId?> = playbackManager.playbackState
        .map { it.currentTrack?.id }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            combine(currentSongId, repository.currentProfileIdFlow) { songId, profileId -> songId to profileId }
                .distinctUntilChanged()
                .collectLatest { (songId, _) ->
                    val track = playbackManager.playbackState.value.currentTrack
                    if (track != null && track.id == songId && Capability.LIBRARY_ADD in repository.currentCapabilities()) {
                        repository.refreshLibraryMembership(track)
                    }
                }
        }
        viewModelScope.launch {
            // collectLatest（不是 collect）：切歌时立刻取消前一首的 loadLyrics
            // —— 否则前一首的 provider fetch（10 秒 callTimeout）会把
            // 整条 pipeline 串行阻塞住，连星标都要等前一首的 HTTP 超时才刷新。
            //
            // About/canonical fetch **不**在这里触发（v0.5 起懒加载）—— 等用户
            // 第一次打开 About 页再调 [onAboutOpened]。
            currentSongId.collectLatest { songId ->
                if (songId != null) {
                    // 切歌瞬间就应该生效的状态（星标 / 歌词清空 / loading on）
                    // 必须在任何 suspend 调用之前更新，否则一 suspend 就可能被下一次
                    // collectLatest 取消掉，用户看到的就是「上一首内容原地不动」。
                    val track = playbackManager.playbackState.value.currentTrack
                    // Prefetched during the previous song's outro: apply it in
                    // the same beat as the song change, so the lyrics view's
                    // rising "up next" block becomes this song without a
                    // loading state in between.
                    val prefetched = _prefetchedLyrics.value[songId.toString()]
                    _isStarred.value = track?.isStarred == true
                    _lyrics.value = prefetched?.lines ?: emptyList()
                    _lyricsLoading.value = prefetched == null
                    _lyricsOwnerSongId.value = songId.toString()
                    // Consumed: the lyrics flows own it now.
                    if (prefetched != null) {
                        _prefetchedLyrics.update { it - songId.toString() }
                    }
                    _showLyricsTranslation.value = false
                    _lyricsActionInFlight.value = null
                    _currentLyricsProviderName.value = prefetched?.providerName
                    _currentLyricsProviderSongId.value = prefetched?.providerSongId
                    pendingLyricsTranslationSwitchOffer = null
                    lyricsSearchJob?.cancel()
                    _lyricsSearchState.value = LyricsSearchState()
                    _aboutError.value = null
                    _askState.value = AskBarState.Idle

                    if (prefetched == null) loadLyrics(songId, track?.title, track?.artist)
                } else {
                    _lyrics.value = emptyList()
                    _lyricsLoading.value = false
                    _showLyricsTranslation.value = false
                    _lyricsActionInFlight.value = null
                    _currentLyricsProviderName.value = null
                    _currentLyricsProviderSongId.value = null
                    pendingLyricsTranslationSwitchOffer = null
                    lyricsSearchJob?.cancel()
                    _lyricsSearchState.value = LyricsSearchState()
                    _isStarred.value = false
                    _aboutError.value = null
                    _askState.value = AskBarState.Idle
                }
            }
        }
        viewModelScope.launch {
            // Preload the next song's lyrics. collectLatest + a short delay:
            // rapid skipping cancels the fetch instead of hitting providers
            // for every track it passes over. getLoadedLyrics also warms the
            // repository's own lyrics cache for non-Subsonic providers.
            playbackManager.playbackState
                .map { it.resolvedNextTrack() }
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .collectLatest { next ->
                    val nextId = next?.id?.toString()
                    _upNextSongId.value = nextId
                    if (next == null || nextId == null) return@collectLatest
                    if (_prefetchedLyrics.value.containsKey(nextId)) return@collectLatest
                    delay(UP_NEXT_PREFETCH_DELAY_MS)
                    var attempt = 0
                    while (true) {
                        val loaded = try {
                            repository.getLoadedLyrics(next.id, next.title, next.artist)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Transient (network / provider timeout): the outro
                            // is minutes away, so try again before giving up.
                            if (attempt < UP_NEXT_PREFETCH_RETRIES) {
                                attempt += 1
                                delay(UP_NEXT_PREFETCH_RETRY_MS * attempt)
                                continue
                            }
                            null
                        }
                        val lines = loaded?.lyrics?.toUiLyrics().orEmpty()
                        if (lines.isNotEmpty()) {
                            val entry = UpNextLyrics(
                                songId = nextId,
                                title = next.title.orEmpty(),
                                artist = next.artist.orEmpty(),
                                lines = lines,
                                providerName = loaded?.providerName,
                                providerSongId = loaded?.providerSongId,
                            )
                            _prefetchedLyrics.update { cache ->
                                (cache - nextId + (nextId to entry))
                                    .entries
                                    .toList()
                                    .takeLast(PREFETCHED_LYRICS_KEPT)
                                    .associate { it.toPair() }
                            }
                        }
                        break
                    }
                }
        }
    }

    private val ratingFlow = currentSongId.flatMapLatest { songId ->
        if (songId != null) {
            repository.getRating(songId).map { it?.rating ?: 0f }
        } else {
            flowOf(0f)
        }
    }

    // Authoritative saved-state from the library cache (Spotify), reactive so a
    // background sync or a confirmed favorite write keeps the heart correct.
    // null = not a cached Spotify track → fall back to [_isStarred] which is
    // updated by [toggleFavorite] on success, so the heart never reverts to a
    // stale playback-track flag (Spotify App Remote’s PlayerState does not
    // reflect live favorite changes).
    private val cachedFavoriteFlow: Flow<Boolean?> = currentSongId.flatMapLatest { songId ->
        if (songId != null) repository.observeSpotifyFavorite(songId) else flowOf(null)
    }

    private val favoriteFlow = combine(
        currentSongId,
        cachedFavoriteFlow,
        _isStarred,
        repository.favoriteOverrides,
    ) { songId, cachedFavorite, vmStarred, overrides ->
        // Override (the user's just-tapped intent) wins; then the cache (real
        // library state); then the ViewModel's own last-known state. The
        // override is cleared on success only AFTER the cache reflects it,
        // so no revert.
        songId?.let(overrides::get) ?: cachedFavorite ?: vmStarred
    }

    private val libraryMembershipFlow = currentSongId.flatMapLatest { songId ->
        if (songId == null) flowOf(null to LibraryMembership.Unknown)
        else repository.observeLibraryMembership(songId).map { songId to it }
    }

    private data class TrackActionsState(
        val isStarred: Boolean,
        val membershipTrackId: MediaId?,
        val membership: LibraryMembership,
        val workingTrackIds: Set<MediaId>,
    )

    private val trackActionsFlow = combine(
        favoriteFlow,
        libraryMembershipFlow,
        _libraryActions,
        repository.currentProfileIdFlow,
    ) { favorite, (membershipId, membership), operations, profileId ->
        val workingIds = operations.keys.filter { it.profileId == profileId }.mapTo(linkedSetOf()) { it.trackId }
        TrackActionsState(favorite, membershipId, membership, workingIds)
    }

    val notesState: StateFlow<List<SongNote>> = currentSongId
        .flatMapLatest { songId ->
            if (songId != null) {
                repository.observeNotes(songId)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Live About entries for the current song (canonical + ask rows). Uses
     * title + artist + album as the identity so the same song played from
     * a different profile / provider sees the same cached entries.
     */
    private val aboutEntriesFlow = playbackManager.playbackState
        .map { state ->
            val track = state.currentTrack
            if (track == null) {
                null
            } else {
                Triple(track.title.orEmpty(), track.artist.orEmpty(), track.album.orEmpty())
            }
        }
        .distinctUntilChanged()
        .flatMapLatest { trio ->
            if (trio == null) {
                flowOf(emptyList())
            } else {
                repository.observeAbout(trio.first, trio.second, trio.third)
            }
        }

    val aboutUiState: StateFlow<AboutUiState> = combine(
        aboutEntriesFlow,
        _aboutLoading,
        _aboutError,
    ) { entries, loading, error ->
        when {
            error != null -> error
            entries.isNotEmpty() -> AboutUiState.Ready(entries)
            loading -> AboutUiState.Loading
            else -> AboutUiState.Idle
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AboutUiState.Idle)

    private val _devicesState = MutableStateFlow(DevicesSheetState())
    val devicesState: StateFlow<DevicesSheetState> = _devicesState.asStateFlow()

    private val playbackAndContext = combine(
        // position/bufferedPosition tick 4×/s during playback, but [uiState]
        // no longer embeds them (the screen reads [positionMs]/[bufferedMs]
        // directly). Strip the ticking fields BEFORE the big combine so a
        // pure position tick dedupes right here instead of rebuilding — and
        // re-emitting — a fresh Playing instance 4×/s.
        playbackManager.playbackState
            .map { it.copy(position = 0L, bufferedPosition = 0L) }
            .distinctUntilChanged(),
        playbackManager.currentActivityContext,
    ) { state, ctx -> state to ctx }

    private val lyricsUiState = combine(
        _lyrics,
        _lyricsLoading,
        _showLyricsTranslation,
        _lyricsActionInFlight,
        _lyricsOwnerSongId,
    ) { lyrics, loading, showTranslation, actionInFlight, owner ->
        LyricsUiState(
            lyrics = lyrics,
            loading = loading,
            showTranslation = showTranslation,
            actionInFlight = actionInFlight,
            ownerSongId = owner,
        )
    }

    val uiState: StateFlow<NowPlayingUiState> = combine(
        playbackAndContext,
        lyricsUiState,
        ratingFlow,
        trackActionsFlow,
        preloadFlow,
    ) { (state, activityContext), lyricsState, rating, trackActions, preload ->
        val song = state.currentTrack
        val pending = state.pendingTrack
        val songKey = song?.id?.toString()
        val lyricsOwned = songKey != null && lyricsState.ownerSongId == songKey
        // The song just started but the lyrics flows haven't caught up yet:
        // its preloaded lyrics are already known — show them in that very
        // emission, so the outro hand-over never meets a loading frame.
        val preloaded = if (lyricsOwned) null else songKey?.let(preload.lyricsBySongId::get)
        when {
            state.connectionPhase == ConnectionPhase.Error && song != null ->
                NowPlayingUiState.ConnectError(
                    songTitle = song.title.orEmpty(),
                    artist = song.artist.orEmpty(),
                    coverArtUrl = repository.resolveCoverUrl(song.coverArt),
                    message = state.connectionErrorMessage?.let { UiText.Raw(it) }
                        ?: UiText.Res(R.string.np_msg_playback_interrupted),
                )

            song != null -> NowPlayingUiState.Playing(
                songTitle = song.title.orEmpty(),
                artist = song.artist.orEmpty(),
                albumName = song.album.orEmpty(),
                coverArtUrl = repository.resolveCoverUrl(song.coverArt),
                isPlaying = state.playWhenReady,
                durationMs = state.duration,
                songId = song.id.toString(),
                rating = rating,
                isStarred = trackActions.isStarred,
                libraryMembership = if (trackActions.membershipTrackId == song.id) trackActions.membership
                    else LibraryMembership.Unknown,
                libraryActionInFlight = song.id in trackActions.workingTrackIds,
                lyrics = when {
                    lyricsOwned -> lyricsState.lyrics
                    preloaded != null -> preloaded.lines
                    else -> emptyList()
                },
                showLyricsTranslation = lyricsState.showTranslation && lyricsOwned,
                lyricsActionInFlight = lyricsState.actionInFlight,
                lyricsLoading = if (lyricsOwned) lyricsState.loading else preloaded == null,
                // Never the playing song itself (repeat-all on a one-song queue).
                upNextLyrics = preload.upNextTimed?.takeIf { it.songId != song.id.toString() },
                queue = state.queue.mapIndexed { index, queueSong ->
                    QueueItem(
                        songId = queueSong.id.toString(),
                        title = queueSong.title.orEmpty(),
                        artist = queueSong.artist.orEmpty(),
                        coverArtUrl = repository.resolveCoverUrl(queueSong.coverArt),
                        entryId = state.queueEntryIds.getOrNull(index) ?: "i$index",
                        userQueued = index in state.userQueued,
                    )
                },
                currentQueueIndex = state.currentIndex,
                upcomingQueue = state.upcoming,
                queueEdit = state.queueEdit,
                playMode = state.playMode,
                albumId = song.albumId?.toString(),
                artistId = song.artistId?.toString(),
                activityContext = activityContext,
                serviceFeatures = ServiceFeatureCatalog.forProvider(song.id.provider),
            )

            // Backend is still handshaking for the track the user tapped —
            // show "about to play" UI, do NOT fake playing / progress.
            state.connectionPhase == ConnectionPhase.Connecting && pending != null ->
                NowPlayingUiState.Launching(
                    songTitle = pending.title.orEmpty(),
                    artist = pending.artist.orEmpty(),
                    albumName = pending.album.orEmpty(),
                    coverArtUrl = repository.resolveCoverUrl(pending.coverArt),
                    durationMs = pending.durationSec?.times(1_000L) ?: 0L,
                    hint = UiText.Res(R.string.np_msg_connecting_spotify),
                )

            // Backend refused / lost connection mid-launch — surface the
            // error with the track context so user knows what failed.
            state.connectionPhase == ConnectionPhase.Error && pending != null ->
                NowPlayingUiState.ConnectError(
                    songTitle = pending.title.orEmpty(),
                    artist = pending.artist.orEmpty(),
                    coverArtUrl = repository.resolveCoverUrl(pending.coverArt),
                    message = state.connectionErrorMessage?.let { UiText.Raw(it) }
                        ?: UiText.Res(R.string.np_msg_couldnt_start),
                )

            else -> NowPlayingUiState.Idle
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingUiState.Idle)

    /**
     * 4Hz playhead position, deliberately kept OUT of [uiState] (see
     * [playbackAndContext]) so only the leaves that render position — wave
     * progress bar, time labels, lyrics highlight — recompose per tick.
     *
     * Shared EAGERLY, not WhileSubscribed: the only collector lives inside the
     * Now Playing overlay, so with WhileSubscribed the flow went cold 5s after
     * a dismiss and cached the close-time position — reopening then composed
     * its first frame against that stale value (collectAsState always seeds
     * from StateFlow.value) and the lyric panes instant-anchored to the wrong
     * line before re-gliding. Eager upkeep is two field projections per 250ms
     * tick (and the ticker only runs while playing) — cheap insurance that
     * `.value` is never stale.
     */
    val positionMs: StateFlow<Long> = playbackManager.playbackState
        .map { it.position }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            playbackManager.playbackState.value.position,
        )

    /**
     * [positionMs] tagged with the song it belongs to, from the same emission
     * — what the overlay host hands the screen (through [SongScopedPosition])
     * so a song change can't show the drawn song the NEXT song's position for
     * the frames before uiState catches up. Eager for the same reason as
     * [positionMs].
     */
    val playhead: StateFlow<NowPlayingPlayhead> = playbackManager.playbackState
        .map { NowPlayingPlayhead(it.currentTrack?.id?.toString(), it.position) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            playbackManager.playbackState.value.let { state ->
                NowPlayingPlayhead(state.currentTrack?.id?.toString(), state.position)
            },
        )

    /** Buffered position, split out of [uiState] for the same reason as [positionMs]. */
    val bufferedMs: StateFlow<Long> = playbackManager.playbackState
        .map { it.bufferedPosition }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            playbackManager.playbackState.value.bufferedPosition,
        )

    /**
     * Live play/pause bit, split out of [uiState] for the same staleness
     * reason as [positionMs] — plus one more: [uiState] is a multi-source
     * combine, so after a cold resubscribe it can't emit until EVERY source
     * (lyrics, rating, favorite) has produced a value, and until then
     * collectAsState serves the cached close-time snapshot. The wave bar keys
     * wavy-vs-flat off isPlaying, so a stale `false` here while the eager
     * position ticked on rendered as "progress moving but the line stays
     * flat". The overlay host overrides Playing.isPlaying with this value.
     * It reads playWhenReady, like Playing.isPlaying: a seek's buffering dip
     * must not flash the label to PLAY (2026-10-05 device QA).
     */
    val isPlayingLive: StateFlow<Boolean> = playbackManager.playbackState
        .map { it.playWhenReady }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            playbackManager.playbackState.value.playWhenReady,
        )

    fun togglePlayPause() {
        val state = playbackManager.playbackState.value
        // What the button shows: PAUSE through a buffering dip pauses.
        if (state.playWhenReady) playbackManager.pause() else playbackManager.resume()
    }

    /** Repeat all → shuffle → repeat one → repeat all, from what the player reports. */
    fun cyclePlayMode() {
        playbackManager.setPlayMode(playbackManager.playbackState.value.playMode.next())
    }

    /**
     * +1 = moved forward (tap skip-next, or auto-advance default), −1 = back.
     * Only a display hint for the Now Playing cover's directional ride-in.
     * −1 belongs to the one song change a PREVIOUS tap causes: it returns to
     * +1 once that change has been taken ([SKIP_DIRECTION_HOLD_MS] after it
     * lands), or when the tap expires without one (a restart, or nothing
     * before the first song) — so a later auto-advance rides in forward.
     */
    private val _skipDirection = MutableStateFlow(1)
    val skipDirection: StateFlow<Int> = _skipDirection.asStateFlow()
    private var skipDirectionReset: Job? = null

    fun skipNext() {
        skipDirectionReset?.cancel()
        _skipDirection.value = 1
        playbackManager.skipNext()
    }

    fun skipPrevious() {
        _skipDirection.value = -1
        armSkipDirectionReset()
        playbackManager.skipPrevious()
    }

    /**
     * Waits (as long as a transport tap is trusted, [TransportTapValidityMs])
     * for the song to change away from the one tapped on, holds −1 while the
     * new cover composes with it, then goes back to +1. No change in time —
     * the player restarted the song or had nothing before it — resets at once.
     */
    private fun armSkipDirectionReset() {
        val tappedOn = playbackManager.playbackState.value.currentTrack?.id
        skipDirectionReset?.cancel()
        skipDirectionReset = viewModelScope.launch {
            val changed = withTimeoutOrNull(TransportTapValidityMs) {
                playbackManager.playbackState.first { state ->
                    val id = state.currentTrack?.id
                    id != null && id != tappedOn
                }
            }
            if (changed != null) delay(SKIP_DIRECTION_HOLD_MS)
            _skipDirection.value = 1
        }
    }

    fun seekTo(fraction: Float) {
        val durationMs = playbackManager.playbackState.value.duration
        if (durationMs > 0) {
            playbackManager.seekTo((fraction.coerceIn(0f, 1f) * durationMs).toLong())
        }
    }

    /** Seek directly to an absolute position. Used by tap-to-seek on lyric lines. */
    fun seekToMs(positionMs: Long) {
        val durationMs = playbackManager.playbackState.value.duration
        val target = positionMs.coerceAtLeast(0L)
        val clamped = if (durationMs > 0) target.coerceAtMost(durationMs) else target
        playbackManager.seekTo(clamped)
    }

    private var lyricsSearchJob: Job? = null

    fun openLyricsSearch() {
        val song = playbackManager.playbackState.value.currentTrack ?: return
        val query = listOf(song.title.orEmpty(), song.artist.orEmpty())
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString(" ")
        if (query.isBlank()) {
            _addToPlaylistMessages.tryEmit(UiText.Res(R.string.np_msg_need_title_artist))
            return
        }
        _lyricsSearchState.value = LyricsSearchState(
            isOpen = true,
            query = query,
            providers = emptyLyricsSearchProviders(),
        )
        searchLyrics(query)
    }

    fun updateLyricsSearchQuery(query: String) {
        _lyricsSearchState.value = _lyricsSearchState.value.copy(
            query = query,
            errorMessage = null,
        )
    }

    fun searchLyrics(query: String = _lyricsSearchState.value.query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            lyricsSearchJob?.cancel()
            _lyricsSearchState.value = _lyricsSearchState.value.copy(
                query = query,
                loading = false,
                providers = emptyLyricsSearchProviders(),
                errorMessage = null,
            )
            return
        }
        lyricsSearchJob?.cancel()
        _lyricsSearchState.value = _lyricsSearchState.value.copy(
            isOpen = true,
            query = trimmed,
            loading = true,
            providers = emptyLyricsSearchProviders(),
            errorMessage = null,
        )
        lyricsSearchJob = viewModelScope.launch {
            repository.searchLyricsProviderSections(trimmed)
                .onSuccess { sections ->
                    if (!_lyricsSearchState.value.isOpen ||
                        _lyricsSearchState.value.query != trimmed
                    ) {
                        return@onSuccess
                    }
                    _lyricsSearchState.value = _lyricsSearchState.value.copy(
                        loading = false,
                        providers = sections.map { it.toUi() },
                        errorMessage = null,
                    )
                }
                .onFailure { error ->
                    _lyricsSearchState.value = _lyricsSearchState.value.copy(
                        loading = false,
                        providers = emptyLyricsSearchProviders(),
                        errorMessage = userMessage(error.message, R.string.np_msg_couldnt_search),
                    )
                }
        }
    }

    fun applyLyricsSearchResult(candidate: LyricsSearchResultUi) {
        val songId = currentSongId.value ?: return
        if (_lyricsSearchState.value.applyingCandidateKey != null ||
            _lyricsActionInFlight.value != null
        ) {
            return
        }
        _lyricsSearchState.value = _lyricsSearchState.value.copy(
            applyingCandidateKey = candidate.stableKey,
            errorMessage = null,
        )
        _lyricsActionInFlight.value = LyricsAction.Search
        viewModelScope.launch {
            repository.applyLyricsSearchResult(
                trackId = songId,
                providerName = candidate.providerName,
                songId = candidate.songId,
            )
                .onSuccess { result ->
                    applyLyricsResult(result.lyrics, result.providerName, result.providerSongId)
                    _lyricsSearchState.value = LyricsSearchState()
                    _addToPlaylistMessages.tryEmit(lyricsAppliedFrom(result.providerName))
                }
                .onFailure { error ->
                    _lyricsSearchState.value = _lyricsSearchState.value.copy(
                        applyingCandidateKey = null,
                        errorMessage = userMessage(error.message, R.string.np_msg_couldnt_apply),
                    )
                    _addToPlaylistMessages.tryEmit(userMessage(error.message, R.string.np_msg_couldnt_apply))
                }
            _lyricsActionInFlight.value = null
        }
    }

    fun dismissLyricsSearch() {
        lyricsSearchJob?.cancel()
        _lyricsSearchState.value = _lyricsSearchState.value.copy(
            isOpen = false,
            loading = false,
            applyingCandidateKey = null,
        )
    }

    private fun emptyLyricsSearchProviders(): List<LyricsSearchProviderUi> =
        repository.lyricsProviderNames().map { providerName ->
            LyricsSearchProviderUi(providerName = providerName)
        }

    fun applyLyrics(rawLrc: String) {
        val songId = currentSongId.value ?: return
        if (_lyricsActionInFlight.value != null) return
        _lyricsActionInFlight.value = LyricsAction.Apply
        viewModelScope.launch {
            repository.applyLyrics(trackId = songId, rawLrc = rawLrc)
                .onSuccess { result ->
                    applyLyricsResult(result.lyrics, result.providerName, result.providerSongId)
                    _addToPlaylistMessages.tryEmit(UiText.Res(R.string.np_msg_lyrics_applied))
                }
                .onFailure { error ->
                    _addToPlaylistMessages.tryEmit(userMessage(error.message, R.string.np_msg_couldnt_apply))
                }
            _lyricsActionInFlight.value = null
        }
    }

    fun translateLyrics() {
        val song = playbackManager.playbackState.value.currentTrack ?: return
        val currentLyrics = _lyrics.value
        if (currentLyrics.isEmpty() || _lyricsActionInFlight.value != null) return

        if (currentLyrics.any { !it.translation.isNullOrBlank() }) {
            _showLyricsTranslation.value = !_showLyricsTranslation.value
            return
        }

        _lyricsActionInFlight.value = LyricsAction.Translate
        viewModelScope.launch {
            when (
                val result = repository.translateLyrics(
                    trackId = song.id,
                    title = song.title,
                    artist = song.artist,
                    lines = currentLyrics.map { it.text },
                    currentLyricsProviderName = _currentLyricsProviderName.value,
                    currentLyricsProviderSongId = _currentLyricsProviderSongId.value,
                )
            ) {
                is YoinRepository.LyricsTranslationResult.ProviderSwitchAvailable -> {
                    pendingLyricsTranslationSwitchOffer = result.offer
                    _lyricsTranslationSwitchOffers.tryEmit(
                        LyricsTranslationSwitchOfferUi(
                            providerName = result.offer.providerName,
                        ),
                    )
                }
                is YoinRepository.LyricsTranslationResult.AlreadyTargetLanguage ->
                    _addToPlaylistMessages.tryEmit(
                        UiText.Res(R.string.np_msg_already_language, listOf(result.targetLanguage)),
                    )
                YoinRepository.LyricsTranslationResult.ApiKeyMissing ->
                    _addToPlaylistMessages.tryEmit(UiText.Res(R.string.np_ask_api_key_missing))
                is YoinRepository.LyricsTranslationResult.Error ->
                    _addToPlaylistMessages.tryEmit(UiText.Raw(result.message))
                is YoinRepository.LyricsTranslationResult.Success -> {
                    val baseLyrics = result.lyrics?.toUiLyrics() ?: currentLyrics
                    _lyrics.value = baseLyrics.mapIndexed { index, line ->
                        line.copy(translation = result.translations[index])
                    }
                    result.providerName?.let { providerName ->
                        _currentLyricsProviderName.value = providerName
                    }
                    result.providerSongId?.let { providerSongId ->
                        _currentLyricsProviderSongId.value = providerSongId
                    }
                    _showLyricsTranslation.value = true
                    _addToPlaylistMessages.tryEmit(UiText.Res(R.string.np_msg_lyrics_translated))
                }
            }
            _lyricsActionInFlight.value = null
        }
    }

    fun applyLyricsTranslationSwitchOffer() {
        val song = playbackManager.playbackState.value.currentTrack ?: return
        val offer = pendingLyricsTranslationSwitchOffer ?: return
        if (_lyricsActionInFlight.value != null) return
        _lyricsActionInFlight.value = LyricsAction.Translate
        viewModelScope.launch {
            repository.applyLyricsTranslationProviderSwitch(song.id, offer)
            _lyrics.value = offer.lyrics.toUiLyrics().mapIndexed { index, line ->
                line.copy(translation = offer.translations[index])
            }
            _currentLyricsProviderName.value = offer.providerName
            _currentLyricsProviderSongId.value = offer.providerSongId
            _showLyricsTranslation.value = true
            pendingLyricsTranslationSwitchOffer = null
            _lyricsActionInFlight.value = null
            _addToPlaylistMessages.tryEmit(lyricsTranslatedFrom(offer.providerName))
        }
    }

    fun setRating(rating: Float) {
        val songId = currentSongId.value ?: return
        viewModelScope.launch {
            repository.setRating(songId, rating)
        }
    }

    fun toggleFavorite() {
        val track = playbackManager.playbackState.value.currentTrack ?: return
        val songId = track.id
        // A track started externally via Spotify App Remote can arrive without a
        // resolvable id (blank uri → this sentinel). Saving it would PUT a bogus
        // id and 4xx-fail, which used to just silently revert the optimistic
        // heart — looking like the tap "did nothing". Refuse with a reason instead.
        if (songId.rawId.isBlank() || songId.rawId == REMOTE_UNKNOWN_TRACK_ID) {
            _addToPlaylistMessages.tryEmit(UiText.Res(R.string.np_msg_cant_save))
            return
        }
        val currentFavorite = (uiState.value as? NowPlayingUiState.Playing)?.isStarred
            ?: _isStarred.value
        val nextFavorite = !currentFavorite
        viewModelScope.launch {
            repository.setFavorite(track, nextFavorite)
                .onSuccess {
                    _isStarred.value = nextFavorite
                }
                .onFailure { error ->
                    // The optimistic heart has already reverted in the repository;
                    // surface WHY instead of leaving a silent no-op (the symptom
                    // was "heart bounces but nothing sticks").
                    _addToPlaylistMessages.tryEmit(
                        userMessage(
                            error.message,
                            if (nextFavorite) {
                                R.string.np_msg_couldnt_save_favorite
                            } else {
                                R.string.np_msg_couldnt_remove_favorite
                            },
                        ),
                    )
                }
        }
    }

    fun addCurrentToLibrary() {
        val track = playbackManager.playbackState.value.currentTrack ?: return
        val profileId = repository.currentProfileId() ?: return
        val key = LibraryActionKey(profileId, track.id)
        if (Capability.LIBRARY_ADD !in repository.currentCapabilities() || key in _libraryActions.value) return
        if ((uiState.value as? NowPlayingUiState.Playing)?.libraryMembership == LibraryMembership.Added) return
        val operation = Any()
        _libraryActions.update { it + (key to operation) }
        viewModelScope.launch {
            try {
                repository.addToLibrary(track)
                    .onSuccess { membership ->
                        if (repository.currentProfileId() != profileId) return@onSuccess
                        _addToPlaylistMessages.tryEmit(
                            if (membership == LibraryMembership.Added) {
                                UiText.Res(R.string.np_msg_added_apple)
                            } else {
                                UiText.Res(R.string.np_msg_apple_pending)
                            },
                        )
                    }
                    .onFailure { error ->
                        if (repository.currentProfileId() != profileId) return@onFailure
                        _addToPlaylistMessages.tryEmit(
                            userMessage(error.message, R.string.np_msg_couldnt_add_apple),
                        )
                    }
            } finally {
                _libraryActions.update { operations ->
                    if (operations[key] === operation) operations - key else operations
                }
            }
        }
    }

    /**
     * Now Playing's note draft, held here so the words also outlive closing
     * Now Playing (the screen composes only while it's open). Snapshot state,
     * not a StateFlow, on purpose: a text field must read its value
     * synchronously, or IME composition glitches. Pass it to
     * [NowPlayingScreen]'s `noteDraft`.
     */
    val noteDraft = NoteDraftState()

    // The profile [noteDraft] is being written under. Profiles are independent
    // data sources: a draft that outlives closing Now Playing must not outlive
    // a profile switch, or its song id would be filed under the next profile.
    private var noteDraftProfileId: String? = repository.currentProfileId()

    // After [noteDraft]: an init block above it would run first and, on the
    // immediate main dispatcher, touch the draft before it exists.
    init {
        viewModelScope.launch {
            repository.currentProfileIdFlow.collect { profileId ->
                if (profileId != noteDraftProfileId) {
                    noteDraftProfileId = profileId
                    noteDraft.discard()
                }
            }
        }
    }

    /**
     * Files a note under the song its draft was STARTED on ([NoteSaveRequest.target])
     * with the moment captured then — not whatever plays when 记下 is pressed:
     * a skip mid-sentence must not move the note (or pair the next song with
     * the previous song's anchor). A request written under another profile —
     * a switch the draft hasn't been discarded for yet — is refused.
     */
    fun saveCurrentNote(request: NoteSaveRequest) {
        if (request.content.isBlank()) return
        if (repository.currentProfileId() != noteDraftProfileId) return
        val track = resolveNoteTrack(request.target, playbackManager.playbackState.value) ?: return
        viewModelScope.launch {
            repository.addNote(track, request.content, request.anchorMs)
        }
    }

    fun deleteNote(id: String) {
        viewModelScope.launch {
            repository.deleteNoteById(id)
        }
    }

    /**
     * The Note page's "对齐到现在": files [note] at a new song moment. Words,
     * id and creation time stay; [positionMs] replaces the anchor.
     */
    fun realignNote(note: SongNote, positionMs: Long) {
        val content = note.content.trim()
        if (content.isEmpty()) return
        viewModelScope.launch {
            // updateNote writes the whole row it is given, the new anchor with it.
            repository.updateNote(note.copy(positionMs = positionMs.coerceAtLeast(0L)), content)
        }
    }

    fun refreshDevices() {
        refreshDevices(showLoading = true)
    }

    private fun refreshDevices(showLoading: Boolean) {
        viewModelScope.launch {
            val providerId = repository.currentProviderId()
            val castState = castManager.castState.value
            val current = _devicesState.value
            val currentDevices = current.devices.takeIf { current.providerId == providerId }.orEmpty()
            _devicesState.value = _devicesState.value.copy(
                providerId = providerId,
                devices = currentDevices.ifEmpty { fallbackDevices(providerId, castState) },
                loading = showLoading,
                errorMessage = null,
            )
            runCatching {
                when (providerId) {
                    MediaId.PROVIDER_SPOTIFY -> repository.listSpotifyDevices()
                    else -> emptyList()
                }
            }.onSuccess { spotifyDevices ->
                val nextDevices = buildDevices(providerId, spotifyDevices, castState)
                val latestDevices = _devicesState.value.devices
                    .takeIf { _devicesState.value.providerId == providerId }
                    .orEmpty()
                _devicesState.value = DevicesSheetState(
                    providerId = providerId,
                    devices = mergeDevicesForStableRows(
                        previous = latestDevices,
                        next = nextDevices,
                    ),
                    loading = false,
                )
            }.onFailure { error ->
                val latestDevices = _devicesState.value.devices
                    .takeIf { _devicesState.value.providerId == providerId }
                    .orEmpty()
                _devicesState.value = DevicesSheetState(
                    providerId = providerId,
                    devices = latestDevices.ifEmpty { fallbackDevices(providerId, castState) },
                    loading = false,
                    // 403 here = token minted before user-read-playback-state
                    // joined SCOPES (it's not in REQUIRED_SCOPES, so no forced
                    // reconnect) — say what actually fixes it.
                    errorMessage = if (error is SpotifyAuthException && error.code == 403) {
                        UiText.Res(R.string.np_msg_spotify_devices_permission)
                    } else {
                        userMessage(error.message, R.string.np_msg_couldnt_load_devices)
                    },
                )
            }
        }
    }

    fun selectDevice(device: YoinDevice) {
        if (!device.isSelectable) return
        viewModelScope.launch {
            _devicesState.value = _devicesState.value.copy(busyDeviceId = device.id)
            val result = runCatching {
                when (device) {
                    is YoinDevice.SpotifyConnect ->
                        repository.transferSpotifyPlayback(device.id)
                    is YoinDevice.LocalPlayback,
                    is YoinDevice.Chromecast -> Unit
                }
            }
            if (result.isSuccess) {
                // Optimistically flip `isActive` to the target right away.
                // Spotify's `GET /v1/me/player/devices` endpoint typically
                // lags the transfer by 1–2 s, and polling in that window
                // returns the stale active device — which reads to the
                // user as "nothing happened". We trust our PUT succeeded
                // and wait a beat before refreshing for the real list.
                _devicesState.value = _devicesState.value.copy(
                    busyDeviceId = null,
                    devices = _devicesState.value.devices.map { row ->
                        when (row) {
                            is YoinDevice.SpotifyConnect ->
                                row.copy(isActive = row.id == device.id)
                            else -> row
                        }
                    },
                )
                delay(1_200)
            } else {
                _devicesState.value = _devicesState.value.copy(
                    busyDeviceId = null,
                    errorMessage = userMessage(
                        result.exceptionOrNull()?.message,
                        R.string.np_msg_couldnt_switch_devices,
                    ),
                )
            }
            refreshDevices(showLoading = false)
        }
    }

    // ── Add-to-playlist (long-press on ❤️) ─────────────────────────────
    //
    // The sheet has a single entry point: user long-presses the heart, we
    // snapshot the current song id into [addToPlaylistTarget], the
    // composable observes the non-null value and opens the sheet. When the
    // user picks a playlist or cancels, we null the target again.
    //
    // [writablePlaylists] refetches whenever the target changes from null
    // to non-null — the sheet always shows fresh data even if the user
    // created a playlist elsewhere in the same session.

    private val _addToPlaylistTarget = MutableStateFlow<List<MediaId>?>(null)
    val addToPlaylistTarget: StateFlow<List<MediaId>?> = _addToPlaylistTarget.asStateFlow()

    private val _addToPlaylistMessages = MutableSharedFlow<UiText>(extraBufferCapacity = 4)
    /** One-shot confirmations / errors for the shell SnackbarHost to surface. */
    val addToPlaylistMessages: SharedFlow<UiText> = _addToPlaylistMessages.asSharedFlow()

    val writablePlaylists: StateFlow<List<AddToPlaylistRow>> = _addToPlaylistTarget
        .flatMapLatest { target ->
            if (target == null) flowOf(emptyList())
            else flowOf(fetchWritablePlaylists())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private suspend fun fetchWritablePlaylists(): List<AddToPlaylistRow> =
        runCatching { repository.getPlaylists() }
            .getOrDefault(emptyList())
            .filter { it.canWrite }
            .map { playlist ->
                AddToPlaylistRow(
                    id = playlist.id,
                    name = playlist.name,
                    songCount = playlist.songCount,
                    coverArtUrl = repository.resolveCoverUrl(playlist.coverArt),
                )
            }

    /** A one-line confirmation on this window's snackbar (a detail page's ▾ actions). */
    fun postMessage(message: String) {
        _addToPlaylistMessages.tryEmit(UiText.Raw(message))
    }

    fun requestAddTracksToPlaylist(trackIds: List<MediaId>) {
        val distinctTargets = trackIds.distinct()
        if (distinctTargets.isEmpty()) return
        _addToPlaylistTarget.value = distinctTargets
    }

    fun requestAddCurrentToPlaylist() {
        val songId = currentSongId.value ?: return
        requestAddTracksToPlaylist(listOf(songId))
    }

    fun dismissAddToPlaylistSheet() {
        _addToPlaylistTarget.value = null
    }

    fun addTargetsToExistingPlaylist(playlistId: MediaId) {
        val targets = _addToPlaylistTarget.value ?: return
        val playlistName = writablePlaylists.value.firstOrNull { it.id == playlistId }?.name
        _addToPlaylistTarget.value = null
        viewModelScope.launch {
            repository.addTracksToPlaylist(playlistId, targets)
                .onSuccess {
                    onPlaylistMutated()
                    _addToPlaylistMessages.tryEmit(addedTo(playlistName))
                }
                .onFailure {
                    _addToPlaylistMessages.tryEmit(couldntAddTo(it.message, playlistName))
                }
        }
    }

    fun createPlaylistAndAddTargets(name: String) {
        val targets = _addToPlaylistTarget.value ?: return
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            _addToPlaylistTarget.value = null
            return
        }
        _addToPlaylistTarget.value = null
        viewModelScope.launch {
            repository.createPlaylist(trimmedName)
                .onFailure {
                    _addToPlaylistMessages.tryEmit(
                        userMessage(it.message, R.string.np_msg_couldnt_create, listOf(trimmedName)),
                    )
                }
                .onSuccess { playlist ->
                    // Best effort — even if addTracks fails the empty playlist
                    // still exists, which matches user intent better than
                    // silently rolling back the create.
                    repository.addTracksToPlaylist(playlist.id, targets)
                        .onSuccess {
                            onPlaylistMutated()
                            _addToPlaylistMessages.tryEmit(addedTo(trimmedName))
                        }
                        .onFailure {
                            onPlaylistMutated()
                            _addToPlaylistMessages.tryEmit(
                                userMessage(it.message, R.string.np_msg_created_but_failed, listOf(trimmedName)),
                            )
                        }
                }
        }
    }

    fun skipToQueueItem(index: Int) {
        playbackManager.skipToQueueItem(index)
    }

    fun moveQueueItem(from: Int, to: Int) = playbackManager.moveQueueItem(from, to)

    fun removeQueueItem(index: Int) = playbackManager.removeQueueItem(index)

    fun clearUserQueue() = playbackManager.clearUserQueue()

    fun setStageMode(mode: NowPlayingStageMode) {
        _stageMode.value = mode
    }

    fun stepBackStage(): Boolean {
        return when (_stageMode.value) {
            NowPlayingStageMode.Expanded -> {
                _stageMode.value = NowPlayingStageMode.Compact
                true
            }
            NowPlayingStageMode.Immersive,
            NowPlayingStageMode.Compact -> false
        }
    }

    fun setDetailPage(page: NowPlayingDetailPage) {
        _detailPage.value = page
    }

    /** A one-line confirmation from the player UI (e.g. lyrics copied below Android 13). */
    fun showMessage(message: String) {
        _addToPlaylistMessages.tryEmit(UiText.Raw(message))
    }

    fun setMediumFullscreen(fullscreen: Boolean) {
        _mediumFullscreen.value = fullscreen
    }

    /**
     * Claims today's single "tap to expand" lyric hint (断点交接 §3.1: at most
     * once a day, by local date). True exactly once per day; the claim is
     * persisted immediately so a second window / process can't show it again.
     */
    fun claimLyricIdleHint(today: java.time.LocalDate = java.time.LocalDate.now()): Boolean {
        if (!lyricIdleHintAllowed(lyricHintStore.lastShownEpochDay(), today)) return false
        lyricHintStore.markShown(today.toEpochDay())
        return true
    }

    // Compact pager AND fullscreen pager both hit `onAboutOpened` when
    // their LaunchedEffects fire during the same frame — without an
    // in-flight guard we issue two concurrent Gemini calls, both see an
    // empty cache, and both write a canonical row set. Tracking the
    // active Job lets us short-circuit the second caller.
    private var canonicalFetchJob: Job? = null

    /**
     * First-time hook for About. Call when the user lands on the About tab
     * (compact or fullscreen). No-op when canonical rows are already
     * cached OR a canonical fetch is already in flight; otherwise issues
     * a single Grounded Gemini fetch.
     */
    fun onAboutOpened() {
        if (canonicalFetchJob?.isActive == true) return
        canonicalFetchJob = viewModelScope.launch { fetchCanonicalAbout(retry = false) }
    }

    /**
     * Explicit user-initiated retry. Overwrites existing canonical rows.
     * Cancels any pending passive fetch first — the user is asking for a
     * fresh answer, they shouldn't race an already-stale call.
     */
    fun retryFetchSongInfo() {
        canonicalFetchJob?.cancel()
        canonicalFetchJob = viewModelScope.launch { fetchCanonicalAbout(retry = true) }
    }

    private suspend fun fetchCanonicalAbout(retry: Boolean) {
        val song = playbackManager.playbackState.value.currentTrack ?: return
        val title = song.title.orEmpty()
        val artist = song.artist.orEmpty()
        val album = song.album.orEmpty()
        if (title.isBlank() || artist.isBlank()) return

        _aboutLoading.value = true
        _aboutError.value = null
        try {
            when (
                val result = repository.ensureCanonicalAbout(
                    title = title,
                    artist = artist,
                    album = album,
                    retry = retry,
                )
            ) {
                is YoinRepository.AboutLoadResult.Success -> _aboutError.value = null
                YoinRepository.AboutLoadResult.ApiKeyMissing -> {
                    _aboutError.value = AboutUiState.ApiKeyMissing
                }
                is YoinRepository.AboutLoadResult.Error -> {
                    _aboutError.value = AboutUiState.Error(result.message)
                }
            }
        } finally {
            _aboutLoading.value = false
        }
    }

    /** Called by the Ask Gemini bar when it gains text-field focus. */
    fun onAskBarFocused() {
        if (_askState.value !is AskBarState.Loading) {
            _askState.value = AskBarState.Focused
        }
    }

    fun dismissAskError() {
        if (_askState.value is AskBarState.Error) {
            _askState.value = AskBarState.Idle
        }
    }

    /**
     * User dismissed the keyboard without submitting (swipe-down, back
     * button, tapping outside the IME). Contract at the call site: only
     * fire when IME has actually transitioned from visible to hidden, so
     * the initial compose when Focused hasn't opened the IME yet doesn't
     * bounce state straight back to Idle.
     */
    fun onAskBarCollapseRequested() {
        if (_askState.value is AskBarState.Focused) {
            _askState.value = AskBarState.Idle
        }
    }

    /**
     * Submit a free-form Ask Gemini question for the current song. On
     * success, the observed About flow will emit the new row automatically;
     * this method only owns the transient [AskBarState] transitions.
     */
    fun askQuestion(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return
        val song = playbackManager.playbackState.value.currentTrack ?: return

        val requestId = ++askRequestId
        _askState.value = AskBarState.Loading(title = trimmed, requestId = requestId)
        viewModelScope.launch {
            val titleResult = repository.generateAskTitle(
                title = song.title.orEmpty(),
                artist = song.artist.orEmpty(),
                album = song.album.orEmpty(),
                question = trimmed,
            )
            if (titleResult is YoinRepository.AskTitleResult.Success) {
                val current = _askState.value
                if (current is AskBarState.Loading && current.requestId == requestId) {
                    _askState.value = current.copy(title = titleResult.title)
                }
            }
        }
        viewModelScope.launch {
            val result = repository.askAboutSong(
                title = song.title.orEmpty(),
                artist = song.artist.orEmpty(),
                album = song.album.orEmpty(),
                question = trimmed,
            )
            _askState.value = when (result) {
                is YoinRepository.AskAboutResult.Success -> AskBarState.Idle
                YoinRepository.AskAboutResult.ApiKeyMissing ->
                    AskBarState.Error(UiText.Res(R.string.np_ask_api_key_missing))
                is YoinRepository.AskAboutResult.Error ->
                    AskBarState.Error(UiText.Raw(result.message))
            }
        }
    }

    private suspend fun loadLyrics(songId: MediaId, title: String?, artist: String?) {
        // 调用方（collectLatest 入口）已经把 _lyrics 清空 + loading=true。这里只在
        // 实际完成 / 失败时把 loading 关掉。若被 collectLatest 取消，让
        // CancellationException 透传，并且**不**碰 loading —— 紧接着新的 handler
        // 会把 loading 再次设成 true，避免中间闪一下 "No lyrics available"。
        try {
            when (val loadedLyrics = repository.getLoadedLyrics(songId, title, artist)) {
                null -> {
                    _lyrics.value = emptyList()
                    _lyricsLoading.value = false
                    _showLyricsTranslation.value = false
                    _currentLyricsProviderName.value = null
                    _currentLyricsProviderSongId.value = null
                }
                else -> applyLyricsResult(
                    loadedLyrics.lyrics,
                    loadedLyrics.providerName,
                    loadedLyrics.providerSongId,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            _lyrics.value = emptyList()
            _lyricsLoading.value = false
            _showLyricsTranslation.value = false
            _currentLyricsProviderName.value = null
            _currentLyricsProviderSongId.value = null
        }
    }

    private fun applyLyricsResult(
        lyrics: SourceLyrics,
        providerName: String?,
        providerSongId: String?,
    ) {
        _lyrics.value = lyrics.toUiLyrics()
        _lyricsLoading.value = false
        _showLyricsTranslation.value = false
        _currentLyricsProviderName.value = providerName
        _currentLyricsProviderSongId.value = providerSongId
        pendingLyricsTranslationSwitchOffer = null
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NowPlayingViewModel(
                playbackManager = container.playbackManager,
                repository = container.repository,
                castManager = container.castManager,
                onPlaylistMutated = container::notifyPlaylistMutation,
                lyricHintStore = container.lyricHintStore,
            ) as T
    }
}

data class DevicesSheetState(
    val providerId: String? = null,
    val devices: List<YoinDevice> = emptyList(),
    val loading: Boolean = false,
    val busyDeviceId: String? = null,
    val errorMessage: UiText? = null,
)

/**
 * The song that plays after the current one, as far as Yoin can tell.
 * [PlaybackState.nextTrack] when the backend resolves it (Media3: shuffle order
 * and repeat-all included). Delegated backends (Spotify App Remote) report
 * none; their queue is the order Yoin started, which predicts the next song
 * only without shuffle or repeat-one — anything else stays unknown rather than
 * guessed (a wrong guess would preview the wrong song's lyrics).
 */
internal fun PlaybackState.resolvedNextTrack(): Track? {
    nextTrack?.let { return it }
    if (shuffleEnabled || repeatMode == Player.REPEAT_MODE_ONE) return null
    val current = currentTrack ?: return null
    if (queue.getOrNull(currentIndex)?.id != current.id) return null
    val next = queue.getOrNull(currentIndex + 1)
        ?: queue.firstOrNull().takeIf { repeatMode == Player.REPEAT_MODE_ALL }
    return next?.takeIf { it.id != current.id }
}

private fun SourceLyrics.toUiLyrics(): List<LyricLine> = when (this) {
    is SourceLyrics.Synced -> lines.map { syncedLine ->
        LyricLine(
            startMs = syncedLine.startMs,
            text = syncedLine.text,
        )
    }
    is SourceLyrics.Unsynced -> text.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { line -> LyricLine(startMs = null, text = line) }
        .toList()
}

private fun YoinRepository.LyricsSearchCandidate.toUi(): LyricsSearchResultUi =
    LyricsSearchResultUi(
        providerName = providerName,
        songId = songId,
        title = title,
        artist = artist,
    )

private fun YoinRepository.LyricsSearchProviderSection.toUi(): LyricsSearchProviderUi =
    LyricsSearchProviderUi(
        providerName = providerName,
        results = candidates.map { it.toUi() },
        errorMessage = errorMessage,
    )

private data class LyricsUiState(
    val lyrics: List<LyricLine>,
    val loading: Boolean,
    val showTranslation: Boolean,
    val actionInFlight: LyricsAction?,
    val ownerSongId: String?,
)

private fun buildDevices(
    providerId: String?,
    spotifyDevices: List<YoinDevice.SpotifyConnect>,
    castState: CastState,
): List<YoinDevice> = when (providerId) {
    MediaId.PROVIDER_SPOTIFY -> spotifyDevices
    else -> fallbackDevices(providerId, castState)
}

private fun mergeDevicesForStableRows(
    previous: List<YoinDevice>,
    next: List<YoinDevice>,
): List<YoinDevice> {
    if (previous.isEmpty()) return next
    if (next.isEmpty()) return previous
    val nextById = next.associateBy(YoinDevice::id)
    val preserved = previous.mapNotNull { row -> nextById[row.id] }
    val newRows = next.filterNot { row -> previous.any { it.id == row.id } }
    return preserved + newRows
}

private fun fallbackDevices(
    providerId: String?,
    castState: CastState,
): List<YoinDevice> = when (providerId) {
    MediaId.PROVIDER_SUBSONIC, MediaId.PROVIDER_LOCAL, null -> buildList {
        add(
            YoinDevice.LocalPlayback(
                isActive = castState !is CastState.Connected,
                isSelectable = false,
                statusText = if (castState is CastState.Connected) {
                    NpDeviceStatusSwitchBack
                } else {
                    null
                },
            ),
        )
        when (castState) {
            is CastState.Connected -> add(
                YoinDevice.Chromecast(
                    id = "cast-connected",
                    name = castState.deviceName,
                    isActive = true,
                    statusText = NpDeviceStatusConnected,
                ),
            )
            CastState.Available -> add(
                YoinDevice.Chromecast(
                    id = "cast-available",
                    name = "Chromecast",
                    isActive = false,
                    statusText = NpDeviceStatusUsePill,
                ),
            )
            CastState.NotAvailable -> Unit
        }
    }
    else -> emptyList()
}

/** English keys stored on [YoinDevice.statusText]; the sheet resolves them. */
internal const val NpDeviceStatusSwitchBack = "Not casting"
internal const val NpDeviceStatusConnected = "Casting"
internal const val NpDeviceStatusUsePill = "Not casting"

private fun userMessage(
    raw: String?,
    @StringRes fallback: Int,
    args: List<Any> = emptyList(),
): UiText = raw?.takeIf { it.isNotBlank() }?.let { UiText.Raw(it) } ?: UiText.Res(fallback, args)

private fun addedTo(name: String?): UiText =
    if (name.isNullOrBlank()) {
        UiText.Res(R.string.np_msg_added_to_playlist)
    } else {
        UiText.Res(R.string.np_msg_added_to, listOf(name))
    }

private fun couldntAddTo(raw: String?, name: String?): UiText =
    if (name.isNullOrBlank()) {
        userMessage(raw, R.string.np_msg_couldnt_add_to_playlist)
    } else {
        userMessage(raw, R.string.np_msg_couldnt_add_to, listOf(name))
    }

private fun lyricsAppliedFrom(providerName: String): UiText = when (providerName) {
    "qq" -> UiText.Res(R.string.np_msg_lyrics_applied_qq)
    "netease" -> UiText.Res(R.string.np_msg_lyrics_applied_netease)
    "huawei" -> UiText.Res(R.string.np_msg_lyrics_applied_huawei)
    "lrclib" -> UiText.Res(R.string.np_msg_lyrics_applied_lrclib)
    else -> UiText.Res(R.string.np_msg_lyrics_applied_from, listOf(providerName))
}

private fun lyricsTranslatedFrom(providerName: String): UiText = when (providerName) {
    "qq" -> UiText.Res(R.string.np_msg_lyrics_translated_qq)
    "netease" -> UiText.Res(R.string.np_msg_lyrics_translated_netease)
    "huawei" -> UiText.Res(R.string.np_msg_lyrics_translated_huawei)
    "lrclib" -> UiText.Res(R.string.np_msg_lyrics_translated_lrclib)
    else -> UiText.Res(R.string.np_msg_lyrics_translated_from, listOf(providerName))
}
