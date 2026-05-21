package com.gpo.yoin.ui.nowplaying

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FullscreenTabGroup(
    selected: NowPlayingDetailPage,
    onSelect: (NowPlayingDetailPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        val lyricsInteraction = remember { MutableInteractionSource() }
        val aboutInteraction = remember { MutableInteractionSource() }
        val noteInteraction = remember { MutableInteractionSource() }

        ButtonGroup(
            overflowIndicator = { _ -> },
            modifier = modifier
                .fillMaxWidth()
                .height(52.dp),
            expandedRatio = ButtonGroupDefaults.ExpandedRatio,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Three buttons each take an equal 1f weight so the group
            // fills the available width. `animateWidth` still animates
            // size changes on press within the shared slot.
            customItem(
                buttonGroupContent = {
                    TabButton(
                        label = "Lyrics",
                        isSelected = selected == NowPlayingDetailPage.Lyrics,
                        interactionSource = lyricsInteraction,
                        onClick = { onSelect(NowPlayingDetailPage.Lyrics) },
                        modifier = Modifier.weight(1f),
                    )
                },
                menuContent = { _ -> },
            )
            customItem(
                buttonGroupContent = {
                    TabButton(
                        label = "About",
                        isSelected = selected == NowPlayingDetailPage.About,
                        interactionSource = aboutInteraction,
                        onClick = { onSelect(NowPlayingDetailPage.About) },
                        modifier = Modifier.weight(1f),
                    )
                },
                menuContent = { _ -> },
            )
            customItem(
                buttonGroupContent = {
                    TabButton(
                        label = "Note",
                        isSelected = selected == NowPlayingDetailPage.Note,
                        interactionSource = noteInteraction,
                        onClick = { onSelect(NowPlayingDetailPage.Note) },
                        modifier = Modifier.weight(1f),
                    )
                },
                menuContent = { _ -> },
            )
        }
    }
}


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ButtonGroupScope.TabButton(
    label: String,
    isSelected: Boolean,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "tabContainer",
    )
    val content by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        },
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "tabContent",
    )
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxHeight()
            .animateWidth(interactionSource),
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
        )
    }
}
