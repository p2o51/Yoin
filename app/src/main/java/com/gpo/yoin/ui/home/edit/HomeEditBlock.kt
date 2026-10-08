package com.gpo.yoin.ui.home.edit

import android.content.res.Resources
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ExpressiveSectionPanel
import com.gpo.yoin.ui.component.seamDissolve
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.experience.smoothstep
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.HomeSectionTitle
import com.gpo.yoin.ui.home.RediscoverPlaceholderText
import com.gpo.yoin.ui.home.placeholderRes
import com.gpo.yoin.ui.home.titleRes
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.roundToInt

/** Everything a Home edit surface reads, resolved once in Home's composition. */
@Immutable
internal class HomeEditDeps(
    val controller: HomeEditController,
    val motion: HomeEditMotion,
    val press: HomeEditPressState,
    val engine: HomeCarryEngine,
    val targets: HomeEditTargets,
    val specs: HomeEditSpecs,
    /** The edit-mode plate (HomePlateVariant.kt): the shipped V1 unless the debug harness says otherwise. */
    val plate: HomePlateLook = HomePlateLook.Default,
    /** The row resize (HomeRowsResize.kt); null in previews and tests that don't need it. */
    val rows: HomeRowsEngine? = null,
)

/**
 * One Home section as an edit block (spec §2.2.1, port sheet §2.2–2.6): a
 * plate behind the content that ripples in with P, grows from the press
 * point at a long-press entry and pre-shows while charging; the charge, lift
 * and hide scales; the hide and drag-handle badges on the title row; and the
 * TalkBack actions while editing. The content itself never scales with P.
 *
 * [placeholder] renders the empty-section panel in place of [content];
 * [wholeBlockWiggle] rotates the whole block (a placeholder, the empty
 * Activities card) instead of its cards, and puts the badges on the panel's
 * own title row. [itemSpacing] is the feed's, for the plate's vertical
 * outset. Every per-frame value is read in draw or layer.
 */
@Composable
internal fun HomeEditBlock(
    section: HomeSection,
    displayIndex: Int,
    displayCount: Int,
    deps: HomeEditDeps,
    placeholder: Boolean,
    wholeBlockWiggle: Boolean,
    itemSpacing: Dp,
    blockTopInBox: () -> Float?,
    safeArea: () -> HomeEditSafeArea,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val controller = deps.controller
    val motion = deps.motion
    val press = deps.press
    val engine = deps.engine
    val look = deps.plate
    val blockTop by rememberUpdatedState(blockTopInBox)
    val safe by rememberUpdatedState(safeArea)
    val blockContent = remember { BlockContent() }
    val rowsEngine = deps.rows
    val rowsTrack = rowsEngine?.track(section)
    val cardScope = remember(section, controller, motion, engine, look, rowsEngine) {
        HomeEditCardScope(
            section = section,
            motion = motion,
            interactive = { !motion.inputEditing },
            enterEdit = { controller.enter(section, lifted = false) },
            blockTopInBox = { blockTop() },
            blockCoordinates = { blockContent.coordinates },
            safeArea = { safe() },
            liftGain = {
                val gain = when {
                    engine.session?.carried == section -> 0f
                    engine.liftSection == section -> 1f - engine.lift.value.coerceIn(0f, 1f)
                    else -> 1f
                }
                // The block being resized holds still under its handle.
                gain * (rowsTrack?.wiggleGain?.value?.coerceIn(0f, 1f) ?: 1f)
            },
            plate = look,
            rows = rowsTrack,
            rowsEngine = rowsEngine,
        )
    }
    val editing = controller.isEditing
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    val handleHover = remember { Animatable(0f) }
    val hoverTarget = if (hovered && editing) 1f else 0f
    LaunchedEffect(hoverTarget) { handleHover.animateTo(hoverTarget, deps.specs.liftTint) }

    val density = LocalDensity.current
    val wiggle = if (wholeBlockWiggle) {
        Modifier.homeEditBlockWiggle(cardScope) {
            blockContent.size?.let {
                val hPx = with(density) { (look.plateOutsetH() + cardScope.contentInset()).toPx() }
                val vPx = with(density) { look.outsetV(itemSpacing).toPx() }
                Size(it.width + 2f * hPx, it.height + 2f * vPx)
            } ?: Size.Zero
        }
    } else {
        Modifier
    }
    // Rows (D1): the preset's row count on this screen, and the steps it has.
    val resizable = rowsTrack != null && !placeholder && !wholeBlockWiggle && rowsTrack.stopCount > 1
    val rowCount = if (resizable) rowsTrack?.rowCounts?.getOrNull(rowsTrack.restStop) else null
    val canMoreRows = resizable && rowsEngine?.canStep(section, 1) == true
    val canFewerRows = resizable && rowsEngine?.canStep(section, -1) == true
    val resources = LocalContext.current.resources
    val sectionState = if (rowCount != null) {
        resources.getString(
            R.string.home_cd_section_index_rows,
            displayIndex + 1,
            displayCount,
            homeRowsLabel(rowCount, resources),
        )
    } else {
        resources.getString(R.string.home_cd_section_index, displayIndex + 1, displayCount)
    }
    val moveUpLabel = stringResource(R.string.home_edit_move_up)
    val moveDownLabel = stringResource(R.string.home_edit_move_down)
    val moreRowsLabel = stringResource(R.string.home_edit_more_rows)
    val fewerRowsLabel = stringResource(R.string.home_edit_fewer_rows)
    val hideLabel = stringResource(R.string.home_edit_hide)
    // Remembered: a new semantics block on every recomposition would be a semantics change each time.
    val talkBack = remember(
        editing,
        section,
        displayIndex,
        displayCount,
        controller,
        rowCount,
        canMoreRows,
        canFewerRows,
        sectionState,
        moveUpLabel,
        moveDownLabel,
        moreRowsLabel,
        fewerRowsLabel,
        hideLabel,
    ) {
        if (editing) {
            Modifier.semantics(mergeDescendants = true) {
                // A step's new count is read out as the block's state changes.
                stateDescription = sectionState
                customActions = buildList {
                    if (displayIndex > 0) add(CustomAccessibilityAction(moveUpLabel) { controller.move(section, -1) })
                    if (displayIndex < displayCount - 1) {
                        add(CustomAccessibilityAction(moveDownLabel) { controller.move(section, 1) })
                    }
                    if (canMoreRows) add(CustomAccessibilityAction(moreRowsLabel) { rowsEngine?.step(section, 1) == true })
                    if (canFewerRows) {
                        add(CustomAccessibilityAction(fewerRowsLabel) { rowsEngine?.step(section, -1) == true })
                    }
                    add(CustomAccessibilityAction(hideLabel) { controller.hide(section) })
                }
            }
        } else {
            Modifier
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // The lifted block above its neighbours, read as it is placed: a lift recomposes no block.
            // While editing, each block also draws over the ones after it: a reflow (a hide) starts
            // a block that was past the viewport at its edge, and it slides in under them.
            .blockZIndex {
                when {
                    engine.liftSection == section -> LiftedBlockZIndex
                    editing -> -displayIndex * BlockZIndexStep
                    else -> 0f
                }
            }
            .blockLayer { outset ->
                var scale = motion.hideScale(section)
                var pivotX = size.width / 2f
                var pivotY = size.height / 2f
                if (press.section == section) {
                    val charge = press.charge.value
                    if (charge > ChargeFloor) {
                        scale *= chargeScale(charge)
                        pivotX = press.origin.x + outset.x
                        pivotY = press.origin.y + outset.y
                    }
                }
                if (engine.liftSection == section) {
                    scale *= liftScale(engine.lift.value)
                    pivotX = engine.liftOrigin.x + outset.x
                    pivotY = engine.liftOrigin.y + outset.y
                }
                scaleX = scale
                scaleY = scale
                if (size.width > 0f && size.height > 0f) {
                    transformOrigin = TransformOrigin(pivotX / size.width, pivotY / size.height)
                }
                alpha = motion.hideAlpha(section) * feedFoldShown(engine.fold.value)
            }
            .then(wiggle)
            .hoverable(hoverSource)
            .then(talkBack),
    ) {
        HomeEditPlateSlot(
            section = section,
            deps = deps,
            itemSpacing = itemSpacing,
            titleRowTop = if (placeholder || wholeBlockWiggle) PanelPadding else 0.dp,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .homeEditContentInset(cardScope)
                .onPlaced { blockContent.coordinates = it },
        ) {
            CompositionLocalProvider(LocalHomeEditCardScope provides cardScope) {
                if (placeholder) HomeEditPlaceholder(section) else content()
            }
        }
        HomeEditBadgesSlot(
            section = section,
            showHandle = displayCount > 1,
            // A panel-shaped block (placeholder, empty card) has its title row inside the panel.
            panel = placeholder || wholeBlockWiggle,
            deps = deps,
            handleHover = { handleHover.value },
            contentInset = cardScope::contentInset,
        )
        if (rowsTrack != null && rowsEngine != null && !placeholder && !wholeBlockWiggle) {
            HomeRowsHandleSlot(section, deps, rowsTrack, rowsEngine, itemSpacing)
        }
    }
}

/**
 * Plate trial V2: lays the block's content [HomeEditCardScope.contentInset]
 * narrower on each side, centred, reading the inset in the layout pass. A
 * no-op for every other plate (the inset is 0 and nothing is read).
 */
private fun Modifier.homeEditContentInset(scope: HomeEditCardScope): Modifier =
    if (scope.plate.variant != HomePlateVariant.V2) {
        this
    } else {
        layout { measurable, constraints ->
            val inset = if (constraints.hasBoundedWidth) scope.contentInset().roundToPx() else 0
            val placeable = measurable.measure(constraints.offset(horizontal = -2 * inset))
            layout(placeable.width + 2 * inset, placeable.height) { placeable.place(inset, 0) }
        }
    }

/**
 * The plate, composed only while it can show (at rest an invisible plate
 * would still cost a seam pass near the bar): from this block's own ripple,
 * so the plates compose staggered through P, and already while any block
 * charges, ahead of a long-press entry. The gate is read in this scope only:
 * it flipping recomposes no block.
 */
@Composable
private fun BoxScope.HomeEditPlateSlot(section: HomeSection, deps: HomeEditDeps, itemSpacing: Dp, titleRowTop: Dp) {
    val motion = deps.motion
    val press = deps.press
    val engine = deps.engine
    val shown by remember(section, deps) {
        derivedStateOf {
            motion.ripple(section) > 0f ||
                press.isCharging ||
                engine.liftSection == section ||
                motion.plateFrom?.section == section
        }
    }
    if (shown) HomeEditPlate(section, deps, itemSpacing, titleRowTop)
}

/**
 * The badges, composed a little before they pop in on this block's ripple
 * (so blocks compose theirs apart), and already part way into any block's
 * charge, a few frames after the plates: a long-press entry then has
 * nothing left to compose in its first frames. Invisible (and disabled)
 * until the ripple reaches them.
 */
@Composable
private fun BoxScope.HomeEditBadgesSlot(
    section: HomeSection,
    showHandle: Boolean,
    panel: Boolean,
    deps: HomeEditDeps,
    handleHover: () -> Float,
    contentInset: () -> Dp,
) {
    val motion = deps.motion
    val press = deps.press
    val shown by remember(section, deps) {
        derivedStateOf { motion.ripple(section) > BadgeComposeAt || press.charge.value > BadgePrepareCharge }
    }
    if (!shown) return
    HomeEditBadges(
        section = section,
        showHandle = showHandle,
        deps = deps,
        handleHover = handleHover,
        titleRowTop = if (panel) PanelPadding else 0.dp,
        endInset = if (panel) PanelPadding - BadgeOverhang else -BadgeOverhang,
        contentInset = contentInset,
    )
}

/**
 * The section title's TalkBack "Edit Home" action (spec §2.1.2), outside
 * edit mode only. Put it on the shared section title; outside a Home edit
 * block it does nothing.
 */
internal fun Modifier.homeEditSectionTitle(): Modifier = this then HomeEditSectionTitleElement

private data object HomeEditSectionTitleElement : ModifierNodeElement<HomeEditSectionTitleNode>() {
    override fun create() = HomeEditSectionTitleNode()

    override fun update(node: HomeEditSectionTitleNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "homeEditSectionTitle"
    }
}

private class HomeEditSectionTitleNode :
    Modifier.Node(),
    SemanticsModifierNode,
    ObserverModifierNode,
    CompositionLocalConsumerModifierNode {

    private var scope: HomeEditCardScope? = null

    override fun onAttach() {
        observeScope()
    }

    override fun onDetach() {
        scope = null
    }

    override fun onObservedReadsChanged() {
        observeScope()
        invalidateSemantics()
    }

    private fun observeScope() {
        observeReads {
            scope = currentValueOf(LocalHomeEditCardScope)?.takeIf { it.interactive() }
        }
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        val scope = scope ?: return
        val label = currentValueOf(LocalContext).getString(R.string.home_edit_action_section)
        customActions = listOf(
            CustomAccessibilityAction(label) {
                scope.enterEdit()
                true
            },
        )
    }
}

/**
 * The block's layer, reaching [HomeEditTokens.BlockLayerOutsetH] and
 * [HomeEditTokens.BlockLayerOutsetV] past the content without changing the
 * block's size. Below full alpha (hide, show) a layer
 * composites offscreen within its own bounds; at the content's bounds that
 * would clip the plate's outset, the badges and a shelf's bleed for the
 * whole fade, then pop them back. [block] gets the outset in px: pivots in
 * content px add it.
 */
private fun Modifier.blockLayer(block: GraphicsLayerScope.(outset: Offset) -> Unit): Modifier = this
    .layout { measurable, constraints ->
        val h = HomeEditTokens.BlockLayerOutsetH.roundToPx()
        val v = HomeEditTokens.BlockLayerOutsetV.roundToPx()
        val placeable = measurable.measure(constraints.offset(2 * h, 2 * v))
        layout((placeable.width - 2 * h).coerceAtLeast(0), (placeable.height - 2 * v).coerceAtLeast(0)) {
            placeable.place(-h, -v)
        }
    }
    .graphicsLayer {
        val outset = Offset(
            HomeEditTokens.BlockLayerOutsetH.roundToPx().toFloat(),
            HomeEditTokens.BlockLayerOutsetV.roundToPx().toFloat(),
        )
        block(outset)
    }
    .layout { measurable, constraints ->
        val h = HomeEditTokens.BlockLayerOutsetH.roundToPx()
        val v = HomeEditTokens.BlockLayerOutsetV.roundToPx()
        val placeable = measurable.measure(constraints.offset(-2 * h, -2 * v))
        layout(placeable.width + 2 * h, placeable.height + 2 * v) { placeable.place(h, v) }
    }

/** A `zIndex` read in the placement pass, so a change re-places the block without recomposing it. */
private fun Modifier.blockZIndex(zIndex: () -> Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { placeable.place(0, 0, zIndex = zIndex()) }
}

/** The one line an empty section's placeholder says (spec §2.4). */
internal fun homeEditPlaceholderText(section: HomeSection, resources: Resources? = null): String {
    if (resources == null) {
        return when (section) {
            HomeSection.Activities -> "No recent activity yet" // i18n-allow: HomeEditBlockTest asserts this English
            HomeSection.JumpBackIn -> "Nothing to jump back into yet" // i18n-allow: HomeEditBlockTest asserts this English
            HomeSection.RecentlyAdded -> "Nothing added this month" // i18n-allow: HomeEditBlockTest asserts this English
            HomeSection.Rediscover -> RediscoverPlaceholderText // i18n-allow: HomeEditBlockTest asserts this English
            HomeSection.RecentlyPlayed -> "Nothing played lately" // i18n-allow: HomeEditBlockTest asserts this English
            HomeSection.YourPlaylists -> "No playlists in your library yet" // i18n-allow: HomeEditBlockTest asserts this English
        }
    }
    return resources.getString(section.placeholderRes)
}

/** Test tag of [section]'s drag handle. */
internal fun homeEditHandleTag(section: HomeSection): String = "home-edit-handle-${section.id}"

// The block content's place in the item: card rects are measured in it, and
// it sizes the whole-block wiggle's plate. Plain fields, read at events and in draw.
private class BlockContent {
    var coordinates: LayoutCoordinates? = null

    val size: Size?
        get() = coordinates?.takeIf { it.isAttached }?.size?.let { Size(it.width.toFloat(), it.height.toFloat()) }
}

/**
 * The plate: Panel-shaped, behind the content and outset past it, with its
 * own seam dissolve. Its alpha ripples in with P; while charging it pre-shows
 * around the finger and at a long-press entry it grows from there (circular
 * corners while its rect moves). The lift shadow follows the current rect.
 */
@Composable
private fun BoxScope.HomeEditPlate(section: HomeSection, deps: HomeEditDeps, itemSpacing: Dp, titleRowTop: Dp) {
    val colors = MaterialTheme.colorScheme
    val base = colors.surfaceContainerHigh
    val liftedColor = colors.surfaceContainerHighest
    val look = deps.plate
    val engine = deps.engine
    // Plate trial V3: the title row's line, for its tonal band.
    val titleLine = MaterialTheme.typography.titleLarge.lineHeight.takeIf { it.isSpecified } ?: TitleLineFallback
    // Every value below is read in layout or draw: V0 answers with today's
    // constants; V1's outsets follow P and the page margin, V2's inset the ripple.
    val outsetH: () -> Dp = { look.plateOutsetH() }
    val outsetV: () -> Dp = { look.outsetV(itemSpacing) }
    val contentInset: () -> Dp = { look.contentInset { deps.motion.ripple(section) } }
    Box(
        Modifier
            .matchParentSize()
            .layout { measurable, constraints ->
                val h = outsetH().roundToPx()
                val v = outsetV().roundToPx()
                val width = constraints.maxWidth.takeIf { it != Constraints.Infinity } ?: constraints.minWidth
                val height = constraints.maxHeight.takeIf { it != Constraints.Infinity } ?: constraints.minHeight
                val placeable = measurable.measure(
                    Constraints.fixed((width + 2 * h).coerceAtLeast(0), (height + 2 * v).coerceAtLeast(0)),
                )
                layout(width, height) { placeable.place(-h, -v) }
            }
            .seamDissolve()
            .graphicsLayer {
                val lift = if (engine.liftSection == section) engine.lift.value.coerceIn(0f, 1f) else 0f
                if (lift > 0f) {
                    val frame = deps.plateFrame(
                        section = section,
                        plateSize = size,
                        outsetH = outsetH().toPx(),
                        outsetV = outsetV().toPx(),
                        contentInset = contentInset().toPx(),
                        density = this,
                    )
                    shadowElevation = HomeEditTokens.LiftShadow.toPx() * lift
                    shape = frame.rect?.let { PlateRectShape(it, HomeEditTokens.PlateRadius.toPx()) }
                        ?: YoinContainerShapes.PanelAnimated
                } else {
                    shadowElevation = 0f
                    shape = RectangleShape
                }
                clip = false
            }
            .drawWithCache {
                // Built on first use, not on every size change of a plate that may not draw.
                var panel: Outline? = null
                val radius = CornerRadius(HomeEditTokens.PlateRadius.toPx())
                onDrawBehind {
                    val hPx = outsetH().toPx()
                    val vPx = outsetV().toPx()
                    val frame = deps.plateFrame(section, size, hPx, vPx, contentInset().toPx(), this)
                    if (frame.alpha <= 0f) return@onDrawBehind
                    val lifted = engine.liftSection == section
                    val tint = if (lifted) engine.liftTint.value.coerceIn(0f, 1f) else 0f
                    val color = lerp(base, liftedColor, tint)
                    val rect = frame.rect
                    if (look.titleRowOnly) {
                        // Plate trial V3: no fill at rest — a tonal band behind the
                        // title row; a lifted block gets its body back so the shadow
                        // has something under it, and the entry growth fades as it grows.
                        val lift = if (lifted) engine.lift.value.coerceIn(0f, 1f) else 0f
                        if (rect == null && lift > 0f) {
                            val outline = panel ?: YoinContainerShapes.Panel.createOutline(size, layoutDirection, this)
                                .also { panel = it }
                            drawOutline(outline, color, alpha = frame.alpha * lift)
                        }
                        if (rect != null) {
                            val fading = 1f - smoothstep(0f, 1f, deps.motion.ripple(section))
                            drawRoundRect(color, rect.topLeft, rect.size, radius, alpha = frame.alpha * fading)
                        } else {
                            val pad = HomePlateTrial.V3TitleRowPad.toPx()
                            val top = vPx + titleRowTop.toPx() - pad
                            val height = titleLine.toPx() + 2f * pad
                            drawRoundRect(
                                color = color,
                                topLeft = Offset(0f, top),
                                size = Size(size.width, height),
                                cornerRadius = CornerRadius(height / 2f),
                                alpha = frame.alpha,
                            )
                        }
                        return@onDrawBehind
                    }
                    if (rect == null) {
                        val outline = panel ?: YoinContainerShapes.Panel.createOutline(size, layoutDirection, this)
                            .also { panel = it }
                        drawOutline(outline, color, alpha = frame.alpha)
                    } else {
                        drawRoundRect(color, rect.topLeft, rect.size, radius, alpha = frame.alpha)
                    }
                }
            },
    )
}

/** The plate this frame, in plate px. [rect] null is the full Panel. */
private class PlateFrame(val rect: Rect?, val alpha: Float)

/** Port sheet §2.2 and §2.5: the entry plate's growth, else the charge pre-show, else the ripple. */
private fun HomeEditDeps.plateFrame(
    section: HomeSection,
    plateSize: Size,
    outsetH: Float,
    outsetV: Float,
    // Plate trial V2: the content sits this much further in from the plate (0 otherwise).
    contentInset: Float,
    density: Density,
): PlateFrame {
    val ripple = smoothstep(0f, 1f, motion.ripple(section))
    val from = motion.plateFrom?.takeIf { it.section == section }
    val toPlateX = outsetH + contentInset
    if (from != null && !(controller.isEditing && ripple >= PlateGrown)) {
        val full = Rect(0f, 0f, plateSize.width, plateSize.height)
        val start = from.rect.translate(toPlateX, outsetV)
        return PlateFrame(lerp(start, full, ripple), latchedPlateAlpha(from.latch, ripple))
    }
    if (from == null && press.section == section) {
        val charge = press.charge.value
        val chargeAlpha = if (charge > ChargeFloor) chargePlateAlpha(charge) else 0f
        if (chargeAlpha > ripple) {
            val content = Size(plateSize.width - 2f * toPlateX, plateSize.height - 2f * outsetV)
            val rect = pressRect(press.origin, content, charge, density).translate(toPlateX, outsetV)
            return PlateFrame(rect, chargeAlpha)
        }
    }
    return PlateFrame(rect = null, alpha = ripple)
}

/** A circular-cornered rect inside the plate layer: the shadow outline while the plate charges or grows. */
private class PlateRectShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

/**
 * The hide button, then the drag handle, at the title row's end (spec
 * §2.2.2): touch row 48 + 8 + 48, the handle's icon on the content edge. They
 * pop in on the ripple (`p_i`, not its smoothstep) and take input only while
 * editing; the handle's tint follows [handleHover].
 */
@Composable
private fun BoxScope.HomeEditBadges(
    section: HomeSection,
    showHandle: Boolean,
    deps: HomeEditDeps,
    handleHover: () -> Float,
    titleRowTop: Dp,
    endInset: Dp,
    // Plate trial V2: the title row moves in with the content (0 otherwise).
    contentInset: () -> Dp,
) {
    val controller = deps.controller
    val motion = deps.motion
    val colors = MaterialTheme.colorScheme
    val titleLine = MaterialTheme.typography.titleLarge.lineHeight.takeIf { it.isSpecified } ?: TitleLineFallback
    val density = LocalDensity.current
    val rowTop = with(density) { titleRowTop.toPx() + (titleLine.toPx() - BadgeTouch.toPx()) / 2f }
    // A layer of its own: the badges never size the block.
    Box(Modifier.matchParentSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(-(endInset + contentInset()).roundToPx(), rowTop.roundToInt()) },
            horizontalArrangement = Arrangement.spacedBy(BadgeGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(BadgeTouch)
                    .homeEditExclusion(deps.targets, HideKey(section)) { controller.isEditing }
                    .graphicsLayer {
                        val local = badgeLocal(motion.ripple(section))
                        val scale = badgeScale(local)
                        scaleX = scale
                        scaleY = scale
                        alpha = badgeAlpha(local)
                    }
                    .seamDissolve(),
                contentAlignment = Alignment.Center,
            ) {
                val hideColors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = colors.surfaceContainerHighest,
                    contentColor = colors.onSurface,
                    // The exit fade keeps its look; only input stops.
                    disabledContainerColor = colors.surfaceContainerHighest,
                    disabledContentColor = colors.onSurface,
                )
                FilledTonalIconButton(
                    onClick = { controller.hide(section) },
                    enabled = motion.inputEditing,
                    colors = hideColors,
                    modifier = Modifier.size(HideButtonSize),
                ) {
                    Icon(
                        imageVector = YoinSymbols.VisibilityOff,
                        contentDescription = stringResource(R.string.home_cd_hide_section, stringResource(section.titleRes)),
                        modifier = Modifier.size(HideIconSize),
                    )
                }
            }
            if (showHandle) {
                val painter = rememberVectorPainter(YoinSymbols.DragHandle)
                val rest = colors.onSurfaceVariant
                val hover = colors.onSurface
                Box(
                    modifier = Modifier
                        .size(BadgeTouch)
                        .testTag(homeEditHandleTag(section))
                        .homeEditHandle(deps.targets, section)
                        .graphicsLayer {
                            val local = badgeLocal(motion.ripple(section))
                            val scale = badgeScale(local) * motion.handlePulse(section)
                            scaleX = scale
                            scaleY = scale
                            alpha = badgeAlpha(local)
                        }
                        .seamDissolve(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(HandleIconSize)
                            .drawBehind {
                                val tint = lerp(rest, hover, handleHover().coerceIn(0f, 1f))
                                with(painter) { draw(size, colorFilter = ColorFilter.tint(tint)) }
                            },
                    )
                }
            }
        }
    }
}

/** An enabled section with nothing to show yet, while editing (spec §2.4): a panel, its title and one honest line. */
@Composable
private fun HomeEditPlaceholder(section: HomeSection, modifier: Modifier = Modifier) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    ExpressiveSectionPanel(
        modifier = modifier
            .fillMaxWidth()
            .height(HomeEditTokens.PlaceholderHeight * fontScale)
            .seamDissolve(),
    ) {
        Column(
            modifier = Modifier.padding(PanelPadding),
            verticalArrangement = Arrangement.spacedBy(PlaceholderLineGap),
        ) {
            HomeSectionTitle(text = stringResource(section.titleRes))
            Text(
                text = homeEditPlaceholderText(section, LocalContext.current.resources),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.seamFade(),
            )
        }
    }
}

private data class HideKey(val section: HomeSection)

// Ripple progress above which the badges are composed: half way to where they start to show (port sheet §2.6).
private const val BadgeComposeAt = HomeEditTokens.BadgeStart / 2f

// Charge past which every block's badges compose ahead of the entry: some frames after the plates do.
private const val BadgePrepareCharge = .35f

// Feed draw order while editing: the lifted block on top, then each block over the ones after it
// (the tray, at −1, under them all).
private const val LiftedBlockZIndex = 1f
private const val BlockZIndexStep = .01f

// Charge below this draws and scales nothing (port sheet §2.2).
private const val ChargeFloor = .0005f

// The entry plate is fully grown: the continuous Panel takes over.
private const val PlateGrown = .9999f

private val BadgeTouch = 48.dp
private val BadgeGap = 8.dp

// The handle's touch box hangs past the content edge, so its icon sits on it.
private val BadgeOverhang = 12.dp
private val HideButtonSize = 32.dp
private val HideIconSize = 18.dp
private val HandleIconSize = 24.dp
private val TitleLineFallback = 28.sp

// A placeholder's padding, as HomeEmptyCard's: its title row sits this far in.
private val PanelPadding = 18.dp
private val PlaceholderLineGap = 4.dp

// ── Previews ──────────────────────────────────────────────────────────────

/**
 * Deps for previews: a standalone controller at [progress], editing when
 * [editing], with a silent, reduced-motion Home layer.
 */
@Composable
internal fun rememberPreviewHomeEditDeps(progress: Float, editing: Boolean): HomeEditDeps {
    val scope = rememberCoroutineScope()
    val controller = rememberStandaloneHomeEditController()
    val density = LocalDensity.current
    return remember(controller, progress, editing) {
        if (editing) controller.enter(HomeSection.JumpBackIn, lifted = false)
        controller.progress.snapTo(progress)
        val specs = HomeEditSpecs.create(MotionScheme.expressive(), reduced = true)
        val motion = HomeEditMotion(
            scope = scope,
            specs = specs,
            style = HomeWiggleStyle(),
            reduced = { true },
            progress = controller.progressReader,
            editing = { controller.isEditing },
            feedback = HomeEditFeedback.None,
        )
        // The tray in, the footer out, without an animation to wait for.
        if (editing) {
            scope.launchNow {
                motion.trayAlpha.snapTo(1f)
                motion.footerAlpha.snapTo(0f)
            }
        }
        HomeEditDeps(
            controller = controller,
            motion = motion,
            press = HomeEditPressState(),
            engine = HomeCarryEngine(
                scope = scope,
                specs = specs,
                motion = motion,
                feedback = HomeEditFeedback.None,
                density = density,
                commitOrder = controller::commitOrder,
                finishDeferredExit = controller::finishDeferredExit,
            ),
            targets = HomeEditTargets(),
            specs = specs,
            // The shipped plate at the preview's P: an edit preview shows its full outsets.
            plate = HomePlateLook(HomePlateVariant.V1, progress = controller.progressReader),
        )
    }
}

@Composable
private fun PreviewBlock(progress: Float, editing: Boolean, placeholder: Boolean) {
    YoinTheme {
        val deps = rememberPreviewHomeEditDeps(progress, editing)
        Box(Modifier.padding(horizontal = 16.dp, vertical = 24.dp)) {
            HomeEditBlock(
                section = HomeSection.JumpBackIn,
                displayIndex = 1,
                displayCount = 3,
                deps = deps,
                placeholder = placeholder,
                wholeBlockWiggle = placeholder,
                itemSpacing = 18.dp,
                blockTopInBox = { 0f },
                safeArea = { HomeEditSafeArea(top = 0f, bottom = 10_000f) },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeSectionTitle(text = HomeSection.JumpBackIn.title)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(3) { index ->
                            Box(
                                Modifier
                                    .homeEditCard(index)
                                    .size(96.dp)
                                    .drawBehind { drawRect(PreviewCard) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private val PreviewCard = Color(0xFF8E7CC3)

@Preview(name = "Block · normal", showBackground = true, widthDp = 360)
@Composable
private fun HomeEditBlockNormalPreview() = PreviewBlock(progress = 0f, editing = false, placeholder = false)

@Preview(name = "Block · edit", showBackground = true, widthDp = 360)
@Composable
private fun HomeEditBlockEditPreview() = PreviewBlock(progress = 1f, editing = true, placeholder = false)

@Preview(name = "Block · placeholder", showBackground = true, widthDp = 360)
@Composable
private fun HomeEditBlockPlaceholderPreview() = PreviewBlock(progress = 1f, editing = true, placeholder = true)
