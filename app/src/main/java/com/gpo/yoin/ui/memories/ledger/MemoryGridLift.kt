package com.gpo.yoin.ui.memories.ledger

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.gpo.yoin.data.memory.MemoryGrid
import com.gpo.yoin.data.memory.MemoryGridCell
import com.gpo.yoin.data.memory.MemoryGridScale
import com.gpo.yoin.ui.detail.smoothStep
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile

/*
 * The grid becomes the top bar (owner 2026-11-09): as All Memories scrolls up past its grid, the grid's
 * squares rise into the top bar and line up across it, oldest at the start, the way an album's cover turns
 * into its spectrum bar. Scrolling back down they come home. Everything per frame is read while drawing.
 */

/**
 * Where the grid's squares are on screen. The grid writes its coordinates here as it's placed; the top bar
 * reads them while drawing. Plain fields, not snapshot state: only draw and layer blocks read them, and
 * those re-run with the list's scroll anyway.
 */
@Stable
internal class MemoryGridLift {
    /** The whole grid view: how far it has gone under the bar. */
    var grid: LayoutCoordinates? = null

    /** A week or day wall. */
    var wall: Wall? = null

    /** A month's quad, by cell index. */
    val quads = HashMap<Int, LayoutCoordinates>()

    class Wall(
        val coordinates: LayoutCoordinates,
        val columns: Int,
        val rows: Int,
        val columnMajor: Boolean,
        val gapPx: Float,
    )

    /**
     * 0 = the grid wholly below a bar [barBottom] px down [into], 1 = wholly gone under it; null when the
     * grid isn't on screen (the caller knows which side it went).
     */
    fun progress(into: LayoutCoordinates, barBottom: Float): Float? {
        val coordinates = grid?.takeIf { it.isAttached && into.isAttached } ?: return null
        val top = into.localPositionOf(coordinates, Offset.Zero).y
        val height = coordinates.size.height.toFloat().coerceAtLeast(1f)
        return ((barBottom - top) / height).coerceIn(0f, 1f)
    }

    /** Where [pixel] sits now, in [into]'s px; null when its part of the grid isn't placed. */
    fun rectOf(pixel: LiftPixel, into: LayoutCoordinates, quadGap: Float): Rect? {
        if (!into.isAttached) return null
        if (pixel.mark < 0) {
            val wall = wall?.takeIf { it.coordinates.isAttached } ?: return null
            val side = (wall.coordinates.size.width - wall.gapPx * (wall.columns - 1)) / wall.columns
            val column = if (wall.columnMajor) pixel.index / wall.rows else pixel.index % wall.columns
            val row = if (wall.columnMajor) pixel.index % wall.rows else pixel.index / wall.columns
            val local = Offset(column * (side + wall.gapPx), row * (side + wall.gapPx))
            return Rect(into.localPositionOf(wall.coordinates, local), Size(side, side))
        }
        val quad = quads[pixel.index]?.takeIf { it.isAttached } ?: return null
        val side = (quad.size.width - quadGap) / 2f
        val local = Offset((pixel.mark % 2) * (side + quadGap), (pixel.mark / 2) * (side + quadGap))
        return Rect(into.localPositionOf(quad, local), Size(side, side))
    }
}

/** One square that rises: a week's or day's whole cell ([mark] -1), or one album of a month's quad. */
internal class LiftPixel(val index: Int, val cell: MemoryGridCell, val mark: Int)

/** The grid's squares with something in them, oldest first. */
internal fun liftPixels(grid: MemoryGrid): List<LiftPixel> = when (grid.scale) {
    MemoryGridScale.Month -> grid.cells.flatMapIndexed { index, cell ->
        cell.marks.indices.map { mark -> LiftPixel(index, cell, mark) }
    }
    MemoryGridScale.Week, MemoryGridScale.Day -> grid.cells.mapIndexedNotNull { index, cell ->
        if (cell.future || cell.marks.isEmpty()) null else LiftPixel(index, cell, -1)
    }
}

/** How far into the lift the bar counts as formed (white title, light status bar icons). */
internal const val GridLiftFormed = 0.5f

/**
 * The squares on their way up and, landed, the bar: drawn over the list and under the bar's title and back
 * button. [barHeight] is the bar's height from the screen's top (status bar included); [progress] is read
 * while drawing only.
 */
@Composable
internal fun MemoryGridLiftLayer(
    lift: MemoryGridLift,
    pixels: List<LiftPixel>,
    paint: CellPaint,
    barHeight: Dp,
    progress: (into: LayoutCoordinates, barBottom: Float) -> Float,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Canvas(modifier.onGloballyPositioned { holder[0] = it }) {
        val into = holder[0] ?: return@Canvas
        val n = pixels.size
        if (n == 0) return@Canvas
        val barBottom = barHeight.toPx()
        val p = progress(into, barBottom)
        if (p <= 0.001f) return@Canvas
        val slot = size.width / n
        val quadGap = MonthQuadGap.toPx()
        pixels.forEachIndexed { i, pixel ->
            val at = if (rtl) n - 1 - i else i
            // A hair wider than the slot so neighbours meet without a seam.
            val target = Rect(at * slot, 0f, (at + 1) * slot + 1f, barBottom)
            if (reduced) {
                // No flight: the bar fades in where it ends up.
                drawLiftPixel(pixel, target, 0f, paint, alpha = p)
                return@forEachIndexed
            }
            // Oldest first, as the cover's strips go: each leaves a little after the one before.
            val lag = 0.25f * i / n
            val q = smoothStep(0.02f + lag, 0.6f + lag, p)
            val from = lift.rectOf(pixel, into, quadGap)
            val rect = if (from == null || q >= 1f) target else lerp(from, target, q)
            val radius = if (from == null) 0f else from.width * 0.18f * (1f - q)
            drawLiftPixel(pixel, rect, radius, paint, alpha = 1f)
        }
        // A light veil under the title once the bar has formed, so white reads on pale covers too.
        val veil = 0.16f * smoothStep(0.6f, 1f, p)
        if (veil > 0f) drawRect(Color.Black, Offset.Zero, Size(size.width, barBottom), alpha = veil)
    }
}

private fun DrawScope.drawLiftPixel(pixel: LiftPixel, rect: Rect, radius: Float, paint: CellPaint, alpha: Float) {
    val draw: DrawScope.() -> Unit = {
        if (pixel.mark < 0) {
            drawLedgerCell(pixel.cell, rect.topLeft, rect.size, radius, paint)
        } else {
            drawMark(pixel.cell.marks[pixel.mark], rect.topLeft, rect.size, radius, paint)
        }
    }
    if (alpha >= 1f) {
        draw()
    } else {
        drawContext.canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { this.alpha = alpha })
        draw()
        drawContext.canvas.restore()
    }
}
