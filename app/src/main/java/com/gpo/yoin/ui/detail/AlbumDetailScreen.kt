package com.gpo.yoin.ui.detail

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.component.ExpressiveHeaderBlock
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressiveMetaPill
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.ExpressiveSectionPanel
import com.gpo.yoin.ui.component.SongListItem
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.component.rememberExpressiveBackdropColors
import com.gpo.yoin.ui.navigation.albumCoverSharedKey
import com.gpo.yoin.ui.navigation.rememberActiveOnlySharedContentConfig
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.withTabularFigures
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun AlbumDetailScreen(
    uiState: AlbumDetailUiState,
    sharedTransitionKey: String? = null,
    onBackClick: () -> Unit,
    onSongClick: (songId: String) -> Unit,
    onToggleStar: (songId: String) -> Unit,
    notedSongIds: Set<String> = emptySet(),
    expandedSongId: String? = null,
    expandedNoteBundle: AlbumExpandedNoteBundle? = null,
    onToggleExpandedSong: (songId: String) -> Unit = {},
    onRetry: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Expressive) {
        // Palette extraction is async and sometimes returns no significant
        // swatch (low-saturation / monochrome covers). In both cases the
        // `accentColor` lingers on `fallbackAccentColor`, which — being
        // `secondaryContainer` — lerps almost invisibly against
        // `surfaceContainer` on the page background. Gate on the
        // `isResolvedFromPalette` flag so we only pass a concrete accent
        // to `ExpressivePageBackground` once the palette actually resolved;
        // until then (and for covers that yield no swatch), the background
        // stays on its neutral static gradient instead of looking "almost
        // but not quite" tinted.
        val accentColor = (uiState as? AlbumDetailUiState.Content)?.coverArtUrl?.let { coverArtUrl ->
            val colors = rememberExpressiveBackdropColors(
                model = coverArtUrl,
                fallbackBaseColor = MaterialTheme.colorScheme.surfaceContainer,
                fallbackAccentColor = MaterialTheme.colorScheme.secondaryContainer,
            )
            colors.accentColor.takeIf { colors.isResolvedFromPalette }
        }
        ExpressivePageBackground(
            accentColor = accentColor,
            modifier = modifier,
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = (uiState as? AlbumDetailUiState.Content)?.albumName.orEmpty(),
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
                    is AlbumDetailUiState.Loading -> {
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

                    is AlbumDetailUiState.Error -> {
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

                    is AlbumDetailUiState.Content -> {
                        AlbumDetailContent(
                            content = uiState,
                            onSongClick = onSongClick,
                            onToggleStar = onToggleStar,
                            notedSongIds = notedSongIds,
                            expandedSongId = expandedSongId,
                            expandedNoteBundle = expandedNoteBundle,
                            onToggleExpandedSong = onToggleExpandedSong,
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
private fun AlbumDetailContent(
    content: AlbumDetailUiState.Content,
    onSongClick: (songId: String) -> Unit,
    onToggleStar: (songId: String) -> Unit,
    notedSongIds: Set<String>,
    expandedSongId: String?,
    expandedNoteBundle: AlbumExpandedNoteBundle?,
    onToggleExpandedSong: (songId: String) -> Unit,
    sharedTransitionKey: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    var revealHeader by remember(content.albumId) { mutableStateOf(false) }
    var revealTracks by remember(content.albumId) { mutableStateOf(false) }
    val supportingEffectsSpec = YoinMotion.defaultEffectsSpec<Float>(role = YoinMotionRole.Standard)
    val supportingSpatialSpec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Standard)
    val headerAlpha by animateFloatAsState(
        targetValue = if (revealHeader) 1f else 0f,
        animationSpec = supportingEffectsSpec,
        label = "albumHeaderAlpha",
    )
    val headerOffsetY by animateFloatAsState(
        targetValue = if (revealHeader) 0f else 18f,
        animationSpec = supportingSpatialSpec,
        label = "albumHeaderOffsetY",
    )
    val tracksAlpha by animateFloatAsState(
        targetValue = if (revealTracks) 1f else 0f,
        animationSpec = supportingEffectsSpec,
        label = "albumTracksAlpha",
    )
    val tracksOffsetY by animateFloatAsState(
        targetValue = if (revealTracks) 0f else 20f,
        animationSpec = supportingSpatialSpec,
        label = "albumTracksOffsetY",
    )
    LaunchedEffect(content.albumId) {
        revealHeader = false
        revealTracks = false
        revealHeader = true
        delay(90)
        revealTracks = true
    }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = 24.dp + navBottom,
        ),
    ) {
        item {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AlbumHeroArtwork(
                    albumId = content.albumId,
                    sharedTransitionKey = sharedTransitionKey,
                    coverArtUrl = content.coverArtUrl,
                    albumName = content.albumName,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .graphicsLayer {
                            alpha = headerAlpha
                            translationY = headerOffsetY
                        }
                        .padding(top = 2.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ExpressiveHeaderBlock(
                        title = content.albumName,
                        overline = "Album",
                        supporting = content.artistName,
                    )
                    AlbumMetaRow(content = content)
                }
            }
        }

        item {
            Text(
                text = "Tracks",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 10.dp)
                    .graphicsLayer {
                        alpha = tracksAlpha
                        translationY = tracksOffsetY
                    },
            )
        }

        itemsIndexed(content.songs, key = { _, song -> song.id }) { _, song ->
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.graphicsLayer {
                    alpha = tracksAlpha
                    translationY = tracksOffsetY
                },
            ) {
                AlbumSongRow(
                    song = song,
                    coverArtUrl = content.coverArtUrl,
                    hasNote = song.id in notedSongIds,
                    onClick = { onSongClick(song.id) },
                    onLongClick = { onToggleExpandedSong(song.id) },
                    onToggleStar = { onToggleStar(song.id) },
                )
                AnimatedVisibility(visible = expandedSongId == song.id) {
                    AlbumSongNotes(
                        bundle = expandedNoteBundle?.takeIf { it.songId == song.id },
                        modifier = Modifier.padding(start = 82.dp, end = 14.dp, bottom = 10.dp),
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun AlbumHeroArtwork(
    albumId: String,
    sharedTransitionKey: String? = null,
    coverArtUrl: String?,
    albumName: String,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val shape = YoinShapeTokens.Large
    val artworkBoundsSpec = YoinMotion.defaultSpatialSpec<Rect>(
        role = YoinMotionRole.Expressive,
        expressiveScheme = MaterialTheme.motionScheme,
    )
    val sharedArtworkModifier = if (
        sharedTransitionScope != null &&
        animatedVisibilityScope != null
    ) {
        val sharedContentConfig =
            rememberActiveOnlySharedContentConfig(
                animatedVisibilityScope = animatedVisibilityScope,
            )
        with(sharedTransitionScope) {
            modifier
                .sharedElement(
                    sharedContentState = rememberSharedContentState(
                        key = albumCoverSharedKey(albumId, sharedTransitionKey),
                        config = sharedContentConfig,
                    ),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = { _, _ -> artworkBoundsSpec },
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
            contentDescription = albumName,
            modifier = Modifier.fillMaxSize(),
            shape = shape,
            fallbackIcon = Icons.Filled.LibraryMusic,
            shadowElevation = 12.dp,
            tonalElevation = 3.dp,
        )
    }
}

@Composable
private fun AlbumMetaRow(
    content: AlbumDetailUiState.Content,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content.year?.let { ExpressiveMetaPill(text = "$it") }
        content.songCount?.let { ExpressiveMetaPill(text = "$it songs") }
        content.totalDuration?.let {
            val mins = it / 60
            ExpressiveMetaPill(text = "$mins min")
        }
    }
}

@Composable
private fun AlbumSongRow(
    song: AlbumSong,
    coverArtUrl: String?,
    hasNote: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleStar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SongListItem(
        title = song.title,
        artist = song.artist,
        album = "",
        durationSeconds = song.duration,
        coverArtUrl = coverArtUrl,
        onClick = onClick,
        onLongClick = onLongClick,
        hasNote = hasNote,
        modifier = modifier,
        trailingContent = {
            IconButton(
                onClick = onToggleStar,
                modifier = Modifier
                    .size(40.dp)
                    .minimumTouchTarget(),
            ) {
                Icon(
                    imageVector = if (song.isStarred) {
                        Icons.Filled.Favorite
                    } else {
                        Icons.Filled.FavoriteBorder
                    },
                    contentDescription = if (song.isStarred) "Unstar" else "Star",
                    tint = if (song.isStarred) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    )
}

@Composable
private fun AlbumSongNotes(
    bundle: AlbumExpandedNoteBundle?,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Notes",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val primary = bundle?.primaryNotes.orEmpty()
        if (primary.isEmpty()) {
            Text(
                text = "\u6ca1\u6709\u7b14\u8bb0",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            primary.forEach { note ->
                Text(
                    text = note.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        bundle?.crossProviderNotes?.forEach { note ->
            Text(
                text = "${note.providerLabel}: ${note.content}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun AlbumDetailScreenLoadingPreview() {
    YoinTheme {
        AlbumDetailScreen(
            uiState = AlbumDetailUiState.Loading,
            onBackClick = {},
            onSongClick = {},
            onToggleStar = {},
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun AlbumDetailScreenContentPreview() {
    YoinTheme {
        AlbumDetailScreen(
            uiState = AlbumDetailUiState.Content(
                albumId = "album-1",
                albumName = "Random Access Memories",
                artistName = "Daft Punk",
                artistId = "artist-1",
                coverArtId = "cover-1",
                coverArtUrl = null,
                year = 2013,
                songCount = 13,
                totalDuration = 4440,
                songs = listOf(
                    AlbumSong(
                        id = "1",
                        title = "Give Life Back to Music",
                        artist = "Daft Punk",
                        trackNumber = 1,
                        duration = 273,
                        isStarred = true,
                    ),
                    AlbumSong(
                        id = "2",
                        title = "Instant Crush",
                        artist = "Daft Punk feat. Julian Casablancas",
                        trackNumber = 5,
                        duration = 337,
                        isStarred = false,
                    ),
                ),
            ),
            onBackClick = {},
            onSongClick = {},
            onToggleStar = {},
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun AlbumDetailScreenErrorPreview() {
    YoinTheme {
        AlbumDetailScreen(
            uiState = AlbumDetailUiState.Error("Could not load album"),
            onBackClick = {},
            onSongClick = {},
            onToggleStar = {},
            onRetry = {},
        )
    }
}
