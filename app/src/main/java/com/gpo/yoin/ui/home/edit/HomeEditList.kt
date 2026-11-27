package com.gpo.yoin.ui.home.edit

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.util.lerp
import com.gpo.yoin.ui.experience.smoothstep
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.cachedBackdropColors
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.shared.home.DragItemBand
import com.gpo.yoin.shared.home.HomeFeedRow
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.titleRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

// Home edit mode's compact list (owner 2026-11-22): a two-finger pinch in
// folds the feed into one strip per section (two columns: two half strips
// per pair, as the feed pairs them), a pinch out unfolds it. The fold is a
// morph, not a crossfade (owner 2026-11-27): each block's plate shrinks into
// its strip from where the block is on screen, top to bottom one after
// another, while the block's cards sink and fade under it and the strip's
// covers and title come up inside; the way back runs the same path in
// reverse (homeListMorph). Reduced motion keeps the plain fade. The strips are
// reordered by dragging, as the feed's blocks are (HomeDragEngine), and a
// finger near the top or bottom scrolls the list. Hide works on each strip;
// the hidden ones and Reset follow under the strips, as in the feed.

/** Numbers of the list and its pinch. */
internal object HomeEditListTokens {
    /** A pinch to this fraction of the fingers' starting span takes the feed all the way into the list. */
    const val PinchInFull = com.gpo.yoin.shared.home.HomeListPinchRules.PinchInFull

    /** A pinch out to this multiple of the starting span brings the feed all the way back. */
    const val PinchOutFull = com.gpo.yoin.shared.home.HomeListPinchRules.PinchOutFull

    /** Let go this far of the way (or further) and the change finishes; short of it, it springs back. */
    const val PinchCommit = com.gpo.yoin.shared.home.HomeListPinchRules.PinchCommit

    /**
     * The fold's stagger: a block at the bottom of the screen starts this far into the progress after
     * one at the top (lanes in between, in proportion), so the blocks fold one after another.
     */
    const val MorphSpread = .32f

    /** A block's cards are gone by this much of its own morph… */
    const val MorphFeedOut = .5f

    /** …and sink to this scale on the way. */
    const val MorphFeedScale = .94f

    /** The strip's plate is fully there by this much of its morph… */
    const val MorphPlateIn = .22f

    /** …its covers, title and buttons come in from this much… */
    const val MorphContentIn = .45f

    /** …growing from this scale. */
    const val MorphContentScale = .9f

    /** The spring's overshoot past the list carries on into the strips' plates, this many times over. */
    const val MorphOvershootGain = 3f

    /** A strip whose block is off screen rises from this many strip heights below its place. */
    const val MorphOffscreenRise = .75f

    val RowHeight = 64.dp

    /** The row over the strips that holds the switch back to the full layout. */
    val HeaderHeight = 48.dp
    val RowGap = 8.dp
    val HideButtonSize = 32.dp
    val HideIconSize = 18.dp
    val HandleTouch = 40.dp

    /** The handle's touch box brings its own room at the strip's end. */
    val StripEndPadding = 4.dp
}

/** The list's progress for a pinch at [scale] begun [fromOn] ([com.gpo.yoin.shared.home.homeListPinchProgress]). Pure. */
internal fun homeListPinchProgress(fromOn: Boolean, scale: Float): Float = com.gpo.yoin.shared.home.homeListPinchProgress(fromOn, scale)

/**
 * One block's own fold at the list's [progress] (0 feed … 1 list; the spring may overshoot either end),
 * the block at [lane] (0 the top of the scroll area … 1 its bottom): it starts
 * [HomeEditListTokens.MorphSpread] × [lane] in, and runs the rest of the way in step with the others.
 * 0 = the block, 1 = the strip; past 1 only while the spring overshoots the list. Pure.
 */
internal fun homeListMorph(progress: Float, lane: Float): Float {
    val spread = HomeEditListTokens.MorphSpread
    val delay = lane.coerceIn(0f, 1f) * spread
    val own = ((progress - delay) / (1f - spread)).coerceIn(0f, 1f)
    return own + (progress - 1f).coerceAtLeast(0f) * HomeEditListTokens.MorphOvershootGain
}

/** Where [top] (px in the feed's Box) falls down the scroll area [area]: 0 its top … 1 its bottom. Pure. */
internal fun homeListLane(top: Float, area: HomeEditSafeArea): Float {
    val height = area.bottom - area.top
    return if (height <= 0f) 0f else ((top - area.top) / height).coerceIn(0f, 1f)
}

/**
 * A feed block's own fold (0 the block … 1 gone into its strip) while the list comes or goes, its row's top at
 * [rowTop] in the feed's Box (null: lane 0); 0 while the list is off or under [reduced] motion. Layer only:
 * it reads the list's progress only while the list shows, so the blocks' layers stay still otherwise.
 */
internal fun HomeDragEngine.feedFold(rowTop: Float?, reduced: Boolean): Float {
    if (reduced || !list.shown) return 0f
    val p = list.progress.value
    val area = host?.scrollArea() ?: return p.coerceIn(0f, 1f)
    return homeListMorph(p, rowTop?.let { homeListLane(it, area) } ?: 0f).coerceIn(0f, 1f)
}

/** A strip's plate on its way between its block ([e] 0) and itself ([e] 1), in the strip's own px. */
@Immutable
internal data class StripMorph(val e: Float, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * Where [rowFirst]'s block is in the feed for the fold: its laid-out band, or, off screen, whether it is
 * below what the feed shows (true) or above it.
 */
private class FeedPlace(val band: DragItemBand?, val below: Boolean)

private fun HomeDragEngine.feedPlace(rowFirst: HomeSection): FeedPlace? {
    val feed = host ?: return null
    feed.rowBand(rowFirst)?.let { return FeedPlace(it, below = false) }
    val order = feed.order()
    val shown = feed.visibleRows().map { order.indexOf(it.key) }.filter { it >= 0 }
    val index = order.indexOf(rowFirst)
    return FeedPlace(null, below = shown.isEmpty() || index > shown.min())
}

/**
 * [rowFirst]'s own fold at this frame ([homeListMorph] at its block's lane: a block off screen below the
 * feed's view folds last, one above it first); 1 while the list rests or is off.
 */
private fun HomeDragEngine.stripFold(rowFirst: HomeSection, place: FeedPlace?): Float {
    if (!list.shown) return 1f
    val p = list.progress.value
    if (p == 1f) return 1f
    val area = host?.scrollArea()
    val lane = when {
        place?.band != null && area != null -> homeListLane(place.band.top, area)
        place?.below == false -> 0f
        else -> 1f
    }
    return homeListMorph(p, lane)
}

/**
 * The strips' draw order through the fold: the one still furthest from its place on top, so a strip
 * travelling up from a block lower on the page passes over the ones already landed rather than under
 * them. 0 at rest. Placement only.
 */
internal fun HomeDragEngine.stripFoldZ(rowFirst: HomeSection): Float {
    if (!list.shown || list.progress.value == 1f) return 0f
    return (1f - stripFold(rowFirst, feedPlace(rowFirst))).coerceIn(0f, .99f)
}

/**
 * The strip of [rowFirst]'s row ([width] × [height]) at this frame of the fold: its plate from the feed
 * block's plate (the block's row, grown by [outsetH] / [outsetV]) to its own bounds, by [homeListMorph] at
 * the block's lane. A block off screen: the strip comes from below (or above) the view. At rest in the
 * list: its own bounds.
 */
internal fun HomeDragEngine.stripMorph(
    rowFirst: HomeSection,
    width: Float,
    height: Float,
    outsetH: Float,
    outsetV: Float,
): StripMorph {
    val rest = StripMorph(1f, 0f, 0f, width, height)
    if (!list.shown || list.progress.value == 1f) return rest
    val own = listHost?.rowBand(rowFirst) ?: return rest
    val place = feedPlace(rowFirst)
    val e = stripFold(rowFirst, place)
    val feed = place?.band
    val rise = height * HomeEditListTokens.MorphOffscreenRise
    val fromTop = when {
        feed != null -> feed.top - own.top - outsetV
        place?.below == false -> -rise - height
        else -> rise
    }
    val fromBottom = if (feed != null) feed.bottom - own.top + outsetV else fromTop + height
    return StripMorph(
        e = e,
        left = lerp(-outsetH, 0f, e),
        top = lerp(fromTop, 0f, e),
        right = lerp(width + outsetH, width, e),
        bottom = lerp(fromBottom, height, e),
    )
}

/** A pinch begun [fromOn] let go at [progress] lands in the list (true) or the feed. Pure. */
internal fun homeListSettlesOn(fromOn: Boolean, progress: Float): Boolean = com.gpo.yoin.shared.home.homeListSettlesOn(fromOn, progress)

/**
 * Feed ⇄ list: [progress] follows the pinch, then springs to where the
 * pinch lands ([on]). Draw and layer read [progress]; the hit test reads [on].
 */
@Stable
internal class HomeEditListMode(
    private val scope: CoroutineScope,
    private val specs: HomeEditSpecs,
    private val feedback: HomeEditFeedback,
) {
    /** 0: the feed; 1: the list over it. Layer and draw only. */
    val progress = Animatable(0f)

    /** Where the list is going: shown (a pinch in landed) or not. Presses go to the list while on. */
    var on: Boolean by mutableStateOf(false)
        private set

    private val shownState = derivedStateOf { on || progress.value > 0f }

    /** The list is composed: on, or on its way in or out. */
    val shown: Boolean get() = shownState.value

    private val coversState = derivedStateOf { progress.value >= 1f }

    /** The list fully covers the feed. */
    val covers: Boolean get() = coversState.value

    private var job: Job? = null
    private var pinching = false
    private var pinchFrom = false
    private var armed = false

    /** Two fingers went down in edit mode. */
    fun pinchStart() {
        job?.cancel()
        pinching = true
        pinchFrom = on
        armed = false
    }

    /** The fingers are at [scale] of their starting span: the list follows them, a tick where letting go would land. */
    fun pinchBy(scale: Float) {
        if (!pinching) return
        val p = homeListPinchProgress(pinchFrom, scale)
        val lands = homeListSettlesOn(pinchFrom, p) != pinchFrom
        if (lands != armed) {
            armed = lands
            feedback.segmentTick()
        }
        job?.cancel()
        job = scope.launchNow { progress.snapTo(p) }
    }

    /** A finger lifted: the list lands where the pinch left it. */
    fun pinchEnd() {
        if (!pinching) return
        pinching = false
        set(homeListSettlesOn(pinchFrom, progress.value))
    }

    /** Springs to the list ([value]) or the feed. */
    fun set(value: Boolean) {
        on = value
        job?.cancel()
        job = scope.launchNow { progress.animateTo(if (value) 1f else 0f, specs.fold) }
    }

    /** The edit header's switch (one hand): list ⇄ full layout, staying in edit mode. Ignored mid-pinch. */
    fun toggle() {
        if (pinching) return
        feedback.click()
        set(!on)
    }

    /** Edit mode is ending: back to the feed alongside it. */
    fun close() {
        pinching = false
        if (on || progress.value > 0f) set(false)
    }

    /** Back to the feed now. */
    fun snapOff() {
        pinching = false
        on = false
        job?.cancel()
        job = scope.launchNow { progress.snapTo(0f) }
    }
}

// ── The switch ────────────────────────────────────────────────────────────

/**
 * The edit header's one-hand switch between the full layout and the list
 * (the pinch stays the main way): it shows where a tap goes, the list over
 * the full layout and the cards over the list. A press on it is never an
 * edit gesture ([targets]); [enabled] gates taps, focus and that exclusion.
 */
@Composable
internal fun HomeEditViewToggle(
    engine: HomeDragEngine,
    targets: HomeEditTargets,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val listOn = engine.list.on
    val currentEnabled by rememberUpdatedState(enabled)
    IconButton(
        onClick = engine::toggleList,
        enabled = enabled,
        modifier = modifier
            .homeEditExclusion(targets, ViewToggleKey) { currentEnabled }
            .handPointer(enabled = enabled),
    ) {
        Icon(
            imageVector = if (listOn) YoinSymbols.ViewCards else YoinSymbols.ViewList,
            contentDescription = stringResource(if (listOn) R.string.home_edit_view_full else R.string.home_edit_view_list),
        )
    }
}

private data object ViewToggleKey

// ── Covers ────────────────────────────────────────────────────────────────

/** One strip cover: the section's own artwork, in its entity backdrop shape, with its palette base. */
@Immutable
internal data class StripCover(val model: Any?, val shape: Shape, val baseColor: Color?)

/**
 * A cover for [model], tinted by the palette its card already resolved.
 * No new request: a miss leaves the strip its plate colour.
 */
internal fun stripCover(model: String?, shape: Shape): StripCover =
    StripCover(model, shape, model?.let(::cachedBackdropColors)?.baseColor)

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

// ── The list ──────────────────────────────────────────────────────────────

/**
 * The compact list over the feed while [HomeEditListMode.shown]: the page's
 * own background ([background], its gradient stops) fading in over the feed,
 * then one strip per section in [rows], the [hidden] tray and Reset. Put it
 * in the page Box at its origin, above the feed; it composes nothing while
 * the list is off. Presses reach it through Home's one detector: [targets]
 * holds its handles and buttons, [listState] its rows.
 */
@Composable
internal fun HomeEditList(
    deps: HomeEditDeps,
    listState: LazyListState,
    rows: List<HomeFeedRow>,
    rowKeys: HomeRowKeys,
    hidden: List<HomeSection>,
    newBadges: Set<HomeSection>,
    canReset: Boolean,
    covers: (HomeSection) -> List<StripCover>,
    targets: HomeEditTargets,
    background: List<Color>,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    listModifier: Modifier = Modifier,
) {
    val engine = deps.engine
    val list = engine.list
    if (!list.shown) return
    val specs = deps.specs
    val reduced = specs.reduced
    // One list per section for as long as [covers] holds: a new order must not hand the strips new lists.
    val coverCache = remember(covers) { HashMap<HomeSection, List<StripCover>>() }
    val coversOf = { section: HomeSection ->
        coverCache.getOrPut(section) { covers(section).take(HomeEditTokens.StripCoverCount) }
    }
    val order = rows.flatMap { it.sections() }
    Box(
        modifier
            .fillMaxSize()
            // Its own layer: the wash re-records here alone as the list comes and goes.
            .graphicsLayer {}
            .drawWithCache {
                // One brush per size and page colours, not one per frame of the list's way in.
                val brush = if (background.size > 1) {
                    Brush.verticalGradient(background, startY = 0f, endY = size.height)
                } else {
                    Brush.verticalGradient(listOf(background.first(), background.first()))
                }
                onDrawBehind {
                    val p = list.progress.value.coerceIn(0f, 1f)
                    if (p > 0f) drawRect(brush, alpha = p)
                }
            },
    ) {
        LazyColumn(
            state = listState,
            modifier = listModifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = list.progress.value.coerceIn(0f, 1f)
                    // Reduced motion: the list only fades. Otherwise each strip morphs out of its block
                    // (HomeEditListStrip); the header and the tray come in quickly around them.
                    alpha = if (reduced) p else smoothstep(0f, HomeEditListTokens.MorphPlateIn * 1.5f, p)
                },
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(HomeEditListTokens.RowGap),
        ) {
            // The header's switch, back to the full layout with one hand.
            item(key = HomeEditListHeaderKey) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = HomeEditListTokens.HeaderHeight),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    HomeEditViewToggle(engine, targets, enabled = list.on && deps.controller.isEditing)
                }
            }
            for (row in rows) {
                item(key = rowKeys.itemKey(row.first)) {
                    // The dragged row places at once (its strip follows the finger); the others make way.
                    // No spec for it, not a snap: a snap lands a frame late (the strip a row off for a frame).
                    val placement = remember(row, specs) { GatedPlacementSpec(specs.placement) { engine.carries(row) } }
                    val carried by remember(row, engine) { derivedStateOf { engine.carries(row) } }
                    // Mid-drag a row the drag splits or forms comes and goes at once: a fading one
                    // drew the dragged strip a second time where it was, a fading-in one hid it.
                    val dragOn by remember(engine) { derivedStateOf { engine.dragging != null } }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(
                                fadeInSpec = if (dragOn) null else specs.effectsIn,
                                placementSpec = if (carried) null else placement,
                                fadeOutSpec = if (dragOn) null else specs.effectsOut,
                            )
                            // The carried strip over every row, from its lift until it has landed; through
                            // the fold, the strips still on their way over the ones landed.
                            .dragZIndex {
                                when {
                                    engine.drawsOnTop(row) -> 1f
                                    reduced -> 0f
                                    else -> engine.stripFoldZ(row.first)
                                }
                            },
                        horizontalArrangement = Arrangement.spacedBy(HomeSpanTokens.ColumnGap),
                    ) {
                        row.sections().forEach { section ->
                            key(section) {
                                HomeEditListStrip(
                                    section = section,
                                    rowFirst = row.first,
                                    index = order.indexOf(section),
                                    count = order.size,
                                    deps = deps,
                                    covers = coversOf(section),
                                    targets = targets,
                                    modifier = Modifier
                                        .weight(1f)
                                        .dragZIndex { if (engine.drawsOnTop(section)) 1f else 0f },
                                )
                            }
                        }
                    }
                }
            }
            homeEditTrayItems(
                hidden = hidden,
                newBadges = newBadges,
                canReset = canReset,
                deps = deps,
                placementSpec = { specs.placement },
                itemSpacing = HomeEditListTokens.RowGap,
                targets = targets,
                inList = true,
            )
        }
    }
}

/**
 * One section as a strip: its covers, its title, Hide and the drag handle.
 * It follows the finger in its layer while dragged and lifts as the feed's
 * blocks do (scale, shadow, plate tint).
 */
@Composable
private fun HomeEditListStrip(
    section: HomeSection,
    rowFirst: HomeSection,
    index: Int,
    count: Int,
    deps: HomeEditDeps,
    covers: List<StripCover>,
    targets: HomeEditTargets,
    modifier: Modifier = Modifier,
) {
    val engine = deps.engine
    val controller = deps.controller
    val reduced = deps.specs.reduced
    val colors = MaterialTheme.colorScheme
    val plate = colors.surfaceContainerHigh
    val density = LocalDensity.current
    val outsetH = { with(density) { HomeEditTokens.PlateOutsetH.toPx() } }
    val outsetV = { with(density) { HomeEditTokens.PlateOutsetVMax.toPx() } }
    val liftedPlate = colors.surfaceContainerHighest
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val height = HomeEditListTokens.RowHeight * fontScale
    val coverSize = (HomeEditListTokens.RowHeight - HomeEditTokens.StripCoverInset)
        .coerceIn(HomeEditTokens.StripCoverMin, HomeEditTokens.StripCoverMax)
    val shape = remember { RoundedCornerShape(HomeEditTokens.PlateRadius) }
    val title = stringResource(section.titleRes)
    val moveUp = stringResource(R.string.home_edit_move_up)
    val moveDown = stringResource(R.string.home_edit_move_down)
    val hide = stringResource(R.string.home_edit_hide)
    val position = stringResource(R.string.home_cd_section_index, index + 1, count)
    Row(
        modifier = modifier
            .height(height)
            // Where it lands while it follows the finger (rule 4).
            .dragSlotMark(engine, section, inList = true, fill = liftedPlate, stroke = colors.outlineVariant)
            // Dragged, it follows the finger, placed against this frame's layout; dropped, it glides home.
            .dragFollow(engine, section, inList = true, box = targets::boxCoordinates)
            .graphicsLayer {
                val lift = if (engine.liftSection == section) engine.lift.value.coerceIn(0f, 1f) else 0f
                val scale = liftScale(lift)
                scaleX = scale
                scaleY = scale
                shadowElevation = HomeEditTokens.LiftShadow.toPx() * lift
                this.shape = shape
                clip = false
            }
            // The plate: at rest the strip's own; through the fold, the block's plate shrinking into it.
            .drawBehind {
                val tint = if (engine.liftSection == section) engine.liftTint.value.coerceIn(0f, 1f) else 0f
                val color = lerp(plate, liftedPlate, tint)
                val radius = CornerRadius(HomeEditTokens.PlateRadius.toPx())
                if (reduced) {
                    drawRoundRect(color, cornerRadius = radius)
                    return@drawBehind
                }
                val m = engine.stripMorph(rowFirst, size.width, size.height, outsetH(), outsetV())
                drawRoundRect(
                    color = color,
                    topLeft = Offset(m.left, m.top),
                    size = Size((m.right - m.left).coerceAtLeast(0f), (m.bottom - m.top).coerceAtLeast(0f)),
                    cornerRadius = radius,
                    alpha = smoothstep(0f, HomeEditListTokens.MorphPlateIn, m.e),
                )
            }
            // The covers, title and buttons ride in the plate's middle and come up late in the morph.
            .graphicsLayer {
                if (reduced) return@graphicsLayer
                val m = engine.stripMorph(rowFirst, size.width, size.height, outsetH(), outsetV())
                if (m.e >= 1f && m.top == 0f) return@graphicsLayer
                translationY = m.centerY - size.height / 2f
                alpha = smoothstep(HomeEditListTokens.MorphContentIn, 1f, m.e)
                val scale = lerp(HomeEditListTokens.MorphContentScale, 1f, m.e.coerceAtMost(1f))
                scaleX = scale
                scaleY = scale
            }
            .semantics(mergeDescendants = true) {
                stateDescription = position
                customActions = buildList {
                    if (index > 0) add(CustomAccessibilityAction(moveUp) { controller.move(section, -1) })
                    if (index in 0 until count - 1) add(CustomAccessibilityAction(moveDown) { controller.move(section, 1) })
                    add(CustomAccessibilityAction(hide) { controller.hide(section) })
                }
            }
            .padding(start = HomeEditTokens.StripPadding, end = HomeEditListTokens.StripEndPadding),
        horizontalArrangement = Arrangement.spacedBy(HomeEditTokens.StripContentGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (covers.isNotEmpty()) StripCovers(covers, coverSize)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colors.onSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        FilledTonalIconButton(
            onClick = { controller.hide(section) },
            enabled = controller.isEditing,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = colors.surfaceContainerHighest,
                contentColor = colors.onSurface,
                disabledContainerColor = colors.surfaceContainerHighest,
                disabledContentColor = colors.onSurface,
            ),
            modifier = Modifier
                .size(HomeEditListTokens.HideButtonSize)
                .homeEditExclusion(targets, ListHideKey(section)) { controller.isEditing }
                .handPointer(enabled = controller.isEditing),
        ) {
            Icon(
                imageVector = YoinSymbols.VisibilityOff,
                contentDescription = stringResource(R.string.home_cd_hide_section, title),
                modifier = Modifier.size(HomeEditListTokens.HideIconSize),
            )
        }
        Box(
            modifier = Modifier
                .size(HomeEditListTokens.HandleTouch)
                .homeEditHandle(targets, section),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = YoinSymbols.DragHandle,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(HomeEditTokens.StripHandleSize),
            )
        }
    }
}

private data class ListHideKey(val section: HomeSection)

/** The list's header item: the strips start at index 1, the tray right after them. */
internal const val HomeEditListHeaderKey = "edit-list-header"
