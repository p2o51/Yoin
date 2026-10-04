package com.gpo.yoin.ui.memories.showcase

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.MarqueeText
import com.gpo.yoin.ui.experience.DeckIndicatorTransitionState
import com.gpo.yoin.ui.theme.GoogleSansFlex
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The Memories top bar (twostate4 `.ts-bar`, `.ts-slotA/B`, `.ts-cov-bar`). Two layers:
 *
 *  · shared, over the pager ([MemoriesTopBar]): the "⌃ Home" pill (Home only, in every state) and the page
 *    dots — each dot a 32×48 hit box on the 18dp pitch, a tap resolved to the nearest dot centre by x, so
 *    every dot's area is symmetric about it;
 *  · per page ([MemoryPageBarSlots]): slot A ("Memories" / "Last heard …"), and the diary's 40dp cover and
 *    slot B (album name marquee + ⌄), composed only once the diary morph starts (P5b drives it).
 *
 * Per-page slots ride the pager with a parallax (x = 0.6·W·rel, so they move at 40% of the page) and fade by
 * 1 − |rel|·2.2; a neighbour's invisible controls take no taps and carry no semantics. Every per-frame value
 * (pager position, p) is read in layout or draw only.
 */

/** Top bar geometry (prototype layoutFor, phone tier). Layout constants, not motion tokens. */
internal object MemoriesTopBarTokens {
    /** Under the status bar. */
    val Height: Dp = 64.dp
    val PillStart: Dp = 16.dp
    val DotsEnd: Dp = 12.dp
    val PillHeight: Dp = 36.dp
    val PillWidth: Dp = 88.dp

    /** The pill makes room for the diary's bar cover. */
    val PillWidthDiary: Dp = 36.dp
    val HitHeight: Dp = 48.dp

    /** The pill's invisible hit margin on each side (::before inset −6). */
    val PillHitMargin: Dp = 6.dp

    /** Dots: 32×48 boxes on an 18dp pitch (−7dp margins), so neighbouring boxes overlap. */
    val DotPitch: Dp = 18.dp
    val DotBox: Dp = 32.dp
    val DotOverhang: Dp = 7.dp
    val DotSize: Dp = 7.dp
    val DotSizeCurrent: Dp = 12.dp

    /** Slot A: 100dp past the pill's start; its right edge stays 12dp clear of the first dot box. */
    val SlotAStart: Dp = 116.dp
    val SlotClearOfDots: Dp = 12.dp

    /** Diary state: the 40dp cover after the pill, then slot B 14dp after it (header breathing). */
    val BarCoverStart: Dp = 64.dp
    val BarCover: Dp = 40.dp
    val SlotBStart: Dp = 118.dp
}

/** Smoothstep, prototype `ss(a, b, x)`. */
internal fun smoothstep(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Index of the dot whose centre is nearest [x] in the cluster's own coordinates (box 0 starts at 0). */
internal fun nearestDot(x: Float, count: Int, pitchPx: Float, firstCentrePx: Float): Int {
    if (count <= 0) return 0
    return ((x - firstCentrePx) / pitchPx).roundToInt().coerceIn(0, count - 1)
}

/** The end inset of the per-page slots: clear of the dot cluster by [MemoriesTopBarTokens.SlotClearOfDots]. */
internal fun slotEndInset(dotCount: Int): Dp = with(MemoriesTopBarTokens) {
    DotsEnd + DotPitch * dotCount + DotOverhang + SlotClearOfDots
}

/**
 * The shared bar: Home pill + dots. [diaryProgress] (p, 0 card … 1 diary) narrows the pill 88 → 36 in the
 * layout phase and fades its label. [position] is the pager's continuous page. [modifier] should place it
 * under the status bar, [MemoriesTopBarTokens.Height] tall.
 */
@Composable
internal fun MemoriesTopBar(
    dotColors: List<Color>,
    dotLabels: List<String>,
    position: () -> Float,
    diaryProgress: () -> Float,
    onHome: () -> Unit,
    onDot: (Int) -> Unit,
    modifier: Modifier = Modifier,
    edgeHint: DeckIndicatorTransitionState? = null,
) {
    Box(modifier = modifier.fillMaxWidth().height(MemoriesTopBarTokens.Height)) {
        HomePill(
            diaryProgress = diaryProgress,
            onClick = onHome,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = MemoriesTopBarTokens.PillStart - MemoriesTopBarTokens.PillHitMargin),
        )
        PageDots(
            colors = dotColors,
            labels = dotLabels,
            position = position,
            onDot = onDot,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = MemoriesTopBarTokens.DotsEnd - MemoriesTopBarTokens.DotOverhang)
                .graphicsLayer {
                    if (edgeHint != null) {
                        translationX = edgeHint.translationXPx
                        scaleX = edgeHint.scale
                        scaleY = edgeHint.scale
                        alpha = edgeHint.alpha
                    }
                },
        )
    }
}

@Composable
private fun HomePill(diaryProgress: () -> Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            // 88 → 36 as the diary opens: a layout-phase read of p, never a recomposition
            .layout { measurable, _ ->
                val pp = smoothstep(0f, 0.6f, diaryProgress().coerceIn(0f, 1f))
                val pill = lerp(MemoriesTopBarTokens.PillWidth, MemoriesTopBarTokens.PillWidthDiary, pp)
                val w = (pill + MemoriesTopBarTokens.PillHitMargin * 2).roundToPx()
                val h = MemoriesTopBarTokens.HitHeight.roundToPx()
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(0, 0) }
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = "Back to Home",
                onClick = onClick,
            )
            .semantics { contentDescription = "Back to Home" }
            .padding(
                horizontal = MemoriesTopBarTokens.PillHitMargin,
                vertical = (MemoriesTopBarTokens.HitHeight - MemoriesTopBarTokens.PillHeight) / 2,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .indication(interaction, ripple())
                .clipToBounds()
                .padding(start = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = YoinSymbols.ChevronUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = "Home",
                style = barTextStyle(14, FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.graphicsLayer {
                    alpha = 1f - smoothstep(0f, 0.4f, diaryProgress().coerceIn(0f, 1f))
                },
            )
        }
    }
}

@Composable
private fun PageDots(
    colors: List<Color>,
    labels: List<String>,
    position: () -> Float,
    onDot: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = colors.size
    Layout(
        modifier = modifier
            .pointerInput(count, onDot) {
                val pitch = MemoriesTopBarTokens.DotPitch.toPx()
                val first = MemoriesTopBarTokens.DotBox.toPx() / 2
                detectTapGestures { tap -> onDot(nearestDot(tap.x, count, pitch, first)) }
            }
            .drawBehind {
                val pitch = MemoriesTopBarTokens.DotPitch.toPx()
                val first = MemoriesTopBarTokens.DotBox.toPx() / 2
                val pos = position()
                colors.forEachIndexed { i, color ->
                    val w = (1f - abs(pos - i)).coerceIn(0f, 1f)
                    val d = lerp(MemoriesTopBarTokens.DotSize, MemoriesTopBarTokens.DotSizeCurrent, w).toPx()
                    drawCircle(
                        color = color,
                        radius = d / 2f,
                        center = Offset(first + pitch * i, size.height / 2f),
                        alpha = 0.5f + 0.5f * w,
                    )
                }
            },
        content = {
            // one semantics node per dot (the cluster resolves the taps)
            labels.forEachIndexed { i, label ->
                Box(
                    Modifier.semantics {
                        role = Role.Button
                        contentDescription = label
                        onClick {
                            onDot(i)
                            true
                        }
                    },
                )
            }
        },
    ) { measurables, _ ->
        val pitch = MemoriesTopBarTokens.DotPitch.roundToPx()
        val box = MemoriesTopBarTokens.DotBox.roundToPx()
        val h = MemoriesTopBarTokens.HitHeight.roundToPx()
        val width = pitch * (count - 1).coerceAtLeast(0) + box
        val placeables = measurables.map { it.measure(Constraints.fixed(box, h)) }
        layout(width, h) { placeables.forEachIndexed { i, p -> p.place(pitch * i, 0) } }
    }
}

/**
 * One page's share of the bar. [relative] is rel = pager position − this page; [diaryProgress] is p.
 * Slot A ("Memories" / "Last heard …") leaves as the diary opens; the diary's cover and slot B only exist
 * from the morph's first frame (P5b drives p; in P5a they are never composed).
 */
@Composable
internal fun MemoryPageBarSlots(
    album: String,
    artistLine: String,
    artistShort: String,
    lastHeard: String?,
    dotCount: Int,
    relative: () -> Float,
    diaryProgress: () -> Float,
    onCloseDiary: () -> Unit,
    cover: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val slotEnd = slotEndInset(dotCount)
    val inDiaryMorph by remember { derivedStateOf { diaryProgress() > 0.001f } }
    // a neighbour's controls (parked over this bar by the parallax, invisible) take no taps
    val onShow by remember { derivedStateOf { barVisibility(relative()) >= 0.5f } }
    val diaryOpen by remember { derivedStateOf { diaryProgress() >= 0.5f } }
    val shown = onShow
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(MemoriesTopBarTokens.Height)
            // the page's own x is −rel·W (the pager); this adds 0.6·W·rel, so the slots travel at 40%
            .graphicsLayer {
                val rel = relative()
                translationX = parallaxPx(rel, size.width)
                alpha = barVisibility(rel)
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .padding(start = MemoriesTopBarTokens.SlotAStart, end = slotEnd)
                .clearAndSetSemantics {
                    if (shown) contentDescription = listOfNotNull("Memories", lastHeard).joinToString(", ")
                }
                .graphicsLayer {
                    val p = diaryProgress().coerceIn(0f, 1f)
                    translationY = -6.dp.toPx() * p
                    alpha = 1f - smoothstep(0f, 0.45f, p)
                },
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Memories",
                style = barTextStyle(15, FontWeight.SemiBold, lineHeight = 1.25f),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            if (lastHeard != null) {
                Text(
                    text = lastHeard,
                    style = barTextStyle(12, FontWeight.Medium, lineHeight = 1.35f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
        if (inDiaryMorph) {
            val interactive = diaryOpen && shown
            val tapToCard = if (interactive) {
                Modifier.clickable(onClickLabel = "Close diary, back to the card", onClick = onCloseDiary)
            } else {
                Modifier.clearAndSetSemantics { }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = MemoriesTopBarTokens.BarCoverStart - BarCoverHitMargin)
                    .size(MemoriesTopBarTokens.HitHeight)
                    .then(tapToCard)
                    .graphicsLayer { alpha = smoothstep(0.62f, 0.9f, diaryProgress()) },
                contentAlignment = Alignment.Center,
            ) {
                cover(Modifier.size(MemoriesTopBarTokens.BarCover).clip(YoinArtworkShapes.ThumbAnimated))
            }
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .padding(start = MemoriesTopBarTokens.SlotBStart, end = slotEnd)
                    .then(tapToCard)
                    .graphicsLayer {
                        val a = smoothstep(0.55f, 0.92f, diaryProgress())
                        translationY = (1f - a) * 10.dp.toPx()
                        alpha = a
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    MarqueeText(
                        text = album,
                        style = barTextStyle(15, FontWeight.SemiBold, lineHeight = 1.3f),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    BarArtistLine(full = artistLine, short = artistShort)
                }
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = YoinSymbols.ChevronDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** The bar cover's 48dp hit box around its 40dp art. */
private val BarCoverHitMargin: Dp = (MemoriesTopBarTokens.HitHeight - MemoriesTopBarTokens.BarCover) / 2

/** The artist line first drops " · year"; only the artist alone still overflowing scrolls. */
@Composable
private fun BarArtistLine(full: String, short: String) {
    val style = barTextStyle(12, FontWeight.Medium, lineHeight = 1.35f)
    BoxWithConstraints {
        val measurer = rememberTextMeasurer()
        val maxPx = constraints.maxWidth
        val text = remember(full, short, maxPx, style) {
            val fits = measurer.measure(full, style, softWrap = false, maxLines = 1).size.width <= maxPx
            if (fits) full else short
        }
        MarqueeText(text = text, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A page's bar visibility at rel (prototype `vis`). */
internal fun barVisibility(relative: Float): Float =
    if (abs(relative) > 1.6f) 0f else (1f - abs(relative) * 2.2f).coerceIn(0f, 1f)

/** The bar slots' extra x on top of the page's own: 0.6·W·rel, so they travel at 40% of the page. */
internal fun parallaxPx(relative: Float, pageWidthPx: Float): Float = relative * pageWidthPx * 0.6f

@Composable
private fun barTextStyle(sizeSp: Int, weight: FontWeight, lineHeight: Float = 1f): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(
        fontFamily = GoogleSansFlex,
        fontWeight = weight,
        fontSize = sizeSp.sp,
        lineHeight = (sizeSp * lineHeight).sp,
        letterSpacing = 0.sp,
    )

@Preview(name = "Memories top bar · card state")
@Composable
private fun MemoriesTopBarPreview() {
    YoinTheme(darkTheme = false) {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) {
            MemoryPageBarSlots(
                album = "夜行列车与未寄出的信",
                artistLine = "椎名林檎 & 东京事变 · 2019",
                artistShort = "椎名林檎 & 东京事变",
                lastHeard = "Last heard Oct 2",
                dotCount = 5,
                relative = { 0f },
                diaryProgress = { 0f },
                onCloseDiary = {},
                cover = { Box(it.background(MemoryPaletteSamples.M1.base)) },
            )
            MemoriesTopBar(
                dotColors = listOf(
                    MemoryPaletteSamples.M1.base,
                    MemoryPaletteSamples.M2.base,
                    MemoryPaletteSamples.M3.base,
                    MemoryPaletteSamples.M4.base,
                    MemoryPaletteSamples.M5.base,
                ),
                dotLabels = List(5) { "Memory ${it + 1}" },
                position = { 0f },
                diaryProgress = { 0f },
                onHome = {},
                onDot = {},
            )
        }
    }
}
