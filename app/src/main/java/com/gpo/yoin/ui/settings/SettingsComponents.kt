package com.gpo.yoin.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.symbols.rememberExpandSymbolPainter
import com.gpo.yoin.ui.component.ExpressiveTextField
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens

// Shared building blocks for Settings and its service setup pages, after
// Pixel Settings (Android 16 Expressive): a quiet primary label over a stack
// of rows, each row its own segment (M3 Expressive's 2dp segmented gap, no
// dividers), plain line icons, and in-place expansion for the few settings
// that need fields. Keeps both pages one visual family.
//
// Segment radii follow Pixel's SettingsLib (measured on the user's reference
// and the Pixel Tablet's SettingsGoogle.apk): 20dp outer, 4dp inner, 28dp for
// the open row of the two-pane list. M3 Compose's `segmentedShapes` reads
// 16dp and gives a lone row 4dp all round, so the shapes are built here.

/**
 * The shape of the segment a row sits in, so a clickable or selected row
 * clips its ripple / fill to exactly that segment.
 */
internal val LocalSettingsRowShape = staticCompositionLocalOf<Shape> { YoinContainerShapes.Panel }

/** The shape a selected row takes (all corners round, detached from its neighbours). */
internal val LocalSettingsRowSelectedShape = staticCompositionLocalOf<Shape> { SettingsSelectedRowShape }

private val SettingsSegmentOuterRadius = 20.dp
private val SettingsSegmentInnerRadius = 4.dp
private val SettingsSelectedRowShape = RoundedCornerShape(28.dp)

/** First / middle / last / only: outer corners round, inner corners tight (circular, so they can animate). */
internal fun settingsSegmentShape(index: Int, count: Int): Shape {
    val top = if (index == 0) SettingsSegmentOuterRadius else SettingsSegmentInnerRadius
    val bottom = if (index == count - 1) SettingsSegmentOuterRadius else SettingsSegmentInnerRadius
    return RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}

@DslMarker
internal annotation class SettingsGroupDsl

/** Collects the rows of a [SettingsGroup]; each [item] becomes one segment. */
@SettingsGroupDsl
internal class SettingsGroupScope {
    internal class Row(val key: Any, val paintsOwnSegment: Boolean, val content: @Composable () -> Unit)

    internal val items = mutableListOf<Row>()

    /**
     * One row. Give data-driven rows a [key] (an id) so a row's state stays
     * with it when rows above it come and go. [paintsOwnSegment]: the row
     * draws its own background (a selectable row that changes colour and
     * shape when selected) — the segment behind it stays clear.
     */
    fun item(key: Any? = null, paintsOwnSegment: Boolean = false, content: @Composable () -> Unit) {
        items += Row(key ?: IndexKey(items.size), paintsOwnSegment, content)
    }

    private data class IndexKey(val index: Int)
}

/** A labelled group: small primary-tinted label, then its rows as segments. */
@Composable
internal fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    trailingLabel: String? = null,
    content: SettingsGroupScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsGroupLabel(title = title, trailingLabel = trailingLabel)
        SettingsSegments(content = content)
    }
}

/**
 * A group's rows without the label: each one a segment on the row surface,
 * shaped by its position ([settingsSegmentShape]). When a row's position
 * changes (a row above or below comes or goes) its corners settle on the
 * spatial spring instead of snapping.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsSegments(
    modifier: Modifier = Modifier,
    content: SettingsGroupScope.() -> Unit,
) {
    val items = SettingsGroupScope().apply(content).items
    val rowColor = settingsSurfaces().row
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        items.forEachIndexed { index, item ->
            key(item.key) {
                val shape = animateShapeAsState(remember(index, items.size) { settingsSegmentShape(index, items.size) })
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = shape,
                    color = if (item.paintsOwnSegment) Color.Transparent else rowColor,
                ) {
                    CompositionLocalProvider(LocalSettingsRowShape provides shape, content = item.content)
                }
            }
        }
    }
}

/** The group label on its own, for groups whose body isn't segments (e.g. a full-bleed card row). */
@Composable
internal fun SettingsGroupLabel(
    title: String,
    modifier: Modifier = Modifier,
    trailingLabel: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Pixel sets the label 8dp inside the rows' edge.
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
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

/**
 * The icon that leads a settings row. A plain line icon (Pixel's sub-page
 * rows); with a [tone] — only where the row IS a service — the pastel
 * service circle instead.
 */
@Composable
internal fun SettingsRowIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: SettingsTone? = null,
) {
    if (tone == null) {
        Box(modifier = modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        return
    }
    Surface(
        modifier = modifier.size(40.dp),
        shape = YoinShapeTokens.Full,
        color = tone.iconContainer,
        contentColor = tone.iconContent,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
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
    iconTone: SettingsTone? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val haptics = rememberYoinHaptics()
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SettingsRowMinHeight)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(SettingsRowIconGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsRowIcon(icon, tone = iconTone)
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
            shape = LocalSettingsRowShape.current,
            color = Color.Transparent,
        ) { row() }
    } else {
        Box(modifier = modifier.fillMaxWidth()) { row() }
    }
}

/** Pixel / M3 two-line list row height. */
private val SettingsRowMinHeight = 72.dp

/** Icon column (40dp) to text, as in Pixel Settings and M3 list tokens. */
private val SettingsRowIconGap = 12.dp

/** Where a row's text starts: 16dp inset + 40dp icon column + the gap. */
internal val SettingsRowTextInset = 16.dp + 40.dp + SettingsRowIconGap

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
    iconTone: SettingsTone? = null,
    // False on a feature's own page (list-detail right pane): always open,
    // no chevron, the header row is just the heading.
    collapsible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val open = expanded || !collapsible
    val segment = LocalSettingsRowShape.current
    Column(modifier = modifier.fillMaxWidth()) {
        // Open, the header is only the top of the segment: its press ripple
        // keeps the segment's top corners and runs square into the fields.
        CompositionLocalProvider(LocalSettingsRowShape provides if (open) OpenBottomShape(segment) else segment) {
            SettingsItem(
                icon = icon,
                title = title,
                summary = summary,
                iconTone = iconTone,
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
        }
        AnimatedVisibility(
            visible = open,
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
                    .padding(start = SettingsRowTextInset, end = 16.dp, bottom = 18.dp),
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

/**
 * [target], but when it changes the corners travel there on the spatial
 * spring (segments whose position changes, a row turning selected). Rounded
 * outlines lerp corner by corner; anything else swaps at the midpoint.
 */
@Composable
internal fun animateShapeAsState(target: Shape): Shape {
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    val spec = YoinMotion.spatialSpring<Float>()
    LaunchedEffect(target) {
        if (target == to) return@LaunchedEffect
        // Start from wherever the corners are right now.
        from = if (progress.value >= 1f) to else LerpShape(from, to, progress.value)
        to = target
        progress.snapTo(0f)
        progress.animateTo(1f, spec)
    }
    val value = progress.value
    return if (value >= 1f) to else LerpShape(from, to, value)
}

private class LerpShape(
    private val start: Shape,
    private val end: Shape,
    private val fraction: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val a = start.createOutline(size, layoutDirection, density)
        val b = end.createOutline(size, layoutDirection, density)
        if (a is Outline.Rounded && b is Outline.Rounded) {
            val ra = a.roundRect
            val rb = b.roundRect
            return Outline.Rounded(
                RoundRect(
                    left = rb.left,
                    top = rb.top,
                    right = rb.right,
                    bottom = rb.bottom,
                    topLeftCornerRadius = lerp(ra.topLeftCornerRadius, rb.topLeftCornerRadius, fraction),
                    topRightCornerRadius = lerp(ra.topRightCornerRadius, rb.topRightCornerRadius, fraction),
                    bottomRightCornerRadius = lerp(ra.bottomRightCornerRadius, rb.bottomRightCornerRadius, fraction),
                    bottomLeftCornerRadius = lerp(ra.bottomLeftCornerRadius, rb.bottomLeftCornerRadius, fraction),
                ),
            )
        }
        return if (fraction < 0.5f) a else b
    }
}

/** [inner]'s top corners with the bottom left open: the outline runs past the bottom edge. */
private class OpenBottomShape(private val inner: Shape) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        inner.createOutline(Size(size.width, size.height * 2f), layoutDirection, density)

    override fun equals(other: Any?): Boolean = other is OpenBottomShape && other.inner == inner

    override fun hashCode(): Int = inner.hashCode()
}

/**
 * The row's segment shape, or — [selected] — the detached all-round shape,
 * travelling between them on the spatial spring. Driven by one fraction, so
 * it keeps up while the segment's own corners are still settling.
 */
@Composable
internal fun animateSelectedRowShape(selected: Boolean): Shape {
    val rowShape = LocalSettingsRowShape.current
    val selectedShape = LocalSettingsRowSelectedShape.current
    val fraction by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = YoinMotion.spatialSpring(),
        label = "rowSelectionShape",
    )
    return when (fraction) {
        0f -> rowShape
        1f -> selectedShape
        else -> LerpShape(rowShape, selectedShape, fraction)
    }
}

