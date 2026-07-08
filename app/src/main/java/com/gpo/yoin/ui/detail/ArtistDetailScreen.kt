package com.gpo.yoin.ui.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.component.AlbumCard
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.YoinDropdownMenu
import com.gpo.yoin.ui.component.YoinDropdownMenuItem
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.elasticPress
import com.gpo.yoin.ui.component.formatTrackDuration
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.rememberCoverColorScheme
import com.gpo.yoin.ui.theme.withTabularFigures

@Composable
fun ArtistDetailScreen(
    uiState: ArtistDetailUiState,
    onBackClick: () -> Unit,
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
    modifier: Modifier = Modifier,
) {
    val content = uiState as? ArtistDetailUiState.Content
    val pageAccent = rememberDetailPageAccent(
        content?.heroCoverArtUrl ?: content?.albums?.firstOrNull()?.coverArtUrl,
    )

    ProvideYoinMotionRole(role = YoinMotionRole.Expressive) {
        ExpressivePageBackground(
            accentColor = pageAccent,
            isPlaying = isPlaying,
            playbackSignal = playbackSignal,
            modifier = modifier,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
            when (uiState) {
                is ArtistDetailUiState.Loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        YoinLoadingIndicator()
                    }

                is ArtistDetailUiState.Error ->
                    ArtistErrorState(uiState.message, onRetry, onBackClick)

                is ArtistDetailUiState.Content ->
                    ArtistDetailContent(
                        content = uiState,
                        onBackClick = onBackClick,
                        onAlbumClick = onAlbumClick,
                        onToggleFollow = onToggleFollow,
                        onPlay = onPlay,
                        onShuffle = onShuffle,
                        onOpenInSpotify = onOpenInSpotify,
                        onTopTrackClick = onTopTrackClick,
                        onShare = onShare,
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
    onOpenInSpotify: () -> Unit,
    onTopTrackClick: (index: Int) -> Unit,
    onShare: () -> Unit,
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
    val toolbarTint = rememberDetailToolbarTint(s)

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
                                if (content.albums.isNotEmpty()) append("  ·  ${content.albums.size} albums")
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = accentText,
                        )
                    },
                    navigationIcon = {
                        DetailBackButton(onClick = onBackClick)
                    },
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
                onTopTrackClick = onTopTrackClick,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }

        ArtistBottomToolbar(
            playContainer = primaryBlock,
            playContent = s.onPrimary,
            toolbarContainer = toolbarTint,
            onPlay = onPlay,
            onShuffle = onShuffle,
            // Saved/liked tracks live in Spotify now — the ▾ menu deep-links out
            // (Spotify only; no in-app saved-tracks mirror).
            showOpenInSpotify = MediaId.parseOrNull(content.artistId)?.provider == MediaId.PROVIDER_SPOTIFY,
            onOpenInSpotify = onOpenInSpotify,
            onShare = onShare,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        )
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
    onTopTrackClick: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val maxW = maxWidth
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

            Spacer(modifier = Modifier.height(24.dp))

            // Popular — top tracks (Spotify only; hidden if empty). The marquee.
            if (content.topTracks.isNotEmpty()) {
                Column(modifier = sidePad) {
                    AlbumSectionLabel(text = "Popular")
                    Spacer(modifier = Modifier.height(8.dp))
                    content.topTracks.forEachIndexed { index, track ->
                        ArtistTopTrackRow(
                            rank = index + 1,
                            track = track,
                            onClick = { onTopTrackClick(index) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(22.dp))
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
        Box(
            modifier = Modifier
                .height(220.dp)
                .maskClip(YoinShapeTokens.LargeIncreased)
                .clickable { onAlbumClick(album.id) },
        ) {
            ExpressiveMediaArtwork(
                model = album.coverArtUrl,
                contentDescription = album.name,
                modifier = Modifier.fillMaxSize(),
                shape = RectangleShape,
                fallbackIcon = Icons.Filled.Album,
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
                            append("$it songs")
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(YoinShapeTokens.Large)
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
            shape = YoinShapeTokens.Small,
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
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ArtistBottomToolbar(
    playContainer: Color,
    playContent: Color,
    toolbarContainer: Color,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    showOpenInSpotify: Boolean,
    onOpenInSpotify: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DetailFloatingToolbar(
        toolbarContainer = toolbarContainer,
        modifier = modifier,
    ) {
        IconButton(onClick = onShare, modifier = Modifier.size(52.dp)) {
            Icon(
                imageVector = Icons.Rounded.IosShare,
                contentDescription = "Share",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        DetailPlaySplitButton(
            playContainer = playContainer,
            playContent = playContent,
            onPlay = onPlay,
            onShuffle = onShuffle,
        ) { dismissMenu ->
            if (showOpenInSpotify) {
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
        }
    }
}

@Composable
private fun ArtistErrorState(
    message: String,
    onRetry: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        DetailBackButton(
            onClick = onBackClick,
            modifier = Modifier.padding(4.dp),
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRetry) { Text("Retry") }
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
