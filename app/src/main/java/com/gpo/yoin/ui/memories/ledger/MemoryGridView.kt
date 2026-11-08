package com.gpo.yoin.ui.memories.ledger

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.memory.MemoryGrid
import com.gpo.yoin.data.memory.MemoryGridCell
import com.gpo.yoin.data.memory.MemoryGridMark
import com.gpo.yoin.data.memory.MemoryGridScale
import com.gpo.yoin.data.memory.MemoryLedgerAlbum
import com.gpo.yoin.ui.component.rememberExpressiveBackdropColors
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/*
 * How a cell reads (owner 2026-11-08): a cell is the album's cover, told by its two colours, never the
 * cover itself (Spotify's artwork may not be cut up). An album you wrote about splits its base and accent
 * on the diagonal; one you only scored is its base, solid; two albums in a week or day splice their bases
 * on the diagonal. A month is a 2×2 of its top four albums. Nothing kept: a faint cell.
 */

/** One album's two colours, as the grid paints it. */
@Immutable
data class LedgerAlbumColors(val base: Color, val accent: Color)

/**
 * The colours of [albums], read off their covers ([coverUrls]) the way every Yoin card is, and springing
 * in as each read lands. Until then (or without a cover) an album wears a muted colour of its own, picked
 * from its key, so cells don't all wait in one grey.
 */
@Composable
internal fun rememberLedgerAlbumColors(
    albums: List<MemoryLedgerAlbum>,
    coverUrls: Map<String, String>,
    enabled: Boolean = true,
): Map<String, LedgerAlbumColors> {
    val out = HashMap<String, LedgerAlbumColors>(albums.size)
    albums.forEach { album ->
        key(album.key) {
            val fallback = remember(album.key) { fallbackAlbumColors(album.key) }
            val colors = rememberExpressiveBackdropColors(
                model = coverUrls[album.key],
                fallbackBaseColor = fallback.base,
                fallbackAccentColor = fallback.accent,
                enabled = enabled,
            )
            out[album.key] = LedgerAlbumColors(colors.baseColor, colors.accentColor)
        }
    }
    return out
}

internal fun fallbackAlbumColors(key: String): LedgerAlbumColors {
    val hue = (abs(key.hashCode()) % 360).toFloat()
    return LedgerAlbumColors(
        base = Color.hsl(hue, 0.32f, 0.42f),
        accent = Color.hsl((hue + 24f) % 360f, 0.42f, 0.66f),
    )
}

/** The albums the [cells] show, each once. */
internal fun albumsIn(cells: List<MemoryGridCell>): List<MemoryLedgerAlbum> =
    cells.flatMap { cell -> cell.marks.map { it.album } }.distinctBy { it.key }

/** Where the grid sits: Home's card (compact) or the All Memories page (hero). */
enum class MemoryGridPlacement { Home, Page }

/**
 * The grid, for its scale. A week is 13×2 (half a year), a day 17×7 (a column a week); a month is a row of
 * 2×2 quads, the last six on Home and every one on the page (in rows of four, with its name and count).
 * [onCellClick] gets the tapped cell's index in [MemoryGrid.cells]; [selected] is outlined.
 */
@Composable
internal fun MemoryGridView(
    grid: MemoryGrid,
    coverUrls: Map<String, String>,
    placement: MemoryGridPlacement,
    modifier: Modifier = Modifier,
    selected: Int? = null,
    extractColors: Boolean = true,
    onCellClick: ((Int) -> Unit)? = null,
    monthCaption: (@Composable (MemoryGridCell) -> String)? = null,
) {
    val cells = when {
        grid.scale == MemoryGridScale.Month && placement == MemoryGridPlacement.Home -> grid.cells.takeLast(6)
        else -> grid.cells
    }
    val offset = grid.cells.size - cells.size
    val colors = rememberLedgerAlbumColors(albumsIn(cells), coverUrls, extractColors)
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val outline = MaterialTheme.colorScheme.primary
    val paint = remember(colors, faint) { CellPaint(colors, faint) }
    when (grid.scale) {
        MemoryGridScale.Month -> MonthQuads(
            cells = cells,
            offset = offset,
            paint = paint,
            outline = outline,
            columns = if (placement == MemoryGridPlacement.Home) 6 else 4,
            selected = selected,
            onCellClick = onCellClick,
            caption = monthCaption,
            modifier = modifier,
        )
        MemoryGridScale.Week, MemoryGridScale.Day -> {
            val week = grid.scale == MemoryGridScale.Week
            CellWall(
                cells = cells,
                columns = if (week) 13 else 17,
                rows = if (week) 2 else 7,
                columnMajor = !week,
                gap = if (week) 5.dp else 3.dp,
                paint = paint,
                outline = outline,
                selected = selected,
                onCellClick = onCellClick,
                modifier = modifier,
            )
        }
    }
}

@Immutable
internal class CellPaint(val colors: Map<String, LedgerAlbumColors>, val faint: Color) {
    fun of(album: MemoryLedgerAlbum): LedgerAlbumColors = colors[album.key] ?: fallbackAlbumColors(album.key)
}

/** A week's or day's cell: nothing, one album (split if written about), or two spliced. */
internal fun DrawScope.drawLedgerCell(
    cell: MemoryGridCell,
    topLeft: Offset,
    size: Size,
    radius: Float,
    paint: CellPaint,
) {
    if (cell.future) return
    val marks = cell.marks
    when {
        marks.isEmpty() -> drawRoundRect(paint.faint, topLeft, size, CornerRadius(radius))
        marks.size == 1 -> drawMark(marks[0], topLeft, size, radius, paint)
        else -> drawSplit(paint.of(marks[0].album).base, paint.of(marks[1].album).base, topLeft, size, radius)
    }
}

private fun DrawScope.drawMark(mark: MemoryGridMark, topLeft: Offset, size: Size, radius: Float, paint: CellPaint) {
    val colors = paint.of(mark.album)
    if (mark.wrote) {
        drawSplit(colors.base, colors.accent, topLeft, size, radius)
    } else {
        drawRoundRect(colors.base, topLeft, size, CornerRadius(radius))
    }
}

/** [first] in the top-left half, [second] in the bottom-right: the diagonal runs top-right to bottom-left. */
private fun DrawScope.drawSplit(first: Color, second: Color, topLeft: Offset, size: Size, radius: Float) {
    val clip = Path().apply { addRoundRect(RoundRect(Rect(topLeft, size), CornerRadius(radius))) }
    clipPath(clip) {
        drawRect(second, topLeft, size)
        val triangle = Path().apply {
            moveTo(topLeft.x, topLeft.y)
            lineTo(topLeft.x + size.width, topLeft.y)
            lineTo(topLeft.x, topLeft.y + size.height)
            close()
        }
        drawPath(triangle, first)
    }
}

@Composable
private fun CellWall(
    cells: List<MemoryGridCell>,
    columns: Int,
    rows: Int,
    columnMajor: Boolean,
    gap: Dp,
    paint: CellPaint,
    outline: Color,
    selected: Int?,
    onCellClick: ((Int) -> Unit)?,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val gapPx = with(LocalDensity.current) { gap.toPx() }
        val cellDp = (maxWidth - gap * (columns - 1)) / columns
        val height = cellDp * rows + gap * (rows - 1)
        fun position(index: Int): Pair<Int, Int> =
            if (columnMajor) (index / rows) to (index % rows) else (index % columns) to (index / columns)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .then(
                    if (onCellClick == null) {
                        Modifier
                    } else {
                        Modifier.pointerInput(cells, columns, rows, columnMajor) {
                            detectTapGestures { tap ->
                                val cell = (size.width - gapPx * (columns - 1)) / columns
                                val column = (tap.x / (cell + gapPx)).toInt().coerceIn(0, columns - 1)
                                val row = (tap.y / (cell + gapPx)).toInt().coerceIn(0, rows - 1)
                                val index = if (columnMajor) column * rows + row else row * columns + column
                                if (index in cells.indices && !cells[index].isEmpty) onCellClick(index)
                            }
                        }
                    },
                ),
        ) {
            val cell = (size.width - gapPx * (columns - 1)) / columns
            val radius = cell * 0.18f
            cells.forEachIndexed { index, item ->
                val (column, row) = position(index)
                val topLeft = Offset(column * (cell + gapPx), row * (cell + gapPx))
                drawLedgerCell(item, topLeft, Size(cell, cell), radius, paint)
                if (index == selected) {
                    val stroke = 2.dp.toPx()
                    drawRoundRect(
                        color = outline,
                        topLeft = topLeft - Offset(stroke, stroke),
                        size = Size(cell + stroke * 2, cell + stroke * 2),
                        cornerRadius = CornerRadius(radius + stroke),
                        style = Stroke(width = stroke),
                    )
                }
            }
        }
    }
}

/** A month: its top four albums, a quad each (written: split; scored: solid), in a 2×2. */
@Composable
private fun MonthQuad(cell: MemoryGridCell, paint: CellPaint, outlined: Boolean, outline: Color, modifier: Modifier) {
    Canvas(modifier = modifier.aspectRatio(1f)) {
        val gap = 2.dp.toPx()
        val quad = (size.width - gap) / 2
        val radius = quad * 0.16f
        repeat(4) { index ->
            val topLeft = Offset((index % 2) * (quad + gap), (index / 2) * (quad + gap))
            val mark = cell.marks.getOrNull(index)
            if (mark == null) {
                drawRoundRect(paint.faint, topLeft, Size(quad, quad), CornerRadius(radius))
            } else {
                drawMark(mark, topLeft, Size(quad, quad), radius, paint)
            }
        }
        if (outlined) {
            drawRoundRect(outline, Offset.Zero, size, CornerRadius(radius * 1.6f), style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun MonthQuads(
    cells: List<MemoryGridCell>,
    offset: Int,
    paint: CellPaint,
    outline: Color,
    columns: Int,
    selected: Int?,
    onCellClick: ((Int) -> Unit)?,
    caption: (@Composable (MemoryGridCell) -> String)?,
    modifier: Modifier,
) {
    val locale = Locale.getDefault()
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cells.withIndex().chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(if (columns > 4) 8.dp else 12.dp)) {
                row.forEach { (index, cell) ->
                    val name = remember(cell.start, locale) {
                        cell.start.month.getDisplayName(TextStyle.SHORT, locale)
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = MonthDescription.format(cell.start) }
                            .then(
                                if (onCellClick != null && !cell.isEmpty) {
                                    Modifier.pointerInput(index) { detectTapGestures { onCellClick(offset + index) } }
                                } else {
                                    Modifier
                                },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        MonthQuad(
                            cell,
                            paint,
                            outlined = selected == offset + index,
                            outline = outline,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        caption?.let {
                            Text(
                                text = it(cell),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                // A short last row keeps its cells the size of the rest.
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

private val MonthDescription: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy MMMM")
