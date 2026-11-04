package com.gpo.yoin.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens

/**
 * Now Playing's tab row as a component: an Expressive button group of equal pill tabs, the selected one in
 * the ink, the rest tonal; a pressed tab widens a little (owner 2026-10-29: the scrapbook's track sheet uses
 * the same group as Now Playing's expanded page).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun YoinTabGroup(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    selectedContainer: Color = MaterialTheme.colorScheme.primary,
    selectedContent: Color = MaterialTheme.colorScheme.onPrimary,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        val interactions = remember(labels.size) { List(labels.size) { MutableInteractionSource() } }
        ButtonGroup(
            overflowIndicator = { _ -> },
            modifier = modifier
                .fillMaxWidth()
                .height(52.dp),
            expandedRatio = ButtonGroupDefaults.ExpandedRatio,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Each tab takes an equal 1f weight so the group fills the width; `animateWidth` still
            // widens a pressed one within the shared slot.
            labels.forEachIndexed { index, label ->
                customItem(
                    buttonGroupContent = {
                        TabButton(
                            label = label,
                            isSelected = index == selectedIndex,
                            interactionSource = interactions[index],
                            onClick = { onSelect(index) },
                            colors = TabColors(selectedContainer, selectedContent, container, content),
                            modifier = Modifier.weight(1f),
                        )
                    },
                    menuContent = { _ -> },
                )
            }
        }
    }
}

private class TabColors(
    val selectedContainer: Color,
    val selectedContent: Color,
    val container: Color,
    val content: Color,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ButtonGroupScope.TabButton(
    label: String,
    isSelected: Boolean,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    colors: TabColors,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        targetValue = if (isSelected) colors.selectedContainer else colors.container,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "tabContainer",
    )
    val content by animateColorAsState(
        targetValue = if (isSelected) colors.selectedContent else colors.content,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "tabContent",
    )
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxHeight()
            .animateWidth(interactionSource)
            .semantics { selected = isSelected },
        interactionSource = interactionSource,
        shape = YoinShapeTokens.Full,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = container,
            contentColor = content,
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
