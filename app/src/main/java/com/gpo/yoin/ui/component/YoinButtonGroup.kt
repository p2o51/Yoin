package com.gpo.yoin.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.navigation.YoinSection
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlinx.coroutines.delay

/**
 * Floating navigation/playback group. Visually a Material 3 Expressive
 * connected button group, hand-rolled on plain Row/Box with dp-interpolated
 * slot widths: the bar lives inside the shell's SharedTransitionLayout, whose
 * lookahead pass hangs/crashes exotic multi-child measure policies (M3
 * ButtonGroup's neighbour compression, SplitButtonLayout, AnimatedContent
 * size transforms) whenever the pill's shared elements are active. Plain
 * layouts + animateFloatAsState survive it; keep it that way.
 *
 * Two chrome modes morphed in place by [detailChrome]:
 *  - Nav (shell): [Home] [now-playing pill (fills)] [Library]
 *  - Detail: [Play split (fills)] [now-playing pill (~25%)]
 * The shell flips to Detail the moment a detail launch is tapped, so the
 * morph IS the tap feedback; the detail window then fades in over the settled
 * morph onto its own pixel-identical DetailBottomBar. The split button here
 * is purely the morph visual — the functional twin lives in the detail
 * window already fading in above.
 */
@OptIn(
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
    detailChrome: Boolean = false,
    onHomeClick: () -> Unit,
    onNowPlayingClick: () -> Unit,
    onLibraryClick: () -> Unit,
    onLibraryLongClick: () -> Unit = onLibraryClick,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        val haptics = rememberYoinHaptics()
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
        var showLibrarySearchHint by remember { mutableStateOf(false) }

        FloatingBottomBar(
            modifier = modifier,
            overlay = {
                LibrarySearchShortcutHint(
                    visible = showLibrarySearchHint,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 26.dp)
                        .offset(y = (-46).dp),
                )
            },
        ) { innerWidth ->
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
            // Press affordance: the pressed icon button widens and the pill
            // absorbs it — the M3 expressive `animateWidth` bulge — while
            // pressing the pill nudges both icon buttons narrower so it grows
            // in turn. Quick spring so it reads as a direct touch echo.
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

            // The nav⇄detail morph, one progress value driving every slot
            // width. All plain Row/Box + width(dp) — see the class KDoc for
            // why nothing fancier is allowed in here.
            val morph by animateFloatAsState(
                targetValue = if (detailChrome) 1f else 0f,
                animationSpec = YoinMotion.defaultSpatialSpec(),
                label = "barChromeMorph",
            )
            val homeWidth = FloatingBarButtonHeight * homeAspect
            val libraryWidth = FloatingBarButtonHeight * libraryAspect
            val pillNavWidth =
                innerWidth - homeWidth - libraryWidth - FloatingBarItemGap * 2
            val pillDetailWidth =
                (innerWidth * FloatingBarDetailPillFraction)
                    .coerceAtLeast(FloatingBarDetailPillMinWidth)
            val pillWidth = lerp(pillNavWidth, pillDetailWidth, morph)
            val leftWidth = lerp(
                homeWidth,
                innerWidth - pillWidth - FloatingBarItemGap,
                morph,
            )
            // Library slot carries its own leading gap so both collapse to 0.
            val rightWidth = lerp(FloatingBarItemGap + libraryWidth, 0.dp, morph)
            val navAlpha = (1f - morph / 0.6f).coerceIn(0f, 1f)
            val splitAlpha = ((morph - 0.4f) / 0.6f).coerceIn(0f, 1f)

            // LEFT SLOT — Home fading out beneath the stretching Play split.
            Box(
                modifier = Modifier
                    .width(leftWidth)
                    .height(FloatingBarButtonHeight)
                    .clipToBounds(),
            ) {
                if (morph < 0.99f) {
                    FilledIconButton(
                        onClick = {
                            haptics.performClick()
                            onHomeClick()
                        },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(homeWidth)
                            .fillMaxHeight()
                            .graphicsLayer { alpha = navAlpha },
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
                if (morph > 0.01f) {
                    // Morph visual only: the functional split button lives in
                    // the detail window fading in above this one.
                    PlaySplitButton(
                        playContainer = MaterialTheme.colorScheme.primary,
                        playContent = MaterialTheme.colorScheme.onPrimary,
                        onPlay = {},
                        onShuffle = {},
                        buttonHeight = FloatingBarButtonHeight,
                        fillPlay = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { alpha = splitAlpha },
                    )
                }
            }

            Spacer(modifier = Modifier.width(FloatingBarItemGap))

            // CENTER — the now-playing pill, absorbing whatever the sides
            // release.
            NowPlayingPill(
                currentTrackId = currentTrackId,
                currentTrackTitle = currentTrackTitle,
                currentTrackArtist = currentTrackArtist,
                currentTrackCoverArtUrl = currentTrackCoverArtUrl,
                connectionErrorMessage = connectionErrorMessage,
                playbackProgress = playbackProgress,
                isPlaying = isPlaying,
                onClick = onNowPlayingClick,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                interactionSource = centerInteraction,
                modifier = Modifier
                    .width(pillWidth)
                    .fillMaxHeight(),
            )

            // RIGHT SLOT — Library (with its leading gap), collapsing away
            // in detail chrome.
            Box(
                modifier = Modifier
                    .width(rightWidth)
                    .height(FloatingBarButtonHeight)
                    .clipToBounds(),
            ) {
                if (morph < 0.99f) {
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
                            .align(Alignment.CenterEnd)
                            .width(libraryWidth)
                            .fillMaxHeight()
                            .graphicsLayer { alpha = navAlpha }
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
