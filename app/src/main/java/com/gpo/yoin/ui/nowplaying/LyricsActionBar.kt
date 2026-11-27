package com.gpo.yoin.ui.nowplaying

import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.component.handPointer
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.symbols.rememberTranslateSymbolPainter
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.roundToInt


/**
 * The lyric tools: Sing (where the song can be sung) at the far left, then the connected group Search ·
 * Translate · Back to the current line · Select, Select at the far right (owner 2026-11-27; the lyrics page
 * has no Share key of its own — lines are shared from select mode).
 *
 * Short of room (the Sing capsule open on a narrow phone, a tight column) the gaps give first, then the
 * keys narrow — never below [LyricsKeyMinWidth] — then the open capsule; a row that still can't hold every
 * key at that floor drops the lowest-priority ones ([lyricsVisibleKeys]) rather than squeezing them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LyricsActionBar(
    actionInFlight: LyricsAction?,
    canTranslate: Boolean,
    canSelect: Boolean,
    canRecenter: Boolean,
    onSearchClick: () -> Unit,
    onTranslateClick: () -> Unit,
    onSelectClick: () -> Unit,
    onRecenterClick: () -> Unit,
    modifier: Modifier = Modifier,
    // The lyrics search expands from (and collapses back into) the search key.
    searchBarState: SearchBarState? = null,
    // 52 in the bottom row; smaller in the landscape header.
    iconSize: Dp = 52.dp,
    // False once the auto-hide has faded the keys all the way out: an
    // unseen key takes no tap (LyricToolsIdleState.hidden).
    interactive: Boolean = true,
) {
    val searchInteraction = remember { MutableInteractionSource() }
    val translateInteraction = remember { MutableInteractionSource() }
    val recenterInteraction = remember { MutableInteractionSource() }
    val selectInteraction = remember { MutableInteractionSource() }
    val sing = LocalNowPlayingSing.current
    val singSupported = sing?.isSupported == true
    // Synchronized directly with SingLyricsCapsuleKey's actual expanded state so the keys
    // compress on the exact same frame the capsule expands (zero pre-expansion jitter).
    var singCapsuleExpanded by remember { mutableStateOf(sing?.isSupported == true && sing.isActive) }
    val singExpanded = singSupported && singCapsuleExpanded

    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        BoxWithConstraints(modifier = modifier) {
            val bounded = maxWidth.value.isFinite()
            val keys = lyricsVisibleKeys(
                maxWidth = if (bounded) maxWidth else Dp.Infinity,
                iconSize = iconSize,
                singSupported = singSupported,
            )
            val fit = lyricsKeyFit(
                maxWidth = if (bounded) maxWidth else Dp.Infinity,
                iconSize = iconSize,
                keyCount = keys.size,
                singSupported = singSupported,
                singExpanded = singExpanded,
            )
            val keyGap by animateDpAsState(
                targetValue = fit.gap,
                animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Standard),
                label = "lyricsKeyAdaptiveGap",
            )
            val keyWidth by animateDpAsState(
                targetValue = fit.keyWidth,
                animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Standard),
                label = "lyricsKeyAdaptiveWidth",
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(keyGap),
            ) {
                if (singSupported) {
                    SingLyricsCapsuleKey(
                        sing = sing,
                        iconSize = iconSize,
                        maxExpandedWidth = if (bounded) fit.capsuleMax else Dp.Unspecified,
                        onCapsuleExpandedChange = { singCapsuleExpanded = it },
                    )
                }
                LyricsKeyRow(
                    interactions = keys.map { key ->
                        when (key) {
                            LyricsKey.Search -> searchInteraction
                            LyricsKey.Translate -> translateInteraction
                            LyricsKey.Recenter -> recenterInteraction
                            LyricsKey.Select -> selectInteraction
                        }
                    },
                    iconSize = iconSize,
                    keyWidth = keyWidth,
                    keyGap = keyGap,
                ) {
                    for (key in keys) {
                        when (key) {
                            LyricsKey.Search -> {
                                val searchKey: @Composable () -> Unit = {
                                    LyricsActionIcon(
                                        icon = rememberVectorPainter(YoinSymbols.Search),
                                        contentDescription = stringResource(R.string.np_cd_search_lyrics),
                                        interactionSource = searchInteraction,
                                        enabled = interactive && actionInFlight == null,
                                        onClick = onSearchClick,
                                        size = iconSize,
                                    )
                                }
                                if (searchBarState != null) {
                                    // A bare, see-through collapsed SearchBar: the only public way to give
                                    // SearchBarState its collapsed bounds, so the expanded search grows
                                    // out of this key.
                                    SearchBar(
                                        state = searchBarState,
                                        inputField = searchKey,
                                        shape = RoundedCornerShape(iconSize * 0.31f),
                                        colors = SearchBarDefaults.colors(containerColor = Color.Transparent),
                                        tonalElevation = 0.dp,
                                        shadowElevation = 0.dp,
                                    )
                                } else {
                                    searchKey()
                                }
                            }
                            LyricsKey.Translate -> {
                                // The translate key stays lit while its own translation runs, so
                                // the 文 ⇄ A orbit is visible (a disabled IconButton drops its
                                // content to 38% alpha); taps are ignored until it finishes.
                                val translating = actionInFlight == LyricsAction.Translate
                                LyricsActionIcon(
                                    icon = rememberTranslateSymbolPainter(translating = translating),
                                    contentDescription = stringResource(R.string.np_cd_translate_lyrics),
                                    interactionSource = translateInteraction,
                                    enabled = interactive && (actionInFlight == null || translating) && canTranslate,
                                    onClick = { if (actionInFlight == null) onTranslateClick() },
                                    size = iconSize,
                                    // Showing / hiding plucks when the lines move (NowPlayingScreen).
                                    tapHaptic = false,
                                )
                            }
                            LyricsKey.Recenter -> LyricsActionIcon(
                                icon = rememberVectorPainter(YoinSymbols.Recenter),
                                contentDescription = stringResource(R.string.np_cd_return_current_line),
                                interactionSource = recenterInteraction,
                                enabled = interactive && actionInFlight == null && canRecenter,
                                onClick = onRecenterClick,
                                size = iconSize,
                            )
                            // ✓ = select lines (copy / share as image). Editing the
                            // LRC moved into select mode's own bar; with no lines to
                            // select the ✓ still opens the editor (paste lyrics).
                            LyricsKey.Select -> LyricsActionIcon(
                                icon = rememberVectorPainter(YoinSymbols.Check),
                                contentDescription = if (canSelect) {
                                    stringResource(R.string.np_cd_select_lyrics)
                                } else {
                                    stringResource(R.string.np_cd_add_lyrics)
                                },
                                interactionSource = selectInteraction,
                                enabled = interactive && actionInFlight == null,
                                onClick = onSelectClick,
                                size = iconSize,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The lyric tools' keys, in their order on the row (Sing, when there, sits before them). */
internal enum class LyricsKey { Search, Translate, Recenter, Select }

/**
 * The keys a row [maxWidth] wide shows: all four while each still gets [LyricsKeyMinWidth] (or the
 * smaller [iconSize]) with the narrowest gaps and the Sing key at its own size; else Back to the current
 * line goes first, then Translate. Search and Select always stay.
 */
internal fun lyricsVisibleKeys(maxWidth: Dp, iconSize: Dp, singSupported: Boolean): List<LyricsKey> {
    val minKey = minOf(LyricsKeyMinWidth, iconSize)
    val all = LyricsKey.entries.toList()
    val dropOrder = listOf(LyricsKey.Recenter, LyricsKey.Translate)
    var shown = all
    for (drop in listOf(null) + dropOrder) {
        if (drop != null) shown = shown - drop
        val gaps = (shown.size - 1) + if (singSupported) 1 else 0
        val needed = minKey * shown.size + LyricsKeyMinGap * gaps + if (singSupported) iconSize else 0.dp
        if (needed <= maxWidth) return shown
    }
    return shown
}

/** How the row spends [maxWidth]: each key's width, the gap, and the most the open Sing capsule may take. */
internal data class LyricsKeyFit(val keyWidth: Dp, val gap: Dp, val capsuleMax: Dp)

internal fun lyricsKeyFit(
    maxWidth: Dp,
    iconSize: Dp,
    keyCount: Int,
    singSupported: Boolean,
    singExpanded: Boolean,
): LyricsKeyFit {
    val minKey = minOf(LyricsKeyMinWidth, iconSize)
    val desiredCapsule = singCapsuleWidth(iconSize)
    val singSlot = when {
        !singSupported -> 0.dp
        singExpanded -> desiredCapsule
        else -> iconSize
    }
    val gapCount = (keyCount - 1).coerceAtLeast(0) + if (singSupported) 1 else 0
    val natural = iconSize * keyCount + LyricsKeyGap * gapCount + singSlot
    if (maxWidth >= natural) return LyricsKeyFit(iconSize, LyricsKeyGap, desiredCapsule)
    // The gaps give first…
    val extraNeeded = natural - maxWidth
    val gap = if (gapCount == 0) {
        LyricsKeyGap
    } else {
        (LyricsKeyGap - extraNeeded / (gapCount * 2f)).coerceIn(LyricsKeyMinGap, LyricsKeyGap)
    }
    // …then the keys, down to their floor…
    val roomForKeys = maxWidth - gap * gapCount - singSlot
    val keyWidth = if (keyCount == 0) iconSize else (roomForKeys / keyCount.toFloat()).coerceIn(minKey, iconSize)
    // …then the open capsule takes what is left (never less than a key).
    val capsuleMax = (maxWidth - keyWidth * keyCount - gap * gapCount).coerceIn(iconSize, desiredCapsule)
    return LyricsKeyFit(keyWidth, gap, capsuleMax)
}

/**
 * Select mode's keys, in the lyric tools' slot and footprint: leave, copy the
 * picked lines, share them as an image card, edit the lyrics (the LRC editor
 * the ✓ used to open).
 */
@Composable
internal fun LyricsSelectionBar(
    selectedCount: Int,
    onCloseClick: () -> Unit,
    onCopyClick: () -> Unit,
    onShareClick: () -> Unit,
    onEditClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 52.dp,
) {
    val closeInteraction = remember { MutableInteractionSource() }
    val copyInteraction = remember { MutableInteractionSource() }
    val shareInteraction = remember { MutableInteractionSource() }
    val editInteraction = remember { MutableInteractionSource() }
    val interactions = listOf(closeInteraction, copyInteraction, shareInteraction, editInteraction)
    val hasSelection = selectedCount > 0

    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        LyricsKeyRow(
            interactions = interactions,
            iconSize = iconSize,
            modifier = modifier,
        ) {
            LyricsActionIcon(
                icon = rememberVectorPainter(YoinSymbols.Close),
                contentDescription = stringResource(R.string.np_cd_done_selecting),
                interactionSource = closeInteraction,
                enabled = true,
                onClick = onCloseClick,
                size = iconSize,
                emphasized = true,
            )
            LyricsActionIcon(
                icon = rememberVectorPainter(YoinSymbols.Copy),
                contentDescription = stringResource(R.string.np_cd_copy_lyrics),
                interactionSource = copyInteraction,
                enabled = hasSelection,
                onClick = onCopyClick,
                size = iconSize,
            )
            LyricsActionIcon(
                icon = rememberVectorPainter(YoinSymbols.Share),
                contentDescription = stringResource(R.string.np_cd_share_lyrics_image),
                interactionSource = shareInteraction,
                enabled = hasSelection,
                onClick = onShareClick,
                size = iconSize,
            )
            LyricsActionIcon(
                icon = rememberVectorPainter(YoinSymbols.Edit),
                contentDescription = stringResource(R.string.np_cd_edit_lyrics),
                interactionSource = editInteraction,
                enabled = true,
                onClick = onEditClick,
                size = iconSize,
            )
        }
    }
}


/**
 * The lyric keys' connected group: four keys 8dp apart, the pressed one
 * widening into its neighbours (M3 Expressive's ButtonGroup press, same
 * expanded ratio). Hand-rolled on a plain [Layout] with
 * fixed slot math: the tools live inside the shell's SharedTransitionLayout,
 * and M3 ButtonGroup's measure policy built negative constraints in that
 * lookahead pass when the window rotated with the lyrics page open (crash,
 * 2026-10-21) — the same failure YoinButtonGroup and PlaySplitButton avoid.
 * Widths here never go below zero, whatever the pass.
 */
@Composable
private fun LyricsKeyRow(
    interactions: List<InteractionSource>,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    keyWidth: Dp = iconSize,
    keyGap: Dp = LyricsKeyGap,
    content: @Composable () -> Unit,
) {
    val pressSpec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Standard)
    val press = interactions.mapIndexed { index, interaction ->
        val pressed by interaction.collectIsPressedAsState()
        animateFloatAsState(
            targetValue = if (pressed) 1f else 0f,
            animationSpec = pressSpec,
            label = "lyricsKeyPress$index",
        )
    }
    Layout(content = content, modifier = modifier.height(iconSize)) { measurables, constraints ->
        val keyPx = keyWidth.roundToPx()
        val heightPx = iconSize.roundToPx()
        val gapPx = keyGap.roundToPx()
        val widths = lyricsKeyWidths(
            count = measurables.size,
            keyPx = keyPx,
            press = { index -> press.getOrNull(index)?.value ?: 0f },
        )
        val height = constraints.constrainHeight(heightPx)
        val placeables = measurables.mapIndexed { index, measurable ->
            measurable.measure(Constraints.fixed(widths[index], height))
        }
        val total = widths.sum() + gapPx * (measurables.size - 1).coerceAtLeast(0)
        layout(constraints.constrainWidth(total), height) {
            var x = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(x, 0)
                x += placeable.width + gapPx
            }
        }
    }
}

/**
 * Each key's width with [press] (0 = at rest, 1 = held, overshoot allowed)
 * applied: the pressed key gains [LyricsKeyExpandedRatio] of its
 * width, taken from its neighbours (both halves for a middle key, all of it
 * from the one beside an end key). The row's total stays put; no width goes
 * below zero.
 */
internal fun lyricsKeyWidths(count: Int, keyPx: Int, press: (Int) -> Float): IntArray {
    val widths = FloatArray(count) { keyPx.toFloat() }
    if (count > 1) {
        for (index in 0 until count) {
            val growth = press(index) * LyricsKeyExpandedRatio * keyPx
            if (growth == 0f) continue
            widths[index] += growth
            when (index) {
                0 -> widths[1] -= growth
                count - 1 -> widths[index - 1] -= growth
                else -> {
                    widths[index - 1] -= growth / 2f
                    widths[index + 1] -= growth / 2f
                }
            }
        }
    }
    return IntArray(count) { widths[it].roundToInt().coerceAtLeast(0) }
}

private val LyricsKeyGap = 8.dp
private val LyricsKeyMinGap = 4.dp

/** No lyric key narrows below this (adaptive-principles §6: touch targets never give way). */
internal val LyricsKeyMinWidth = 44.dp

/** M3 `ButtonGroupDefaults.ExpandedRatio`: how much of its width a pressed key gains. */
private const val LyricsKeyExpandedRatio = 0.15f

@Composable
private fun LyricsActionIcon(
    icon: Painter,
    contentDescription: String,
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    // The key that leaves a mode reads stronger than the actions beside it.
    emphasized: Boolean = false,
    // Off for a key whose result plays its own haptic (translate: the pluck).
    tapHaptic: Boolean = true,
) {
    val haptics = rememberYoinHaptics()
    FilledTonalIconButton(
        onClick = {
            if (tapHaptic) haptics.performContextClick()
            onClick()
        },
        enabled = enabled,
        modifier = modifier
            .size(width = size, height = size)
            .handPointer(enabled = enabled),
        interactionSource = interactionSource,
        shape = RoundedCornerShape(size * 0.31f),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (emphasized) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
            contentColor = if (emphasized) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
        ),
    ) {
        Icon(
            painter = icon,
            contentDescription = contentDescription,
        )
    }
}

@Preview
@Composable
private fun LyricsActionBarPreview() {
    YoinTheme {
        LyricsActionBar(
            actionInFlight = null,
            canTranslate = true,
            canSelect = true,
            canRecenter = false,
            onSearchClick = {},
            onTranslateClick = {},
            onSelectClick = {},
            onRecenterClick = {},
        )
    }
}

@Preview
@Composable
private fun LyricsSelectionBarPreview() {
    YoinTheme {
        LyricsSelectionBar(
            selectedCount = 2,
            onCloseClick = {},
            onCopyClick = {},
            onShareClick = {},
            onEditClick = {},
        )
    }
}
