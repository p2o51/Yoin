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
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * What a [MetaLine] does with groups that do not all fit.
 *
 * - [DropTrailing]: whole groups drop from the end ("12 tracks  44 min" loses the minutes). The
 *   first group never drops: wider than the line, it ends in an ellipsis, so the line is never empty.
 * - [Share]: for a pair of names (a song row's artist and album). Text groups share the width and
 *   each ends in an ellipsis: the first gets up to [ShareLeadWeight] of it, a later group what the
 *   ones before leave over. A later group squeezed under [MetaShareMinWidth] drops instead of
 *   showing a lone "…". Counts ([MetaGroup.Stat]) and kinds keep their width.
 */
enum class MetaOverflow { DropTrailing, Share }

private val MetaGroupGap = 12.dp
private val MetaInnerGap = 4.dp

/** In [MetaOverflow.Share], the narrowest a later group is drawn before it drops. */
internal val MetaShareMinWidth = 40.dp

/** The first text group's share of a [MetaOverflow.Share] line when every group is too long: 3 : 2. */
private const val ShareLeadWeight = 3
private const val ShareTrailWeight = 2

/**
 * The widths a [MetaOverflow.Share] line gives its groups, in px; 0 = dropped. Every group fits
 * at its [natural] width when it can. Otherwise the [fixed] groups keep theirs and the others
 * split the rest by weight (the first text group 3, each later one 2): one shorter than its share
 * keeps its natural width and hands the difference to the others (water-filling), so a short
 * album beside a long artist name shows whole and the artist takes the rest. A later group left
 * under [minSharePx] (or 0) drops from the end and the line is shared again. The first group
 * never drops; it is cut to the line when it is all that fits.
 */
internal fun metaLineShareWidths(
    natural: List<Int>,
    fixed: List<Boolean>,
    gapPx: Int,
    maxWidth: Int,
    minSharePx: Int,
): List<Int> {
    if (natural.isEmpty()) return emptyList()
    if (maxWidth <= 0) return List(natural.size) { 0 }
    var count = natural.size
    while (true) {
        val widths = shareAmong(natural.take(count), fixed.take(count), gapPx, maxWidth)
        val starved = (1 until count).any { i -> widths[i] < minOf(minSharePx, natural[i]) || widths[i] <= 0 }
        if (!starved || count == 1) {
            return widths.map { it.coerceAtMost(maxWidth) } + List(natural.size - count) { 0 }
        }
        count--
    }
}

private fun shareAmong(natural: List<Int>, fixed: List<Boolean>, gapPx: Int, maxWidth: Int): List<Int> {
    val total = natural.sum() + gapPx * (natural.size - 1)
    if (total <= maxWidth) return natural
    val widths = natural.toMutableList()
    var room = maxWidth - gapPx * (natural.size - 1) - natural.indices.filter { fixed[it] }.sumOf { natural[it] }
    val firstShared = natural.indices.firstOrNull { !fixed[it] }
    val open = natural.indices.filter { !fixed[it] }.toMutableList()
    fun weight(i: Int) = if (i == firstShared) ShareLeadWeight else ShareTrailWeight
    // Water-filling: whoever needs less than its share takes only what it needs.
    while (open.isNotEmpty()) {
        val weights = open.sumOf(::weight)
        val satisfied = open.filter { i -> natural[i].toLong() * weights <= room.coerceAtLeast(0).toLong() * weight(i) }
        if (satisfied.isEmpty()) {
            val share = room.coerceAtLeast(0)
            open.forEach { i -> widths[i] = (share.toLong() * weight(i) / weights).toInt() }
            break
        }
        satisfied.forEach { i ->
            widths[i] = natural[i]
            room -= natural[i]
        }
        open.removeAll(satisfied)
    }
    return widths
}

@Composable
fun MetaLine(
    groups: List<MetaGroup>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    maxLines: Int = 1,
    // The line's ink. Unspecified = the theme's onSurface; a coloured card
    // (Activities) passes its own content colour so the line matches its text.
    color: Color = Color.Unspecified,
    overflow: MetaOverflow = MetaOverflow.DropTrailing,
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
        val placeables = when (overflow) {
            MetaOverflow.DropTrailing -> {
                // Every group is measured once at its natural width, then the ones that fit are placed. Not
                // intrinsics: asking for a group's intrinsic width and then not measuring it (a dropped group)
                // left the ticket's line re-measuring without end when the page's width changed under Now
                // Playing (ANR). The first group alone is held to the line, so a name wider than it ends in an
                // ellipsis instead of leaving the line empty (it can't be any wider than the line anyway).
                val natural = Constraints(maxHeight = constraints.maxHeight)
                val measured = measurables.mapIndexed { i, measurable ->
                    measurable.measure(if (i == 0) natural.copy(maxWidth = constraints.maxWidth) else natural)
                }
                val count = metaLineVisibleCount(measured.map(Placeable::width), gapPx, constraints.maxWidth)
                measured.take(count)
            }
            MetaOverflow.Share -> {
                // Intrinsic widths first, then every group is measured exactly once (a dropped one at 0 wide,
                // and not placed), so no group is ever asked for its width and left unmeasured (see above).
                val natural = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
                val widths = if (constraints.hasBoundedWidth) {
                    metaLineShareWidths(
                        natural = natural,
                        fixed = groups.map { it !is MetaGroup.Plain },
                        gapPx = gapPx,
                        maxWidth = constraints.maxWidth,
                        minSharePx = MetaShareMinWidth.roundToPx(),
                    )
                } else {
                    natural
                }
                measurables.mapIndexedNotNull { i, measurable ->
                    val placeable = measurable.measure(
                        Constraints(maxWidth = widths[i].coerceAtLeast(0), maxHeight = constraints.maxHeight),
                    )
                    placeable.takeIf { widths[i] > 0 }
                }
            }
        }
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
                    overflow = TextOverflow.Ellipsis,
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
            // Only shows when the line holds the group narrower than its text (MetaOverflow).
            overflow = TextOverflow.Ellipsis,
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
            // Only shows when the line holds the group narrower than its text (MetaOverflow).
            overflow = TextOverflow.Ellipsis,
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

@Preview(showBackground = true, widthDp = 180, name = "Shares a long artist and album")
@Composable
private fun MetaLineSharePreview() {
    YoinTheme {
        MetaLine(
            groups = listOf(
                MetaGroup.Plain("Kendrick Lamar, SZA, Travis Scott & Future"),
                MetaGroup.Plain("Black Panther: The Album (Music From and Inspired By)", muted = true),
            ),
            overflow = MetaOverflow.Share,
        )
    }
}
