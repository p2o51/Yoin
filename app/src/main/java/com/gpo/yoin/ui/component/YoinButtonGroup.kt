package com.gpo.yoin.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
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
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButtonShapes
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.experience.CenteredBarBottomMargin
import com.gpo.yoin.ui.experience.CenteredBarBottomMarginWide
import com.gpo.yoin.ui.experience.CenteredBarMaxWidth
import com.gpo.yoin.ui.experience.MergedBarMaxWidth
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.experience.smoothstep
import com.gpo.yoin.ui.home.edit.withThreshold
import com.gpo.yoin.ui.navigation.YoinSection
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
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
 * Three chrome poses, every one a dp-lerp of the same four slots:
 *  - Nav (chromeProgress 0): [Home] [now-playing pill (fills)] [Library]
 *  - Detail (chromeProgress 1): [Play split (fills)] [extras] [pill]
 *  - Merged (paneProgress 1, Wide shell with its detail column open):
 *    [Home] [Library] [pill (fills)] [Play split] [Shuffle] — the one bar of
 *    the window spans both columns (adaptive principle 2), nav on the shell's
 *    side, the page's Play on the detail's side, the pill bridging them.
 *  - Edit ([editPose] progress 1, Home being edited): the idle path with
 *    Home → [Undo|Add] and Library → [Done], the pill folded in every pose.
 * Every pose is HOSTED — this composable never animates it, it only renders
 * the values, so exactly one driver exists per window (the shell's
 * rememberShellBarChromeMorph / pane spring, or a detail page's
 * predictive-back scrub). The shell flips to Detail the moment a detail
 * launch is tapped, so the morph IS the tap feedback; the detail window then
 * fades in over the settled morph onto its own pixel-identical DetailBottomBar.
 * The split button here is purely the morph visual in that case — the
 * functional twin lives in the detail window already fading in above. In
 * the merged pose it is functional: the shell hands it the column's actions.
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
    // Functional Play split for the detail windows' bar and the merged pose;
    // null = the shell's decorative theme-colored stand-in.
    playSplitActions: BarPlaySplitActions? = null,
    onHomeClick: () -> Unit,
    onNowPlayingClick: () -> Unit,
    onLibraryClick: () -> Unit,
    onLibraryLongClick: () -> Unit = onLibraryClick,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    // ShellChromeForm.CenteredBar: centred, capped at 600, and the detail pose
    // pulls the page's promotable actions out of ▾ into round buttons with a
    // fixed 200dp pill (断点交接 §2.3). False = the portrait bar, unchanged.
    centered: Boolean = false,
    // Wide windows sit the centred bar 28dp up instead of 24.
    wideMargin: Boolean = false,
    // Now Playing side panel open: the pill folds away (it became the panel)
    // and the bar wraps what is left (断点交接 §3.4).
    navOnly: Boolean = false,
    // Merged pose (0 = nav, 1 = merged), read per frame; hosted like
    // chromeProgress. Only the Wide shell drives it.
    paneProgress: () -> Float = { 0f },
    // Home edit pose, hosted by the shell's edit controller. Clicks route on
    // the discrete [editing], never on the animated progress.
    editPose: BarEditPose? = null,
    editing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        val haptics = rememberYoinHaptics()
        // Selection animates independently; palette changes use the shared
        // theme wash directly so both windows paint the same handoff frame.
        val homeSelection by animateFloatAsState(
            targetValue = if (selectedSection == YoinSection.HOME) 1f else 0f,
            animationSpec = YoinMotion.defaultEffectsSpec(),
            label = "buttonGroupSelection",
        )
        val colors = MaterialTheme.colorScheme
        val homeContainerColor = lerp(colors.surfaceContainerHighest, colors.primaryContainer, homeSelection)
        val homeContentColor = lerp(colors.onSurfaceVariant, colors.onPrimaryContainer, homeSelection)
        val libraryContainerColor = lerp(colors.surfaceContainerHighest, colors.primaryContainer, 1f - homeSelection)
        val libraryContentColor = lerp(colors.onSurfaceVariant, colors.onPrimaryContainer, 1f - homeSelection)
        // Already animated once by YoinChromeGroup (every bar form shares it).
        val playContainer = playSplitActions?.playContainer ?: colors.primary
        val playContent = playSplitActions?.playContent ?: colors.onPrimary
        var showLibrarySearchHint by remember { mutableStateOf(false) }
        // Detail pose extras (CenteredBar): Shuffle + the page's promotable
        // actions as round buttons. The merged pose promotes only Shuffle —
        // two columns' worth of nav already sit in the bar — and keeps the
        // page's extras as ▾ menu rows.
        val promotedExtras = if (centered) {
            playSplitActions?.let { actions ->
                listOf(shuffleExtra(actions)) + actions.promotable
            }.orEmpty()
        } else {
            emptyList()
        }
        val mergedExtras = listOf(shuffleExtra(playSplitActions))

        // Interaction sources are hoisted so the press of any one button
        // can drive the widths of the others (neighbour compression).
        val homeInteraction = rememberButtonGroupInteractionSource()
        val centerInteraction = rememberButtonGroupInteractionSource()
        val libraryInteraction = rememberButtonGroupInteractionSource()
        val homePressed by homeInteraction.collectIsPressedAsState()
        val centerPressed by centerInteraction.collectIsPressedAsState()
        val libraryPressed by libraryInteraction.collectIsPressedAsState()
        // One reveal / settle for the search hint, whichever slot hosts the
        // Library button (both share libraryInteraction). Library is Done
        // while editing: no hint, and entering edit clears a showing one.
        LaunchedEffect(libraryPressed, editing) {
            if (editing) {
                showLibrarySearchHint = false
            } else if (libraryPressed) {
                delay(LIBRARY_SEARCH_HINT_DELAY_MS)
                showLibrarySearchHint = true
            } else {
                delay(LIBRARY_SEARCH_HINT_SETTLE_MS)
                showLibrarySearchHint = false
            }
        }
        val editLeftSlot = editPose?.leftSlot() ?: BarEditLeftSlot.UndoDisabled
        val editSlotAlphas = rememberBarEditSlotAlphas(editLeftSlot)

        // Every animated input below is kept as a State and read only inside
        // the bar's own measure / subcomposition (barGeometry), never here —
        // a morph or column frame must not recompose the whole group.
        val navOnlyProgress = animateFloatAsState(
            targetValue = if (navOnly) 1f else 0f,
            animationSpec = YoinMotion.defaultSpatialSpec(),
            label = "barNavOnly",
        )
        // Selection affordance: the active tab settles into a wider pill.
        // Soft spatial spring — this is a state change, not a touch echo.
        val homeSelectionAspect = animateFloatAsState(
            targetValue = if (selectedSection == YoinSection.HOME) SELECTED_ASPECT else BASE_ASPECT,
            animationSpec = YoinMotion.defaultSpatialSpec(),
            label = "homeSelectionAspect",
        )
        val librarySelectionAspect = animateFloatAsState(
            targetValue = if (selectedSection == YoinSection.LIBRARY) SELECTED_ASPECT else BASE_ASPECT,
            animationSpec = YoinMotion.defaultSpatialSpec(),
            label = "librarySelectionAspect",
        )
        // Press affordance: the pressed icon button widens and the pill
        // absorbs it — the M3 expressive `animateWidth` bulge — while
        // pressing the pill nudges both icon buttons narrower so it grows
        // in turn. Quick spring so it reads as a direct touch echo.
        val homePressDelta = animateFloatAsState(
            targetValue = (if (homePressed) PRESS_EXPAND else 0f) -
                (if (centerPressed) NEIGHBOUR_SQUEEZE else 0f),
            animationSpec = YoinMotion.fastSpatialSpec(),
            label = "homePressDelta",
        )
        val libraryPressDelta = animateFloatAsState(
            targetValue = (if (libraryPressed) PRESS_EXPAND else 0f) -
                (if (centerPressed) NEIGHBOUR_SQUEEZE else 0f),
            animationSpec = YoinMotion.fastSpatialSpec(),
            label = "libraryPressDelta",
        )

        // IDLE pose (nothing playing, no error): the pill cedes the bar
        // to two labeled halves — [icon Home] [icon Library] — and the
        // whole thing springs back to the pill layout the moment a track
        // lands. Same dp-lerp language as the detail morph (the pill's
        // nav width simply collapses to 0 as the halves grow), so the two
        // poses compose: idle is applied to the NAV endpoints first, then
        // the chrome morph lerps toward detail as usual.
        val idle = currentTrackTitle == null && connectionErrorMessage == null
        val idleProgress = animateFloatAsState(
            targetValue = if (idle) 1f else 0f,
            animationSpec = YoinMotion.defaultSpatialSpec(),
            label = "barIdleProgress",
        )

        // Every fixed-height slot follows the form's button height (48 / 44).
        val buttonHeight = floatingBarButtonHeight(centered)
        val promotedCount = promotedExtras.size
        val mergedCount = mergedExtras.size
        val geometry: (Dp) -> BarGeometry = { slotInner ->
            resolveBarGeometry(
                slotInner = slotInner,
                morph = chromeProgress().coerceIn(0f, 1f),
                pane = paneProgress().coerceIn(0f, 1f),
                idle = idleProgress.value,
                edit = editPose?.progress()?.coerceIn(0f, 1f) ?: 0f,
                navOnly = navOnlyProgress.value,
                homeAspect = (homeSelectionAspect.value + homePressDelta.value).coerceAtLeast(MIN_ASPECT),
                libraryAspect = (librarySelectionAspect.value + libraryPressDelta.value).coerceAtLeast(MIN_ASPECT),
                buttonHeight = buttonHeight,
                centered = centered,
                promotedCount = promotedCount,
                mergedCount = mergedCount,
            )
        }

        FloatingBottomBar(
            modifier = modifier,
            centered = centered,
            bottomMargin = if (wideMargin) CenteredBarBottomMarginWide else CenteredBarBottomMargin,
            widthCap = { lerp(CenteredBarMaxWidth, MergedBarMaxWidth, paneProgress().coerceIn(0f, 1f)) },
            barWidth = { slot ->
                geometry(slot - FloatingBarRowPadding * 2).surfaceInner + FloatingBarRowPadding * 2
            },
            overlay = { surfaceWidth ->
                // The hint rides above whichever slot hosts Library: the
                // bar's right end in the nav pose, beside Home when merged.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .width(surfaceWidth),
                ) {
                    LibrarySearchShortcutHint(
                        visible = showLibrarySearchHint,
                        modifier = Modifier.offset {
                            val pane = paneProgress().coerceIn(0f, 1f)
                            val homeRest = buttonHeight *
                                (homeSelectionAspect.value + homePressDelta.value).coerceAtLeast(MIN_ASPECT)
                            val libraryRest = buttonHeight *
                                (librarySelectionAspect.value + libraryPressDelta.value).coerceAtLeast(MIN_ASPECT)
                            val navX = surfaceWidth - LibrarySearchHintEndInset - LibrarySearchHintSize
                            val mergedX = FloatingBarRowPadding + homeRest + FloatingBarItemGap +
                                libraryRest / 2 - LibrarySearchHintSize / 2
                            IntOffset(
                                x = lerp(navX, mergedX, pane).roundToPx(),
                                y = LibrarySearchHintLift.roundToPx(),
                            )
                        },
                    )
                }
            },
        ) { innerWidth, slotInner ->
            // The nav⇄detail morph and the nav⇄merged lerp, each driving every
            // slot width. All plain Row/Box + width(dp) — see the class KDoc
            // for why nothing fancier is allowed in here.
            val g = geometry(slotInner)
            val morph = g.morph
            val pane = g.pane
            // CENTER absorbs whatever the sides release — the same remainder
            // rule yields the nav fill, the fixed detail pill and the merged fill.
            val pillWidth = (innerWidth - g.leftWidth - FloatingBarItemGap - g.extrasSlotWidth - g.rightWidth)
                .coerceAtLeast(0.dp)
            val navAlpha = (1f - morph / 0.6f).coerceIn(0f, 1f)
            val splitAlpha = ((morph - 0.4f) / 0.6f).coerceIn(0f, 1f)
            // Merged twins fade in over the tail of the column spring and nav
            // Library fades out over its head; each is composed (and so
            // hit-tested) only while it actually shows — an invisible twin
            // on top would steal the visible button's taps.
            val mergedAlpha = ((pane - MERGED_REVEAL_START) / (1f - MERGED_REVEAL_START)).coerceIn(0f, 1f)
            val navLibraryAlpha = navAlpha * (1f - pane / NAV_LIBRARY_FADE_END).coerceIn(0f, 1f)
            // Label reveal rides the tail of the width spring; fade the pill
            // out fast so the squeeze never shows crushed content.
            val idleLabelAlpha = ((g.idleWeight - 0.55f) / 0.45f).coerceIn(0f, 1f)
            val pillIdleAlpha = (1f - maxOf(g.idle, g.navOnly) / 0.5f).coerceIn(0f, 1f)
            // Edit pose: each nav button's content swaps over the middle of P;
            // Done's container tints linearly in P. Local lerps, no new scheme.
            val editSwap = barEditSwap(g.edit)
            val editHomeContainer = lerp(homeContainerColor, colors.surfaceContainerHighest, editSwap)
            val doneContainer = lerp(libraryContainerColor, colors.primary, g.edit)
            val doneContent = lerp(libraryContentColor, colors.onPrimary, g.edit)

            // LEFT SLOT — Home fading out beneath the stretching Play split,
            // or Library joining it in the merged pose.
            Box(
                modifier = Modifier
                    .width(g.leftWidth)
                    .height(buttonHeight)
                    .clipToBounds(),
            ) {
                // The merged Library is composed BENEATH Home: mid-spring it
                // emerges from behind Home's trailing edge, never over it, and
                // Home keeps its taps in the transient overlap.
                if (mergedAlpha > 0f) {
                    LibraryButton(
                        selected = selectedSection == YoinSection.LIBRARY,
                        width = g.libraryWidth,
                        containerColor = doneContainer,
                        contentColor = doneContent,
                        labelAlpha = 0f,
                        editSwap = editSwap,
                        editing = editing,
                        interactionSource = libraryInteraction,
                        onClick = {
                            // Edit haptics belong to the controller alone.
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
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .graphicsLayer { alpha = mergedAlpha },
                    )
                }
                if (morph < 0.99f) {
                    FilledIconButton(
                        onClick = {
                            if (editing) {
                                editPose?.onLeftSlotClick()
                            } else {
                                haptics.performClick()
                                onHomeClick()
                            }
                        },
                        shapes = barEditIconButtonShapes(
                            shape = IconButtonDefaults.filledShape,
                            pressMorph = editing && editLeftSlot != BarEditLeftSlot.UndoDisabled,
                        ),
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(g.homeWidth)
                            .fillMaxHeight()
                            .graphicsLayer { alpha = navAlpha }
                            .barEditLeftSlotSemantics(editing, editLeftSlot),
                        interactionSource = homeInteraction,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = editHomeContainer,
                            contentColor = homeContentColor,
                        ),
                    ) {
                        // Home and the edit layers stacked in one button, so the
                        // swap never re-lays out the slot.
                        Box(contentAlignment = Alignment.Center) {
                            if (editSwap < 0.99f) {
                                BarIconLabelRow(
                                    icon = if (selectedSection == YoinSection.HOME) {
                                        YoinSymbols.HomeFilled
                                    } else {
                                        YoinSymbols.Home
                                    },
                                    label = "Home",
                                    labelAlpha = idleLabelAlpha,
                                    contentDescription = "Home",
                                    modifier = Modifier
                                        .graphicsLayer { alpha = 1f - editSwap }
                                        .then(if (editing) Modifier.clearAndSetSemantics {} else Modifier),
                                )
                            }
                            if (editSwap > 0.01f) {
                                BarEditLeftSlotLayers(
                                    editSwap = editSwap,
                                    alphas = editSlotAlphas,
                                    labelAlpha = idleLabelAlpha,
                                )
                            }
                        }
                    }
                }
                if (morph > 0.01f) {
                    // Functional on detail pages; decorative in the shell
                    // (where the real twin lives in the window fading in above).
                    PlaySplitButton(
                        playContainer = playContainer,
                        playContent = playContent,
                        onPlay = playSplitActions?.onPlay ?: {},
                        onShuffle = playSplitActions?.onShuffle ?: {},
                        buttonHeight = buttonHeight,
                        fillPlay = true,
                        compact = true,
                        showShuffleInMenu = promotedExtras.isEmpty(),
                        trailingMenuItems = { dismissMenu ->
                            playSplitActions?.menuItems?.invoke(this, dismissMenu)
                            if (promotedExtras.isEmpty()) {
                                BarExtraActionMenuItems(
                                    actions = playSplitActions?.promotable.orEmpty(),
                                    dismissMenu = dismissMenu,
                                )
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(g.splitDetailWidth)
                            .graphicsLayer { alpha = splitAlpha },
                    )
                }
            }

            Spacer(modifier = Modifier.width(FloatingBarItemGap))

            // EXTRAS (CenteredBar detail pose only) — each carries its own
            // trailing gap so the slot collapses cleanly to 0 in nav pose.
            if (promotedExtras.isNotEmpty() && morph > 0.01f) {
                Row(
                    modifier = Modifier
                        .width(g.extrasSlotWidth)
                        .height(buttonHeight)
                        .clipToBounds()
                        .graphicsLayer { alpha = splitAlpha },
                ) {
                    promotedExtras.forEach { action ->
                        BarExtraButton(action = action, size = buttonHeight, onClick = {
                            haptics.performClick()
                            action.onClick()
                        })
                        Spacer(modifier = Modifier.width(FloatingBarItemGap))
                    }
                }
            }

            // CENTER — the now-playing pill, absorbing whatever the sides
            // release. Fully idle (and not in detail chrome) = not composed:
            // its 0dp slot would still marquee and hit-test, and an idle tap
            // opening the "Nothing playing" page is exactly what the idle
            // pose exists to retire. The surface then hugs the slots exactly
            // (barGeometry), so no slack is ever left where the pill was.
            if (g.pillComposed) {
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
                    // Fading out but still composed on the way into edit:
                    // no press, click or haptic.
                    enabled = !editing,
                    modifier = Modifier
                        .width(pillWidth)
                        .fillMaxHeight()
                        .graphicsLayer {
                            alpha = if (morph > 0.005f) 1f else pillIdleAlpha
                        },
                )
            }

            // RIGHT SLOT — Library (with its leading gap), collapsing away
            // in detail chrome; the page's Play split + Shuffle in the
            // merged pose.
            Box(
                modifier = Modifier
                    .width(g.rightWidth)
                    .height(buttonHeight)
                    .clipToBounds(),
            ) {
                if (morph < 0.99f && navLibraryAlpha > 0f) {
                    LibraryButton(
                        selected = selectedSection == YoinSection.LIBRARY,
                        width = g.libraryWidth,
                        containerColor = doneContainer,
                        contentColor = doneContent,
                        labelAlpha = idleLabelAlpha,
                        editSwap = editSwap,
                        editing = editing,
                        interactionSource = libraryInteraction,
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
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .graphicsLayer { alpha = navLibraryAlpha },
                    )
                }
                if (mergedAlpha > 0f) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(g.rightMergedWidth - FloatingBarItemGap)
                            .height(buttonHeight)
                            .graphicsLayer { alpha = mergedAlpha },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Shuffle is promoted beside it; the page's own extras
                        // (Go to artist, Share…) stay in ▾ — see promotedExtras.
                        PlaySplitButton(
                            playContainer = playContainer,
                            playContent = playContent,
                            onPlay = playSplitActions?.onPlay ?: {},
                            onShuffle = playSplitActions?.onShuffle ?: {},
                            buttonHeight = buttonHeight,
                            fillPlay = true,
                            compact = true,
                            showShuffleInMenu = false,
                            trailingMenuItems = { dismissMenu ->
                                playSplitActions?.menuItems?.invoke(this, dismissMenu)
                                BarExtraActionMenuItems(
                                    actions = playSplitActions?.promotable.orEmpty(),
                                    dismissMenu = dismissMenu,
                                )
                            },
                            modifier = Modifier.width(FloatingBarSplitWidth),
                        )
                        mergedExtras.forEach { action ->
                            Spacer(modifier = Modifier.width(FloatingBarItemGap))
                            BarExtraButton(action = action, size = buttonHeight, onClick = {
                                haptics.performClick()
                                action.onClick()
                            })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Every slot width of the bar for one frame — a pure function of the slot's
 * full inner width and the hosted poses, evaluated inside the bar (surface
 * width and row content agree by construction).
 */
internal class BarGeometry(
    val morph: Float,
    val pane: Float,
    /** Idle-like pose: nothing playing, or Home being edited (the larger). */
    val idle: Float,
    /** Raw Home edit progress; already folded into [idle]. */
    val edit: Float,
    val navOnly: Float,
    val idleWeight: Float,
    val homeWidth: Dp,
    val libraryWidth: Dp,
    val leftWidth: Dp,
    val extrasSlotWidth: Dp,
    val splitDetailWidth: Dp,
    val rightWidth: Dp,
    val rightMergedWidth: Dp,
    val pillComposed: Boolean,
    /** The inner row width the surface gives the slots this frame. */
    val surfaceInner: Dp,
)

internal fun resolveBarGeometry(
    slotInner: Dp,
    morph: Float,
    pane: Float,
    idle: Float,
    edit: Float,
    navOnly: Float,
    homeAspect: Float,
    libraryAspect: Float,
    buttonHeight: Dp,
    centered: Boolean,
    promotedCount: Int,
    mergedCount: Int,
): BarGeometry {
    val gap = FloatingBarItemGap
    // Editing Home rides the shipped idle path: the pill folds in every pose
    // and the nav slots become [Undo|Add] [Done].
    val idleLike = maxOf(idle, edit)
    // The panel fold and the merged pose win over the idle halves: nav
    // buttons keep their resting sizes while the bar wraps them.
    val idleWeight = idleLike * (1f - navOnly) * (1f - pane)
    val homeRest = buttonHeight * homeAspect
    val libraryRest = buttonHeight * libraryAspect
    val idleHalf = ((slotInner - gap * 2) / 2).coerceAtLeast(0.dp)
    val homeWidth = lerp(homeRest, idleHalf, idleWeight)
    val libraryWidth = lerp(libraryRest, idleHalf, idleWeight)
    val rightMerged = gap + FloatingBarSplitWidth + (buttonHeight + gap) * mergedCount
    // Slots that need no inner width (everything but the pill), so the
    // surface can wrap them while the pill folds away beside the Now
    // Playing panel (navOnly) or in the merged pose with nothing playing.
    val fixedWithoutPill = lerp(
        lerp(homeRest, FloatingBarSplitWidth, morph) + gap + lerp(gap + libraryRest, 0.dp, morph),
        homeRest + gap + libraryRest + gap + rightMerged,
        pane,
    )
    val collapsePill = maxOf(navOnly, idleLike * pane)
    val pillComposed = (idleLike < 0.995f && navOnly < 0.995f) || morph > 0.005f
    val laidInner = if (pillComposed) lerp(slotInner, fixedWithoutPill, collapsePill) else slotInner
    // CenteredBar detail pose: [Play split (stretches)] [extras…] [pill 200].
    val extrasWidth = (buttonHeight + gap) * promotedCount
    val pillDetailWidth = if (centered) FloatingBarDetailPillWidth else laidInner - FloatingBarSplitWidth - gap
    val splitDetailWidth = if (centered) {
        (laidInner - pillDetailWidth - gap - extrasWidth).coerceAtLeast(FloatingBarSplitWidth)
    } else {
        FloatingBarSplitWidth
    }
    // LEFT: Home → Play split (detail) / Home + Library (merged).
    val leftWidth = lerp(lerp(homeWidth, splitDetailWidth, morph), homeWidth + gap + libraryWidth, pane)
    val extrasSlotWidth = lerp(0.dp, extrasWidth, morph) * (1f - pane)
    // RIGHT: Library (with its leading gap) → nothing (detail) / Play split +
    // Shuffle (merged).
    val rightWidth = lerp(lerp(gap + libraryWidth, 0.dp, morph), rightMerged, pane)
    // With the pill folded away the surface hugs the slots exactly — a lerp
    // between two resting widths would disagree with the slots mid-spring
    // and leave a hole where the pill was.
    val surfaceInner = if (pillComposed) laidInner else leftWidth + gap + extrasSlotWidth + rightWidth
    return BarGeometry(
        morph = morph,
        pane = pane,
        idle = idleLike,
        edit = edit,
        navOnly = navOnly,
        idleWeight = idleWeight,
        homeWidth = homeWidth,
        libraryWidth = libraryWidth,
        leftWidth = leftWidth,
        extrasSlotWidth = extrasSlotWidth,
        splitDetailWidth = splitDetailWidth,
        rightWidth = rightWidth,
        rightMergedWidth = rightMerged,
        pillComposed = pillComposed,
        surfaceInner = surfaceInner,
    )
}

/**
 * The Library destination button — one composable for its nav and merged
 * slots. In the edit pose it cross-fades to Done over [editSwap], and a
 * press while [editing] morphs its corners (rememberBarEditPressShape).
 */
@Composable
private fun LibraryButton(
    selected: Boolean,
    width: Dp,
    containerColor: Color,
    contentColor: Color,
    labelAlpha: Float,
    editSwap: Float,
    editing: Boolean,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .then(if (editing) Modifier.semantics { contentDescription = "Done" } else Modifier),
        shape = rememberBarEditPressShape(
            rest = MaterialTheme.shapes.extraLarge,
            interactionSource = interactionSource,
            active = editing,
        ),
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (editSwap < 0.99f) {
                BarIconLabelRow(
                    icon = if (selected) YoinSymbols.LibraryFilled else YoinSymbols.Library,
                    label = "Library",
                    labelAlpha = labelAlpha,
                    contentDescription = "Library",
                    modifier = Modifier
                        .graphicsLayer { alpha = 1f - editSwap }
                        .then(if (editing) Modifier.clearAndSetSemantics {} else Modifier),
                )
            }
            if (editSwap > 0.01f) {
                BarIconLabelRow(
                    icon = YoinSymbols.Check,
                    label = "Done",
                    labelAlpha = labelAlpha,
                    modifier = Modifier
                        .graphicsLayer { alpha = editSwap }
                        .clearAndSetSemantics {},
                )
            }
        }
    }
}

/** A bar button's icon with its idle-pose label, which shows only while [labelAlpha] does. */
@Composable
private fun BarIconLabelRow(
    icon: ImageVector,
    label: String,
    labelAlpha: Float,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    color: Color = LocalContentColor.current,
    iconSize: Dp = BarIconSize,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = color,
            modifier = Modifier.size(iconSize),
        )
        if (labelAlpha > 0.01f) {
            Text(
                text = label,
                color = color,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.graphicsLayer { alpha = labelAlpha },
            )
        }
    }
}

/** The edit pose's content swap, over P 0.35 → 0.65 (PORT §2.9). */
internal fun barEditSwap(edit: Float): Float = smoothstep(EDIT_SWAP_START, EDIT_SWAP_END, edit)

/** TalkBack's name for the edit left slot. Disabled Undo also reports disabled. */
internal fun barEditLeftSlotDescription(slot: BarEditLeftSlot): String = when (slot) {
    BarEditLeftSlot.Undo, BarEditLeftSlot.UndoDisabled -> "Undo"
    BarEditLeftSlot.Add -> "Show hidden sections"
}

/**
 * The left slot's button speaks for its stacked layers while editing; the
 * dimmed Undo stays clickable (the controller no-ops it) but reads disabled.
 */
internal fun Modifier.barEditLeftSlotSemantics(editing: Boolean, slot: BarEditLeftSlot): Modifier = if (editing) {
    semantics {
        contentDescription = barEditLeftSlotDescription(slot)
        if (slot == BarEditLeftSlot.UndoDisabled) disabled()
    }
} else {
    this
}

/**
 * The edit left slot's shapes. While [pressMorph] (editing, with something to
 * undo or add) a press squares the button off to M3 Expressive's pressed
 * shape: the visual twin of the controller's click haptic, which a tablet
 * without a vibration motor never plays (SPEC §2.8). Otherwise it is [shape],
 * pressed or not, so normal-mode presses look exactly as before.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun barEditIconButtonShapes(shape: Shape, pressMorph: Boolean): IconButtonShapes =
    if (pressMorph) IconButtonDefaults.shapes(shape = shape) else IconButtonShapes(shape)

/**
 * Done's press echo, the [barEditIconButtonShapes] twin for a plain Surface
 * button: while [active], a press moves [rest]'s corners to [pressed] (M3
 * Expressive's pressed corner) on the default effects spring, as M3's own
 * buttons do, and eases back on release. The radius is read in the outline
 * only, so a press never recomposes the bar. Inactive and at rest it is
 * [rest] itself: normal-mode presses look exactly as before.
 */
@Composable
internal fun rememberBarEditPressShape(
    rest: CornerBasedShape,
    interactionSource: InteractionSource,
    active: Boolean,
    pressed: CornerBasedShape = MaterialTheme.shapes.small,
): Shape {
    val isPressed by interactionSource.collectIsPressedAsState()
    val press = remember { Animatable(0f) }
    val spec = YoinMotion.defaultEffectsSpec<Float>()
    LaunchedEffect(isPressed, active) {
        if (!active && press.value == 0f) return@LaunchedEffect
        press.animateTo(if (isPressed && active) 1f else 0f, spec)
    }
    // Leaving edit mid-press, the corners still ease back before [rest] returns.
    val morphing by remember { derivedStateOf { press.value > 0f } }
    val morphShape = remember(rest, pressed) { PressCornerShape(rest, pressed) { press.value } }
    return if (active || morphing) morphShape else rest
}

/**
 * A rounded rectangle [progress] of the way from [rest]'s corner to
 * [pressed]'s. Both have uniform corners; capped at a full round end, as a
 * [CornerBasedShape] does.
 */
private class PressCornerShape(
    private val rest: CornerBasedShape,
    private val pressed: CornerBasedShape,
    private val progress: () -> Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val from = rest.topStart.toPx(size, density)
        val to = pressed.topStart.toPx(size, density)
        val radius = (from + (to - from) * progress().coerceIn(0f, 1f)).coerceIn(0f, size.minDimension / 2f)
        return Outline.Rounded(RoundRect(size.toRect(), CornerRadius(radius)))
    }
}

/** Alphas of the three left-slot layers, read in draw only. */
@Immutable
internal class BarEditSlotAlphas(
    val undo: () -> Float,
    val add: () -> Float,
    val undoDisabled: () -> Float,
)

/**
 * Bar-local fades between Undo, Add and the dimmed Undo (fastEffects). They
 * follow [slot] even outside edit, so entering never fades the wrong kind.
 */
@Composable
internal fun rememberBarEditSlotAlphas(slot: BarEditLeftSlot): BarEditSlotAlphas {
    val fastEffects = YoinMotion.fastEffectsSpec<Float>()
    val spec = remember(fastEffects) { fastEffects.withThreshold(EDIT_SLOT_ALPHA_THRESHOLD) }
    val undo = animateFloatAsState(
        targetValue = if (slot == BarEditLeftSlot.Undo) 1f else 0f,
        animationSpec = spec,
        label = "barEditUndoAlpha",
    )
    val add = animateFloatAsState(
        targetValue = if (slot == BarEditLeftSlot.Add) 1f else 0f,
        animationSpec = spec,
        label = "barEditAddAlpha",
    )
    val undoDisabled = animateFloatAsState(
        targetValue = if (slot == BarEditLeftSlot.UndoDisabled) 1f else 0f,
        animationSpec = spec,
        label = "barEditUndoDisabledAlpha",
    )
    return remember(undo, add, undoDisabled) {
        BarEditSlotAlphas(undo = { undo.value }, add = { add.value }, undoDisabled = { undoDisabled.value })
    }
}

/**
 * The edit pose's left-slot content, stacked and cross-faded: Undo, Add and
 * the dimmed Undo. Undo is a text label until yoin-symbols ships its glyph,
 * so it shows in every pose. The slot's button owns click and semantics.
 */
@Composable
internal fun BarEditLeftSlotLayers(
    editSwap: Float,
    alphas: BarEditSlotAlphas,
    labelAlpha: Float,
    modifier: Modifier = Modifier,
    iconSize: Dp = BarIconSize,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier.clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        BarEditUndoLabel(
            color = colors.onSurface,
            modifier = Modifier.graphicsLayer { alpha = editSwap * alphas.undo() },
        )
        BarIconLabelRow(
            icon = YoinSymbols.Add,
            label = "Add",
            labelAlpha = labelAlpha,
            color = colors.onSurface,
            iconSize = iconSize,
            modifier = Modifier.graphicsLayer { alpha = editSwap * alphas.add() },
        )
        BarEditUndoLabel(
            color = colors.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA),
            modifier = Modifier.graphicsLayer { alpha = editSwap * alphas.undoDisabled() },
        )
    }
}

@Composable
private fun BarEditUndoLabel(color: Color, modifier: Modifier = Modifier) {
    Text(
        text = "Undo",
        color = color,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}

@Preview(name = "Bar edit left slot · Undo / Add / disabled", showBackground = true)
@Composable
private fun BarEditLeftSlotLayersPreview() {
    YoinTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                BarEditSlotAlphas(undo = { 1f }, add = { 0f }, undoDisabled = { 0f }),
                BarEditSlotAlphas(undo = { 0f }, add = { 1f }, undoDisabled = { 0f }),
                BarEditSlotAlphas(undo = { 0f }, add = { 0f }, undoDisabled = { 1f }),
            ).forEach { alphas ->
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .width(120.dp)
                        .height(48.dp),
                ) {
                    BarEditLeftSlotLayers(
                        editSwap = 1f,
                        alphas = alphas,
                        labelAlpha = 1f,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** A promoted page action as a round tonal button (detail extras, merged Shuffle). */
@Composable
private fun BarExtraButton(action: BarExtraAction, size: Dp, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(size),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = action.label,
            modifier = Modifier.size(22.dp),
        )
    }
}

private fun shuffleExtra(actions: BarPlaySplitActions?): BarExtraAction = BarExtraAction(
    icon = YoinSymbols.Shuffle,
    label = "Shuffle play",
    onClick = actions?.onShuffle ?: {},
)

@Composable
internal fun LibrarySearchShortcutHint(
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
                        imageVector = YoinSymbols.Search,
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

// Merged pose (column open): its twins appear over the tail of the column
// spring, the nav Library leaves over its head.
private const val MERGED_REVEAL_START = 0.4f
private const val NAV_LIBRARY_FADE_END = 0.6f

// Edit pose (PORT §2.9): the content swap's window on P, the left-slot
// fades' threshold, and the dimmed Undo's content alpha.
private const val EDIT_SWAP_START = 0.35f
private const val EDIT_SWAP_END = 0.65f
private const val EDIT_SLOT_ALPHA_THRESHOLD = 0.002f
private const val DISABLED_CONTENT_ALPHA = 0.38f

// Every bar glyph's size (the symbols' own 24dp).
private val BarIconSize = 24.dp

// The search hint bubble: 48dp, 26dp in from the bar's end in the nav pose,
// lifted to sit just above the bar.
private val LibrarySearchHintSize = 48.dp
private val LibrarySearchHintEndInset = 26.dp
private val LibrarySearchHintLift = (-46).dp

/**
 * Functional Play-split wiring for the detail windows' bar and the merged pose.
 *
 * [menuItems] always stay in ▾ (Open in Spotify…); [promotable] actions (Go to
 * artist, Share) leave the menu for their own round buttons wherever the bar
 * has room — the CenteredBar detail pose (断点交接 §2.3) — and render as menu
 * rows everywhere else. Shuffle is promoted alongside them.
 */
class BarPlaySplitActions(
    val playContainer: androidx.compose.ui.graphics.Color,
    val playContent: androidx.compose.ui.graphics.Color,
    val onPlay: () -> Unit,
    val onShuffle: () -> Unit,
    val menuItems: @Composable androidx.compose.foundation.layout.ColumnScope.(dismissMenu: () -> Unit) -> Unit,
    val promotable: List<BarExtraAction> = emptyList(),
)
