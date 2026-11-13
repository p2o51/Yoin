package com.gpo.yoin.ui.settings

import com.gpo.yoin.ui.component.handPointer
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.ui.component.MetaGroup
import com.gpo.yoin.ui.component.MetaLine
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.ScreenCorners
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.currentScreenCornerRadius
import com.gpo.yoin.ui.theme.withTabularFigures
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/**
 * The screen-corner control as the landing's step shows it (Settings has a page of its own,
 * [ScreenCornersPage]): the value, "Match screen", the corner bar and its hint. While the bar moves, a
 * line traces the window's edge with the chosen corner, so the user matches it to the glass by eye; it
 * fades a moment after the finger lifts.
 */
@Composable
internal fun ScreenCornerControl(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Loads the stored choice before the first read.
    val radius = currentScreenCornerRadius()
    val following = ScreenCorners.userRadius == null
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

    ScreenCornerSlider(
        value = radius.value,
        following = following,
        reported = ScreenCorners.deviceRadius?.value,
        onValueChange = { whole ->
            ScreenCorners.select(context, whole.dp, persist = false)
            showToken++
        },
        onInteractionChange = { active ->
            dragging = active
            if (!active) ScreenCorners.select(context, ScreenCorners.radius, persist = true)
            showToken++
        },
        onFollow = {
            ScreenCorners.select(context, null)
            showToken++
        },
        modifier = modifier,
    )
    ScreenCornerTrace(visible = traceShown)
}

/**
 * What [ScreenCornerControl] shows, without its state: the value (or "Match screen · N dp"), the
 * "Match screen" reset, the corner bar and its hint.
 */
@Composable
internal fun ScreenCornerSlider(
    value: Float,
    following: Boolean,
    reported: Float?,
    onValueChange: (Float) -> Unit,
    onInteractionChange: (Boolean) -> Unit,
    onFollow: () -> Unit,
    modifier: Modifier = Modifier,
    barModifier: Modifier = Modifier,
    hint: String? = stringResource(R.string.screen_corners_hint),
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScreenCornerValueRow(value = value, following = following, onFollow = onFollow)
        ScreenCornerBar(
            value = value,
            reported = reported,
            onValueChange = onValueChange,
            onInteractionChange = onInteractionChange,
            modifier = barModifier,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** "Match screen" with "N dp" beside it, or the page's title, and the reset while the user's own value is in use. */
@Composable
internal fun ScreenCornerValueRow(
    value: Float,
    following: Boolean,
    onFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    // Fixed height: "Match screen" (a 48dp target) appearing mid-drag must not move the bar.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth().height(48.dp)) {
        if (following) {
            // The label, then the value quieter beside it (MetaLine): no glyph between.
            Box(Modifier.weight(1f).padding(start = 8.dp)) {
                MetaLine(
                    groups = listOf(
                        MetaGroup.Plain(stringResource(R.string.screen_corners_follow)),
                        MetaGroup.Plain(
                            stringResource(R.string.screen_corners_value, value.roundToInt()),
                            muted = true,
                        ),
                    ),
                    style = MaterialTheme.typography.titleMedium.withTabularFigures(),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            Text(
                text = stringResource(R.string.settings_motion_screen_corners),
                style = MaterialTheme.typography.titleMedium.withTabularFigures(),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        }
        AnimatedVisibility(
            visible = !following,
            enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard),
            exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard),
        ) {
            TextButton(
                onClick = {
                    haptics.performConfirm()
                    onFollow()
                },
                modifier = Modifier.handPointer(),
            ) { Text(stringResource(R.string.screen_corners_follow)) }
        }
    }
}

/**
 * The corner bar, drawn like the rating bar (a full-round track, the primary fill from the start, the
 * value inside it) but in whole dp, 0–64. The fill's head is a round knob at 0 and squares off as the
 * value grows. A dot marks what the display reports.
 *
 * Haptics: a light tick each dp, a firmer one every 8dp, a click landing on the display's own value, a
 * threshold at either end. A tap sets the value too; a vertical swipe passes to the page.
 */
@Composable
internal fun ScreenCornerBar(
    value: Float,
    reported: Float?,
    onValueChange: (Float) -> Unit,
    onInteractionChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnInteractionChange by rememberUpdatedState(onInteractionChange)
    val latestReported by rememberUpdatedState(reported)
    val min = ScreenCorners.Min.value
    val max = ScreenCorners.Max.value
    val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)
    val shown = remember { Animatable(fraction) }
    val fillSpec = YoinMotion.fastSpatialSpec<Float>()
    LaunchedEffect(fraction) { shown.animateTo(fraction, fillSpec) }
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.screen_corners_slider_cd)
    val state = stringResource(R.string.screen_corners_value, value.roundToInt())
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(BarHeight)
            .semantics {
                contentDescription = label
                stateDescription = state
                progressBarRangeInfo = ProgressBarRangeInfo(value, min..max, steps = (max - min).roundToInt() - 1)
                setProgress { target ->
                    latestOnValueChange(target.coerceIn(min, max).roundToInt().toFloat())
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val knob = size.height.toFloat()
                    fun valueAt(x: Float): Float {
                        val f = ((x - knob / 2f) / (size.width - knob).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        return (min + f * (max - min)).roundToInt().toFloat()
                    }
                    var last = Float.NaN
                    fun apply(x: Float) {
                        val v = valueAt(x)
                        if (v == last) return
                        if (!last.isNaN()) {
                            val r = latestReported?.roundToInt()?.toFloat()
                            when {
                                r != null && v == r -> haptics.performContextClick()
                                v == min || v == max -> haptics.performThreshold()
                                v.roundToInt() % 8 == 0 -> haptics.performSegmentTick()
                                else -> haptics.performFrequentTick()
                            }
                        }
                        last = v
                        latestOnValueChange(v)
                    }
                    val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    if (start == null) {
                        // A tap (not a swipe the page took): set the value there.
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (up != null && !up.pressed && !up.isConsumed) {
                            up.consume()
                            latestOnInteractionChange(true)
                            haptics.performClick()
                            apply(up.position.x)
                            latestOnInteractionChange(false)
                        }
                        return@awaitEachGesture
                    }
                    latestOnInteractionChange(true)
                    apply(start.position.x)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            apply(change.position.x)
                            change.consume()
                            if (!change.pressed) break
                        }
                    } finally {
                        latestOnInteractionChange(false)
                    }
                }
            }
            .drawBehind {
                val h = size.height
                val full = CornerRadius(h / 2f, h / 2f)
                drawRoundRect(scheme.surfaceVariant, cornerRadius = full)
                val f = shown.value
                val fillWidth = h + (size.width - h) * f
                // Round at its start; its head squares off a little as the corner grows.
                val head = (h / 2f) * (1f - 0.6f * f)
                val fill = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = 0f,
                            top = 0f,
                            right = fillWidth,
                            bottom = h,
                            topLeftCornerRadius = full,
                            bottomLeftCornerRadius = full,
                            topRightCornerRadius = CornerRadius(head, head),
                            bottomRightCornerRadius = CornerRadius(head, head),
                        ),
                    )
                }
                drawPath(fill, scheme.primary)
                val r = reported
                if (r != null) {
                    val rf = ((r - min) / (max - min)).coerceIn(0f, 1f)
                    val x = h / 2f + (size.width - h) * rf
                    val covered = x < fillWidth - h * 0.2f
                    drawCircle(
                        color = if (covered) {
                            scheme.onPrimary.copy(alpha = 0.7f)
                        } else {
                            scheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                        radius = 2.5.dp.toPx(),
                        // Low in the bar, under the value's text when the two meet.
                        center = Offset(x, h - 8.dp.toPx()),
                    )
                }
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        // The value inside the bar, at its end; it turns onPrimary as the fill reaches it.
        val crossover = ((shown.value - 0.80f) / 0.20f).coerceIn(0f, 1f)
        Text(
            text = stringResource(R.string.screen_corners_value, value.roundToInt()),
            style = MaterialTheme.typography.titleMedium.withTabularFigures(),
            color = lerp(scheme.onSurfaceVariant, scheme.onPrimary, crossover),
            maxLines = 1,
            modifier = Modifier.padding(end = 20.dp),
        )
    }
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

/** The bar's height: the rating bar's 48dp, a little taller as a page's main control. */
internal val BarHeight = 56.dp
private const val TraceLingerMs = 1200L
private val TraceStroke = 3.dp
