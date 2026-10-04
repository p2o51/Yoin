package com.gpo.yoin.ui.component

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Single-line text that marquee-scrolls when it doesn't fit — the standing
 * alternative to wrapping or ellipsising titles (设计规则: 一行显示，超出滚动).
 * Static text stays static; only overflowing text animates, with soft fade
 * masks at the clip edges so the scroll never hard-cuts a glyph.
 * [running] = false holds an overflowing line at its start (clipped, only
 * its end faded) — e.g. until its page has settled. The leading edge only
 * fades once the line actually moves.
 */
@Composable
internal fun MarqueeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    running: Boolean = true,
) {
    BoxWithConstraints(modifier = modifier) {
        val textMeasurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val availableWidthPx = with(density) { maxWidth.roundToPx() }
        val shouldMarquee = remember(text, style, availableWidthPx) {
            if (availableWidthPx <= 0) {
                false
            } else {
                textMeasurer.measure(
                    text = AnnotatedString(text),
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    constraints = Constraints(maxWidth = Constraints.Infinity),
                ).size.width > availableWidthPx
            }
        }

        // the leading edge only fades once the line moves (before that a faded first glyph reads as cut off)
        var moving by remember(text) { mutableStateOf(false) }
        LaunchedEffect(text, running, shouldMarquee) {
            moving = false
            if (running && shouldMarquee) {
                delay(MarqueeInitialDelayMs.toLong())
                moving = true
            }
        }
        Box(
            modifier = when {
                shouldMarquee && !moving ->
                    Modifier
                        .fillMaxWidth()
                        .clipToBounds()
                        .edgeFade(end = 18.dp)
                shouldMarquee ->
                    Modifier
                        .fillMaxWidth()
                        .clipToBounds()
                        .horizontalFadeMask(edgeWidth = 18.dp)
                else -> Modifier.fillMaxWidth()
            },
        ) {
            Text(
                text = text,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = if (shouldMarquee && running) {
                    Modifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        repeatDelayMillis = 1800,
                        initialDelayMillis = MarqueeInitialDelayMs,
                    )
                } else {
                    Modifier
                },
            )
        }
    }
}

private const val MarqueeInitialDelayMs = 1200
