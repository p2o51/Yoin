@file:Suppress("ktlint:standard:function-naming") // Composables are PascalCase

package com.gpo.yoin.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.YoinDropdownMenu
import com.gpo.yoin.ui.component.YoinDropdownMenuItem
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinTheme

/** The press area reaches this far past the label each side, the label staying on the page edge. */
private val SortRowPressBleed = 8.dp

/**
 * The order a Library view is in, at the top of its list (it scrolls away with
 * it): the order's name and the sort mark. A tap opens the orders the view
 * offers ([librarySortOptions]); the one in use carries a check.
 */
@Composable
internal fun LibrarySortRow(
    sort: LibrarySort,
    options: List<LibrarySort>,
    onSortSelected: (LibrarySort) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberYoinHaptics()
    val label = sort.label()
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .offset(x = -SortRowPressBleed)
                .minimumTouchTarget()
                .clip(RoundedCornerShape(12.dp))
                .clickable(
                    onClickLabel = stringResource(R.string.library_sort_action),
                    role = Role.DropdownList
                ) { expanded = true }
                .padding(horizontal = SortRowPressBleed, vertical = 10.dp)
                .seamFade(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Icon(
                imageVector = YoinSymbols.Sort,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        YoinDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                YoinDropdownMenuItem(
                    text = option.label(),
                    onClick = {
                        expanded = false
                        if (option != sort) {
                            haptics.performTick()
                            onSortSelected(option)
                        }
                    },
                    trailingIcon = if (option == sort) {
                        { Icon(imageVector = YoinSymbols.Check, contentDescription = null) }
                    } else {
                        null
                    }
                )
            }
        }
    }
}

@Composable
internal fun LibrarySort.label(): String = when (this) {
    LibrarySort.Recents -> stringResource(R.string.library_sort_recents)
    LibrarySort.RecentlyAdded -> stringResource(R.string.library_sort_recently_added)
    LibrarySort.Alphabetical -> stringResource(R.string.library_sort_alphabetical)
    LibrarySort.Creator -> stringResource(R.string.library_sort_creator)
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun LibrarySortRowPreview() {
    YoinTheme {
        var sort by remember { mutableStateOf(LibrarySort.Recents) }
        LibrarySortRow(
            sort = sort,
            options = LibrarySort.entries,
            onSortSelected = { sort = it },
            modifier = Modifier.padding(16.dp)
        )
    }
}
