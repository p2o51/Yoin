package com.gpo.yoin.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.navigation.YoinSection
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotionSpeed
import kotlinx.coroutines.delay

/**
 * Floating navigation/playback group. Visually a Material 3 Expressive connected
 * button group, but laid out with a plain `Row` (see the comment at the Row for
 * why `ButtonGroup` had to go). The selection pill and the press-to-expand /
 * neighbour-compress reaction — the expressive `animateWidth` feel — are
 * hand-rolled here on top of layout weight + aspect ratio, which stays
 * crash-safe under the foldable shared-transition lookahead.
 *
 * Two chrome modes, morphed in place ([detailChrome]):
 *  - Nav (shell): [Home] [now-playing pill] [Library]
 *  - Detail: [Play split button] [shorter now-playing pill]
 * The shell flips to Detail the moment a detail launch is tapped, so the
 * morph IS the tap feedback; the detail window then fades in over the settled
 * morph onto its own pixel-identical [DetailBottomBar]. The split button here
 * is purely the morph visual — its real, functional twin lives in the detail
 * window that is already fading in above.
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
        val slotSizeSpec = YoinMotion.defaultSpatialSpec<IntSize>()

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

            // LEFT SLOT — Home ⇄ Play-split morph. AnimatedContent's size
            // transform grows the slot from the 48dp icon to the split
            // button's intrinsic width; the weighted pill absorbs the rest.
            AnimatedContent(
                targetState = detailChrome,
                transitionSpec = {
                    (
                        YoinMotion.fadeIn(YoinMotionRole.Standard, YoinMotionSpeed.Fast) +
                            YoinMotion.scaleIn(YoinMotionRole.Standard, initialScale = 0.85f)
                        ).togetherWith(
                            YoinMotion.fadeOut(YoinMotionRole.Standard, YoinMotionSpeed.Fast) +
                                YoinMotion.scaleOut(YoinMotionRole.Standard, targetScale = 0.85f),
                        ).using(SizeTransform(clip = false) { _, _ -> slotSizeSpec })
                },
                label = "barLeftSlot",
            ) { inDetail ->
                if (inDetail) {
                    // Morph visual only: the functional split button lives in
                    // the detail window fading in above this one.
                    PlaySplitButton(
                        playContainer = MaterialTheme.colorScheme.primary,
                        playContent = MaterialTheme.colorScheme.onPrimary,
                        onPlay = {},
                        onShuffle = {},
                        buttonHeight = FloatingBarButtonHeight,
                    )
                } else {
                    FilledIconButton(
                        onClick = {
                            haptics.performClick()
                            onHomeClick()
                        },
                        modifier = Modifier
                            .height(FloatingBarButtonHeight)
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
            }

            // CENTER — the now-playing pill; the only weighted child, so it
            // absorbs whatever the side slots release. Detail mode pads its
            // start so the pill reads as its own island beside the split
            // button (mirrors DetailBottomBar's gap).
            val pillStartGap by animateDpAsState(
                targetValue = if (detailChrome) DetailBarPillGapInShell else 0.dp,
                animationSpec = YoinMotion.defaultSpatialSpec(),
                label = "pillStartGap",
            )
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
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = pillStartGap),
            )

            // RIGHT SLOT — Library, collapses away in detail chrome.
            AnimatedVisibility(
                visible = !detailChrome,
                enter = YoinMotion.expandHorizontally(role = YoinMotionRole.Standard) +
                    YoinMotion.fadeIn(role = YoinMotionRole.Standard),
                exit = YoinMotion.shrinkHorizontally(role = YoinMotionRole.Standard) +
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard),
            ) {
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
                        .height(FloatingBarButtonHeight)
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

// Mirrors DetailBottomBar's DetailBarPillGap — kept as a local constant so
// ui/component doesn't depend on ui/detail; change the two together.
private val DetailBarPillGapInShell = 16.dp
