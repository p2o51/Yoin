package com.gpo.yoin.ui.home

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.gpo.yoin.ui.theme.LocalYoinMotionRole
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

private const val HomeLoadingIndicatorDelayMillis = 180L

/** How Home's hold on the header avatar ends ([HomeAccountHold]). */
private enum class HomeHoldEnd { HandBack, FadeAway, Release }

// Loading's indicator springs in from (and out to) this scale.
private const val HOME_LOADING_INDICATOR_SCALE = 0.6f

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
    val uiState by viewModel.uiState.collectAsState()
    val homeLayout by viewModel.homeLayout.collectAsState()
    // A section new since the last edit session waits in the tray (Q6a).
    val unseenNewSections by viewModel.unseenNewSections.collectAsState()
    // A new instance per source: covers resolved before it was up resolve again.
    val buildCoverArtUrl by viewModel.coverArtUrlBuilder.collectAsState()

    HomeAccountSwitcherHost(
        accountSwitcher = accountSwitcher,
        onNavigateToSettings = onNavigateToSettings,
        onEditHome = { editController?.enter(null, lifted = false) },
    ) { account ->
        HomeContent(
            uiState = uiState,
            sections = homeLayout.sections,
            editController = editController,
            footerNewBadge = unseenNewSections.isNotEmpty(),
            isPlaying = isPlaying,
            playbackSignal = playbackSignal,
            activeSongId = activeSongId,
            onNavigateToSettings = onNavigateToSettings,
            activeAccount = account.account,
            onOpenAccounts = account.onOpen,
            accountButtonHidden = account.windowUp,
            onAccountAnchor = account.onAnchor,
            accountSwitchPending = account.switchPending,
            accountAnchor = account.anchor,
            accountHandedUp = account.landed,
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
            onActivityArtistsShown = viewModel::onActivityArtistsShown,
            onFeedAtRestChanged = viewModel::onFeedAtRestChanged,
        )
    }
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
    // What the feed pictures in Activities and whether it rests (owner Q16).
    onActivityArtistsShown: (List<String>) -> Unit = {},
    onFeedAtRestChanged: (Boolean) -> Unit = {},
    // A switch begun from Home's account card is under way ([activeAccount] is
    // its target), where the header's avatar is (window px), and whether the
    // card's close flew an avatar into the header (false: it faded in place).
    accountSwitchPending: Boolean = false,
    accountAnchor: Rect? = null,
    accountHandedUp: Boolean = true
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
            val contentOffsetState = animateFloatAsState(
                targetValue = if (isContent && hasPlayedInitialContentEntrance) 1f else 0f,
                animationSpec = YoinMotion.defaultSpatialSpec(),
                label = "homeInitialContentOffset",
            )
            val contentOffsetProgress by contentOffsetState

            // The header's avatar through a switch begun from Home: the feed
            // (header and all) goes to Loading and comes back, so a stand-in
            // holds the avatar's spot from the tap — the target account, back
            // to the one in use if the switch fails — until the page is back
            // under it. Meanwhile the feed fades in place (no 16dp settle), so
            // the header's avatar is where the stand-in rests when it hands over.
            val avatarHold = remember { HomeAccountHold() }
            LaunchedEffect(accountSwitchPending) {
                if (accountSwitchPending) avatarHold.begin()
            }
            val holdingAvatar = accountSwitchPending || avatarHold.latched
            val currentUiState by rememberUpdatedState(uiState)
            val currentAnchor by rememberUpdatedState(accountAnchor)
            val currentPending by rememberUpdatedState(accountSwitchPending)
            // The feed's own at-rest reports (Q16's, passed on as they are),
            // with the account whose feed made each, read here for one thing:
            // a feed that was resting and isn't any more is being moved (a
            // scroll, a pull), and the stand-in doesn't chase it.
            var feedRest by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
            val holdFadeSpec = YoinMotion.defaultEffectsSpec<Float>()
            LaunchedEffect(avatarHold.generation) {
                if (avatarHold.generation == 0) return@LaunchedEffect
                avatarHold.fade.snapTo(1f)
                // The switch first: landed, failed or turned away.
                snapshotFlow { currentPending }.first { !it }
                // Then the page under the stand-in. On a feed: hand back once it
                // is all in and the stand-in rests on its avatar — at once if
                // the feed is moved first. On an error page (no header): fade.
                fun feedIn() = contentAlphaState.value >= 1f && contentOffsetState.value == 1f
                var rested: String? = null
                val end = snapshotFlow {
                    when (val state = currentUiState) {
                        is HomeUiState.Error -> HomeHoldEnd.FadeAway
                        is HomeUiState.Content -> {
                            // Only this feed's own reports: one going (its last
                            // "not resting") is no move of the one coming.
                            val owner = state.ownerProfileId.orEmpty()
                            val rest = feedRest?.takeIf { it.first == owner }?.second
                            val moved = rested == owner && rest == false
                            if (rest == true) rested = owner
                            when {
                                avatarHold.abandoned -> HomeHoldEnd.Release
                                moved -> HomeHoldEnd.HandBack
                                feedIn() && (avatarHold.atRest || currentAnchor == null) -> HomeHoldEnd.HandBack
                                else -> null
                            }
                        }
                        HomeUiState.Loading -> {
                            rested = null
                            null
                        }
                    }
                }.filterNotNull().first()
                when (end) {
                    HomeHoldEnd.HandBack -> avatarHold.handBack(holdFadeSpec)
                    HomeHoldEnd.FadeAway -> avatarHold.fadeAway(holdFadeSpec)
                    HomeHoldEnd.Release -> Unit
                }
                // The feed settles where it is before Home's entrance offset
                // has a say again: no 16dp jump under the header's avatar.
                snapshotFlow { currentUiState !is HomeUiState.Content || feedIn() }.first { it }
                avatarHold.release()
            }
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
            val reportFeedAtRest = remember(onFeedAtRestChanged, feedOwner) {
                { rest: Boolean ->
                    feedRest = feedOwner to rest
                    onFeedAtRestChanged(rest)
                }
            }
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
                                translationY = if (holdingAvatar) {
                                    0f
                                } else {
                                    (1f - contentOffsetProgress) * contentEntranceOffsetPx
                                }
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
                                albumMemoryMarks = feed.albumMemoryMarks,
                                playlists = feed.playlists,
                                recentlyPlayed = feed.recentlyPlayed,
                                memoryPill = feed.memoryPill,
                                homeCovered = homeCovered,
                                sections = sections,
                                onNavigateToSettings = onNavigateToSettings,
                                activeAccount = activeAccount,
                                onOpenAccounts = onOpenAccounts,
                                accountButtonHidden = accountButtonHidden ||
                                    (holdingAvatar && !avatarHold.headerAvatarBack),
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
                                onActivityArtistsShown = onActivityArtistsShown,
                                onFeedAtRestChanged = reportFeedAtRest,
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

                else -> Unit
            }

            // Loading's indicator, past its delay: springs in, and out again when
            // the feed (or an error) takes the page — kept outside the branches
            // above so it can leave on its own exit.
            val indicatorRole = LocalYoinMotionRole.current
            AnimatedVisibility(
                visible = isLoading && showDelayedLoading,
                modifier = Modifier.align(Alignment.Center),
                enter = YoinMotion.fadeIn(indicatorRole) +
                    YoinMotion.scaleIn(indicatorRole, initialScale = HOME_LOADING_INDICATOR_SCALE),
                exit = YoinMotion.fadeOut(indicatorRole) +
                    YoinMotion.scaleOut(indicatorRole, targetScale = HOME_LOADING_INDICATOR_SCALE)
            ) {
                YoinLoadingIndicator()
            }

            HomeAccountAvatarHold(
                hold = avatarHold,
                holding = holdingAvatar,
                card = activeAccount,
                anchor = accountAnchor,
                // The header is on the page to say where it is.
                anchorLive = feed != null,
                // Until the switcher's window (its flight) has gone.
                shown = !accountButtonHidden,
                landed = accountHandedUp,
                modifier = Modifier.matchParentSize()
            )
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
