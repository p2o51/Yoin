package com.gpo.yoin.ui.home

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.roundToInt

// Jump Back In on Medium / Tabletop / Wide panes: the cover follows the column
// instead of sitting at the phone's fixed 100dp, so a 163dp (Medium) or 193dp
// (Wide) column stops reading as a sparse dot field. Compact — and a landscape
// phone, which reads Wide by width — never comes here: HomeWidgetGridSection
// keeps its Row + weights + 100dp path byte for byte. Everything below sizes in
// the LAYOUT phase only (no BoxWithConstraints / SubcomposeLayout): the Now
// Playing side panel hands width back over a spring, and re-subcomposing the
// grid on every frame of it is what adaptive principle 7 rules out.

/** The phone's cover-to-column rhythm: a 100dp cover in a ~118.7dp column. */
internal const val JbiCoverColumnFill = 0.84f

/** Never smaller than the phone's fixed cover. */
internal val JbiCoverMin = 100.dp
internal val JbiCoverMax = 160.dp

/** [JbiCoverFit.Capped128]: the grid never out-shouts the Activities bento. */
internal val JbiCoverMaxHierarchy = 128.dp

// The phone Row's rhythm, kept: 12dp between columns, 16dp between rows (the
// section Column's spacing), 16dp between a 1×2's cover and its copy.
private val JbiColumnGap = 12.dp
private val JbiRowGap = 16.dp
private val JbiWideCardCopyGap = 16.dp

/**
 * The cover (backdrop square) side for one [columnWidth]: the phone's 0.84
 * rhythm clamped to 100–160dp (or 100–128dp for [JbiCoverFit.Capped128]), and
 * never wider than the column itself.
 */
internal fun jbiCoverSide(columnWidth: Dp, fit: JbiCoverFit = JbiCoverFit.PhoneRhythm): Dp = when (fit) {
    JbiCoverFit.PhoneRhythm -> (columnWidth * JbiCoverColumnFill).coerceIn(JbiCoverMin, JbiCoverMax)
    JbiCoverFit.Capped128 -> (columnWidth * JbiCoverColumnFill).coerceIn(JbiCoverMin, JbiCoverMaxHierarchy)
    JbiCoverFit.Legacy100 -> JbiCoverMin
}.coerceAtMost(columnWidth)

/** Where one card sits on the true column grid: its first column and how many it spans. */
internal data class JbiCellPlacement(val startColumn: Int, val span: Int) {
    /** Left edge, `k × (col + gap)`, from the column [pitch] (`col + gap`). */
    fun cellLeft(pitch: Float): Int = (startColumn * pitch).roundToInt()

    /**
     * `s × col + (s − 1) × gap`, snapped so the right edge lands on its own
     * column line rather than accumulating rounding across the row.
     */
    fun cellWidth(pitch: Float, gap: Float): Int = ((startColumn + span) * pitch - gap).roundToInt() - cellLeft(pitch)
}

/** Column pitch (`col + gap`), where `col = (maxWidth − (columns − 1) × gap) / columns`. */
internal fun jbiColumnPitch(maxWidth: Float, columns: Int, gap: Float): Float =
    (maxWidth - gap * (columns - 1)) / columns + gap

/**
 * Column offsets for [packWidgetRows]' rows, in its order: a 1×2 spans two
 * columns, a 1×1 one, and cards run from the start edge — so the wide card's
 * alternating side carries over and a short row leaves its trailing columns
 * empty.
 */
internal fun jbiCellPlacements(
    rows: List<List<HomeWidgetCard>>,
    columns: Int,
): List<List<JbiCellPlacement>> {
    return rows.map { row ->
        var start = 0
        row.map { card ->
            val span = (if (card.expanded) 2 else 1).coerceAtMost(columns)
            JbiCellPlacement(startColumn = start, span = span).also { start += span }
        }
    }
}

/**
 * Jump Back In on true columns. Every card is measured at the fixed width of
 * the columns it spans, height loose; a row is as tall as its tallest card,
 * cards hang from its top, and rows sit 16dp apart. Unlike the phone Row's
 * weights, a 1×2 spans exactly two columns plus the gap between them, so
 * paired rows stay on the same column lines as full rows.
 *
 * [cell] must emit exactly one layout node per card.
 */
@Composable
internal fun JbiColumnGrid(
    rows: List<List<HomeWidgetCard>>,
    columns: Int,
    modifier: Modifier = Modifier,
    cell: @Composable (HomeWidgetCard) -> Unit,
) {
    val placements = remember(rows, columns) { jbiCellPlacements(rows, columns) }
    val measurePolicy = remember(placements, columns) {
        MeasurePolicy { measurables, constraints ->
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
            val gap = JbiColumnGap.toPx()
            val rowGap = JbiRowGap.roundToPx()
            val pitch = jbiColumnPitch(width.toFloat(), columns, gap)
            val placeables = ArrayList<Placeable>(measurables.size)
            val xs = IntArray(measurables.size)
            val ys = IntArray(measurables.size)
            var y = 0
            placements.forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) y += rowGap
                var rowHeight = 0
                row.forEach { placement ->
                    val index = placeables.size
                    if (index >= measurables.size) return@forEach
                    val cellWidth = placement.cellWidth(pitch, gap).coerceAtLeast(0)
                    val placeable = measurables[index].measure(
                        Constraints(minWidth = cellWidth, maxWidth = cellWidth),
                    )
                    placeables += placeable
                    xs[index] = placement.cellLeft(pitch)
                    ys[index] = y
                    rowHeight = maxOf(rowHeight, placeable.height)
                }
                y += rowHeight
            }
            layout(width, constraints.constrainHeight(y)) {
                placeables.forEachIndexed { index, placeable ->
                    placeable.placeRelative(xs[index], ys[index])
                }
            }
        }
    }
    Layout(
        content = {
            rows.forEach { row ->
                // Keyed so a Medium ⇄ Wide re-pack carries each card's
                // remembered state (palette, press) along with it.
                row.forEach { card -> key(card.stableId) { cell(card) } }
            }
        },
        modifier = modifier,
        measurePolicy = measurePolicy,
    )
}

/** Between a standing signal card's cover block and its rating. */
internal val JbiTallCopyGap = 10.dp

/**
 * A standing signal card's copy keeps the lying one's three lines: it then
 * ends beside its neighbours' next covers, before their titles.
 */
internal const val JbiTallCommentMaxLines = 3

/** A row height to borrow when no single-row piece measured one (a lattice of only standing cards). */
private val JbiRowCopyEstimate = 44.dp

/**
 * Jump Back In on a [JbiLayout] template (HomeJbiTemplate.kt), layout phase
 * only. Every piece is measured at the fixed width of the columns it spans.
 * One row pitch runs the whole grid — the tallest single-row piece plus the
 * 16dp gap, or half a standing card plus gap when its copy runs longer — so
 * covers keep one rhythm down every lane; each piece then sits at
 * (its row + its lane's offset) × pitch, hanging from the top of its cell. A
 * standing card gets two pitches minus the gap as its minimum height. [cell]
 * must emit exactly one layout node per cell.
 */
@Composable
internal fun JbiSpanGrid(
    layout: JbiLayout,
    fit: JbiCoverFit,
    modifier: Modifier = Modifier,
    cell: @Composable (JbiCell) -> Unit,
) {
    val template = layout.template
    val measurePolicy = remember(template, fit) {
        MeasurePolicy { measurables, constraints ->
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
            val gap = JbiColumnGap.toPx()
            val rowGap = JbiRowGap.toPx()
            val pitchX = jbiColumnPitch(width.toFloat(), template.columns, gap)
            val pieces = template.pieces
            val count = minOf(measurables.size, pieces.size)
            val placeables = arrayOfNulls<Placeable>(count)
            fun widthOf(piece: JbiPiece): Int =
                JbiCellPlacement(piece.column, piece.kind.columnSpan).cellWidth(pitchX, gap).coerceAtLeast(0)

            var rowHeight = 0
            for (index in 0 until count) {
                val piece = pieces[index]
                if (piece.kind == JbiPieceKind.TallSignal) continue
                val w = widthOf(piece)
                val placeable = measurables[index].measure(Constraints(minWidth = w, maxWidth = w))
                placeables[index] = placeable
                rowHeight = maxOf(rowHeight, placeable.height)
            }
            if (rowHeight == 0) {
                val column = (pitchX - gap).coerceAtLeast(0f).toDp()
                rowHeight = (jbiCoverSide(column, fit) + JbiRowCopyEstimate).roundToPx()
            }
            val tallMin = rowHeight * 2 + rowGap.roundToInt()
            var tallest = tallMin
            for (index in 0 until count) {
                val piece = pieces[index]
                if (piece.kind != JbiPieceKind.TallSignal) continue
                val w = widthOf(piece)
                val placeable = measurables[index].measure(
                    Constraints(minWidth = w, maxWidth = w, minHeight = tallMin),
                )
                placeables[index] = placeable
                tallest = maxOf(tallest, placeable.height)
            }
            val pitchY = maxOf(rowHeight + rowGap, (tallest + rowGap) / 2f)
            var height = 0
            val ys = IntArray(count)
            for (index in 0 until count) {
                val y = (template.topOf(pieces[index]) * pitchY).roundToInt()
                ys[index] = y
                height = maxOf(height, y + (placeables[index]?.height ?: 0))
            }
            layout(width, constraints.constrainHeight(height)) {
                for (index in 0 until count) {
                    val piece = pieces[index]
                    placeables[index]?.placeRelative(
                        JbiCellPlacement(piece.column, piece.kind.columnSpan).cellLeft(pitchX),
                        ys[index],
                    )
                }
            }
        }
    }
    Layout(
        content = {
            // Keyed so a re-seat carries each card's remembered state
            // (palette, press) along with it.
            layout.cells.forEach { jbiCell -> key(jbiCell.card.stableId) { cell(jbiCell) } }
        },
        modifier = modifier,
        measurePolicy = measurePolicy,
    )
}

/**
 * The 1×2 on true columns. From its own width `W = 2 × col + 12dp` it recovers
 * the column, measures the cover block at [jbiCoverSide] of it and gives the
 * copy the rest, `W − side − 16dp`, at `x = side + 16dp` — the phone's 16dp
 * cover/copy language. Both hang from the top. Exactly two children: the cover
 * block, then the copy.
 */
@Composable
internal fun JbiWideCardLayout(
    fit: JbiCoverFit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val measurePolicy = remember(fit) {
        MeasurePolicy { measurables, constraints ->
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
            val column = ((width - JbiColumnGap.toPx()) / 2f).coerceAtLeast(0f).toDp()
            val side = jbiCoverSide(column, fit).roundToPx().coerceIn(0, width)
            val copyX = side + JbiWideCardCopyGap.roundToPx()
            val copyWidth = (width - copyX).coerceAtLeast(0)
            val cover = measurables.getOrNull(0)
                ?.measure(Constraints(minWidth = side, maxWidth = side))
            val copy = measurables.getOrNull(1)
                ?.measure(Constraints(minWidth = copyWidth, maxWidth = copyWidth))
            val height = maxOf(cover?.height ?: 0, copy?.height ?: 0)
            layout(width, constraints.constrainHeight(height)) {
                cover?.placeRelative(0, 0)
                copy?.placeRelative(copyX, 0)
            }
        }
    }
    Layout(content = content, modifier = modifier, measurePolicy = measurePolicy)
}

/**
 * The 1×1 backdrop square on true columns: [jbiCoverSide] of the width its
 * cell offers (the cell is exactly one column), measured fixed so the title
 * and subtitle under it still span the whole cell.
 */
internal fun Modifier.jbiCoverSquare(fit: JbiCoverFit): Modifier = layout { measurable, constraints ->
    val column = if (constraints.hasBoundedWidth) constraints.maxWidth.toDp() else JbiCoverMin
    val side = jbiCoverSide(column, fit).roundToPx().coerceAtMost(constraints.maxWidth)
    val placeable = measurable.measure(Constraints.fixed(side, side))
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

// Previews

@Preview(name = "Medium portrait (720dp feed)", widthDp = 720, heightDp = 1100, showBackground = true)
@Composable
private fun HomeWidgetGridMediumPreview() {
    // An 800dp portrait tablet caps the feed at 720dp with 16dp page margins:
    // a 688dp grid, 163dp columns, 137dp covers.
    YoinTheme {
        ProvidePreviewWindow(widthDp = 800, heightDp = 1280) {
            HomeWidgetGridSection(
                title = "Jump Back In",
                cards = previewJbiCards(),
                extractBackdropColors = false,
                onCardClick = {},
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

private fun previewJbiCards(): List<HomeWidgetCard> {
    val types = listOf(MemoryEntityType.ALBUM, MemoryEntityType.PLAYLIST, MemoryEntityType.SONG)
    val compact = (1..8).map { index ->
        HomeWidgetCard(
            stableId = "c$index",
            entityType = types[index % types.size],
            title = "Cover $index",
            subtitle = "Hannah Jadagu",
            coverArtUrl = null,
            target = HomeWidgetTarget.AlbumDetail(albumId = "c$index"),
        )
    }
    val wide = listOf(
        HomeWidgetCard(
            stableId = "w1",
            entityType = MemoryEntityType.ALBUM,
            title = "Describe",
            subtitle = "Hannah Jadagu",
            coverArtUrl = null,
            ratingText = "8.4",
            ratingBasis = "Oct 2",
            comment = "A record that sounds like the walk home.",
            commentIsHeadline = true,
            expanded = true,
            target = HomeWidgetTarget.MemoryFocus(sessionId = 1L),
        ),
        HomeWidgetCard(
            stableId = "w2",
            entityType = MemoryEntityType.SONG,
            title = "Say It Now",
            subtitle = "Hannah Jadagu",
            coverArtUrl = null,
            ratingText = "N/A",
            comment = "The bridge at 1:42.",
            expanded = true,
            target = HomeWidgetTarget.AlbumDetail(albumId = "w2"),
        ),
    )
    return wide + compact
}
