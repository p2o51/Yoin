package com.gpo.yoin.ui.home

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.FeedFrameClass
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.YoinWindowInfo
import com.gpo.yoin.ui.experience.feedCoverColumnsFor
import com.gpo.yoin.ui.experience.feedFrameClass
import com.gpo.yoin.ui.experience.feedUnitsFor
import com.gpo.yoin.ui.home.edit.HomeEditTokens
import com.gpo.yoin.ui.home.edit.HomeSpan
import com.gpo.yoin.ui.home.edit.HomeSpanDrag
import com.gpo.yoin.ui.home.edit.HomeSpanInfo
import com.gpo.yoin.ui.home.edit.HomeSpanTokens
import kotlin.math.roundToInt

// Large-screen Home, stage 1 (owner 2026-11-16): the feed in rows of one full
// or two half sections. Below the two-column width every section stacks in
// one column in the same order (left, then right), the pairing kept stored.

/**
 * The feed lays out in two columns: a Wide frame (W ≥ 840, tall), whose
 * content is W − 64 ≥ 776dp. Every other frame caps the content at 688dp
 * (or is a landscape handset), so this is "content ≥ 720dp", the Now Playing
 * side panel's squeeze included (it re-provides a narrower window info).
 */
internal fun homeTwoColumn(info: YoinWindowInfo): Boolean =
    !info.isCompactHeight && feedFrameClass(info) == FeedFrameClass.Wide

/**
 * The window info a section in a [contentWidth]-wide column reads: its own
 * width class N and cover columns (adaptive principle 1, content reads its
 * container); everything else the window's.
 */
internal fun YoinWindowInfo.forHomeColumn(contentWidth: Dp): YoinWindowInfo {
    val mode = if (contentWidth >= TwoColumnMinContent) LayoutMode.Wide else LayoutMode.Medium
    return copy(feedUnits = feedUnitsFor(contentWidth, mode), feedCoverColumns = feedCoverColumnsFor(contentWidth))
}

// The narrowest content a Wide frame has (840 − 2 × 32).
private val TwoColumnMinContent = 776.dp

/**
 * Where [section] sits in [rows] for a span drag ([HomeSpanInfo]); null when
 * it isn't laid out. [fullPx] is the content width. A full row can turn half
 * only with the next enabled section displayed right after it ([enabledOrder]).
 */
internal fun homeSpanInfoOf(
    section: HomeSection,
    rows: List<HomeFeedRow>,
    enabledOrder: List<HomeSection>,
    fullPx: Float,
    gapPx: Float,
): HomeSpanInfo? {
    val index = rows.indexOfFirst { row ->
        when (row) {
            is HomeFeedRow.Full -> row.section == section
            is HomeFeedRow.Halves -> row.left == section || row.right == section
        }
    }
    if (index < 0 || fullPx <= 0f) return null
    val halfPx = (fullPx - gapPx) / 2f
    return when (val row = rows[index]) {
        is HomeFeedRow.Full -> {
            val next = enabledOrder.getOrNull(enabledOrder.indexOf(section) + 1)
            HomeSpanInfo(
                span = HomeSpan.Full,
                currentPx = fullPx,
                halfPx = halfPx,
                fullPx = fullPx,
                hasNext = next != null && rows.getOrNull(index + 1)?.first == next,
            )
        }
        is HomeFeedRow.Halves -> HomeSpanInfo(
            span = if (row.left == section) HomeSpan.LeftHalf else HomeSpan.RightHalf,
            currentPx = halfPx,
            halfPx = halfPx,
            fullPx = fullPx,
            hasNext = true,
        )
    }
}

/**
 * Widths (px) of a row's cells: a full row its section; a pair two halves
 * [gapPx] apart. Under a span drag ([dragged], [dragPx]) the dragged cell is
 * [dragPx] wide and its partner takes what is left (never under half a half,
 * the dragged cell drawn over it). Pure.
 */
internal fun homeRowCellWidths(
    row: HomeFeedRow,
    widthPx: Int,
    gapPx: Int,
    dragged: HomeSection?,
    dragPx: Int,
): List<Int> {
    val half = (widthPx - gapPx) / 2
    return when (row) {
        is HomeFeedRow.Full -> listOf(if (dragged == row.section) dragPx.coerceIn(1, widthPx) else widthPx)
        is HomeFeedRow.Halves -> {
            val minPartner = half / 2
            when (dragged) {
                row.left -> {
                    val l = dragPx.coerceIn(1, widthPx)
                    listOf(l, (widthPx - gapPx - l).coerceAtLeast(minPartner))
                }
                row.right -> {
                    val r = dragPx.coerceIn(1, widthPx)
                    listOf((widthPx - gapPx - r).coerceAtLeast(minPartner), r)
                }
                else -> listOf(half, widthPx - gapPx - half)
            }
        }
    }
}

/**
 * One feed row: a full section, or two halves [gap] apart, top-aligned. Each
 * cell reads its own column ([LocalHomeColumn], [forHomeColumn]). A span drag
 * ([span]) sizes its cell to the finger in the layout pass, and draws the
 * width it will snap to as a dashed outline.
 */
@Composable
internal fun HomeFeedRowLayout(
    row: HomeFeedRow,
    span: HomeSpanDrag?,
    gap: Dp,
    modifier: Modifier = Modifier,
    cell: @Composable (HomeSection) -> Unit,
) {
    val sections = when (row) {
        is HomeFeedRow.Full -> listOf(row.section)
        is HomeFeedRow.Halves -> listOf(row.left, row.right)
    }
    val outline = MaterialTheme.colorScheme.primary
    val windowInfo = LocalYoinWindowInfo.current
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                val dragged = span?.section?.takeIf { it in sections } ?: return@drawWithContent
                if (size.width <= 0f) return@drawWithContent
                val gapPx = gap.toPx()
                val half = (size.width - gapPx) / 2f
                val targetHalf = span.target != HomeSpan.Full
                val w = if (targetHalf) half else size.width
                // A right half grows from the right edge; everything else from the left.
                val rightAnchored = row is HomeFeedRow.Halves && row.right == dragged
                val x = if (rightAnchored) size.width - w else 0f
                val stroke = HomeSpanTokens.OutlineStroke.toPx()
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(x + stroke / 2f, stroke / 2f),
                    size = Size(w - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(HomeEditTokens.PlateRadius.toPx()),
                    style = Stroke(
                        width = stroke,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(HomeSpanTokens.OutlineDash.toPx(), HomeSpanTokens.OutlineGap.toPx()),
                        ),
                    ),
                    alpha = if (span.dragging) 1f else .5f,
                )
            },
        content = {
            sections.forEach { section ->
                key(section) {
                    BoxWithConstraints {
                        val half = row is HomeFeedRow.Halves || span?.section == section
                        val info = remember(windowInfo, maxWidth, half) {
                            if (half) windowInfo.forHomeColumn(maxWidth) else windowInfo
                        }
                        CompositionLocalProvider(
                            LocalYoinWindowInfo provides info,
                            LocalHomeColumn provides HomeColumn(half = half),
                        ) { cell(section) }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val dragged = span?.section?.takeIf { it in sections }
        val widths = homeRowCellWidths(row, width, gapPx, dragged, span?.width?.value?.roundToInt() ?: width)
        val placeables = measurables.mapIndexed { index, measurable ->
            val w = widths.getOrElse(index) { width }
            measurable.measure(Constraints.fixedWidth(w).copy(maxHeight = constraints.maxHeight))
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            // The dragged cell last, so it draws over a partner it grows into.
            val order = placeables.indices.sortedBy { if (sections[it] == dragged) 1 else 0 }
            order.forEach { index ->
                val placeable = placeables[index]
                val x = if (index == 0) 0 else width - placeable.width
                placeable.placeRelative(x, 0)
            }
        }
    }
}
