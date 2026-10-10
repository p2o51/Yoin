package com.gpo.yoin.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.R
import com.gpo.yoin.data.home.HomeLayoutStore
import com.gpo.yoin.data.home.HomeSnapshot
import com.gpo.yoin.data.home.HomeSnapshotStore
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
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.ServiceFeatureCatalog
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    // AppContainer.musicConfigurationRevision: ticks on a switch, on deleting
    // the active profile, on editing any account's credentials. Home reloads
    // on its scope (see [observeScope]); only an edit that rebuilt the active
    // account's source is left to this.
    private val configurationRevision: StateFlow<Long> = MutableStateFlow(0L),
    // ProfileManager.switchingState: from a switch's start until the new
    // account's first feed, Home shows Loading (owner Q14b, see [observeSwitching]).
    private val switchingState: StateFlow<ProfileManager.SwitchState> =
        MutableStateFlow(ProfileManager.SwitchState.Idle),
    // Each account's last feed on disk (P2 PR3, owner Q14a): painted when
    // nothing of a scope is up yet, then replaced by the fresh load. Null =
    // none (previews, tests).
    private val snapshotStore: HomeSnapshotStore? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // What Home has published, and whose it is (the profile id): [uiState]
    // shows it unless an account switch holds Loading over it ([show]).
    private var published: HomeUiState = HomeUiState.Loading
    private var publishedProfileId: String? = null

    // The account a switch is going to. Set at the switch's start, cleared
    // when a feed (or its error) of that account is published, or when the
    // switch fails — the outgoing account's feed then comes back as it was.
    private var switchTarget: String? = null

    // Edit-mode freeze: while Home is being edited, content updates queue in
    // [frozenState] instead of reshaping the feed under the user's hands.
    // Every publish goes through [emit] and every splice reads
    // [currentContent], so splices made while frozen build on each other.
    private var editing = false
    private var frozenState: HomeUiState? = null
    private var frozenProfileId: String? = null

    // Debug-only: `home.content` marks the first Content only. Declared before
    // init, which can publish a cached Content synchronously.
    private var perfContentMarked = false

    // Which provider|profile the Content in [_uiState] belongs to. The live
    // observers only splice into content of the CURRENT scope: right after a
    // profile switch the screen may still hold the old profile's feed (until
    // refresh repaints), and a tick must never graft one profile onto another.
    private var contentScopeKey: String? = null

    // Bumped by [refresh]: a reload of the current scope goes through the same
    // single loader as a scope change, so the two can't race each other.
    private val refreshRequests = MutableStateFlow(0)

    // Per provider|profile, the memory-signal stamp the published content's
    // signals were read at (recorded on publish, not on build: a build whose
    // load then fails never reached the screen). A stamp tick equal to it
    // brings nothing new (the first tick replays the load's own stamp).
    private val signalStamps = mutableMapOf<String, Long>()

    // The memory-signal ticks seen so far, the last one's provider|profile
    // and stamp, and a nudge that replays it: a load that publishes signals
    // older than a tick which came in while it was out replays that tick
    // (see [replaySignalTickMissedByLoad]).
    private var signalTickCount = 0
    private var lastSignalTick: Pair<String, Long>? = null
    private val signalTickReplays = MutableStateFlow(0)

    // The scope the latest load ran for, and the active source it started
    // with: a revision tick that finds the same scope on a rebuilt source is
    // an edit of the active account's credentials (see [observeConfigurationRevision]).
    private var loadedScope: HomeScope? = null
    private var loadedSource: Any? = null

    // The load in flight: while its memory signals are still being built, a
    // signal tick of its scope leaves the build to it (one candidate build,
    // not two racing); the load replays a tick it then misses.
    private var activeLoad: HomeLoad? = null

    // Per provider|profile, the memory signals behind the Rediscover up now
    // (the latest a load or a tick spliced in): a block that lands later and
    // re-derives Rediscover picks from these.
    private val scopeSignals = mutableMapOf<String, MemorySignals>()

    // Albums and songs played since this VM started, per provider|profile:
    // Rediscover drops them (and the songs of those albums), so a build that
    // began before the play can't put a removed card back.
    private val playedRediscoverRawIds = mutableMapOf<String, MutableSet<String>>()
    private val playedRediscoverSongRawIds = mutableMapOf<String, MutableSet<String>>()

    // Snapshot reads in flight or done, per profile id, each taken by the
    // first load of that profile that has nothing up ([paintableSnapshot]).
    // Started early — the active profile's as this VM is created, a switch's
    // target as the switch begins — so the read is usually in by then.
    private val snapshotReads = mutableMapOf<String, Deferred<HomeSnapshot?>>()

    // The profile whose layout [homeLayout] holds, once its first read is in
    // (set right after the layout, see [observeHomeLayout]): a snapshot paints
    // in its own account's layout, not the default (or the outgoing
    // account's) it would rearrange from a beat later.
    private val layoutReadFor = MutableStateFlow<String?>(null)

    // Owner Q16: the artists the Activities bento pictures (MediaId strings,
    // as the feed reports them) and whether the feed rests — on screen,
    // nothing over it, its list still. Spotify portraits are asked for only
    // while it does ([fillActivityPortraits]); until the feed says so, it
    // doesn't.
    private val shownActivityArtists = MutableStateFlow<List<String>>(emptyList())
    private val feedAtRest = MutableStateFlow(false)

    /**
     * Turns a source-relative cover id into the feed's URL (the activities and
     * the shelves resolve theirs as they compose). A new instance for each
     * active source, so covers resolved while there was none — a snapshot
     * painted on a cold start — are asked for again once it is in.
     */
    val coverArtUrlBuilder: StateFlow<(String) -> String> = repository.activeProviderId
        .map { newCoverArtUrlBuilder() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, newCoverArtUrlBuilder())

    private val _homeLayout = MutableStateFlow(HomeLayout.Default)

    /**
     * The active profile's home layout (which sections show, in what order),
     * reconciled against the live section catalog. Orthogonal to [uiState]:
     * content loads the same regardless of layout, and the feed renders from
     * this. Falls back to [HomeLayout.Default] when no profile is active or the
     * profile hasn't customized. See [observeHomeLayout].
     */
    val homeLayout: StateFlow<HomeLayout> = _homeLayout.asStateFlow()

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
        observeHomeLayout()
        // The constructor's Loading is what a cold start shows until the first Content.
        if (YoinPerf.enabled) YoinPerf.mark("home.loading", "ms_since_process_start" to YoinPerf.sinceProcessStart())
        // Off the main thread, ahead of the source: the profile id is restored
        // synchronously, the source a beat later.
        activeProfileId.value?.takeIf(String::isNotBlank)?.let(::readSnapshot)
        observeScope()
        observeConfigurationRevision()
        observeSwitching()
        observeRecentHistory()
        observeMemorySignals()
        observeRediscoverRemovals()
    }

    /** The feed reports the artists (MediaId strings) its Activities picture now. */
    fun onActivityArtistsShown(artistIds: List<String>) {
        shownActivityArtists.value = artistIds
    }

    /**
     * The feed came to rest (Home resumed, nothing over it, its list still)
     * or left it: scrolled, covered, gone to the background or off screen.
     */
    fun onFeedAtRestChanged(atRest: Boolean) {
        feedAtRest.value = atRest
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

    /**
     * Keep [homeLayout] on the active profile's persisted layout — and only
     * then mark whose it is ([layoutReadFor]), so a snapshot waiting for it
     * never paints in the one before ([paintableSnapshot]).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeHomeLayout() {
        viewModelScope.launch {
            activeProfileId
                .flatMapLatest { profileId ->
                    if (profileId.isNullOrBlank()) {
                        flowOf(profileId to HomeLayout.Default)
                    } else {
                        homeLayoutStore.layoutFlow(profileId)
                            .map { prefs -> profileId to HomeLayout.reconcile(prefs) }
                    }
                }
                .collect { (profileId, layout) ->
                    _homeLayout.value = layout
                    layoutReadFor.value = profileId
                }
        }
    }

    /** Freeze the feed while Home is in edit mode; unfreezing publishes the latest queued state. */
    fun setEditing(editing: Boolean) {
        if (this.editing == editing) return
        this.editing = editing
        if (!editing) {
            frozenState?.let { state ->
                published = state
                publishedProfileId = frozenProfileId
                show()
            }
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

    /**
     * Publish [state], [profileId]'s feed (or its Loading / error). [perfSrc]
     * labels a Content for the debug-only `home.content` mark: mem | disk | snapshot.
     */
    private fun emit(state: HomeUiState, profileId: String?, perfSrc: String? = null) {
        markPerf(state, perfSrc)
        val owned = if (state is HomeUiState.Content && state.ownerProfileId != profileId) {
            state.copy(ownerProfileId = profileId)
        } else {
            state
        }
        if (editing) {
            frozenState = owned
            frozenProfileId = profileId
        } else {
            published = owned
            publishedProfileId = profileId
            show()
        }
    }

    /**
     * Put [published] on screen — or Loading while an account switch holds
     * it: from the switch's start until the switched-to account's own feed
     * (or its error) is published, which ends the hold.
     */
    private fun show() {
        val target = switchTarget
        if (target != null && published !is HomeUiState.Loading && publishedProfileId == target) {
            switchTarget = null
        }
        _uiState.value = if (switchTarget != null) HomeUiState.Loading else published
    }

    /**
     * Q14b: switching accounts takes Home to Loading at once — the outgoing
     * account's feed doesn't sit there for the ping and warm-up (up to 16 s)
     * as if nothing happened — and the incoming account's first feed ends
     * it ([show]). Display only: loads stay on [observeScope]'s one trigger,
     * so the hand-over hold there is untouched. A failed switch restores the
     * outgoing source unchanged (no scope move, no reload): the hold just
     * lifts and its feed is back as it was.
     */
    private fun observeSwitching() {
        viewModelScope.launch {
            switchingState.collect { state ->
                // The incoming account's snapshot reads during the ping and
                // warm-up, ready to paint the moment the switch commits; a
                // switch that fails lets it go.
                when (state) {
                    is ProfileManager.SwitchState.Switching -> readSnapshot(state.profileId)
                    is ProfileManager.SwitchState.Error ->
                        if (state.profileId != activeProfileId.value) snapshotReads.remove(state.profileId)?.cancel()
                    ProfileManager.SwitchState.Idle -> Unit
                }
                switchTarget = when (state) {
                    is ProfileManager.SwitchState.Switching -> state.profileId
                    is ProfileManager.SwitchState.Error -> null
                    // Committed (setActive comes before Idle): hold on until
                    // the new account's feed is up. A switch that never
                    // landed holds nothing.
                    ProfileManager.SwitchState.Idle -> switchTarget?.takeIf { it == activeProfileId.value }
                }
                show()
            }
        }
    }

    /** The newest content, queued or published. */
    private fun currentContent(): HomeUiState.Content? = (frozenState ?: published) as? HomeUiState.Content

    /** Reload the current scope (Retry, an edit of the active account's credentials). */
    fun refresh() {
        refreshRequests.update { it + 1 }
    }

    /**
     * The ONE trigger for loading Home: the active profile and its provider,
     * plus [refresh] requests. The two halves settle in either order — on a
     * cold start the profile id is restored synchronously while ProfileManager
     * builds the source asynchronously; a switch swaps the source before
     * setActive; deleting the active profile nulls the source, moves the id,
     * then builds the next source. collectLatest cancels a load whose scope
     * has moved on, so none of these orders publishes the wrong account, and
     * each settled scope publishes one load.
     */
    private fun observeScope() {
        val scopes = combine(activeProfileId, repository.activeProviderId) { profileId, providerId ->
            HomeScope(providerId = providerId, profileId = profileId)
        }.distinctUntilChanged()
        viewModelScope.launch {
            var previous: HomeScope? = null
            combine(scopes, refreshRequests) { scope, _ -> scope }
                .collectLatest { scope ->
                    val handOver = previous?.handsOverTo(scope) == true
                    previous = scope
                    if (handOver) {
                        // A switch between providers: the new account's source
                        // is in, setActive is next, and for that beat the new
                        // provider sits on the outgoing profile — no account's
                        // scope. Keep what's up (no Loading, no pre-paint read)
                        // until the profile follows and cancels this; bounded.
                        delay(ACTIVE_SOURCE_WAIT_MS)
                    }
                    loadScope(scope)
                    // The load is done: the Activities artists' portraits.
                    fillActivityPortraits(scope)
                }
        }
    }

    /**
     * A switch or a delete moves the scope too, and [observeScope] reloads
     * for it; editing another account's credentials leaves the active source
     * alone. Only a tick that finds the scope Home last loaded on a rebuilt
     * source — the active account's credentials were edited — reloads here.
     * The revision this VM starts at is already reflected by its first load.
     */
    private fun observeConfigurationRevision() {
        viewModelScope.launch {
            configurationRevision.drop(1).collect {
                val scope = HomeScope(providerId = repository.currentProviderId(), profileId = activeProfileId.value)
                if (scope == loadedScope && repository.activeSourceIdentity() !== loadedSource) refresh()
            }
        }
    }

    /**
     * Owner Q16: Spotify's recently-played names artists without images; the
     * repository fills what the device has before the feed goes up, and
     * here, once [scope]'s load is done, Spotify is asked for the rest — the
     * artists the bento pictures in this process's endpoint feed, while the
     * feed rests (on screen, uncovered, still, for
     * [ACTIVITY_PORTRAIT_SETTLE_MS]), one request at a time, each waiting
     * for the feed to rest again (and that long) when it has stopped resting,
     * stopping at the rate limit. Each portrait splices in as it lands
     * ([splicePortrait]). Runs until the scope moves or reloads
     * (collectLatest cancels it); newly pictured artists (a row preset, a
     * wider window) are asked for as they come. Only a service whose
     * Activities name artists without portraits does this
     * ([ServiceFeatures.activityArtistPortraits]: Spotify; Subsonic and
     * Apple Music build theirs from local records, with covers).
     */
    @OptIn(FlowPreview::class)
    private suspend fun fillActivityPortraits(scope: HomeScope) {
        if (!ServiceFeatureCatalog.forProvider(scope.providerId).activityArtistPortraits) return
        if (scope.profileId.isNullOrBlank()) return
        val scopeKey = homeScopeKey(scope.providerId, scope.profileId)
        combine(shownActivityArtists, feedAtRest) { shown, atRest -> shown.takeIf { atRest } }
            .debounce(ACTIVITY_PORTRAIT_SETTLE_MS)
            .filterNotNull()
            .map { shown -> portraitCandidates(scope, scopeKey, shown) }
            .filter { artistIds -> artistIds.isNotEmpty() }
            .distinctUntilChanged()
            .collect { artistIds ->
                try {
                    repository.fillSpotifyActivityArtistPortraits(
                        artistIds = artistIds,
                        awaitTurn = ::awaitFeedAtRest,
                        onPortrait = { artistId, url -> splicePortrait(scope, scopeKey, artistId, url) }
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // Not the feed's failure: the cards keep their stand-ins.
                }
            }
    }

    /**
     * A portrait request's turn: now while the feed rests; otherwise once it
     * has rested again for [ACTIVITY_PORTRAIT_SETTLE_MS] — back from a
     * scroll, a page over Home, the background — so a request never rides a
     * return animation or another screen's reads.
     */
    @OptIn(FlowPreview::class)
    private suspend fun awaitFeedAtRest() {
        if (feedAtRest.value) return
        feedAtRest.debounce(ACTIVITY_PORTRAIT_SETTLE_MS).first { atRest -> atRest }
    }

    /**
     * The raw ids of [shown] that are artists of [scopeKey]'s Activities as
     * this process's endpoint read them — not a snapshot's (an earlier
     * process's) nor the activity log a failed read fell back to.
     */
    private fun portraitCandidates(scope: HomeScope, scopeKey: String, shown: List<String>): List<String> {
        if (!matchesCurrentScope(scope.providerId, scope.profileId)) return emptyList()
        val content = contentOf(scopeKey) ?: return emptyList()
        if (!content.activitiesFromRemote || scopeKey in snapshotEndpointFeeds) return emptyList()
        val inFeed = content.activities.mapNotNullTo(HashSet()) { event -> event.spotifyArtistRawId() }
        return shown.mapNotNull { id -> MediaId.parseOrNull(id)?.takeIf { it.provider == MediaId.PROVIDER_SPOTIFY } }
            .map(MediaId::rawId)
            .filter { rawId -> rawId in inFeed }
            .distinct()
    }

    /** [artistId]'s portrait just came in: onto [scopeKey]'s Activities, if they are still the endpoint's. */
    private fun splicePortrait(scope: HomeScope, scopeKey: String, artistId: String, url: String) {
        if (!matchesCurrentScope(scope.providerId, scope.profileId)) return
        val latest = contentOf(scopeKey)?.takeIf { it.activitiesFromRemote } ?: return
        if (latest.activities.none { it.spotifyArtistRawId() == artistId && it.coverArtId != url }) return
        val next = latest.copy(
            activities = latest.activities.map { event ->
                if (event.spotifyArtistRawId() == artistId) event.copy(coverArtId = url) else event
            }
        )
        keep(scopeKey, scope.providerId, scope.profileId, next)
        emit(next, scope.profileId)
    }

    /**
     * Load [scope] and publish it. Loading shows only when there is nothing
     * of this scope to show: its content stays up through a same-scope reload,
     * the in-memory cache paints at once, and else the account's snapshot
     * does ([paintableSnapshot]). Then [HomeLoad] puts the local tier up and
     * splices every slower block into it as it lands — onto the snapshot too,
     * so it is replaced block by block, a block whose read fails keeping what
     * the snapshot showed.
     */
    private suspend fun loadScope(scope: HomeScope) {
        val providerId = scope.providerId
        val profileId = scope.profileId
        val scopeKey = homeScopeKey(providerId, profileId)
        loadedScope = scope
        loadedSource = repository.activeSourceIdentity()
        val signalTicksBeforeLoad = signalTickCount
        val perf = YoinPerf.begin("home.refresh")
        try {
            if (contentOf(scopeKey) == null) {
                val cachedHomeContent = homeContentCache[scopeKey]
                val snapshot = if (cachedHomeContent == null) paintableSnapshot(providerId, profileId) else null
                when {
                    cachedHomeContent != null -> {
                        emit(cachedHomeContent, profileId, perfSrc = "mem")
                        contentScopeKey = scopeKey
                        // This process's own feed is newer than any snapshot.
                        profileId?.let(snapshotReads::remove)?.cancel()
                    }
                    snapshot != null -> {
                        val (snapshotScopeKey, feed) = snapshot
                        // Nothing read yet: not written back as it is, and an
                        // endpoint feed in it only holds until this process's
                        // own endpoint read answers.
                        snapshotOnlyScopes += snapshotScopeKey
                        if (feed.activitiesFromRemote) {
                            snapshotEndpointFeeds += snapshotScopeKey
                        } else {
                            snapshotEndpointFeeds -= snapshotScopeKey
                        }
                        emit(feed, profileId, perfSrc = "snapshot")
                        // Its own provider's scope: painted before the source
                        // is up, it is this scope's once a source of that
                        // provider arrives, and no other's.
                        contentScopeKey = snapshotScopeKey
                    }
                    else -> {
                        emit(HomeUiState.Loading, profileId)
                        contentScopeKey = scopeKey
                    }
                }
            }
            if (providerId != null) resolveSnapshotCovers(scopeKey, providerId, profileId)
            if (providerId == null && !profileId.isNullOrBlank()) {
                // A profile whose source isn't built yet (cold start, the next
                // profile after a delete): hold what's up rather than publish a
                // feed read from no source. The source's arrival moves the
                // scope and cancels this hold — so the wait isn't for the
                // source (a second waiter would race that into a second load)
                // but for ProfileManager settling without one (unreadable
                // credentials): then, or at the bound, the load below runs
                // without a source, as before.
                withTimeoutOrNull(ACTIVE_SOURCE_WAIT_MS) {
                    combine(repository.activeProviderId, repository.activeSourceSettled) { provider, settled ->
                        provider == null && settled
                    }.first { sourceless -> sourceless }
                }
            }
            HomeLoad(scopeKey, providerId, profileId, signalTicksBeforeLoad).run()
            if (perf != null) {
                val result = if (matchesCurrentScope(providerId, profileId)) "ok" else "superseded"
                YoinPerf.end(perf, "provider" to providerId, "result" to result)
            }
        } catch (cancellation: CancellationException) {
            // The scope moved on (or a refresh restarted it) mid-load.
            if (perf != null) YoinPerf.end(perf, "provider" to providerId, "result" to "superseded")
            throw cancellation
        } catch (e: Exception) {
            if (perf != null) {
                YoinPerf.end(perf, "provider" to providerId, "result" to "error", "err" to e.javaClass.simpleName)
            }
            if (!matchesCurrentScope(providerId, profileId)) return
            // Content of this scope already up (cached, the local tier, or the
            // feed a same-scope reload started from) stays rather than an error.
            if (contentOf(scopeKey) == null) {
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
                    profileId
                )
            }
        }
    }

    /** The content up (or queued while editing) when it belongs to [scopeKey]. */
    private fun contentOf(scopeKey: String): HomeUiState.Content? =
        currentContent()?.takeIf { contentScopeKey == scopeKey }

    /** [scopeKey]'s content was just published on signals read at [stamp] (null: built without them). */
    private fun recordSignalStamp(scopeKey: String, stamp: Long?) {
        if (stamp != null) signalStamps[scopeKey] = stamp else signalStamps.remove(scopeKey)
    }

    /**
     * A load just published [scopeKey]'s content on signals read at
     * [publishedStamp], as it began. A memory-signal tick that came in while
     * they were built was left to the load ([HomeLoad.signalsPending]) or
     * dropped (Loading had nothing to splice into), and Room won't tick the
     * same stamp again — so a tick of this scope since [ticksBeforeLoad] with
     * another stamp is replayed.
     */
    private fun replaySignalTickMissedByLoad(scopeKey: String, publishedStamp: Long?, ticksBeforeLoad: Int) {
        if (signalTickCount == ticksBeforeLoad) return
        val (tickScopeKey, tickStamp) = lastSignalTick ?: return
        if (tickScopeKey == scopeKey && tickStamp != publishedStamp) signalTickReplays.update { it + 1 }
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
            if ((frozenState ?: published) !is HomeUiState.Loading) {
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

    fun buildCoverArtUrl(coverArtId: String): String =
        repository.resolveSubsonicCoverUrl(coverArtId, size = 320).orEmpty()

    private fun newCoverArtUrlBuilder(): (String) -> String = { coverArtId -> buildCoverArtUrl(coverArtId) }

    // ── Snapshot (P2 PR3) ────────────────────────────────────────────────

    /** Start reading [profileId]'s snapshot unless a read is already out or in. */
    private fun readSnapshot(profileId: String) {
        val store = snapshotStore ?: return
        if (profileId in snapshotReads) return
        snapshotReads[profileId] = viewModelScope.async {
            try {
                store.read(profileId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * [profileId]'s snapshot as a feed to paint, with the scope it belongs to
     * (its provider's), or null: no snapshot, one of another provider than
     * [providerId] — the account's source disagrees, so it isn't this
     * account's feed — or a read that, with the account's layout, takes
     * longer than [SNAPSHOT_READ_WAIT_MS] (the load goes ahead with Loading).
     * It paints in that layout ([homeLayout]): drawn in the default's
     * sections, it would rearrange as soon as the account's own lands. With
     * no source yet ([providerId] null) it paints on its own word and is
     * checked when the source arrives: then only a source of its provider
     * finds it up as this scope's content.
     */
    private suspend fun paintableSnapshot(providerId: String?, profileId: String?): Pair<String, HomeUiState.Content>? {
        val id = profileId?.takeIf(String::isNotBlank) ?: return null
        readSnapshot(id)
        val read = snapshotReads[id] ?: return null
        val snapshot = withTimeoutOrNull(SNAPSHOT_READ_WAIT_MS) {
            read.await()
                ?.takeIf { found -> providerId == null || found.provider == providerId }
                ?.also { layoutReadFor.first { layoutProfileId -> layoutProfileId == id } }
        }
        // Taken (a scope change cancelling this wait leaves it to the next load).
        snapshotReads.remove(id)
        if (snapshot == null) return null
        val content = snapshot.feed.toHomeContent { key -> snapshotCoverUrl(key, snapshot.provider) }
        return homeScopeKey(snapshot.provider, id) to content
    }

    /**
     * The covers of [scopeKey]'s feed that couldn't resolve when it went up —
     * a snapshot painted before the account's source — resolved now that the
     * source is in. The cards' covers reveal over their type icons; the
     * activities and shelves follow [coverArtUrlBuilder]. Not a fresh feed,
     * so nothing is written back.
     */
    private fun resolveSnapshotCovers(scopeKey: String, providerId: String, profileId: String?) {
        val shown = contentOf(scopeKey) ?: return
        val resolved = shown.withResolvedCovers { key -> snapshotCoverUrl(key, providerId) }
        if (resolved !== shown) emit(resolved, profileId)
    }

    /**
     * A snapshot cover [key] as a URL: through the active source when it is
     * of the snapshot's [provider]; until then a URL key is its own URL (every
     * source serves one as itself) and a source-relative id waits (null).
     */
    private fun snapshotCoverUrl(key: String, provider: String): String? {
        val ref = CoverRef.fromStorageKey(key) ?: return null
        if (repository.currentProviderId() == provider) return repository.resolveCoverUrl(ref, size = HOME_COVER_SIZE)
        return (ref as? CoverRef.Url)?.url
    }

    /**
     * Content of [scopeKey] just went up: kept in memory for this process,
     * and queued as [profileId]'s snapshot (written in the background,
     * conflated and capped by the store) — once something this process read
     * is in it ([snapshotOnlyScopes]): the snapshot as painted, or with a
     * played card taken off at most, is what is on disk already. A feed read
     * without a source names no provider, so it isn't written.
     */
    private fun keep(scopeKey: String, providerId: String?, profileId: String?, content: HomeUiState.Content) {
        homeContentCache[scopeKey] = content
        val store = snapshotStore ?: return
        if (scopeKey in snapshotOnlyScopes) return
        if (providerId == null || profileId.isNullOrBlank()) return
        store.save(profileId, providerId) { content.toSnapshotFeed() }
    }

    /** A block read by this process just went into [scopeKey]'s content: from now on it is written back ([keep]). */
    private fun markFresh(scopeKey: String) {
        snapshotOnlyScopes -= scopeKey
    }

    /**
     * Whether [content]'s Activities are Spotify's endpoint feed, which a
     * local activity-log write leaves alone: this process's own (kept through
     * a failed reload too, see [HomeLoad.activitiesFor]), or [scopeKey]'s
     * snapshot's only while a load of that scope still waits on the endpoint.
     */
    private fun endpointFeedOwns(scopeKey: String, content: HomeUiState.Content): Boolean =
        content.activitiesFromRemote &&
            repository.currentProviderId() == MediaId.PROVIDER_SPOTIFY &&
            (scopeKey !in snapshotEndpointFeeds || activeLoad?.remoteFeedPending == scopeKey)

    /** A cover resolved for a card, with the storage key it came from (what a snapshot keeps). */
    private fun cardCover(ref: CoverRef?, subsonicFallback: MediaId? = null): Pair<String?, String?> {
        val fallback = subsonicFallback
            ?.takeIf { id -> id.provider == MediaId.PROVIDER_SUBSONIC }
            ?.let { id -> CoverRef.SourceRelative(id.rawId) }
        repository.resolveCoverUrl(ref, size = HOME_COVER_SIZE)?.let { url -> return url to CoverRef.toStorageKey(ref) }
        fallback?.let { cover ->
            repository.resolveCoverUrl(cover, size = HOME_COVER_SIZE)?.let { url ->
                return url to CoverRef.toStorageKey(cover)
            }
        }
        return null to CoverRef.toStorageKey(ref ?: fallback)
    }

    /**
     * One load of a scope, published in tiers (P2 PR2) rather than all at
     * once behind its slowest read. The local tier — the activity log, the
     * grid from its persisted pools of any age, the noted track — goes up
     * first; the memory signals, the pools' rotation, Recently Added, Recently
     * Played, Your Playlists, the hero's footnote and Spotify's
     * recently-played feed each splice into that same Content as they land.
     * A splice owns its own fields and re-derives what reads them (the grid,
     * Rediscover, Recently Played's dedupe), so blocks may land in any order.
     * Until the local tier is in they collect in a draft it publishes; onto
     * this scope's content already up (the in-memory cache, a same-scope
     * reload) they go straight. A block whose read fails keeps what is up.
     * Every section loads whatever the layout shows (see [homeLayout]).
     *
     * Profile scoping is the repository's job here: every read and write
     * reached from a load resolves the active profile itself, so there is no
     * profile id to thread through. Grid pools, activities, notes, ratings
     * and play history key off profileId+provider from the same
     * `activeProfileId` StateFlow this ViewModel watches; the Spotify library
     * reads key off the active source's own profile id, which is that same
     * profile's. Each splice checks the scope is still this one.
     */
    private inner class HomeLoad(
        private val scopeKey: String,
        private val providerId: String?,
        private val profileId: String?,
        private val signalTicksBeforeLoad: Int
    ) {
        private val spotify = providerId == MediaId.PROVIDER_SPOTIFY && !profileId.isNullOrBlank()

        // The feed being built while nothing of this scope is up; null once
        // it is (from the start on a same-scope reload or a cache paint).
        private var draft: HomeUiState.Content? =
            if (contentOf(scopeKey) == null) {
                HomeUiState.Content(activities = emptyList(), ownerProfileId = profileId)
            } else {
                null
            }

        private var localTierIn = false

        // What the grid is built from once the local tier is in: the
        // persisted pools (then their rotation) and the noted-track 1×2. A
        // read that failed ([poolsRead] / [notedTrackRead] false) keeps what
        // is up: the covers, re-packed around the wide cards, or the note card.
        private var pools: YoinRepository.HomeGridPoolSnapshot? = null
        private var poolsRead = false
        private var notedTrack: Pair<HomeWidgetCard, MediaId>? = null
        private var notedTrackRead = false

        // The activity log as read, and Spotify's endpoint feed: in (answered
        // or failed), and its events (null: the read failed).
        private var localActivities: List<ActivityEvent>? = null
        private var remoteFeedIn = !spotify
        private var remoteFeed: List<ActivityEvent>? = null

        // Recently Played as read, before the Activities dedupe.
        private var recentlyPlayedRead: List<Album>? = null

        // A hero up without its footnote, left for Spotify's endpoint: its
        // hero is the endpoint's once that answers, so the local tier's isn't
        // resolved ahead of it (on a cold detail cache, a Spotify request).
        private var footnotePending = false

        // This load's memory signals: built, then published (stamp recorded).
        private var signals: MemorySignals? = null
        private var signalsBuilt = false
        private var signalsPublished = false

        /** This load's scope while its signals aren't on screen yet ([observeMemorySignals] waits for them). */
        val signalsPending: String? get() = scopeKey.takeUnless { signalsPublished }

        /** This load's scope while Spotify's endpoint hasn't answered it yet ([endpointFeedOwns]). */
        val remoteFeedPending: String? get() = scopeKey.takeUnless { remoteFeedIn }

        suspend fun run() {
            activeLoad = this
            var failed = false
            try {
                coroutineScope {
                    // One library playlist read feeds the shelf and, when due,
                    // the pools' rotation.
                    val playlistsRead = async(start = CoroutineStart.LAZY) {
                        guardedListResult { repository.getPlaylists() }
                    }
                    launch { spliceSignals(loadMemorySignals()) }
                    launch { spliceRecentlyAdded(loadRecentlyAdded()) }
                    launch { splicePlaylists(playlistsRead.await()) }
                    launch {
                        spliceRecentlyPlayed(
                            guardedListResult { repository.getRecentlyPlayedAlbums(HOME_RECENTLY_PLAYED_LIMIT) }
                        )
                    }
                    if (spotify) launch { spliceRemoteFeed(loadSpotifyFeed()) }
                    // Launched last: whatever above lands at once rides up
                    // with the local tier instead of right behind it.
                    launch { loadLocalTier(scope = this, playlistsRead = playlistsRead) }
                }
                // Without a source there is no local tier to put up first: a
                // sourceless load publishes once, with every read in.
                draft?.let { pending ->
                    if (matchesCurrentScope(providerId, profileId)) {
                        draft = null
                        markFresh(scopeKey)
                        publish(pending, perfSrc = "disk")
                        settleSignals()
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                failed = true
                throw e
            } finally {
                if (activeLoad === this) activeLoad = null
                // Failed before its signals went up: a tick it held back
                // ([signalsPending]) is replayed onto the feed that stays.
                if (failed && !signalsPublished) {
                    replaySignalTickMissedByLoad(scopeKey, signalStamps[scopeKey], signalTicksBeforeLoad)
                }
            }
        }

        private suspend fun loadLocalTier(scope: CoroutineScope, playlistsRead: Deferred<Result<List<Playlist>>>) {
            // The one read a load can't do without: a throw fails it, as before.
            val activities = repository.getRecentActivities(limit = HOME_ACTIVITY_LIMIT).first()
            // Pools of any age go up now; expired ones rotate right behind —
            // the shelf's one network moment per [GRID_POOLS_TTL_MS]. A failed
            // read can't tell fresh pools from none: no rotation on it, the
            // next load reads again.
            val fresh = guardedResult { repository.getCachedHomeGridPools(maxAgeMs = GRID_POOLS_TTL_MS) }
            val persisted = if (fresh.getOrNull() != null) {
                fresh
            } else {
                guardedResult { repository.getCachedHomeGridPools(maxAgeMs = null) }
            }
            pools = persisted.getOrNull()
            poolsRead = persisted.isSuccess
            val note = loadNotedTrackCard()
            notedTrack = note.getOrNull()
            notedTrackRead = note.isSuccess
            localActivities = activities
            localTierIn = true
            if (fresh.isSuccess && fresh.getOrNull() == null) {
                scope.launch { spliceRotatedPools(fetchAndPersistGridPools(playlistsRead)) }
            }
            spliceActivities(scope) { latest ->
                val grid = gridWith(latest, memory = latest.memoryCard())
                latest.copy(
                    widgetGrid = grid,
                    // The noted song it may now show leaves the shelf.
                    rediscover = rediscoverFor(
                        signals = scopeSignals[scopeKey],
                        grid = grid,
                        pill = latest.memoryPill,
                        recentlyAdded = latest.recentlyAdded(),
                        fallback = latest.rediscover
                    )
                )
            }
        }

        private fun spliceSignals(built: MemorySignals?) {
            signals = built
            signalsBuilt = true
            if (built != null) scopeSignals[scopeKey] = built
            splice(fresh = built != null) { latest ->
                // Unscoped / failed: keep the pill and memory 1×2 up.
                val pill = built?.pill ?: latest.memoryPill
                val memory = if (built != null) built.memoryCard else latest.memoryCard()
                val grid = gridWith(latest, memory)
                latest.copy(
                    widgetGrid = grid,
                    memoryPill = pill,
                    rediscover = rediscoverFor(built, grid, pill, latest.recentlyAdded(), fallback = latest.rediscover)
                )
            }
        }

        /** [rotated]: the next batch; null when none came (offline): the shelf stays as it is. */
        private fun spliceRotatedPools(rotated: YoinRepository.HomeGridPoolSnapshot?) {
            if (rotated == null) return
            pools = rotated
            poolsRead = true
            // The wide cards up stay as they are, so Rediscover's dedupe stands.
            splice { latest ->
                latest.copy(
                    widgetGrid = buildWidgetGrid(rotated, memory = latest.memoryCard(), note = latest.notedTrackCard())
                )
            }
        }

        private fun spliceRecentlyAdded(read: Result<RecentlyAdded>) {
            val added = read.getOrNull() ?: return
            splice { latest ->
                latest.copy(
                    recentlyAddedTracks = added.tracks,
                    recentlyAddedAlbums = added.albums,
                    rediscover = rediscoverFor(
                        signals = scopeSignals[scopeKey],
                        grid = latest.widgetGrid,
                        pill = latest.memoryPill,
                        recentlyAdded = added,
                        fallback = latest.rediscover
                    )
                )
            }
        }

        private fun splicePlaylists(read: Result<List<Playlist>>) {
            val playlists = read.getOrNull() ?: return
            splice { latest -> latest.copy(playlists = playlists.distinctBy { it.id }.take(HOME_PLAYLIST_LIMIT)) }
        }

        private fun spliceRecentlyPlayed(read: Result<List<Album>>) {
            val albums = read.getOrNull()?.distinctBy { it.id } ?: return
            recentlyPlayedRead = albums
            splice { latest -> latest.copy(recentlyPlayed = albums.notShownIn(latest.activities)) }
        }

        private suspend fun spliceRemoteFeed(feed: List<ActivityEvent>?) {
            remoteFeed = feed
            remoteFeedIn = true
            coroutineScope { spliceActivities(scope = this, fresh = feed != null) { it } }
        }

        /**
         * Spotify's Activities: the recently-played endpoint, falling back to
         * the activity log when it fails (user-read-recently-played not
         * granted) or has no plays. Null = the read failed.
         */
        private suspend fun loadSpotifyFeed(): List<ActivityEvent>? = try {
            repository.getSpotifyRecentActivities(limit = HOME_ACTIVITY_LIMIT)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }

        /**
         * Splice the Activities this load now has (see [activitiesFor]) and
         * what [also] derives from them; [fresh]: a read just landed (not a
         * failed endpoint read). A new hero drops the footnote of the old one
         * and resolves its own — on a cold detail cache a network read — then
         * splices it in if that hero is still up. On Spotify that waits for
         * the endpoint's answer ([footnotePending]).
         */
        private fun spliceActivities(
            scope: CoroutineScope,
            fresh: Boolean = true,
            also: (HomeUiState.Content) -> HomeUiState.Content
        ) {
            var heroToResolve: List<ActivityEvent>? = null
            splice(fresh) { latest ->
                val (activities, fromRemote) = activitiesFor(latest)
                val heroChanged = !sameHero(activities, latest.activities)
                if (heroChanged || footnotePending) heroToResolve = activities
                also(
                    latest.copy(
                        activities = activities,
                        activitiesFromRemote = fromRemote,
                        activityHeroFootnote = latest.activityHeroFootnote.takeUnless { heroChanged },
                        activityHeroYear = latest.activityHeroYear.takeUnless { heroChanged },
                        activityHeroSongCount = latest.activityHeroSongCount.takeUnless { heroChanged },
                        activityHeroMinutes = latest.activityHeroMinutes.takeUnless { heroChanged },
                        recentlyPlayed = recentlyPlayedRead?.notShownIn(activities) ?: latest.recentlyPlayed
                    )
                )
            }
            val activities = heroToResolve ?: return
            footnotePending = spotify && !remoteFeedIn
            if (footnotePending) return
            scope.launch {
                val footnote = loadActivityHeroFootnote(activities)
                // Its hero's, not a block of the feed: onto a snapshot alone
                // it doesn't make that snapshot new.
                splice(fresh = false) { latest ->
                    if (!sameHero(latest.activities, activities)) {
                        latest
                    } else {
                        latest.copy(
                            activityHeroFootnote = footnote.text,
                            activityHeroYear = footnote.year,
                            activityHeroSongCount = footnote.songCount,
                            activityHeroMinutes = footnote.minutes
                        )
                    }
                }
            }
        }

        /**
         * Whose Activities go up: Spotify's endpoint feed once it has plays —
         * and, while it hasn't answered or when it fails, the endpoint feed
         * already up stays (a local write never clobbers it). The snapshot's
         * endpoint feed only holds while the read is out: it is an earlier
         * process's, maybe days old, so a failed read hands it to the
         * activity log like a cold start without a snapshot. Otherwise the
         * activity log, as read (until then, what's up). Activities read by
         * this load are no longer the snapshot's ([snapshotEndpointFeeds]).
         */
        private fun activitiesFor(latest: HomeUiState.Content): Pair<List<ActivityEvent>, Boolean> {
            val endpoint = remoteFeed
            val local = localActivities
            val feedUpIsSnapshots = scopeKey in snapshotEndpointFeeds
            return when {
                spotify && !endpoint.isNullOrEmpty() -> {
                    snapshotEndpointFeeds -= scopeKey
                    endpoint to true
                }
                spotify && latest.activitiesFromRemote &&
                    (!remoteFeedIn || (endpoint == null && !feedUpIsSnapshots)) -> latest.activities to true

                else -> {
                    if (local != null) snapshotEndpointFeeds -= scopeKey
                    (local ?: latest.activities) to false
                }
            }
        }

        /**
         * The grid with [memory] as its memory 1×2: rebuilt from the pools
         * once the local tier has read them; before that (a splice onto
         * content already up), or when they couldn't be read, the cards up
         * are re-packed around it.
         */
        private fun gridWith(
            latest: HomeUiState.Content,
            memory: Pair<HomeWidgetCard, MediaId?>?
        ): List<HomeWidgetCard> {
            val note = if (localTierIn && notedTrackRead) notedTrack else latest.notedTrackCard()
            return if (localTierIn && poolsRead) {
                buildWidgetGrid(pools, memory = memory, note = note)
            } else {
                repackWidgetGrid(latest.widgetGrid, memory = memory, note = note)
            }
        }

        /**
         * Apply [transform] to this scope's newest content — the draft until
         * the local tier opens it to the screen. Synchronous from read to
         * publish, so splices landing together build on each other. [fresh]:
         * it carries a block this load just read, so a snapshot it lands on
         * is written back from now on ([markFresh]); a failed read's splice
         * doesn't.
         */
        private fun splice(fresh: Boolean = true, transform: (HomeUiState.Content) -> HomeUiState.Content) {
            if (!matchesCurrentScope(providerId, profileId)) return
            if (fresh) markFresh(scopeKey)
            val pending = draft
            if (pending != null) {
                draft = transform(pending)
                openIfReady()
                return
            }
            val latest = contentOf(scopeKey) ?: return
            val next = transform(latest)
            if (next != latest) publish(next, perfSrc = null)
            settleSignals()
        }

        /**
         * Publish the draft once the local tier is in (a sourceless load: at
         * its end, see [run]). Spotify's Activities are its endpoint's: an
         * empty local tier (no activity log, no pools) waits for that answer
         * rather than flash an empty feed.
         */
        private fun openIfReady() {
            val pending = draft ?: return
            if (!localTierIn || providerId == null) return
            val localTierEmpty = localActivities.isNullOrEmpty() && pools.isNullOrEmpty()
            if (!remoteFeedIn && localTierEmpty) return
            draft = null
            // Built from this load's reads alone.
            markFresh(scopeKey)
            publish(pending, perfSrc = "disk")
            settleSignals()
        }

        private fun publish(content: HomeUiState.Content, perfSrc: String?) {
            keep(scopeKey, providerId, profileId, content)
            emit(content, profileId, perfSrc = perfSrc)
            contentScopeKey = scopeKey
        }

        /** This load's signals just reached the screen: record their stamp, replay a tick they missed. */
        private fun settleSignals() {
            if (!signalsBuilt || signalsPublished || draft != null) return
            signalsPublished = true
            val stamp = signals?.stamp
            recordSignalStamp(scopeKey, stamp)
            replaySignalTickMissedByLoad(scopeKey, stamp, signalTicksBeforeLoad)
        }
    }

    /** Albums the Activities feed doesn't already show (its album cards and played tracks' albums). */
    private fun List<Album>.notShownIn(activities: List<ActivityEvent>): List<Album> {
        val shown = activities.mapNotNullTo(HashSet()) { event ->
            event.albumId ?: event.entityId.takeIf { event.entityType.equals("album", ignoreCase = true) }
        }
        return filterNot { album -> album.id.toString() in shown || album.id.rawId in shown }
    }

    /**
     * Library items added within the last 30 days, newest first (a week until
     * owner 2026-10-05 — the taller tablet section ran short). Provider-agnostic:
     * reads the unified starred/saved library ([YoinRepository.getStarred]) once
     * and keeps both tracks and albums whose `addedAt` parses to within the
     * window (tracks feed the 2×2 grid, albums the scrolling shelf — Figma
     * 622:777). A failed read is a failure (the shelf up stays), not an empty
     * shelf; cooperative cancellation is rethrown.
     */
    private suspend fun loadRecentlyAdded(): Result<RecentlyAdded> = guardedResult {
        val cutoff = System.currentTimeMillis() - RECENTLY_ADDED_WINDOW_MS
        val starred = repository.getStarred()
        val tracks = starred.tracks
            .withinRecentlyAddedWindow(cutoff, RECENTLY_ADDED_TRACK_LIMIT, key = { it.id }) { it.addedAt }
        val albums = starred.albums
            .withinRecentlyAddedWindow(cutoff, RECENTLY_ADDED_ALBUM_LIMIT, key = { it.id }) { it.addedAt }
        RecentlyAdded(tracks = tracks, albums = albums)
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
                    // endpoint feed — a snapshot's only while the endpoint read
                    // is out ([endpointFeedOwns]).
                    val keepEndpointFeed = endpointFeedOwns(scopeKey, currentContent)
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
                    // The endpoint's feed landed while the footnote loaded: it owns Activities now.
                    if (!keepEndpointFeed && endpointFeedOwns(scopeKey, latest)) return@collectLatest
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
                    if (!keepEndpointFeed) {
                        // The activity log as read now: a block of this
                        // process's, no longer a snapshot's endpoint feed.
                        snapshotEndpointFeeds -= scopeKey
                        markFresh(scopeKey)
                    }
                    // Nothing moved (the endpoint feed kept, or the log as up):
                    // no publish, and nothing to write back.
                    if (nextContent == latest) return@collectLatest
                    keep(scopeKey, providerId, profileId, nextContent)
                    emit(nextContent, profileId)
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
            // A replay re-sends the latest tick ([replaySignalTickMissedByLoad]).
            combine(
                repository.observeMemorySignalStamp().debounce(RECENT_HISTORY_DEBOUNCE_MS),
                signalTickReplays
            ) { stamp, _ -> stamp }
                .collectLatest { stamp ->
                    // The tick belongs to the scope it started in; if that
                    // moves while it builds, it is dropped, never "kept".
                    val providerId = repository.currentProviderId()
                    val profileId = activeProfileId.value
                    val scopeKey = homeScopeKey(providerId, profileId)
                    signalTickCount++
                    lastSignalTick = scopeKey to stamp
                    if (contentScopeKey != scopeKey) return@collectLatest
                    val currentContent = currentContent() ?: return@collectLatest
                    // A load of this scope is still building its signals (the
                    // local tier is up without them): it publishes them and
                    // replays this tick if they come out older.
                    if (activeLoad?.signalsPending == scopeKey) return@collectLatest
                    // Nothing moved since the last build read this stamp — the
                    // first tick replays the one the load itself just built on.
                    if (signalStamps[scopeKey] == stamp) return@collectLatest
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
                    // What's up now reflects this build's stamp, spliced in or already equal.
                    signals?.stamp?.let { built -> signalStamps[scopeKey] = built }
                    signals?.let { built -> scopeSignals[scopeKey] = built }
                    // Read now: a snapshot it lands on is written back from here.
                    if (signals != null) markFresh(scopeKey)
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
                    keep(scopeKey, providerId, profileId, nextContent)
                    emit(nextContent, profileId)
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
                    keep(scopeKey, play.provider, play.profileId, nextContent)
                    emit(nextContent, play.profileId)
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
        val (coverArtUrl, coverKey) = cardCover(track.coverArt, subsonicFallback = track.albumId)
        return HomeRediscoverItem(
            // MediaId never holds a blank id: an album-less song stands in for itself.
            albumId = track.albumId ?: track.id,
            albumName = song.album,
            artistName = song.artist.takeIf(String::isNotBlank),
            coverArtUrl = coverArtUrl,
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
            coverKey = coverKey,
        )
    }

    /**
     * Recompute just the memory-album and noted-track 1×2 cards and re-pack
     * them with the existing compact cards. A null [signals] (unscoped /
     * failed build) keeps the memory card already on screen.
     */
    private suspend fun refreshWidgetGridSignalCards(
        current: List<HomeWidgetCard>,
        signals: MemorySignals?,
    ): List<HomeWidgetCard> {
        val memory = if (signals != null) signals.memoryCard else current.memoryCard()
        val note = loadNotedTrackCard().getOrElse { current.notedTrackCard() }
        return repackWidgetGrid(current, memory = memory, note = note)
    }

    /**
     * [current]'s compact cards behind the wide [memory] and [note] cards,
     * deduping any compact the wide cards now cover, within the cover budget.
     */
    private fun repackWidgetGrid(
        current: List<HomeWidgetCard>,
        memory: Pair<HomeWidgetCard, MediaId?>?,
        note: Pair<HomeWidgetCard, MediaId>?
    ): List<HomeWidgetCard> {
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

    /** The grid's memory 1×2 with its album, as on screen. */
    private fun List<HomeWidgetCard>.memoryCard(): Pair<HomeWidgetCard, MediaId?>? =
        firstOrNull { it.target is HomeWidgetTarget.MemoryFocus }?.let { card -> card to memoryCardAlbumId(card) }

    private fun HomeUiState.Content.memoryCard(): Pair<HomeWidgetCard, MediaId?>? = widgetGrid.memoryCard()

    /** The grid's noted-track 1×2 with its track, as on screen. */
    private fun List<HomeWidgetCard>.notedTrackCard(): Pair<HomeWidgetCard, MediaId>? = firstNotNullOfOrNull { card ->
        (card.target as? HomeWidgetTarget.PlaySong)?.song?.id?.takeIf { card.expanded }?.let { card to it }
    }

    private fun HomeUiState.Content.notedTrackCard(): Pair<HomeWidgetCard, MediaId>? = widgetGrid.notedTrackCard()

    private fun HomeUiState.Content.recentlyAdded(): RecentlyAdded =
        RecentlyAdded(tracks = recentlyAddedTracks, albums = recentlyAddedAlbums)

    /** Whether [a] and [b] crown the same hero (the bento's album / playlist slot). */
    private fun sameHero(a: List<ActivityEvent>, b: List<ActivityEvent>): Boolean {
        val heroA = selectHomeHeroActivity(a)
        val heroB = selectHomeHeroActivity(b)
        return heroA?.entityType == heroB?.entityType && heroA?.entityId == heroB?.entityId
    }

    // ── Widget grid (Jump Back In × memories) ────────────────────────────
    //
    // The grid reads the persisted candidate pools: fresh pools (within
    // [GRID_POOLS_TTL_MS]) compose with zero network; older ones still go up
    // with the local tier while one re-fetch rotates them — the shelf's
    // rotation moment ([HomeLoad]). The memory / noted signal cards are
    // always resolved live regardless of pool age.

    /**
     * One network fan-out builds the next batch of recommendation pools,
     * pre-shuffled into their final order and persisted — so every open until
     * the TTL expires reads the same shelf straight from disk. When a read
     * fails and none brings anything (offline, server down, no source yet —
     * Apple Music's starred tracks "succeed" empty without a request) there is
     * no new batch: the persisted pools stay as they are and the shelf shows
     * them, whatever their age, instead of an empty batch wiping them.
     */
    private suspend fun fetchAndPersistGridPools(
        // The load's one library playlist read, shared with Your Playlists.
        playlistsRead: Deferred<Result<List<Playlist>>>
    ): YoinRepository.HomeGridPoolSnapshot? =
        coroutineScope {
            val albumsDeferred = async {
                guardedListResult { repository.getAlbumList("random", size = GRID_ALBUM_REQUEST_SIZE) }
            }
            val tracksDeferred = async { guardedListResult { loadGridTracks() } }
            val albums = albumsDeferred.await()
            val tracks = tracksDeferred.await()
            val playlists = playlistsRead.await()
            val reads = listOf(albums, tracks, playlists)
            // No new batch: the persisted pools stay, and so does the shelf
            // built from them (the local tier put them up).
            if (reads.any { it.isFailure } && reads.none { it.getOrNull().orEmpty().isNotEmpty() }) {
                return@coroutineScope null
            }
            val snapshot = YoinRepository.HomeGridPoolSnapshot(
                albums = albums.getOrDefault(emptyList())
                    .distinctBy { album -> album.id }
                    .shuffled()
                    .take(GRID_POOL_ALBUMS),
                // loadGridTracks is already random/shuffled at the source.
                tracks = tracks.getOrDefault(emptyList())
                    .distinctBy { track -> track.id }
                    .take(GRID_POOL_TRACKS),
                // Artless playlists only make the cut when there aren't
                // enough with artwork (see buildWidgetGrid).
                playlists = playlists.getOrDefault(emptyList())
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
    private fun buildWidgetGrid(
        // Null: none persisted yet — the wide cards alone.
        pools: YoinRepository.HomeGridPoolSnapshot?,
        memory: Pair<HomeWidgetCard, MediaId?>?,
        note: Pair<HomeWidgetCard, MediaId>?
    ): List<HomeWidgetCard> {
        // Covers first: an artless item (an Apple Music library playlist with
        // no artwork) reads as a grey hole in the shelf, so it only fills in
        // behind the ones with art — inside each pool here, and across the
        // whole shelf below (so a spare album with art beats an artless
        // playlist). Stable sorts: the persisted shuffle order holds within
        // each group, and the 2+2+3+3+2 recipe is unchanged whenever there is
        // enough art to fill it.
        val albumPool = pools?.albums.orEmpty()
            .filterNot { album -> album.id == memory?.second }
            .map { album -> album.toWidgetCard() }
            .sortedBy { card -> card.coverArtUrl == null }
        val trackPool = pools?.tracks.orEmpty()
            .filterNot { track -> track.id == note?.second }
            .map { track -> track.toWidgetCard() }
            .sortedBy { card -> card.coverArtUrl == null }
        val playlistPool = pools?.playlists.orEmpty()
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
        // Read BEFORE the build: a write landing mid-build moves the stamp past
        // this one, so [observeMemorySignals] still rebuilds for it — on its
        // tick, or on the load's replay of a tick that came in before the
        // publish ([replaySignalTickMissedByLoad]). Recorded only once content
        // built on it is published ([recordSignalStamp]).
        val stamp = guardedOrNull { repository.observeMemorySignalStamp().firstOrNull() }
        val pool = try {
            repository.getAlbumMemoryCandidates(limit = MEMORY_CANDIDATE_LIMIT, includeIneligible = true)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return null
        }
        val candidates = pool.memoryEligible(MEMORY_CANDIDATE_LIMIT)
        val noteCount = guardedOrNull { repository.countNotes() } ?: return null
        // Songs are Rediscover's alone: a failed read keeps the ones behind
        // the shelf up (none on a first build).
        val songs = guardedListResult {
            rediscoverSongs.load(
                provider = providerId,
                profileId = profileId,
                playedBefore = nowMillis() - REDISCOVER_AWAY_MS,
                limit = REDISCOVER_SONG_QUERY_LIMIT,
            )
        }.getOrElse { scopeSignals[homeScopeKey(providerId, profileId)]?.songs.orEmpty() }
        if (!matchesCurrentScope(providerId, profileId)) return null
        val pill = buildHomeMemoryPill(candidates, noteCount, scope = homeScopeKey(providerId, profileId))
            .withMemoryTitle(candidates)
        val memoryCard = pickJbiMemoryCandidate(
            candidates = candidates,
            avoidRawAlbumId = pill.latest?.albumId?.rawId,
            preferRawAlbumId = preferJbiRawAlbumId,
        )?.let { candidate -> toMemoryCard(candidate) }
        return MemorySignals(pill = pill, memoryCard = memoryCard, pool = pool, songs = songs, stamp = stamp)
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
            coverKey = candidate.coverArtKey,
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
     * duration) so the card can play the song on tap. Success(null): no
     * note; a failed note read is a failure (the card up stays).
     */
    private suspend fun loadNotedTrackCard(): Result<Pair<HomeWidgetCard, MediaId>?> {
        val notes = guardedListResult { repository.getRecentSongNotes(limit = 1) }
            .getOrElse { return Result.failure(it) }
        val note = notes.firstOrNull() ?: return Result.success(null)
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
        val (coverArtUrl, coverKey) = cardCover(coverRef)
        val card = HomeWidgetCard(
            stableId = "grid-note:${note.id}",
            entityType = MemoryEntityType.SONG,
            title = note.title,
            subtitle = subtitle,
            subtitleText = subtitleText,
            coverArtUrl = coverArtUrl,
            coverKey = coverKey,
            ratingText = score,
            ratingUnavailable = score == "N/A",
            ratingBasis = formatMemoryDate(note.updatedAt),
            ratingBasisDateMillis = note.updatedAt,
            comment = note.content,
            expanded = true,
            target = HomeWidgetTarget.PlaySong(song),
        )
        return Result.success(card to trackId)
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
        val (coverArtUrl, coverKey) = cardCover(coverArt, subsonicFallback = id)
        return HomeWidgetCard(
            stableId = "grid-album:$id",
            entityType = MemoryEntityType.ALBUM,
            title = name,
            subtitle = subtitle,
            subtitleText = subtitleText,
            coverArtUrl = coverArtUrl,
            target = HomeWidgetTarget.AlbumDetail(id.toString()),
            coverKey = coverKey,
        )
    }

    private fun Track.toWidgetCard(): HomeWidgetCard {
        val (subtitle, subtitleText) = singleSubtitle(artist)
        val (coverArtUrl, coverKey) = cardCover(coverArt, subsonicFallback = albumId)
        return HomeWidgetCard(
            stableId = "grid-song:$id",
            entityType = MemoryEntityType.SONG,
            title = title.orEmpty(),
            subtitle = subtitle,
            subtitleText = subtitleText,
            coverArtUrl = coverArtUrl,
            target = HomeWidgetTarget.PlaySong(this),
            coverKey = coverKey,
        )
    }

    private fun Playlist.toWidgetCard(): HomeWidgetCard {
        val (subtitle, subtitleText) = playlistSubtitle(owner)
        val (coverArtUrl, coverKey) = cardCover(coverArt)
        return HomeWidgetCard(
            stableId = "grid-playlist:$id",
            entityType = MemoryEntityType.PLAYLIST,
            title = name,
            subtitle = subtitle,
            subtitleText = subtitleText,
            coverArtUrl = coverArtUrl,
            target = HomeWidgetTarget.PlaylistDetail(id.toString()),
            coverKey = coverKey,
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

    /**
     * A list read that tells a failure apart from an empty result: a section
     * whose read fails keeps what it shows, an empty one empties (the grid
     * pools' rotation, Home's shelves, the noted track, Rediscover's songs).
     */
    private suspend fun <T> guardedListResult(block: suspend () -> List<T>): Result<List<T>> = guardedResult(block)

    private suspend fun <T> guardedResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (e: Exception) {
        Result.failure(e)
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
                configurationRevision = container.musicConfigurationRevision,
                switchingState = container.profileManager.switchingState,
                snapshotStore = container.homeSnapshotStore
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

        // How long a profile without its source yet holds the feed (Library
        // waits as long) before loading without one.
        private const val ACTIVE_SOURCE_WAIT_MS = 4_000L

        // How long the feed rests — loaded, its list still — before Spotify
        // is asked for its Activities artists' portraits (Q16): past the
        // first reveal, and never mid-fling.
        private const val ACTIVITY_PORTRAIT_SETTLE_MS = 1_500L

        // How long a load with nothing up waits for its snapshot's read (a
        // small file, read since the VM or the switch began; usually in by
        // then) and the account's layout before going ahead with Loading.
        private const val SNAPSHOT_READ_WAIT_MS = 400L

        // The pixel size Home resolves its cards' covers at (grid, Rediscover,
        // the pill's memory), a snapshot's included.
        private const val HOME_COVER_SIZE = 480
        private val MemoryDateFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMM d", Locale.US)
        private val homeContentCache = mutableMapOf<String, HomeUiState.Content>()

        // What a scope's content up, or in [homeContentCache], still has of
        // its snapshot — with the cache, as long as the process (a ViewModel
        // made after the last one's Activity finished paints the cache).
        // Scopes whose content is only their snapshot: nothing this process
        // read has gone into it yet. [keep] writes none of them back — it
        // would store the feed already on disk as new — until a block read
        // now lands in it ([markFresh]).
        private val snapshotOnlyScopes = mutableSetOf<String>()

        // Scopes whose Activities are their snapshot's Spotify endpoint feed
        // (an earlier process's recently-played): it holds the place while
        // this process's endpoint read is out, never past a failed one — then
        // the activity log takes over, live, as on a cold start without a
        // snapshot ([HomeLoad.activitiesFor], [endpointFeedOwns]).
        private val snapshotEndpointFeeds = mutableSetOf<String>()

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

/** No pools persisted, or an empty batch. */
private fun YoinRepository.HomeGridPoolSnapshot?.isNullOrEmpty(): Boolean =
    this == null || (albums.isEmpty() && tracks.isEmpty() && playlists.isEmpty())

/** This event's raw artist id when it is a Spotify artist's, else null. */
private fun ActivityEvent.spotifyArtistRawId(): String? =
    if (provider == MediaId.PROVIDER_SPOTIFY && entityType == ActivityEntityType.ARTIST.name) {
        MediaId.storedRawId(MediaId.PROVIDER_SPOTIFY, entityId)
    } else {
        null
    }

/** What Home's content belongs to: the active profile and its source's provider (null until built). */
private data class HomeScope(val providerId: String?, val profileId: String?) {
    /**
     * [next] is the first half of a switch between providers: the incoming
     * source on the outgoing profile (ProfileManager.switchTo sets the source,
     * then setActive).
     */
    fun handsOverTo(next: HomeScope): Boolean = profileId != null && next.profileId == profileId &&
        providerId != null && next.providerId != null && next.providerId != providerId
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
    // The memory-signal stamp read before the build (null: unread).
    val stamp: Long? = null,
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
        coverKey = candidate.coverArtKey,
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
