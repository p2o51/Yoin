package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gpo.yoin.player.CastState
import com.gpo.yoin.player.VisualizerData
import com.gpo.yoin.ui.component.AudioVisualizer
import com.gpo.yoin.ui.component.CastButton
import com.gpo.yoin.ui.component.LyricsDisplay
import com.gpo.yoin.ui.component.QueueSheet
import com.gpo.yoin.ui.component.RatingSlider
import com.gpo.yoin.ui.component.VisualizerStyle
import com.gpo.yoin.ui.component.WaveProgressBar
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * Full-screen Now Playing overlay.
 *
 * All state is hoisted — this composable is purely presentational.
 */
@Composable
fun NowPlayingScreen(
    uiState: NowPlayingUiState,
    visualizerData: VisualizerData,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onRatingChange: (Float) -> Unit,
    onToggleFavorite: () -> Unit,
    onSkipToQueueItem: (Int) -> Unit,
    castState: CastState = CastState.NotAvailable,
    onCastClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val background = MaterialTheme.colorScheme.background
    val vizColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(surfaceContainer, background),
                ),
            )
            .padding(WindowInsets.systemBars.asPaddingValues()),
    ) {
        // Background visualizer layer
        AudioVisualizer(
            visualizerData = visualizerData,
            color = vizColor,
            style = VisualizerStyle.Ambient,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
        )

        when (uiState) {
            is NowPlayingUiState.Idle -> IdleContent()
            is NowPlayingUiState.Playing -> PlayingContent(
                state = uiState,
                onTogglePlayPause = onTogglePlayPause,
                onSkipNext = onSkipNext,
                onSkipPrevious = onSkipPrevious,
                onSeek = onSeek,
                onRatingChange = onRatingChange,
                onToggleFavorite = onToggleFavorite,
                onSkipToQueueItem = onSkipToQueueItem,
                castState = castState,
                onCastClick = onCastClick,
            )
        }
    }
}

@Composable
private fun IdleContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Nothing playing",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlayingContent(
    state: NowPlayingUiState.Playing,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onRatingChange: (Float) -> Unit,
    onToggleFavorite: () -> Unit,
    onSkipToQueueItem: (Int) -> Unit,
    castState: CastState = CastState.NotAvailable,
    onCastClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val progress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val buffered = if (state.durationMs > 0) {
        (state.bufferedMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    var showQueue by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Album cover + Rating slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Album cover — takes most of the width
            AlbumCover(
                coverArtUrl = state.coverArtUrl,
                modifier = Modifier.weight(1f),
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Rating slider + Favorite button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.height(250.dp),
            ) {
                RatingSlider(
                    rating = state.rating,
                    onRatingChange = onRatingChange,
                    modifier = Modifier.weight(1f),
                )

                Spacer(modifier = Modifier.height(8.dp))

                FavoriteButton(
                    isStarred = state.isStarred,
                    onClick = onToggleFavorite,
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Song info
        Text(
            text = state.songTitle,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = state.artist,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Lyrics display
        LyricsDisplay(
            lyrics = state.lyrics,
            positionMs = state.positionMs,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Progress bar
        WaveProgressBar(
            progress = progress,
            buffered = buffered,
            onSeek = onSeek,
            modifier = Modifier.fillMaxWidth(),
        )

        // Time labels
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime(state.positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatTime(state.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Playback controls
        PlaybackControls(
            isPlaying = state.isPlaying,
            onTogglePlayPause = onTogglePlayPause,
            onSkipNext = onSkipNext,
            onSkipPrevious = onSkipPrevious,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Bottom pills — Queue button + Cast pill
        BottomPills(
            queueSize = state.queue.size,
            onQueueClick = { showQueue = true },
            castState = castState,
            onCastClick = onCastClick,
        )
    }

    // Queue bottom sheet
    if (showQueue) {
        QueueSheet(
            queue = state.queue,
            currentIndex = state.currentQueueIndex,
            onItemClick = { index ->
                onSkipToQueueItem(index)
                showQueue = false
            },
            onDismiss = { showQueue = false },
        )
    }
}

@Composable
private fun FavoriteButton(
    isStarred: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val heartColor by animateColorAsState(
        targetValue = if (isStarred) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "heartColor",
    )

    IconButton(
        onClick = onClick,
        modifier = modifier.size(40.dp),
    ) {
        Icon(
            imageVector = if (isStarred) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (isStarred) "Remove from favorites" else "Add to favorites",
            tint = heartColor,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun AlbumCover(
    coverArtUrl: String?,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = coverArtUrl,
        contentDescription = "Album cover",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxWidth()
            .clip(YoinShapeTokens.ExtraLarge),
    )
}

@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playPauseScale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = YoinMotion.bouncySpatialSpring(),
        label = "playPauseScale",
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onSkipPrevious,
            modifier = Modifier.size(64.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = "Skip previous",
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        FilledIconButton(
            onClick = onTogglePlayPause,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .size(72.dp)
                .scale(playPauseScale),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(40.dp),
            )
        }

        IconButton(
            onClick = onSkipNext,
            modifier = Modifier.size(64.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "Skip next",
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun BottomPills(
    queueSize: Int,
    onQueueClick: () -> Unit,
    castState: CastState = CastState.NotAvailable,
    onCastClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CastButton(
            castState = castState,
            onClick = onCastClick,
        )

        FilledTonalButton(onClick = onQueueClick) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "Queue ($queueSize)")
        }
    }
}

/** Format milliseconds as m:ss (e.g. "3:45", "0:00"). */
internal fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

// ── Previews ────────────────────────────────────────────────────────────

private val previewPlayingState = NowPlayingUiState.Playing(
    songTitle = "Starlight",
    artist = "Muse",
    albumName = "Black Holes and Revelations",
    coverArtUrl = null,
    isPlaying = true,
    positionMs = 125_000L,
    durationMs = 240_000L,
    bufferedMs = 180_000L,
    songId = "1",
    rating = 3.7f,
    isStarred = true,
    lyrics = listOf(
        LyricLine(startMs = 0, text = "Far away…"),
        LyricLine(startMs = 60_000, text = "This ship is taking me far away"),
        LyricLine(startMs = 120_000, text = "Far away from the memories"),
        LyricLine(startMs = 180_000, text = "Of the people who care if I live or die"),
    ),
    queue = listOf(
        QueueItem("1", "Starlight", "Muse", null),
        QueueItem("2", "Supermassive Black Hole", "Muse", null),
        QueueItem("3", "Map of the Problematique", "Muse", null),
    ),
    currentQueueIndex = 0,
)

private val previewVisualizerData = VisualizerData(
    fft = FloatArray(32) { i ->
        val t = i.toFloat() / 32
        (kotlin.math.sin(t * Math.PI * 2).toFloat() * 0.4f + 0.5f)
            .coerceIn(0f, 1f)
    },
)

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F, showSystemUi = true)
@Composable
private fun NowPlayingScreenPlayingPreview() {
    YoinTheme {
        NowPlayingScreen(
            uiState = previewPlayingState,
            visualizerData = previewVisualizerData,
            onTogglePlayPause = {},
            onSkipNext = {},
            onSkipPrevious = {},
            onSeek = {},
            onRatingChange = {},
            onToggleFavorite = {},
            onSkipToQueueItem = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F, showSystemUi = true)
@Composable
private fun NowPlayingScreenIdlePreview() {
    YoinTheme {
        NowPlayingScreen(
            uiState = NowPlayingUiState.Idle,
            visualizerData = VisualizerData.Empty,
            onTogglePlayPause = {},
            onSkipNext = {},
            onSkipPrevious = {},
            onSeek = {},
            onRatingChange = {},
            onToggleFavorite = {},
            onSkipToQueueItem = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PlaybackControlsPreview() {
    YoinTheme {
        PlaybackControls(
            isPlaying = false,
            onTogglePlayPause = {},
            onSkipNext = {},
            onSkipPrevious = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun AlbumCoverPreview() {
    YoinTheme {
        AlbumCover(
            coverArtUrl = null,
            modifier = Modifier.size(300.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun FavoriteButtonPreview() {
    YoinTheme {
        FavoriteButton(
            isStarred = true,
            onClick = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun BottomPillsPreview() {
    YoinTheme {
        BottomPills(
            queueSize = 5,
            onQueueClick = {},
        )
    }
}
