@file:Suppress("ktlint:standard:function-naming") // Composables are PascalCase

package com.gpo.yoin.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ExpressiveSegmentChip
import com.gpo.yoin.ui.component.ExpressiveSegmentRow
import com.gpo.yoin.ui.component.elasticPress
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.transformWhile

/** Space between the ✕ and the first chip: the chips' own gap. */
private val ClearChipGap = 8.dp

/** The chips' height (ExpressiveSegmentChip): the ✕ and By You match it. */
private val ChipHeight = 44.dp

/** Between Playlists and its By You: the chips' own gap, which unfolds with By You. */
private val SubChipGap = 8.dp

/**
 * How far clear of the row's ends an unfolding By You comes to rest: past the
 * row's scroll-aware edge fade (24dp), so it lands readable.
 */
private val SubChipRevealMargin = 24.dp

/**
 * Library's chip row, after Spotify's Your Library: one chip per view, and
 * none on is All, the resting view. With a chip on, a ✕ unfolds in front of
 * the row and turns it off again; so does tapping the chip that is on. The ✕
 * stays put while the chips scroll, so All is always one tap away.
 *
 * With Playlists on, its By You sub-chip unfolds right after it whenever the
 * playlists are mixed ([PlaylistsByYou.available]), and the chips after it
 * make way on the same spatial spring. By You is a toggle over the Playlists
 * view, not a view: leaving Playlists folds it away and keeps it as it was.
 * Unfolding, it scrolls itself into view, clear of the edge fades, where the
 * row is too short for it (a Wide column the detail pane narrows, whose head
 * row the ✕ narrows further as it unfolds) or was scrolled with Playlists at
 * an end.
 *
 * Library is a root section: none of this touches system back, which still
 * leaves the app from any chip.
 *
 * @param horizontalPadding the page margin the row's ends keep while resting.
 */
@Composable
internal fun LibraryFilterRow(
    tabs: List<LibraryTab>,
    selectedTab: LibraryTab,
    label: (LibraryTab) -> String,
    onTabSelected: (LibraryTab) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
    playlistsByYou: PlaylistsByYou = PlaylistsByYou(),
    onPlaylistsByYouChange: (Boolean) -> Unit = {}
) {
    val chips = remember(tabs) { tabs.filter { it != LibraryTab.All } }
    val filtered = selectedTab != LibraryTab.All
    val haptics = rememberYoinHaptics()
    // The ✕ brings its own page margin; the chips' inset gives way to the gap
    // after it, so the first chip slides over by the ✕'s width and no more.
    val chipsStart by animateDpAsState(
        targetValue = if (filtered) ClearChipGap else horizontalPadding,
        animationSpec = YoinMotion.spatialSpring()
    )
    // The ✕'s own state and the row's width, for By You's reveal: from All the
    // ✕ unfolds in front of the row on the same frames as By You, narrowing
    // the row under it. Only that effect reads them, so a frame of the unfold
    // recomposes nothing.
    val clearChipVisibility = remember { MutableTransitionState(filtered) }
    clearChipVisibility.targetState = filtered
    var rowWidth by remember { mutableIntStateOf(0) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedVisibility(
            visibleState = clearChipVisibility,
            enter = YoinMotion.expandHorizontally(role = YoinMotionRole.Standard, expandFrom = Alignment.Start) +
                YoinMotion.fadeIn(role = YoinMotionRole.Standard),
            exit = YoinMotion.shrinkHorizontally(role = YoinMotionRole.Standard, shrinkTowards = Alignment.Start) +
                YoinMotion.fadeOut(role = YoinMotionRole.Standard)
        ) {
            LibraryClearFilterChip(
                onClick = {
                    haptics.performTick()
                    onTabSelected(LibraryTab.All)
                },
                modifier = Modifier.padding(start = horizontalPadding)
            )
        }
        ExpressiveSegmentRow(
            modifier = Modifier
                .weight(1f)
                .onSizeChanged { rowWidth = it.width }
                .selectableGroup(),
            contentPadding = PaddingValues(start = chipsStart, end = horizontalPadding)
        ) {
            chips.forEach { tab ->
                // A chip and its sub-chip are one item of the row, so the gap
                // before By You unfolds with it instead of snapping in.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ExpressiveSegmentChip(
                        label = label(tab),
                        // All is no chip, so with All on none of them is selected.
                        selected = tab == selectedTab,
                        onClick = { onTabSelected(if (tab == selectedTab) LibraryTab.All else tab) }
                    )
                    if (tab == LibraryTab.Playlists) {
                        LibraryByYouChip(
                            visible = selectedTab == LibraryTab.Playlists && playlistsByYou.available,
                            selected = playlistsByYou.selected,
                            onSelectedChange = onPlaylistsByYouChange,
                            rowWidth = { rowWidth },
                            rowSettled = { clearChipVisibility.isIdle }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Playlists' By You, unfolding from Playlists' trailing edge.
 *
 * @param rowWidth the chip row's width now, its view.
 * @param rowSettled the ✕ in front of the row is not unfolding or folding,
 *   so the row is done changing width.
 */
@Composable
private fun LibraryByYouChip(
    visible: Boolean,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    rowWidth: () -> Int,
    rowSettled: () -> Boolean
) {
    AnimatedVisibility(
        visible = visible,
        enter = YoinMotion.expandHorizontally(role = YoinMotionRole.Standard, expandFrom = Alignment.Start) +
            YoinMotion.fadeIn(role = YoinMotionRole.Standard),
        exit = YoinMotion.shrinkHorizontally(role = YoinMotionRole.Standard, shrinkTowards = Alignment.Start) +
            YoinMotion.fadeOut(role = YoinMotionRole.Standard)
    ) {
        val enterExit = transition
        LibraryToggleChip(
            label = stringResource(R.string.library_filter_by_you),
            selected = selected,
            onSelectedChange = onSelectedChange,
            modifier = Modifier
                .padding(start = SubChipGap)
                .then(
                    rememberRevealOnArrival(
                        arriving = {
                            enterExit.targetState == EnterExitState.Visible &&
                                (enterExit.isRunning || !rowSettled())
                        },
                        rowWidth = rowWidth
                    )
                )
        )
    }
}

/** What By You's reveal asks again on: the chip's size, the row's width, and whether it is still arriving. */
private data class RevealFrame(val chip: IntSize, val rowWidth: Int, val arriving: Boolean)

/**
 * Scrolls the chip it is on into the row's view as it arrives, [SubChipRevealMargin]
 * clear of either end. It asks for the chip's final bounds: the chip measures
 * full size while it unfolds, and the chips after it give the row room to
 * reach them, so the scroll runs alongside the unfold instead of after it.
 *
 * It asks again each time the row's width changes while the chip is
 * [arriving], and once more as it lands: from All, the ✕ unfolds in front of
 * the row on the same frames and narrows it, so a chip that was in view when
 * it first asked could come to rest under the far end's fade (a Wide column
 * the detail pane narrows to 840dp). An ask that is already met returns at
 * once. Past the arrival the row is the user's: a later resize or a drag
 * never pulls the chip back.
 */
@Composable
private fun rememberRevealOnArrival(arriving: () -> Boolean, rowWidth: () -> Int): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val marginPx = with(LocalDensity.current) { SubChipRevealMargin.toPx() }
    val isArriving by rememberUpdatedState(arriving)
    val currentRowWidth by rememberUpdatedState(rowWidth)
    LaunchedEffect(requester) {
        snapshotFlow { RevealFrame(size, currentRowWidth(), isArriving()) }
            .filter { it.chip.width > 0 }
            // Through the arrival, then the frame it lands on.
            .transformWhile { frame ->
                emit(frame)
                frame.arriving
            }
            .collect { frame ->
                val clear = Rect(-marginPx, 0f, frame.chip.width + marginPx, frame.chip.height.toFloat())
                try {
                    requester.bringIntoView(clear)
                } catch (stopped: CancellationException) {
                    // A drag on the row, or a row with no more room, ends the
                    // scroll early; the chip has unfolded all the same.
                    // Leaving composition still cancels this effect.
                    currentCoroutineContext().ensureActive()
                }
            }
    }
    return remember(requester) {
        Modifier
            .bringIntoViewRequester(requester)
            .onSizeChanged { size = it }
    }
}

/**
 * An on/off chip in the segment chips' look ([ExpressiveSegmentChip]): the
 * same height, shape and colours, the same spring on its colour. It reads as
 * a checkbox to accessibility services, not as one of the row's tabs.
 */
@Composable
private fun LibraryToggleChip(
    label: String,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = rememberYoinHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "toggleChipContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "toggleChipContent"
    )
    Surface(
        checked = selected,
        onCheckedChange = { on ->
            haptics.performToggle(on)
            onSelectedChange(on)
        },
        // Outermost, so the role sits on the toggle's own node.
        modifier = modifier
            .semantics { role = Role.Checkbox }
            .heightIn(min = ChipHeight)
            .elasticPress(interactionSource),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        contentColor = contentColor,
        interactionSource = interactionSource
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LibraryClearFilterChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier
            .size(ChipHeight)
            .elasticPress(interactionSource),
        interactionSource = interactionSource,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Icon(
            imageVector = YoinSymbols.Close,
            contentDescription = stringResource(R.string.library_cd_clear_filter),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun LibraryFilterRowPreview() {
    YoinTheme {
        var selected by remember { mutableStateOf(LibraryTab.Playlists) }
        var byYou by remember { mutableStateOf(false) }
        LibraryFilterRow(
            tabs = LibraryTab.Chips,
            selectedTab = selected,
            label = { it.name },
            onTabSelected = { selected = it },
            playlistsByYou = PlaylistsByYou(available = true, selected = byYou),
            onPlaylistsByYouChange = { byYou = it }
        )
    }
}
