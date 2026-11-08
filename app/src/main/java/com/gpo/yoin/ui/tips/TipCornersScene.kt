package com.gpo.yoin.ui.tips

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.settings.ScreenCornerSlider
import com.gpo.yoin.ui.settings.SettingsItem
import com.gpo.yoin.ui.settings.SettingsRowTextInset
import com.gpo.yoin.ui.theme.ScreenCorners
import com.gpo.yoin.ui.theme.YoinMotion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Screen corners (owner 2026-11-08): Settings › Motion › Screen corners, its own slider row. Sliding
 * draws a line around the phone's edge at the chosen corner; the demo slides until the line sits on
 * this little phone's own corner. The demo keeps its value to itself: it never changes the real one.
 */
@Composable
internal fun ScreenCornersDemo(playing: Boolean) {
    var value by remember { mutableFloatStateOf(DemoStartDp) }
    var following by remember { mutableStateOf(true) }
    // The demo phone's corner in its screen's dp (the screen is laid out wide, then scaled down).
    var frameCornerDp by remember { mutableFloatStateOf(DemoFallbackTargetDp) }
    val trace = remember { Animatable(0f) }
    var touches by remember { mutableIntStateOf(0) }
    val fadeSpec = YoinMotion.defaultEffectsSpec<Float>()
    // The user's own slides show the line too, and it fades a moment after they stop.
    LaunchedEffect(touches) {
        if (touches == 0) return@LaunchedEffect
        trace.animateTo(1f, fadeSpec)
        delay(TraceHoldMs)
        trace.animateTo(0f, fadeSpec)
    }
    DemoPhone(playing = playing, script = {
        value = DemoStartDp
        following = true
        trace.snapTo(0f)
        delay(500)
        val target = frameCornerDp.coerceIn(ScreenCorners.Min.value, ScreenCorners.Max.value).roundToInt().toFloat()
        moveTo("corner-slider", fx = thumbFraction(DemoStartDp))
        val to = at("corner-slider", fx = thumbFraction(target))
        coroutineScope {
            launch { trace.animateTo(1f, fadeSpec) }
            drag(to.x, to.y, ms = 1_400) { p ->
                following = false
                value = (DemoStartDp + (target - DemoStartDp) * p).roundToInt().toFloat()
            }
        }
        lift()
        delay(TraceHoldMs)
        trace.animateTo(0f, fadeSpec)
        delay(900)
    }) {
        val scheme = MaterialTheme.colorScheme
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    val laidOut = coordinates.size.width
                    val shown = coordinates.boundsInRoot().width
                    if (laidOut > 0 && shown > 0f) {
                        // Root px are the page's; one screen dp is [shown / laidOut] of a page dp.
                        frameCornerDp = DemoPhoneCorner.value * laidOut / shown
                    }
                },
        ) {
            Column(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(top = 24.dp)) {
                SettingsItem(
                    icon = YoinSymbols.Smartphone,
                    title = stringResource(R.string.settings_motion_screen_corners),
                )
                ScreenCornerSlider(
                    value = value,
                    following = following,
                    onValueChange = {
                        following = false
                        value = it.roundToInt().toFloat()
                        touches++
                    },
                    onValueChangeFinished = { touches++ },
                    onFollow = {
                        following = true
                        value = DemoStartDp
                        touches++
                    },
                    modifier = Modifier.padding(start = SettingsRowTextInset - 8.dp, end = 16.dp, bottom = 16.dp),
                    sliderModifier = Modifier.demoTarget("corner-slider"),
                )
            }
            // The line around the edge, as the settings row draws it around the real window.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val a = trace.value
                if (a <= 0f) return@Canvas
                val stroke = TraceStrokeDp.dp.toPx()
                val half = stroke / 2f
                val r = (value.dp.toPx() - half).coerceAtLeast(0f)
                drawRoundRect(
                    color = scheme.primary.copy(alpha = a),
                    topLeft = Offset(half, half),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = stroke),
                )
            }
        }
    }
}

/** Where on the slider the thumb sits for [dp]: the track runs nearly edge to edge. */
private fun thumbFraction(dp: Float): Float {
    val f = (dp - ScreenCorners.Min.value) / (ScreenCorners.Max.value - ScreenCorners.Min.value)
    return SliderInset + (1f - 2f * SliderInset) * f
}

private const val DemoStartDp = 8f
private const val DemoFallbackTargetDp = 48f
private const val SliderInset = 0.02f
private const val TraceHoldMs = 1_200L
private const val TraceStrokeDp = 3f
