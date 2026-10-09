package com.gpo.yoin.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.ui.component.ScrollEdgePreview
import com.gpo.yoin.ui.component.SeamTopPreference
import com.gpo.yoin.ui.component.SeamTopStyle
import com.gpo.yoin.ui.component.currentSeamTopStyle
import com.gpo.yoin.ui.component.descriptionRes
import com.gpo.yoin.ui.component.nameRes
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * Settings › Motion › Scroll edge as a page (owner 2026-10-09: a full page with pictures and words instead of
 * a menu). One card per style: a live preview of it, its name, what it does. The choice applies at once and
 * repaints every open page (SeamTopPreference, synced by Cloud sync like before).
 */
@Composable
internal fun ScrollEdgePageContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = rememberYoinHaptics()
    val selected = currentSeamTopStyle()
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.settings_scroll_edge_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 20.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SeamTopStyle.entries.forEach { style ->
                StyleCard(
                    style = style,
                    selected = style == selected,
                    onClick = {
                        if (style != selected) {
                            haptics.performContextClick()
                            SeamTopPreference.select(context, style)
                        }
                    },
                )
            }
        }
        Text(
            text = stringResource(R.string.settings_scroll_edge_foot),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 16.dp),
        )
    }
}

@Composable
private fun StyleCard(
    style: SeamTopStyle,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val surfaces = settingsSurfaces()
    val container by animateColorAsState(if (selected) scheme.primaryContainer else surfaces.row, YoinMotion.defaultEffectsSpec(), label = "edgeCard")
    val content by animateColorAsState(if (selected) scheme.onPrimaryContainer else scheme.onSurface, YoinMotion.defaultEffectsSpec(), label = "edgeCardText")
    val corner by animateDpAsState(if (selected) 32.dp else 24.dp, YoinMotion.defaultSpatialSpec(), label = "edgeCardCorner")
    Surface(
        shape = RoundedCornerShape(corner),
        color = container,
        contentColor = content,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)) {
            // Covers stay cover-sized: a wide pane gets more columns, not stretched ones.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                ScrollEdgePreview(
                    style = style,
                    playing = true,
                    columns = (maxWidth / PreviewColumnWidth).toInt().coerceIn(3, 8),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PreviewHeight),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 8.dp, end = 8.dp),
            ) {
                RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(start = 2.dp))
                Text(
                    text = stringResource(style.nameRes),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (style == SeamTopStyle.Default) {
                    Text(
                        text = stringResource(R.string.settings_scroll_edge_default),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSecondaryContainer,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .background(scheme.secondaryContainer, RoundedCornerShape(10.dp))
                            .padding(horizontal = 9.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                text = stringResource(style.descriptionRes),
                style = MaterialTheme.typography.bodyMedium,
                color = content.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 44.dp, end = 8.dp),
            )
        }
    }
}

private val PreviewHeight = 150.dp
private val PreviewColumnWidth = 112.dp

@Preview(showBackground = true)
@Composable
private fun ScrollEdgePagePreview() {
    YoinTheme {
        ScrollEdgePageContent(modifier = Modifier.padding(16.dp))
    }
}
