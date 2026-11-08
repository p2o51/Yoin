package com.gpo.yoin.ui.settings

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.ScreenCorners
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.currentScreenCornerRadius
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/**
 * The screen-corner slider (Settings › Motion › Screen corners, and the landing's step). While it moves, a
 * line traces the window's edge with the chosen corner, so the user matches it to the glass by eye; it
 * fades a moment after the finger lifts. A tick every 4dp, a firmer click passing the corner the display
 * reports. "Match screen" forgets the choice.
 */
@Composable
internal fun ScreenCornerControl(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = rememberYoinHaptics()
    // Loads the stored choice before the first read.
    val radius = currentScreenCornerRadius()
    val following = ScreenCorners.userRadius == null
    val reported = ScreenCorners.deviceRadius
    var dragging by remember { mutableStateOf(false) }
    // The trace stays up while the finger moves and for a moment after; each change restarts that moment.
    var showToken by remember { mutableIntStateOf(0) }
    var traceShown by remember { mutableStateOf(false) }
    LaunchedEffect(showToken, dragging) {
        if (showToken == 0) return@LaunchedEffect
        traceShown = true
        if (!dragging) {
            delay(TraceLingerMs)
            traceShown = false
        }
    }
    var lastWhole by remember { mutableFloatStateOf(radius.value.roundToInt().toFloat()) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            // Fixed height: "Match screen" (a 48dp target) appearing mid-drag must not move the slider.
            Text(
                text = if (following) {
                    stringResource(R.string.screen_corners_summary_follow, radius.value.roundToInt())
                } else {
                    stringResource(R.string.screen_corners_value, radius.value.roundToInt())
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            AnimatedVisibility(
                visible = !following,
                enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard),
                exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard),
            ) {
                TextButton(
                    onClick = {
                        haptics.performClick()
                        ScreenCorners.select(context, null)
                        lastWhole = ScreenCorners.radius.value.roundToInt().toFloat()
                        showToken++
                    },
                ) { Text(stringResource(R.string.screen_corners_follow)) }
            }
        }
        val label = stringResource(R.string.screen_corners_slider_cd)
        Slider(
            value = radius.value,
            onValueChange = { raw ->
                val whole = raw.roundToInt().toFloat()
                if (whole != lastWhole) {
                    val crossedReported = reported != null &&
                        (lastWhole - reported.value) * (whole - reported.value) <= 0f &&
                        whole.roundToInt() == reported.value.roundToInt()
                    when {
                        crossedReported -> haptics.performContextClick()
                        whole.roundToInt() % 4 == 0 -> haptics.performTick()
                    }
                    lastWhole = whole
                }
                if (!dragging) {
                    dragging = true
                    showToken++
                }
                ScreenCorners.select(context, whole.dp, persist = false)
            },
            onValueChangeFinished = {
                dragging = false
                ScreenCorners.select(context, ScreenCorners.radius, persist = true)
                showToken++
            },
            valueRange = ScreenCorners.Min.value..ScreenCorners.Max.value,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label },
        )
        Text(
            text = stringResource(R.string.screen_corners_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ScreenCornerTrace(visible = traceShown)
}

/**
 * A line around the whole window at [ScreenCorners.radius], drawn on the window's own overlay (above every
 * page, under nothing, never clipped by a scrolling list, never taking a touch).
 */
@Composable
private fun ScreenCornerTrace(visible: Boolean) {
    val root = LocalView.current.rootView
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.primary.toArgb()
    val drawable = remember(root) { CornerTraceDrawable() }
    val alpha = remember { Animatable(0f) }
    val fadeSpec = YoinMotion.defaultEffectsSpec<Float>()
    DisposableEffect(root, drawable) {
        root.overlay.add(drawable)
        onDispose { root.overlay.remove(drawable) }
    }
    LaunchedEffect(visible) {
        alpha.animateTo(if (visible) 1f else 0f, fadeSpec)
    }
    LaunchedEffect(root, drawable, color, density) {
        snapshotFlow { Pair(ScreenCorners.radius, alpha.value) }.collectLatest { (radius, a) ->
            drawable.update(
                radiusPx = with(density) { radius.toPx() },
                strokePx = with(density) { TraceStroke.toPx() },
                color = color,
                alpha = a,
                width = root.width,
                height = root.height,
            )
        }
    }
}

private class CornerTraceDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private var radiusPx = 0f
    private var fade = 0f

    fun update(radiusPx: Float, strokePx: Float, color: Int, alpha: Float, width: Int, height: Int) {
        this.radiusPx = radiusPx
        fade = alpha
        paint.color = color
        paint.strokeWidth = strokePx
        setBounds(0, 0, width, height)
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (fade <= 0f) return
        val half = paint.strokeWidth / 2f
        rect.set(bounds.left + half, bounds.top + half, bounds.right - half, bounds.bottom - half)
        val baseAlpha = paint.alpha
        paint.alpha = (baseAlpha * fade).roundToInt().coerceIn(0, 255)
        // The stroke sits inside the edge: its outer side follows the corner exactly.
        val r = (radiusPx - half).coerceAtLeast(0f)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.alpha = baseAlpha
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private const val TraceLingerMs = 1200L
private val TraceStroke = 3.dp
