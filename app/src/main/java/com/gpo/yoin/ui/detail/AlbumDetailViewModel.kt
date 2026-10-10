package com.gpo.yoin.ui.detail

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.R
import com.gpo.yoin.data.album.AlbumScrapbookData
import com.gpo.yoin.data.album.AlbumScrapbookQuery
import com.gpo.yoin.data.album.AlbumScrapbookSource
import com.gpo.yoin.data.album.AlbumScrapbookTrackKey
import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.memory.AlbumMemoryTitleSource
import com.gpo.yoin.data.memory.AlbumMemoryTitleStore
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.model.isUnplayableAppleImport
import com.gpo.yoin.data.repository.FavoriteState
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.ServiceFeatureCatalog
import com.gpo.yoin.data.source.spotify.SpotifyRateLimitException
import com.gpo.yoin.perf.YoinPerf
import com.gpo.yoin.player.PlaybackState
import com.gpo.yoin.ui.common.UiText
import com.gpo.yoin.ui.component.FavoriteGlyph
import com.gpo.yoin.ui.component.toUserMessage
import com.gpo.yoin.ui.memories.ResolvedMemoryTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumDetailViewModel(
    private val albumId: String,
    private val repository: YoinRepository,
    // Scores, notes, questions and plays for this album (live). Page 1's Avg. / emblem and page 2 read it.
    private val scrapbookSource: AlbumScrapbookSource = AlbumScrapbookSource.None,
    // The player, for "play from this note" on page 2 (null in tests that don't care).
    private val playback: AlbumScrapbookPlayback? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    // The user's own name for the album's Memory (page 2's title; null in tests that don't care).
    private val memoryTitleStore: AlbumMemoryTitleStore? = null,
    // Page 2's title, live, from the resolver Memories and Home share (user > AI; none over the album name).
    private val memoryTitles: (MediaId) -> Flow<ScrapTitle?> = { flowOf(null) },
) : ViewModel() {

    private val _uiState = MutableStateFlow<AlbumDetailUiState>(AlbumDetailUiState.Loading)
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    // Debug-only: `detail.content` marks the first Content only. Declared before
    // init, whose load can publish a mem-cached Content synchronously.
    private var perfContentMarked = false

    private var albumSongs: List<Track> = emptyList()
    private var loadedAlbum: Album? = null

    /** The album's tracks as fetched: the baseline under each row's heart (YoinRepository.observeFavoriteStates). */
    private val favoriteBase = MutableStateFlow<List<Track>>(emptyList())

    /** Heart taps whose write is still out; the row shows one at once. Each tap is its own token. */
    private val pendingFavoriteTaps = MutableStateFlow<Map<MediaId, PendingFavoriteTap>>(emptyMap())

    private class PendingFavoriteTap(val favorite: Boolean)

    /** Each row's heart as shown; its quiet flips count the changes nobody tapped (FavoriteGlyph). */
    private val favoriteGlyphs = HashMap<MediaId, FavoriteGlyph>()

    /** The album's rating row as Room last reported it (its NeoDB dirty flags drive [neoDb]). */
    private var ratingRow: AlbumRating? = null

    private val _neoDb = MutableStateFlow(AlbumNeoDbSync.Unknown)

    /** Where NeoDB stands for this album — the rate sheet's quiet last line (owner R3, 2026-10-06). */
    val neoDb: StateFlow<AlbumNeoDbSync> = _neoDb.asStateFlow()

    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 4)

    /** One-line notices for the window's snackbar (a failed NeoDB sync). */
    val messages: SharedFlow<UiText> = _messages.asSharedFlow()
    private val albumTrackIds = MutableStateFlow<List<MediaId>>(emptyList())
    private val _expandedSongId = MutableStateFlow<String?>(null)
    val expandedSongId: StateFlow<String?> = _expandedSongId.asStateFlow()

    val notedSongIds: StateFlow<Set<String>> = albumTrackIds
        .flatMapLatest(repository::observeTracksWithNotes)
        .map { ids -> ids.mapTo(linkedSetOf(), MediaId::toString) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val expandedNoteBundle: StateFlow<AlbumExpandedNoteBundle?> = _expandedSongId
        .flatMapLatest { songId ->
            val track = albumSongs.firstOrNull { it.id.toString() == songId }
                ?: return@flatMapLatest flowOf(null)
            combine(
                repository.observeNotes(track.id),
                repository.observeCrossProviderNotes(
                    trackId = track.id,
                    title = track.title.orEmpty(),
                    artist = track.artist.orEmpty(),
                ),
            ) { primary, crossProvider ->
                AlbumExpandedNoteBundle(
                    songId = track.id.toString(),
                    primaryNotes = primary
                        .filter { it.content.isNotBlank() }
                        .map { AlbumPrimaryNote(id = it.id, content = it.content, createdAt = it.createdAt) },
                    crossProviderNotes = crossProvider
                        .mapNotNull { note ->
                            note.content.takeIf(String::isNotBlank)?.let { content ->
                                AlbumCrossProviderNote(
                                    providerLabel = note.provider.toProviderLabel(),
                                    content = content,
                                )
                            }
                        },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Tracks with an Apple Music library write in flight; keyed by the track id string. */
    private val workingLibraryTrackIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The latest scrapbook read; null until the album has loaded and Room answered once (or the read
     * failed, when it becomes the empty read so page 2 never waits on it — see the collector).
     */
    private val scrapbookData = MutableStateFlow<AlbumScrapbookData?>(null)

    /**
     * Page 2 (the scrapbook), rebuilt whenever the album content or its data moves. Pure
     * ([buildAlbumScrapbook]), so an unrelated Content change (a library check) rebuilds to an equal
     * book and is dropped here.
     */
    val scrapbook: StateFlow<AlbumScrapbookUiState> = combine(
        _uiState.filterIsInstance<AlbumDetailUiState.Content>(),
        scrapbookData.filterNotNull(),
        // Never holds page 2 back: no title until the resolver answers.
        (MediaId.parseOrNull(albumId)?.let(memoryTitles) ?: flowOf(null)).onStart { emit(null) },
    ) { content, data, title -> buildAlbumScrapbook(content, data, clock(), title = title) }
        .distinctUntilChanged()
        .map<AlbumScrapbook, AlbumScrapbookUiState> { book -> AlbumScrapbookUiState.Ready(book) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumScrapbookUiState.Loading)

    private var seekJob: Job? = null

    init {
        loadAlbum()
        observeFavoriteStates()
        observeLibraryMembership()
    }

    fun getAlbumSongs(): List<Track> = albumSongs

    /** Debug-only `detail.content`: the first Content this VM publishes (docs/perf/yoinperf-logging.md). */
    private fun markPerfContent(resolvedId: String) {
        if (!YoinPerf.enabled || perfContentMarked) return
        perfContentMarked = true
        YoinPerf.mark(
            "detail.content",
            "kind" to "album",
            "id" to albumId,
            // Apple Music folds a library id onto its catalog album: detail.visible carries this one.
            "resolved" to resolvedId.takeIf { it != albumId }
        )
    }

    fun retry() {
        _uiState.value = AlbumDetailUiState.Loading
        loadAlbum()
    }

    private fun loadAlbum() {
        viewModelScope.launch {
            try {
                val parsedAlbumId = MediaId.parse(albumId)
                repository.awaitActiveSource(DETAIL_SOURCE_WAIT_MS)
                val album = repository.getAlbum(parsedAlbumId)
                if (album == null) {
                    _uiState.value = AlbumDetailUiState.Error(UiText.Res(R.string.detail_album_error_not_found))
                    return@launch
                }
                loadedAlbum = album
                albumSongs = album.tracks.applyFavoriteOverrides(repository.favoriteOverrides.value)
                favoriteGlyphs.clear()
                albumSongs.forEach { track -> favoriteGlyphs[track.id] = FavoriteGlyph(track.isStarred) }
                albumTrackIds.value = albumSongs.map(Track::id)
                // The visit row feeds Home's activity and the widgets, not this page:
                // Content doesn't wait on the Room insert.
                launch { repository.recordAlbumVisit(album) }
                _uiState.value = AlbumDetailUiState.Content(
                    albumId = album.id.toString(),
                    albumName = album.name,
                    artistName = album.artist.orEmpty(),
                    artistId = album.artistId?.toString(),
                    coverArtId = CoverRef.toStorageKey(album.coverArt),
                    coverArtUrl = album.coverArt?.let { repository.resolveCoverUrl(it) },
                    year = album.year,
                    songCount = album.songCount,
                    totalDuration = album.durationSec,
                    // Membership was seeded by the load itself (before Content existed), so
                    // read it here; observeLibraryMembership only sees later changes.
                    songs = albumSongs.map { song -> song.toAlbumSong(album.artist) }
                        .withLibraryMembership(repository.trackLibraryStates.first(), workingLibraryTrackIds.value),
                )
                markPerfContent(album.id.toString())

                // The rows follow the favorite state from here on, and Spotify is
                // asked about likes its 200-track mirror can't show: one batched
                // check after the page is out, whose answer flips hearts quietly.
                // The album's own saved state (the ▾ menu's library row) rides
                // the same check when its saved-albums mirror doesn't have it.
                favoriteBase.value = album.tracks
                observeAlbumSaved(album.id)
                launch { repository.refreshFavoriteStates(album.tracks, album = album) }

                // 观察 album_ratings，把持久化状态 merge 回 Content —— 用户在
                // 别处（Memory / 以后的 NeoDB 拉取）改了评分 / 评论时，打开
                // AlbumDetail 要看到最新值。pull-from-NeoDB 之后这条 flow 也会
                // 自动刷到新结果。
                launch {
                    repository.observeAlbumRating(parsedAlbumId).collect { rating ->
                        ratingRow = rating
                        val current = _uiState.value as? AlbumDetailUiState.Content
                            ?: return@collect
                        // 只在 review 没有未保存编辑时同步下游 review；
                        // 保护用户当前正在输入的草稿不被覆盖。
                        val nextReview = if (current.reviewHasUnsavedEdits) {
                            current.userReview
                        } else {
                            rating?.review.orEmpty()
                        }
                        _uiState.value = current.copy(
                            userRating = rating?.rating?.takeIf { it > 0f },
                            userReview = nextReview,
                            userReviewAt = rating?.reviewUpdatedAt,
                            reviewHasUnsavedEdits = current.reviewHasUnsavedEdits &&
                                nextReview != rating?.review.orEmpty(),
                        )
                        refreshNeoDbLine()
                    }
                }

                // 单曲均分、评过分的曲目、专辑级 last-play 和第二页的手帐都读同一条
                // 活的 Room 流：在 NP 里改分 / 写笔记 / 提问之后，两页都跟着更新。
                val query = AlbumScrapbookQuery(
                    albumId = album.id,
                    albumName = album.name,
                    tracks = albumSongs.map { track ->
                        AlbumScrapbookTrackKey(
                            id = track.id,
                            title = track.title.orEmpty(),
                            artist = track.artist.orEmpty(),
                        )
                    },
                )
                launch {
                    scrapbookSource.observe(query)
                        .retryWhen { cause, attempt ->
                            // Page 2 must not sit in Loading on a failed read: with nothing read yet it
                            // shows the empty / sparse book; after a good read it keeps those values (page
                            // 1 keeps its signals too). The retried read's next emission replaces them.
                            if (scrapbookData.value == null) scrapbookData.value = AlbumScrapbookData.Empty
                            val retry = attempt < SCRAPBOOK_MAX_RETRIES
                            val next = if (retry) "retrying" else "giving up"
                            Log.w(TAG, "Album scrapbook read failed (attempt ${attempt + 1}); $next", cause)
                            if (retry) delay(scrapbookRetryDelayMs(attempt))
                            retry
                        }
                        // Gave up (logged above): page 2 stays on what it shows; never crash the page.
                        .catch { }
                        .collect { data ->
                            scrapbookData.value = data
                            mergeRatingSummary(data)
                        }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Album load failed for $albumId", e)
                _uiState.value = AlbumDetailUiState.Error(
                    e.toDetailMessage(R.string.detail_album_error_load),
                )
            }
        }
    }

    /**
     * The row's trailing control. Providers with favorites toggle the heart; a
     * library-add provider (Apple Music) adds the song to the user's library instead —
     * the row renders a check, not a heart, and there is no removal.
     */
    fun toggleStar(songId: String) {
        val track = albumSongs.find { it.id.toString() == songId } ?: return
        val features = ServiceFeatureCatalog.forProvider(track.id.provider)
        if (!features.supportsFavorites && features.supportsLibraryAdd) {
            addToLibrary(track)
            return
        }
        val target = !(favoriteGlyphs[track.id]?.favorite ?: track.isStarred)
        // The row shows the tap at once (Subsonic's write only lands with the
        // server's answer). When the write ends the repository's state takes
        // over: the same heart once it landed, the old one back if it failed.
        val tap = PendingFavoriteTap(target)
        pendingFavoriteTaps.update { taps -> taps + (track.id to tap) }
        viewModelScope.launch {
            try {
                repository.setFavorite(track, favorite = target)
            } finally {
                pendingFavoriteTaps.update { taps -> if (taps[track.id] === tap) taps - track.id else taps }
            }
        }
    }

    /**
     * The page is on screen again (its Activity or pane resumed): asks Spotify
     * about the rows once more — a like made in the Spotify app meanwhile shows
     * here. The repository asks about a track at most every 30 s.
     */
    fun onResumed() {
        val tracks = favoriteBase.value.takeIf { it.isNotEmpty() } ?: return
        viewModelScope.launch { repository.refreshFavoriteStates(tracks, album = loadedAlbum) }
    }

    private var albumSavedJob: Job? = null

    /** The ▾ menu's library row follows the album's saved state; none where the service can't save albums. */
    private fun observeAlbumSaved(albumId: MediaId) {
        albumSavedJob?.cancel()
        albumSavedJob = viewModelScope.launch {
            repository.observeAlbumSaved(albumId).collect { saved ->
                val current = _uiState.value as? AlbumDetailUiState.Content ?: return@collect
                if (current.librarySaved != saved) _uiState.value = current.copy(librarySaved = saved)
            }
        }
    }

    /**
     * The ▾ menu's Save to library / Remove from library. The row flips at
     * once (the repository's write in flight); a failed write flips it back
     * and says why on the window's snackbar.
     */
    fun toggleLibrarySaved() {
        val album = loadedAlbum ?: return
        val saved = (_uiState.value as? AlbumDetailUiState.Content)?.librarySaved ?: return
        val target = !saved
        viewModelScope.launch {
            repository.setAlbumSaved(album, target).onFailure { error ->
                Log.w(TAG, "Album ${if (target) "save" else "removal"} failed for ${album.id}", error)
                _messages.tryEmit(
                    error.toLibrarySaveMessage(
                        if (target) R.string.detail_album_save_failed else R.string.detail_album_remove_failed
                    )
                )
            }
        }
    }

    /** Folds the repository's favorite state, the taps still out on top, into the rows. */
    private fun observeFavoriteStates() {
        viewModelScope.launch {
            combine(
                favoriteBase.flatMapLatest { tracks -> repository.observeFavoriteStates(tracks) },
                pendingFavoriteTaps
            ) { states, taps -> states to taps }
                .collect { (states, taps) -> applyFavoriteStates(states, taps) }
        }
    }

    private fun applyFavoriteStates(states: Map<MediaId, FavoriteState>, taps: Map<MediaId, PendingFavoriteTap>) {
        val current = _uiState.value as? AlbumDetailUiState.Content ?: return
        albumSongs = albumSongs.map { track ->
            val state = taps[track.id]?.let { tap -> FavoriteState(tap.favorite, fromUser = true) }
                ?: states[track.id]
                ?: return@map track
            val glyph = (favoriteGlyphs[track.id] ?: FavoriteGlyph(track.isStarred))
                .next(state.isStarred, state.fromUser)
            favoriteGlyphs[track.id] = glyph
            if (track.isStarred == glyph.favorite) track else track.copy(isStarred = glyph.favorite)
        }
        _uiState.value = current.copy(
            songs = current.songs.map { song ->
                val glyph = MediaId.parseOrNull(song.id)?.let(favoriteGlyphs::get) ?: return@map song
                song.copy(isStarred = glyph.favorite, favoriteQuietFlips = glyph.quietFlips)
            }
        )
    }

    private fun addToLibrary(track: Track) {
        val songId = track.id.toString()
        if (songId in workingLibraryTrackIds.value) return
        val current = _uiState.value as? AlbumDetailUiState.Content
        if (current?.songs?.firstOrNull { it.id == songId }?.libraryMembership == LibraryMembership.Added) return
        workingLibraryTrackIds.value += songId
        viewModelScope.launch {
            try {
                // Membership itself arrives through observeLibraryMembership; only
                // confirmed membership turns the control into a stable check.
                repository.addToLibrary(track)
            } finally {
                workingLibraryTrackIds.value -= songId
            }
        }
    }

    private fun observeLibraryMembership() {
        viewModelScope.launch {
            combine(repository.trackLibraryStates, workingLibraryTrackIds) { states, working -> states to working }
                .collectLatest { (states, working) ->
                    val current = _uiState.value as? AlbumDetailUiState.Content ?: return@collectLatest
                    _uiState.value = current.copy(songs = current.songs.withLibraryMembership(states, working))
                }
        }
    }

    private fun List<AlbumSong>.withLibraryMembership(
        states: Map<MediaId, LibraryMembership>,
        working: Set<String>,
    ): List<AlbumSong> = map { song ->
        val id = MediaId.parseOrNull(song.id) ?: return@map song
        song.copy(
            libraryMembership = states[id] ?: LibraryMembership.Unknown,
            libraryActionInFlight = song.id in working,
        )
    }

    private fun List<Track>.applyFavoriteOverrides(
        overrides: Map<MediaId, Boolean>,
    ): List<Track> = map { track ->
        overrides[track.id]?.let { isStarred -> track.copy(isStarred = isStarred) } ?: track
    }

    /**
     * Page 1's computed signals from the live read: the track average, how many tracks are rated and
     * which (the emblem's cut rings), and the album's last play in Yoin.
     */
    private fun mergeRatingSummary(data: AlbumScrapbookData) {
        val current = _uiState.value as? AlbumDetailUiState.Content ?: return
        val ratedValues = albumSongs.mapNotNull { data.ratings[it.id] }
        val ratedIds = albumSongs.filter { it.id in data.ratings }.mapTo(linkedSetOf()) { it.id.toString() }
        _uiState.value = current.copy(
            averageTrackRating = ratedValues.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            ratedTrackCount = ratedValues.size,
            ratedSongIds = ratedIds,
            lastPlayedAt = data.plays.lastPlayedAt,
        )
    }

    /**
     * Play-from-a-note's second half (page 2): the caller plays [songId] from the start unless this
     * returns true. Once that track really is current — and prepared (duration > 0) — seek to
     * [positionMs], once; give up after [SEEK_TIMEOUT_MS]. When the track is already current and
     * prepared, seek now (resuming it if paused) and return true: the caller must not restart it.
     */
    fun requestNoteSeek(songId: String, positionMs: Long): Boolean {
        val player = playback ?: return false
        seekJob?.cancel()
        val now = player.state.value
        if (now.currentTrack?.id?.toString() == songId && now.duration > 0L) {
            player.seekTo(positionMs)
            if (!now.isPlaying) player.resume()
            return true
        }
        seekJob = viewModelScope.launch {
            val ready = withTimeoutOrNull(SEEK_TIMEOUT_MS) {
                player.state.first { state -> state.currentTrack?.id?.toString() == songId && state.duration > 0L }
            }
            if (ready != null && positionMs > 0L) player.seekTo(positionMs)
        }
        return false
    }

    fun toggleExpandedSong(songId: String) {
        _expandedSongId.value = if (_expandedSongId.value == songId) null else songId
    }

    /**
     * 松开评分滑条时调用（0.1 步进，0..10，和 NP 的滑条一致）；
     * 0 当「撤销评分」落库（[YoinRepository.setAlbumRating] 接受 0）。
     */
    fun setUserRating(rating: Float) {
        val album = loadedAlbum ?: return
        viewModelScope.launch {
            repository.setAlbumRating(album, rating)
        }
    }

    /** 输入 review 时调用 —— 只更新本地 UiState，不 upsert。 */
    fun onReviewDraftChange(text: String) {
        val current = _uiState.value as? AlbumDetailUiState.Content ?: return
        _uiState.value = current.copy(
            userReview = text,
            reviewHasUnsavedEdits = true,
        )
        refreshNeoDbLine()
    }

    /**
     * The sheet's NeoDB line follows what happens while it is open: a score set or words typed after a sync
     * becomes pending again, not a stale "Synced". Unknown (never read), signed out, a push in flight
     * and a failure keep their own owners ([onRateSheetOpened], [syncToNeoDb]).
     */
    private fun refreshNeoDbLine() {
        val shown = _neoDb.value
        if (shown != AlbumNeoDbSync.Synced && shown != AlbumNeoDbSync.Idle && shown != AlbumNeoDbSync.Pending) return
        val unsaved = (_uiState.value as? AlbumDetailUiState.Content)?.reviewHasUnsavedEdits == true
        _neoDb.value = if (unsaved) AlbumNeoDbSync.Pending else albumNeoDbSync(configured = true, row = ratingRow)
    }

    /** The rate sheet opened: read where NeoDB stands for this album. */
    fun onRateSheetOpened() {
        if (_neoDb.value == AlbumNeoDbSync.Syncing) return
        viewModelScope.launch {
            _neoDb.value = albumNeoDbSync(neoDbConfigured(), ratingRow)
        }
    }

    /**
     * The rate sheet closed: keep the words (no Save button any more), then
     * hand the album to NeoDB when signed in and something changed.
     */
    fun onRateSheetClosed() {
        val album = loadedAlbum ?: return
        val current = _uiState.value as? AlbumDetailUiState.Content ?: return
        viewModelScope.launch {
            if (current.reviewHasUnsavedEdits) {
                repository.setAlbumReview(album, current.userReview)
                _uiState.value = (_uiState.value as? AlbumDetailUiState.Content)
                    ?.copy(reviewHasUnsavedEdits = false) ?: return@launch
            }
            syncToNeoDb(album)
        }
    }

    /** Retries a failed NeoDB sync from the sheet. */
    fun retryNeoDbSync() {
        val album = loadedAlbum ?: return
        viewModelScope.launch { syncToNeoDb(album) }
    }

    private suspend fun neoDbConfigured(): Boolean = try {
        repository.isNeoDBConfigured()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    }

    private suspend fun syncToNeoDb(album: Album) {
        val configured = neoDbConfigured()
        val row = try {
            repository.getAlbumRatingRow(album.id)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ratingRow
        }
        val state = albumNeoDbSync(configured, row)
        if (state != AlbumNeoDbSync.Pending) {
            _neoDb.value = state
            return
        }
        _neoDb.value = AlbumNeoDbSync.Syncing
        val result = try {
            repository.pushAlbumToNeoDB(album)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Result.failure(error)
        }
        _neoDb.value = if (result.isSuccess) AlbumNeoDbSync.Synced else AlbumNeoDbSync.Failed
        if (result.isFailure) {
            Log.w(TAG, "NeoDB sync failed for ${album.id}", result.exceptionOrNull())
            _messages.tryEmit(UiText.Res(R.string.detail_album_neodb_failed))
        }
    }

    /** Renames the album's Memory (page 2's title); blank restores Yoin's own title. */
    fun renameMemoryTitle(title: String) {
        val store = memoryTitleStore ?: return
        val id = MediaId.parseOrNull(albumId) ?: return
        viewModelScope.launch { runCatching { store.setTitle(id, title) } }
    }

    /** Drops the user's name: the AI title shows again. */
    fun restoreMemoryTitle() {
        val store = memoryTitleStore ?: return
        val id = MediaId.parseOrNull(albumId) ?: return
        viewModelScope.launch { runCatching { store.clearTitle(id) } }
    }

    class Factory(
        private val albumId: String,
        private val container: AppContainer,
    ) : ViewModelProvider.Factory {
        private val playback = object : AlbumScrapbookPlayback {
            override val state: StateFlow<PlaybackState> get() = container.playbackManager.playbackState

            override fun seekTo(positionMs: Long) = container.playbackManager.seekTo(positionMs)

            override fun resume() = container.playbackManager.resume()
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AlbumDetailViewModel(
                albumId = albumId,
                repository = container.repository,
                scrapbookSource = container.albumScrapbookSource,
                playback = playback,
                memoryTitleStore = container.albumMemoryTitleStore,
                memoryTitles = { id -> container.albumMemoryTitleResolver.observe(id).map { it.toScrapTitle() } },
            ) as T
    }

    companion object {
        /** Play-from-a-note gives up on its seek if the track isn't current and prepared by then. */
        const val SEEK_TIMEOUT_MS = 4_000L

        /** A failed scrapbook read retries after 1s, doubling up to this. */
        const val SCRAPBOOK_RETRY_MAX_MS = 30_000L

        /** Retries before a failing scrapbook read is given up (1 + 2 + 4 + 8 + 16 s ≈ half a minute). */
        const val SCRAPBOOK_MAX_RETRIES = 5L

        private const val TAG = "AlbumDetailVM"

        /** Backoff before retry [attempt] (0-based): 1s, 2s, 4s … capped at [SCRAPBOOK_RETRY_MAX_MS]. */
        internal fun scrapbookRetryDelayMs(attempt: Long): Long =
            (1_000L shl attempt.coerceIn(0L, 5L).toInt()).coerceAtMost(SCRAPBOOK_RETRY_MAX_MS)
    }
}

/** Where NeoDB stands for one album, as the rate sheet's last line reads it. */
enum class AlbumNeoDbSync {
    /** Not read yet: the line is left out. */
    Unknown,

    /** No NeoDB account: the line offers sign-in. */
    SignedOut,

    /** Signed in, nothing written for this album yet. */
    Idle,

    /** A change waits: it goes when the sheet closes. */
    Pending,
    Syncing,
    Synced,
    Failed,
}

/** [row]'s NeoDB state for an account that is [configured] or not (the dirty flags decide). */
internal fun albumNeoDbSync(configured: Boolean, row: AlbumRating?): AlbumNeoDbSync = when {
    !configured -> AlbumNeoDbSync.SignedOut
    row != null && (row.ratingNeedsSync || row.reviewNeedsSync) -> AlbumNeoDbSync.Pending
    row != null && (row.rating > 0f || !row.review.isNullOrBlank()) -> AlbumNeoDbSync.Synced
    else -> AlbumNeoDbSync.Idle
}

/** What page 2 needs from the player: its state and two commands (Memories' play-from-a-note contract). */
interface AlbumScrapbookPlayback {
    val state: StateFlow<PlaybackState>

    fun seekTo(positionMs: Long)

    fun resume()
}

data class AlbumExpandedNoteBundle(
    val songId: String,
    val primaryNotes: List<AlbumPrimaryNote>,
    val crossProviderNotes: List<AlbumCrossProviderNote>,
)

data class AlbumPrimaryNote(
    val id: String,
    val content: String,
    val createdAt: Long,
)

data class AlbumCrossProviderNote(
    val providerLabel: UiText,
    val content: String,
)

private fun Track.toAlbumSong(albumArtist: String?): AlbumSong = AlbumSong(
    id = id.toString(),
    title = title.orEmpty(),
    artist = artist.orEmpty(),
    trackNumber = trackNumber,
    duration = durationSec,
    isStarred = isStarred,
    isUnavailable = isUnplayableAppleImport,
    featArtist = artist?.takeIf {
        it.isNotBlank() && !it.equals(albumArtist, ignoreCase = true)
    },
)

private fun String.toProviderLabel(): UiText = when (this) {
    MediaId.PROVIDER_SPOTIFY -> UiText.Raw("Spotify")
    MediaId.PROVIDER_SUBSONIC -> UiText.Raw("Subsonic")
    MediaId.PROVIDER_LOCAL -> UiText.Res(R.string.detail_provider_local)
    else -> UiText.Raw(replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() })
}

/**
 * How long a detail page's first load waits for the active source. A widget tap
 * on a cold process opens the page before ProfileManager has built the source,
 * and a fetch without one fails with "No profile configured". Past this the
 * load goes ahead as before (the disk cache still answers; no source = error).
 */
internal const val DETAIL_SOURCE_WAIT_MS = 4_000L

/**
 * A failed album save's snackbar line. Spotify's rate limit (the likeliest
 * reason while the account is throttled) is said in the app's language; the
 * rest as [toDetailMessage] says it.
 */
internal fun Throwable.toLibrarySaveMessage(@StringRes fallback: Int): UiText =
    if (this is SpotifyRateLimitException) UiText.Res(R.string.cmp_error_spotify_busy) else toDetailMessage(fallback)

/**
 * [toUserMessage] owns the connectivity lines. A sentinel fallback means this
 * screen's own load line, which is a resource.
 */
internal fun Throwable.toDetailMessage(@StringRes fallback: Int): UiText {
    val resolved = toUserMessage("\u0000")
    return if (resolved == "\u0000") UiText.Res(fallback) else UiText.Raw(resolved)
}

/** Page 2's title from the shared resolver; none over the bare album name (the header already says it). */
internal fun ResolvedMemoryTitle.toScrapTitle(): ScrapTitle? =
    if (source == AlbumMemoryTitleSource.ALBUM) {
        null
    } else {
        ScrapTitle(
            text = text,
            edited = source == AlbumMemoryTitleSource.USER,
            canRestore = canRestoreGenerated,
            restoreLabel = restoreLabel,
            serif = isSerif,
            draftSeed = draftSeed,
        )
    }
