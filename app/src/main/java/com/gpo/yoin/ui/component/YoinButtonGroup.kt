package com.gpo.yoin.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.navigation.rememberActiveOnlySharedContentConfig
import com.gpo.yoin.ui.navigation.nowPlayingCoverSharedKey
import com.gpo.yoin.ui.navigation.YoinSection
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * Floating navigation/playback group. Visually a Material 3 Expressive connected
 * button group, but laid out with a plain `Row` (see the comment at the Row for
 * why `ButtonGroup` had to go). The selection pill and the press-to-expand /
 * neighbour-compress reaction — the expressive `animateWidth` feel — are
 * hand-rolled here on top of layout weight + aspect ratio, which stays
 * crash-safe under the foldable shared-transition lookahead.
 */
@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
fun YoinButtonGroup(
    selectedSection: YoinSection,
    currentTrackId: String?,
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
    onLibraryLongClick: () -> Unit = onLibraryClick,
    // Reports the pill Surface's window bounds + rendered color — the source
    // geometry for the Button-Group → detail dock morph (see DetailDockMorph).
    onPillGeometryChanged: (Rect, Color) -> Unit = { _, _ -> },
    // Reports the mini artwork's window bounds — the morph cover's origin.
    onPillArtBoundsChanged: (Rect) -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        val haptics = rememberYoinHaptics()
        val surfaceColor by animateColorAsState(
            targetValue = MaterialTheme.colorScheme.surfaceContainerHigh,
            animationSpec = YoinMotion.defaultEffectsSpec(),
            label = "buttonGroupSurfaceColor",
        )
        val progressFillColor by animateColorAsState(
            targetValue = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
            animationSpec = YoinMotion.defaultEffectsSpec(),
            label = "buttonGroupProgressFill",
        )
        val homeContainerColor by animateColorAsState(
            targetValue = if (selectedSection == YoinSection.HOME) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
            animationSpec = YoinMotion.defaultEffectsSpec(),
            label = "buttonGroupHomeContainer",
        )
        val homeContentColor by animateColorAsState(
            targetValue = if (selectedSection == YoinSection.HOME) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            animationSpec = YoinMotion.defaultEffectsSpec(),
            label = "buttonGroupHomeContent",
        )
        val centerContainerColor by animateColorAsState(
            targetValue = if (currentTrackTitle != null) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
            animationSpec = YoinMotion.defaultEffectsSpec(),
            label = "buttonGroupCenterContainer",
        )
        val centerContentColor by animateColorAsState(
            targetValue = if (currentTrackTitle != null) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            animationSpec = YoinMotion.defaultEffectsSpec(),
            label = "buttonGroupCenterContent",
        )
        val libraryContainerColor by animateColorAsState(
            targetValue = if (selectedSection == YoinSection.LIBRARY) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
            animationSpec = YoinMotion.defaultEffectsSpec(),
            label = "buttonGroupLibraryContainer",
        )
        val libraryContentColor by animateColorAsState(
            targetValue = if (selectedSection == YoinSection.LIBRARY) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            animationSpec = YoinMotion.defaultEffectsSpec(),
            label = "buttonGroupLibraryContent",
        )
        val sharedBoundsSpec = YoinMotion.defaultSpatialSpec<Rect>(
            role = YoinMotionRole.Standard,
            expressiveScheme = MaterialTheme.motionScheme,
        )
        var showLibrarySearchHint by remember { mutableStateOf(false) }

        Box(
            modifier = modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        onPillGeometryChanged(it.boundsInWindow(), surfaceColor)
                    },
                shape = MaterialTheme.shapes.extraLarge,
                color = surfaceColor,
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
            ) {
                // Interaction sources are hoisted so the press of any one button
                // can drive the widths of the others (neighbour compression).
                val homeInteraction = rememberButtonGroupInteractionSource()
                val centerInteraction = rememberButtonGroupInteractionSource()
                val libraryInteraction = rememberButtonGroupInteractionSource()
                val homePressed by homeInteraction.collectIsPressedAsState()
                val centerPressed by centerInteraction.collectIsPressedAsState()
                val libraryPressed by libraryInteraction.collectIsPressedAsState()

                // Selection affordance: the active tab settles into a wider pill.
                // Soft spatial spring — this is a state change, not a touch echo.
                val homeSelectionAspect by animateFloatAsState(
                    targetValue = if (selectedSection == YoinSection.HOME) SELECTED_ASPECT else BASE_ASPECT,
                    animationSpec = YoinMotion.defaultSpatialSpec(),
                    label = "homeSelectionAspect",
                )
                val librarySelectionAspect by animateFloatAsState(
                    targetValue = if (selectedSection == YoinSection.LIBRARY) SELECTED_ASPECT else BASE_ASPECT,
                    animationSpec = YoinMotion.defaultSpatialSpec(),
                    label = "librarySelectionAspect",
                )
                // Press affordance: the pressed icon button widens and the centre
                // (the weighted filler) absorbs it — the M3 expressive
                // `animateWidth` bulge — while pressing the centre nudges both
                // icon buttons narrower so it grows in turn. Quick spring so it
                // reads as a direct touch echo. Driven through aspect/weight, so
                // it can never produce the negative child width that crashed the
                // real ButtonGroup under the foldable lookahead.
                val homePressDelta by animateFloatAsState(
                    targetValue = (if (homePressed) PRESS_EXPAND else 0f) -
                        (if (centerPressed) NEIGHBOUR_SQUEEZE else 0f),
                    animationSpec = YoinMotion.fastSpatialSpec(),
                    label = "homePressDelta",
                )
                val libraryPressDelta by animateFloatAsState(
                    targetValue = (if (libraryPressed) PRESS_EXPAND else 0f) -
                        (if (centerPressed) NEIGHBOUR_SQUEEZE else 0f),
                    animationSpec = YoinMotion.fastSpatialSpec(),
                    label = "libraryPressDelta",
                )
                val homeAspect = (homeSelectionAspect + homePressDelta).coerceAtLeast(MIN_ASPECT)
                val libraryAspect = (librarySelectionAspect + libraryPressDelta).coerceAtLeast(MIN_ASPECT)
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
                    animationSpec = YoinMotion.defaultSpatialSpec(),
                    label = "waveAmplitude",
                )

                // Plain Row instead of the M3 ButtonGroup. ButtonGroupMeasurePolicy
                // does an asymmetric neighbour-compression (widths[i-1]/[i+1] -=
                // growth) that, under the Pixel Fold's shared-transition LOOKAHEAD
                // (degenerate maxWidth + the weighted centre item), drives a child
                // width NEGATIVE and throws in Constraints.copy. A Row never does
                // that. (animateWidth was a ButtonGroupScope extension; dropped.)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    run {
                        FilledIconButton(
                            onClick = {
                                haptics.performClick()
                                onHomeClick()
                            },
                            modifier = Modifier
                                .fillMaxHeight()
                                .aspectRatio(homeAspect),
                            interactionSource = homeInteraction,
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
                    }

                    run {
                        val clampedProgress = playbackProgress.coerceIn(0f, 1f)

                        FilledTonalButton(
                            onClick = {
                                haptics.performContextClick()
                                onNowPlayingClick()
                            },
                            modifier = Modifier
                                .weight(1.65f)
                                .fillMaxHeight(),
                            interactionSource = centerInteraction,
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
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    NowPlayingArtwork(
                                        currentTrackId = currentTrackId,
                                        currentTrackCoverArtUrl = currentTrackCoverArtUrl,
                                        currentTrackTitle = currentTrackTitle,
                                        sharedTransitionScope = sharedTransitionScope,
                                        animatedVisibilityScope = animatedVisibilityScope,
                                        modifier = Modifier.onGloballyPositioned {
                                            onPillArtBoundsChanged(it.boundsInWindow())
                                        },
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
                                            color = centerContentColor,
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
                                            color = centerContentColor.copy(alpha = 0.72f),
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = marqueeArtistModifier,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    run {
                        LaunchedEffect(libraryPressed) {
                            if (libraryPressed) {
                                delay(LIBRARY_SEARCH_HINT_DELAY_MS)
                                showLibrarySearchHint = true
                            } else {
                                delay(LIBRARY_SEARCH_HINT_SETTLE_MS)
                                showLibrarySearchHint = false
                            }
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxHeight()
                                .aspectRatio(libraryAspect)
                                .combinedClickable(
                                    interactionSource = libraryInteraction,
                                    indication = null,
                                    onClick = {
                                        haptics.performClick()
                                        onLibraryClick()
                                    },
                                    onLongClick = {
                                        showLibrarySearchHint = true
                                        haptics.performContextClick()
                                        onLibraryLongClick()
                                    },
                                ),
                            shape = MaterialTheme.shapes.extraLarge,
                            color = libraryContainerColor,
                            contentColor = libraryContentColor,
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.LibraryMusic,
                                    contentDescription = "Library",
                                )
                            }
                        }
                    }
                }
            }
            LibrarySearchShortcutHint(
                visible = showLibrarySearchHint,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 26.dp)
                    .offset(y = (-46).dp),
            )
        }
    }
}

@Composable
private fun LibrarySearchShortcutHint(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = YoinMotion.scaleIn(
            role = YoinMotionRole.Standard,
            initialScale = 0.68f,
        ) + YoinMotion.fadeIn(role = YoinMotionRole.Standard),
        exit = YoinMotion.scaleOut(
            role = YoinMotionRole.Standard,
            targetScale = 0.82f,
        ) + YoinMotion.fadeOut(role = YoinMotionRole.Standard),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.size(46.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.primary,
                tonalElevation = 8.dp,
                shadowElevation = 16.dp,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search shortcut",
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberButtonGroupInteractionSource() =
    remember { MutableInteractionSource() }

// Icon-button width is height × aspect. Selection swaps the resting aspect;
// press adds a transient delta. See the press/selection comment in YoinButtonGroup.
private const val BASE_ASPECT = 1f
private const val SELECTED_ASPECT = 1.5f
private const val PRESS_EXPAND = 0.25f
private const val NEIGHBOUR_SQUEEZE = 0.12f
private const val MIN_ASPECT = 0.7f

private const val LIBRARY_SEARCH_HINT_DELAY_MS = 240L
private const val LIBRARY_SEARCH_HINT_SETTLE_MS = 120L

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun NowPlayingArtwork(
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
