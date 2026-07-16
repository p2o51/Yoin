package com.gpo.yoin.ui.detail

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Shared bottom floating toolbar shell for Album / Artist / Playlist detail
 * pages. Action content stays page-specific until the ▾ menus settle.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DetailFloatingToolbar(
    toolbarContainer: Color,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = toolbarContainer,
        ),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * The detail pages' bottom row: the floating toolbar and the mini-player dock
 * as ONE horizontally centered cluster (the caller centers it), instead of a
 * centered toolbar with the dock pinned to the screen edge. The row sizes to
 * the toolbar's intrinsic height and the dock fills it, so the two are
 * exactly equal-height by construction — no hand-tuned offsets.
 *
 * No arrangement spacing here on purpose: the toolbar↔dock gap lives inside
 * the dock's own show/hide animation (see DetailMiniPlayer), so a hidden dock
 * contributes exactly zero width and the toolbar stays perfectly centered.
 *
 * [toolbar] may be null (e.g. an empty playlist): the dock then stands alone.
 */
@Composable
internal fun DetailToolbarRow(
    miniPlayer: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    toolbar: (@Composable () -> Unit)?,
) {
    Row(
        modifier = modifier.height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        toolbar?.invoke()
        miniPlayer?.invoke()
    }
}
