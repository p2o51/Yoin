package com.gpo.yoin.ui.tips

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.gpo.yoin.ui.settings.BarHeight
import com.gpo.yoin.ui.settings.ScreenCornersPageContent
import com.gpo.yoin.ui.theme.ScreenCorners
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Screen corners (owner 2026-11-08): Settings › Motion › Screen corners, its own page in the little
 * phone. The page is one rounded rectangle over black; the demo slides the corner bar down from the
 * roundest until no black shows in this phone's own corners. The demo keeps its value to itself: it never changes the
 * real one.
 */
@Composable
internal fun ScreenCornersDemo(playing: Boolean) {
    var value by remember { mutableFloatStateOf(DemoStartDp) }
    var following by remember { mutableStateOf(true) }
    // The demo phone's corner in its screen's dp (the screen is laid out wide, then scaled down).
    var frameCornerDp by remember { mutableFloatStateOf(DemoFallbackTargetDp) }
    // The bar's width in screen dp, to find where on it a value sits.
    var barWidthDp by remember { mutableFloatStateOf(0f) }
    DemoPhone(playing = playing, script = {
        value = DemoStartDp
        following = true
        delay(600)
        val target = frameCornerDp.coerceIn(ScreenCorners.Min.value, ScreenCorners.Max.value).roundToInt().toFloat()
        moveTo("corner-bar", fx = knobFraction(DemoStartDp, barWidthDp))
        val to = at("corner-bar", fx = knobFraction(target, barWidthDp))
        drag(to.x, to.y, ms = 1_600) { p ->
            following = false
            value = (DemoStartDp + (target - DemoStartDp) * p).roundToInt().toFloat()
        }
        lift()
        delay(2_400)
    }) {
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
            ScreenCornersPageContent(
                value = value,
                following = following,
                reported = null,
                onValueChange = {
                    following = false
                    value = it
                },
                onInteractionChange = {},
                onFollow = {
                    following = true
                    value = DemoStartDp
                },
                onBackClick = {},
                examplesPlaying = playing,
                barModifier = Modifier
                    .demoTarget("corner-bar")
                    .onGloballyPositioned { c ->
                        val laidOut = c.size.width
                        if (laidOut > 0) barWidthDp = laidOut / c.size.height.toFloat() * BarHeight.value
                    },
            )
        }
    }
}

/** Where on the corner bar the knob for [dp] sits, as a fraction of its width. */
private fun knobFraction(dp: Float, barWidthDp: Float): Float {
    if (barWidthDp <= 0f) return 0.5f
    val f = (dp - ScreenCorners.Min.value) / (ScreenCorners.Max.value - ScreenCorners.Min.value)
    val knob = BarHeight.value
    return (knob / 2f + (barWidthDp - knob) * f) / barWidthDp
}

private const val DemoStartDp = 64f
private const val DemoFallbackTargetDp = 48f
