package com.gpo.yoin.ui.home

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.ui.common.asString
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.experience.ReportMotionPressure
import com.gpo.yoin.ui.experience.RevealState
import com.gpo.yoin.ui.experience.rememberRevealState
import com.gpo.yoin.ui.home.edit.HomeEditController
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.settings.ProfileCard
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

private const val HomeLoadingIndicatorDelayMillis = 180L

// Loading reports motion pressure (design.md: AdaptiveReduced while Home
// loads; the intro waits up to 0.4 s for it to clear). A load that hangs past
// this lifts it anyway, so the whole app doesn't stay reduced behind it.
private const val HOME_LOADING_PRESSURE_MAX_MS = 3_000L
private val HomeInitialEntranceOffset = 16.dp

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    isPlaying: Boolean,
    playbackSignal: Float,
    activeSongId: String? = null,
    // Something above Home owns the screen (Now Playing, the detail column):
    // the Memories bubble keeps quiet and the edit wiggle stops.
    homeCovered: Boolean = false,
    // Home edit mode's controller, hoisted in the shell so back, the bar and
    // every exit trigger reach it (edit mode is in place, in the feed).
    editController: HomeEditController? = null,
    onNavigateToSettings: () -> Unit,
    onNavigateToMemories: () -> Unit,
    onOpenMemoryFocus: (sessionId: Long) -> Unit = {},
    memoriesRevealState: RevealState = rememberRevealState(),
    onCommitMemoriesReveal: () -> Unit = {},
    onAlbumClick: (albumId: String, sharedTransitionKey: String?) -> Unit,
    onArtistClick: (artistId: String) -> Unit,
    onPlaylistClick: (playlistId: String) -> Unit,
    onSongClick: (Track) -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
    // The account switcher behind the header avatar (null = the plain Settings gear).
    accountSwitcher: AccountSwitcherViewModel? = null,
) {
    val accountCards by (accountSwitcher?.cards ?: remember { MutableStateFlow(emptyList()) }).collectAsState()
    val activeAccount = accountCards.firstOrNull { it.isActive }
    // Shown = the dialog window exists; open = its card is in (it animates out before the window goes).
    var switcherShown by rememberSaveable { mutableStateOf(false) }
    var switcherOpen by rememberSaveable { mutableStateOf(false) }
    var accountAnchor by remember { mutableStateOf<Rect?>(null) }
    // A trip to Settings waits until the card is back in the avatar: launched at once, Settings covers the shell
    // mid-spring, the stopped window's frame clock pauses, and the half-closed card greets you on the way back.
    // (Only the spring's invisible tail may be left then.)
    var afterSwitcherDismissed by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun closeSwitcherThen(action: () -> Unit) {
        afterSwitcherDismissed = action
        switcherOpen = false
    }
    if (switcherShown && accountSwitcher != null) {
        HomeAccountSwitcherDialog(
            visible = switcherOpen,
            anchor = accountAnchor,
            cards = accountCards,
            onRequestClose = { switcherOpen = false },
            onCardHome = {
                afterSwitcherDismissed?.let { action ->
                    afterSwitcherDismissed = null
                    action()
                }
            },
            onDismissed = { switcherShown = false },
            onSwitch = { card ->
                // An account that can't be used right now is recovered in Settings.
                if (card.unavailableReason != null) {
                    closeSwitcherThen(onNavigateToSettings)
                } else {
                    switcherOpen = false
                    accountSwitcher.switchTo(card.id)
                }
            },
            onManageAccounts = { closeSwitcherThen(onNavigateToSettings) },
            onAddAccount = { closeSwitcherThen(onNavigateToSettings) },
            onOpenSettings = { closeSwitcherThen(onNavigateToSettings) },
            onEditHome = {
                switcherOpen = false
                editController?.enter(null, lifted = false)
            },
        )
    }
    val uiState by viewModel.uiState.collectAsState()
    val homeLayout by viewModel.homeLayout.collectAsState()
    // A section new since the last edit session waits in the tray (Q6a).
    val unseenNewSections by viewModel.unseenNewSections.collectAsState()
    // A new instance per source: covers resolved before it was up resolve again.
    val buildCoverArtUrl by viewModel.coverArtUrlBuilder.collectAsState()

    HomeContent(
        uiState = uiState,
        sections = homeLayout.sections,
        editController = editController,
        footerNewBadge = unseenNewSections.isNotEmpty(),
        isPlaying = isPlaying,
        playbackSignal = playbackSignal,
        activeSongId = activeSongId,
        onNavigateToSettings = onNavigateToSettings,
        activeAccount = activeAccount,
        onOpenAccounts = {
            switcherShown = true
            switcherOpen = true
        },
        accountButtonHidden = switcherShown,
        onAccountAnchor = { accountAnchor = it },
        onNavigateToMemories = onNavigateToMemories,
        onOpenMemoryFocus = onOpenMemoryFocus,
        memoriesRevealState = memoriesRevealState,
        onCommitMemoriesReveal = onCommitMemoriesReveal,
        onAlbumClick = onAlbumClick,
        onArtistClick = onArtistClick,
        onPlaylistClick = onPlaylistClick,
        onSongClick = onSongClick,
        homeCovered = homeCovered,
        onRetry = viewModel::refresh,
        buildCoverArtUrl = buildCoverArtUrl,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        modifier = modifier,
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun HomeContent(
    uiState: HomeUiState,
    sections: List<HomeSectionState> = HomeLayout.Default.sections,
    // Home edit mode's shell controller; null = a standalone one (previews, tests).
    editController: HomeEditController? = null,
    // The feed's "Edit Home" footer is badged "New" (D5).
    footerNewBadge: Boolean = false,
    isPlaying: Boolean,
    playbackSignal: Float,
    activeSongId: String? = null,
    onNavigateToSettings: () -> Unit,
    // The account in use (null = none yet) and opening the account switcher.
    activeAccount: ProfileCard? = null,
    onOpenAccounts: () -> Unit = onNavigateToSettings,
    accountButtonHidden: Boolean = false,
    onAccountAnchor: (Rect) -> Unit = {},
    onNavigateToMemories: () -> Unit,
    onOpenMemoryFocus: (sessionId: Long) -> Unit = {},
    memoriesRevealState: RevealState = rememberRevealState(),
    onCommitMemoriesReveal: () -> Unit = {},
    onAlbumClick: (albumId: String, sharedTransitionKey: String?) -> Unit,
    onArtistClick: (artistId: String) -> Unit,
    onPlaylistClick: (playlistId: String) -> Unit,
    onSongClick: (Track) -> Unit,
    // Something above Home owns the screen (Now Playing, the detail column).
    homeCovered: Boolean = false,
    onRetry: () -> Unit,
    buildCoverArtUrl: (String) -> String,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    ReportMotionPressure(
        tag = "home",
        isHighPressure = uiState is HomeUiState.Loading,
        maxDurationMs = HOME_LOADING_PRESSURE_MAX_MS,
    )

    ProvideYoinMotionRole(role = YoinMotionRole.Expressive) {
        // The same page gradient as Library (surfaceContainer → background →
        // surfaceContainerLow): a flat background read as a different app
        // beside it, and left the Now Playing panel's reveal bare.
        ExpressivePageBackground(modifier = modifier.fillMaxSize()) {
            val isLoading = uiState is HomeUiState.Loading
            val isContent = uiState is HomeUiState.Content
            val contentEntranceOffsetPx = with(LocalDensity.current) { HomeInitialEntranceOffset.toPx() }
            var showDelayedLoading by remember { mutableStateOf(false) }
            var hasPlayedInitialContentEntrance by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(isLoading) {
                if (!isLoading) {
                    showDelayedLoading = false
                    return@LaunchedEffect
                }
                showDelayedLoading = false
                delay(HomeLoadingIndicatorDelayMillis)
                showDelayedLoading = true
            }
            LaunchedEffect(isContent) {
                if (isContent && !hasPlayedInitialContentEntrance) {
                    hasPlayedInitialContentEntrance = true
                }
            }
            val contentAlphaState = animateFloatAsState(
                targetValue = if (isContent && hasPlayedInitialContentEntrance) 1f else 0f,
                animationSpec = YoinMotion.defaultEffectsSpec(),
                label = "homeInitialContentAlpha",
            )
            val contentAlpha by contentAlphaState
            val contentOffsetProgress by animateFloatAsState(
                targetValue = if (isContent && hasPlayedInitialContentEntrance) 1f else 0f,
                animationSpec = YoinMotion.defaultSpatialSpec(),
                label = "homeInitialContentOffset",
            )
            // A feed that goes back to Loading (an account switch, a deleted
            // account) leaves on the springs it came in on — fading, settling
            // the 16dp back down — before Loading takes the page, instead of
            // blinking out.
            var lastFeed by remember { mutableStateOf<HomeUiState.Content?>(null) }
            LaunchedEffect(uiState) {
                if (uiState is HomeUiState.Content) lastFeed = uiState
            }
            val feedVisible by remember { derivedStateOf { contentAlphaState.value > 0f } }
            val feed = uiState as? HomeUiState.Content
                ?: lastFeed?.takeIf { uiState is HomeUiState.Loading && feedVisible }
            // Each account's feed keeps its own list state (the scroll) while
            // Loading stands over it, so a failed switch brings the feed back
            // where it was; another account's feed starts fresh, and drops it.
            val feedStates = rememberSaveableStateHolder()
            val feedOwner = feed?.ownerProfileId.orEmpty()
            var keptFeedOwner by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(feed != null, feedOwner) {
                if (feed == null) return@LaunchedEffect
                keptFeedOwner?.takeIf { it != feedOwner }?.let(feedStates::removeState)
                keptFeedOwner = feedOwner
            }

            when {
                feed != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = contentAlpha
                                translationY = (1f - contentOffsetProgress) * contentEntranceOffsetPx
                            },
                    ) {
                        // Edit mode happens in place, inside the feed (HomeEditorialContent).
                        feedStates.SaveableStateProvider(feedOwner) {
                            HomeEditorialContent(
                                activities = feed.activities,
                                widgetGrid = feed.widgetGrid,
                                activityHeroFootnote = feed.activityHeroFootnote,
                                activityHeroYear = feed.activityHeroYear,
                                activityHeroSongCount = feed.activityHeroSongCount,
                                activityHeroMinutes = feed.activityHeroMinutes,
                                recentlyAddedTracks = feed.recentlyAddedTracks,
                                recentlyAddedAlbums = feed.recentlyAddedAlbums,
                                rediscover = feed.rediscover,
                                playlists = feed.playlists,
                                recentlyPlayed = feed.recentlyPlayed,
                                memoryPill = feed.memoryPill,
                                homeCovered = homeCovered,
                                sections = sections,
                                onNavigateToSettings = onNavigateToSettings,
                                activeAccount = activeAccount,
                                onOpenAccounts = onOpenAccounts,
                                accountButtonHidden = accountButtonHidden,
                                onAccountAnchor = onAccountAnchor,
                                onNavigateToMemories = onNavigateToMemories,
                                editController = editController,
                                footerNewBadge = footerNewBadge,
                                onOpenMemoryFocus = onOpenMemoryFocus,
                                memoriesRevealState = memoriesRevealState,
                                onCommitMemoriesReveal = onCommitMemoriesReveal,
                                onAlbumClick = onAlbumClick,
                                onArtistClick = onArtistClick,
                                onPlaylistClick = onPlaylistClick,
                                onSongClick = onSongClick,
                                buildCoverArtUrl = buildCoverArtUrl,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                uiState is HomeUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = uiState.messageText?.asString() ?: uiState.message,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(onClick = onRetry) {
                                    Text(stringResource(R.string.home_error_retry))
                                }
                                TextButton(onClick = onNavigateToSettings) {
                                    Text(stringResource(R.string.home_error_settings))
                                }
                            }
                        }
                    }
                }

                else -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (showDelayedLoading) {
                            YoinLoadingIndicator()
                        }
                    }
                }
            }
        }
    }
}

// Previews

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun HomeContentLoadingPreview() {
    YoinTheme {
        HomeContent(
            uiState = HomeUiState.Loading,
            isPlaying = false,
            playbackSignal = 0f,
            activeSongId = null,
            onNavigateToSettings = {},
            onNavigateToMemories = {},
            onAlbumClick = { _, _ -> },
            onArtistClick = {},
            onPlaylistClick = {},
            onSongClick = { _ -> },
            onRetry = {},
            buildCoverArtUrl = { "" },
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun HomeContentErrorPreview() {
    YoinTheme {
        HomeContent(
            uiState = HomeUiState.Error("Failed to connect to server"),
            isPlaying = false,
            playbackSignal = 0f,
            activeSongId = null,
            onNavigateToSettings = {},
            onNavigateToMemories = {},
            onAlbumClick = { _, _ -> },
            onArtistClick = {},
            onPlaylistClick = {},
            onSongClick = { _ -> },
            onRetry = {},
            buildCoverArtUrl = { "" },
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun HomeContentPreview() {
    YoinTheme {
        HomeContent(
            uiState = HomeUiState.Content(
                activities = listOf(
                    ActivityEvent(
                        id = 1,
                        entityType = ActivityEntityType.ALBUM.name,
                        actionType = ActivityActionType.PLAYED.name,
                        entityId = "a1",
                        title = "Black Holes and Revelations",
                        subtitle = "Muse",
                        coverArtId = "c1",
                        albumId = "a1",
                        songId = "s1",
                        artistId = "artist-1",
                        timestamp = System.currentTimeMillis() - 3_600_000L,
                    ),
                    ActivityEvent(
                        id = 2,
                        entityType = ActivityEntityType.ARTIST.name,
                        actionType = ActivityActionType.VISITED.name,
                        entityId = "artist-2",
                        title = "Daft Punk",
                        subtitle = "Artist",
                        coverArtId = "c2",
                        artistId = "artist-2",
                        timestamp = System.currentTimeMillis() - 86_400_000L,
                    ),
                    ActivityEvent(
                        id = 3,
                        entityType = ActivityEntityType.SONG.name,
                        actionType = ActivityActionType.PLAYED.name,
                        entityId = "s3",
                        title = "Starlight",
                        subtitle = "Muse",
                        coverArtId = "c1",
                        albumId = "a2",
                        songId = "s3",
                        artistId = "artist-1",
                        timestamp = System.currentTimeMillis() - 172_800_000L,
                    ),
                ),
                widgetGrid = listOf(
                    HomeWidgetCard(
                        stableId = "grid-memory:preview",
                        entityType = MemoryEntityType.ALBUM,
                        title = "Describe",
                        subtitle = "Hannah Jadagu",
                        coverArtUrl = null,
                        ratingText = "7.0",
                        ratingBasis = "Based on 5/5 tracks",
                        comment = "小さな家路で、愛を歌う",
                        expanded = true,
                        target = HomeWidgetTarget.MemoryFocus(sessionId = 1L),
                    ),
                    HomeWidgetCard(
                        stableId = "grid-song:preview",
                        entityType = MemoryEntityType.SONG,
                        title = "Little House",
                        subtitle = "Rachel Chinouriri",
                        coverArtUrl = null,
                        target = HomeWidgetTarget.PlaySong(
                            Track(
                                id = MediaId.subsonic("js1"),
                                title = "Little House",
                                artist = "Rachel Chinouriri",
                                album = "Little House",
                                artistId = null,
                                albumId = MediaId.subsonic("album-js1"),
                                coverArt = CoverRef.SourceRelative("cover-js1"),
                                durationSec = null,
                                trackNumber = null,
                                year = null,
                                genre = null,
                                userRating = null,
                            ),
                        ),
                    ),
                    HomeWidgetCard(
                        stableId = "grid-playlist:preview",
                        entityType = MemoryEntityType.PLAYLIST,
                        title = "Endless Natsu",
                        subtitle = "51",
                        coverArtUrl = null,
                        target = HomeWidgetTarget.PlaylistDetail("subsonic:pl1"),
                    ),
                ),
            ),
            isPlaying = true,
            playbackSignal = 0.35f,
            activeSongId = "js1",
            onNavigateToSettings = {},
            onNavigateToMemories = {},
            onAlbumClick = { _, _ -> },
            onArtistClick = {},
            onPlaylistClick = {},
            onSongClick = { _ -> },
            onRetry = {},
            buildCoverArtUrl = { "" },
        )
    }
}
