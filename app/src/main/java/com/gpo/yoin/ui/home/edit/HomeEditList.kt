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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
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
import com.gpo.yoin.ui.home.HomeFeedRow
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.titleRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

// Home edit mode's compact list (owner 2026-11-22): a two-finger pinch in
// folds the feed into one strip per section (two columns: two half strips
// per pair, as the feed pairs them), a pinch out unfolds it. The strips are
// reordered by dragging, as the feed's blocks are (HomeDragEngine), and a
// finger near the top or bottom scrolls the list. Hide works on each strip;
// the hidden ones and Reset follow under the strips, as in the feed.

/** Numbers of the list and its pinch. */
internal object HomeEditListTokens {
    /** A pinch to this fraction of the fingers' starting span takes the feed all the way into the list. */
    const val PinchInFull = .55f

    /** A pinch out to this multiple of the starting span brings the feed all the way back. */
    const val PinchOutFull = 1.8f

    /** Let go this far of the way (or further) and the change finishes; short of it, it springs back. */
    const val PinchCommit = .35f

    /** The strips grow into place from this scale as the list comes in. */
    const val EnterScale = .94f

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

/**
 * The list's progress for a pinch at [scale] (the fingers' span over their
 * starting span) begun [fromOn] (in the list) or not: in from the feed,
 * 0 → 1 as the span closes to [HomeEditListTokens.PinchInFull]; out from the
 * list, 1 → 0 as it opens to [HomeEditListTokens.PinchOutFull]. The other
 * way does nothing. Pure.
 */
internal fun homeListPinchProgress(fromOn: Boolean, scale: Float): Float = with(HomeEditListTokens) {
    if (!fromOn) {
        ((1f - scale) / (1f - PinchInFull)).coerceIn(0f, 1f)
    } else {
        1f - ((scale - 1f) / (PinchOutFull - 1f)).coerceIn(0f, 1f)
    }
}

/** A pinch begun [fromOn] let go at [progress] lands in the list (true) or the feed. Pure. */
internal fun homeListSettlesOn(fromOn: Boolean, progress: Float): Boolean =
    if (!fromOn) progress >= HomeEditListTokens.PinchCommit else progress > 1f - HomeEditListTokens.PinchCommit

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
            .drawBehind {
                val p = list.progress.value.coerceIn(0f, 1f)
                if (p <= 0f) return@drawBehind
                val brush = if (background.size > 1) {
                    Brush.verticalGradient(background, startY = 0f, endY = size.height)
                } else {
                    Brush.verticalGradient(listOf(background.first(), background.first()))
                }
                drawRect(brush, alpha = p)
            },
    ) {
        LazyColumn(
            state = listState,
            modifier = listModifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = list.progress.value.coerceIn(0f, 1f)
                    alpha = p
                    val scale = if (reduced) 1f else HomeEditListTokens.EnterScale + (1f - HomeEditListTokens.EnterScale) * p
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(.5f, 0f)
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
                item(key = homeSectionItemKey(row.first)) {
                    // The dragged row places at once (its strip follows the finger); the others make way.
                    // No spec for it, not a snap: a snap lands a frame late (the strip a row off for a frame).
                    val placement = remember(row, specs) { GatedPlacementSpec(specs.placement) { engine.carries(row) } }
                    val carried by remember(row, engine) { derivedStateOf { engine.carries(row) } }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(
                                fadeInSpec = specs.effectsIn,
                                placementSpec = if (carried) null else placement,
                                fadeOutSpec = specs.effectsOut,
                            )
                            .dragZIndex { if (engine.carries(row)) 1f else 0f },
                        horizontalArrangement = Arrangement.spacedBy(HomeSpanTokens.ColumnGap),
                    ) {
                        row.sections().forEach { section ->
                            key(section) {
                                HomeEditListStrip(
                                    section = section,
                                    index = order.indexOf(section),
                                    count = order.size,
                                    deps = deps,
                                    covers = coversOf(section),
                                    targets = targets,
                                    modifier = Modifier
                                        .weight(1f)
                                        .dragZIndex { if (engine.dragging == section) 1f else 0f },
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
    index: Int,
    count: Int,
    deps: HomeEditDeps,
    covers: List<StripCover>,
    targets: HomeEditTargets,
    modifier: Modifier = Modifier,
) {
    val engine = deps.engine
    val controller = deps.controller
    val colors = MaterialTheme.colorScheme
    val plate = colors.surfaceContainerHigh
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
            .graphicsLayer {
                val offset = engine.translation(section, inList = true)
                translationX = offset.x
                translationY = offset.y
                val lift = if (engine.liftSection == section) engine.lift.value.coerceIn(0f, 1f) else 0f
                val scale = liftScale(lift)
                scaleX = scale
                scaleY = scale
                shadowElevation = HomeEditTokens.LiftShadow.toPx() * lift
                this.shape = shape
                clip = false
            }
            .clip(shape)
            .drawBehind {
                val tint = if (engine.liftSection == section) engine.liftTint.value.coerceIn(0f, 1f) else 0f
                drawRect(lerp(plate, liftedPlate, tint))
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
