package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.withTabularFigures

/** One metadata line: groups laid out with gaps, never joined by a glyph. */
sealed interface MetaGroup {
    /** A count or measure: the number emphasised, the unit muted ("12" + "tracks"). */
    data class Stat(val value: String, val unit: String = "") : MetaGroup

    /** Plain text at the line's normal emphasis. [muted] draws it at 60%. */
    data class Plain(val text: String, val muted: Boolean = false) : MetaGroup

    /** A kind label (Album, Single). [accent] uses the theme accent; otherwise muted. */
    data class Kind(val text: String, val accent: Boolean = false) : MetaGroup
}

/**
 * How many leading groups fit in [maxWidth]. Groups are dropped from the end,
 * whole. A group wider than the line is dropped too, so the result can be 0.
 * [gapPx] is the space between groups, not inside one.
 */
internal fun metaLineVisibleCount(groupWidths: List<Int>, gapPx: Int, maxWidth: Int): Int {
    if (groupWidths.isEmpty() || maxWidth <= 0) return 0
    var used = 0
    var count = 0
    for (width in groupWidths) {
        val next = if (count == 0) width else used + gapPx + width
        if (next > maxWidth) return count
        used = next
        count++
    }
    return count
}

private val MetaGroupGap = 12.dp
private val MetaInnerGap = 4.dp

@Composable
fun MetaLine(
    groups: List<MetaGroup>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    maxLines: Int = 1,
) {
    val gap = MetaGroupGap
    Layout(
        modifier = modifier,
        content = {
            groups.forEach { group ->
                MetaGroupText(group, style, maxLines)
            }
        },
    ) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val widths = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
        val count = metaLineVisibleCount(widths, gapPx, constraints.maxWidth)
        val placeables = measurables.take(count).map { it.measure(loose) }
        val width = placeables.fold(0) { acc, placeable ->
            if (acc == 0) placeable.width else acc + gapPx + placeable.width
        }
        val height = placeables.maxOfOrNull(Placeable::height) ?: 0
        layout(width, height) {
            var x = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width + gapPx
            }
        }
    }
}

@Composable
private fun MetaGroupText(group: MetaGroup, style: TextStyle, maxLines: Int) {
    val scheme = MaterialTheme.colorScheme
    when (group) {
        is MetaGroup.Stat -> Row(
            horizontalArrangement = Arrangement.spacedBy(MetaInnerGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = group.value,
                style = style.copy(fontWeight = FontWeight.Medium).withTabularFigures(),
                color = scheme.onSurface,
                maxLines = maxLines,
                softWrap = false,
            )
            if (group.unit.isNotEmpty()) {
                Text(
                    text = group.unit,
                    style = style.copy(fontWeight = FontWeight.Normal),
                    color = scheme.onSurface.copy(alpha = 0.6f),
                    maxLines = maxLines,
                    softWrap = false,
                )
            }
        }
        is MetaGroup.Plain -> Text(
            text = group.text,
            style = style,
            color = scheme.onSurface.copy(alpha = if (group.muted) 0.6f else 1f),
            maxLines = maxLines,
            softWrap = false,
        )
        is MetaGroup.Kind -> Text(
            text = group.text,
            style = MaterialTheme.typography.labelSmall,
            color = if (group.accent) scheme.primary else scheme.onSurfaceVariant,
            maxLines = maxLines,
            softWrap = false,
        )
    }
}

@Preview(showBackground = true, widthDp = 280)
@Composable
private fun MetaLinePreview() {
    YoinTheme {
        MetaLine(
            groups = listOf(
                MetaGroup.Plain("2024"),
                MetaGroup.Stat("12", "tracks"),
                MetaGroup.Stat("44", "min"),
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 120, name = "Drops the last group")
@Composable
private fun MetaLineNarrowPreview() {
    YoinTheme {
        MetaLine(
            groups = listOf(
                MetaGroup.Kind("Album", accent = true),
                MetaGroup.Plain("Artist"),
                MetaGroup.Plain("A very long album title", muted = true),
            ),
        )
    }
}
