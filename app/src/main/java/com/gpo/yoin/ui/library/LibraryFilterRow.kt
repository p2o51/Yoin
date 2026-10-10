@file:Suppress("ktlint:standard:function-naming") // Composables are PascalCase

package com.gpo.yoin.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ExpressiveSegmentedTabs
import com.gpo.yoin.ui.component.elasticPress
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme

/** Space between the ✕ and the first chip: the chips' own gap. */
private val ClearChipGap = 8.dp

/** The ✕ matches the chips' height (ExpressiveSegmentedTabs, 44dp). */
private val ClearChipSize = 44.dp

/**
 * Library's chip row, after Spotify's Your Library: one chip per view, and
 * none on is All, the resting view. With a chip on, a ✕ unfolds in front of
 * the row and turns it off again; so does tapping the chip that is on. The ✕
 * stays put while the chips scroll, so All is always one tap away.
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
    horizontalPadding: Dp = 16.dp
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
        ExpressiveSegmentedTabs(
            items = chips,
            // All is no chip, so with All on none of them is selected.
            selectedItem = selectedTab,
            label = label,
            onSelectedChange = { tab -> onTabSelected(if (tab == selectedTab) LibraryTab.All else tab) },
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = chipsStart, end = horizontalPadding)
        )
    }
}

@Composable
private fun LibraryClearFilterChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier
            .size(ClearChipSize)
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
        var selected by remember { mutableStateOf(LibraryTab.Albums) }
        LibraryFilterRow(
            tabs = LibraryTab.Chips,
            selectedTab = selected,
            label = { it.name },
            onTabSelected = { selected = it }
        )
    }
}
