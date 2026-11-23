package com.gpo.yoin.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
 * width it will snap to as a dashed outline. Each section's cell is its own
 * movable content in [cells] and moves with it to another row; [data] is what
 * a cell shows (null: the section has no cell).
 */
@Composable
internal fun <T> HomeFeedRowLayout(
    row: HomeFeedRow,
    span: HomeSpanDrag?,
    gap: Dp,
    modifier: Modifier = Modifier,
    flips: HomeCellFlips? = null,
    /** Two columns: every cell's exact height (HomeGridUnits.kt); null in one column. */
    grid: HomeGridHeight? = null,
    /** The cell drawn over its partner (a dragged block), read as the row places. */
    onTop: () -> HomeSection? = { null },
    cells: HomeFeedCells<T>,
    data: (HomeSection) -> T?,
) {
    val sections = when (row) {
        is HomeFeedRow.Full -> listOf(row.section)
        is HomeFeedRow.Halves -> listOf(row.left, row.right)
    }
    val outline = MaterialTheme.colorScheme.primary
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
                    val cell = data(section)
                    if (cell != null) {
                        cells.of(section)(
                            HomeFeedCellPlace(
                                rowKey = row.first,
                                pairHalf = row is HomeFeedRow.Halves,
                                span = span,
                                flips = flips,
                                grid = grid,
                                data = cell,
                            ),
                        )
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
            // The dragged cell last, so it draws over a partner it grows into (or is dragged across).
            val top = onTop()
            val order = placeables.indices.sortedBy { if (sections[it] == dragged || sections[it] == top) 1 else 0 }
            order.forEach { index ->
                val placeable = placeables[index]
                val x = if (index == 0) 0 else width - placeable.width
                placeable.placeRelative(x, 0)
            }
        }
    }
}

/**
 * Where a cell sits: the row it is in (keyed by its first section), whether
 * that row is a pair, what the row hands every cell, and what the cell shows
 * ([data], plain values). Equal for a cell that hasn't changed, so its host skips.
 */
@Stable
internal data class HomeFeedCellPlace<T>(
    val rowKey: HomeSection,
    val pairHalf: Boolean,
    val span: HomeSpanDrag?,
    val flips: HomeCellFlips?,
    val grid: HomeGridHeight?,
    val data: T,
)

/**
 * Each section's cell as movable content, one per section for the feed's
 * life. A section changing rows (a half dragged out of its pair, a full row
 * dragged into one, a span or Undo regrouping) changes LazyColumn items: the
 * old item drops the cell and the new one takes it, and the cell moves with
 * its whole tree — its covers' requests and painters, its measured text,
 * every remembered state — instead of being disposed in one item and
 * composed anew in the other (a ~200ms frame on the tablet, each time).
 * Both happen in one frame: the old item recomposes as the list's items
 * change and the new one composes in that frame's measure, while the
 * runtime still holds what was dropped.
 *
 * A cell shows [content] for its place and nothing else: no lambda of the
 * row it sits in. A row's lambda changes with the row and re-invokes where
 * it was called, and would recompose a cell on its way to another row
 * against the row it left (where it no longer is: its block gone).
 */
@Stable
internal class HomeFeedCells<T>(private val content: @Composable (HomeSection, HomeFeedCellPlace<T>) -> Unit) {
    private val hosts = HashMap<HomeSection, @Composable (HomeFeedCellPlace<T>) -> Unit>()

    fun of(section: HomeSection): @Composable (HomeFeedCellPlace<T>) -> Unit =
        hosts.getOrPut(section) { movableContentOf { place: HomeFeedCellPlace<T> -> HomeFeedCell(section, place, content) } }
}

/** One cell: [section] in its column at [place] (its window info, its grid height, the move between rows). */
@Composable
private fun <T> HomeFeedCell(
    section: HomeSection,
    place: HomeFeedCellPlace<T>,
    content: @Composable (HomeSection, HomeFeedCellPlace<T>) -> Unit,
) {
    val flips = place.flips
    flips?.compose(section, place.rowKey)
    val windowInfo = LocalYoinWindowInfo.current
    val morph = remember(flips, section) { flips?.let { HomeCellMorph(it, section) } }
    // The cell is measured at the width it springs to, once: its content reads that width's info
    // here and lays out for it alone. Only the block around it (HomeEditBlock) follows the spring.
    BoxWithConstraints(flips?.let { Modifier.homeCellFlip(it, section, place.rowKey) } ?: Modifier) {
        val half = place.pairHalf || place.span?.section == section
        // Two columns (a grid height): every cell reads its own width, full rows too. A cell
        // changing rows springs its width (HomeCellFlips), and the window's full-width info
        // in a cell still half wide crammed a full row's covers into it until the spring
        // ended. At rest a full row's width is the feed's content width: the same info.
        val column = half || place.grid != null
        val info = remember(windowInfo, maxWidth, column) {
            if (column) windowInfo.forHomeColumn(maxWidth) else windowInfo
        }
        CompositionLocalProvider(
            LocalYoinWindowInfo provides info,
            LocalHomeColumn provides HomeColumn(half = half),
            LocalHomeGridHeight provides place.grid,
            LocalHomeCellMorph provides morph,
        ) { content(section, place) }
    }
}

/**
 * A section's cell moving to another row (two columns: a pair forming or
 * splitting on the handle's release, an Undo) moves there from where it
 * was and springs its width, instead of the old row fading out while the
 * new one fades in. The old row, fading out, no longer draws the cell.
 * On only while [enabled] (edit mode). A [pinned] cell (the dragged block,
 * which follows the finger) springs its width but never flies.
 */
@Stable
internal class HomeCellFlips(
    private val scope: CoroutineScope,
    private val spec: () -> FiniteAnimationSpec<Float>,
    private val offsetSpec: () -> FiniteAnimationSpec<androidx.compose.ui.geometry.Offset>,
) {
    /** The page Box: every bound is in its px. */
    var box: () -> LayoutCoordinates? = { null }
    var enabled: () -> Boolean = { false }

    /** A span drag sizes its row's cells itself, frame by frame: no spring while one runs. */
    var spanDragging: () -> Boolean = { false }

    /** The dragged block: it is drawn under the finger, so a change of row moves nothing. */
    var pinned: () -> HomeSection? = { null }

    /** The row (keyed by its first section) each displayed section is in now. Published after each composition. */
    var rowOf: Map<HomeSection, HomeSection> by mutableStateOf(emptyMap())

    private val lastRow = HashMap<HomeSection, HomeSection>()
    private val lastBounds = HashMap<HomeSection, Rect>()
    private val pending = HashMap<HomeSection, Rect>()
    private val offsets = HashMap<HomeSection, Animatable<androidx.compose.ui.geometry.Offset, AnimationVector2D>>()
    private val widths = HashMap<HomeSection, Animatable<Float, AnimationVector1D>>()
    private val lastTarget = HashMap<HomeSection, Int>()

    /** The feed composed [section] in the row [rowKey]: a change of row arms a move from its last place. */
    fun compose(section: HomeSection, rowKey: HomeSection) {
        val was = lastRow[section] ?: return
        val from = lastBounds[section] ?: return
        // Read without observing: the gate (edit mode, a carry's fold) must not recompose the cells.
        if (was == rowKey || !androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { enabled() }) return
        pending[section] = from
        widths[section] = Animatable(from.width)
    }

    /** [section] moved into [rowKey] from another row this composition: it appears without a fade. */
    fun moved(section: HomeSection, rowKey: HomeSection): Boolean = lastRow[section]?.let { it != rowKey } == true

    /** Sections no longer displayed lose their place: shown again later, they don't fly in from it. */
    fun retain(displayed: Collection<HomeSection>) {
        val keep = displayed.toSet()
        lastRow.keys.retainAll(keep)
        lastBounds.keys.retainAll(keep)
        lastTarget.keys.retainAll(keep)
    }

    internal fun shows(section: HomeSection, rowKey: HomeSection): Boolean =
        rowOf[section]?.let { it == rowKey } ?: true

    /**
     * [section]'s cell is [target] px wide: a cell staying in its row whose
     * width changes (a pair splitting leaves the left half's row full width)
     * springs its drawn width from the width it had ([drawnWidth]).
     */
    internal fun target(section: HomeSection, target: Int) {
        val last = lastTarget.put(section, target)
        if (last != null && last != target && widths[section] == null && !spanDragging() &&
            androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { enabled() }
        ) {
            val w = Animatable(last.toFloat())
            widths[section] = w
            scope.launch {
                w.animateTo(target.toFloat(), spec())
                if (widths[section] === w) widths.remove(section)
            }
        }
    }

    /**
     * The width [section]'s block is drawn at in a cell [target] px wide:
     * the spring's while it runs, else the cell's. Reading it in a layout
     * pass re-lays that pass (only) each frame of the spring.
     */
    internal fun drawnWidth(section: HomeSection, target: Int): Int =
        widths[section]?.value?.roundToInt()?.coerceAtLeast(1) ?: target

    /** How much wider [section]'s content lays out than its block is drawn now (< 0: narrower); 0 at rest. */
    internal fun contentExtra(section: HomeSection): Int {
        val target = lastTarget[section] ?: return 0
        return target - drawnWidth(section, target)
    }

    internal fun offsetOf(section: HomeSection) = offsets[section]?.value

    internal fun placed(section: HomeSection, rowKey: HomeSection, coordinates: LayoutCoordinates, targetWidth: Float) {
        // A row fading out still places its old cell: it keeps no record.
        if (!shows(section, rowKey)) return
        val box = box()?.takeIf { it.isAttached } ?: return
        val topLeft = box.localPositionOf(coordinates, androidx.compose.ui.geometry.Offset.Zero)
        val width = widths[section]?.value ?: coordinates.size.width.toFloat()
        val from = pending.remove(section)
        if (from != null) {
            val delta = from.topLeft - topLeft
            if (section != pinned()) {
                val offset = Animatable(delta, androidx.compose.ui.geometry.Offset.VectorConverter)
                offsets[section] = offset
                scope.launch {
                    offset.animateTo(androidx.compose.ui.geometry.Offset.Zero, offsetSpec())
                    if (offsets[section] === offset) offsets.remove(section)
                }
            }
            val w = widths[section]
            if (w != null && abs(w.value - targetWidth) >= 1f) {
                scope.launch {
                    w.animateTo(targetWidth, spec())
                    if (widths[section] === w) widths.remove(section)
                }
            } else {
                widths.remove(section)
            }
        }
        lastRow[section] = rowKey
        lastBounds[section] = Rect(topLeft, androidx.compose.ui.geometry.Size(width, coordinates.size.height.toFloat()))
    }
}

/**
 * A cell under [flips]: measured at the width it springs to (its content
 * lays out once, never at the widths in between), drawn from where it came,
 * hidden while its old row fades. Its block follows the spring ([HomeCellMorph]).
 */
private fun Modifier.homeCellFlip(flips: HomeCellFlips, section: HomeSection, rowKey: HomeSection): Modifier =
    onPlaced { coordinates -> flips.placed(section, rowKey, coordinates, coordinates.size.width.toFloat()) }
        .layout { measurable, constraints ->
            val target = constraints.maxWidth
            flips.target(section, target)
            val placeable = measurable.measure(constraints.copy(minWidth = target, maxWidth = target))
            layout(target, placeable.height) { placeable.place(0, 0) }
        }
        .drawWithContent {
            if (!flips.shows(section, rowKey)) return@drawWithContent
            val offset = flips.offsetOf(section)
            if (offset == null) drawContent() else translate(offset.x, offset.y) { this@drawWithContent.drawContent() }
        }

/**
 * A cell springing between half and full width (a pair forming or
 * splitting, the dragged block changing rows). Its content lays out once,
 * at the width the cell springs to: laid out at every width in between, a
 * section re-packed its grid each time it crossed a width class (Activities'
 * bento, Jump Back In's covers), drawing cards over each other and taking a
 * long frame for each re-pack. The block around the content (its plate,
 * badges, handle and layer) follows the spring instead ([homeCellMorphBlock]),
 * and the content shows inside it ([homeCellMorphContent]): revealed as the
 * block widens, the block closing around it as it narrows. Read in layout and
 * draw only.
 */
@Stable
internal class HomeCellMorph(private val flips: HomeCellFlips, private val section: HomeSection) {
    /** The block's width now in a cell [target] px wide. */
    fun drawnWidth(target: Int): Int = flips.drawnWidth(section, target)

    /** How much wider the content lays out than the block draws now (< 0: narrower); 0 at rest. */
    fun contentExtra(): Int = flips.contentExtra(section)
}

/** The morph of the cell a block sits in; null outside two columns' cells. */
internal val LocalHomeCellMorph = staticCompositionLocalOf<HomeCellMorph?> { null }

/** The block of a cell [morph]s: as wide as the spring draws it, from the cell's start. */
internal fun Modifier.homeCellMorphBlock(morph: HomeCellMorph?): Modifier = if (morph == null) {
    this
} else {
    layout { measurable, constraints ->
        if (!constraints.hasBoundedWidth) return@layout measurable.measure(constraints).let { layout(it.width, it.height) { it.place(0, 0) } }
        val w = morph.drawnWidth(constraints.maxWidth)
        val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
        layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
    }
}

/**
 * The content inside a [morph]ing block: laid out at the width the cell
 * springs to whatever width the block is drawn at (the same constraints each
 * frame, so it isn't measured again), cut off at the block's edge while it
 * is the wider of the two.
 *
 * The cut is drawn OUTSIDE the layout, at the block's width: inside it, its
 * size was the content's own (the wider one), so nothing was cut, and a
 * widening block drew the cards of its final width past its edge (Jump Back
 * In's covers over the next column, a dragged block's shelf off the screen,
 * 2026-11-23).
 */
internal fun Modifier.homeCellMorphContent(morph: HomeCellMorph?): Modifier = if (morph == null) {
    this
} else {
    drawWithContent {
        if (morph.contentExtra() > 0) {
            clipRect(left = -size.width, top = -size.height, right = size.width, bottom = size.height * 2f) {
                this@drawWithContent.drawContent()
            }
        } else {
            drawContent()
        }
    }.layout { measurable, constraints ->
        if (!constraints.hasBoundedWidth) return@layout measurable.measure(constraints).let { layout(it.width, it.height) { it.place(0, 0) } }
        val w = homeCellMorphContentWidth(constraints.maxWidth, morph.contentExtra())
        val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
        layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
    }
}

/** The width a morphing block's content lays out at: its box's [boxWidth] plus what the spring has left to go ([extra]). Pure. */
internal fun homeCellMorphContentWidth(boxWidth: Int, extra: Int): Int = (boxWidth + extra).coerceAtLeast(1)
