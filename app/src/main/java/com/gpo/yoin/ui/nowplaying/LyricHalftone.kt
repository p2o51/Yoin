package com.gpo.yoin.ui.nowplaying

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Focus's lyric halftone (波点, owner 2026-11-03, prototype v7e): in focus
 * every lyric line is printed through a fine grid of dots. The current line
 * develops from dots to solid text, the other lines rest as fine dots, and a
 * line that has been sung breaks back into dots. Focus only — the resting
 * page and the opened lyrics stay solid.
 *
 * A line's dot radius lives in dp: [LyricDotsRest] at rest, [LyricDotsSolid]
 * and above = no mask at all (solid text, not offscreen).
 */

/** The dot radius (dp) a line shows at [focus] 0..1, given its own [lineRadius]. */
internal fun lyricDotRadius(lineRadius: Float, focus: Float): Float {
    val f = focus.coerceIn(0f, 1f)
    return LyricDotsSolid + (lineRadius - LyricDotsSolid) * f
}

/**
 * Prints the content through a grid of dots [LyricDotsCell] apart, each
 * [radiusDp] across (read in the layer and draw phases only).
 */
fun Modifier.lyricHalftone(radiusDp: () -> Float): Modifier = this
    .graphicsLayer {
        compositingStrategy = if (radiusDp() < LyricDotsSolid) {
            CompositingStrategy.Offscreen
        } else {
            CompositingStrategy.Auto
        }
    }
    .drawWithCache {
        val cell = max(2, LyricDotsCell.dp.toPx().roundToInt())
        val tiles = arrayOfNulls<ShaderBrush>(RadiusSteps + 1)
        fun tile(step: Int): ShaderBrush = tiles[step] ?: run {
            val image = ImageBitmap(cell, cell)
            Canvas(image).drawCircle(
                center = Offset(cell / 2f, cell / 2f),
                radius = (LyricDotsSolid * step / RadiusSteps).dp.toPx(),
                paint = Paint().apply {
                    color = Color.Black
                    isAntiAlias = true
                },
            )
            ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated)).also { tiles[step] = it }
        }
        onDrawWithContent {
            val r = radiusDp()
            if (r >= LyricDotsSolid) {
                drawContent()
                return@onDrawWithContent
            }
            val step = (r / LyricDotsSolid * RadiusSteps).roundToInt().coerceIn(0, RadiusSteps)
            if (step <= 0) return@onDrawWithContent
            drawContent()
            drawRect(brush = tile(step), blendMode = BlendMode.DstIn)
        }
    }

/** The grid pitch, dp. */
internal const val LyricDotsCell = 4f

/** At or above this radius (dp) a 4dp cell is fully covered: solid text. */
internal const val LyricDotsSolid = 3f

/** Where the current line starts developing from, dp. */
internal const val LyricDotsDevelopFrom = 1f

/** Other lines' resting dots, dp. */
internal const val LyricDotsRest = 1.3f

/** The current line develops dots → solid over this long. */
internal const val LyricDotsDevelopMs = 700

/** A sung line breaks back into dots over this long. */
internal const val LyricDotsBreakMs = 600

private const val RadiusSteps = 30
