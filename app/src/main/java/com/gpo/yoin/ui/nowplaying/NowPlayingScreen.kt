package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
 * Accepts optional shared-transition scopes for the cover-art / title / artist morph.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
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
    onDismiss: () -> Unit = {},
    castState: CastState = CastState.NotAvailable,
    onCastClick: () -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
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
            ),
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
                onDismiss = onDismiss,
                castState = castState,
                onCastClick = onCastClick,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
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

@OptIn(ExperimentalSharedTransitionApi::class)
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
    onDismiss: () -> Unit = {},
    castState: CastState = CastState.NotAvailable,
    onCastClick: () -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
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

    val titleStretchScale by animateFloatAsState(
        targetValue = if (state.isPlaying) 1.04f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "titleStretch",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(WindowInsets.systemBars.asPaddingValues())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Top,
    ) {
        // ── 0. Drag handle / dismiss button + Playing from ─────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Close Now Playing",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Playing from ${state.albumName}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        // ── 1. Album cover + Rating slider ────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            verticalAlignment = Alignment.Top,
        ) {
            AlbumCover(
                coverArtUrl = state.coverArtUrl,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                modifier = Modifier.weight(1f),
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxHeight(),
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

        Spacer(modifier = Modifier.height(16.dp))

        // ── 2. Lyrics header + lyrics ─────────────────────────────────────
        Text(
            text = "Lyrics",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        LyricsDisplay(
            lyrics = state.lyrics,
            positionMs = state.positionMs,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── 3. Playback controls (with progress bar) ─────────────────────
        PlaybackControls(
            isPlaying = state.isPlaying,
            onTogglePlayPause = onTogglePlayPause,
            onSkipNext = onSkipNext,
            onSkipPrevious = onSkipPrevious,
            progress = progress,
            buffered = buffered,
            onSeek = onSeek,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ── 4. Song title + artist (bottom, large) ────────────────────────
        val titleModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            with(sharedTransitionScope) {
                Modifier
                    .sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "np_title"),
                        animatedVisibilityScope = animatedVisibilityScope,
                        boundsTransform = { _, _ ->
                            spring(stiffness = Spring.StiffnessMediumLow)
                        },
                    )
                    .fillMaxWidth()
            }
        } else {
            Modifier.fillMaxWidth()
        }
        Text(
            text = state.songTitle,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            modifier = titleModifier
                .graphicsLayer {
                    scaleX = titleStretchScale
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .basicMarquee(
                    iterations = Int.MAX_VALUE,
                    repeatDelayMillis = 2000,
                    initialDelayMillis = 1500,
                ),
        )
        Spacer(modifier = Modifier.height(2.dp))

        val artistModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            with(sharedTransitionScope) {
                Modifier
                    .sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "np_artist"),
                        animatedVisibilityScope = animatedVisibilityScope,
                        boundsTransform = { _, _ ->
                            spring(stiffness = Spring.StiffnessMediumLow)
                        },
                    )
                    .fillMaxWidth()
            }
        } else {
            Modifier.fillMaxWidth()
        }
        Text(
            text = state.artist,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = artistModifier,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ── 5. Bottom pills ───────────────────────────────────────────────
        BottomPills(
            onQueueClick = { showQueue = true },
            castState = castState,
            onCastClick = onCastClick,
        )

        Spacer(modifier = Modifier.height(24.dp))
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

    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(40.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = heartColor,
        ),
    ) {
        Icon(
            imageVector = if (isStarred) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (isStarred) "Remove from favorites" else "Add to favorites",
            tint = heartColor,
            modifier = Modifier.size(24.dp),
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun AlbumCover(
    coverArtUrl: String?,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val baseModifier = modifier
        .aspectRatio(1f)

    val finalModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            baseModifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "np_cover"),
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = { _, _ ->
                    spring(stiffness = Spring.StiffnessMediumLow)
                },
            )
        }
    } else {
        baseModifier
    }

    Surface(
        modifier = finalModifier.clip(YoinShapeTokens.ExtraLarge),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = YoinShapeTokens.ExtraLarge,
    ) {
        if (coverArtUrl != null) {
            AsyncImage(
                model = coverArtUrl,
                contentDescription = "Album cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    progress: Float,
    buffered: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stretchScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.06f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "playStretch",
    )
    val textStretchScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.15f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "textStretch",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        // Row 1: ButtonGroup(Pause, SkipNext) + Spacer + Shuffle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ButtonGroup(
                overflowIndicator = { _ -> },
                expandedRatio = ButtonGroupDefaults.ExpandedRatio,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                customItem(
                    buttonGroupContent = {
                        val interactionSource = remember { MutableInteractionSource() }
                        FilledTonalButton(
                            onClick = onTogglePlayPause,
                            modifier = Modifier
                                .height(56.dp)
                                .animateWidth(interactionSource)
                                .graphicsLayer { scaleX = stretchScale },
                            shape = MaterialTheme.shapes.extraLarge,
                            interactionSource = interactionSource,
                        ) {
                            Text(
                                text = if (isPlaying) "PAUSE" else "PLAY",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = textStretchScale
                                },
                            )
                        }
                    },
                    menuContent = { _ -> },
                )

                customItem(
                    buttonGroupContent = {
                        val interactionSource = remember { MutableInteractionSource() }
                        FilledIconButton(
                            onClick = onSkipNext,
                            modifier = Modifier
                                .size(56.dp)
                                .animateWidth(interactionSource),
                            interactionSource = interactionSource,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = "Skip next",
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    },
                    menuContent = { _ -> },
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Shuffle / playback mode (UI only)
            FilledIconButton(
                onClick = { /* TODO: implement playback mode toggle */ },
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Shuffle,
                    contentDescription = "Shuffle",
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Row 2: Skip Previous + Progress Bar (same row)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledIconButton(
                onClick = onSkipPrevious,
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = "Skip previous",
                    modifier = Modifier.size(28.dp),
                )
            }

            WaveProgressBar(
                progress = progress,
                buffered = buffered,
                onSeek = onSeek,
                isPlaying = isPlaying,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BottomPills(
    onQueueClick: () -> Unit,
    castState: CastState = CastState.NotAvailable,
    onCastClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val pillShape = RoundedCornerShape(8.dp)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CastButton(
            castState = castState,
            onClick = onCastClick,
        )

        ButtonGroup(
            overflowIndicator = { _ -> },
            expandedRatio = ButtonGroupDefaults.ExpandedRatio,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            customItem(
                buttonGroupContent = {
                    val interactionSource = remember { MutableInteractionSource() }
                    FilledTonalButton(
                        onClick = onQueueClick,
                        modifier = Modifier
                            .height(36.dp)
                            .animateWidth(interactionSource),
                        interactionSource = interactionSource,
                        shape = pillShape,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Queue",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                menuContent = { _ -> },
            )
            customItem(
                buttonGroupContent = {
                    val interactionSource = remember { MutableInteractionSource() }
                    FilledTonalButton(
                        onClick = { /* TODO: device selector */ },
                        modifier = Modifier
                            .height(36.dp)
                            .animateWidth(interactionSource),
                        interactionSource = interactionSource,
                        shape = pillShape,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Devices,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Devices",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                menuContent = { _ -> },
            )
            customItem(
                buttonGroupContent = {
                    val interactionSource = remember { MutableInteractionSource() }
                    FilledTonalButton(
                        onClick = { /* TODO: notes */ },
                        modifier = Modifier
                            .height(36.dp)
                            .animateWidth(interactionSource),
                        interactionSource = interactionSource,
                        shape = pillShape,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.StickyNote2,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Notes",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                menuContent = { _ -> },
            )
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
            progress = 0.4f,
            buffered = 0.7f,
            onSeek = {},
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
            onQueueClick = {},
        )
    }
}
