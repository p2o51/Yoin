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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Text
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
 * Two chrome modes morphed in place by [chromeProgress]:
 *  - Nav (0): [Home] [now-playing pill (fills)] [Library]
 *  - Detail (1): [Play split (fills)] [now-playing pill (~25%)]
 * The pose is HOSTED — this composable never animates it, it only renders the
 * value, so exactly one driver exists per window (the shell's
 * rememberShellBarChromeMorph, or a detail page's predictive-back scrub).
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
    // Chrome pose (0 = nav, 1 = detail), read per frame. Animated OR
    // gesture-scrubbed by the caller — never in here, so a predictive-back
    // scrub and the settle springs can share one Animatable per window.
    chromeProgress: () -> Float = { 0f },
    // Functional Play split for the detail windows; null = the shell's
    // decorative theme-colored stand-in.
    playSplitActions: BarPlaySplitActions? = null,
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

            // IDLE pose (nothing playing, no error): the pill cedes the bar
            // to two labeled halves — [icon Home] [icon Library] — and the
            // whole thing springs back to the pill layout the moment a track
            // lands. Same dp-lerp language as the detail morph (the pill's
            // nav width simply collapses to 0 as the halves grow), so the two
            // poses compose: idle is applied to the NAV endpoints first, then
            // the chrome morph lerps toward detail as usual.
            val idle = currentTrackTitle == null && connectionErrorMessage == null
            val idleProgress by animateFloatAsState(
                targetValue = if (idle) 1f else 0f,
                animationSpec = YoinMotion.defaultSpatialSpec(),
                label = "barIdleProgress",
            )

            // The nav⇄detail morph, one progress value driving every slot
            // width. All plain Row/Box + width(dp) — see the class KDoc for
            // why nothing fancier is allowed in here.
            val morph = chromeProgress().coerceIn(0f, 1f)
            val idleHalf = (innerWidth - FloatingBarItemGap * 2) / 2
            val homeWidth = lerp(FloatingBarButtonHeight * homeAspect, idleHalf, idleProgress)
            val libraryWidth = lerp(FloatingBarButtonHeight * libraryAspect, idleHalf, idleProgress)
            val pillNavWidth =
                innerWidth - homeWidth - libraryWidth - FloatingBarItemGap * 2
            val pillDetailWidth =
                innerWidth - FloatingBarSplitWidth - FloatingBarItemGap
            val pillWidth = lerp(pillNavWidth, pillDetailWidth, morph)
            val leftWidth = lerp(homeWidth, FloatingBarSplitWidth, morph)
            // Library slot carries its own leading gap so both collapse to 0.
            val rightWidth = lerp(FloatingBarItemGap + libraryWidth, 0.dp, morph)
            val navAlpha = (1f - morph / 0.6f).coerceIn(0f, 1f)
            val splitAlpha = ((morph - 0.4f) / 0.6f).coerceIn(0f, 1f)
            // Label reveal rides the tail of the width spring; fade the pill
            // out fast so the squeeze never shows crushed content.
            val idleLabelAlpha = ((idleProgress - 0.55f) / 0.45f).coerceIn(0f, 1f)
            val pillIdleAlpha = (1f - idleProgress / 0.5f).coerceIn(0f, 1f)

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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Home,
                                contentDescription = "Home",
                            )
                            if (idleLabelAlpha > 0.01f) {
                                Text(
                                    text = "Home",
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.graphicsLayer { alpha = idleLabelAlpha },
                                )
                            }
                        }
                    }
                }
                if (morph > 0.01f) {
                    // Functional on detail pages; decorative in the shell
                    // (where the real twin lives in the window fading in above).
                    PlaySplitButton(
                        playContainer = playSplitActions?.playContainer
                            ?: MaterialTheme.colorScheme.primary,
                        playContent = playSplitActions?.playContent
                            ?: MaterialTheme.colorScheme.onPrimary,
                        onPlay = playSplitActions?.onPlay ?: {},
                        onShuffle = playSplitActions?.onShuffle ?: {},
                        buttonHeight = FloatingBarButtonHeight,
                        fillPlay = true,
                        compact = true,
                        trailingMenuItems = playSplitActions?.menuItems ?: {},
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(FloatingBarSplitWidth)
                            .graphicsLayer { alpha = splitAlpha },
                    )
                }
            }

            Spacer(modifier = Modifier.width(FloatingBarItemGap))

            // CENTER — the now-playing pill, absorbing whatever the sides
            // release. Fully idle (and not in detail chrome) = not composed:
            // its 0dp slot would still marquee and hit-test, and an idle tap
            // opening the "Nothing playing" page is exactly what the idle
            // pose exists to retire.
            if (idleProgress < 0.995f || morph > 0.005f) {
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
                        .fillMaxHeight()
                        .graphicsLayer {
                            alpha = if (morph > 0.005f) 1f else pillIdleAlpha
                        },
                )
            }

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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.LibraryMusic,
                                    contentDescription = "Library",
                                )
                                if (idleLabelAlpha > 0.01f) {
                                    Text(
                                        text = "Library",
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.graphicsLayer { alpha = idleLabelAlpha },
                                    )
                                }
                            }
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

/** Functional Play-split wiring for the detail windows' bar. */
class BarPlaySplitActions(
    val playContainer: androidx.compose.ui.graphics.Color,
    val playContent: androidx.compose.ui.graphics.Color,
    val onPlay: () -> Unit,
    val onShuffle: () -> Unit,
    val menuItems: @Composable androidx.compose.foundation.layout.ColumnScope.(dismissMenu: () -> Unit) -> Unit,
)
