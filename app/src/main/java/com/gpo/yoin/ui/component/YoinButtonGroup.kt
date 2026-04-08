package com.gpo.yoin.ui.component

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.navigation.YoinSection
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinShapeTokens
import kotlin.math.sin

/**
 * Floating navigation/playback group built on the official Material 3 Expressive
 * `ButtonGroup` API. The container itself is custom, but item interaction, pressed
 * width animation and selection affordances stay inside the official MD3 system.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun YoinButtonGroup(
    selectedSection: YoinSection,
    currentTrackTitle: String?,
    currentTrackArtist: String?,
    currentTrackCoverArtUrl: String?,
    isPlaybackReady: Boolean,
    connectionErrorMessage: String?,
    playbackProgress: Float = 0f,
    isPlaying: Boolean = false,
    onHomeClick: () -> Unit,
    onNowPlayingClick: () -> Unit,
    onLibraryClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val surfaceColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = YoinMotion.effectsSpring(),
        label = "buttonGroupSurfaceColor",
    )
    val progressFillColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
        animationSpec = YoinMotion.effectsSpring(),
        label = "buttonGroupProgressFill",
    )
    val homeContainerColor by animateColorAsState(
        targetValue = if (selectedSection == YoinSection.HOME) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "buttonGroupHomeContainer",
    )
    val homeContentColor by animateColorAsState(
        targetValue = if (selectedSection == YoinSection.HOME) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "buttonGroupHomeContent",
    )
    val centerContainerColor by animateColorAsState(
        targetValue = if (currentTrackTitle != null) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "buttonGroupCenterContainer",
    )
    val centerContentColor by animateColorAsState(
        targetValue = if (currentTrackTitle != null) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "buttonGroupCenterContent",
    )
    val libraryContainerColor by animateColorAsState(
        targetValue = if (selectedSection == YoinSection.LIBRARY) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "buttonGroupLibraryContainer",
    )
    val libraryContentColor by animateColorAsState(
        targetValue = if (selectedSection == YoinSection.LIBRARY) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "buttonGroupLibraryContent",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = surfaceColor,
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
    ) {
        // Animate selected tab aspect ratio: 1.5:1 selected, 1:1 unselected
        val homeAspect by animateFloatAsState(
            targetValue = if (selectedSection == YoinSection.HOME) 1.5f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "homeAspect",
        )
        val libraryAspect by animateFloatAsState(
            targetValue = if (selectedSection == YoinSection.LIBRARY) 1.5f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "libraryAspect",
        )

        // Wave animation for progress divider
        val waveTransition = rememberInfiniteTransition(label = "wave")
        val wavePhase by waveTransition.animateFloat(
            initialValue = 0f,
            targetValue = 2f * Math.PI.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "wavePhase",
        )
        val waveAmplitude by animateFloatAsState(
            targetValue = if (isPlaying) 1f else 0f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "waveAmplitude",
        )

        ButtonGroup(
            overflowIndicator = { _ -> },
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            expandedRatio = ButtonGroupDefaults.ExpandedRatio,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            customItem(
                buttonGroupContent = {
                    val interactionSource = rememberButtonGroupInteractionSource()
                    FilledIconButton(
                        onClick = onHomeClick,
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(homeAspect)
                            .animateWidth(interactionSource),
                        interactionSource = interactionSource,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = homeContainerColor,
                            contentColor = homeContentColor,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = "Home",
                        )
                    }
                },
                menuContent = { _ ->
                    Icon(
                        imageVector = Icons.Filled.Home,
                        contentDescription = "Home",
                    )
                },
            )

            customItem(
                buttonGroupContent = {
                    val interactionSource = rememberButtonGroupInteractionSource()
                    val clampedProgress = playbackProgress.coerceIn(0f, 1f)

                    FilledTonalButton(
                        onClick = onNowPlayingClick,
                        modifier = Modifier
                            .weight(1.65f)
                            .fillMaxHeight()
                            .animateWidth(interactionSource)
                            .animateContentSize(animationSpec = YoinMotion.spatialSpring()),
                        interactionSource = interactionSource,
                        shape = MaterialTheme.shapes.extraLarge,
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = centerContainerColor,
                            contentColor = centerContentColor,
                        ),
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            if (currentTrackTitle != null && clampedProgress > 0f) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clip(MaterialTheme.shapes.extraLarge)
                                        .drawWithContent {
                                            drawContent()
                                            val w = size.width
                                            val h = size.height
                                            val progressX = w * clampedProgress
                                            val amp = 4.dp.toPx() * waveAmplitude
                                            val waveSteps = 20

                                            val path = Path().apply {
                                                moveTo(0f, 0f)
                                                lineTo(progressX, 0f)
                                                // Right edge: wavy line down
                                                for (i in 0..waveSteps) {
                                                    val fraction = i.toFloat() / waveSteps
                                                    val y = fraction * h
                                                    val dx = sin(
                                                        wavePhase +
                                                            fraction * 2f *
                                                            Math.PI.toFloat(),
                                                    ) * amp
                                                    lineTo(progressX + dx, y)
                                                }
                                                    lineTo(0f, h)
                                                    close()
                                                }
                                            drawPath(path, progressFillColor)
                                        },
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                NowPlayingArtwork(
                                    currentTrackCoverArtUrl = currentTrackCoverArtUrl,
                                    currentTrackTitle = currentTrackTitle,
                                    sharedTransitionScope = sharedTransitionScope,
                                    animatedVisibilityScope = animatedVisibilityScope,
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    val titleText = currentTrackTitle ?: when {
                                        connectionErrorMessage != null -> "Playback unavailable"
                                        else -> "Nothing playing"
                                    }
                                    val artistText = currentTrackArtist ?: when {
                                        connectionErrorMessage != null -> connectionErrorMessage
                                        else -> "Tap to open player"
                                    }

                                    val titleMod = if (
                                        sharedTransitionScope != null &&
                                        animatedVisibilityScope != null &&
                                        currentTrackTitle != null
                                    ) {
                                        with(sharedTransitionScope) {
                                            Modifier.sharedBounds(
                                                sharedContentState = rememberSharedContentState(
                                                    key = "np_title",
                                                ),
                                                animatedVisibilityScope = animatedVisibilityScope,
                                                boundsTransform = { _, _ ->
                                                    spring(stiffness = Spring.StiffnessMediumLow)
                                                },
                                            )
                                        }
                                    } else {
                                        Modifier
                                    }
                                    val marqueeModifier = if (currentTrackTitle != null) {
                                        titleMod.basicMarquee(
                                            iterations = Int.MAX_VALUE,
                                            repeatDelayMillis = 2000,
                                            initialDelayMillis = 1500,
                                        )
                                    } else {
                                        titleMod
                                    }
                                    Text(
                                        text = titleText,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = centerContentColor,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = marqueeModifier,
                                    )

                                    val artistMod = if (
                                        sharedTransitionScope != null &&
                                        animatedVisibilityScope != null &&
                                        currentTrackArtist != null
                                    ) {
                                        with(sharedTransitionScope) {
                                            Modifier.sharedBounds(
                                                sharedContentState = rememberSharedContentState(
                                                    key = "np_artist",
                                                ),
                                                animatedVisibilityScope = animatedVisibilityScope,
                                                boundsTransform = { _, _ ->
                                                    spring(stiffness = Spring.StiffnessMediumLow)
                                                },
                                            )
                                        }
                                    } else {
                                        Modifier
                                    }
                                    val artistMarqueeMod = if (currentTrackArtist != null) {
                                        artistMod.basicMarquee(
                                            iterations = Int.MAX_VALUE,
                                            repeatDelayMillis = 2000,
                                            initialDelayMillis = 2500,
                                        )
                                    } else {
                                        artistMod
                                    }
                                    Text(
                                        text = artistText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = centerContentColor.copy(alpha = 0.72f),
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = artistMarqueeMod,
                                    )
                                }
                            }
                        }
                    }
                },
                menuContent = { _ -> },
            )

            customItem(
                buttonGroupContent = {
                    val interactionSource = rememberButtonGroupInteractionSource()
                    FilledIconButton(
                        onClick = onLibraryClick,
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(libraryAspect)
                            .animateWidth(interactionSource),
                        interactionSource = interactionSource,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = libraryContainerColor,
                            contentColor = libraryContentColor,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LibraryMusic,
                            contentDescription = "Library",
                        )
                    }
                },
                menuContent = { _ ->
                    Icon(
                        imageVector = Icons.Filled.LibraryMusic,
                        contentDescription = "Library",
                    )
                },
            )
        }
    }
}

@Composable
private fun rememberButtonGroupInteractionSource() =
    remember { MutableInteractionSource() }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun NowPlayingArtwork(
    currentTrackCoverArtUrl: String?,
    currentTrackTitle: String?,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val baseModifier = modifier.size(34.dp)
    val finalModifier = if (
        sharedTransitionScope != null &&
        animatedVisibilityScope != null &&
        currentTrackCoverArtUrl != null
    ) {
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

    ExpressiveMediaArtwork(
        model = currentTrackCoverArtUrl,
        contentDescription = currentTrackTitle ?: "Current track",
        modifier = finalModifier,
        shape = YoinShapeTokens.Small,
        fallbackIcon = Icons.Filled.MusicNote,
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
    )
}
