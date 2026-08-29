package com.gpo.yoin.ui.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.component.AlbumCard
import com.gpo.yoin.ui.component.DetailErrorState
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.PlaySplitButton
import com.gpo.yoin.ui.component.YoinDropdownMenu
import com.gpo.yoin.ui.component.YoinDropdownMenuItem
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.elasticPress
import com.gpo.yoin.ui.component.formatTrackDuration
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.navigation.YoinSection
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.rememberCoverColorScheme
import com.gpo.yoin.ui.theme.withTabularFigures

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
    onOpenInSpotify: () -> Unit = {},
    onTopTrackClick: (index: Int) -> Unit = {},
    onShare: () -> Unit = {},
    isPlaying: Boolean = false,
    playbackSignal: Float = 0f,
    onOpenNowPlaying: () -> Unit = {},
    nowPlayingOpen: Boolean = false,
    // True when this window sits directly over the shell: predictive back
    // scrubs the bar toward nav chrome (matching the reveal underneath).
    morphBarOnBack: Boolean = false,
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
    val pageAccent = rememberDetailPageAccent(
        content?.heroCoverArtUrl ?: content?.albums?.firstOrNull()?.coverArtUrl,
    )

    ProvideYoinMotionRole(role = YoinMotionRole.Expressive) {
        // In-window predictive back (AOSP cross-activity math): the whole
        // page — background included — collapses as one card over the LIVE
        // window beneath (the Activity turns translucent for the gesture);
        // the bar is a sibling on top and never transforms — it scrubs its
        // own morph off the same progress.
        val backCollapse = rememberDetailBackCollapse(onBack = onLeavePage)
        val enterIntro = rememberDetailEnterIntro(
            barHandoff = enterBarHandoff,
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
                    AnimatedContent(
                        targetState = uiState,
                        transitionSpec = {
                            YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                                YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                        },
                        // Class-keyed so Content→Content data updates (topTracks
                        // arriving, follow toggles) don't re-trigger the fade.
                        contentKey = { it::class },
                        label = "artistDetailState",
                        modifier = Modifier.fillMaxSize(),
                    ) { state ->
                        when (state) {
                            is ArtistDetailUiState.Loading ->
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .statusBarsPadding(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    YoinLoadingIndicator()
                                    DetailBackButton(
                                        onClick = onBackClick,
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(start = 4.dp, top = 12.dp),
                                    )
                                }

                            is ArtistDetailUiState.Error ->
                                DetailErrorState(
                                    message = state.message,
                                    onRetry = onRetry,
                                    onBack = onBackClick,
                                    backPadding = PaddingValues(start = 4.dp, top = 12.dp),
                                )

                            is ArtistDetailUiState.Content ->
                                ArtistDetailContent(
                                    content = state,
                                    onBackClick = onBackClick,
                                    onAlbumClick = onAlbumClick,
                                    onToggleFollow = onToggleFollow,
                                    onPlay = onPlay,
                                    onShuffle = onShuffle,
                                    onTopTrackClick = onTopTrackClick,
                                )
                        }
                    }
                }
            }

            run {
                // Persistent bottom bar — rendered in ALL states; Play/menu
                // act on Content and no-op during Loading/Error.
                val barHeroUrl = content?.heroCoverArtUrl
                    ?: content?.albums?.firstOrNull()?.coverArtUrl
                val barScheme = rememberCoverColorScheme(barHeroUrl)
                    ?: MaterialTheme.colorScheme
                val barPlayContainer by animateColorAsState(
                    barScheme.primary,
                    YoinMotion.effectsSpring(),
                    label = "artistBarPlayContainer",
                )
                val showOpenInSpotify = content != null &&
                    MediaId.parseOrNull(content.artistId)?.provider == MediaId.PROVIDER_SPOTIFY
            DetailBottomBar(
                    playContainer = barPlayContainer,
                    playContent = barScheme.onPrimary,
                    onPlay = onPlay,
                    onShuffle = onShuffle,
                    onOpenNowPlaying = onOpenNowPlaying,
                    miniPlayer = miniPlayerState,
                    playbackProgress = playbackProgress,
                nowPlayingOpen = nowPlayingOpen,
                interactionsEnabled = enterIntro.pageVisible,
                    backMorphProgress = if (morphBarOnBack) {
                        { backCollapse.progress }
                    } else {
                        { 0f }
                    },
                    navSection = navSection,
                    backExitProgress = if (barExitsOnBack) {
                        { backCollapse.progress }
                    } else {
                        { 0f }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) { dismissMenu ->
                    if (showOpenInSpotify) {
                        // Saved/liked tracks live in Spotify now — the ▾ menu
                        // deep-links out (Spotify only; no in-app mirror).
                        YoinDropdownMenuItem(
                            text = "Open in Spotify",
                            onClick = {
                                dismissMenu()
                                onOpenInSpotify()
                            },
                            leadingIcon = {
                                Icon(Icons.Filled.Launch, contentDescription = null, modifier = Modifier.size(22.dp))
                            },
                            textStyle = MaterialTheme.typography.titleMedium,
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                        )
                    }
                    YoinDropdownMenuItem(
                        text = "Share",
                        onClick = {
                            dismissMenu()
                            onShare()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.IosShare,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        textStyle = MaterialTheme.typography.titleMedium,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ArtistDetailContent(
    content: ArtistDetailUiState.Content,
    onBackClick: () -> Unit,
    onAlbumClick: (albumId: String) -> Unit,
    onToggleFollow: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onTopTrackClick: (index: Int) -> Unit,
) {
    // Portrait → first album cover fallback (older Subsonic has no artist.jpg).
    val heroUrl = content.heroCoverArtUrl ?: content.albums.firstOrNull()?.coverArtUrl

    // Material roles seeded from the artist portrait (same path as the album page).
    val coverScheme = rememberCoverColorScheme(heroUrl)
    val s = coverScheme ?: MaterialTheme.colorScheme
    val primaryBlock by animateColorAsState(s.primary, YoinMotion.effectsSpring(), label = "artistPrimaryBlock")
    val secondaryBlock by animateColorAsState(s.secondary, YoinMotion.effectsSpring(), label = "artistSecondaryBlock")
    val titleColor by animateColorAsState(s.primary, YoinMotion.effectsSpring(), label = "artistTitleColor")
    val accentText = s.secondary

    // M3 medium-flexible top bar: large on arrival, collapses to a small bar as
    // the page scrolls and stays small until scrolled back to the top.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                MediumFlexibleTopAppBar(
                    title = {
                        Text(
                            text = content.artistName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = titleColor,
                        )
                    },
                    subtitle = {
                        Text(
                            text = buildString {
                                append("Artist")
                                content.albums.size.takeIf { it > 0 }?.let {
                                    append(if (it == 1) "  ·  1 album" else "  ·  $it albums")
                                }
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = accentText,
                        )
                    },
                    navigationIcon = {
                        // end padding widens the nav slot so the COLLAPSED
                        // title clears the button halo; the expanded title is
                        // placed from the bar edge and stays at 16dp.
                        DetailBackButton(
                            onClick = onBackClick,
                            modifier = Modifier.padding(end = 14.dp),
                        )
                    },
                    // Default 136dp packs the title right under the back
                    // button; extra height = breathing room between them.
                    expandedHeight = 156.dp,
                    // Transparent both ends so the bar blends with the gradient
                    // page background (no surface band, no collapse colour flash).
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                        titleContentColor = titleColor,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { innerPadding ->
            // Single flat page — no pager. (Bio / About is a future destination,
            // not an empty placeholder behind a promising dot indicator.)
            ArtistOverviewPage(
                content = content,
                heroUrl = heroUrl,
                primaryBlock = primaryBlock,
                secondaryBlock = secondaryBlock,
                accent = primaryBlock,
                accentOn = s.onPrimary,
                onAlbumClick = onAlbumClick,
                onToggleFollow = onToggleFollow,
                onPlay = onPlay,
                onShuffle = onShuffle,
                onTopTrackClick = onTopTrackClick,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
}

@Composable
private fun ArtistOverviewPage(
    content: ArtistDetailUiState.Content,
    heroUrl: String?,
    primaryBlock: Color,
    secondaryBlock: Color,
    accent: Color,
    accentOn: Color,
    onAlbumClick: (String) -> Unit,
    onToggleFollow: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onTopTrackClick: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val maxW = maxWidth
        // PANE-relative (an embedded activity sees its own container), so a
        // shell↔detail split on an 840dp window still renders the Compact
        // hero/carousel in its ~460dp pane — exactly as intended.
        val layoutMode = LocalYoinWindowInfo.current.layoutMode
        // Smaller portrait so the Albums / Follow stats can flank it and use the
        // space that's otherwise empty beside a circle.
        val portraitSize = minOf(maxW * 0.44f, 188.dp)
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // Per-section inset (NOT on the scroll Column) so the carousel can go
        // edge-to-edge while text content stays inset.
        val sidePad = Modifier.padding(horizontal = 16.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 110.dp + navBottom),
        ) {
            val (followIdle, followActive) = artistFollowLabels(content.artistId)
            val followLabel = if (content.isStarred) followActive else followIdle

            if (layoutMode != LayoutMode.Compact) {
                // >= Medium hero — the centred circle can't carry a wide pane
                // alone: portrait (200dp) left; name, meta, Follow ★ and Play
                // in a right column. Same arrow background, same blocks.
                ArtistWideHero(
                    content = content,
                    heroUrl = heroUrl,
                    primaryBlock = primaryBlock,
                    secondaryBlock = secondaryBlock,
                    accent = accent,
                    accentOn = accentOn,
                    followLabel = followLabel,
                    onToggleFollow = onToggleFollow,
                    onPlay = onPlay,
                    onShuffle = onShuffle,
                )
            } else {
            // Hero — Albums (left) · portrait (center) · Follow (right).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                AlbumArrowBackground(
                    primaryBlock = primaryBlock,
                    secondaryBlock = secondaryBlock,
                    lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                    markHeight = portraitSize + 48.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(portraitSize + 64.dp)
                        .align(Alignment.TopCenter),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AlbumSectionLabel(text = "Albums")
                    // albums.size, NOT the provider albumCount (Spotify inflates it).
                    Text(
                        text = content.albums.size.toString(),
                        style = MaterialTheme.typography.headlineSmall
                            .copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                ExpressiveMediaArtwork(
                    model = heroUrl,
                    contentDescription = content.artistName,
                    modifier = Modifier.size(portraitSize),
                    shape = CircleShape,
                    fallbackIcon = Icons.Filled.Person,
                    border = null,
                    shadowElevation = 0.dp,
                    tonalElevation = 3.dp,
                    requestSizePx = 600,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Per-provider wording: Spotify "Follow"; Subsonic "Favorite".
                    AlbumSectionLabel(text = followLabel)
                    ArtistFollowBun(
                        following = content.isStarred,
                        label = followLabel,
                        accent = accent,
                        accentOn = accentOn,
                        onToggle = onToggleFollow,
                    )
                }
            }
            }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Popular — top tracks (Spotify only; hidden if empty). The marquee.
            // Arrives on a second emission after the hero paints, so the
            // section grows in and pushes Discography down instead of popping.
            AnimatedVisibility(
                visible = content.topTracks.isNotEmpty(),
                enter = YoinMotion.fadeIn(role = YoinMotionRole.Expressive) +
                    expandVertically(animationSpec = YoinMotion.spatialSpring()),
                exit = YoinMotion.fadeOut(role = YoinMotionRole.Expressive) +
                    shrinkVertically(animationSpec = YoinMotion.spatialSpring()),
            ) {
                Column {
                    Column(modifier = sidePad) {
                        AlbumSectionLabel(text = "Popular")
                        Spacer(modifier = Modifier.height(8.dp))
                        content.topTracks.forEachIndexed { index, track ->
                            ArtistTopTrackRow(
                                rank = index + 1,
                                track = track,
                                pageArtistName = content.artistName,
                                onClick = { onTopTrackClick(index) },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(22.dp))
                }
            }

            AlbumSectionLabel(text = "Discography", modifier = sidePad)
            Spacer(modifier = Modifier.height(12.dp))

            if (content.albums.isEmpty()) {
                Text(
                    text = "No albums",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            } else if (layoutMode != LayoutMode.Compact) {
                // >= Medium: the one-row carousel wastes a tall pane — the same
                // albums flow as rows of the shared AlbumCard instead.
                ArtistDiscographyGrid(
                    albums = content.albums,
                    onAlbumClick = onAlbumClick,
                    modifier = sidePad,
                )
            } else {
                // Official M3 carousel, edge-to-edge: items scroll freely off both
                // screen edges, no white side gutters.
                ArtistDiscographyCarousel(
                    albums = content.albums,
                    onAlbumClick = onAlbumClick,
                )
            }
        }
    }
}

/**
 * Hero at >= Medium: the Compact triptych (stat · circle · star) leaves a wide
 * pane mostly empty, so the portrait moves left and an identity column — name,
 * meta line, Follow ★ Bun and the Play split — fills the freed width. Same
 * arrow background and the same building blocks as Compact, only rearranged.
 */
@Composable
private fun ArtistWideHero(
    content: ArtistDetailUiState.Content,
    heroUrl: String?,
    primaryBlock: Color,
    secondaryBlock: Color,
    accent: Color,
    accentOn: Color,
    followLabel: String,
    onToggleFollow: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val portraitSize = 200.dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        AlbumArrowBackground(
            primaryBlock = primaryBlock,
            secondaryBlock = secondaryBlock,
            lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
            markHeight = portraitSize + 48.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(portraitSize + 64.dp)
                .align(Alignment.TopCenter),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            ExpressiveMediaArtwork(
                model = heroUrl,
                contentDescription = content.artistName,
                modifier = Modifier.size(portraitSize),
                shape = CircleShape,
                fallbackIcon = Icons.Filled.Person,
                border = null,
                shadowElevation = 0.dp,
                tonalElevation = 3.dp,
                requestSizePx = 600,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = content.artistName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Same meta voice as the top-bar subtitle, in the page's mono.
                // albums.size, NOT the provider albumCount (Spotify inflates it).
                Text(
                    text = buildString {
                        append("Artist")
                        content.albums.size.takeIf { it > 0 }?.let {
                            append(if (it == 1) "  ·  1 album" else "  ·  $it albums")
                        }
                    },
                    style = MaterialTheme.typography.titleSmall
                        .copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ArtistFollowBun(
                        following = content.isStarred,
                        label = followLabel,
                        accent = accent,
                        accentOn = accentOn,
                        onToggle = onToggleFollow,
                    )
                    PlaySplitButton(
                        playContainer = accent,
                        playContent = accentOn,
                        onPlay = onPlay,
                        onShuffle = onShuffle,
                    )
                }
            }
        }
    }
}

/**
 * Discography at >= Medium: rows of the shared [AlbumCard] (its default 156dp
 * fixed width — 3-up at typical split-pane widths, 4-up from ~692dp of pane
 * width with these 12dp gutters). A plain [FlowRow] inside the page's single
 * verticalScroll Column — NOT a LazyVerticalGrid, which would need its own
 * bounded height inside this already-scrollable parent.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtistDiscographyGrid(
    albums: List<ArtistAlbum>,
    onAlbumClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 12dp gutters / 16dp row gap — the Library albums grid rhythm.
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        albums.forEach { album ->
            AlbumCard(
                coverArtUrl = album.coverArtUrl,
                title = album.name,
                subtitle = album.songCount?.let { if (it == 1) "1 song" else "$it songs" },
                metaLabel = album.year?.toString(),
                onClick = { onAlbumClick(album.id) },
                // Palette extraction across a whole grid drops frames — same
                // @palette-perf story as the Library albums grid.
                extractBackdropColors = false,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtistDiscographyCarousel(
    albums: List<ArtistAlbum>,
    onAlbumClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val carouselState = rememberCarouselState { albums.size }
    HorizontalMultiBrowseCarousel(
        state = carouselState,
        preferredItemWidth = 200.dp,
        itemSpacing = 8.dp,
        // A small lead-in so the first item doesn't jam the screen edge, while
        // items still mask/peek freely off both sides (not boxed-in white gutters).
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp),
    ) { index ->
        val album = albums[index]
        // Same press language as AlbumCard: one interaction source shared by
        // the clickable and the artwork so its built-in elasticPress engages.
        val interactionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .height(220.dp)
                .maskClip(YoinArtworkShapes.HeroAnimated)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = { onAlbumClick(album.id) },
                ),
        ) {
            ExpressiveMediaArtwork(
                model = album.coverArtUrl,
                contentDescription = album.name,
                modifier = Modifier.fillMaxSize(),
                shape = RectangleShape,
                fallbackIcon = Icons.Filled.Album,
                interactionSource = interactionSource,
                border = null,
                shadowElevation = 0.dp,
                requestSizePx = 480,
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f)),
                        ),
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        album.year?.let { append(it) }
                        album.songCount?.let {
                            if (isNotEmpty()) append("  ·  ")
                            append(if (it == 1) "1 song" else "$it songs")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * (idle, active) labels for the star/follow toggle, by provider. Spotify uses
 * the real "Follow" concept (`/me/following`); Subsonic has no follow, only
 * starring, so it reads "Favorite".
 */
private fun artistFollowLabels(artistId: String): Pair<String, String> =
    when (MediaId.parseOrNull(artistId)?.provider) {
        MediaId.PROVIDER_SPOTIFY -> "Follow" to "Following"
        else -> "Favorite" to "Favorited"
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ArtistFollowBun(
    following: Boolean,
    label: String,
    accent: Color,
    accentOn: Color,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    val interaction = remember { MutableInteractionSource() }
    val container by animateColorAsState(
        targetValue = if (following) accent else Color.Transparent,
        animationSpec = YoinMotion.effectsSpring(),
        label = "followBunContainer",
    )
    Surface(
        onClick = {
            if (following) haptics.performTick() else haptics.performConfirm()
            onToggle()
        },
        modifier = modifier
            .size(width = 60.dp, height = 60.dp)
            .elasticPress(interaction),
        interactionSource = interaction,
        shape = MaterialShapes.Bun.toShape(),
        color = container,
        border = if (!following) {
            BorderStroke(1.5.dp, accent.copy(alpha = 0.55f))
        } else {
            null
        },
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (following) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = label,
                tint = if (following) accentOn else accent,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** One row in the "Popular" list: rank · thumbnail · title/artist · duration. */
@Composable
private fun ArtistTopTrackRow(
    rank: Int,
    track: ArtistTopTrack,
    pageArtistName: String,
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
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = rank.toString(),
            style = MaterialTheme.typography.labelMedium.withTabularFigures(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 18.dp),
        )
        ExpressiveMediaArtwork(
            model = track.coverArtUrl,
            contentDescription = null,
            modifier = Modifier.size(46.dp),
            shape = YoinArtworkShapes.Thumb,
            fallbackIcon = Icons.Filled.MusicNote,
            border = null,
            shadowElevation = 0.dp,
            requestSizePx = 120,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The mapper keeps only the primary artist, so on this page the
            // subtitle is almost always the page's own name — show it only
            // for genuine collaborator credits.
            if (track.artist.isNotBlank() && track.artist != pageArtistName) {
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        track.durationSec?.let { secs ->
            Text(
                text = formatTrackDuration(secs),
                style = MaterialTheme.typography.labelLarge.withTabularFigures(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun ArtistDetailScreenContentPreview() {
    YoinTheme {
        ArtistDetailScreen(
            uiState = ArtistDetailUiState.Content(
                artistId = "artist-1",
                artistName = "Hannah Jadagu",
                albumCount = 3,
                heroCoverArtUrl = null,
                isStarred = true,
                albums = listOf(
                    ArtistAlbum("1", "Aperture", null, 2023, 11),
                    ArtistAlbum("2", "What Is Going On?", null, 2021, 6),
                    ArtistAlbum("3", "Describe", null, 2025, 8),
                ),
            ),
            onBackClick = {},
            onAlbumClick = {},
            onRetry = {},
        )
    }
}
