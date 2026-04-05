package com.gpo.yoin.ui.detail

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gpo.yoin.R
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    uiState: AlbumDetailUiState,
    onBackClick: () -> Unit,
    onSongClick: (songId: String) -> Unit,
    onToggleStar: (songId: String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = when (uiState) {
                        is AlbumDetailUiState.Content -> uiState.albumName
                        else -> ""
                    }
                    Text(
                        text = title,
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
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        when (uiState) {
            is AlbumDetailUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    YoinLoadingIndicator()
                }
            }

            is AlbumDetailUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
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

            is AlbumDetailUiState.Content -> {
                AlbumDetailContent(
                    content = uiState,
                    onSongClick = onSongClick,
                    onToggleStar = onToggleStar,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

@Composable
private fun AlbumDetailContent(
    content: AlbumDetailUiState.Content,
    onSongClick: (songId: String) -> Unit,
    onToggleStar: (songId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Spring fade-in
    var targetAlpha by remember { mutableFloatStateOf(0f) }
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "albumContentAlpha",
    )
    LaunchedEffect(Unit) { targetAlpha = 1f }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .alpha(alpha),
    ) {
        // Cover art
        item {
            if (LocalInspectionMode.current) {
                androidx.compose.foundation.Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = content.albumName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .aspectRatio(1f)
                        .clip(YoinShapeTokens.Large),
                )
            } else {
                AsyncImage(
                    model = content.coverArtUrl,
                    contentDescription = content.albumName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .aspectRatio(1f)
                        .clip(YoinShapeTokens.Large),
                )
            }
        }

        // Album info
        item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = content.albumName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = content.artistName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = buildAlbumMeta(content.year, content.songCount, content.totalDuration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Song list
        itemsIndexed(
            items = content.songs,
            key = { _, song -> song.id },
        ) { _, song ->
            AlbumSongRow(
                song = song,
                onClick = { onSongClick(song.id) },
                onToggleStar = { onToggleStar(song.id) },
            )
        }

        // Bottom spacer
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun AlbumSongRow(
    song: AlbumSong,
    onClick: () -> Unit,
    onToggleStar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Track number
        Text(
            text = song.trackNumber?.toString() ?: "-",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(32.dp),
        )

        // Title + artist
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Duration
        if (song.duration != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatDuration(song.duration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Star toggle
        IconButton(
            onClick = onToggleStar,
            modifier = Modifier.size(40.dp),
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
    }
}

private fun formatDuration(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(mins, secs)
}

private fun buildAlbumMeta(year: Int?, songCount: Int?, totalDuration: Int?): String {
    val parts = mutableListOf<String>()
    year?.let { parts.add(it.toString()) }
    songCount?.let { parts.add("$it songs") }
    totalDuration?.let {
        val mins = it / 60
        parts.add("$mins min")
    }
    return parts.joinToString(" · ")
}

// ── Previews ────────────────────────────────────────────────────────────

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
                albumName = "Random Access Memories",
                artistName = "Daft Punk",
                coverArtUrl = null,
                year = 2013,
                songCount = 13,
                totalDuration = 4460,
                songs = listOf(
                    AlbumSong(
                        id = "1",
                        title = "Give Life Back to Music",
                        artist = "Daft Punk",
                        trackNumber = 1,
                        duration = 275,
                        isStarred = true,
                    ),
                    AlbumSong(
                        id = "2",
                        title = "The Game of Love",
                        artist = "Daft Punk",
                        trackNumber = 2,
                        duration = 321,
                        isStarred = false,
                    ),
                    AlbumSong(
                        id = "3",
                        title = "Giorgio by Moroder",
                        artist = "Daft Punk",
                        trackNumber = 3,
                        duration = 544,
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
            uiState = AlbumDetailUiState.Error("Network error"),
            onBackClick = {},
            onSongClick = {},
            onToggleStar = {},
            onRetry = {},
        )
    }
}
