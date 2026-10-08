package com.gpo.yoin.ui.component

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.experience.EdgeSplitGroupInset
import com.gpo.yoin.ui.experience.EdgeSplitGroupWidth
import com.gpo.yoin.ui.experience.EdgeSplitSegments
import com.gpo.yoin.ui.experience.computeEdgeSplitSegments
import com.gpo.yoin.ui.experience.rememberEdgeSplitSegments
import com.gpo.yoin.ui.experience.EdgeSplitSide
import com.gpo.yoin.ui.experience.rememberEdgeSplitEdgeShift
import com.gpo.yoin.ui.experience.rememberEdgeSplitSide
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.navigation.YoinSection
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.delay

/**
 * The Button Group for a short window (ShellChromeForm.EdgeSplit, 断点交接
 * §2.2): the portrait bar stood upright and split in two around the camera
 * cutout, both capsules hugging the left screen edge inside the cutout band.
 *
 *  - UPPER: [Home] [Library] (selected 82 tall, the other 56) — or, in detail
 *    chrome, the upright Play split, plus Shuffle when there is no cutout on
 *    this edge and the capsule has the extra slot ([EdgeSplitSegments.roomy]).
 *  - LOWER: the upright now-playing pill. Dragging Now Playing down lands back
 *    in it, exactly as in portrait (order "A").
 *
 * [chromeProgress] is the same hosted nav⇄detail pose as the portrait bar —
 * the upper capsule crossfades in place, the lower one never moves — so the
 * shell ↔ detail hand-off (and its predictive-back scrub) reads the same
 * way. Geometry comes from the live cutout rects, so both windows compute
 * identical capsules (§14.10). Plain Box/Column only: this lives under the
 * shell's SharedTransitionLayout lookahead (see YoinButtonGroup's KDoc).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun YoinEdgeSplitGroup(
    selectedSection: YoinSection,
    currentTrackId: String?,
    currentTrackTitle: String?,
    currentTrackArtist: String?,
    currentTrackCoverArtUrl: String?,
    connectionErrorMessage: String?,
    playbackProgress: Float = 0f,
    isPlaying: Boolean = false,
    chromeProgress: () -> Float = { 0f },
    playSplitActions: BarPlaySplitActions? = null,
    // Home edit pose: Home → Undo/Add, Library → Done, the pill capsule steps
    // out as when idle. Clicks route on the discrete [editing].
    editPose: BarEditPose? = null,
    editing: Boolean = false,
    onHomeClick: () -> Unit,
    onNowPlayingClick: () -> Unit,
    onLibraryClick: () -> Unit,
    onLibraryLongClick: () -> Unit = onLibraryClick,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    // Explicit geometry for previews/tests; the live window otherwise.
    segmentsOverride: EdgeSplitSegments? = null,
    // The cutout's edge; the live window otherwise.
    sideOverride: EdgeSplitSide? = null,
    modifier: Modifier = Modifier,
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val segments = segmentsOverride ?: rememberEdgeSplitSegments(windowHeight = maxHeight)
            val side = sideOverride ?: rememberEdgeSplitSide()
            val edgeShift = rememberEdgeSplitEdgeShift(side)
            // The capsules sit in the cutout's band, on whichever edge that is.
            val x = if (side == EdgeSplitSide.Right) {
                maxWidth - EdgeSplitGroupInset - edgeShift - EdgeSplitGroupWidth
            } else {
                EdgeSplitGroupInset + edgeShift
            }
            val idle = currentTrackTitle == null && connectionErrorMessage == null
            val idleProgress by animateFloatAsState(
                targetValue = if (idle) 1f else 0f,
                animationSpec = YoinMotion.defaultSpatialSpec(),
                label = "edgeSplitIdle",
            )

            EdgeCapsule(
                x = x,
                top = segments.upperTop,
                height = segments.upperHeight,
            ) { innerHeight ->
                UpperCapsuleContent(
                    innerHeight = innerHeight,
                    side = side,
                    roomy = segments.roomy,
                    selectedSection = selectedSection,
                    chromeProgress = chromeProgress,
                    playSplitActions = playSplitActions,
                    editPose = editPose,
                    editing = editing,
                    onHomeClick = onHomeClick,
                    onLibraryClick = onLibraryClick,
                    onLibraryLongClick = onLibraryLongClick,
                )
            }

            // Nothing playing, or Home being edited: the lower capsule steps
            // out (the portrait bar's idle pose retires its pill the same
            // way). Detail chrome keeps it.
            val morph = chromeProgress().coerceIn(0f, 1f)
            val idleLike = maxOf(idleProgress, editPose?.progress()?.coerceIn(0f, 1f) ?: 0f)
            if (idleLike < 0.995f || morph > 0.005f) {
                EdgeCapsule(
                    x = x,
                    top = segments.lowerTop,
                    height = segments.lowerHeight,
                    modifier = Modifier.graphicsLayer {
                        val hide = if (morph > 0.005f) 0f else idleLike
                        alpha = 1f - hide
                        // Steps out over its own edge.
                        translationX = if (side == EdgeSplitSide.Right) {
                            (maxWidth - x).toPx() * hide
                        } else {
                            -(EdgeSplitGroupWidth + x).toPx() * hide
                        }
                    },
                ) { innerHeight ->
                    NowPlayingPillVertical(
                        currentTrackId = currentTrackId,
                        currentTrackTitle = currentTrackTitle,
                        currentTrackArtist = currentTrackArtist,
                        currentTrackCoverArtUrl = currentTrackCoverArtUrl,
                        connectionErrorMessage = connectionErrorMessage,
                        playbackProgress = playbackProgress,
                        isPlaying = isPlaying,
                        onClick = onNowPlayingClick,
                        // Stepping out but still composed on the way into
                        // edit: no press, click or haptic.
                        enabled = !editing,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        modifier = Modifier
                            .width(EdgeSplitButtonWidth)
                            .height(innerHeight),
                    )
                }
            }
        }
    }
}

/** One capsule: the bar's container colour and tonal lift (no shadow, like the bar), 32dp ends. */
@Composable
private fun EdgeCapsule(
    x: Dp,
    top: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    content: @Composable (innerHeight: Dp) -> Unit,
) {
    val shape = RoundedCornerShape(EdgeSplitGroupWidth / 2)
    Surface(
        modifier = modifier
            .offset(x = x, y = top)
            .width(EdgeSplitGroupWidth)
            .height(height),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 8.dp,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(EdgeSplitCapsulePadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            content((height - EdgeSplitCapsulePadding * 2).coerceAtLeast(0.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UpperCapsuleContent(
    innerHeight: Dp,
    side: EdgeSplitSide,
    roomy: Boolean,
    selectedSection: YoinSection,
    chromeProgress: () -> Float,
    playSplitActions: BarPlaySplitActions?,
    editPose: BarEditPose?,
    editing: Boolean,
    onHomeClick: () -> Unit,
    onLibraryClick: () -> Unit,
    onLibraryLongClick: () -> Unit,
) {
    val haptics = rememberYoinHaptics()
    val homeDescription = stringResource(R.string.cmp_edge_cd_home)
    val libraryDescription = stringResource(R.string.cmp_edge_cd_library)
    val doneDescription = stringResource(R.string.cmp_edge_cd_done)
    val homeSelection by animateFloatAsState(
        targetValue = if (selectedSection == YoinSection.HOME) 1f else 0f,
        animationSpec = YoinMotion.defaultSpatialSpec(),
        label = "edgeSplitHomeSelection",
    )
    val colors = MaterialTheme.colorScheme
    val homeContainer = lerp(colors.surfaceContainerHighest, colors.primaryContainer, homeSelection)
    val homeContent = lerp(colors.onSurfaceVariant, colors.onPrimaryContainer, homeSelection)
    val libraryContainer = lerp(colors.surfaceContainerHighest, colors.primaryContainer, 1f - homeSelection)
    val libraryContent = lerp(colors.onSurfaceVariant, colors.onPrimaryContainer, 1f - homeSelection)
    // The selected button takes whatever the unselected one and the gap leave.
    val selectedHeight = (innerHeight - EdgeNavGap - EdgeNavRestingHeight).coerceAtLeast(EdgeNavRestingHeight)
    val homeHeight = lerp(EdgeNavRestingHeight, selectedHeight, homeSelection)
    val libraryHeight = lerp(EdgeNavRestingHeight, selectedHeight, 1f - homeSelection)
    val morph = chromeProgress().coerceIn(0f, 1f)
    val navAlpha = (1f - morph / 0.6f).coerceIn(0f, 1f)
    val splitAlpha = ((morph - 0.4f) / 0.6f).coerceIn(0f, 1f)
    // Edit pose, as in the bar: icons swap over the middle of P, Done's
    // container tints linearly in P.
    val edit = editPose?.progress()?.coerceIn(0f, 1f) ?: 0f
    val editSwap = barEditSwap(edit)
    val editLeftSlot = editPose?.leftSlot() ?: BarEditLeftSlot.UndoDisabled
    val editSlotAlphas = rememberBarEditSlotAlphas(editLeftSlot)
    var showLibrarySearchHint by remember { mutableStateOf(false) }
    // Library is Done while editing: entering edit clears a showing hint.
    LaunchedEffect(editing) {
        if (editing) showLibrarySearchHint = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (morph < 0.99f) {
            val libraryInteraction = remember { MutableInteractionSource() }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = navAlpha
                        val s = 1f - 0.06f * morph
                        scaleX = s
                        scaleY = s
                    },
                verticalArrangement = Arrangement.spacedBy(EdgeNavGap),
            ) {
                FilledIconButton(
                    onClick = {
                        // Edit haptics belong to the controller alone.
                        if (editing) {
                            editPose?.onLeftSlotClick()
                        } else {
                            haptics.performClick()
                            onHomeClick()
                        }
                    },
                    // Editing: M3 Expressive's pressed shape is the press echo.
                    shapes = barEditIconButtonShapes(
                        shape = RoundedCornerShape(EdgeNavCornerRadius),
                        pressMorph = editing && editLeftSlot != BarEditLeftSlot.UndoDisabled,
                    ),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = lerp(homeContainer, colors.surfaceContainerHighest, editSwap),
                        contentColor = homeContent,
                    ),
                    modifier = Modifier
                        .width(EdgeSplitButtonWidth)
                        .height(homeHeight)
                        .barEditLeftSlotSemantics(editing, editLeftSlot),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (editSwap < 0.99f) {
                            Icon(
                                imageVector = if (selectedSection == YoinSection.HOME) {
                                    YoinSymbols.HomeFilled
                                } else {
                                    YoinSymbols.Home
                                },
                                contentDescription = if (editing) null else homeDescription,
                                modifier = Modifier
                                    .size(EdgeNavIconSize)
                                    .graphicsLayer { alpha = 1f - editSwap },
                            )
                        }
                        if (editSwap > 0.01f) {
                            BarEditLeftSlotLayers(
                                editSwap = editSwap,
                                alphas = editSlotAlphas,
                                labelAlpha = 0f,
                                iconSize = EdgeNavIconSize,
                            )
                        }
                    }
                }
                LaunchedEffect(showLibrarySearchHint) {
                    if (showLibrarySearchHint) {
                        delay(EdgeLibraryHintHoldMs)
                        showLibrarySearchHint = false
                    }
                }
                Surface(
                    shape = rememberBarEditPressShape(
                        rest = RoundedCornerShape(EdgeNavCornerRadius),
                        interactionSource = libraryInteraction,
                        active = editing,
                    ),
                    color = lerp(libraryContainer, colors.primary, edit),
                    contentColor = lerp(libraryContent, colors.onPrimary, edit),
                    modifier = Modifier
                        .width(EdgeSplitButtonWidth)
                        .height(libraryHeight)
                        .combinedClickable(
                            interactionSource = libraryInteraction,
                            indication = null,
                            onClick = {
                                if (editing) {
                                    editPose?.onDone()
                                } else {
                                    haptics.performClick()
                                    onLibraryClick()
                                }
                            },
                            onLongClick = if (editing) {
                                null
                            } else {
                                {
                                    showLibrarySearchHint = true
                                    haptics.performContextClick()
                                    onLibraryLongClick()
                                }
                            },
                        )
                        .then(if (editing) Modifier.semantics { contentDescription = doneDescription } else Modifier),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (editSwap < 0.99f) {
                            Icon(
                                imageVector = if (selectedSection == YoinSection.LIBRARY) {
                                    YoinSymbols.LibraryFilled
                                } else {
                                    YoinSymbols.Library
                                },
                                contentDescription = if (editing) null else libraryDescription,
                                modifier = Modifier
                                    .size(EdgeNavIconSize)
                                    .graphicsLayer { alpha = 1f - editSwap },
                            )
                        }
                        if (editSwap > 0.01f) {
                            Icon(
                                imageVector = YoinSymbols.Check,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(EdgeNavIconSize)
                                    .graphicsLayer { alpha = editSwap },
                            )
                        }
                    }
                }
            }
            // The hint opens toward the content, away from the edge.
            LibrarySearchShortcutHint(
                visible = showLibrarySearchHint,
                modifier = if (side == EdgeSplitSide.Right) {
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = -(EdgeSplitGroupWidth + 4.dp))
                } else {
                    Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = EdgeSplitGroupWidth + 4.dp)
                },
            )
        }
        if (morph > 0.01f) {
            val shuffleSlot = if (roomy) VerticalSplitShuffleHeight + EdgeSplitGap * 2 else 0.dp
            PlaySplitButtonVertical(
                playContainer = playSplitActions?.playContainer ?: colors.primary,
                playContent = playSplitActions?.playContent ?: colors.onPrimary,
                onPlay = playSplitActions?.onPlay ?: {},
                onShuffle = playSplitActions?.onShuffle ?: {},
                playHeight = (innerHeight - EdgeSplitGap - VerticalSplitMenuHeight - shuffleSlot)
                    .coerceAtLeast(EdgeSplitButtonWidth),
                showShuffleButton = roomy,
                trailingMenuItems = { dismissMenu ->
                    playSplitActions?.menuItems?.invoke(this, dismissMenu)
                    BarExtraActionMenuItems(
                        actions = playSplitActions?.promotable.orEmpty(),
                        dismissMenu = dismissMenu,
                    )
                },
                modifier = Modifier
                    .clipToBounds()
                    .graphicsLayer {
                        alpha = splitAlpha
                        val s = 0.94f + 0.06f * morph
                        scaleX = s
                        scaleY = s
                    },
            )
        }
    }
}

private val EdgeSplitCapsulePadding = 6.dp
private val EdgeSplitButtonWidth = 52.dp
private val EdgeSplitGap = 2.dp
private val EdgeNavGap = 4.dp
private val EdgeNavRestingHeight = 56.dp
private val EdgeNavCornerRadius = 26.dp
private val EdgeNavIconSize = 22.dp
private const val EdgeLibraryHintHoldMs = 1_400L

@Preview(name = "Edge split · cutout left", widthDp = 844, heightDp = 390, showBackground = true)
@Composable
private fun YoinEdgeSplitGroupPreview() {
    YoinTheme {
        YoinEdgeSplitGroup(
            selectedSection = YoinSection.HOME,
            currentTrackId = "1",
            currentTrackTitle = "RUNNING TO YOU",
            currentTrackArtist = "Blusher",
            currentTrackCoverArtUrl = null,
            connectionErrorMessage = null,
            playbackProgress = 0.4f,
            onHomeClick = {},
            onNowPlayingClick = {},
            onLibraryClick = {},
            segmentsOverride = computeEdgeSplitSegments(
                windowHeight = 390.dp,
                topInset = 0.dp,
                bottomInset = 0.dp,
                leftCutoutTop = 176.dp,
                leftCutoutBottom = 212.dp,
            ),
        )
    }
}

@Preview(name = "Edge split · detail, no left cutout", widthDp = 844, heightDp = 390, showBackground = true)
@Composable
private fun YoinEdgeSplitGroupRoomyPreview() {
    YoinTheme {
        YoinEdgeSplitGroup(
            selectedSection = YoinSection.HOME,
            currentTrackId = "1",
            currentTrackTitle = "RUNNING TO YOU",
            currentTrackArtist = "Blusher",
            currentTrackCoverArtUrl = null,
            connectionErrorMessage = null,
            playbackProgress = 0.4f,
            chromeProgress = { 1f },
            onHomeClick = {},
            onNowPlayingClick = {},
            onLibraryClick = {},
            segmentsOverride = computeEdgeSplitSegments(
                windowHeight = 390.dp,
                topInset = 0.dp,
                bottomInset = 0.dp,
                leftCutoutTop = null,
                leftCutoutBottom = null,
            ),
        )
    }
}

@Preview(name = "Edge split · editing Home", widthDp = 844, heightDp = 390, showBackground = true)
@Composable
private fun YoinEdgeSplitGroupEditPreview() {
    YoinTheme {
        YoinEdgeSplitGroup(
            selectedSection = YoinSection.HOME,
            currentTrackId = "1",
            currentTrackTitle = "RUNNING TO YOU",
            currentTrackArtist = "Blusher",
            currentTrackCoverArtUrl = null,
            connectionErrorMessage = null,
            playbackProgress = 0.4f,
            editPose = BarEditPose(
                progress = { 1f },
                leftSlot = { BarEditLeftSlot.Undo },
                onLeftSlotClick = {},
                onDone = {},
            ),
            editing = true,
            onHomeClick = {},
            onNowPlayingClick = {},
            onLibraryClick = {},
            segmentsOverride = computeEdgeSplitSegments(
                windowHeight = 390.dp,
                topInset = 0.dp,
                bottomInset = 0.dp,
                leftCutoutTop = 176.dp,
                leftCutoutBottom = 212.dp,
            ),
        )
    }
}
