package com.gpo.yoin.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.home.HomeLayoutStore
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.memory.deterministicMemoryTitle
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.ui.memories.MemoryEntityType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class HomeViewModel(
    private val repository: YoinRepository,
    // Public so the edit-mode UI can watch for profile switches: an open layout
    // editor must close when the profile changes, or its draft (belonging to
    // the old profile) would be persisted into the new one.
    val activeProfileId: StateFlow<String?>,
    private val homeLayoutStore: HomeLayoutStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // Which provider|profile the Content in [_uiState] belongs to. The live
    // observers only splice into content of the CURRENT scope: right after a
    // profile switch the screen may still hold the old profile's feed (until
    // refresh repaints), and a tick must never graft one profile onto another.
    private var contentScopeKey: String? = null

    /**
     * The active profile's home layout (which sections show, in what order),
     * reconciled against the live section catalog. Orthogonal to [uiState]:
     * content loads the same regardless of layout, and the feed renders from
     * this. Falls back to [HomeLayout.Default] when no profile is active or the
     * profile hasn't customized.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val homeLayout: StateFlow<HomeLayout> =
        activeProfileId
            .flatMapLatest { profileId ->
                if (profileId.isNullOrBlank()) {
                    flowOf(HomeLayout.Default)
                } else {
                    homeLayoutStore.layoutFlow(profileId).map(HomeLayout::reconcile)
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, HomeLayout.Default)

    init {
        refresh()
        observeRecentHistory()
        observeMemorySignals()
    }

    /** Persist a new home layout for the active profile (no-op with no profile). */
    fun setHomeLayout(layout: HomeLayout) {
        val profileId = activeProfileId.value
        if (profileId.isNullOrBlank()) return
        viewModelScope.launch {
            homeLayoutStore.setLayout(profileId, layout.toPrefs())
        }
    }

    fun refresh() {
        val providerId = repository.currentProviderId()
        val profileId = activeProfileId.value
        viewModelScope.launch {
            val scopeKey = homeScopeKey(providerId, profileId)
            val cachedHomeContent = homeContentCache[scopeKey]
            val cachedSpotifyContent = if (
                providerId == MediaId.PROVIDER_SPOTIFY &&
                !profileId.isNullOrBlank()
            ) {
                loadCachedSpotifyHomeContent()
            } else {
                null
            }

            _uiState.value = cachedHomeContent
                ?: cachedSpotifyContent
                ?: HomeUiState.Loading
            contentScopeKey = scopeKey

            try {
                val freshContent = when {
                    providerId == MediaId.PROVIDER_SPOTIFY && !profileId.isNullOrBlank() ->
                        loadSpotifyHomeContent()

                    else -> loadHomeContent()
                }
                if (!matchesCurrentScope(providerId, profileId)) return@launch
                homeContentCache[scopeKey] = freshContent
                _uiState.value = freshContent
                contentScopeKey = scopeKey
            } catch (e: Exception) {
                if (!matchesCurrentScope(providerId, profileId)) return@launch
                if (cachedSpotifyContent == null) {
                    _uiState.value = HomeUiState.Error(
                        e.message ?: "Failed to load home content",
                    )
                }
            }
        }
    }

    /** The pill last shown for the current scope — kept when a fresh read can't resolve one. */
    private fun cachedMemoryPill(): HomeMemoryPill? =
        homeContentCache[homeScopeKey(repository.currentProviderId(), activeProfileId.value)]?.memoryPill

    fun buildCoverArtUrl(coverArtId: String): String =
        repository.resolveSubsonicCoverUrl(coverArtId, size = 320).orEmpty()

    private suspend fun loadHomeContent(): HomeUiState.Content =
        coroutineScope {
            val activitiesDeferred = async {
                repository.getRecentActivities(limit = HOME_ACTIVITY_LIMIT).first()
            }
            // One candidate build feeds both the header pill and the grid's
            // memory card.
            val signalsDeferred = async { loadMemorySignals() }
            val widgetGridDeferred = async {
                resolveWidgetGrid(localOnly = false, signals = signalsDeferred.await())
            }
            val recentlyAddedDeferred = async { loadRecentlyAdded() }
            // Parallel with the grid/shelf loads: on a cold detail cache this can
            // be a network fetch, and it must not serialize the first paint.
            val heroFootnoteDeferred = async {
                loadActivityHeroFootnote(activitiesDeferred.await())
            }

            val recentlyAdded = recentlyAddedDeferred.await()
            HomeUiState.Content(
                activities = activitiesDeferred.await(),
                activityHeroFootnote = heroFootnoteDeferred.await(),
                widgetGrid = widgetGridDeferred.await(),
                recentlyAddedTracks = recentlyAdded.tracks,
                recentlyAddedAlbums = recentlyAdded.albums,
                memoryPill = signalsDeferred.await()?.pill ?: cachedMemoryPill(),
            )
        }

    /**
     * Library items added within the last week, newest first. Provider-agnostic:
     * reads the unified starred/saved library ([YoinRepository.getStarred]) once
     * and keeps both tracks and albums whose `addedAt` parses to within the
     * window (tracks feed the 2×2 grid, albums the scrolling shelf — Figma
     * 622:777). Failures degrade to an empty shelf rather than breaking the whole
     * home load; cooperative cancellation is rethrown.
     */
    private suspend fun loadRecentlyAdded(): RecentlyAdded {
        return try {
            val cutoff = System.currentTimeMillis() - RECENTLY_ADDED_WINDOW_MS
            val starred = repository.getStarred()
            val tracks = starred.tracks
                .withinRecentlyAddedWindow(cutoff, RECENTLY_ADDED_TRACK_LIMIT, key = { it.id }) { it.addedAt }
            val albums = starred.albums
                .withinRecentlyAddedWindow(cutoff, RECENTLY_ADDED_ALBUM_LIMIT, key = { it.id }) { it.addedAt }
            RecentlyAdded(tracks = tracks, albums = albums)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            RecentlyAdded()
        }
    }

    /**
     * Profile scoping is the repository's job here and in the fresh twin
     * [loadSpotifyHomeContent]: every read and write reached from either
     * resolves the active profile itself, so there is no profile id to thread
     * through. Grid pools, activities, notes, ratings and play history key off
     * profileId+provider from the same `activeProfileId` StateFlow this
     * ViewModel watches; the Spotify library reads key off the active source's
     * own profile id, which is that same profile's.
     */
    private suspend fun loadCachedSpotifyHomeContent(): HomeUiState.Content? = coroutineScope {
        val activitiesDeferred = async {
            repository.getRecentActivities(limit = HOME_ACTIVITY_LIMIT).first()
        }
        val signalsDeferred = async { loadMemorySignals() }
        // Instant pre-paint: pools of any age from disk, never the network.
        // The fresh load right behind this rotates them only if expired.
        val widgetGridDeferred = async {
            resolveWidgetGrid(localOnly = true, signals = signalsDeferred.await())
        }

        val activities = activitiesDeferred.await()
        val widgetGrid = widgetGridDeferred.await()
        if (activities.isEmpty() && widgetGrid.isEmpty()) {
            null
        } else {
            HomeUiState.Content(
                activities = activities,
                widgetGrid = widgetGrid,
                memoryPill = signalsDeferred.await()?.pill ?: cachedMemoryPill(),
            )
        }
    }

    private suspend fun loadSpotifyHomeContent(): HomeUiState.Content = coroutineScope {
        val activitiesDeferred = async { resolveSpotifyActivities() }
        val signalsDeferred = async { loadMemorySignals() }
        val widgetGridDeferred = async {
            resolveWidgetGrid(localOnly = false, signals = signalsDeferred.await())
        }
        val recentlyAddedDeferred = async { loadRecentlyAdded() }

        val (activities, activitiesFromRemote) = activitiesDeferred.await()
        val heroFootnoteDeferred = async { loadActivityHeroFootnote(activities) }
        val recentlyAdded = recentlyAddedDeferred.await()
        HomeUiState.Content(
            activities = activities,
            activitiesFromRemote = activitiesFromRemote,
            activityHeroFootnote = heroFootnoteDeferred.await(),
            widgetGrid = widgetGridDeferred.await(),
            recentlyAddedTracks = recentlyAdded.tracks,
            recentlyAddedAlbums = recentlyAdded.albums,
            memoryPill = signalsDeferred.await()?.pill ?: cachedMemoryPill(),
        )
    }

    /**
     * Spotify Activities prefer the real recently-played endpoint, falling back
     * to the locally recorded feed when the endpoint fails (e.g.
     * user-read-recently-played not granted) or the account has no recent
     * plays. Returns the events plus whether they came from the endpoint, so
     * [observeRecentHistory] knows whether the endpoint owns the feed.
     * CancellationException is rethrown so cooperative cancellation isn't
     * swallowed by the fallback.
     */
    private suspend fun resolveSpotifyActivities(): Pair<List<ActivityEvent>, Boolean> {
        val remote = try {
            repository.getSpotifyRecentActivities(limit = HOME_ACTIVITY_LIMIT)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        return if (!remote.isNullOrEmpty()) {
            remote to true
        } else {
            repository.getRecentActivities(limit = HOME_ACTIVITY_LIMIT).first() to false
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeRecentHistory() {
        viewModelScope.launch {
            // Room re-emits on every activity_events insert — i.e. every track
            // change and every detail-page visit. Debounce coalesces those
            // bursts so a rapid skip-through doesn't rebuild per song.
            repository.getRecentActivities(limit = HOME_ACTIVITY_LIMIT)
                .debounce(RECENT_HISTORY_DEBOUNCE_MS)
                .collectLatest { localActivities ->
                    val providerId = repository.currentProviderId()
                    val profileId = activeProfileId.value
                    val scopeKey = homeScopeKey(providerId, profileId)
                    if (contentScopeKey != scopeKey) return@collectLatest
                    val currentContent = _uiState.value as? HomeUiState.Content ?: return@collectLatest
                    // The recently-played endpoint owns the Spotify feed ONLY when
                    // the activities actually came from it; on the local fallback
                    // (scope missing / no recent plays) the feed must keep
                    // live-updating from local writes like every other provider.
                    // Otherwise a local activity-event write must not clobber the
                    // endpoint feed.
                    val keepEndpointFeed = currentContent.activitiesFromRemote &&
                        repository.currentProviderId() == MediaId.PROVIDER_SPOTIFY
                    val effectiveActivities =
                        if (keepEndpointFeed) currentContent.activities else localActivities
                    // Only re-resolve the hero footnote when the hero actually
                    // changed — resolving per emission is wasted work, and a
                    // transient getAlbum failure would wipe a good footnote.
                    val newHero = selectHomeHeroActivity(effectiveActivities)
                    val oldHero = selectHomeHeroActivity(currentContent.activities)
                    val heroUnchanged = newHero?.entityType == oldHero?.entityType &&
                        newHero?.entityId == oldHero?.entityId
                    val footnote = if (heroUnchanged) {
                        currentContent.activityHeroFootnote
                    } else {
                        loadActivityHeroFootnote(effectiveActivities)
                    }
                    // Apply to the LATEST content, owning only the activity
                    // fields: a snapshot write would revert a pill / grid
                    // update that landed while the footnote loaded.
                    if (!matchesCurrentScope(providerId, profileId) || contentScopeKey != scopeKey) {
                        return@collectLatest
                    }
                    val latest = _uiState.value as? HomeUiState.Content ?: return@collectLatest
                    val nextContent = latest.copy(
                        activities = effectiveActivities,
                        activitiesFromRemote = keepEndpointFeed,
                        activityHeroFootnote = footnote,
                    )
                    homeContentCache[scopeKey] = nextContent
                    _uiState.value = nextContent
                }
        }
    }

    /**
     * Keep the widget grid's two memory-flavoured cards and the header's
     * Memories pill live. Note / track rating / album review writes create NO
     * activity event (they go straight to their Room tables from Now Playing
     * and the detail pages), so Home listens to the tables' change stamps
     * directly and splices refreshed wide cards into the current grid — plain
     * recommendation cards stay as loaded; only a full refresh re-rolls those.
     */
    @OptIn(FlowPreview::class)
    private fun observeMemorySignals() {
        viewModelScope.launch {
            repository.observeMemorySignalStamp()
                .debounce(RECENT_HISTORY_DEBOUNCE_MS)
                .collectLatest {
                    // The tick belongs to the scope it started in; if that
                    // moves while it builds, it is dropped, never "kept".
                    val providerId = repository.currentProviderId()
                    val profileId = activeProfileId.value
                    val scopeKey = homeScopeKey(providerId, profileId)
                    if (contentScopeKey != scopeKey) return@collectLatest
                    val currentContent = _uiState.value as? HomeUiState.Content ?: return@collectLatest
                    // Live splices keep the grid's memory album while it still
                    // qualifies — writing on it shouldn't reshuffle the grid.
                    val shownMemoryAlbum = currentContent.widgetGrid
                        .firstOrNull { it.target is HomeWidgetTarget.MemoryFocus }
                        ?.let(::memoryCardAlbumId)
                    val signals = loadMemorySignals(preferJbiRawAlbumId = shownMemoryAlbum?.rawId)
                    val refreshedGrid = refreshWidgetGridSignalCards(currentContent.widgetGrid, signals)
                    if (!matchesCurrentScope(providerId, profileId) || contentScopeKey != scopeKey) {
                        return@collectLatest
                    }
                    val latest = _uiState.value as? HomeUiState.Content ?: return@collectLatest
                    // Unscoped / failed build: keep what's on screen rather
                    // than flashing an empty pill.
                    val refreshedPill = signals?.pill ?: latest.memoryPill
                    // A refresh that landed meanwhile already rebuilt the grid
                    // with fresh signal cards; don't splice an older grid over it.
                    val nextGrid = if (latest.widgetGrid == currentContent.widgetGrid) refreshedGrid else latest.widgetGrid
                    if (nextGrid == latest.widgetGrid && refreshedPill == latest.memoryPill) return@collectLatest
                    val nextContent = latest.copy(
                        widgetGrid = nextGrid,
                        memoryPill = refreshedPill,
                    )
                    homeContentCache[scopeKey] = nextContent
                    _uiState.value = nextContent
                }
        }
    }

    /**
     * Recompute just the memory-album and noted-track 1×2 cards and re-pack
     * them with the existing compact cards (deduping any compact that the new
     * wide cards now cover), preserving the 12-cell budget. A null [signals]
     * (unscoped / failed build) keeps the memory card already on screen.
     */
    private suspend fun refreshWidgetGridSignalCards(
        current: List<HomeWidgetCard>,
        signals: MemorySignals?,
    ): List<HomeWidgetCard> {
        val memory = if (signals != null) {
            signals.memoryCard
        } else {
            current.firstOrNull { it.target is HomeWidgetTarget.MemoryFocus }
                ?.let { card -> card to memoryCardAlbumId(card) }
        }
        val note = loadNotedTrackCard()
        val wideCards = listOfNotNull(memory?.first, note?.first)
        if (wideCards.isEmpty() && current.none { it.expanded }) return current
        val coveredIds = buildSet {
            memory?.second?.let { add(it.toString()) }
            note?.second?.let { add(it.toString()) }
        }
        val compacts = current
            .filterNot { it.expanded }
            .filterNot { card ->
                when (val target = card.target) {
                    is HomeWidgetTarget.AlbumDetail -> target.albumId in coveredIds
                    is HomeWidgetTarget.PlaySong -> target.song.id.toString() in coveredIds
                    else -> false
                }
            }
        return wideCards + compacts.take(GRID_MAX_COMPACTS)
    }

    // ── Widget grid (Jump Back In × memories) ────────────────────────────

    /**
     * Resolve the widget grid through the persisted candidate pools: fresh
     * pools compose instantly with zero network; expired pools trigger one
     * re-fetch — the shelf's rotation moment, at most once per
     * [GRID_POOLS_TTL_MS] — and the [localOnly] pre-paint path accepts any age
     * and never touches the network. The memory / noted signal cards are
     * always resolved live regardless of pool age.
     */
    private suspend fun resolveWidgetGrid(
        localOnly: Boolean,
        signals: MemorySignals?,
    ): List<HomeWidgetCard> {
        val fresh = guardedOrNull { repository.getCachedHomeGridPools(maxAgeMs = GRID_POOLS_TTL_MS) }
        if (fresh != null) return buildWidgetGrid(fresh, signals)
        if (localOnly) {
            val stale = guardedOrNull { repository.getCachedHomeGridPools(maxAgeMs = null) }
            return stale?.let { buildWidgetGrid(it, signals) } ?: emptyList()
        }
        return buildWidgetGrid(fetchAndPersistGridPools(), signals)
    }

    /**
     * One network fan-out builds the next batch of recommendation pools,
     * pre-shuffled into their final order and persisted — so every open until
     * the TTL expires reads the same shelf straight from disk.
     */
    private suspend fun fetchAndPersistGridPools(): YoinRepository.HomeGridPoolSnapshot =
        coroutineScope {
            val albumsDeferred = async {
                guardedList { repository.getAlbumList("random", size = GRID_ALBUM_REQUEST_SIZE) }
            }
            val tracksDeferred = async { guardedList { loadGridTracks() } }
            val playlistsDeferred = async { guardedList { repository.getPlaylists() } }
            val snapshot = YoinRepository.HomeGridPoolSnapshot(
                albums = albumsDeferred.await()
                    .distinctBy { album -> album.id }
                    .shuffled()
                    .take(GRID_POOL_ALBUMS),
                // loadGridTracks is already random/shuffled at the source.
                tracks = tracksDeferred.await()
                    .distinctBy { track -> track.id }
                    .take(GRID_POOL_TRACKS),
                // Artless playlists only make the cut when there aren't
                // enough with artwork (see buildWidgetGrid).
                playlists = playlistsDeferred.await()
                    .distinctBy { playlist -> playlist.id }
                    .shuffled()
                    .sortedBy { playlist -> playlist.coverArt == null }
                    .take(GRID_POOL_PLAYLISTS),
                cachedAt = System.currentTimeMillis(),
            )
            // Persist failure must not cost the grid — worst case the next
            // open re-fetches instead of reading disk.
            try {
                repository.replaceHomeGridPools(
                    albums = snapshot.albums,
                    tracks = snapshot.tracks,
                    playlists = snapshot.playlists,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
            }
            snapshot
        }

    /**
     * Assemble the 3×4 = 12-cell home widget grid from prepared pools. The
     * design composition: one noted track (1×2) + two plain tracks + three
     * playlists + four albums, of which one carries a rating/review (1×2) —
     * 2+2+3+3+2 = 12 cells. Deterministic given the pools (no shuffling here):
     * the shelf only changes when the pools rotate or a memory signal moves.
     * Leftover candidates top the grid back up toward 12 cells when a signal
     * is missing.
     */
    private suspend fun buildWidgetGrid(
        pools: YoinRepository.HomeGridPoolSnapshot,
        signals: MemorySignals?,
    ): List<HomeWidgetCard> {
        val memory = signals?.memoryCard
        val note = loadNotedTrackCard()
        // Covers first: an artless item (an Apple Music library playlist with
        // no artwork) reads as a grey hole in the shelf, so it only fills in
        // behind the ones with art — inside each pool here, and across the
        // whole shelf below (so a spare album with art beats an artless
        // playlist). Stable sorts: the persisted shuffle order holds within
        // each group, and the 2+2+3+3+2 recipe is unchanged whenever there is
        // enough art to fill it.
        val albumPool = pools.albums
            .filterNot { album -> album.id == memory?.second }
            .map { album -> album.toWidgetCard() }
            .sortedBy { card -> card.coverArtUrl == null }
        val trackPool = pools.tracks
            .filterNot { track -> track.id == note?.second }
            .map { track -> track.toWidgetCard() }
            .sortedBy { card -> card.coverArtUrl == null }
        val playlistPool = pools.playlists
            .map { playlist -> playlist.toWidgetCard() }
            .sortedBy { card -> card.coverArtUrl == null }

        val wideCards = listOfNotNull(memory?.first, note?.first)
        val primary = interleaveCards(
            albumPool.take(3),
            trackPool.take(2),
            playlistPool.take(3),
        )
        val extras = interleaveCards(
            albumPool.drop(3),
            trackPool.drop(2),
            playlistPool.drop(3),
        )
        return wideCards + (primary + extras)
            .sortedBy { card -> card.coverArtUrl == null }
            .take(GRID_MAX_COMPACTS)
    }

    /**
     * ONE memory-candidate build per load / signal tick, shared by the header
     * pill (latest memory + notes count) and the grid's memory 1×2. Null =
     * unscoped, failed, or the scope moved mid-build — callers keep what they
     * already show instead of flashing an empty pill.
     */
    private suspend fun loadMemorySignals(preferJbiRawAlbumId: String? = null): MemorySignals? {
        val providerId = repository.currentProviderId() ?: return null
        val profileId = activeProfileId.value?.takeIf { it.isNotBlank() } ?: return null
        val candidates = try {
            repository.getAlbumMemoryCandidates(limit = MEMORY_CANDIDATE_LIMIT)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return null
        }
        val noteCount = guardedOrNull { repository.countNotes() } ?: return null
        if (!matchesCurrentScope(providerId, profileId)) return null
        val pill = buildHomeMemoryPill(candidates, noteCount, scope = homeScopeKey(providerId, profileId))
        val memoryCard = pickJbiMemoryCandidate(
            candidates = candidates,
            avoidRawAlbumId = pill.latest?.albumId?.rawId,
            preferRawAlbumId = preferJbiRawAlbumId,
        )?.let { candidate -> toMemoryCard(candidate) }
        return MemorySignals(pill = pill, memoryCard = memoryCard)
    }

    /** The album a memory 1×2 points at, recovered from its stable id. */
    private fun memoryCardAlbumId(card: HomeWidgetCard): MediaId? {
        // stableId = "grid-memory:<provider>:<rawAlbumId>"; raw ids may hold ':'.
        val rest = card.stableId.removePrefix("grid-memory:")
        val provider = rest.substringBefore(':', missingDelimiterValue = "")
        val rawId = rest.substringAfter(':', missingDelimiterValue = "")
        return if (provider.isNotBlank() && rawId.isNotBlank()) MediaId(provider, rawId) else null
    }

    /**
     * The rated/reviewed album 1×2 for [candidate] (see [pickJbiMemoryCandidate]).
     * Returns the card plus the album's [MediaId] so the plain-album pool can
     * exclude it. Tapping pushes into the Memories deck stopped on this album.
     */
    private suspend fun toMemoryCard(candidate: AlbumMemoryCandidate): Pair<HomeWidgetCard, MediaId> {
        val rawAlbumId = MediaId.storedRawId(candidate.provider, candidate.albumId)
        val albumId = MediaId(candidate.provider, rawAlbumId)
        val hasReview = candidate.hasAlbumReview
        // 首页从此渲染 AI 拟题而不是乐评全文（v2.2 印章卡决定）：只读缓存
        // （Memories 卡打开时才触发生成），miss 走 deterministic 模板 ——
        // 拟题槽永不为空，也永不在首页引爆一次 Gemini 请求。
        val memoryTitle = guardedOrNull { repository.getCachedAlbumMemoryTitle(albumId) }
            ?: deterministicMemoryTitle(
                ratedTrackCount = candidate.ratedTrackCount,
                totalTrackCount = candidate.totalTracks,
                noteCount = candidate.noteCount,
                hasAlbumReview = hasReview,
            )
        val rating = candidate.albumRating ?: candidate.averageSongRating
        // A reviewed memory dates itself off its last touch (the Figma "record"
        // card); an auto-averaged one shows what the score rests on.
        val basis = when {
            hasReview -> (candidate.lastPlayedAt ?: candidate.firstPlayedAt)?.let(::formatMemoryDate)
            candidate.ratedTrackCount > 0 && candidate.totalTracks > 0 ->
                "Based on ${candidate.ratedTrackCount}/${candidate.totalTracks} tracks"
            else -> null
        }
        val card = HomeWidgetCard(
            stableId = "grid-memory:${candidate.provider}:$rawAlbumId",
            entityType = MemoryEntityType.ALBUM,
            title = candidate.albumName,
            subtitle = candidate.artistName?.takeIf { it.isNotBlank() }
                ?.let { artist -> "Album · $artist" }
                ?: "Album",
            coverArtUrl = candidate.coverArtUrl,
            ratingText = formatMemoryScore(rating),
            ratingBasis = basis,
            comment = memoryTitle,
            commentIsHeadline = true,
            expanded = true,
            target = HomeWidgetTarget.MemoryFocus(candidate.sessionId),
        )
        return card to albumId
    }

    /**
     * The noted-track 1×2: the most recently touched song note, enriched
     * best-effort with the track's rating and last-play metadata (cover,
     * duration) so the card can play the song on tap.
     */
    private suspend fun loadNotedTrackCard(): Pair<HomeWidgetCard, MediaId>? {
        val note = guardedList { repository.getRecentSongNotes(limit = 1) }.firstOrNull()
            ?: return null
        val trackId = MediaId(note.provider, note.trackId)
        val rating = guardedOrNull { repository.getRating(trackId).first()?.rating }
        val recentPlay = guardedOrNull { repository.getMostRecentPlay(trackId) }
        val coverRef = CoverRef.fromStorageKey(recentPlay?.coverArtId)
        val song = Track(
            id = trackId,
            title = note.title,
            artist = note.artist.takeIf { it.isNotBlank() },
            artistId = null,
            album = recentPlay?.album?.takeIf { it.isNotBlank() },
            albumId = recentPlay?.albumId?.takeIf { it.isNotBlank() }
                ?.let { MediaId(note.provider, it) },
            coverArt = coverRef,
            durationSec = recentPlay?.durationMs?.takeIf { it > 0L }?.let { (it / 1000L).toInt() },
            trackNumber = null,
            year = null,
            genre = null,
            userRating = null,
        )
        val card = HomeWidgetCard(
            stableId = "grid-note:${note.id}",
            entityType = MemoryEntityType.SONG,
            title = note.title,
            subtitle = note.artist.takeIf { it.isNotBlank() }
                ?.let { artist -> "Single · $artist" }
                ?: "Single",
            coverArtUrl = repository.resolveCoverUrl(coverRef, size = 480),
            ratingText = formatMemoryScore(rating),
            ratingBasis = formatMemoryDate(note.updatedAt),
            comment = note.content,
            expanded = true,
            target = HomeWidgetTarget.PlaySong(song),
        )
        return card to trackId
    }

    private suspend fun loadGridTracks(): List<Track> =
        if (Capability.RANDOM_SONGS in repository.currentCapabilities()) {
            repository.getRandomSongs(size = GRID_TRACK_REQUEST_SIZE)
        } else {
            // No random-songs endpoint (Spotify) — sample the saved library.
            repository.getStarred().tracks.shuffled()
        }

    private fun Album.toWidgetCard(): HomeWidgetCard = HomeWidgetCard(
        stableId = "grid-album:$id",
        entityType = MemoryEntityType.ALBUM,
        title = name,
        subtitle = artist?.takeIf { it.isNotBlank() }?.let { "Album · $it" } ?: "Album",
        coverArtUrl = repository.resolveCoverUrl(coverArt, size = 480)
            ?: id.takeIf { it.provider == MediaId.PROVIDER_SUBSONIC }
                ?.let { repository.resolveCoverUrl(CoverRef.SourceRelative(it.rawId), size = 480) },
        target = HomeWidgetTarget.AlbumDetail(id.toString()),
    )

    private fun Track.toWidgetCard(): HomeWidgetCard = HomeWidgetCard(
        stableId = "grid-song:$id",
        entityType = MemoryEntityType.SONG,
        title = title.orEmpty(),
        subtitle = artist?.takeIf { it.isNotBlank() }?.let { "Single · $it" } ?: "Single",
        coverArtUrl = repository.resolveCoverUrl(coverArt, size = 480)
            ?: albumId?.takeIf { it.provider == MediaId.PROVIDER_SUBSONIC }
                ?.let { repository.resolveCoverUrl(CoverRef.SourceRelative(it.rawId), size = 480) },
        target = HomeWidgetTarget.PlaySong(this),
    )

    private fun Playlist.toWidgetCard(): HomeWidgetCard = HomeWidgetCard(
        stableId = "grid-playlist:$id",
        entityType = MemoryEntityType.PLAYLIST,
        title = name,
        subtitle = owner?.takeIf { it.isNotBlank() && it != "Playlist" }
            ?.let { "Playlist · $it" }
            ?: "Playlist",
        coverArtUrl = repository.resolveCoverUrl(coverArt, size = 480),
        target = HomeWidgetTarget.PlaylistDetail(id.toString()),
    )

    /**
     * Round-robin across the pools so the compact covers mix types instead of
     * clumping (album, track, playlist, album, …), matching the Figma spread.
     */
    private fun interleaveCards(vararg groups: List<HomeWidgetCard>): List<HomeWidgetCard> {
        val iterators = groups.map { it.iterator() }
        val result = mutableListOf<HomeWidgetCard>()
        var appended = true
        while (appended) {
            appended = false
            for (iterator in iterators) {
                if (iterator.hasNext()) {
                    result += iterator.next()
                    appended = true
                }
            }
        }
        return result
    }

    /**
     * "2024 · 12 songs · 44 min" for the hero bento slot — the same entry
     * [selectHomeHeroActivity] crowns for the UI (first album/playlist).
     * Albums only: playlist metadata isn't disk-cached, so a playlist hero
     * just skips the line. Resolved through the detail cache
     * ([YoinRepository.getAlbum] is LRU + disk backed), so this is usually a
     * local read; any failure just drops the line.
     */
    private suspend fun loadActivityHeroFootnote(activities: List<ActivityEvent>): String? {
        val hero = selectHomeHeroActivity(activities) ?: return null
        if (hero.entityType != ActivityEntityType.ALBUM.name) return null
        val album = try {
            repository.getAlbum(MediaId(hero.provider, MediaId.storedRawId(hero.provider, hero.entityId)))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        } ?: return null
        val parts = mutableListOf<String>()
        album.year?.let { parts += it.toString() }
        album.songCount?.takeIf { count -> count > 0 }?.let { parts += if (it == 1) "1 song" else "$it songs" }
        album.durationSec?.takeIf { seconds -> seconds > 60 }?.let { parts += "${it / 60} min" }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private suspend fun <T> guardedList(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        emptyList()
    }

    private suspend fun <T> guardedOrNull(block: suspend () -> T?): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    private fun formatMemoryScore(rating: Float?): String =
        if (rating != null && rating > 0f) {
            String.format(Locale.US, "%.1f", rating)
        } else {
            "N/A"
        }

    private fun formatMemoryDate(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(MemoryDateFormatter)

    private fun matchesCurrentScope(
        providerId: String?,
        profileId: String?,
    ): Boolean = repository.currentProviderId() == providerId &&
        activeProfileId.value == profileId

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(
                repository = container.repository,
                activeProfileId = container.profileManager.activeProfileId,
                homeLayoutStore = container.homeLayoutStore,
            ) as T
    }

    private companion object {
        // The widget grid: at most two wide signal cards (so at most two
        // cards ever do the extra review/note lookups) plus up to 24 covers.
        // A phone shows the first 12 cells (trimToPhoneShelf: the 3 × 4
        // shelf, unchanged); a tablet template seats as many as its phone-
        // sized columns take — 10 columns × 3 rows with no signal cards and
        // two 2×2 features is the deepest, 24 covers (HomeJbiTemplate.kt).
        private const val GRID_MAX_COMPACTS = 24

        // Recent activities the feed keeps (unique entities, songs included —
        // the bento drops songs). The widest unit bento seats 13 plus a hero,
        // so 20 often ran it on its degrade path once songs were filtered out.
        private const val HOME_ACTIVITY_LIMIT = 40
        private const val GRID_ALBUM_REQUEST_SIZE = 24
        private const val GRID_TRACK_REQUEST_SIZE = 16
        // Same pool as the Memories deck (its ensureCandidates uses 48), so
        // what the pill shows is what the deck holds. The builder scans 48
        // seeds either way; only the final take() differs.
        private const val MEMORY_CANDIDATE_LIMIT = 48

        // Persisted pool sizes (enough for GRID_MAX_COMPACTS covers even when
        // dedup bites and a library is short on playlists) and the rotation
        // cadence: the shelf re-rolls at most every 6 hours, otherwise it
        // reads from disk with zero network.
        private const val GRID_POOL_ALBUMS = 12
        private const val GRID_POOL_TRACKS = 8
        private const val GRID_POOL_PLAYLISTS = 8
        private const val GRID_POOLS_TTL_MS = 6L * 60L * 60L * 1000L

        // "Recently Added" home shelf: library items added within the last week.
        // Tracks fill a fixed 2×2 grid (4 cells); albums scroll horizontally, so
        // they get a deeper cap.
        private const val RECENTLY_ADDED_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
        private const val RECENTLY_ADDED_TRACK_LIMIT = 4
        private const val RECENTLY_ADDED_ALBUM_LIMIT = 12

        // Coalesces activity-event bursts (track skips, detail visits) before
        // rebuilding the live activities feed.
        private const val RECENT_HISTORY_DEBOUNCE_MS = 1_000L
        private val MemoryDateFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMM d", Locale.US)
        private val homeContentCache = mutableMapOf<String, HomeUiState.Content>()

        private fun homeScopeKey(providerId: String?, profileId: String?): String =
            "${providerId.orEmpty()}|${profileId.orEmpty()}"
    }
}

/**
 * Parse a library "added at" / "starred at" ISO-8601 string to epoch millis,
 * tolerating the shapes seen across providers: an instant with `Z` (Spotify
 * `added_at`), an offset date-time, a zone-less date-time (legacy Subsonic
 * `starred`, read as UTC), and date-only. Unparseable / blank → null, so the
 * item is dropped from the shelf.
 */
private fun parseAddedAtMillis(addedAt: String?): Long? {
    if (addedAt.isNullOrBlank()) return null
    return runCatching { Instant.parse(addedAt).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(addedAt).toInstant().toEpochMilli() }
        .recoverCatching {
            LocalDateTime.parse(addedAt).toInstant(ZoneOffset.UTC).toEpochMilli()
        }
        .recoverCatching {
            LocalDate.parse(addedAt).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        .getOrNull()
}

/** The shared memory read: header pill + the grid's memory 1×2 (card, album id). */
private data class MemorySignals(
    val pill: HomeMemoryPill,
    val memoryCard: Pair<HomeWidgetCard, MediaId?>?,
)

/**
 * The grid's memory 1×2 keeps the owner's recipe — the strongest memory with
 * a review, else with any rating — but prefers a different album than the
 * header pill already shows; the same album only when nothing else qualifies.
 * [preferRawAlbumId] (the album a live splice already shows) wins while it
 * still qualifies, so a write on it updates the card in place instead of
 * swapping albums under the user.
 */
internal fun pickJbiMemoryCandidate(
    candidates: List<AlbumMemoryCandidate>,
    avoidRawAlbumId: String?,
    preferRawAlbumId: String? = null,
): AlbumMemoryCandidate? {
    fun AlbumMemoryCandidate.rawAlbumId(): String = MediaId.storedRawId(provider, albumId)
    fun AlbumMemoryCandidate.qualifies(): Boolean =
        hasAlbumReview || albumRating != null || averageSongRating != null
    fun pick(pool: List<AlbumMemoryCandidate>): AlbumMemoryCandidate? =
        pool.firstOrNull { it.hasAlbumReview }
            ?: pool.firstOrNull { it.albumRating != null || it.averageSongRating != null }
    if (preferRawAlbumId != null) {
        candidates.firstOrNull { it.rawAlbumId() == preferRawAlbumId && it.qualifies() }
            ?.let { return it }
    }
    val others = candidates.filterNot { candidate -> candidate.rawAlbumId() == avoidRawAlbumId }
    return pick(others) ?: pick(candidates)
}

/** Recently-added library split by kind for the two halves of the shelf. */
private data class RecentlyAdded(
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
)

/**
 * Keep only items whose [addedAt] parses to at-or-after [cutoffMillis], newest
 * first, deduped by [key], capped at [limit]. Shared by the recently-added
 * track and album lists so both apply the same window / ordering. Dedup guards
 * the album shelf's keyed `LazyRow` against a provider that lists the same id
 * twice (some Subsonic servers repeat starred entries across folders).
 */
private inline fun <T> List<T>.withinRecentlyAddedWindow(
    cutoffMillis: Long,
    limit: Int,
    key: (T) -> Any,
    addedAt: (T) -> String?,
): List<T> = mapNotNull { item -> parseAddedAtMillis(addedAt(item))?.let { millis -> millis to item } }
    .filter { (addedMs, _) -> addedMs >= cutoffMillis }
    .sortedByDescending { (addedMs, _) -> addedMs }
    .map { (_, item) -> item }
    .distinctBy(key)
    .take(limit)
