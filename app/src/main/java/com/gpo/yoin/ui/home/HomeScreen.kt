package com.gpo.yoin.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gpo.yoin.R
import com.gpo.yoin.data.local.PlayHistory
import com.gpo.yoin.data.remote.Album
import com.gpo.yoin.player.VisualizerData
import com.gpo.yoin.ui.component.AlbumCard
import com.gpo.yoin.ui.component.AudioVisualizer
import com.gpo.yoin.ui.component.VisualizerStyle
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    isPlaying: Boolean,
    visualizerData: VisualizerData,
    onNavigateToSettings: () -> Unit,
    onAlbumClick: (albumId: String) -> Unit,
    onSongClick: (songId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    HomeContent(
        uiState = uiState,
        isPlaying = isPlaying,
        visualizerData = visualizerData,
        onNavigateToSettings = onNavigateToSettings,
        onAlbumClick = onAlbumClick,
        onSongClick = onSongClick,
        onRetry = viewModel::refresh,
        buildCoverArtUrl = viewModel::buildCoverArtUrl,
        modifier = modifier,
    )
}

@Composable
fun HomeContent(
    uiState: HomeUiState,
    isPlaying: Boolean,
    visualizerData: VisualizerData,
    onNavigateToSettings: () -> Unit,
    onAlbumClick: (albumId: String) -> Unit,
    onSongClick: (songId: String) -> Unit,
    onRetry: () -> Unit,
    buildCoverArtUrl: (String) -> String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            HomeTopBar(onNavigateToSettings = onNavigateToSettings)

            when (uiState) {
                is HomeUiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                is HomeUiState.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = uiState.message,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = onRetry) {
                                Text("Retry")
                            }
                        }
                    }
                }

                is HomeUiState.Content -> {
                    if (isPlaying) {
                        AudioVisualizer(
                            visualizerData = visualizerData,
                            style = VisualizerStyle.Compact,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }

                    HomeContentSections(
                        activities = uiState.activities,
                        mixAlbums = uiState.mixAlbums,
                        memories = uiState.memories,
                        onAlbumClick = onAlbumClick,
                        onSongClick = onSongClick,
                        buildCoverArtUrl = buildCoverArtUrl,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeTopBar(
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Yoin",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        IconButton(onClick = onNavigateToSettings) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomeContentSections(
    activities: List<PlayHistory>,
    mixAlbums: List<Album>,
    memories: List<Album>,
    onAlbumClick: (albumId: String) -> Unit,
    onSongClick: (songId: String) -> Unit,
    buildCoverArtUrl: (String) -> String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (activities.isNotEmpty()) {
            SectionHeader(title = "Activities")
            ActivitiesRow(
                activities = activities,
                onSongClick = onSongClick,
                buildCoverArtUrl = buildCoverArtUrl,
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (mixAlbums.isNotEmpty()) {
            SectionHeader(title = "Mix")
            AlbumsRow(
                albums = mixAlbums,
                onAlbumClick = onAlbumClick,
                buildCoverArtUrl = buildCoverArtUrl,
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (memories.isNotEmpty()) {
            SectionHeader(title = "Memories")
            AlbumsRow(
                albums = memories,
                onAlbumClick = onAlbumClick,
                buildCoverArtUrl = buildCoverArtUrl,
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Phase 13 — visualization area placeholder
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun AlbumsRow(
    albums: List<Album>,
    onAlbumClick: (albumId: String) -> Unit,
    buildCoverArtUrl: (String) -> String,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(
            items = albums,
            key = { _, album -> album.id },
        ) { index, album ->
            val coverUrl = album.coverArt?.let { buildCoverArtUrl(it) }
                ?: buildCoverArtUrl(album.id)

            val alpha = remember { Animatable(0f) }
            LaunchedEffect(album.id) {
                alpha.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessLow,
                    ),
                )
            }

            AlbumCard(
                coverArtUrl = coverUrl,
                albumName = album.name,
                artistName = album.artist.orEmpty(),
                onClick = { onAlbumClick(album.id) },
                modifier = Modifier.alpha(alpha.value),
            )
        }
    }
}

@Composable
private fun ActivitiesRow(
    activities: List<PlayHistory>,
    onSongClick: (songId: String) -> Unit,
    buildCoverArtUrl: (String) -> String,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(
            items = activities,
            key = { _, history -> history.id },
        ) { index, history ->
            val alpha = remember { Animatable(0f) }
            LaunchedEffect(history.id) {
                alpha.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessLow,
                    ),
                )
            }

            ActivityCard(
                history = history,
                coverArtUrl = history.coverArtId?.let { buildCoverArtUrl(it) },
                onClick = { onSongClick(history.songId) },
                modifier = Modifier.alpha(alpha.value),
            )
        }
    }
}

@Composable
fun ActivityCard(
    history: PlayHistory,
    coverArtUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(150.dp)
            .clickable(onClick = onClick),
    ) {
        if (LocalInspectionMode.current) {
            androidx.compose.foundation.Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = history.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(150.dp)
                    .clip(YoinShapeTokens.Medium),
            )
        } else {
            AsyncImage(
                model = coverArtUrl,
                contentDescription = history.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(150.dp)
                    .clip(YoinShapeTokens.Medium),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = history.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = history.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = formatTimeAgo(history.playedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
        )
    }
}

private fun formatTimeAgo(playedAtMillis: Long): String {
    val diff = System.currentTimeMillis() - playedAtMillis
    val minutes = diff / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "${minutes}m ago"
        hours < 24L -> "${hours}h ago"
        days < 7L -> "${days}d ago"
        else -> "${days / 7}w ago"
    }
}

// ── Previews ────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun HomeContentLoadingPreview() {
    YoinTheme {
        HomeContent(
            uiState = HomeUiState.Loading,
            isPlaying = false,
            visualizerData = VisualizerData.Empty,
            onNavigateToSettings = {},
            onAlbumClick = {},
            onSongClick = {},
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
            visualizerData = VisualizerData.Empty,
            onNavigateToSettings = {},
            onAlbumClick = {},
            onSongClick = {},
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
                    PlayHistory(
                        id = 1,
                        songId = "s1",
                        title = "Starlight",
                        artist = "Muse",
                        album = "Black Holes",
                        albumId = "a1",
                        coverArtId = "c1",
                        playedAt = System.currentTimeMillis() - 3_600_000L,
                        durationMs = 240_000L,
                        completedPercent = 0.95f,
                    ),
                    PlayHistory(
                        id = 2,
                        songId = "s2",
                        title = "Get Lucky",
                        artist = "Daft Punk",
                        album = "Random Access Memories",
                        albumId = "a2",
                        coverArtId = "c2",
                        playedAt = System.currentTimeMillis() - 86_400_000L,
                        durationMs = 310_000L,
                        completedPercent = 1.0f,
                    ),
                ),
                mixAlbums = listOf(
                    Album(id = "a1", name = "Black Holes and Revelations", artist = "Muse"),
                    Album(id = "a2", name = "Random Access Memories", artist = "Daft Punk"),
                    Album(id = "a3", name = "OK Computer", artist = "Radiohead"),
                ),
                memories = listOf(
                    Album(id = "a4", name = "The Dark Side of the Moon", artist = "Pink Floyd"),
                    Album(id = "a5", name = "Abbey Road", artist = "The Beatles"),
                ),
            ),
            isPlaying = true,
            visualizerData = VisualizerData(
                fft = FloatArray(24) { i ->
                    val t = i.toFloat() / 24
                    (kotlin.math.sin(t * Math.PI * 3).toFloat() * 0.35f + 0.4f)
                        .coerceIn(0f, 1f)
                },
            ),
            onNavigateToSettings = {},
            onAlbumClick = {},
            onSongClick = {},
            onRetry = {},
            buildCoverArtUrl = { "" },
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun ActivityCardPreview() {
    YoinTheme {
        ActivityCard(
            history = PlayHistory(
                id = 1,
                songId = "s1",
                title = "Starlight",
                artist = "Muse",
                album = "Black Holes",
                albumId = "a1",
                coverArtId = "c1",
                playedAt = System.currentTimeMillis() - 3_600_000L,
                durationMs = 240_000L,
                completedPercent = 0.95f,
            ),
            coverArtUrl = null,
            onClick = {},
        )
    }
}
