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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
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
 *    slot B (album name marquee + ⌄), composed once the diary morph starts. The bar cover rides the card
 *    cover's flight ([MemoryPageMorph.barCover]) and takes over from it by alpha; a tap on it, on slot B or
 *    on the ⌄ closes the diary.
 *
 * The shared bar shares its touches with the pager beneath, so a horizontal drag that starts on the pill or
 * the dots still pages (a tap still lands on them: the pager never consumes one).
 *
 * Per-page slots ride the pager with a parallax (x = 0.6·W·rel, so they move at 40% of the page) and fade by
 * 1 − |rel|·2.2; a neighbour's invisible controls take no taps and carry no semantics. Every per-frame value
 * (pager position, p) is read in layout or draw only.
 */

/**
 * Top bar geometry (prototype layoutFor). Layout constants, not motion tokens. The pill's start and the dots'
 * end move with the tier ([MemoriesBarInsets]: 16 / 12 phone, 24 / 18 Medium, 32 / 26 spread); the slots
 * and the diary's bar cover follow the pill.
 */
internal object MemoriesTopBarTokens {
    /** Under the status bar. */
    val Height: Dp = 64.dp
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

    /** Slot A's right edge stays 12dp clear of the first dot box. */
    val SlotClearOfDots: Dp = 12.dp

    /** Diary state: the 40dp cover after the pill (slot B 14dp after it, [MemoriesBarInsets]). */
    val BarCover: Dp = 40.dp
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
internal fun slotEndInset(dotCount: Int, insets: MemoriesBarInsets = MemoriesBarInsets.Phone): Dp =
    with(MemoriesTopBarTokens) {
        insets.dotsEnd + DotPitch * dotCount + DotOverhang + SlotClearOfDots
    }

/**
 * The shared bar: Home pill + dots. [diaryProgress] (the current page's cover flight fp, 0 card … 1 diary)
 * narrows the pill 88 → 36 in the layout phase and fades its label: the pill makes room for the bar cover,
 * so it follows the cover (which waits in the bar during a frozen collapse). [position] is the pager's continuous page. [modifier] should place it
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
    insets: MemoriesBarInsets = MemoriesBarInsets.Phone,
    currentDot: Int = -1,
) {
    Box(modifier = modifier.fillMaxWidth().height(MemoriesTopBarTokens.Height).shareTouchesWithSiblings()) {
        HomePill(
            diaryProgress = diaryProgress,
            onClick = onHome,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = insets.pillStart - MemoriesTopBarTokens.PillHitMargin),
        )
        PageDots(
            colors = dotColors,
            labels = dotLabels,
            position = position,
            onDot = onDot,
            current = currentDot,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = insets.dotsEnd - MemoriesTopBarTokens.DotOverhang)
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
                val pp = smoothstep(0f, MemoriesMorphTokens.PillNarrowTo, diaryProgress().coerceIn(0f, 1f))
                val pill = lerp(MemoriesTopBarTokens.PillWidth, MemoriesTopBarTokens.PillWidthDiary, pp)
                val w = (pill + MemoriesTopBarTokens.PillHitMargin * 2).roundToPx()
                val h = MemoriesTopBarTokens.HitHeight.roundToPx()
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(0, 0) }
            }
            // one node for TalkBack: "Home, button" (the chevron and the fading label are drawing only)
            .clearAndSetSemantics {
                contentDescription = "Home"
                role = Role.Button
                onClick(label = "Close Memories, back to Home") {
                    onClick()
                    true
                }
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
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
                    alpha = 1f - smoothstep(0f, MemoriesMorphTokens.PillLabelFadeTo, diaryProgress().coerceIn(0f, 1f))
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
    current: Int,
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
                        role = Role.Tab
                        contentDescription = label
                        selected = i == current
                        onClick(label = "Show this memory") {
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
 * from the morph's first frame. [morph] flies the bar cover with the card's ([MemoryPageMorph.barCover]);
 * without one (previews) it simply fades in. [reducedMotion]: alpha only, slot A gone by p .5 and slot B's
 * text from p .5. [settled]: the page is at rest (the marquees only run then, with the diary fully open).
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
    morph: MemoryPageMorph? = null,
    reducedMotion: Boolean = false,
    settled: () -> Boolean = { true },
    insets: MemoriesBarInsets = MemoriesBarInsets.Phone,
) {
    val slotEnd = slotEndInset(dotCount, insets)
    // The derived flags read the CURRENT readers: a reader captured by the first composition keeps reading a
    // dead controller once the host hands in a new one (a display-size change rebuilt p's state, and slot B —
    // the bar cover, the album, ⌄ — never appeared on that page again).
    val currentDiaryProgress by rememberUpdatedState(diaryProgress)
    val currentRelative by rememberUpdatedState(relative)
    val currentSettled by rememberUpdatedState(settled)
    val inDiaryMorph by remember { derivedStateOf { currentDiaryProgress() > 0.001f } }
    // a neighbour's controls (parked over this bar by the parallax, invisible) take no taps
    val onShow by remember { derivedStateOf { barVisibility(currentRelative()) >= 0.5f } }
    val diaryOpen by remember { derivedStateOf { currentDiaryProgress() >= 0.5f } }
    val marqueeRunning by remember { derivedStateOf { currentDiaryProgress() >= 1f && currentSettled() } }
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
                .padding(start = insets.slotAStart, end = slotEnd)
                // the page's heading while the card shows; gone with the diary (slot B speaks then)
                .clearAndSetSemantics {
                    if (shown && !diaryOpen) {
                        contentDescription = listOfNotNull("Memories", lastHeard).joinToString(", ")
                        heading()
                    }
                }
                .graphicsLayer {
                    val p = diaryProgress().coerceIn(0f, 1f)
                    if (reducedMotion) {
                        translationY = 0f
                        alpha = reducedCardAlpha(p)
                    } else {
                        translationY = -MemoriesMorphTokens.SlotALift.toPx() * p
                        alpha = 1f - smoothstep(0f, MemoriesMorphTokens.SlotAFadeTo, p)
                    }
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
            val tapToCard = if (interactive) Modifier.clickable(onClick = onCloseDiary) else Modifier
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = insets.barCoverStart - BarCoverHitMargin)
                    .size(MemoriesTopBarTokens.HitHeight)
                    // the same action as slot B beside it: one TalkBack stop, not two
                    .clearAndSetSemantics { }
                    .then(tapToCard),
                contentAlignment = Alignment.Center,
            ) {
                // no border, 4dp corners; in flight it is the card's cover's twin (same path, same size)
                val layer = if (morph != null) {
                    Modifier.graphicsLayer { with(morph) { barCover() } }
                } else {
                    Modifier
                        .graphicsLayer {
                            alpha = smoothstep(
                                MemoriesMorphTokens.CoverSwapFrom,
                                MemoriesMorphTokens.CoverSwapTo,
                                diaryProgress(),
                            )
                        }
                        .clip(YoinArtworkShapes.ThumbAnimated)
                }
                cover(Modifier.size(MemoriesTopBarTokens.BarCover).then(layer))
            }
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .padding(start = insets.slotBStart, end = slotEnd)
                    .clearAndSetSemantics {
                        if (interactive) {
                            contentDescription = "$album, $artistLine"
                            role = Role.Button
                            onClick(label = "Close the diary, back to the card") {
                                onCloseDiary()
                                true
                            }
                        }
                    }
                    .then(tapToCard)
                    .graphicsLayer {
                        if (reducedMotion) {
                            translationY = 0f
                            alpha = reducedDiaryAlpha(diaryProgress().coerceIn(0f, 1f))
                        } else {
                            val a = smoothstep(
                                MemoriesMorphTokens.SlotBFadeFrom,
                                MemoriesMorphTokens.SlotBFadeTo,
                                diaryProgress(),
                            )
                            translationY = (1f - a) * MemoriesMorphTokens.SlotBDrop.toPx()
                            alpha = a
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    MarqueeText(
                        text = album,
                        style = barTextStyle(15, FontWeight.SemiBold, lineHeight = 1.3f),
                        color = MaterialTheme.colorScheme.onSurface,
                        running = marqueeRunning,
                    )
                    BarArtistLine(full = artistLine, short = artistShort, running = marqueeRunning)
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
private fun BarArtistLine(full: String, short: String, running: Boolean) {
    val style = barTextStyle(12, FontWeight.Medium, lineHeight = 1.35f)
    BoxWithConstraints {
        val measurer = rememberTextMeasurer()
        val maxPx = constraints.maxWidth
        val text = remember(full, short, maxPx, style) {
            val fits = measurer.measure(full, style, softWrap = false, maxLines = 1).size.width <= maxPx
            if (fits) full else short
        }
        MarqueeText(text = text, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, running = running)
    }
}

/** The bar's album-name style (fallback A's title flies to it). */
@Composable
internal fun barTitleStyle(): TextStyle = barTextStyle(15, FontWeight.SemiBold, lineHeight = 1.3f)

/**
 * Lets a touch on the shared bar also reach its siblings beneath — the pager — so a horizontal drag that
 * starts on the pill or the dots still pages. Compose stops hit-testing siblings at the first one hit; this
 * node (it takes no events itself) asks it to go on.
 */
private fun Modifier.shareTouchesWithSiblings(): Modifier = this then ShareTouchesElement

private data object ShareTouchesElement : ModifierNodeElement<ShareTouchesNode>() {
    override fun create() = ShareTouchesNode()

    override fun update(node: ShareTouchesNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "shareTouchesWithSiblings"
    }
}

private class ShareTouchesNode : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) = Unit

    override fun onCancelPointerInput() = Unit

    override fun sharePointerInputWithSiblings(): Boolean = true
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
