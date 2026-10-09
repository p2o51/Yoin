package com.gpo.yoin.ui.landing

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.component.ScrollEdgePreview
import com.gpo.yoin.ui.component.SeamTopStyle
import com.gpo.yoin.ui.component.nameRes
import com.gpo.yoin.ui.component.shortRes
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * The scroll-edge step: the three styles side by side, each a live preview, the chosen one outlined; under
 * them one line on the chosen style. That line's box is as tall as the longest of the three, so picking never
 * changes the window's height (owner 2026-10-09: no jump between options). The choice applies at once
 * (SeamTopPreference), the same as Settings › Scroll edge.
 */
@Composable
internal fun ScrollEdgeScene(
    selected: SeamTopStyle,
    playing: Boolean,
    onSelect: (SeamTopStyle) -> Unit,
) {
    val haptics = rememberYoinHaptics()
    LandingSceneColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SeamTopStyle.entries.forEach { style ->
                EdgeOption(
                    style = style,
                    selected = style == selected,
                    playing = playing,
                    onClick = {
                        if (style != selected) haptics.performContextClick()
                        onSelect(style)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceBright,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            // All three lines are laid out in one cell; only the chosen one is visible.
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                SeamTopStyle.entries.forEach { style ->
                    val alpha by animateFloatAsState(if (style == selected) 1f else 0f, YoinMotion.defaultEffectsSpec(), label = "edgeLine")
                    val name = stringResource(style.nameRes)
                    val line = stringResource(style.shortRes)
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(name) }
                            append("  ")
                            append(line)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .graphicsLayer { this.alpha = alpha }
                            .then(if (style == selected) Modifier else Modifier.clearAndSetSemantics {}),
                    )
                }
            }
        }
    }
}

@Composable
private fun EdgeOption(
    style: SeamTopStyle,
    selected: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val corner by animateDpAsState(if (selected) 26.dp else 18.dp, YoinMotion.defaultSpatialSpec(), label = "edgeCorner")
    val ring by animateColorAsState(if (selected) scheme.primary else Color.Transparent, YoinMotion.defaultEffectsSpec(), label = "edgeRing")
    val nameColor by animateColorAsState(if (selected) scheme.primary else scheme.onSurfaceVariant, YoinMotion.defaultEffectsSpec(), label = "edgeName")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .semantics {
                this.selected = selected
            }
            .clickable(role = Role.RadioButton, onClick = onClick),
    ) {
        val shape = RoundedCornerShape(corner)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(100f / 132f)
                .clip(shape)
                .border(3.dp, ring, shape),
        ) {
            ScrollEdgePreview(style = style, playing = playing, modifier = Modifier.fillMaxSize())
        }
        Text(
            text = stringResource(style.nameRes),
            style = MaterialTheme.typography.labelLarge,
            color = nameColor,
            maxLines = 1,
            overflow = TextOverflow.Visible,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ScrollEdgeScenePreview() {
    YoinTheme {
        ScrollEdgeScene(selected = SeamTopStyle.Tide, playing = false, onSelect = {})
    }
}
