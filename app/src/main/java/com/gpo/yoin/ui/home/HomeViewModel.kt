package com.gpo.yoin.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.R
import com.gpo.yoin.data.home.HomeLayoutStore
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.local.SongMemoryAggregate
import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.memory.AlbumMemoryTitleSource
import com.gpo.yoin.data.memory.REDISCOVER_AWAY_MS
import com.gpo.yoin.data.memory.RediscoverPick
import com.gpo.yoin.data.memory.RediscoverSongPick
import com.gpo.yoin.data.memory.RediscoverSongSource
import com.gpo.yoin.data.memory.memoryEligible
import com.gpo.yoin.data.memory.selectRediscover
import com.gpo.yoin.data.memory.selectRediscoverShelf
import com.gpo.yoin.data.memory.selectRediscoverSongs
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.perf.YoinPerf
import com.gpo.yoin.ui.common.UiText
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryScoreKind
import com.gpo.yoin.ui.memories.ResolvedMemoryTitle
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: YoinRepository,
    // Public so the shell can exit edit mode on profile switch: the edit
    // draft belongs to the old profile and must never be persisted into the
    // new one.
    val activeProfileId: StateFlow<String?>,
    private val homeLayoutStore: HomeLayoutStore,
    private val homeEditHintStore: HomeEditHintStore = HomeEditHintStore.InMemory(),
    // Rediscover's clock (the 90-day rule, plays observed since subscription).
    private val nowMillis: () -> Long = System::currentTimeMillis,
    // Rediscover's songs (rated / noted tracks with their Yoin history). The
    // Factory reads Room directly until YoinRepository grows a twin.
    private val rediscoverSongs: RediscoverSongSource = RediscoverSongSource.None,
    // The memory card's title: the shared resolver Memories and the album page
    // use too (user > AI > album name). Null = the album name.
    private val memoryTitle: suspend (AlbumMemoryCandidate) -> ResolvedMemoryTitle? = { null },
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // Edit-mode freeze: while Home is being edited, content updates queue in
    // [frozenState] instead of reshaping the feed under the user's hands.
    // Every publish goes through [emit] and every splice reads
    // [currentContent], so splices made while frozen build on each other.
    private var editing = false
    private var frozenState: HomeUiState? = null

    // Debug-only: `home.content` marks the first Content only. Declared before
    // init, which can publish a cached Content synchronously.
    private var perfContentMarked = false

    // Which provider|profile the Content in [_uiState] belongs to. The live
    // observers only splice into content of the CURRENT scope: right after a
    // profile switch the screen may still hold the old profile's feed (until
    // refresh repaints), and a tick must never graft one profile onto another.
    private var contentScopeKey: String? = null

    // Albums and songs played since this VM started, per provider|profile:
    // Rediscover drops them (and the songs of those albums), so a build that
    // began before the play can't put a removed card back.
    private val playedRediscoverRawIds = mutableMapOf<String, MutableSet<String>>()
    private val playedRediscoverSongRawIds = mutableMapOf<String, MutableSet<String>>()

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

    // SharedPreferences isn't observable: [onEditSessionStarted] re-reads the
    // store into this after marking, which re-emits [unseenNewSections].
    private val seenSectionIds = MutableStateFlow(homeEditHintStore.seenSectionIds())

    /**
     * Sections appended hidden to this profile's customized layout that the
     * user hasn't met in the edit tray yet (drives the "Edit Home" badge).
     */
    val unseenNewSections: StateFlow<Set<HomeSection>> =
        combine(homeLayout, seenSectionIds) { layout, seen -> unseenNewSectionsOf(layout, seen) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        // The constructor's Loading is what a cold start shows until the first Content.
        if (YoinPerf.enabled) YoinPerf.mark("home.loading", "ms_since_process_start" to YoinPerf.sinceProcessStart())
        refresh()
        observeRecentHistory()
        observeMemorySignals()
        observeRediscoverRemovals()
    }

    /**
     * Persist a new home layout for the active profile (no-op with no profile).
     * A layout that arrives without retained ids keeps the current ones, and one
     * equal to the catalog defaults clears the row — back to "never customized",
     * so new sections follow their defaults again. The store skips a write that
     * matches the stored row.
     */
    fun setHomeLayout(layout: HomeLayout) {
        val profileId = activeProfileId.value?.takeIf { it.isNotBlank() } ?: return
        val next = if (layout.retained.isEmpty()) layout.copy(retained = homeLayout.value.retained) else layout
        viewModelScope.launch {
            if (next.isDefault && next.retained.isEmpty()) {
                homeLayoutStore.clearLayout(profileId)
            } else {
                homeLayoutStore.setLayout(profileId, next.toPrefs())
            }
        }
    }

    /** Freeze the feed while Home is in edit mode; unfreezing publishes the latest queued state. */
    fun setEditing(editing: Boolean) {
        if (this.editing == editing) return
        this.editing = editing
        if (!editing) {
            frozenState?.let { _uiState.value = it }
            frozenState = null
        }
    }

    /**
     * Called once when an edit session starts: snapshots this session's hints,
     * then records the session and marks its "New" sections seen. Writes no
     * home layout.
     */
    fun onEditSessionStarted(): HomeEditSessionHints {
        val hints = HomeEditSessionHints(
            showHeaderHint = showEditHeaderHint(homeEditHintStore.editSessionCount()),
            newBadges = unseenNewSectionsOf(homeLayout.value, seenSectionIds.value),
        )
        homeEditHintStore.recordEditSession()
        if (hints.newBadges.isNotEmpty()) {
            homeEditHintStore.markSectionsSeen(hints.newBadges.map { it.id })
            seenSectionIds.value = homeEditHintStore.seenSectionIds()
        }
        return hints
    }

    /** [perfSrc] labels a Content for the debug-only `home.content` mark: mem | disk | fresh. */
    private fun emit(state: HomeUiState, perfSrc: String? = null) {
        markPerf(state, perfSrc)
        if (editing) frozenState = state else _uiState.value = state
    }

    /** The newest content, queued or published. */
    private fun currentContent(): HomeUiState.Content? = (frozenState ?: _uiState.value) as? HomeUiState.Content

    fun refresh() {
        val providerId = repository.currentProviderId()
        val profileId = activeProfileId.value
        viewModelScope.launch {
            val perf = YoinPerf.begin("home.refresh")
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

            emit(
                cachedHomeContent
                    ?: cachedSpotifyContent
                    ?: HomeUiState.Loading,
                perfSrc = if (cachedHomeContent != null) "mem" else "disk"
            )
            contentScopeKey = scopeKey

            try {
                val freshContent = when {
                    providerId == MediaId.PROVIDER_SPOTIFY && !profileId.isNullOrBlank() ->
                        loadSpotifyHomeContent()

                    else -> loadHomeContent()
                }
                if (!matchesCurrentScope(providerId, profileId)) {
                    if (perf != null) YoinPerf.end(perf, "provider" to providerId, "result" to "superseded")
                    return@launch
                }
                homeContentCache[scopeKey] = freshContent
                emit(freshContent, perfSrc = "fresh")
                if (perf != null) YoinPerf.end(perf, "provider" to providerId, "result" to "ok")
                contentScopeKey = scopeKey
            } catch (e: Exception) {
                if (perf != null) {
                    YoinPerf.end(perf, "provider" to providerId, "result" to "error", "err" to e.javaClass.simpleName)
                }
                if (!matchesCurrentScope(providerId, profileId)) return@launch
                if (cachedSpotifyContent == null) {
                    val detail = e.message
                    emit(
                        HomeUiState.Error(
                            message = detail ?: "Failed to load home content",
                            messageText = if (detail != null) {
                                UiText.Raw(detail)
                            } else {
                                UiText.Res(R.string.home_error_load_failed)
                            },
                        ),
                    )
                }
            }
        }
    }

    /**
     * Debug-only Home marks (docs/perf/yoinperf-logging.md), on publish:
     * `home.loading` when the feed falls back to Loading (a refresh with
     * nothing cached to paint), and `home.content` once, for the first
     * Content this VM publishes.
     */
    private fun markPerf(next: HomeUiState, src: String?) {
        if (!YoinPerf.enabled) return
        if (next is HomeUiState.Loading) {
            if ((frozenState ?: _uiState.value) !is HomeUiState.Loading) {
                YoinPerf.mark("home.loading", "ms_since_process_start" to YoinPerf.sinceProcessStart())
            }
            return
        }
        val content = next as? HomeUiState.Content ?: return
        if (perfContentMarked) return
        perfContentMarked = true
        val sections = listOf(
            content.activities.isNotEmpty(),
            content.widgetGrid.isNotEmpty(),
            content.recentlyAddedTracks.isNotEmpty() || content.recentlyAddedAlbums.isNotEmpty(),
            content.rediscover.isNotEmpty(),
            content.recentlyPlayed.isNotEmpty(),
            content.playlists.isNotEmpty()
        ).count { it }
        YoinPerf.mark(
            "home.content",
            "ms_since_process_start" to YoinPerf.sinceProcessStart(),
            "sections" to sections,
            "src" to src
        )
    }

    /** The pill last shown for the current scope — kept when a fresh read can't resolve one. */
    private fun cachedMemoryPill(): HomeMemoryPill? =
        homeContentCache[homeScopeKey(repository.currentProviderId(), activeProfileId.value)]?.memoryPill

    /** Rediscover as last shown for the current scope — kept when a fresh read can't resolve signals. */
    private fun cachedRediscover(): List<HomeRediscoverItem> =
        homeContentCache[homeScopeKey(repository.currentProviderId(), activeProfileId.value)]?.rediscover.orEmpty()

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
            val playlistsDeferred = async { loadPlaylists() }
            val recentlyPlayedDeferred = async { loadRecentlyPlayed() }
            // Parallel with the grid/shelf loads: on a cold detail cache this can
            // be a network fetch, and it must not serialize the first paint.
            val heroFootnoteDeferred = async {
                loadActivityHeroFootnote(activitiesDeferred.await())
            }

            val recentlyAdded = recentlyAddedDeferred.await()
            val signals = signalsDeferred.await()
            val widgetGrid = widgetGridDeferred.await()
            val pill = signals?.pill ?: cachedMemoryPill()
            val hero = heroFootnoteDeferred.await()
            HomeUiState.Content(
                activities = activitiesDeferred.await(),
                activityHeroFootnote = hero.text,
                activityHeroYear = hero.year,
                activityHeroSongCount = hero.songCount,
                activityHeroMinutes = hero.minutes,
                widgetGrid = widgetGrid,
                recentlyAddedTracks = recentlyAdded.tracks,
                recentlyAddedAlbums = recentlyAdded.albums,
                memoryPill = pill,
                rediscover = rediscoverFor(signals, widgetGrid, pill, recentlyAdded, cachedRediscover()),
                playlists = playlistsDeferred.await(),
                recentlyPlayed = recentlyPlayedDeferred.await().notShownIn(activitiesDeferred.await()),
            )
        }

    /** Recently Played: the provider's own list; unsupported or failing hides the shelf. */
    private suspend fun loadRecentlyPlayed(): List<Album> =
        guardedList { repository.getRecentlyPlayedAlbums(HOME_RECENTLY_PLAYED_LIMIT) }
            .distinctBy { it.id }

    /** Albums the Activities feed doesn't already show (its album cards and played tracks' albums). */
    private fun List<Album>.notShownIn(activities: List<ActivityEvent>): List<Album> {
        val shown = activities.mapNotNullTo(HashSet()) { event ->
            event.albumId ?: event.entityId.takeIf { event.entityType.equals("album", ignoreCase = true) }
        }
        return filterNot { album -> album.id.toString() in shown || album.id.rawId in shown }
    }

    /** Your Playlists: the first [HOME_PLAYLIST_LIMIT] of the library's playlists; a failure hides the shelf. */
    private suspend fun loadPlaylists(): List<Playlist> =
        guardedList { repository.getPlaylists() }
            .distinctBy { it.id }
            .take(HOME_PLAYLIST_LIMIT)

    /**
     * Library items added within the last 30 days, newest first (a week until
     * owner 2026-10-05 — the taller tablet section ran short). Provider-agnostic:
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
            val signals = signalsDeferred.await()
            val pill = signals?.pill ?: cachedMemoryPill()
            HomeUiState.Content(
                activities = activities,
                widgetGrid = widgetGrid,
                memoryPill = pill,
                // Pre-paint: no Recently Added yet, so nothing of it to dedupe against.
                rediscover = rediscoverFor(signals, widgetGrid, pill, RecentlyAdded(), cachedRediscover()),
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
        val playlistsDeferred = async { loadPlaylists() }
        val recentlyPlayedDeferred = async { loadRecentlyPlayed() }

        val (activities, activitiesFromRemote) = activitiesDeferred.await()
        val heroFootnoteDeferred = async { loadActivityHeroFootnote(activities) }
        val recentlyAdded = recentlyAddedDeferred.await()
        val signals = signalsDeferred.await()
        val widgetGrid = widgetGridDeferred.await()
        val pill = signals?.pill ?: cachedMemoryPill()
        val hero = heroFootnoteDeferred.await()
        HomeUiState.Content(
            activities = activities,
            activitiesFromRemote = activitiesFromRemote,
            activityHeroFootnote = hero.text,
            activityHeroYear = hero.year,
            activityHeroSongCount = hero.songCount,
            activityHeroMinutes = hero.minutes,
            widgetGrid = widgetGrid,
            recentlyAddedTracks = recentlyAdded.tracks,
            recentlyAddedAlbums = recentlyAdded.albums,
            memoryPill = pill,
            rediscover = rediscoverFor(signals, widgetGrid, pill, recentlyAdded, cachedRediscover()),
            playlists = playlistsDeferred.await(),
            recentlyPlayed = recentlyPlayedDeferred.await().notShownIn(activities),
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
                    val currentContent = currentContent() ?: return@collectLatest
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
                        null
                    } else {
                        loadActivityHeroFootnote(effectiveActivities)
                    }
                    // Apply to the LATEST content, owning only the activity
                    // fields: a snapshot write would revert a pill / grid
                    // update that landed while the footnote loaded.
                    if (!matchesCurrentScope(providerId, profileId) || contentScopeKey != scopeKey) {
                        return@collectLatest
                    }
                    val latest = currentContent() ?: return@collectLatest
                    val nextContent = if (footnote == null) {
                        latest.copy(
                            activities = effectiveActivities,
                            activitiesFromRemote = keepEndpointFeed,
                        )
                    } else {
                        latest.copy(
                            activities = effectiveActivities,
                            activitiesFromRemote = keepEndpointFeed,
                            activityHeroFootnote = footnote.text,
                            activityHeroYear = footnote.year,
                            activityHeroSongCount = footnote.songCount,
                            activityHeroMinutes = footnote.minutes,
                        )
                    }
                    homeContentCache[scopeKey] = nextContent
                    emit(nextContent)
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
                    val currentContent = currentContent() ?: return@collectLatest
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
                    val latest = currentContent() ?: return@collectLatest
                    // Unscoped / failed build: keep what's on screen rather
                    // than flashing an empty pill.
                    val refreshedPill = signals?.pill ?: latest.memoryPill
                    // A refresh that landed meanwhile already rebuilt the grid
                    // with fresh signal cards; don't splice an older grid over it.
                    val nextGrid = if (latest.widgetGrid == currentContent.widgetGrid) refreshedGrid else latest.widgetGrid
                    // Picked against the grid and pill being published, so its
                    // dedupe always matches what's on screen.
                    val refreshedRediscover = rediscoverFor(
                        signals = signals,
                        grid = nextGrid,
                        pill = refreshedPill,
                        recentlyAdded = RecentlyAdded(latest.recentlyAddedTracks, latest.recentlyAddedAlbums),
                        fallback = latest.rediscover,
                    )
                    if (
                        nextGrid == latest.widgetGrid &&
                        refreshedPill == latest.memoryPill &&
                        refreshedRediscover == latest.rediscover
                    ) {
                        return@collectLatest
                    }
                    val nextContent = latest.copy(
                        widgetGrid = nextGrid,
                        memoryPill = refreshedPill,
                        rediscover = refreshedRediscover,
                    )
                    homeContentCache[scopeKey] = nextContent
                    emit(nextContent)
                }
        }
    }

    /**
     * Take a Rediscover card off the shelf once it plays: an album card when
     * any of its tracks plays, a song card when the song or its album does.
     * Watches the newest play_history row on its own (folding it into the
     * memory stamp would rebuild every candidate on each track change) and
     * only acts on plays from subscription on: the row that exists already
     * may be months old, and for a returning user its card can rightly be on
     * the shelf. Goes through [emit], so a removal queues while Home is being
     * edited.
     */
    private fun observeRediscoverRemovals() {
        viewModelScope.launch {
            val observedSince = nowMillis()
            repository.observeMostRecentPlay()
                .filterNotNull()
                .filter { play -> play.playedAt >= observedSince }
                .collect { play ->
                    val scopeKey = homeScopeKey(play.provider, play.profileId)
                    val rawAlbumId = play.albumId.takeIf(String::isNotBlank)
                        ?.let { albumId -> MediaId.storedRawId(play.provider, albumId) }
                    val rawSongId = play.songId.takeIf(String::isNotBlank)
                        ?.let { songId -> MediaId.storedRawId(play.provider, songId) }
                    rawAlbumId?.let { playedRediscoverRawIds.getOrPut(scopeKey) { mutableSetOf() } += it }
                    rawSongId?.let { playedRediscoverSongRawIds.getOrPut(scopeKey) { mutableSetOf() } += it }
                    if (contentScopeKey != scopeKey) return@collect
                    val latest = currentContent() ?: return@collect
                    val played = { item: HomeRediscoverItem ->
                        item.playedBy(setOfNotNull(rawAlbumId), setOfNotNull(rawSongId))
                    }
                    if (latest.rediscover.none(played)) return@collect
                    val nextContent = latest.copy(rediscover = latest.rediscover.filterNot(played))
                    homeContentCache[scopeKey] = nextContent
                    emit(nextContent)
                }
        }
    }

    /**
     * Rediscover for the content about to publish: album and song cards on
     * one shelf ([selectRediscoverShelf]). Dedupes against what that content
     * shows — the grid's memory 1×2 and the pill's latest album, the grid's
     * noted-track 1×2 song, and every album and track [recentlyAdded] loaded
     * (the shelf seats 12–20 of them by width; the ViewModel can't tell which,
     * so all of them) — and drops what played this session (a played album
     * takes its songs along). A null [signals] (unscoped / failed build) keeps
     * [fallback], minus anything played since or now in Recently Added.
     */
    private fun rediscoverFor(
        signals: MemorySignals?,
        grid: List<HomeWidgetCard>,
        pill: HomeMemoryPill?,
        recentlyAdded: RecentlyAdded,
        fallback: List<HomeRediscoverItem>,
    ): List<HomeRediscoverItem> {
        val scopeKey = homeScopeKey(repository.currentProviderId(), activeProfileId.value)
        val playedAlbums = playedRediscoverRawIds[scopeKey].orEmpty()
        val playedSongs = playedRediscoverSongRawIds[scopeKey].orEmpty()
        val addedAlbums = recentlyAdded.albums.mapTo(HashSet()) { album -> album.id.rawId }
        val addedTracks = recentlyAdded.tracks.mapTo(HashSet()) { track -> track.id.rawId }
        if (signals == null) {
            return fallback.filterNot { item ->
                item.playedBy(playedAlbums, playedSongs) || item.shownIn(addedAlbums, addedTracks)
            }
        }
        val exclude = buildSet {
            grid.firstOrNull { it.target is HomeWidgetTarget.MemoryFocus }
                ?.let(::memoryCardAlbumId)
                ?.let { add(it.rawId) }
            pill?.latest?.albumId?.rawId?.let(::add)
            addAll(addedAlbums)
            addAll(playedAlbums)
        }
        val notedSong = grid.firstNotNullOfOrNull { card ->
            (card.target as? HomeWidgetTarget.PlaySong)?.song?.id?.rawId?.takeIf { card.expanded }
        }
        val now = nowMillis()
        val albums = selectRediscover(signals.pool, now, exclude, limit = Int.MAX_VALUE)
        val songs = selectRediscoverSongs(
            songs = signals.songs,
            nowMillis = now,
            excludeRawSongIds = playedSongs + addedTracks + listOfNotNull(notedSong),
            // A song card wears its album's cover and name: one whose album
            // Recently Added shows is that album twice on the page.
            excludeRawAlbumIds = playedAlbums + addedAlbums,
        )
        return selectRediscoverShelf(albums, songs).mapNotNull { entry ->
            when (entry) {
                is RediscoverPick -> toRediscoverItem(entry)
                is RediscoverSongPick -> toRediscoverSongItem(entry)
            }
        }
    }

    /** A Rediscover song pick as a card, its track rebuilt from play history so a tap can play it. */
    private fun toRediscoverSongItem(pick: RediscoverSongPick): HomeRediscoverItem? {
        val song = pick.song
        if (song.songId.isBlank() || song.provider.isBlank()) return null
        val track = song.toTrack()
        return HomeRediscoverItem(
            // MediaId never holds a blank id: an album-less song stands in for itself.
            albumId = track.albumId ?: track.id,
            albumName = song.album,
            artistName = song.artist.takeIf(String::isNotBlank),
            coverArtUrl = repository.resolveCoverUrl(track.coverArt, size = 480)
                ?: track.albumId?.takeIf { it.provider == MediaId.PROVIDER_SUBSONIC }
                    ?.let { repository.resolveCoverUrl(CoverRef.SourceRelative(it.rawId), size = 480) },
            score = pick.score,
            scoreText = pick.score?.let(::rediscoverScoreText),
            // Your own track rating: the solid sticker.
            scoreKind = if (pick.score == null) MemoryScoreKind.NONE else MemoryScoreKind.ALBUM_RATING,
            lastPlayedAt = pick.lastPlayedAt,
            firstPlayedAt = song.firstPlayedAt,
            playCount = song.playCount,
            noteCount = song.noteCount,
            song = track,
            noteSnippet = song.latestNote?.takeIf(String::isNotBlank),
        )
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
     * pill (latest memory + notes count), the grid's memory 1×2 and
     * Rediscover. The build includes ineligible albums for Rediscover; the
     * pill and the 1×2 read only its eligible subset, which is exactly the
     * Memory pool. Null = unscoped, failed, or the scope moved mid-build —
     * callers keep what they already show instead of flashing an empty pill.
     */
    private suspend fun loadMemorySignals(preferJbiRawAlbumId: String? = null): MemorySignals? {
        val providerId = repository.currentProviderId() ?: return null
        val profileId = activeProfileId.value?.takeIf { it.isNotBlank() } ?: return null
        val pool = try {
            repository.getAlbumMemoryCandidates(limit = MEMORY_CANDIDATE_LIMIT, includeIneligible = true)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return null
        }
        val candidates = pool.memoryEligible(MEMORY_CANDIDATE_LIMIT)
        val noteCount = guardedOrNull { repository.countNotes() } ?: return null
        // Songs are Rediscover's alone: a failed read just leaves them out.
        val songs = guardedList {
            rediscoverSongs.load(
                provider = providerId,
                profileId = profileId,
                playedBefore = nowMillis() - REDISCOVER_AWAY_MS,
                limit = REDISCOVER_SONG_QUERY_LIMIT,
            )
        }
        if (!matchesCurrentScope(providerId, profileId)) return null
        val pill = buildHomeMemoryPill(candidates, noteCount, scope = homeScopeKey(providerId, profileId))
            .withMemoryTitle(candidates)
        val memoryCard = pickJbiMemoryCandidate(
            candidates = candidates,
            avoidRawAlbumId = pill.latest?.albumId?.rawId,
            preferRawAlbumId = preferJbiRawAlbumId,
        )?.let { candidate -> toMemoryCard(candidate) }
        return MemorySignals(pill = pill, memoryCard = memoryCard, pool = pool, songs = songs)
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
     * The bubble's latest memory with its Memory title, resolved as the card's
     * is (cache-only on the AI side); the bare album name doesn't count.
     */
    private suspend fun HomeMemoryPill.withMemoryTitle(candidates: List<AlbumMemoryCandidate>): HomeMemoryPill {
        val shown = latest ?: return this
        val candidate = candidates.firstOrNull { it.sessionId == shown.sessionId } ?: return this
        val resolved = guardedOrNull { memoryTitle(candidate) }
            ?.takeIf { it.source != AlbumMemoryTitleSource.ALBUM }
            ?: return this
        return copy(latest = shown.copy(memoryTitle = resolved.text))
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
        // Home shows the album's Memory title, not the review text (v2.2 stamp card); resolved the way
        // Memories and the album page resolve it, so one album never reads two titles (owner F6, 2026-10-05).
        // Cache-only on the AI side: Home never triggers a Gemini request.
        val resolvedTitle = guardedOrNull { memoryTitle(candidate) }
        val rating = candidate.albumRating ?: candidate.averageSongRating
        val score = formatMemoryScore(rating)
        // A reviewed memory dates itself off its last touch (the Figma "record"
        // card); an auto-averaged one shows what the score rests on.
        val basisDate = if (hasReview) candidate.lastPlayedAt ?: candidate.firstPlayedAt else null
        val trackBasis = basisDate == null && candidate.ratedTrackCount > 0 && candidate.totalTracks > 0
        val basis = when {
            basisDate != null -> formatMemoryDate(basisDate)
            trackBasis -> "Based on ${candidate.ratedTrackCount}/${candidate.totalTracks} tracks"
            else -> null
        }
        val (subtitle, subtitleText) = albumSubtitle(candidate.artistName)
        val card = HomeWidgetCard(
            stableId = "grid-memory:${candidate.provider}:$rawAlbumId",
            entityType = MemoryEntityType.ALBUM,
            title = candidate.albumName,
            subtitle = subtitle,
            subtitleText = subtitleText,
            coverArtUrl = candidate.coverArtUrl,
            ratingText = score,
            ratingUnavailable = score == "N/A",
            ratingBasis = basis,
            ratingBasisText = if (trackBasis) {
                UiText.Res(
                    R.string.home_widget_basis_tracks,
                    listOf(candidate.ratedTrackCount, candidate.totalTracks),
                )
            } else {
                null
            },
            ratingBasisDateMillis = basisDate,
            // Only a written title (the user's or the AI's): the bare album name is already the card's title.
            comment = resolvedTitle?.takeIf { it.source != AlbumMemoryTitleSource.ALBUM }?.text,
            commentIsHeadline = true,
            commentSerif = resolvedTitle?.isSerif ?: false,
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
        val score = formatMemoryScore(rating)
        val (subtitle, subtitleText) = singleSubtitle(note.artist)
        val card = HomeWidgetCard(
            stableId = "grid-note:${note.id}",
            entityType = MemoryEntityType.SONG,
            title = note.title,
            subtitle = subtitle,
            subtitleText = subtitleText,
            coverArtUrl = repository.resolveCoverUrl(coverRef, size = 480),
            ratingText = score,
            ratingUnavailable = score == "N/A",
            ratingBasis = formatMemoryDate(note.updatedAt),
            ratingBasisDateMillis = note.updatedAt,
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

    private fun Album.toWidgetCard(): HomeWidgetCard {
        val (subtitle, subtitleText) = albumSubtitle(artist)
        return HomeWidgetCard(
            stableId = "grid-album:$id",
            entityType = MemoryEntityType.ALBUM,
            title = name,
            subtitle = subtitle,
            subtitleText = subtitleText,
            coverArtUrl = repository.resolveCoverUrl(coverArt, size = 480)
                ?: id.takeIf { it.provider == MediaId.PROVIDER_SUBSONIC }
                    ?.let { repository.resolveCoverUrl(CoverRef.SourceRelative(it.rawId), size = 480) },
            target = HomeWidgetTarget.AlbumDetail(id.toString()),
        )
    }

    private fun Track.toWidgetCard(): HomeWidgetCard {
        val (subtitle, subtitleText) = singleSubtitle(artist)
        return HomeWidgetCard(
            stableId = "grid-song:$id",
            entityType = MemoryEntityType.SONG,
            title = title.orEmpty(),
            subtitle = subtitle,
            subtitleText = subtitleText,
            coverArtUrl = repository.resolveCoverUrl(coverArt, size = 480)
                ?: albumId?.takeIf { it.provider == MediaId.PROVIDER_SUBSONIC }
                    ?.let { repository.resolveCoverUrl(CoverRef.SourceRelative(it.rawId), size = 480) },
            target = HomeWidgetTarget.PlaySong(this),
        )
    }

    private fun Playlist.toWidgetCard(): HomeWidgetCard {
        val (subtitle, subtitleText) = playlistSubtitle(owner)
        return HomeWidgetCard(
            stableId = "grid-playlist:$id",
            entityType = MemoryEntityType.PLAYLIST,
            title = name,
            subtitle = subtitle,
            subtitleText = subtitleText,
            coverArtUrl = repository.resolveCoverUrl(coverArt, size = 480),
            target = HomeWidgetTarget.PlaylistDetail(id.toString()),
        )
    }

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
     * Year, song count and minutes for the hero bento slot — the same entry
     * [selectHomeHeroActivity] crowns for the UI (first album/playlist).
     * Albums only: playlist metadata isn't disk-cached, so a playlist hero
     * just skips the line. Resolved through the detail cache
     * ([YoinRepository.getAlbum] is LRU + disk backed), so this is usually a
     * local read; any failure just drops the line.
     */
    private suspend fun loadActivityHeroFootnote(activities: List<ActivityEvent>): HeroFootnote {
        val hero = selectHomeHeroActivity(activities) ?: return HeroFootnote()
        if (hero.entityType != ActivityEntityType.ALBUM.name) return HeroFootnote()
        val album = try {
            repository.getAlbum(MediaId(hero.provider, MediaId.storedRawId(hero.provider, hero.entityId)))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        } ?: return HeroFootnote()
        val year = album.year
        val songs = album.songCount?.takeIf { count -> count > 0 }
        val minutes = album.durationSec?.takeIf { seconds -> seconds > 60 }?.let { it / 60 }
        return HeroFootnote(
            year = year,
            songCount = songs,
            minutes = minutes,
        )
    }

    private data class HeroFootnote(
        val text: String? = null,
        val year: Int? = null,
        val songCount: Int? = null,
        val minutes: Int? = null,
    )

    private fun albumSubtitle(artist: String?): Pair<String, UiText> {
        val name = artist?.takeIf { it.isNotBlank() }
        return if (name != null) {
            name to UiText.Raw(name)
        } else {
            "" to UiText.Raw("")
        }
    }

    private fun singleSubtitle(artist: String?): Pair<String, UiText> {
        val name = artist?.takeIf { it.isNotBlank() }
        return if (name != null) {
            name to UiText.Raw(name)
        } else {
            "" to UiText.Raw("")
        }
    }

    /** "Playlist" here is a provider placeholder owner, matched as data, not as UI copy. */
    private fun playlistSubtitle(owner: String?): Pair<String, UiText> {
        val name = owner?.takeIf { it.isNotBlank() && it != "Playlist" }
        return if (name != null) {
            name to UiText.Raw(name)
        } else {
            "" to UiText.Raw("")
        }
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
                homeEditHintStore = container.homeEditHintStore,
                rediscoverSongs = RediscoverSongSource.of(container.database.playHistoryDao()),
                memoryTitle = { candidate -> container.albumMemoryTitleResolver.resolve(candidate) },
            ) as T
    }

    private companion object {
        // The widget grid: at most two wide signal cards (so at most two
        // cards ever do the extra review/note lookups) plus up to 28 covers.
        // A phone shows the first 12 cells at the default row preset
        // (trimToPhoneShelf: the 3 × 4 shelf, unchanged) and 14 covers at XL
        // (6 rows); a tablet template seats as many as its phone-sized
        // columns take — 10 columns × 3 rows at XL (D1 row presets) less the
        // two signal cards is the deepest, 26 covers (HomeRowPresets.kt).
        private const val GRID_MAX_COMPACTS = 28

        /** Your Playlists shelf: enough to scroll on a tablet, not the whole library. */
        private const val HOME_PLAYLIST_LIMIT = 20

        /** Recently Played shelf. */
        private const val HOME_RECENTLY_PLAYED_LIMIT = 20

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

        // Rediscover songs read per build, already in shelf order: room for
        // the shelf after the songs whose album is on it drop out.
        private const val REDISCOVER_SONG_QUERY_LIMIT = 64

        // Persisted pool sizes (enough for GRID_MAX_COMPACTS covers even when
        // dedup bites and a library is short on playlists) and the rotation
        // cadence: the shelf re-rolls at most every 6 hours, otherwise it
        // reads from disk with zero network.
        private const val GRID_POOL_ALBUMS = 14
        private const val GRID_POOL_TRACKS = 10
        private const val GRID_POOL_PLAYLISTS = 10
        private const val GRID_POOLS_TTL_MS = 6L * 60L * 60L * 1000L

        // "Recently Added" home shelf: library items added within the last 30 days.
        // Loaded deep enough for the widest feed (4 × 2 tracks, 20 albums); the
        // section takes what its width seats (HomeFeedDensity), so a phone still
        // shows the newest 4 tracks and 12 albums.
        private const val RECENTLY_ADDED_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        private const val RECENTLY_ADDED_TRACK_LIMIT = RecentlyAddedMaxTracks
        private const val RECENTLY_ADDED_ALBUM_LIMIT = RecentlyAddedMaxAlbums

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

/**
 * The shared memory read: header pill + the grid's memory 1×2 (card, album
 * id), plus the whole candidate build (ineligible albums included) and the
 * rated / noted songs that Rediscover picks from.
 */
private data class MemorySignals(
    val pill: HomeMemoryPill,
    val memoryCard: Pair<HomeWidgetCard, MediaId?>?,
    val pool: List<AlbumMemoryCandidate> = emptyList(),
    val songs: List<SongMemoryAggregate> = emptyList(),
)

/** Whether [this] card played: an album card's album, a song card's song or its album. Raw ids. */
private fun HomeRediscoverItem.playedBy(playedAlbums: Set<String>, playedSongs: Set<String>): Boolean {
    val track = song ?: return albumId.rawId in playedAlbums
    return track.id.rawId in playedSongs || track.albumId?.rawId?.let { it in playedAlbums } == true
}

/**
 * Whether Recently Added shows [this] card already: an album card's album, a
 * song card's song or its album (the card wears that album's cover). Raw ids.
 */
private fun HomeRediscoverItem.shownIn(albums: Set<String>, tracks: Set<String>): Boolean {
    val track = song ?: return albumId.rawId in albums
    return track.id.rawId in tracks || track.albumId?.rawId?.let { it in albums } == true
}

/** The history's track, enough to play it alone (the noted-track 1×2 rebuilds its song the same way). */
private fun SongMemoryAggregate.toTrack(): Track = Track(
    id = MediaId(provider, MediaId.storedRawId(provider, songId)),
    title = title,
    artist = artist.takeIf(String::isNotBlank),
    artistId = null,
    album = album.takeIf(String::isNotBlank),
    albumId = albumId.takeIf(String::isNotBlank)?.let { MediaId(provider, MediaId.storedRawId(provider, it)) },
    coverArt = CoverRef.fromStorageKey(coverArtId),
    durationSec = durationMs.takeIf { it > 0L }?.let { (it / 1000L).toInt() },
    trackNumber = null,
    year = null,
    genre = null,
    userRating = null,
)

/** A Rediscover pick as a card; null when the album has no usable raw id. */
private fun toRediscoverItem(pick: RediscoverPick): HomeRediscoverItem? {
    val candidate = pick.candidate
    val rawAlbumId = MediaId.storedRawId(candidate.provider, candidate.albumId)
    if (rawAlbumId.isBlank() || candidate.provider.isBlank()) return null
    return HomeRediscoverItem(
        albumId = MediaId(candidate.provider, rawAlbumId),
        albumName = candidate.albumName,
        artistName = candidate.artistName?.takeIf { it.isNotBlank() },
        coverArtUrl = candidate.coverArtUrl,
        score = pick.score,
        scoreText = pick.score?.let(::rediscoverScoreText),
        // rediscoverScore's own order: a set album rating wins.
        scoreKind = when {
            pick.score == null -> MemoryScoreKind.NONE
            (candidate.albumRating ?: 0f) > 0f -> MemoryScoreKind.ALBUM_RATING
            else -> MemoryScoreKind.AVERAGE_TRACK_RATING
        },
        lastPlayedAt = pick.lastPlayedAt,
        firstPlayedAt = candidate.firstPlayedFromHistoryAt,
        playCount = candidate.playCountFromHistory,
        hasReview = candidate.hasAlbumReview,
        noteCount = candidate.noteCount,
        ratedTrackCount = candidate.ratedTrackCount,
    )
}

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

/** [layout]'s appended-hidden sections whose ids aren't in [seenIds], in layout order. */
private fun unseenNewSectionsOf(layout: HomeLayout, seenIds: Set<String>): Set<HomeSection> =
    layout.newSections.filterTo(LinkedHashSet()) { it.id !in seenIds }

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
