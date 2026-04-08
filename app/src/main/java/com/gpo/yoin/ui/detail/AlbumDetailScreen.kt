package com.gpo.yoin.ui.detail

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.component.ExpressiveHeaderBlock
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressiveMetaPill
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.ExpressiveSectionPanel
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.withTabularFigures

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
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        ExpressivePageBackground(modifier = Modifier.padding(innerPadding)) {
            when (uiState) {
                is AlbumDetailUiState.Loading -> {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        YoinLoadingIndicator()
                    }
                }

                is AlbumDetailUiState.Error -> {
                    ExpressiveSectionPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
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

                is AlbumDetailUiState.Content -> {
                    AlbumDetailContent(
                        content = uiState,
                        onSongClick = onSongClick,
                        onToggleStar = onToggleStar,
                    )
                }
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
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ExpressiveSectionPanel(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 4.dp,
                shadowElevation = 10.dp,
            ) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ExpressiveMediaArtwork(
                        model = content.coverArtUrl,
                        contentDescription = content.albumName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        shape = YoinShapeTokens.ExtraLarge,
                        fallbackIcon = Icons.Filled.LibraryMusic,
                        shadowElevation = 12.dp,
                        tonalElevation = 3.dp,
                    )
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
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        itemsIndexed(content.songs, key = { _, song -> song.id }) { _, song ->
            AlbumSongRow(
                song = song,
                onClick = { onSongClick(song.id) },
                onToggleStar = { onToggleStar(song.id) },
            )
        }

        item {
            Spacer(modifier = Modifier.height(116.dp))
        }
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
    onClick: () -> Unit,
    onToggleStar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = YoinShapeTokens.ExtraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExpressiveMetaPill(text = song.trackNumber?.toString() ?: "—")

            androidx.compose.foundation.layout.Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleSmall,
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

            song.duration?.let {
                Text(
                    text = formatDuration(it),
                    style = MaterialTheme.typography.labelLarge.withTabularFigures(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FilledIconButton(
                onClick = onToggleStar,
                modifier = Modifier
                    .size(44.dp)
                    .minimumTouchTarget(),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (song.isStarred) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ),
            ) {
                Icon(
                    imageVector = if (song.isStarred) {
                        Icons.Filled.Favorite
                    } else {
                        Icons.Filled.FavoriteBorder
                    },
                    contentDescription = if (song.isStarred) "Unstar" else "Star",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(mins, secs)
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
