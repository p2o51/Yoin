package com.gpo.yoin.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    uiState: PlaylistDetailUiState,
    onBackClick: () -> Unit,
    onPlayAllClick: () -> Unit,
    onSongClick: (songId: String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = (uiState as? PlaylistDetailUiState.Content)?.playlistName.orEmpty(),
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
                is PlaylistDetailUiState.Loading -> {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        YoinLoadingIndicator()
                    }
                }

                is PlaylistDetailUiState.Error -> {
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

                is PlaylistDetailUiState.Content -> {
                    PlaylistDetailContent(
                        content = uiState,
                        onPlayAllClick = onPlayAllClick,
                        onSongClick = onSongClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistDetailContent(
    content: PlaylistDetailUiState.Content,
    onPlayAllClick: () -> Unit,
    onSongClick: (songId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier.fillMaxSize(),
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
                        contentDescription = content.playlistName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        shape = YoinShapeTokens.ExtraLarge,
                        fallbackIcon = Icons.Filled.LibraryMusic,
                        shadowElevation = 12.dp,
                        tonalElevation = 3.dp,
                    )
                    ExpressiveHeaderBlock(
                        title = content.playlistName,
                        overline = "Playlist",
                        supporting = content.comment?.takeIf { it.isNotBlank() } ?: "Hand-picked tracks ready to play through.",
                    )
                    PlaylistMetaRow(content = content)
                    if (content.songs.isNotEmpty()) {
                        Button(
                            onClick = onPlayAllClick,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Play All")
                        }
                    }
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

        items(content.songs, key = { it.id }) { song ->
            SongListItem(
                title = song.title,
                artist = song.artist,
                album = song.album,
                durationSeconds = song.duration,
                coverArtUrl = song.coverArtUrl,
                onClick = { onSongClick(song.id) },
            )
        }

        item {
            Spacer(modifier = Modifier.height(116.dp))
        }
    }
}

@Composable
private fun PlaylistMetaRow(
    content: PlaylistDetailUiState.Content,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content.owner.takeIf { it.isNotBlank() }?.let { ExpressiveMetaPill(text = it) }
        content.songCount?.let { ExpressiveMetaPill(text = "$it tracks") }
        content.totalDuration?.takeIf { it > 0 }?.let { ExpressiveMetaPill(text = formatPlaylistDuration(it)) }
        content.isPublic?.let { ExpressiveMetaPill(text = if (it) "Public" else "Private") }
    }
}

private fun formatPlaylistDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PlaylistDetailContentPreview() {
    YoinTheme {
        PlaylistDetailScreen(
            uiState = PlaylistDetailUiState.Content(
                playlistName = "Late Night Rotation",
                owner = "gpo",
                comment = "Pulled from Navidrome",
                isPublic = false,
                songCount = 2,
                totalDuration = 768,
                coverArtUrl = null,
                songs = listOf(
                    PlaylistSong(
                        id = "1",
                        title = "Paranoid Android",
                        artist = "Radiohead",
                        album = "OK Computer",
                        duration = 386,
                        coverArtUrl = null,
                    ),
                    PlaylistSong(
                        id = "2",
                        title = "Comfortably Numb",
                        artist = "Pink Floyd",
                        album = "The Wall",
                        duration = 382,
                        coverArtUrl = null,
                    ),
                ),
            ),
            onBackClick = {},
            onPlayAllClick = {},
            onSongClick = {},
            onRetry = {},
        )
    }
}
