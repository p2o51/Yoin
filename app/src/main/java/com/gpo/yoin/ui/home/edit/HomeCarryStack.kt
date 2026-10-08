package com.gpo.yoin.ui.home.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.cachedBackdropColors
import com.gpo.yoin.ui.home.HomeFeedFrame
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.titleRes
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

// ── Covers ────────────────────────────────────────────────────────────────

/** One strip cover: the section's own artwork, in its entity backdrop shape, with its palette base. */
@Immutable
internal data class StripCover(val model: Any?, val shape: Shape, val baseColor: Color?)

/**
 * A cover for [model], tinted by the palette its card already resolved
 * (critique 9). No new request: a miss leaves the strip its plate colour.
 */
internal fun stripCover(model: String?, shape: Shape): StripCover =
    StripCover(model, shape, model?.let(::cachedBackdropColors)?.baseColor)

/**
 * A strip's plate colour (port sheet §4.4, critique 8): the feed plate's
 * colour (the carried strip follows its lift tint, so the hand-off doesn't
 * snap), then [tint] of the way to the first cover's palette base.
 */
internal fun stripPlateColor(
    carried: Boolean,
    liftTint: Float,
    plate: Color,
    liftedPlate: Color,
    coverBase: Color?,
    tint: Float,
): Color {
    val base = if (carried) lerp(plate, liftedPlate, liftTint.coerceIn(0f, 1f)) else plate
    return if (coverBase != null && tint > HomeEditTokens.StripTintVisible) lerp(base, coverBase, tint) else base
}

// ── Overlay ───────────────────────────────────────────────────────────────

/**
 * The strip stack (port sheet §4.4, §4.10): while a carry runs, every
 * section is a strip flying between its feed plate and its slot. Put it in
 * the page Box (at its origin, inside the seam tide) above the feed; it
 * draws nothing and takes no touches outside a carry.
 *
 * In edit mode at rest the first [warmCount] labels of [sections] are
 * composed ahead of a carry, invisible (Home raises the count a label a
 * frame). Composed in the fold's first frame they would stall it for several
 * frames and eat the cross-fade. A carry composes whatever is missing and
 * otherwise changes no composition here: which strip is carried is read in
 * draw, layer and placement only.
 *
 * Z order (critique 5): the hole and the other plates, their labels, the
 * carried strip's shadow, its plate, its label. The strips never wiggle.
 */
@Composable
internal fun HomeCarryStack(
    engine: HomeCarryEngine,
    covers: (HomeSection) -> List<StripCover>,
    modifier: Modifier = Modifier,
    sections: List<HomeSection> = emptyList(),
    warmCount: () -> Int = { 0 },
) {
    // With no [sections] given (previews, tests), the carry's own.
    val ids = sections.ifEmpty { engine.session?.orig.orEmpty() }
    // All of them once a carry runs; equal values, so a fully warm stack recomposes nothing as it starts.
    val count by remember(engine, ids) {
        derivedStateOf { if (engine.session != null) ids.size else warmCount().coerceIn(0, ids.size) }
    }
    if (count <= 0) return
    // The strips at a carry's own size, else at the size they would have now
    // (only the slot top differs): equal values, so a carry starting recomposes nothing.
    val size by remember(engine, ids) {
        derivedStateOf { (engine.session?.metrics ?: engine.provisionalMetrics(ids.size))?.labelSize }
    }
    val labelSize = size ?: return
    val scheme = MaterialTheme.colorScheme
    val plate = scheme.surfaceContainerHigh
    val liftedPlate = scheme.surfaceContainerHighest
    val hole = scheme.secondaryContainer
    val holeRadius = with(LocalDensity.current) { HomeEditTokens.PlateRadius.toPx() }
    // One list per section for as long as [covers] holds: a new order must not hand the labels new lists.
    val coverCache = remember(covers) { HashMap<HomeSection, List<StripCover>>() }
    val coversOf = { section: HomeSection ->
        coverCache.getOrPut(section) { covers(section).take(HomeEditTokens.StripCoverCount) }
    }

    Box(
        modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .drawBehind {
                val live = engine.session ?: return@drawBehind
                val fold = engine.fold.value
                val holeA = HomeEditTokens.HoleAlpha * holeAlpha(fold)
                if (holeA > 0f) {
                    val rect = holeRect(live.metrics, engine.holeY.value)
                    drawRoundRect(hole, rect.topLeft, rect.size, CornerRadius(holeRadius), alpha = holeA)
                }
                for (section in live.ids) {
                    if (section == live.carried) continue
                    val frame = engine.frameFor(section) ?: continue
                    val base = coversOf(section).firstOrNull()?.baseColor
                    drawStripPlate(frame, stripPlateColor(false, 0f, plate, liftedPlate, base, frame.tint))
                }
            },
    ) {
        for (section in ids.take(count)) {
            key(section) {
                StripLabel(engine, section, labelSize, coversOf(section))
            }
        }
        StripShadow(engine)
        Spacer(
            Modifier
                .matchParentSize()
                .zIndex(CarriedPlateZ)
                .drawBehind {
                    val carried = engine.session?.carried ?: return@drawBehind
                    val frame = engine.frameFor(carried) ?: return@drawBehind
                    val colour = stripPlateColor(
                        carried = true,
                        liftTint = engine.liftTint.value,
                        plate = plate,
                        liftedPlate = liftedPlate,
                        coverBase = coversOf(carried).firstOrNull()?.baseColor,
                        tint = frame.tint,
                    )
                    drawStripPlate(frame, colour)
                },
        )
    }
}

// The carried strip's layers above the other labels (0): its shadow, its plate, its label.
private const val CarriedShadowZ = 1f
private const val CarriedPlateZ = 2f
private const val CarriedLabelZ = 3f

/** Whether the strip labels should be composed ahead of a carry ([HomeCarryStack]'s `warmCount`). */
internal enum class StripWarmth {
    /** Edit mode at rest: compose them, a few frames on. */
    Warm,

    /** Entering or exiting: leave them as they are. */
    Keep,

    /** Normal mode at rest: let them go. */
    Cold,
}

/** [StripWarmth] from the controller: [editing], and P [moving] (an entry or exit in flight). */
internal fun stripWarmth(editing: Boolean, moving: Boolean): StripWarmth = when {
    editing && !moving -> StripWarmth.Warm
    editing || moving -> StripWarmth.Keep
    else -> StripWarmth.Cold
}

/** Frames between edit mode settling and the strips composing: the entry's last work (the cards turning inert) goes first. */
internal const val StripWarmFrames = 3

/** A strip label's fixed size in px: the slot's width and height, and the cover edge. */
@Immutable
private data class StripLabelSize(val width: Float, val height: Float, val coverSize: Float)

private val StripMetrics.labelSize: StripLabelSize get() = StripLabelSize(width, height, coverSize)

private fun DrawScope.drawStripPlate(frame: StripFrame, colour: Color) {
    val alpha = frame.plateAlpha.coerceIn(0f, 1f)
    if (alpha <= 0f) return
    drawRoundRect(colour, frame.rect.topLeft, frame.rect.size, CornerRadius(frame.radius), alpha = alpha)
}

/**
 * Covers, title and handle at the slot's size (w × h_s, never
 * interpolated); only its position and alpha follow the strip, in the layer.
 */
@Composable
private fun StripLabel(
    engine: HomeCarryEngine,
    section: HomeSection,
    size: StripLabelSize,
    covers: List<StripCover>,
) {
    val coverSize = with(LocalDensity.current) { size.coverSize.toDp() }
    Row(
        modifier = Modifier
            .stripSized(size.width, size.height) { if (engine.session?.carried == section) CarriedLabelZ else 0f }
            .graphicsLayer {
                val frame = engine.frameFor(section)
                translationX = frame?.labelOffset?.x ?: 0f
                translationY = frame?.labelOffset?.y ?: 0f
                alpha = frame?.labelAlpha?.coerceIn(0f, 1f) ?: 0f
            }
            .padding(horizontal = HomeEditTokens.StripPadding),
        horizontalArrangement = Arrangement.spacedBy(HomeEditTokens.StripContentGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (covers.isNotEmpty()) StripCovers(covers, coverSize)
        Text(
            text = stringResource(section.titleRes),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = YoinSymbols.DragHandle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(HomeEditTokens.StripHandleSize),
        )
    }
}

/** Up to three covers, each overlapping the one before by 30%; the first on top. No borders. */
@Composable
private fun StripCovers(covers: List<StripCover>, size: Dp) {
    val fallback = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(horizontalArrangement = Arrangement.spacedBy(-size * HomeEditTokens.StripCoverOverlap)) {
        covers.forEachIndexed { index, cover ->
            Box(
                Modifier
                    .zIndex((covers.size - index).toFloat())
                    .size(size)
                    .clip(cover.shape)
                    .background(cover.baseColor ?: fallback),
            ) {
                if (cover.model != null) {
                    AsyncImage(
                        model = cover.model,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * The carried strip's shadow, lifting to 6dp as the strip lands. A
 * full-size layer whose outline is the strip's current rect, set in the
 * layer phase (critique 25's no-composition rule), so a carry relayouts
 * nothing frame by frame.
 */
@Composable
private fun BoxScope.StripShadow(engine: HomeCarryEngine) {
    Box(
        Modifier
            .matchParentSize()
            .zIndex(CarriedShadowZ)
            .graphicsLayer {
                val frame = engine.session?.carried?.let(engine::frameFor)
                if (frame == null) {
                    shadowElevation = 0f
                    shape = RectangleShape
                } else {
                    shadowElevation = HomeEditTokens.LiftShadow.toPx() * frame.shadowAlpha
                    shape = StripRectShape(frame.rect, frame.radius)
                }
                clip = false
            },
    )
}

/** A rounded rect inside a full-size layer: the carried strip's shadow outline. */
private class StripRectShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

/**
 * A fixed px size at the parent's origin, whatever the parent's alignment; the
 * layer moves it. [zIndex] is read as it is placed, so the carried label
 * rises above the others without recomposing.
 */
private fun Modifier.stripSized(width: Float, height: Float, zIndex: () -> Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(
        Constraints.fixed(width.roundToInt().coerceAtLeast(0), height.roundToInt().coerceAtLeast(0)),
    )
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0, zIndex()) }
}

// ── Host ──────────────────────────────────────────────────────────────────

/**
 * [CarryHost] over Home's LazyColumn, which sits at the page Box's origin.
 * The order is the draft's ([order], critique 1); plates come from
 * `layoutInfo` and the feed frame; [emittedKeys] is the item key list Home
 * emits, in order (published in a `SideEffect`), which places the dropped
 * item after the reorder (critique 17).
 */
@Stable
internal class LazyListCarryHost(
    private val listState: LazyListState,
    private val feedFrame: HomeFeedFrame,
    private val order: () -> List<HomeSection>,
    private val emittedKeys: () -> List<Any>,
    private val safe: () -> HomeEditSafeArea,
    private val plateOutsetPx: () -> Size,
    private val density: Density,
) : CarryHost {
    // Last laid-out height per item key: the anchor's clamp needs the blocks above the dropped one.
    private val heights = HashMap<Any, Int>()

    /** Records item heights as they lay out; [rememberLazyListCarryHost] runs it while Home is composed. */
    suspend fun trackHeights() {
        snapshotFlow { listState.layoutInfo }.collect { record(it) }
    }

    override fun displayedOrder(): List<HomeSection> = order()

    override fun plateRect(section: HomeSection): Rect? {
        val info = listState.layoutInfo
        val key = carryItemKey(section)
        val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return null
        val top = (item.offset - info.viewportStartOffset).toFloat()
        val outset = plateOutsetPx()
        val left = contentLeft()
        return Rect(
            left = left - outset.width,
            top = top - outset.height,
            right = left + contentWidth() + outset.width,
            bottom = top + item.size + outset.height,
        )
    }

    override fun isAbove(section: HomeSection): Boolean = carryIsAbove(
        section = section,
        order = order(),
        visible = listState.layoutInfo.visibleItemsInfo.mapNotNull { carryKeySection(it.key) },
        pastHeader = listState.firstVisibleItemIndex > 0,
    )

    override fun safeArea(): HomeEditSafeArea = safe()

    override fun contentLeft(): Float = with(density) { feedFrame.start.toPx() }

    override fun contentWidth(): Float = with(density) { feedFrame.contentWidth.toPx() }

    /**
     * One scroll puts the dropped plate's top on its slot (port sheet §4.9),
     * unless the header still shows below the status band. The request is
     * made right after the commit, so the next measure already has the new
     * item order; then this waits for that layout (at most a few frames).
     */
    override suspend fun anchorAndAwaitLayout(dropped: HomeSection, order: List<HomeSection>, slotTop: Float) {
        val before = listState.layoutInfo
        record(before)
        val statusBand = safe().top - with(density) { HomeEditTokens.SafeGap.toPx() }
        val anchor = carryAnchor(
            emittedKeys = emittedKeys(),
            order = order,
            dropped = dropped,
            slotTop = slotTop,
            plateOutsetV = plateOutsetPx().height,
            viewportStartOffset = before.viewportStartOffset,
            heightOf = heights::get,
            spacingPx = before.mainAxisItemSpacing,
            headerVisible = carryHeaderVisible(before.headerBottomInBox(), statusBand),
        )
        if (anchor == null && carryOrderFollows(before.visibleSections(), order)) return
        anchor?.let { listState.requestScrollToItem(it.index, it.offset) }
        coroutineScope {
            val laidOut = async {
                snapshotFlow { listState.layoutInfo }
                    .first { it !== before && carryOrderFollows(it.visibleSections(), order) }
            }
            val timeout = async { repeat(HomeEditTokens.AnchorFrameLimit) { withFrameNanos { } } }
            select {
                laidOut.onAwait { }
                timeout.onAwait { }
            }
            coroutineContext.cancelChildren()
        }
    }

    private fun record(info: LazyListLayoutInfo) {
        info.visibleItemsInfo.forEach { heights[it.key] = it.size }
    }

    private fun LazyListLayoutInfo.visibleSections(): List<HomeSection> =
        visibleItemsInfo.mapNotNull { carryKeySection(it.key) }

    private fun LazyListLayoutInfo.headerBottomInBox(): Float? =
        visibleItemsInfo.firstOrNull { it.index == 0 }?.let { (it.offset - viewportStartOffset + it.size).toFloat() }
}

/** A [LazyListCarryHost] that keeps its height cache while Home is composed. Hand it to the engine as `host`. */
@Composable
internal fun rememberLazyListCarryHost(
    listState: LazyListState,
    feedFrame: HomeFeedFrame,
    order: () -> List<HomeSection>,
    emittedKeys: () -> List<Any>,
    safeArea: () -> HomeEditSafeArea,
    plateOutsetPx: () -> Size,
): LazyListCarryHost {
    val density = LocalDensity.current
    val currentOrder by rememberUpdatedState(order)
    val currentKeys by rememberUpdatedState(emittedKeys)
    val currentSafe by rememberUpdatedState(safeArea)
    val currentOutset by rememberUpdatedState(plateOutsetPx)
    val host = remember(listState, feedFrame, density) {
        LazyListCarryHost(
            listState = listState,
            feedFrame = feedFrame,
            order = { currentOrder() },
            emittedKeys = { currentKeys() },
            safe = { currentSafe() },
            plateOutsetPx = { currentOutset() },
            density = density,
        )
    }
    LaunchedEffect(host) { host.trackHeights() }
    return host
}

// ── Anchor and order (pure) ───────────────────────────────────────────────

/** The feed item key of [section]'s block. */
internal fun carryItemKey(section: HomeSection): String = CarryKeyPrefix + section.id

/** The section a feed item key belongs to, or null for the header, tray and footer. */
internal fun carryKeySection(key: Any): HomeSection? {
    val id = (key as? String)?.takeIf { it.startsWith(CarryKeyPrefix) }?.removePrefix(CarryKeyPrefix) ?: return null
    return HomeSection.fromId(id)
}

/** The anchor scroll: item [index] [offset] px past the viewport start (negative: below it). */
@Immutable
internal data class CarryAnchor(val index: Int, val offset: Int)

/**
 * The anchor that puts [dropped]'s plate top on [slotTop] (Box px) once
 * [order] is committed, or null to leave the scroll alone: the header shows,
 * the dropped item isn't emitted, or a height the clamp needs is unknown.
 * [emittedKeys] may still be the order before the commit. The list sits at
 * the Box origin; the clamp keeps the header out.
 */
internal fun carryAnchor(
    emittedKeys: List<Any>,
    order: List<HomeSection>,
    dropped: HomeSection,
    slotTop: Float,
    plateOutsetV: Float,
    viewportStartOffset: Int,
    heightOf: (Any) -> Int?,
    spacingPx: Int,
    headerVisible: Boolean,
): CarryAnchor? {
    if (headerVisible) return null
    val keys = carryReorderedKeys(emittedKeys, order)
    val index = keys.indexOf(carryItemKey(dropped))
    if (index < 1) return null
    val offset = anchorScrollOffset(
        droppedIndex = index,
        // The item's top in the list's offset space (Box px minus the top padding).
        desiredTop = slotTop + plateOutsetV + viewportStartOffset,
        heightsAbove = keys.subList(1, index).map(heightOf),
        spacingPx = spacingPx,
        headerVisible = false,
    ) ?: return null
    return CarryAnchor(index, offset)
}

/**
 * [keys] with the blocks of [order] refilled into their own positions in
 * that order; every other item (header, a block fading out, the tray) keeps
 * its index, as disabled sections keep theirs in the layout.
 */
internal fun carryReorderedKeys(keys: List<Any>, order: List<HomeSection>): List<Any> {
    val present = keys.toSet()
    val wanted = order.map(::carryItemKey).filter { it in present }
    val wantedSet = wanted.toSet()
    val slots = keys.indices.filter { keys[it] in wantedSet }
    if (slots.size != wanted.size) return keys
    val result = keys.toMutableList()
    slots.forEachIndexed { i, slot -> result[slot] = wanted[i] }
    return result
}

/** The visible blocks of [order] appear in that order (other sections ignored). */
internal fun carryOrderFollows(visible: List<HomeSection>, order: List<HomeSection>): Boolean {
    val indices = visible.map(order::indexOf).filter { it >= 0 }
    return indices.zipWithNext().all { (a, b) -> a < b }
}

/**
 * The header still shows below the status band (critique 34): the anchor
 * leaves the scroll alone. Fully under the band it counts as gone.
 */
internal fun carryHeaderVisible(headerBottomInBox: Float?, statusBandBottom: Float): Boolean =
    headerBottomInBox != null && headerBottomInBox > statusBandBottom

/**
 * The side an unlaid-out section of [order] comes from: above unless it
 * follows every visible block. A block held back above the lifted one
 * (between visible blocks) therefore comes from above, as it would in the
 * feed. With no block visible, [pastHeader] decides.
 */
internal fun carryIsAbove(
    section: HomeSection,
    order: List<HomeSection>,
    visible: List<HomeSection>,
    pastHeader: Boolean,
): Boolean {
    val last = visible.maxOfOrNull(order::indexOf) ?: -1
    return if (last >= 0) order.indexOf(section) < last else pastHeader
}

private const val CarryKeyPrefix = "section-"

// ── Previews ──────────────────────────────────────────────────────────────

// Frames on demand: every animation in a preview engine lands at once.
private object PreviewFrameClock : MonotonicFrameClock {
    private var nanos = 0L

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        nanos += PreviewFrameNanos
        return onFrame(nanos)
    }
}

private const val PreviewFrameNanos = 16_000_000L

private class PreviewCarryHost(private val density: Density) : CarryHost {
    private val order = listOf(
        HomeSection.Activities,
        HomeSection.JumpBackIn,
        HomeSection.RecentlyAdded,
        HomeSection.Rediscover,
    )

    override fun displayedOrder(): List<HomeSection> = order

    override fun plateRect(section: HomeSection): Rect? = with(density) {
        when (section) {
            HomeSection.Activities -> Rect(8.dp.toPx(), 60.dp.toPx(), 404.dp.toPx(), 420.dp.toPx())
            HomeSection.JumpBackIn -> Rect(8.dp.toPx(), 432.dp.toPx(), 404.dp.toPx(), 780.dp.toPx())
            else -> null
        }
    }

    override fun isAbove(section: HomeSection): Boolean = false

    override fun safeArea(): HomeEditSafeArea = with(density) { HomeEditSafeArea(44.dp.toPx(), 700.dp.toPx()) }

    override fun contentLeft(): Float = with(density) { 16.dp.toPx() }

    override fun contentWidth(): Float = with(density) { 380.dp.toPx() }

    override suspend fun anchorAndAwaitLayout(dropped: HomeSection, order: List<HomeSection>, slotTop: Float) = Unit
}

private fun previewCovers(section: HomeSection, bun: Shape): List<StripCover> = when (section) {
    HomeSection.Activities -> listOf(
        StripCover(null, bun, Color(0xFF7A5AA6)),
        StripCover(null, CircleShape, Color(0xFF3F6C8C)),
        StripCover(null, bun, Color(0xFFB0605A)),
    )
    HomeSection.JumpBackIn -> listOf(StripCover(null, bun, Color(0xFF4F7F5B)), StripCover(null, CircleShape, null))
    HomeSection.RecentlyAdded -> listOf(StripCover(null, bun, Color(0xFFC08A3E)))
    HomeSection.Rediscover -> emptyList()
    HomeSection.RecentlyPlayed -> listOf(StripCover(null, bun, Color(0xFF8A5A9E)))
    HomeSection.YourPlaylists -> listOf(StripCover(null, bun, Color(0xFF5A6FA6)))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeCarryStackPreviewFrame(foldAt: Float) {
    YoinTheme {
        val bun = MaterialShapes.Bun.toShape()
        val density = LocalDensity.current
        val specs = rememberHomeEditSpecs(reduced = false)
        val engine = remember(density, specs, foldAt) {
            val scope = CoroutineScope(Dispatchers.Unconfined + PreviewFrameClock)
            val motion = HomeEditMotion(
                scope = scope,
                specs = specs,
                style = HomeWiggleStyle(),
                reduced = { false },
                progress = { 1f },
                editing = { true },
                feedback = HomeEditFeedback.None,
                uptimeMs = { 0L },
            )
            HomeCarryEngine(
                scope = scope,
                specs = specs,
                motion = motion,
                feedback = HomeEditFeedback.None,
                density = density,
                commitOrder = { false },
                finishDeferredExit = {},
                uptimeMs = { 0L },
            ).apply {
                host = PreviewCarryHost(density)
                liftBlock(HomeSection.JumpBackIn, Offset.Zero)
                start(fingerY = with(density) { 300.dp.toPx() }, uptimeMs = 0L)
                scope.launchNow { fold.snapTo(foldAt) }
            }
        }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
            HomeCarryStack(engine, covers = { previewCovers(it, bun) })
        }
    }
}

@Preview(name = "Strips · folded", widthDp = 412, heightDp = 760)
@Composable
private fun HomeCarryStackFoldedPreview() {
    HomeCarryStackPreviewFrame(foldAt = 1f)
}

@Preview(
    name = "Strips · folded, dark",
    widthDp = 412,
    heightDp = 760,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun HomeCarryStackDarkPreview() {
    HomeCarryStackPreviewFrame(foldAt = 1f)
}

@Preview(name = "Strips · mid fold", widthDp = 412, heightDp = 760)
@Composable
private fun HomeCarryStackMidFoldPreview() {
    HomeCarryStackPreviewFrame(foldAt = .5f)
}
