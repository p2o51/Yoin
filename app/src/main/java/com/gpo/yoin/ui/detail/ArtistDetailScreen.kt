package com.gpo.yoin.ui.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.component.AlbumCard
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.YoinDropdownMenu
import com.gpo.yoin.ui.component.YoinDropdownMenuItem
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.elasticPress
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.rememberCoverColorScheme
import com.gpo.yoin.ui.theme.withTabularFigures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState

private val ArtistToolbarButtonHeight = 60.dp

@Composable
fun ArtistDetailScreen(
    uiState: ArtistDetailUiState,
    onBackClick: () -> Unit,
    onAlbumClick: (albumId: String) -> Unit,
    onRetry: () -> Unit,
    onToggleFollow: () -> Unit = {},
    onPlay: () -> Unit = {},
    onShuffle: () -> Unit = {},
    onShare: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Expressive) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
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
                        onShare = onShare,
                    )
            }
        }
    }
}

@Composable
private fun ArtistDetailContent(
    content: ArtistDetailUiState.Content,
    onBackClick: () -> Unit,
    onAlbumClick: (albumId: String) -> Unit,
    onToggleFollow: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onShare: () -> Unit,
) {
    // Portrait → first album cover fallback (older Subsonic has no artist.jpg).
    val heroUrl = content.heroCoverArtUrl ?: content.albums.firstOrNull()?.coverArtUrl

    // Material roles seeded from the artist portrait (same path as the album page).
    val coverScheme = rememberCoverColorScheme(heroUrl)
    val s = coverScheme ?: MaterialTheme.colorScheme
    val primaryBlock by animateColorAsState(s.primary, tween(420), label = "artistPrimaryBlock")
    val secondaryBlock by animateColorAsState(s.secondary, tween(420), label = "artistSecondaryBlock")
    val titleColor by animateColorAsState(s.primary, tween(420), label = "artistTitleColor")
    val accentText = s.secondary
    val toolbarTint = androidx.compose.ui.graphics.lerp(
        MaterialTheme.colorScheme.surfaceContainer,
        s.secondaryContainer,
        0.5f,
    )

    val pagerState = rememberPagerState(pageCount = { 2 })

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ArtistTopHeader(
                artistName = content.artistName,
                albumCount = content.albumCount,
                titleColor = titleColor,
                accentText = accentText,
                pageFraction = pagerState.currentPage + pagerState.currentPageOffsetFraction,
                onBackClick = onBackClick,
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
            ) { page ->
                when (page) {
                    0 -> ArtistOverviewPage(
                        content = content,
                        heroUrl = heroUrl,
                        primaryBlock = primaryBlock,
                        secondaryBlock = secondaryBlock,
                        accent = primaryBlock,
                        accentOn = s.onPrimary,
                        onAlbumClick = onAlbumClick,
                        onToggleFollow = onToggleFollow,
                    )

                    else -> ArtistSecondaryPage()
                }
            }
        }

        ArtistBottomToolbar(
            playContainer = primaryBlock,
            playContent = s.onPrimary,
            toolbarContainer = toolbarTint,
            onPlay = onPlay,
            onShuffle = onShuffle,
            onShare = onShare,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        )
    }
}

@Composable
private fun ArtistTopHeader(
    artistName: String,
    albumCount: Int?,
    titleColor: Color,
    accentText: Color,
    pageFraction: Float,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = artistName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append("Artist")
                        albumCount?.let { append("  ·  $it albums") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = accentText,
                    maxLines = 1,
                )
            }
        }
        AlbumPageDots(
            activeFraction = pageFraction,
            activeColor = accentText,
            inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            modifier = Modifier
                .padding(top = 6.dp)
                .align(Alignment.CenterHorizontally),
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
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val maxW = maxWidth
        val portraitSize = minOf(maxW * 0.52f, 220.dp)
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        Box(modifier = Modifier.fillMaxSize()) {
            // Arrow-mark backdrop over the portrait area (fixed behind content).
            AlbumArrowBackground(
                primaryBlock = primaryBlock,
                secondaryBlock = secondaryBlock,
                lineColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                markHeight = portraitSize + 140.dp,
                modifier = Modifier.fillMaxSize(),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 110.dp + navBottom),
            ) {
                // Circular portrait — the focus.
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
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
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Albums count (left) · Follow (right) — mirrors the album hero row.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AlbumSectionLabel(text = "Albums")
                        Text(
                            text = (content.albumCount ?: content.albums.size).toString(),
                            style = MaterialTheme.typography.headlineSmall
                                .copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AlbumSectionLabel(text = if (content.isStarred) "Following" else "Follow")
                        ArtistFollowBun(
                            following = content.isStarred,
                            accent = accent,
                            accentOn = accentOn,
                            onToggle = onToggleFollow,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                AlbumSectionLabel(text = "Discography")
                Spacer(modifier = Modifier.height(10.dp))

                if (content.albums.isEmpty()) {
                    Text(
                        text = "无",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp),
                    ) {
                        items(content.albums, key = { it.id }) { album ->
                            AlbumCard(
                                coverArtUrl = album.coverArtUrl,
                                title = album.name,
                                subtitle = album.songCount?.let { "$it songs" },
                                metaLabel = album.year?.toString(),
                                onClick = { onAlbumClick(album.id) },
                                fixedWidth = 168.dp,
                                showIndication = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ArtistFollowBun(
    following: Boolean,
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
                contentDescription = if (following) "Following" else "Follow",
                tint = if (following) accentOn else accent,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun ArtistSecondaryPage(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "A bio, top tracks and a Gemini-written About will live here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = toolbarContainer,
        ),
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onShare, modifier = Modifier.size(52.dp)) {
                Icon(
                    imageVector = Icons.Rounded.IosShare,
                    contentDescription = "Share",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            ArtistPlaySplitButton(
                playContainer = playContainer,
                playContent = playContent,
                onPlay = onPlay,
                onShuffle = onShuffle,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ArtistPlaySplitButton(
    playContainer: Color,
    playContent: Color,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val buttonColors = ButtonDefaults.buttonColors(
        containerColor = playContainer,
        contentColor = playContent,
    )
    SplitButtonLayout(
        modifier = modifier,
        leadingButton = {
            SplitButtonDefaults.LeadingButton(
                onClick = onPlay,
                colors = buttonColors,
                modifier = Modifier.height(ArtistToolbarButtonHeight),
                contentPadding = PaddingValues(horizontal = 26.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize),
                )
                Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                Text("Play", style = MaterialTheme.typography.titleMedium)
            }
        },
        trailingButton = {
            SplitButtonDefaults.TrailingButton(
                checked = menuOpen,
                onCheckedChange = { menuOpen = it },
                colors = buttonColors,
                modifier = Modifier.height(ArtistToolbarButtonHeight),
                contentPadding = PaddingValues(horizontal = 20.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "More play options",
                    modifier = Modifier
                        .size(SplitButtonDefaults.TrailingIconSize)
                        .graphicsLayer { rotationZ = if (menuOpen) 180f else 0f },
                )
                YoinDropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    shadowElevation = 0.dp,
                ) {
                    YoinDropdownMenuItem(
                        text = "Shuffle play",
                        onClick = {
                            menuOpen = false
                            onShuffle()
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(22.dp))
                        },
                        textStyle = MaterialTheme.typography.titleMedium,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
        },
    )
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
        IconButton(onClick = onBackClick, modifier = Modifier.padding(4.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
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
