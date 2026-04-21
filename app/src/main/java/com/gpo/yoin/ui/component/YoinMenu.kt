package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * App-wide menus built on the Material Design 3 Expressive dropdown
 * architecture:
 *
 * - [DropdownMenuPopup] supplies the popup shell (positioning + dismiss).
 * - [DropdownMenuGroup] supplies the visible rounded container, container
 *   color, and tonal/shadow elevation, pulled from [MenuDefaults.groupShapes]
 *   and [MenuDefaults.groupStandardContainerColor] so we inherit whatever
 *   shape/color tokens the Expressive library defines for the current theme
 *   (see https://m3.material.io/components/menus/specs).
 * - [DropdownMenuItem] provides the row itself; typography, padding, and
 *   hover/pressed state layers come from the library.
 *
 * **Use [YoinDropdownMenu] + [YoinDropdownMenuItem] for every new menu.**
 * Never drop raw `DropdownMenu` into new code — the legacy flat-surface API
 * predates the group pattern and won't pick up the Expressive container
 * tokens.
 *
 * For menus with semantically distinct sections (e.g. destructive actions
 * split from primary actions), compose multiple [YoinDropdownMenuSection]s
 * inside a single [YoinDropdownMenu] so [MenuDefaults.groupShape] can shape
 * the leading / middle / trailing groups correctly.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun YoinDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
    ) {
        DropdownMenuGroup(shapes = MenuDefaults.groupShapes()) {
            content()
        }
    }
}

/**
 * A visually distinct section inside a [YoinDropdownMenu] — useful when a
 * menu has separate buckets (e.g. editing vs destructive). The caller passes
 * its `index` and the total `count` of sections so the library picks the
 * correct leading / middle / trailing / standalone shape automatically.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun YoinDropdownMenuSection(
    index: Int,
    count: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenuGroup(shapes = MenuDefaults.groupShape(index = index, count = count)) {
        content()
    }
}

@Composable
fun YoinDropdownMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    DropdownMenuItem(
        text = { Text(text = text) },
        onClick = onClick,
        modifier = modifier,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
    )
}
