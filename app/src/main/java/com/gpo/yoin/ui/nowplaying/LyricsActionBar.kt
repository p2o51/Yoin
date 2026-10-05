package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.symbols.rememberTranslateSymbolPainter
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme


@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
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
    searchModifier: Modifier = Modifier,
    // 52 in the bottom accessory; smaller when the tools ride the tab row
    // (16:9 lyrics page, enlarged phone — 断点交接 §3.1 / §3.4).
    iconSize: Dp = 52.dp,
) {
    val searchInteraction = remember { MutableInteractionSource() }
    val translateInteraction = remember { MutableInteractionSource() }
    val selectInteraction = remember { MutableInteractionSource() }
    val recenterInteraction = remember { MutableInteractionSource() }

    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        ButtonGroup(
            overflowIndicator = { _ -> },
            modifier = modifier.height(iconSize),
            expandedRatio = ButtonGroupDefaults.ExpandedRatio,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            customItem(
                buttonGroupContent = {
                    LyricsActionIcon(
                        icon = rememberVectorPainter(YoinSymbols.Search),
                        contentDescription = "Search lyrics",
                        interactionSource = searchInteraction,
                        enabled = actionInFlight == null,
                        onClick = onSearchClick,
                        size = iconSize,
                        modifier = searchModifier,
                    )
                },
                menuContent = { _ -> },
            )
            customItem(
                buttonGroupContent = {
                    // The translate key stays lit while its own translation runs, so
                    // the 文 ⇄ A orbit is visible (a disabled IconButton drops its
                    // content to 38% alpha); taps are ignored until it finishes.
                    val translating = actionInFlight == LyricsAction.Translate
                    LyricsActionIcon(
                        icon = rememberTranslateSymbolPainter(translating = translating),
                        contentDescription = "Translate lyrics",
                        interactionSource = translateInteraction,
                        enabled = (actionInFlight == null || translating) && canTranslate,
                        onClick = { if (actionInFlight == null) onTranslateClick() },
                        size = iconSize,
                    )
                },
                menuContent = { _ -> },
            )
            customItem(
                buttonGroupContent = {
                    // ✓ = select lines (copy / share as image). Editing the
                    // LRC moved into select mode's own bar; with no lines to
                    // select the ✓ still opens the editor (paste lyrics).
                    LyricsActionIcon(
                        icon = rememberVectorPainter(YoinSymbols.Check),
                        contentDescription = if (canSelect) "Select lyrics" else "Add lyrics",
                        interactionSource = selectInteraction,
                        enabled = actionInFlight == null,
                        onClick = onSelectClick,
                        size = iconSize,
                    )
                },
                menuContent = { _ -> },
            )
            customItem(
                buttonGroupContent = {
                    LyricsActionIcon(
                        icon = rememberVectorPainter(YoinSymbols.Recenter),
                        contentDescription = "Return to current line",
                        interactionSource = recenterInteraction,
                        enabled = actionInFlight == null && canRecenter,
                        onClick = onRecenterClick,
                        size = iconSize,
                    )
                },
                menuContent = { _ -> },
            )
        }
    }
}


/**
 * Select mode's keys, in the lyric tools' slot and footprint: leave, copy the
 * picked lines, share them as an image card, edit the lyrics (the LRC editor
 * the ✓ used to open).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
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
    val hasSelection = selectedCount > 0

    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        ButtonGroup(
            overflowIndicator = { _ -> },
            modifier = modifier.height(iconSize),
            expandedRatio = ButtonGroupDefaults.ExpandedRatio,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            customItem(
                buttonGroupContent = {
                    LyricsActionIcon(
                        icon = rememberVectorPainter(YoinSymbols.Close),
                        contentDescription = "Done selecting lyrics",
                        interactionSource = closeInteraction,
                        enabled = true,
                        onClick = onCloseClick,
                        size = iconSize,
                        emphasized = true,
                    )
                },
                menuContent = { _ -> },
            )
            customItem(
                buttonGroupContent = {
                    LyricsActionIcon(
                        icon = rememberVectorPainter(Icons.Rounded.ContentCopy),
                        contentDescription = "Copy selected lyrics",
                        interactionSource = copyInteraction,
                        enabled = hasSelection,
                        onClick = onCopyClick,
                        size = iconSize,
                    )
                },
                menuContent = { _ -> },
            )
            customItem(
                buttonGroupContent = {
                    LyricsActionIcon(
                        icon = rememberVectorPainter(YoinSymbols.Share),
                        contentDescription = "Share selected lyrics as an image",
                        interactionSource = shareInteraction,
                        enabled = hasSelection,
                        onClick = onShareClick,
                        size = iconSize,
                    )
                },
                menuContent = { _ -> },
            )
            customItem(
                buttonGroupContent = {
                    LyricsActionIcon(
                        icon = rememberVectorPainter(YoinSymbols.Edit),
                        contentDescription = "Edit lyrics",
                        interactionSource = editInteraction,
                        enabled = true,
                        onClick = onEditClick,
                        size = iconSize,
                    )
                },
                menuContent = { _ -> },
            )
        }
    }
}


@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ButtonGroupScope.LyricsActionIcon(
    icon: Painter,
    contentDescription: String,
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    // The key that leaves a mode reads stronger than the actions beside it.
    emphasized: Boolean = false,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(width = size, height = size)
            .animateWidth(interactionSource),
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
