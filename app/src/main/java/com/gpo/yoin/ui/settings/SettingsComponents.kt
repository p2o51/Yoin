package com.gpo.yoin.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.symbols.rememberExpandSymbolPainter
import com.gpo.yoin.ui.component.ExpressiveSectionPanel
import com.gpo.yoin.ui.component.ExpressiveTextField
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens

// Shared building blocks for Settings and its service setup pages: a quiet
// group label over one tonal panel, rows inside it, and in-place expansion
// for the few settings that need fields. Keeps both pages one visual family.

/** A labelled group: small primary-tinted label, then one panel holding its rows. */
@Composable
internal fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    trailingLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsGroupLabel(title = title, trailingLabel = trailingLabel)
        ExpressiveSectionPanel(
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 1.dp,
            content = content,
        )
    }
}

/** The group label on its own, for groups whose body isn't a panel (e.g. a full-bleed card row). */
@Composable
internal fun SettingsGroupLabel(
    title: String,
    modifier: Modifier = Modifier,
    trailingLabel: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (trailingLabel != null) {
            Text(
                text = trailingLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Hairline between rows of one [SettingsGroup] panel, inset past the icon column. */
@Composable
internal fun SettingsRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 72.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

/** Tonal icon badge that leads every settings row. */
@Composable
internal fun SettingsRowIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(40.dp),
        shape = YoinShapeTokens.Full,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
}

/** One settings row: icon · title/summary · optional trailing control. */
@Composable
internal fun SettingsItem(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val haptics = rememberYoinHaptics()
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsRowIcon(icon)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (summary != null) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing,
                )
            }
        }
    }
    if (onClick != null) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            onClick = {
                haptics.performClick()
                onClick()
            },
            shape = YoinContainerShapes.Panel,
            color = Color.Transparent,
        ) { row() }
    } else {
        Box(modifier = modifier.fillMaxWidth()) { row() }
    }
}

/**
 * A row that opens in place to reveal its fields. Height grows on the spatial
 * spring, the body fades on the effects spring, and the chevron folds over
 * like a hinge (flattens, then flips — it doesn't spin).
 */
@Composable
internal fun SettingsExpandableItem(
    icon: ImageVector,
    title: String,
    summary: String?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    // False on a feature's own page (list-detail right pane): always open,
    // no chevron, the header row is just the heading.
    collapsible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SettingsItem(
            icon = icon,
            title = title,
            summary = summary,
            onClick = if (collapsible) ({ onExpandedChange(!expanded) }) else null,
            trailing = if (collapsible) {
                {
                    Icon(
                        painter = rememberExpandSymbolPainter(expanded),
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                null
            },
        )
        AnimatedVisibility(
            visible = expanded || !collapsible,
            enter = expandVertically(
                animationSpec = YoinMotion.spatialSpring(),
                expandFrom = Alignment.Top,
            ) + YoinMotion.fadeIn(role = YoinMotionRole.Standard),
            exit = shrinkVertically(
                animationSpec = YoinMotion.spatialSpring(),
                shrinkTowards = Alignment.Top,
            ) + YoinMotion.fadeOut(role = YoinMotionRole.Standard),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 72.dp, end = 16.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}

/** Masked input with a show/hide toggle, for passwords, keys and tokens. */
@Composable
internal fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    var visible by remember { mutableStateOf(false) }
    ExpressiveTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingContent = {
            // The trailing slot sits inline with the ~24dp text row, so a
            // plain 44dp minimumTouchTarget would stretch the whole field.
            // Pin the slot to icon size and let the 44dp touch area overflow
            // into the field's padding (requiredSize can break the pinned
            // constraints; hit testing extends past the unclipped parent).
            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                IconButton(
                    onClick = {
                        haptics.performTick()
                        visible = !visible
                    },
                    modifier = Modifier.requiredSize(44.dp),
                ) {
                    Icon(
                        imageVector = if (visible) YoinSymbols.VisibilityOff else YoinSymbols.Visibility,
                        contentDescription = if (visible) "Hide $label" else "Show $label",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        modifier = modifier,
    )
}
