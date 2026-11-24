package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.withTabularFigures

/** One metadata line: groups laid out with gaps, never joined by a glyph. */
sealed interface MetaGroup {
    /** A count or measure: the number emphasised, the unit muted ("12" + "tracks"). */
    data class Stat(val value: String, val unit: String = "") : MetaGroup

    /** Plain text at the line's normal emphasis. [muted] draws it at 60%. */
    data class Plain(val text: String, val muted: Boolean = false) : MetaGroup

    /** A kind label (Album, Single), at the line's size. [accent] uses the theme accent; otherwise muted. */
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
    // The line's ink. Unspecified = the theme's onSurface; a coloured card
    // (Activities) passes its own content colour so the line matches its text.
    color: Color = Color.Unspecified,
) {
    val gap = MetaGroupGap
    val ink = color.takeOrElse { MaterialTheme.colorScheme.onSurface }
    Layout(
        modifier = modifier,
        content = {
            groups.forEach { group ->
                MetaGroupText(group, style, maxLines, ink, inkIsTheme = color == Color.Unspecified)
            }
        },
    ) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        // Every group is measured at its natural width, then the ones that fit are placed. Not intrinsics:
        // asking for a group's intrinsic width and then not measuring it (a dropped group) left the
        // ticket's line re-measuring without end when the page's width changed under Now Playing (ANR).
        val natural = Constraints(maxHeight = constraints.maxHeight)
        val measured = measurables.map { it.measure(natural) }
        val count = metaLineVisibleCount(measured.map(Placeable::width), gapPx, constraints.maxWidth)
        val placeables = measured.take(count)
        val width = placeables.fold(0) { acc, placeable ->
            if (acc == 0) placeable.width else acc + gapPx + placeable.width
        }
        val tops = metaLineTops(placeables.map { it.height }, placeables.map { it[FirstBaseline] })
        val height = placeables.indices.maxOfOrNull { i -> tops[i] + placeables[i].height } ?: 0
        layout(width, height) {
            var x = 0
            placeables.forEachIndexed { i, placeable ->
                placeable.placeRelative(x, tops[i])
                x += placeable.width + gapPx
            }
        }
    }
}

/**
 * Where each group's top goes so all of them sit on one baseline. Each group is
 * its own Text, and a Text's baseline sits where its own font puts it: a run of
 * Han text lays out on the CJK fallback's taller ascent and descent, a run of
 * digits or Latin on Google Sans Flex's, so centring the boxes left "2024"
 * riding higher than "专辑" beside it (owner 2026-11-24). Falls back to centring
 * when a group has no baseline ([AlignmentLine.Unspecified]).
 */
internal fun metaLineTops(heights: List<Int>, baselines: List<Int>): List<Int> {
    if (heights.isEmpty()) return emptyList()
    if (baselines.any { it == AlignmentLine.Unspecified }) {
        val tallest = heights.max()
        return heights.map { (tallest - it) / 2 }
    }
    val lowest = baselines.max()
    return baselines.map { lowest - it }
}

/**
 * [MetaLine]'s groups, wrapping onto up to [maxRows] rows instead of dropping the ones that don't fit (account
 * cards: four counts on a phone). Groups still never join with a glyph; whole groups move to the next row.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MetaFlow(
    groups: List<MetaGroup>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    maxRows: Int = 2,
    color: Color = Color.Unspecified,
) {
    val ink = color.takeOrElse { MaterialTheme.colorScheme.onSurface }
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MetaGroupGap),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        maxLines = maxRows,
    ) {
        // One baseline per row, as in MetaLine.
        groups.forEach { group ->
            MetaGroupText(group, style, 1, ink, inkIsTheme = color == Color.Unspecified, Modifier.alignByBaseline())
        }
    }
}

@Composable
private fun MetaGroupText(
    group: MetaGroup,
    style: TextStyle,
    maxLines: Int,
    ink: Color,
    inkIsTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    when (group) {
        // The number and its unit share a baseline, not a centre line: "12" is set in Google Sans Flex and
        // "首" in the CJK fallback, whose boxes put the baseline at different heights.
        is MetaGroup.Stat -> Row(
            horizontalArrangement = Arrangement.spacedBy(MetaInnerGap),
            modifier = modifier,
        ) {
            Text(
                text = group.value,
                style = style.copy(fontWeight = FontWeight.Medium).withTabularFigures(),
                color = ink,
                maxLines = maxLines,
                softWrap = false,
                modifier = Modifier.alignByBaseline(),
            )
            if (group.unit.isNotEmpty()) {
                Text(
                    text = group.unit,
                    style = style.copy(fontWeight = FontWeight.Normal),
                    color = ink.copy(alpha = ink.alpha * 0.6f),
                    maxLines = maxLines,
                    softWrap = false,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        is MetaGroup.Plain -> Text(
            text = group.text,
            style = style,
            color = if (group.muted) ink.copy(alpha = ink.alpha * 0.6f) else ink,
            maxLines = maxLines,
            softWrap = false,
            modifier = modifier,
        )
        // The line's own size (a fixed labelSmall read a size smaller than the
        // artist and year beside it, owner 2026-10-15); the colour and the
        // label weight set it apart.
        is MetaGroup.Kind -> Text(
            text = group.text,
            style = style.copy(fontWeight = FontWeight.Medium),
            color = when {
                group.accent -> scheme.primary
                inkIsTheme -> scheme.onSurfaceVariant
                else -> ink.copy(alpha = ink.alpha * 0.75f)
            },
            maxLines = maxLines,
            softWrap = false,
            modifier = modifier,
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
