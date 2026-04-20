package com.gpo.yoin.ui.detail

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.component.AlbumCard
import com.gpo.yoin.ui.component.ExpressiveHeaderBlock
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressiveMetaPill
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.ExpressiveSectionPanel
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.navigation.artistCoverSharedKey
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ArtistDetailScreen(
    uiState: ArtistDetailUiState,
    onBackClick: () -> Unit,
    onAlbumClick: (albumId: String) -> Unit,
    onRetry: () -> Unit,
    sharedTransitionKey: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        ExpressivePageBackground(modifier = modifier) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = (uiState as? ArtistDetailUiState.Content)?.artistName.orEmpty(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onBackClick) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                },
            ) { innerPadding ->
                when (uiState) {
                    is ArtistDetailUiState.Loading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .navigationBarsPadding(),
                            contentAlignment = Alignment.Center,
                        ) {
                            YoinLoadingIndicator()
                        }
                    }

                    is ArtistDetailUiState.Error -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .navigationBarsPadding()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            ExpressiveSectionPanel(
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                androidx.compose.foundation.layout.Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = uiState.message,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    TextButton(onClick = onRetry) {
                                        Text("Retry")
                                    }
                                }
                            }
                        }
                    }

                    is ArtistDetailUiState.Content -> {
                        ArtistDetailContent(
                            content = uiState,
                            onAlbumClick = onAlbumClick,
                            sharedTransitionKey = sharedTransitionKey,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ArtistDetailContent(
    content: ArtistDetailUiState.Content,
    onAlbumClick: (albumId: String) -> Unit,
    sharedTransitionKey: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    var targetAlpha by remember { mutableFloatStateOf(0f) }
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = YoinMotion.defaultEffectsSpec(role = YoinMotionRole.Standard),
        label = "artistContentAlpha",
    )
    LaunchedEffect(Unit) { targetAlpha = 1f }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier
            .fillMaxSize()
            .alpha(alpha),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = 24.dp + navBottom,
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ArtistHeroArtwork(
                    artistId = content.artistId,
                    sharedTransitionKey = sharedTransitionKey,
                    // Prefer the artist's own portrait; fall back to
                    // the first album cover so Subsonic servers that
                    // don't expose artist.jpg still render something.
                    coverArtUrl = content.heroCoverArtUrl
                        ?: content.albums.firstOrNull()?.coverArtUrl,
                    artistName = content.artistName,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    // Must be 1:1 — the hero clips to `CircleShape`.
                    // Any other ratio produces an ellipse and quietly
                    // breaks the "artists are circles" convention
                    // (Library row avatars, Home tiles, activity grid).
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ExpressiveHeaderBlock(
                        title = content.artistName,
                        overline = "Artist",
                    )
                    content.albumCount?.let {
                        ExpressiveMetaPill(text = "$it releases")
                    }
                }
            }
        }

        items(
            items = content.albums,
            key = { it.id },
        ) { album ->
            AlbumCard(
                coverArtUrl = album.coverArtUrl,
                title = album.name,
                subtitle = album.songCount?.let { count -> "$count songs" },
                metaLabel = album.year?.toString(),
                onClick = { onAlbumClick(album.id) },
                modifier = Modifier.fillMaxWidth(),
                fixedWidth = null,
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ArtistHeroArtwork(
    artistId: String,
    sharedTransitionKey: String?,
    coverArtUrl: String?,
    artistName: String,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    modifier: Modifier = Modifier,
) {
    // Artists are always circular across the app (Library row avatars,
    // Home jump-back-in tiles, activity grid). The hero needs to match —
    // anything else gives away that this used to be the album-detail
    // shape borrowed wholesale during the multi-provider port.
    val shape = CircleShape
    val artworkBoundsSpec = YoinMotion.defaultSpatialSpec<Rect>(
        role = YoinMotionRole.Expressive,
        expressiveScheme = MaterialTheme.motionScheme,
    )
    val sharedArtworkModifier = if (
        sharedTransitionScope != null &&
        animatedVisibilityScope != null
    ) {
        with(sharedTransitionScope) {
            modifier
                .sharedElement(
                    sharedContentState = rememberSharedContentState(
                        key = artistCoverSharedKey(artistId, sharedTransitionKey),
                    ),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = { _, _ -> artworkBoundsSpec },
                    renderInOverlayDuringTransition = false,
                    zIndexInOverlay = 1f,
                )
                .clip(shape)
        }
    } else {
        modifier
    }

    Box(modifier = sharedArtworkModifier) {
        ExpressiveMediaArtwork(
            model = coverArtUrl,
            contentDescription = artistName,
            modifier = Modifier.fillMaxSize(),
            shape = shape,
            fallbackIcon = Icons.Filled.LibraryMusic,
            shadowElevation = 12.dp,
            tonalElevation = 3.dp,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun ArtistDetailScreenLoadingPreview() {
    YoinTheme {
        ArtistDetailScreen(
            uiState = ArtistDetailUiState.Loading,
            onBackClick = {},
            onAlbumClick = {},
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun ArtistDetailScreenContentPreview() {
    YoinTheme {
        ArtistDetailScreen(
            uiState = ArtistDetailUiState.Content(
                artistId = "artist-1",
                artistName = "Daft Punk",
                albumCount = 4,
                heroCoverArtUrl = null,
                albums = listOf(
                    ArtistAlbum(
                        id = "1",
                        name = "Random Access Memories",
                        coverArtUrl = null,
                        year = 2013,
                        songCount = 13,
                    ),
                    ArtistAlbum(
                        id = "2",
                        name = "TRON: Legacy",
                        coverArtUrl = null,
                        year = 2010,
                        songCount = 22,
                    ),
                    ArtistAlbum(
                        id = "3",
                        name = "Human After All",
                        coverArtUrl = null,
                        year = 2005,
                        songCount = 10,
                    ),
                    ArtistAlbum(
                        id = "4",
                        name = "Discovery",
                        coverArtUrl = null,
                        year = 2001,
                        songCount = 14,
                    ),
                ),
            ),
            onBackClick = {},
            onAlbumClick = {},
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun ArtistDetailScreenErrorPreview() {
    YoinTheme {
        ArtistDetailScreen(
            uiState = ArtistDetailUiState.Error("Network error"),
            onBackClick = {},
            onAlbumClick = {},
            onRetry = {},
        )
    }
}
