package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VerticalAlignCenter
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotionRole


@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LyricsActionBar(
    actionInFlight: LyricsAction?,
    canTranslate: Boolean,
    canRecenter: Boolean,
    onSearchClick: () -> Unit,
    onTranslateClick: () -> Unit,
    onApplyClick: () -> Unit,
    onRecenterClick: () -> Unit,
    modifier: Modifier = Modifier,
    searchModifier: Modifier = Modifier,
    // 52 in the bottom accessory; smaller when the tools ride the tab row
    // (16:9 lyrics page, enlarged phone — 断点交接 §3.1 / §3.4).
    iconSize: Dp = 52.dp,
) {
    val searchInteraction = remember { MutableInteractionSource() }
    val translateInteraction = remember { MutableInteractionSource() }
    val applyInteraction = remember { MutableInteractionSource() }
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
                        icon = Icons.Rounded.Search,
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
                    LyricsActionIcon(
                        icon = Icons.Rounded.Translate,
                        contentDescription = "Translate lyrics",
                        interactionSource = translateInteraction,
                        enabled = actionInFlight == null && canTranslate,
                        onClick = onTranslateClick,
                        size = iconSize,
                    )
                },
                menuContent = { _ -> },
            )
            customItem(
                buttonGroupContent = {
                    LyricsActionIcon(
                        icon = Icons.Rounded.Check,
                        contentDescription = "Apply lyrics",
                        interactionSource = applyInteraction,
                        enabled = actionInFlight == null,
                        onClick = onApplyClick,
                        size = iconSize,
                    )
                },
                menuContent = { _ -> },
            )
            customItem(
                buttonGroupContent = {
                    LyricsActionIcon(
                        icon = Icons.Rounded.VerticalAlignCenter,
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


@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ButtonGroupScope.LyricsActionIcon(
    icon: ImageVector,
    contentDescription: String,
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
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
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
        )
    }
}
