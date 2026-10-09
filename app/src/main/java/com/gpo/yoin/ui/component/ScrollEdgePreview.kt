package com.gpo.yoin.ui.component

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalInspectionMode
import com.gpo.yoin.R
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinTheme
import androidx.compose.ui.MotionDurationScale
import kotlin.math.PI
import kotlin.math.cos

// A live picture of one scroll-edge style (Settings › Scroll edge, the
// landing): a row of filter chips stands for the fixed chrome, and a column
// of covers drifts up under it through the REAL seam modifiers, pinned to
// [style] (seamDissolveViewport's preview-only `style`). The drift is slow
// enough to watch; the seam is fed a speed that swells and eases every seven
// seconds, so each style also shows how it widens under a fast scroll.

/** The style's name in Settings. */
@get:StringRes
internal val SeamTopStyle.nameRes: Int
    get() = when (this) {
        SeamTopStyle.Tide -> R.string.settings_motion_tide_line
        SeamTopStyle.Dots -> R.string.settings_motion_dots
        SeamTopStyle.Cookie -> R.string.settings_motion_cookie_wave
    }

/** One line on what the style does (the landing's picker). */
@get:StringRes
internal val SeamTopStyle.shortRes: Int
    get() = when (this) {
        SeamTopStyle.Tide -> R.string.landing_edge_tide_short
        SeamTopStyle.Dots -> R.string.landing_edge_dots_short
        SeamTopStyle.Cookie -> R.string.landing_edge_cookie_short
    }

/** The longer description (Settings › Scroll edge). */
@get:StringRes
internal val SeamTopStyle.descriptionRes: Int
    get() = when (this) {
        SeamTopStyle.Tide -> R.string.settings_scroll_edge_tide_desc
        SeamTopStyle.Dots -> R.string.settings_scroll_edge_dots_desc
        SeamTopStyle.Cookie -> R.string.settings_scroll_edge_cookie_desc
    }

/**
 * A live preview of [style]. [playing] runs the drift (pass whether the preview is on screen); at rest, or with
 * animations off, the covers stop half-way through the seam so the look still reads.
 */
@Composable
internal fun ScrollEdgePreview(
    style: SeamTopStyle,
    playing: Boolean,
    modifier: Modifier = Modifier,
    columns: Int = 2,
) {
    val scheme = MaterialTheme.colorScheme
    val page = scheme.surfaceContainerLow
    val flow = remember { SeamFlow() }
    val scroll = rememberScrollState()
    var blockHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val inPreview = LocalInspectionMode.current
    val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val animate = playing && !inPreview && durationScale != 0f
    LaunchedEffect(animate, blockHeight) {
        if (blockHeight <= 0) return@LaunchedEffect
        if (!animate) {
            scroll.scrollTo((blockHeight * RestFraction).toInt())
            flow.speedDp = 0f
            return@LaunchedEffect
        }
        val pxPerDp = with(density) { 1.dp.toPx() }
        var position = scroll.value.toFloat()
        var start = -1L
        var last = -1L
        while (true) {
            withFrameNanos { now ->
                if (start < 0) {
                    start = now
                    last = now
                }
                val dt = (now - last) / 1e9f
                last = now
                val swell = 0.5f - 0.5f * cos(2f * PI.toFloat() * ((now - start) / 1e9f) / CyclePeriodSec)
                val delta = (DriftSlowDp + (DriftFastDp - DriftSlowDp) * swell) * pxPerDp * dt
                position = (position + delta) % blockHeight
                flow.travelPx += delta
                flow.speedDp = SeamSpeedPeakDp * swell
            }
            scroll.scrollTo(position.toInt())
        }
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(PreviewCorner))
            .background(page)
            .clearAndSetSemantics {},
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 7.dp),
        ) {
            listOf(30.dp, 24.dp, 34.dp, 28.dp).forEach { width ->
                Box(
                    Modifier
                        .width(width)
                        .height(14.dp)
                        .background(scheme.surfaceContainerHighest, RoundedCornerShape(7.dp)),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .seamDissolveViewport(
                        top = SeamTop.Chrome,
                        flow = flow,
                        background = SeamBackground(listOf(page)),
                        style = style,
                    ) { Float.POSITIVE_INFINITY }
                    .verticalScroll(scroll, enabled = false),
            ) {
                repeat(BlockRepeats) { copy ->
                    CoverBlock(
                        columns = columns,
                        modifier = if (copy == 0) Modifier.onSizeChanged { blockHeight = it.height } else Modifier,
                    )
                }
            }
        }
    }
}

/** Three rows of covers with two text lines under each; repeated so the drift can wrap without a seam. */
@Composable
private fun CoverBlock(columns: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        verticalArrangement = Arrangement.spacedBy(RowGap),
        modifier = modifier.padding(start = 10.dp, end = 10.dp, bottom = RowGap),
    ) {
        repeat(3) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                repeat(columns) { column ->
                    val colors = CoverColors[(row * columns + column) % CoverColors.size]
                    Column(modifier = Modifier.weight(1f)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .seamDissolve()
                                .background(Brush.linearGradient(colors), YoinArtworkShapes.Cover),
                        )
                        TextLine(fraction = 0.8f, color = scheme.onSurfaceVariant.copy(alpha = 0.35f), top = 5.dp)
                        TextLine(fraction = 0.5f, color = scheme.onSurfaceVariant.copy(alpha = 0.22f), top = 3.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TextLine(fraction: Float, color: Color, top: Dp) {
    Box(
        Modifier
            .padding(top = top)
            .fillMaxWidth(fraction)
            .height(4.dp)
            .seamFade()
            .background(color, RoundedCornerShape(2.dp)),
    )
}

private val PreviewCorner = 18.dp
private val RowGap = 10.dp
private const val BlockRepeats = 3
private const val RestFraction = 0.42f
private const val CyclePeriodSec = 7f
private const val DriftSlowDp = 14f
private const val DriftFastDp = 30f
private const val SeamSpeedPeakDp = 700f

private val CoverColors = listOf(
    listOf(Color(0xFF9113FF), Color(0xFF5B2BD0)),
    listOf(Color(0xFFFF59CD), Color(0xFFD9479F)),
    listOf(Color(0xFF192396), Color(0xFF4253D6)),
    listOf(Color(0xFFF2A541), Color(0xFFE0702E)),
    listOf(Color(0xFF2FA58A), Color(0xFF1E6F86)),
    listOf(Color(0xFF7A5CFF), Color(0xFFC49BFF)),
)

@Preview(showBackground = true, widthDp = 120, heightDp = 160)
@Composable
private fun ScrollEdgePreviewPreview() {
    YoinTheme {
        ScrollEdgePreview(style = SeamTopStyle.Cookie, playing = false, modifier = Modifier.fillMaxSize())
    }
}
