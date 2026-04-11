package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupScope
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import coil3.compose.AsyncImage
import com.gpo.yoin.player.CastState
import com.gpo.yoin.player.VisualizerData
import com.gpo.yoin.ui.component.CastButton
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.horizontalFadeMask
import com.gpo.yoin.ui.component.LyricsDisplay
import com.gpo.yoin.ui.component.QueueSheet
import com.gpo.yoin.ui.component.RatingSlider
import com.gpo.yoin.ui.component.WaveProgressBar
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.ReportMotionPressure
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.withTabularFigures

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

    ReportMotionPressure(
        tag = "now-playing",
        isHighPressure = uiState is NowPlayingUiState.Playing &&
            uiState.isPlaying &&
            visualizerData.fft.isNotEmpty(),
    )

    ProvideYoinMotionRole(role = YoinMotionRole.Expressive) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(surfaceContainer, background),
                    ),
                ),
        ) {
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
    val motionProfile = LocalMotionProfile.current
    val heroStretchSpec = if (motionProfile == MotionProfile.Full) {
        YoinMotion.slowSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    } else {
        YoinMotion.fastSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    }
    val heroBoundsSpec = YoinMotion.slowSpatialSpec<Rect>(
        role = YoinMotionRole.Expressive,
        expressiveScheme = MaterialTheme.motionScheme,
    )
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
    val playInteractionSource = rememberNowPlayingButtonGroupInteractionSource()
    val nextInteractionSource = rememberNowPlayingButtonGroupInteractionSource()
    val playPressed by playInteractionSource.collectIsPressedAsState()
    val nextPressed by nextInteractionSource.collectIsPressedAsState()
    val transportPressed = playPressed || nextPressed

    val titleStretchScale by animateFloatAsState(
        targetValue = when {
            motionProfile == MotionProfile.AdaptiveReduced && transportPressed -> 1.02f
            motionProfile == MotionProfile.AdaptiveReduced && state.isPlaying -> 1.01f
            motionProfile == MotionProfile.AdaptiveReduced -> 0.99f
            transportPressed -> 1.05f
            state.isPlaying -> 1.03f
            else -> 0.97f
        },
        animationSpec = heroStretchSpec,
        label = "titleStretch",
    )
    val artistStretchScale by animateFloatAsState(
        targetValue = when {
            motionProfile == MotionProfile.AdaptiveReduced && transportPressed -> 1.015f
            motionProfile == MotionProfile.AdaptiveReduced && state.isPlaying -> 1.008f
            motionProfile == MotionProfile.AdaptiveReduced -> 0.992f
            transportPressed -> 1.04f
            state.isPlaying -> 1.02f
            else -> 0.98f
        },
        animationSpec = heroStretchSpec,
        label = "artistStretch",
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
                .weight(1f),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── 3. Playback controls (with progress bar) ─────────────────────
        PlaybackControls(
            isPlaying = state.isPlaying,
            onTogglePlayPause = onTogglePlayPause,
            onSkipNext = onSkipNext,
            onSkipPrevious = onSkipPrevious,
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            progress = progress,
            buffered = buffered,
            onSeek = onSeek,
            playInteractionSource = playInteractionSource,
            nextInteractionSource = nextInteractionSource,
            playPressed = playPressed,
            nextPressed = nextPressed,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ── 4. Song title + artist (bottom, large) ────────────────────────
        val titleModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            with(sharedTransitionScope) {
                Modifier
                    .sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "np_title"),
                        animatedVisibilityScope = animatedVisibilityScope,
                        boundsTransform = { _, _ -> heroBoundsSpec },
                    )
                    .fillMaxWidth()
            }
        } else {
            Modifier.fillMaxWidth()
        }
        Box(modifier = titleModifier) {
            NowPlayingMarqueeTitle(
                text = state.songTitle,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                stretchScale = titleStretchScale,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(2.dp))

        val artistModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            with(sharedTransitionScope) {
                Modifier
                    .sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "np_artist"),
                        animatedVisibilityScope = animatedVisibilityScope,
                        boundsTransform = { _, _ -> heroBoundsSpec },
                    )
                    .fillMaxWidth()
            }
        } else {
            Modifier.fillMaxWidth()
        }
        Text(
            text = state.artist,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = MaterialTheme.typography.titleMedium.fontSize * 0.9f,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = artistModifier.graphicsLayer {
                scaleX = artistStretchScale
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
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
        ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
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
}

@Composable
private fun FavoriteButton(
    isStarred: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        val heartColor by animateColorAsState(
            targetValue = if (isStarred) {
                MaterialTheme.colorScheme.onTertiary
            } else {
                MaterialTheme.colorScheme.onTertiaryContainer
            },
            animationSpec = YoinMotion.defaultEffectsSpec(),
            label = "heartColor",
        )
        val heartContainerColor by animateColorAsState(
            targetValue = if (isStarred) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
            animationSpec = YoinMotion.defaultEffectsSpec(),
            label = "heartContainerColor",
        )

        FilledIconButton(
            onClick = onClick,
            modifier = modifier
                .size(44.dp)
                .minimumTouchTarget(),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = heartContainerColor,
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
    val coverBoundsSpec = YoinMotion.slowSpatialSpec<Rect>(
        role = YoinMotionRole.Expressive,
        expressiveScheme = MaterialTheme.motionScheme,
    )

    val finalModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            baseModifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "np_cover"),
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = { _, _ -> coverBoundsSpec },
            )
        }
    } else {
        baseModifier
    }

    ExpressiveMediaArtwork(
        model = coverArtUrl,
        contentDescription = "Album cover",
        modifier = finalModifier,
        shape = YoinShapeTokens.ExtraLarge,
        fallbackIcon = Icons.Filled.PlayArrow,
        tonalElevation = 4.dp,
        shadowElevation = 0.dp,
    )
}

@Composable
private fun NowPlayingMarqueeTitle(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color,
    stretchScale: Float,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val textMeasurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val availableWidthPx = with(density) { maxWidth.roundToPx() }
        val shouldMarquee = remember(text, style, availableWidthPx) {
            if (availableWidthPx <= 0) {
                false
            } else {
                textMeasurer.measure(
                    text = AnnotatedString(text),
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    constraints = Constraints(maxWidth = Constraints.Infinity),
                ).size.width > availableWidthPx
            }
        }

        Box(
            modifier = if (shouldMarquee) {
                Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .horizontalFadeMask(edgeWidth = 28.dp)
            } else {
                Modifier.fillMaxWidth()
            },
        ) {
            Text(
                text = text,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = stretchScale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .then(
                        if (shouldMarquee) {
                            Modifier.basicMarquee(
                                iterations = Int.MAX_VALUE,
                                repeatDelayMillis = 2000,
                                initialDelayMillis = 1500,
                            )
                        } else {
                            Modifier
                        },
                    ),
            )
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
    positionMs: Long,
    durationMs: Long,
    progress: Float,
    buffered: Float,
    onSeek: (Float) -> Unit,
    playInteractionSource: MutableInteractionSource,
    nextInteractionSource: MutableInteractionSource,
    playPressed: Boolean,
    nextPressed: Boolean,
    modifier: Modifier = Modifier,
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        val controlButtonSize = 56.dp
        val controlSpatialSpec = if (playPressed || nextPressed) {
            YoinMotion.fastSpatialSpec<Dp>()
        } else {
            YoinMotion.defaultSpatialSpec<Dp>()
        }
        val textStretchSpec = if (playPressed) {
            YoinMotion.fastSpatialSpec<Float>()
        } else {
            YoinMotion.defaultSpatialSpec<Float>()
        }
        val playHorizontalPadding by animateDpAsState(
            targetValue = when {
                playPressed -> 28.dp
                nextPressed -> 14.dp
                isPlaying -> 24.dp
                else -> 16.dp
            },
            animationSpec = controlSpatialSpec,
            label = "playHorizontalPadding",
        )
        val nextButtonWidth by animateDpAsState(
            targetValue = if (playPressed || nextPressed) 48.dp else controlButtonSize,
            animationSpec = controlSpatialSpec,
            label = "nextButtonWidth",
        )
        val textStretchScale by animateFloatAsState(
            targetValue = when {
                playPressed -> 1.08f
                isPlaying -> 1.02f
                else -> 0.97f
            },
            animationSpec = textStretchSpec,
            label = "textStretch",
        )

        Column(modifier = modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ButtonGroup(
                    overflowIndicator = { _ -> },
                    modifier = Modifier.height(controlButtonSize),
                    expandedRatio = ButtonGroupDefaults.ExpandedRatio,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    customItem(
                        buttonGroupContent = {
                            FilledTonalButton(
                                onClick = onTogglePlayPause,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .animateWidth(playInteractionSource)
                                    .animateContentSize(
                                        animationSpec = YoinMotion.defaultSpatialSpec(),
                                    ),
                                shape = MaterialTheme.shapes.extraLarge,
                                interactionSource = playInteractionSource,
                                contentPadding = PaddingValues(horizontal = playHorizontalPadding),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            ) {
                                Text(
                                    text = if (isPlaying) "PAUSE" else "PLAY",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontSize = MaterialTheme.typography.titleLarge.fontSize * 0.9f,
                                    ),
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.graphicsLayer {
                                        scaleX = textStretchScale
                                        transformOrigin = TransformOrigin(0f, 0.5f)
                                    },
                                )
                            }
                        },
                        menuContent = { _ -> },
                    )

                    customItem(
                        buttonGroupContent = {
                            FilledIconButton(
                                onClick = onSkipNext,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .animateWidth(nextInteractionSource)
                                    .width(nextButtonWidth),
                                interactionSource = nextInteractionSource,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
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

                FilledIconButton(
                    onClick = { /* TODO: implement playback mode toggle */ },
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shuffle,
                        contentDescription = "Shuffle",
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledIconButton(
                    onClick = onSkipPrevious,
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = "Skip previous",
                        modifier = Modifier.size(28.dp),
                    )
                }

                PlaybackTimeLabel(
                    text = formatTime(positionMs),
                    modifier = Modifier
                        .width(44.dp)
                        .offset(y = 6.dp),
                )
                WaveProgressBar(
                    progress = progress,
                    buffered = buffered,
                    durationMs = durationMs,
                    onSeek = onSeek,
                    isPlaying = isPlaying,
                    modifier = Modifier.weight(1f),
                )
                PlaybackTimeLabel(
                    text = "-${formatTime((durationMs - positionMs).coerceAtLeast(0L))}",
                    modifier = Modifier
                        .width(52.dp)
                        .offset(y = 6.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun PlaybackTimeLabel(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: androidx.compose.ui.text.style.TextAlign = androidx.compose.ui.text.style.TextAlign.Start,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.withTabularFigures(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = textAlign,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BottomPills(
    onQueueClick: () -> Unit,
    castState: CastState = CastState.NotAvailable,
    onCastClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        val queueInteraction = remember { MutableInteractionSource() }
        val devicesInteraction = remember { MutableInteractionSource() }
        val notesInteraction = remember { MutableInteractionSource() }

        val queuePressed by queueInteraction.collectIsPressedAsState()
        val devicesPressed by devicesInteraction.collectIsPressedAsState()
        val notesPressed by notesInteraction.collectIsPressedAsState()

        val anyPressed = queuePressed || devicesPressed || notesPressed

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
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                customItem(
                    buttonGroupContent = {
                        PillButton(
                            onClick = onQueueClick,
                            icon = Icons.AutoMirrored.Filled.QueueMusic,
                            label = "Queue",
                            showLabel = !anyPressed || queuePressed,
                            interactionSource = queueInteraction,
                            shape = YoinShapeTokens.Full,
                        )
                    },
                    menuContent = { _ -> },
                )
                customItem(
                    buttonGroupContent = {
                        PillButton(
                            onClick = { /* TODO: device selector */ },
                            icon = Icons.Filled.Devices,
                            label = "Devices",
                            showLabel = !anyPressed || devicesPressed,
                            interactionSource = devicesInteraction,
                            shape = RoundedCornerShape(20.dp),
                        )
                    },
                    menuContent = { _ -> },
                )
                customItem(
                    buttonGroupContent = {
                        PillButton(
                            onClick = { /* TODO: notes */ },
                            icon = Icons.AutoMirrored.Filled.StickyNote2,
                            label = "Notes",
                            showLabel = !anyPressed || notesPressed,
                            interactionSource = notesInteraction,
                            shape = YoinShapeTokens.Full,
                        )
                    },
                    menuContent = { _ -> },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ButtonGroupScope.PillButton(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    showLabel: Boolean,
    interactionSource: MutableInteractionSource,
    shape: androidx.compose.ui.graphics.Shape,
) {
    val labelFraction by animateFloatAsState(
        targetValue = if (showLabel) 1f else 0f,
        animationSpec = YoinMotion.fastEffectsSpec(),
        label = "labelFraction",
    )

    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .height(44.dp)
            .minimumTouchTarget()
            .animateWidth(interactionSource),
        interactionSource = interactionSource,
        shape = shape,
        contentPadding = PaddingValues(horizontal = 10.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(16.dp),
        )
        // Keep text always in composition — animate width to 0 via layout
        // so there's no sudden jump when content is removed
        Row(
            modifier = Modifier
                .graphicsLayer { alpha = labelFraction }
                .clipToBounds()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val w = (placeable.width * labelFraction).roundToInt()
                    layout(w, placeable.height) {
                        placeable.placeRelative(0, 0)
                    }
                },
        ) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun rememberNowPlayingButtonGroupInteractionSource() =
    remember { MutableInteractionSource() }

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
        val playInteractionSource = remember { MutableInteractionSource() }
        val nextInteractionSource = remember { MutableInteractionSource() }
        PlaybackControls(
            isPlaying = false,
            onTogglePlayPause = {},
            onSkipNext = {},
            onSkipPrevious = {},
            positionMs = 96_000L,
            durationMs = 240_000L,
            progress = 0.4f,
            buffered = 0.7f,
            onSeek = {},
            playInteractionSource = playInteractionSource,
            nextInteractionSource = nextInteractionSource,
            playPressed = false,
            nextPressed = false,
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
