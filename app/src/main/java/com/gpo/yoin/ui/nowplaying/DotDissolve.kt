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
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What leaves the stage when the cover is focused (the rating bar, the tabs,
 * the lyric list) breaks up into fine dots as it goes, instead of fading
 * (owner 2026-11-02, gradient lab: "这个进入的感觉是好的").
 *
 * The content is masked by a grid of dots ([DotCell] apart) that shrink to
 * nothing as [progress] runs 0 → ~0.74; the last 10% also fades the layer,
 * for content the dots never fully clear. At 0 nothing is masked and the
 * layer is not offscreen; once the dots are gone the content isn't drawn.
 *
 * [progress] is read in the layer and draw phases only.
 */
fun Modifier.dotDissolve(progress: () -> Float): Modifier = this
    .graphicsLayer {
        val c = progress()
        // The DstIn mask needs its own layer, only while it is masking.
        compositingStrategy = if (c > 0.001f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        alpha = 1f - smoothstep(((c - 0.9f) / 0.1f).coerceIn(0f, 1f))
    }
    .drawWithCache {
        val cell = max(2, DotCell.toPx().roundToInt())
        val maxRadius = DotRadius.toPx()
        // One small tile per radius step: a dot in the middle of a cell.
        val tiles = arrayOfNulls<ShaderBrush>(RadiusSteps + 1)
        fun tile(step: Int): ShaderBrush = tiles[step] ?: run {
            val image = ImageBitmap(cell, cell)
            Canvas(image).drawCircle(
                center = Offset(cell / 2f, cell / 2f),
                radius = maxRadius * step / RadiusSteps,
                paint = Paint().apply {
                    color = Color.Black
                    isAntiAlias = true
                },
            )
            ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated)).also { tiles[step] = it }
        }
        onDrawWithContent {
            val c = progress()
            if (c <= 0.001f) {
                drawContent()
                return@onDrawWithContent
            }
            val shrink = 1f - smoothstep(min(1f, c * 1.35f))
            val step = (shrink * RadiusSteps).roundToInt()
            if (step <= 0) return@onDrawWithContent
            drawContent()
            if (step < RadiusSteps) drawRect(brush = tile(step), blendMode = BlendMode.DstIn)
        }
    }

private fun smoothstep(x: Float): Float = x * x * (3f - 2f * x)

private val DotCell = 5.dp
private val DotRadius = 3.3.dp
private const val RadiusSteps = 20
