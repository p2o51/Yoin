package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinShapeTokens

/**
 * App-wide menu components that conform to the Material Design 3 Expressive
 * menu spec (https://m3.material.io/components/menus/specs):
 *
 * - Rounded corner container (Medium / 12dp) instead of default extraSmall
 *   (4dp) — Expressive menus favor softer, more rounded containers.
 * - `surfaceContainer` container color for clear separation from surface.
 * - BodyLarge typography for menu item text (Expressive spec) instead of
 *   LabelLarge (classic M3).
 * - Consistent leading icon sizing, 12dp horizontal / 8dp vertical item padding.
 *
 * **Use [YoinDropdownMenu] + [YoinDropdownMenuItem] for every new menu.**
 * Never drop raw `DropdownMenu` into new code.
 */
@Composable
fun YoinDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        shape = YoinShapeTokens.Medium,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = MenuDefaults.TonalElevation,
        shadowElevation = MenuDefaults.ShadowElevation,
        content = content,
    )
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
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        onClick = onClick,
        modifier = modifier,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = MenuDefaults.itemColors(),
    )
}
