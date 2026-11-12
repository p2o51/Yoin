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
import kotlin.math.ceil

/*
 * The grid becomes the top bar (owner 2026-11-09): as All Memories scrolls up past its grid, the grid's
 * squares rise into the top bar. Scrolling back down they come home. Everything per frame is read while drawing.
 *
 * The bar is a mosaic of square pixels (owner 2026-11-12, replacing full-height slanted stripes that read as
 * an album's spectrum bar): the squares keep the grid's own look, piece together edge to edge in a few rows,
 * and run oldest first down each column and on across. When there are more places than squares, a square
 * takes two neighbouring places, so every bar is filled whatever the count.
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

/** The bar's squares: [rows] of them down, each [side] px, [places] in all (the last column may run off the edge). */
internal class BarMosaic(val rows: Int, val side: Float, val places: Int)

/**
 * The fewest rows (two at least, so it reads as pixels, not tiles) whose squares, filling the bar's height and
 * running across its [width], have a place for each of [count] squares.
 */
internal fun barMosaic(count: Int, width: Float, height: Float): BarMosaic {
    var rows = 2
    while (true) {
        val side = height / rows
        val columns = ceil(width / side).toInt().coerceAtLeast(1)
        if (rows * columns >= count || rows >= MaxBarRows) return BarMosaic(rows, side, rows * columns)
        rows++
    }
}

/** Past this many rows the squares would be specks; extra squares then share places. */
private const val MaxBarRows = 8

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
        val quadGap = MonthQuadGap.toPx()
        val mosaic = barMosaic(n, size.width, barBottom)
        // Reduced motion: no flight, the whole bar fades in where it ends up (one layer, not one per square).
        if (reduced) drawContext.canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = p })
        for (k in 0 until mosaic.places) {
            val i = (k.toLong() * n / mosaic.places).toInt()
            val pixel = pixels[i]
            val column = k / mosaic.rows
            val row = k % mosaic.rows
            val x = if (rtl) size.width - (column + 1) * mosaic.side else column * mosaic.side
            // A hair larger than the square so neighbours meet without a seam.
            val target = Rect(x, row * mosaic.side, x + mosaic.side + 1f, (row + 1) * mosaic.side + 1f)
            if (reduced) {
                drawLiftPixel(pixel, target, 0f, paint)
                continue
            }
            // Oldest first, as the cover's strips go, but close together so they rise as one flock.
            val lag = 0.1f * i / n
            val q = smoothStep(0.02f + lag, 0.6f + lag, p)
            val from = lift.rectOf(pixel, into, quadGap)
            val rect = if (from == null || q >= 1f) target else lerp(from, target, q)
            val radius = if (from == null) 0f else from.width * 0.18f * (1f - q)
            drawLiftPixel(pixel, rect, radius, paint)
        }
        if (reduced) drawContext.canvas.restore()
        // A light veil under the title once the bar has formed, so white reads on pale covers too.
        val veil = 0.24f * smoothStep(0.6f, 1f, p)
        if (veil > 0f) drawRect(Color.Black, Offset.Zero, Size(size.width, barBottom), alpha = veil)
    }
}

private fun DrawScope.drawLiftPixel(pixel: LiftPixel, rect: Rect, radius: Float, paint: CellPaint) {
    if (pixel.mark < 0) {
        drawLedgerCell(pixel.cell, rect.topLeft, rect.size, radius, paint)
    } else {
        drawMark(pixel.cell.marks[pixel.mark], rect.topLeft, rect.size, radius, paint)
    }
}
