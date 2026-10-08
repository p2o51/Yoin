package com.gpo.yoin.ui.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.ReleaseType
import com.gpo.yoin.data.source.ServiceFeatureCatalog
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.common.asString
import com.gpo.yoin.ui.component.BarExtraAction
import com.gpo.yoin.ui.component.DetailErrorState
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.ExpressiveSegmentedTabs
import com.gpo.yoin.ui.component.MetaGroup
import com.gpo.yoin.ui.component.YoinDropdownMenuItem
import com.gpo.yoin.ui.component.expressivePageSeamBackground
import com.gpo.yoin.ui.component.formatTrackDuration
import com.gpo.yoin.ui.component.seamDissolve
import com.gpo.yoin.ui.component.seamDissolveViewport
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.component.seamRemainingPx
import com.gpo.yoin.ui.component.yoinPageContentWidth
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.navigation.YoinSection
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.rememberCoverColorScheme
import com.gpo.yoin.ui.theme.withTabularFigures

/*
 * Artist page, rebuilt 2026-09 around what every provider can actually supply.
 *
 * Provider data is thin and shrinking (Spotify's Feb 2026 Web API migration
 * removed artist top tracks, related artists, followers and popularity for
 * Development Mode apps; Subsonic and Apple Music library artists never had
 * them). What remains everywhere: name, portrait, releases. So the page leans
 * on the user's OWN layer instead, like the Album page does:
 *
 *   header      back, name, year span, follow star
 *   hero        pinwheel mark around the portrait
 *   meta        Last Play | Avg. of your album ratings (Album hero anatomy)
 *   Most Played your own most-played songs (local play history)
 *   Discography release timeline: year column, type, your rating per release
 */

@Composable
fun ArtistDetailScreen(
    uiState: ArtistDetailUiState,
    onBackClick: () -> Unit,
    // The actual window exit, invoked by the back-collapse handler AFTER its
    // commit motion. Defaults to onBackClick so previews/tests keep the old
    // direct-exit behaviour; the Activity passes a dispatcher-routed
    // onBackClick + a finish()-ing onLeavePage.
    onLeavePage: () -> Unit = onBackClick,
    onAlbumClick: (albumId: String) -> Unit,
    onRetry: () -> Unit,
    onToggleFollow: () -> Unit = {},
    onPlay: () -> Unit = {},
    onShuffle: () -> Unit = {},
    // The ▾ rows after Shuffle play (Add to queue, Open in …): the host builds them.
    menu: DetailMenu = DetailMenu(),
    onMostPlayedClick: (index: Int) -> Unit = {},
    onShare: () -> Unit = {},
    isPlaying: Boolean = false,
    playbackSignal: Float = 0f,
    onOpenNowPlaying: () -> Unit = {},
    nowPlayingOpen: Boolean = false,

    // True when a nav-pose bar sits beneath this window: predictive back
    // scrubs the bar toward nav chrome (matching the reveal underneath), and
    // without a shell hand-off the bar morphs nav→detail in-window on reveal.
    morphBarOnBack: Boolean = false,
    // FullChoreography only: the back pose is bridged to the shell (its content
    // plays the entering side, its bar morphs in lockstep). Plain pushes keep
    // the morph inside this window.
    bridgeBackToShell: Boolean = morphBarOnBack,
    // Shell tab at launch time (the back scrub's revealed selection) and
    // whether the launch used the bar hand-off window animation (delays the
    // content slide-in to match the transparent hold).
    navSection: YoinSection = YoinSection.HOME,
    enterBarHandoff: Boolean = false,
    // NP-origin: the back reveal is the expanded player (no bar there) — the
    // bar rides the gesture down off-screen instead of morphing.
    barExitsOnBack: Boolean = false,
    miniPlayerState: DetailMiniPlayerState? = null,
    playbackProgress: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val content = uiState as? ArtistDetailUiState.Content
    // Portrait → first album cover fallback (older Subsonic has no artist.jpg).
    val heroUrl = content?.heroCoverArtUrl ?: content?.albums?.firstOrNull()?.coverArtUrl
    val pageAccent = rememberDetailPageAccent(heroUrl)

    // Material roles seeded from the portrait (same MCU path as Album/Playlist),
    // animated so the resolve doesn't pop.
    val scheme = rememberCoverColorScheme(heroUrl) ?: MaterialTheme.colorScheme
    val titleColor by animateColorAsState(scheme.primary, YoinMotion.effectsSpring(), label = "artistTitleColor")
    val armLowerLeft by animateColorAsState(scheme.tertiary, YoinMotion.effectsSpring(), label = "artistArmLowerLeft")
    val armUpper by animateColorAsState(scheme.primary, YoinMotion.effectsSpring(), label = "artistArmUpper")
    val armLowerRight by animateColorAsState(scheme.secondary, YoinMotion.effectsSpring(), label = "artistArmLowerRight")
    val colors = ArtistPageColors(
        // Pinwheel arms in mark order: lower-left, upper, lower-right.
        arms = listOf(armLowerLeft, armUpper, armLowerRight),
        accent = titleColor,
    )

    val provider = content?.artistId?.let { MediaId.parseOrNull(it)?.provider }
    val supportsFollow = content != null && ServiceFeatureCatalog.forProvider(provider).supportsFavorites
    // Breakpoints (断点交接 §5): height first, then width. From Medium up the
    // hero carries the name and Follow, so the header keeps only back — the
    // name appears once.
    val windowInfo = LocalYoinWindowInfo.current
    val landscape = windowInfo.isCompactHeight
    val heroCarriesIdentity = !landscape &&
        windowInfo.layoutMode != LayoutMode.Compact && windowInfo.layoutMode != LayoutMode.Tabletop

    ProvideYoinMotionRole(role = YoinMotionRole.Expressive) {
        // In-window predictive back (AOSP cross-activity math): the whole
        // page — background included — collapses as one card over the LIVE
        // window beneath (the Activity turns translucent for the gesture);
        // the bar is a sibling on top and never transforms — it scrubs its
        // own morph off the same progress.
        val backCollapse = rememberDetailBackCollapse(
            onBack = onLeavePage,
            bridgeToShell = bridgeBackToShell,
        )
        // The header arrow leaves THIS page through its commit choreography —
        // never via the window's back dispatcher, where an open Now Playing
        // side panel ranks first and would take the tap. In the shell's
        // detail column it pops the column's stack, as before.
        @Suppress("NAME_SHADOWING")
        val onBackClick: () -> Unit = if (LocalDetailHostMode.current == DetailHostMode.Pane) {
            onBackClick
        } else {
            backCollapse::requestBack
        }
        val enterIntro = rememberDetailEnterIntro(
            barHandoff = enterBarHandoff && bridgeBackToShell,
            visualReady = uiState !is ArtistDetailUiState.Loading,
            back = backCollapse,
        )
        Box(
            modifier = modifier.then(
                rememberDetailMotionFrameRateModifier(backCollapse, enterIntro),
            ),
        ) {
            if (enterIntro.pageVisible) {
                DetailEnterPageMountEffect(enterIntro)
                ExpressivePageBackground(
                    accentColor = pageAccent,
                    isPlaying = isPlaying,
                    playbackSignal = playbackSignal,
                    modifier = Modifier
                        .fillMaxSize()
                        .detailBackCollapseTransform(backCollapse)
                        .detailEnterIntroTransform(enterIntro),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .detailChromeBand(),
                    ) {
                        // The Album / Playlist compact header; persists across
                        // Loading/Error/Content (it carries the back affordance).
                        ArtistTopHeader(
                            artistName = content?.artistName.orEmpty(),
                            activeSpan = content?.let { artistActiveSpan(it.albums) },
                            titleColor = titleColor,
                            showIdentity = !heroCarriesIdentity,
                            showFollow = supportsFollow && !heroCarriesIdentity,
                            following = content?.isStarred == true,
                            followLabels = artistFollowLabels(provider),
                            onBackClick = onBackClick,
                            onToggleFollow = onToggleFollow,
                        )
                        AnimatedContent(
                            targetState = uiState,
                            transitionSpec = {
                                YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                                    YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                            },
                            // Class-keyed so Content→Content updates (the personal
                            // layer arriving, follow toggles) don't re-trigger the fade.
                            contentKey = { it::class },
                            label = "artistDetailState",
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) { state ->
                            when (state) {
                                is ArtistDetailUiState.Loading ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .navigationBarsPadding(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        DetailLoadingIndicator(enterIntro)
                                    }

                                // No onBack: the persistent header carries it.
                                is ArtistDetailUiState.Error ->
                                    DetailErrorState(
                                        message = state.message.asString(),
                                        onRetry = onRetry,
                                    )

                                is ArtistDetailUiState.Content ->
                                    ArtistBody(
                                        content = state,
                                        heroUrl = state.heroCoverArtUrl
                                            ?: state.albums.firstOrNull()?.coverArtUrl,
                                        colors = colors,
                                        onAlbumClick = onAlbumClick,
                                        onMostPlayedClick = onMostPlayedClick,
                                        follow = if (supportsFollow) {
                                            {
                                                ArtistFollowStar(
                                                    following = state.isStarred,
                                                    labels = artistFollowLabels(provider),
                                                    activeTint = titleColor,
                                                    onToggle = onToggleFollow,
                                                    showLabel = true,
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                    )
                            }
                        }
                    }
                }
            }

            // Persistent bottom bar — rendered in ALL states; Play/menu act on
            // Content and no-op during Loading/Error.
            DetailBottomBar(
                playContainer = scheme.primary,
                playContent = scheme.onPrimary,
                onPlay = onPlay,
                onShuffle = onShuffle,
                onOpenNowPlaying = onOpenNowPlaying,
                miniPlayer = miniPlayerState,
                playbackProgress = playbackProgress,
                nowPlayingOpen = nowPlayingOpen,

                interactionsEnabled = enterIntro.pageVisible,
                enterChromeProgress = rememberDetailBarEnterProgress(
                    followShell = enterBarHandoff && bridgeBackToShell,
                    back = backCollapse,
                    inWindowMorph = morphBarOnBack && !bridgeBackToShell,
                    intro = enterIntro,
                ),
                backMorphProgress = if (morphBarOnBack) {
                    { backCollapse.progress }
                } else {
                    { 0f }
                },
                navSection = navSection,
                backExitProgress = if (barExitsOnBack) {
                    { detailBarExitProgress(enterIntro, backCollapse) }
                } else {
                    { 0f }
                },
                promotable = listOf(
                    BarExtraAction(
                        icon = YoinSymbols.Share,
                        label = stringResource(R.string.detail_artist_share),
                        onClick = onShare,
                    ),
                ),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) { dismissMenu ->
                DetailMenuRows(menu, dismissMenu)
            }
        }
    }
}

/** Cover-seeded colours the page body needs, resolved once at the screen level. */
private class ArtistPageColors(
    val arms: List<Color>,
    val accent: Color,
)

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

/**
 * The Album / Playlist compact header in artist terms: back, name, and the
 * year span, and the follow star on the right (where Apple Music
 * keeps "favorite artist"). Hidden for providers without follow/favorite.
 */
@Composable
private fun ArtistTopHeader(
    artistName: String,
    activeSpan: String?,
    titleColor: Color,
    showFollow: Boolean,
    following: Boolean,
    followLabels: Pair<String, String>,
    onBackClick: () -> Unit,
    onToggleFollow: () -> Unit,
    modifier: Modifier = Modifier,
    // False from Medium up: the wide hero carries name and Follow (§5).
    showIdentity: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DetailBackButton(onClick = onBackClick)
        // Air between the button's touch halo and the title cluster (Album parity).
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (showIdentity) {
                Text(
                    text = artistName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (activeSpan != null) {
                    DetailMetaLine(
                        groups = listOf(MetaGroup.Plain(activeSpan)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (showFollow) {
            ArtistFollowStar(
                following = following,
                labels = followLabels,
                activeTint = titleColor,
                onToggle = onToggleFollow,
            )
        }
    }
}

/**
 * Follow toggle: an outlined star that fills in the page accent, with the
 * same short pop as the track hearts when it changes.
 */
@Composable
private fun ArtistFollowStar(
    following: Boolean,
    labels: Pair<String, String>,
    activeTint: Color,
    onToggle: () -> Unit,
    // The wide hero names the action beside the star (Follow / Favorite).
    showLabel: Boolean = false,
) {
    val haptics = rememberYoinHaptics()
    val tint by animateColorAsState(
        targetValue = if (following) activeTint else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = YoinMotion.effectsSpring(),
        label = "artistFollowTint",
    )
    var tapPulse by remember { mutableIntStateOf(0) }
    val bounce = remember { Animatable(1f) }
    val bounceSpec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Standard)
    LaunchedEffect(tapPulse) {
        if (tapPulse == 0) return@LaunchedEffect
        bounce.animateTo(if (following) 1.25f else 1.15f, tween(durationMillis = 90))
        bounce.animateTo(1f, bounceSpec)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showLabel) {
            AlbumSectionLabel(text = if (following) labels.second else labels.first)
            Spacer(modifier = Modifier.width(4.dp))
        }
        IconButton(
            onClick = {
                tapPulse++
                if (following) haptics.performTick() else haptics.performConfirm()
                onToggle()
            },
        ) {
            Icon(
                imageVector = if (following) YoinSymbols.StarFilled else YoinSymbols.Star,
                contentDescription = if (following) labels.second else labels.first,
                tint = tint,
                modifier = Modifier.graphicsLayer {
                    scaleX = bounce.value
                    scaleY = bounce.value
                },
            )
        }
    }
}

/**
 * (idle, active) labels for the follow star, by provider. Spotify has a real
 * "Follow"; Subsonic only stars, so it reads "Favorite".
 */
@Composable
private fun artistFollowLabels(provider: String?): Pair<String, String> =
    if (provider == MediaId.PROVIDER_SPOTIFY) {
        stringResource(R.string.detail_artist_follow) to stringResource(R.string.detail_artist_following)
    } else {
        stringResource(R.string.detail_artist_favorite) to stringResource(R.string.detail_artist_favorited)
    }

/** "2016 – 2025" from the releases' years; one year alone; null when none carry a year. */
private fun artistActiveSpan(albums: List<ArtistAlbum>): String? {
    val years = albums.mapNotNull { it.year }.filter { it > 0 }
    val first = years.minOrNull() ?: return null
    val last = years.max()
    return if (first == last) "$first" else "$first – $last"
}

// ---------------------------------------------------------------------------
// Body
// ---------------------------------------------------------------------------

@Composable
private fun ArtistBody(
    content: ArtistDetailUiState.Content,
    heroUrl: String?,
    colors: ArtistPageColors,
    onAlbumClick: (String) -> Unit,
    onMostPlayedClick: (Int) -> Unit,
    follow: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val maxW = maxWidth
        // PANE-relative (an embedded activity sees its own container). Height
        // first: a landscape handset turns the hero sideways (§5).
        val windowInfo = LocalYoinWindowInfo.current
        val landscape = windowInfo.isCompactHeight
        val layoutMode = windowInfo.layoutMode
        val desktop = !landscape && layoutMode == LayoutMode.Wide
        val wide = !landscape && layoutMode != LayoutMode.Compact && layoutMode != LayoutMode.Tabletop
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val scrollState = rememberScrollState()
        // The pinwheel turns with the page as it scrolls (read at draw time —
        // no recomposition per scroll frame); still under reduced motion.
        val turnWithScroll = LocalMotionProfile.current != MotionProfile.AdaptiveReduced
        val pinwheelTurn: () -> Float = {
            ArtistPinwheelRestDegrees +
                if (turnWithScroll) scrollState.value * ArtistPinwheelDegreesPerPx else 0f
        }
        val mostPlayedVisible = content.listening?.mostPlayed?.isNotEmpty() == true

        Column(
            modifier = Modifier
                .fillMaxSize()
                // Scrolls under the fixed header (tide line at the column's
                // top edge) and on under the bar (the bottom field).
                .seamDissolveViewport(
                    background = expressivePageSeamBackground(),
                    remainingPx = { scrollState.seamRemainingPx() },
                ) { scrollState.value.toFloat() }
                .verticalScroll(scrollState)
                // Landscape keeps the group in the cutout band: only the nav
                // bar needs clearing at the bottom.
                .padding(bottom = (if (landscape) 24.dp else 120.dp) + navBottom),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                landscape -> ArtistLandscapeHero(
                    content = content,
                    heroUrl = heroUrl,
                    colors = colors,
                    pinwheelTurn = pinwheelTurn,
                    onMostPlayedClick = onMostPlayedClick,
                )
                wide -> ArtistWideHero(
                    content = content,
                    heroUrl = heroUrl,
                    colors = colors,
                    pinwheelTurn = pinwheelTurn,
                    desktop = desktop,
                    follow = follow,
                    modifier = if (desktop) Modifier else Modifier.yoinPageContentWidth(),
                )
                else -> {
                    // Portrait on the pinwheel's hub; the meta row below takes the
                    // Album hero's cover-block width so the two pages line up.
                    val portraitSize = minOf(maxW * 0.52f, 216.dp)
                    ArtistPinwheelHero(
                        heroUrl = heroUrl,
                        artistName = content.artistName,
                        colors = colors,
                        portraitSize = portraitSize,
                        pinwheelTurn = pinwheelTurn,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ArtistHeroMeta(
                        content = content,
                        heroUrl = heroUrl,
                        modifier = Modifier.width(minOf(maxW * 0.74f, 300.dp)),
                    )
                }
            }

            Spacer(modifier = Modifier.height(if (landscape) 20.dp else 32.dp))

            if (desktop) {
                // Wide full window (ArtistDesktop): Most Played (480) beside
                // the discography, under the hero.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(48.dp),
                ) {
                    if (mostPlayedVisible) {
                        ArtistMostPlayed(
                            listening = content.listening,
                            onClick = onMostPlayedClick,
                            modifier = Modifier.width(ArtistDesktopMostPlayedWidth),
                        )
                    }
                    ArtistDiscography(
                        albums = content.albums,
                        accent = colors.accent,
                        onAlbumClick = onAlbumClick,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                val sections = Modifier
                    .then(if (landscape) Modifier.fillMaxWidth() else Modifier.yoinPageContentWidth())
                    .padding(horizontal = 16.dp)
                // Landscape shows Most Played beside the portrait already.
                if (!landscape) {
                    // Arrives with the personal layer; grows in instead of popping.
                    AnimatedVisibility(
                        visible = mostPlayedVisible,
                        enter = YoinMotion.fadeIn(role = YoinMotionRole.Expressive) +
                            expandVertically(animationSpec = YoinMotion.spatialSpring()),
                        exit = YoinMotion.fadeOut(role = YoinMotionRole.Expressive) +
                            shrinkVertically(animationSpec = YoinMotion.spatialSpring()),
                    ) {
                        ArtistMostPlayed(
                            listening = content.listening,
                            onClick = onMostPlayedClick,
                            modifier = sections.padding(bottom = 32.dp),
                        )
                    }
                }

                ArtistDiscography(
                    albums = content.albums,
                    accent = colors.accent,
                    onAlbumClick = onAlbumClick,
                    modifier = sections,
                )
            }
        }
    }
}

private val ArtistDesktopMostPlayedWidth = 480.dp

/**
 * Landscape handset (ArtistLandscape): the portrait turned sideways — the
 * 220dp circle on its pinwheel on the left (arms kept whole and clear of the
 * capsule band), Last Play | Avg. and Most Played on the right. The name is
 * in the header row only; the discography follows below with the page.
 */
@Composable
private fun ArtistLandscapeHero(
    content: ArtistDetailUiState.Content,
    heroUrl: String?,
    colors: ArtistPageColors,
    pinwheelTurn: () -> Float,
    onMostPlayedClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // The arms reach ~137dp from the hub: this keeps them whole — clear
            // of the capsule band on the left and of the scroll edge on top.
            .padding(start = 40.dp, end = 24.dp, top = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        Box(modifier = Modifier.size(ArtistLandscapePortraitSize)) {
            ArtistPinwheelBackground(
                colors = colors.arms,
                lineColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                portraitSize = ArtistLandscapePortraitSize,
                rotationDegrees = pinwheelTurn,
                modifier = Modifier.fillMaxSize(),
                markScale = ArtistPinwheelLandscapeScale,
            )
            ArtistPortrait(heroUrl = heroUrl, artistName = content.artistName, modifier = Modifier.fillMaxSize())
        }
        Column(modifier = Modifier.weight(1f)) {
            ArtistHeroMeta(
                content = content,
                heroUrl = heroUrl,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .fillMaxWidth(),
            )
            if (content.listening?.mostPlayed?.isNotEmpty() == true) {
                Spacer(modifier = Modifier.height(20.dp))
                ArtistMostPlayed(
                    listening = content.listening,
                    onClick = onMostPlayedClick,
                )
            }
        }
    }
}

private val ArtistLandscapePortraitSize = 220.dp

// Arms reach ≈0.46 × portrait × scale from the hub: 1.35 keeps them whole
// inside the 40dp gutter, never under the capsules.
private const val ArtistPinwheelLandscapeScale = 1.35f

// ---------------------------------------------------------------------------
// Hero
// ---------------------------------------------------------------------------

@Composable
private fun ArtistPinwheelHero(
    heroUrl: String?,
    artistName: String,
    colors: ArtistPageColors,
    portraitSize: Dp,
    pinwheelTurn: () -> Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(portraitSize + ArtistPinwheelBandExtra)
            // Full width, so the bleeding arms stay inside the print.
            .seamDissolve(),
        contentAlignment = Alignment.Center,
    ) {
        // Width = the page (no side padding): the arms bleed off the screen
        // edges only; the band is tall enough that they clear the meta row
        // below at the resting turn.
        ArtistPinwheelBackground(
            colors = colors.arms,
            lineColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
            portraitSize = portraitSize,
            rotationDegrees = pinwheelTurn,
            modifier = Modifier.fillMaxSize(),
        )
        ArtistPortrait(heroUrl = heroUrl, artistName = artistName, modifier = Modifier.size(portraitSize))
    }
}

@Composable
private fun ArtistPortrait(heroUrl: String?, artistName: String, modifier: Modifier = Modifier) {
    ExpressiveMediaArtwork(
        model = heroUrl,
        contentDescription = artistName,
        modifier = modifier.seamDissolve(),
        shape = CircleShape,
        fallbackIcon = YoinSymbols.Artist,
        border = null,
        shadowElevation = 0.dp,
        tonalElevation = 3.dp,
        requestSizePx = 720,
    )
}

/**
 * The Album hero's meta row, in artist terms — Last Play (your latest play of
 * anything by them, day over time) | Avg. (the mean of your album
 * ratings for their releases, "Based on X/N").
 */
@Composable
private fun ArtistHeroMeta(
    content: ArtistDetailUiState.Content,
    heroUrl: String?,
    modifier: Modifier = Modifier,
) {
    val listening = content.listening
    Row(
        modifier = modifier.seamFade(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AlbumSectionLabel(text = stringResource(R.string.detail_artist_last_play))
            val resources = LocalContext.current.resources
            val labels = listening?.lastPlayedAt?.let { albumLastPlayLabels(it, resources) }
            Text(
                text = labels?.first ?: "—",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                // null listening = still loading: keep the line quiet instead of
                // flashing "Never" before the history read lands.
                text = when {
                    labels != null -> labels.second
                    listening == null -> " "
                    else -> stringResource(R.string.detail_artist_never)
                },
                style = MaterialTheme.typography.bodyMedium.withTabularFigures(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The album page's groove emblem (owner W3, 2026-10-05), no caption:
        // one ring per release, the rated ones cut, the album ratings'
        // average in the middle. Album ratings are given on each album page;
        // here the emblem only reports them.
        val spec = remember(content.albums) { content.albumAverageEmblemSpec() }
        val description = artistAverageDescription(content)
        AlbumScoreEmblem(
            spec = spec,
            coverArtUrl = heroUrl,
            ratedCount = content.ratedAlbumCount,
            total = content.albums.size,
            enabled = false,
            onClick = {},
            modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        )
    }
}

/**
 * The artist's [AlbumScoreEmblem]: the album ratings' average (unrated when
 * none is rated), one ring per release, oldest outermost — a career read like
 * an album's tracks.
 */
internal fun ArtistDetailUiState.Content.albumAverageEmblemSpec(): AlbumEmblemSpec {
    val average = averageAlbumRating
    return AlbumEmblemSpec(
        score = if (average != null) AlbumScore(AlbumScoreKind.Average, average) else AlbumScore(AlbumScoreKind.None, 0f),
        trackRated = albums.asReversed().map { it.userRating != null },
    )
}

/** TalkBack for the artist's emblem: the emblem itself would say "Track average". */
@Composable
internal fun artistAverageDescription(content: ArtistDetailUiState.Content): String {
    val average = content.averageAlbumRating ?: return stringResource(R.string.detail_artist_no_albums_rated)
    val score = "%.1f".format(java.util.Locale.ROOT, average)
    return stringResource(
        R.string.detail_artist_album_average,
        score,
        content.ratedAlbumCount,
        content.albums.size,
    )
}

/**
 * >= Medium hero (ArtistFold / ArtistDesktop): the pinwheel portrait on the
 * left (180 Medium, 220 Wide), the identity column on the right — the ONLY
 * place the name appears here (the header keeps just back), the meta line,
 * Follow, and the Last Play | Avg. row. Play lives in the bottom bar, not
 * here.
 */
@Composable
private fun ArtistWideHero(
    content: ArtistDetailUiState.Content,
    heroUrl: String?,
    colors: ArtistPageColors,
    pinwheelTurn: () -> Float,
    modifier: Modifier = Modifier,
    desktop: Boolean = false,
    follow: (@Composable () -> Unit)? = null,
) {
    val portraitSize = if (desktop) 220.dp else 180.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                // The boards' portrait insets (ArtistFold 24, ArtistDesktop
                // 64): with the matching mark scale below they keep the
                // pinwheel's left arm inside the pane — a split pane's edge
                // clips it otherwise.
                horizontal = if (desktop) 64.dp else 24.dp,
                vertical = ArtistWideHeroGap,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArtistWideHeroGap),
    ) {
        Box(modifier = Modifier.size(portraitSize)) {
            // Drawn around the portrait's own box (the Canvas does not clip),
            // so the hub stays under the circle wherever the row puts it.
            ArtistPinwheelBackground(
                colors = colors.arms,
                lineColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                portraitSize = portraitSize,
                rotationDegrees = pinwheelTurn,
                modifier = Modifier.fillMaxSize(),
                markScale = if (desktop) ArtistPinwheelWideScale else ArtistPinwheelMediumScale,
            )
            ArtistPortrait(heroUrl = heroUrl, artistName = content.artistName, modifier = Modifier.fillMaxSize())
        }
        val nameStyle = if (desktop) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineLarge
        Column(
            modifier = Modifier
                .weight(1f)
                .seamFade(fontSize = nameStyle.fontSize),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = content.artistName,
                style = nameStyle,
                color = colors.accent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ArtistWideKind(albumCount = content.albums.size, span = artistActiveSpan(content.albums))
            follow?.invoke()
            Spacer(modifier = Modifier.height(4.dp))
            ArtistHeroMeta(
                content = content,
                heroUrl = heroUrl,
                // Capped, then filled: the SpaceBetween row needs a real width
                // or Last Play and Avg. collapse onto each other.
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .fillMaxWidth(),
            )
        }
    }
}

// Height the band adds around the portrait so the pinwheel's lower arm clears
// the meta row at the resting turn.
private val ArtistPinwheelBandExtra = 150.dp

// Scroll-linked turn of the pinwheel, degrees per px scrolled (~18° per 100dp at 3×).
private const val ArtistPinwheelDegreesPerPx = 0.06f

// >= Medium: the pinwheel sits behind a side portrait with the name column to
// its right; the arms reach ≈0.78 × portrait from the hub, so the row's gap
// and vertical room keep them clear of that text.
private const val ArtistPinwheelWideScale = 1.7f

// Medium's 180dp portrait sits 24dp in: at this scale the arms reach ≈19dp
// past the portrait, so they stay inside the pane.
private const val ArtistPinwheelMediumScale = 1.5f
private val ArtistWideHeroGap = 56.dp

@Composable
private fun ArtistWideKind(albumCount: Int, span: String?) {
    DetailMetaLine(
        groups = buildList {
            add(
                MetaGroup.Stat(
                    albumCount.toString(),
                    pluralStringResource(R.plurals.detail_unit_release, albumCount),
                ),
            )
            if (span != null) add(MetaGroup.Plain(span))
        },
        style = MaterialTheme.typography.titleSmall.withTabularFigures(),
    )
}

@Composable
private fun artistReleaseCountLabel(count: Int): String =
    pluralStringResource(R.plurals.detail_artist_releases, count, count)

@Composable
private fun ArtistPlayedMeta(album: String, duration: String?) {
    val groups = buildList {
        if (album.isNotBlank()) add(MetaGroup.Plain(album))
        if (duration != null) add(MetaGroup.Plain(duration, muted = album.isNotBlank()))
    }
    if (groups.isEmpty()) return
    DetailMetaLine(
        groups = groups,
        style = MaterialTheme.typography.bodySmall.withTabularFigures(),
    )
}

// ---------------------------------------------------------------------------
// Most Played
// ---------------------------------------------------------------------------

/**
 * The user's own most-played songs by this artist (local play history, so it
 * exists for every provider — it stands in for the "Popular" list Spotify no
 * longer exposes). Rank · thumb · title/album · ×plays.
 */
@Composable
private fun ArtistMostPlayed(
    listening: ArtistListeningSummary?,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val songs = listening?.mostPlayed.orEmpty()
    Column(modifier = modifier.fillMaxWidth()) {
        ArtistSectionHeader(
            title = stringResource(R.string.detail_artist_most_played),
            trailing = listening?.playCount?.takeIf { it > 0 }?.let { count ->
                pluralStringResource(R.plurals.detail_artist_plays_total, count, count)
            },
        )
        Spacer(modifier = Modifier.height(6.dp))
        songs.forEachIndexed { index, song ->
            ArtistPlayedRow(rank = index + 1, song = song, onClick = { onClick(index) })
        }
    }
}

@Composable
private fun ArtistPlayedRow(
    rank: Int,
    song: ArtistPlayedSong,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(YoinContainerShapes.ListRow)
            .clickable {
                haptics.performClick()
                onClick()
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = rank.toString(),
            style = MaterialTheme.typography.labelLarge.withTabularFigures(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier
                .widthIn(min = 16.dp)
                .seamFade(),
        )
        ExpressiveMediaArtwork(
            model = song.coverArtUrl,
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .seamDissolve(),
            shape = YoinArtworkShapes.Thumb,
            fallbackIcon = YoinSymbols.MusicNote,
            border = null,
            shadowElevation = 0.dp,
            requestSizePx = 120,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .seamFade(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ArtistPlayedMeta(album = song.album, duration = song.durationSec?.let(::formatTrackDuration))
        }
        Text(
            text = pluralStringResource(R.plurals.detail_artist_plays, song.playCount, song.playCount),
            style = MaterialTheme.typography.labelLarge.withTabularFigures(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.seamFade(),
        )
    }
}

/** Underlined section label (the Album page's) with an optional count on the right. */
@Composable
private fun ArtistSectionHeader(title: String, trailing: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .seamFade(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumSectionLabel(text = title, modifier = Modifier.weight(1f))
        trailing?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Discography — a release timeline
// ---------------------------------------------------------------------------

private enum class DiscographyFilter {
    All,
    Albums,
    SinglesAndEps,
    Compilations,
    ;

    fun accepts(type: ReleaseType?): Boolean = when (this) {
        All -> true
        Albums -> type == ReleaseType.Album
        SinglesAndEps -> type == ReleaseType.Single || type == ReleaseType.EP
        Compilations -> type == ReleaseType.Compilation
    }
}

/**
 * Every release, newest first, as a timeline: a year column marks where a
 * year begins, each row carries the release kind and track count, the newest
 * one is tagged "Latest", and your own rating sits on the right where you gave
 * one. Kind filters appear only when the provider reports at least two kinds.
 * The first [DiscographyCollapsedCount] rows show; the rest expand in place.
 */
@Composable
private fun ArtistDiscography(
    albums: List<ArtistAlbum>,
    accent: Color,
    onAlbumClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filters = remember(albums) {
        val kinds = DiscographyFilter.entries.drop(1).filter { f -> albums.any { f.accepts(it.releaseType) } }
        if (kinds.size >= 2) listOf(DiscographyFilter.All) + kinds else emptyList()
    }
    var filter by rememberSaveable { mutableStateOf(DiscographyFilter.All) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val shown = albums.filter { filter.accepts(it.releaseType) }
    val newestId = albums.firstOrNull()?.id
    val filterLabels = mutableMapOf<DiscographyFilter, String>()
    for (item in filters) {
        filterLabels[item] = filterLabel(item)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        ArtistSectionHeader(
            title = stringResource(R.string.detail_artist_discography),
            trailing = artistReleaseCountLabel(albums.size),
        )
        if (filters.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            ExpressiveSegmentedTabs(
                items = filters,
                selectedItem = filter,
                label = { filterLabels.getValue(it) },
                onSelectedChange = { filter = it },
                modifier = Modifier.seamFade(),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (albums.isEmpty()) {
            Text(
                text = stringResource(R.string.detail_artist_no_releases),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
            return@Column
        }
        val head = shown.take(DiscographyCollapsedCount)
        val tail = shown.drop(DiscographyCollapsedCount)
        head.forEachIndexed { index, album ->
            ArtistReleaseRow(
                album = album,
                showYear = index == 0 || head[index - 1].year != album.year,
                isLatest = album.id == newestId,
                accent = accent,
                onClick = { onAlbumClick(album.id) },
            )
        }
        AnimatedVisibility(
            visible = showAll && tail.isNotEmpty(),
            enter = YoinMotion.fadeIn(role = YoinMotionRole.Expressive) +
                expandVertically(animationSpec = YoinMotion.spatialSpring()),
            exit = YoinMotion.fadeOut(role = YoinMotionRole.Expressive) +
                shrinkVertically(animationSpec = YoinMotion.spatialSpring()),
        ) {
            Column {
                tail.forEachIndexed { index, album ->
                    val previous = if (index == 0) head.lastOrNull() else tail[index - 1]
                    ArtistReleaseRow(
                        album = album,
                        showYear = previous?.year != album.year,
                        isLatest = false,
                        accent = accent,
                        onClick = { onAlbumClick(album.id) },
                    )
                }
            }
        }
        if (tail.isNotEmpty()) {
            TextButton(
                onClick = { showAll = !showAll },
                modifier = Modifier
                    .padding(start = ArtistReleaseYearColumn)
                    .seamFade(),
            ) {
                Text(
                    text = if (showAll) {
                        stringResource(R.string.detail_artist_show_fewer)
                    } else {
                        stringResource(R.string.detail_artist_show_all, shown.size)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                )
            }
        }
    }
}

// Rows visible before "Show all".
private const val DiscographyCollapsedCount = 8

// Width of the timeline's year column (fits "2025" in labelLarge tabular figures).
private val ArtistReleaseYearColumn = 48.dp

@Composable
private fun ArtistReleaseRow(
    album: ArtistAlbum,
    showYear: Boolean,
    isLatest: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(YoinContainerShapes.ListRow)
            .clickable {
                haptics.performClick()
                onClick()
            }
            .padding(end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(ArtistReleaseYearColumn)
                .padding(start = 8.dp)
                .seamFade(),
        ) {
            if (showYear) {
                Text(
                    text = album.year?.toString() ?: "—",
                    style = MaterialTheme.typography.labelLarge.withTabularFigures(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ExpressiveMediaArtwork(
            model = album.coverArtUrl,
            contentDescription = album.name,
            modifier = Modifier
                .size(56.dp)
                .seamDissolve(),
            shape = YoinArtworkShapes.Cover,
            fallbackIcon = YoinSymbols.Album,
            border = null,
            shadowElevation = 0.dp,
            requestSizePx = 168,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .seamFade(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = album.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ReleaseMetaLine(album = album, isLatest = isLatest)
        }
        album.userRating?.let { rating ->
            Text(
                text = formatAlbumScore(rating),
                style = MaterialTheme.typography.titleSmall.withTabularFigures(),
                color = accent,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .seamFade(),
            )
        }
    }
}

/** Latest, release kind, and song count. Empty when a release has none of them. */
@Composable
private fun ReleaseMetaLine(album: ArtistAlbum, isLatest: Boolean) {
    val kind = when (album.releaseType) {
        ReleaseType.Album -> stringResource(R.string.detail_artist_kind_album)
        ReleaseType.EP -> stringResource(R.string.detail_artist_kind_ep)
        ReleaseType.Single -> stringResource(R.string.detail_artist_kind_single)
        ReleaseType.Compilation -> stringResource(R.string.detail_artist_kind_compilation)
        null -> null
    }
    val groups = buildList {
        if (isLatest) add(MetaGroup.Kind(stringResource(R.string.detail_artist_latest), accent = true))
        if (kind != null) add(MetaGroup.Kind(kind))
        album.songCount?.let { count ->
            add(MetaGroup.Stat(count.toString(), pluralStringResource(R.plurals.detail_unit_song, count)))
        }
    }
    if (groups.isEmpty()) return
    DetailMetaLine(groups = groups, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun filterLabel(filter: DiscographyFilter): String = when (filter) {
    DiscographyFilter.All -> stringResource(R.string.detail_artist_filter_all)
    DiscographyFilter.Albums -> stringResource(R.string.detail_artist_filter_albums)
    DiscographyFilter.SinglesAndEps -> stringResource(R.string.detail_artist_filter_singles)
    DiscographyFilter.Compilations -> stringResource(R.string.detail_artist_filter_compilations)
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun ArtistDetailScreenContentPreview() {
    YoinTheme { ArtistDetailPreviewContent() }
}

@Preview(name = "Landscape handset", widthDp = 844, heightDp = 390, showBackground = true)
@Composable
private fun ArtistDetailLandscapePreview() {
    YoinTheme { ProvidePreviewWindow(widthDp = 844, heightDp = 390) { ArtistDetailPreviewContent() } }
}

@Preview(name = "Medium fold", widthDp = 690, heightDp = 840, showBackground = true)
@Composable
private fun ArtistDetailFoldPreview() {
    YoinTheme { ProvidePreviewWindow(widthDp = 690, heightDp = 840) { ArtistDetailPreviewContent() } }
}

@Preview(name = "Wide full window", widthDp = 1440, heightDp = 900, showBackground = true)
@Composable
private fun ArtistDetailDesktopPreview() {
    YoinTheme { ProvidePreviewWindow(widthDp = 1440, heightDp = 900) { ArtistDetailPreviewContent() } }
}

@Composable
private fun ArtistDetailPreviewContent() {
        ArtistDetailScreen(
            uiState = ArtistDetailUiState.Content(
                artistId = "artist-1",
                artistName = "Hannah Jadagu",
                heroCoverArtUrl = null,
                isStarred = true,
                albums = listOf(
                    ArtistAlbum("3", "Describe", null, 2025, 8, ReleaseType.Album, userRating = 8.5f),
                    ArtistAlbum("1", "Aperture", null, 2023, 11, ReleaseType.Album),
                    ArtistAlbum("2", "What Is Going On?", null, 2021, 6, ReleaseType.EP),
                ),
                listening = ArtistListeningSummary(
                    playCount = 42,
                    lastPlayedAt = System.currentTimeMillis() - 86_400_000L,
                    mostPlayed = listOf(
                        ArtistPlayedSong("s1", "Describe", "Describe", null, 231, 12),
                        ArtistPlayedSong("s2", "Warning Sign", "Aperture", null, 205, 7),
                    ),
                ),
            ),
            onBackClick = {},
            onAlbumClick = {},
            onRetry = {},
        )
}
