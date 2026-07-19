package com.gpo.yoin.ui.component

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.navigation.nowPlayingCoverSharedKey
import com.gpo.yoin.ui.navigation.rememberActiveOnlySharedContentConfig
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.math.sin

/**
 * The now-playing pill: mini artwork + marquee title/artist over a sine-edged
 * playback-progress wash. Extracted from the shell Button Group so the detail
 * pages' bottom bar renders the EXACT same pill — the two windows' bars must
 * be pixel twins for the shell⇄detail hand-off to read as one persistent bar.
 *
 * The shared-element hooks (cover / np_title / np_artist) only engage when the
 * caller passes both scopes — the shell does, detail Activities don't.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun NowPlayingPill(
    currentTrackId: String?,
    currentTrackTitle: String?,
    currentTrackArtist: String?,
    currentTrackCoverArtUrl: String?,
    connectionErrorMessage: String?,
    playbackProgress: Float,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val haptics = rememberYoinHaptics()
    val containerColor by animateColorAsState(
        targetValue = if (currentTrackTitle != null) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "nowPlayingPillContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (currentTrackTitle != null) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "nowPlayingPillContent",
    )
    val progressFillColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "nowPlayingPillProgressFill",
    )
    val waveTransition = rememberInfiniteTransition(label = "nowPlayingPillWave")
    val wavePhase by waveTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "nowPlayingPillWavePhase",
    )
    val waveAmplitude by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = YoinMotion.defaultSpatialSpec(),
        label = "nowPlayingPillWaveAmplitude",
    )
    val sharedBoundsSpec = YoinMotion.defaultSpatialSpec<Rect>(
        role = YoinMotionRole.Standard,
        expressiveScheme = MaterialTheme.motionScheme,
    )
    val clampedProgress = playbackProgress.coerceIn(0f, 1f)

    // Track-change pulse: every hand-off — manual skip, auto-advance, or a
    // remote device switching songs — bumps the pill content in from below
    // (snap down + fade, spring back), so the bar acknowledges the change
    // even when the player is closed. A plain graphicsLayer pulse, NOT
    // AnimatedContent: this bar lives under the shell's shared-transition
    // lookahead, which chokes on size-transforming containers (class KDoc of
    // YoinButtonGroup). First track after idle skips the pulse — the idle→
    // pill width morph is already the entrance.
    val trackPulse = remember { Animatable(0f) }
    val trackPulseSpec = YoinMotion.defaultSpatialSpec<Float>()
    var lastPulsedTrackId by remember { mutableStateOf(currentTrackId) }
    LaunchedEffect(currentTrackId) {
        val previous = lastPulsedTrackId
        lastPulsedTrackId = currentTrackId
        if (currentTrackId != null && previous != null && currentTrackId != previous) {
            trackPulse.snapTo(1f)
            trackPulse.animateTo(0f, trackPulseSpec)
        }
    }

    FilledTonalButton(
        onClick = {
            haptics.performContextClick()
            onClick()
        },
        modifier = modifier,
        interactionSource = interactionSource,
        shape = MaterialTheme.shapes.extraLarge,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        // Fill the whole button, not just the content: the bar forces a 48dp
        // height while the content row is ~42dp — a wrap-content Box here lets
        // the wave wash (matchParentSize below) shrink to the content and leave
        // a container-colored seam above/below it.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (currentTrackTitle != null && clampedProgress > 0f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(MaterialTheme.shapes.extraLarge)
                        .drawWithContent {
                            drawContent()
                            val width = size.width
                            val height = size.height
                            val progressX = width * clampedProgress
                            val amplitude = 4.dp.toPx() * waveAmplitude
                            val waveSteps = 20

                            val path = Path().apply {
                                moveTo(0f, 0f)
                                lineTo(progressX, 0f)
                                for (index in 0..waveSteps) {
                                    val fraction = index.toFloat() / waveSteps
                                    val y = fraction * height
                                    val dx = sin(
                                        wavePhase +
                                            fraction * 2f * Math.PI.toFloat(),
                                    ) * amplitude
                                    lineTo(progressX + dx, y)
                                }
                                lineTo(0f, height)
                                close()
                            }
                            drawPath(path, progressFillColor)
                        },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .graphicsLayer {
                        val pulse = trackPulse.value
                        translationY = 9.dp.toPx() * pulse
                        alpha = 1f - 0.55f * pulse
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NowPlayingPillArtwork(
                    currentTrackId = currentTrackId,
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

                    val titleModifier = if (
                        sharedTransitionScope != null &&
                        animatedVisibilityScope != null &&
                        currentTrackTitle != null
                    ) {
                        val sharedContentConfig =
                            rememberActiveOnlySharedContentConfig(
                                animatedVisibilityScope = animatedVisibilityScope,
                            )
                        with(sharedTransitionScope) {
                            Modifier.sharedBounds(
                                sharedContentState = rememberSharedContentState(
                                    key = "np_title",
                                    config = sharedContentConfig,
                                ),
                                animatedVisibilityScope = animatedVisibilityScope,
                                boundsTransform = { _, _ -> sharedBoundsSpec },
                            )
                        }
                    } else {
                        Modifier
                    }
                    val marqueeTitleModifier = if (currentTrackTitle != null) {
                        titleModifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            repeatDelayMillis = 2000,
                            initialDelayMillis = 1500,
                        )
                    } else {
                        titleModifier
                    }
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.labelLarge,
                        color = contentColor,
                        maxLines = 1,
                        softWrap = false,
                        modifier = marqueeTitleModifier,
                    )

                    val artistModifier = if (
                        sharedTransitionScope != null &&
                        animatedVisibilityScope != null &&
                        currentTrackArtist != null
                    ) {
                        val sharedContentConfig =
                            rememberActiveOnlySharedContentConfig(
                                animatedVisibilityScope = animatedVisibilityScope,
                            )
                        with(sharedTransitionScope) {
                            Modifier.sharedBounds(
                                sharedContentState = rememberSharedContentState(
                                    key = "np_artist",
                                    config = sharedContentConfig,
                                ),
                                animatedVisibilityScope = animatedVisibilityScope,
                                boundsTransform = { _, _ -> sharedBoundsSpec },
                            )
                        }
                    } else {
                        Modifier
                    }
                    val marqueeArtistModifier = if (currentTrackArtist != null) {
                        artistModifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            repeatDelayMillis = 2000,
                            initialDelayMillis = 2500,
                        )
                    } else {
                        artistModifier
                    }
                    Text(
                        text = artistText,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.72f),
                        maxLines = 1,
                        softWrap = false,
                        modifier = marqueeArtistModifier,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun NowPlayingPillArtwork(
    currentTrackId: String?,
    currentTrackCoverArtUrl: String?,
    currentTrackTitle: String?,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val baseModifier = modifier.size(34.dp)
    val sharedBoundsSpec = YoinMotion.defaultSpatialSpec<Rect>(
        role = YoinMotionRole.Standard,
        expressiveScheme = MaterialTheme.motionScheme,
    )
    val finalModifier = if (
        sharedTransitionScope != null &&
        animatedVisibilityScope != null &&
        currentTrackCoverArtUrl != null
    ) {
        val sharedContentConfig =
            rememberActiveOnlySharedContentConfig(animatedVisibilityScope = animatedVisibilityScope)
        with(sharedTransitionScope) {
            baseModifier.sharedBounds(
                sharedContentState = rememberSharedContentState(
                    key = nowPlayingCoverSharedKey(currentTrackId),
                    config = sharedContentConfig,
                ),
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = { _, _ -> sharedBoundsSpec },
            )
        }
    } else {
        baseModifier
    }

    ExpressiveMediaArtwork(
        model = currentTrackCoverArtUrl,
        contentDescription = currentTrackTitle ?: "Current track",
        modifier = finalModifier,
        shape = YoinArtworkShapes.ThumbAnimated,
        fallbackIcon = Icons.Filled.MusicNote,
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
    )
}
