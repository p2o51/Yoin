@file:Suppress("ktlint:standard:function-naming") // Composables are PascalCase

package com.gpo.yoin.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.flow.first

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
 * Unfolding, it scrolls itself into view, clear of the edge fades (a narrow
 * row on a phone, a landscape phone's header, a narrow Wide window).
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
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedVisibility(
            visible = filtered,
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
                            onSelectedChange = onPlaylistsByYouChange
                        )
                    }
                }
            }
        }
    }
}

/** Playlists' By You, unfolding from Playlists' trailing edge. */
@Composable
private fun LibraryByYouChip(visible: Boolean, selected: Boolean, onSelectedChange: (Boolean) -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = YoinMotion.expandHorizontally(role = YoinMotionRole.Standard, expandFrom = Alignment.Start) +
            YoinMotion.fadeIn(role = YoinMotionRole.Standard),
        exit = YoinMotion.shrinkHorizontally(role = YoinMotionRole.Standard, shrinkTowards = Alignment.Start) +
            YoinMotion.fadeOut(role = YoinMotionRole.Standard)
    ) {
        LibraryToggleChip(
            label = stringResource(R.string.library_filter_by_you),
            selected = selected,
            onSelectedChange = onSelectedChange,
            modifier = Modifier
                .padding(start = SubChipGap)
                .then(rememberRevealOnArrival())
        )
    }
}

/**
 * Scrolls the chip it is on into the row's view as it arrives, [SubChipRevealMargin]
 * clear of either end. It asks for the chip's final bounds: the chip measures
 * full size while it unfolds, and the chips after it give the row room to
 * reach them, so the scroll runs alongside the unfold instead of after it.
 */
@Composable
private fun rememberRevealOnArrival(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val marginPx = with(LocalDensity.current) { SubChipRevealMargin.toPx() }
    LaunchedEffect(requester) {
        val arrived = snapshotFlow { size }.first { it.width > 0 }
        try {
            requester.bringIntoView(Rect(-marginPx, 0f, arrived.width + marginPx, arrived.height.toFloat()))
        } catch (stopped: CancellationException) {
            // A drag on the row, or a row with no more room, ends the scroll
            // early; the chip has unfolded all the same. Leaving composition
            // still cancels this effect.
            currentCoroutineContext().ensureActive()
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
