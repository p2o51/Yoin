package com.gpo.yoin.ui.home.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.flow.collectLatest

/**
 * What a card inside a Home section needs to wiggle and to offer "Edit Home"
 * to TalkBack. Provided per section by the edit block; absent (null) outside
 * Home, where the card hooks do nothing.
 */
@Stable
internal class HomeEditCardScope(
    val section: HomeSection,
    val motion: HomeEditMotion,
    /** Cards take clicks: not editing (a snapshot read). */
    val interactive: () -> Boolean,
    /** TalkBack's long-click: enter edit mode on this section, without a lift. */
    val enterEdit: () -> Unit,
    /** The block's top in the page Box, from the list's layout info (draw-time read); null when not laid out. */
    val blockTopInBox: () -> Float?,
    /** The block's content, which card rects are measured in (`onPlaced` only). */
    val blockCoordinates: () -> LayoutCoordinates?,
    val safeArea: () -> HomeEditSafeArea,
    /** 1 − lift while this section is lifted, 0 under its strip, else 1. */
    val liftGain: () -> Float,
    /** The edit-mode plate (HomePlateVariant.kt); the shipped V1 unless the debug harness says otherwise. */
    val plate: HomePlateLook = HomePlateLook.Default,
    /** This section's row resize (HomeRowsResize.kt); null for a section without presets, or outside Home. */
    val rows: HomeRowsTrack? = null,
    val rowsEngine: HomeRowsEngine? = null,
) {
    /** How far this block's content draws in on each side while editing (plate trial V2; else 0). Layout / draw. */
    fun contentInset(): Dp = plate.contentInset { motion.ripple(section) }
}

internal val LocalHomeEditCardScope = compositionLocalOf<HomeEditCardScope?> { null }

/** Whether a card should take its click: false while editing. True outside Home. */
@Composable
internal fun homeEditInteractive(): Boolean = LocalHomeEditCardScope.current?.interactive() ?: true

/**
 * Card [index] of its section (port sheet §3.1 parity order): rotates the
 * card about its centre in draw (card-level wiggle, §3.3), records its rect
 * in the block for edit-mode taps, and in normal mode adds TalkBack's
 * "Edit Home" long-click. Put it on the card's outermost modifier.
 */
internal fun Modifier.homeEditCard(index: Int): Modifier = this then HomeEditCardElement(index)

private data class HomeEditCardElement(val index: Int) : ModifierNodeElement<HomeEditCardNode>() {
    override fun create() = HomeEditCardNode(index)

    override fun update(node: HomeEditCardNode) {
        node.update(index)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "homeEditCard"
        properties["index"] = index
    }
}

private class HomeEditCardNode(private var index: Int) :
    Modifier.Node(),
    DrawModifierNode,
    LayoutAwareModifierNode,
    SemanticsModifierNode,
    ObserverModifierNode,
    CompositionLocalConsumerModifierNode,
    HomeEditCardSpot {

    // The card's top in its block at its last placement: the wiggle's edge
    // band reads it in draw. Taps resolve the rect afresh ([rectInBlock]).
    private var placedRect: Rect? = null
    private var placed: LayoutCoordinates? = null
    private var registeredIn: HomeEditCardScope? = null
    private var longClickScope: HomeEditCardScope? = null

    override val cardIndex: Int get() = index

    // The index is read at tap time, so a re-indexed card needs no re-registration.
    fun update(index: Int) {
        this.index = index
    }

    override fun onAttach() {
        observeLongClick()
    }

    override fun onDetach() {
        unregister()
        placedRect = null
        placed = null
        longClickScope = null
    }

    // Edit mode toggles the long-click, so semantics follow the scope and the
    // controller's editing flag without anything reading them per frame.
    override fun onObservedReadsChanged() {
        observeLongClick()
        invalidateSemantics()
    }

    private fun observeLongClick() {
        observeReads {
            longClickScope = currentValueOf(LocalHomeEditCardScope)?.takeIf { it.interactive() }
        }
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        val scope = longClickScope ?: return
        onLongClick(label = currentValueOf(LocalContext).getString(R.string.home_edit_action_card)) {
            scope.enterEdit()
            true
        }
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        placed = coordinates
        val scope = currentValueOf(LocalHomeEditCardScope) ?: return
        val block = scope.blockCoordinates()?.takeIf { it.isAttached } ?: return
        placedRect = block.localBoundingBoxOf(coordinates, clipBounds = false)
        if (registeredIn === scope) return
        unregister()
        registeredIn = scope
        scope.motion.registerCard(scope.section, this)
    }

    override fun rectInBlock(): Rect? {
        if (!isAttached) return null
        val scope = registeredIn ?: return null
        val block = scope.blockCoordinates()?.takeIf { it.isAttached } ?: return null
        val own = placed?.takeIf { it.isAttached } ?: return null
        return block.localBoundingBoxOf(own, clipBounds = false)
    }

    private fun unregister() {
        registeredIn?.let { it.motion.unregisterCard(it.section, this) }
        registeredIn = null
    }

    override fun ContentDrawScope.draw() {
        val angle = currentValueOf(LocalHomeEditCardScope)
            ?.cardWiggleDeg(index, size, topInBlock = placedRect?.top ?: 0f, density = this)
            ?: 0f
        if (angle == 0f) {
            drawContent()
        } else {
            rotate(angle, pivot = center) { this@draw.drawContent() }
        }
    }
}

/**
 * Whole-block wiggle for a block with no cards (a placeholder, the empty
 * Activities card): rotates the content about its centre by Θ from the
 * [platePx] size, with the edge band on the plate (port sheet §3.1, §3.3).
 */
internal fun Modifier.homeEditBlockWiggle(scope: HomeEditCardScope, platePx: () -> Size): Modifier =
    this then HomeEditBlockWiggleElement(scope, platePx)

private data class HomeEditBlockWiggleElement(
    val scope: HomeEditCardScope,
    val platePx: () -> Size,
) : ModifierNodeElement<HomeEditBlockWiggleNode>() {
    override fun create() = HomeEditBlockWiggleNode(scope, platePx)

    override fun update(node: HomeEditBlockWiggleNode) {
        node.scope = scope
        node.platePx = platePx
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "homeEditBlockWiggle"
    }
}

private class HomeEditBlockWiggleNode(
    var scope: HomeEditCardScope,
    var platePx: () -> Size,
) : Modifier.Node(), DrawModifierNode {
    override fun ContentDrawScope.draw() {
        val angle = scope.blockWiggleDeg(size, platePx, density = this)
        if (angle == 0f) {
            drawContent()
        } else {
            rotate(angle, pivot = center) { this@draw.drawContent() }
        }
    }
}

/**
 * Card [index]'s angle in degrees (port sheet §3.3), 0 to draw it straight.
 * [topInBlock] is the card's top in its block. Cheap at rest (critique 20):
 * with no sway and no kick it returns before reading the list's layout info
 * or the safe area, so scrolling a still page redraws nothing extra.
 */
internal fun HomeEditCardScope.cardWiggleDeg(index: Int, size: Size, topInBlock: Float, density: Density): Float {
    if (motion.style.target != HomeWiggleTarget.Card) return 0f
    val envelope = motion.envelope.value
    val blockKick = motion.blockKick(section)
    val cardKick = motion.cardKick(section, index)
    if (envelope <= HomeEditTokens.SwayFloor && blockKick == 0f && cardKick == 0f) return 0f
    val sway = motion.sway(section, envelope)
    val gain = liftGain() * (if (motion.reducedMotion) 0f else 1f)
    val amplitude = cardAmpDeg(size.width / density.density, size.height / density.density)
    val alt = cardAlt(index)
    if (cardAngleDeg(sway, blockKick, cardKick, alt, amplitude, gain, band = 1f) == 0f) return 0f
    val top = (blockTopInBox() ?: return 0f) + topInBlock
    val safe = safeArea()
    val fade = with(density) { HomeEditTokens.BandFade.toPx() }
    val band = edgeBand(top, top + size.height, safe.top, safe.bottom, fade)
    return cardAngleDeg(sway, blockKick, cardKick, alt, amplitude, gain, band)
}

/**
 * The whole block's angle in degrees, 0 to draw it straight: Θ from the
 * [plate] size (the content's [size] until the plate is known), the edge
 * band on the plate. Cheap at rest, like [cardWiggleDeg].
 */
internal fun HomeEditCardScope.blockWiggleDeg(size: Size, plate: () -> Size, density: Density): Float {
    val envelope = motion.envelope.value
    val kick = motion.blockKick(section)
    if (envelope <= HomeEditTokens.SwayFloor && kick == 0f) return 0f
    val plateSize = plate().takeUnless { it.width <= 0f || it.height <= 0f } ?: size
    val sway = motion.sway(section, envelope)
    val gain = liftGain() * (if (motion.reducedMotion) 0f else 1f)
    val theta = blockThetaDeg(plateSize.width, plateSize.height, density)
    if (blockAngleDeg(sway, kick, theta, gain, band = 1f) == 0f) return 0f
    val top = blockTopInBox() ?: return 0f
    val outsetV = ((plateSize.height - size.height) / 2f).coerceAtLeast(0f)
    val safe = safeArea()
    val band = edgeBand(
        top = top - outsetV,
        bottom = top + size.height + outsetV,
        safeTop = safe.top,
        safeBottom = safe.bottom,
        fadePx = with(density) { HomeEditTokens.BandFade.toPx() },
    )
    return blockAngleDeg(sway, kick, theta, gain, band)
}

/**
 * The page's one wiggle clock: runs [HomeEditMotion.runClock] only while
 * [HomeEditMotion.clockShouldRun], so a still page costs nothing. Emits no UI.
 */
@Composable
internal fun HomeEditClock(motion: HomeEditMotion) {
    LaunchedEffect(motion) {
        snapshotFlow { motion.clockShouldRun }.collectLatest { run -> if (run) motion.runClock() }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 200)
@Composable
private fun HomeEditWigglePreview() {
    YoinTheme {
        val scope = rememberCoroutineScope()
        val specs = rememberHomeEditSpecs(reduced = false)
        val motion = remember(specs) {
            HomeEditMotion(
                scope = scope,
                specs = specs,
                style = HomeWiggleStyle(),
                reduced = { false },
                progress = { 1f },
                editing = { true },
                feedback = HomeEditFeedback.None,
            )
        }
        val cardScope = remember(motion) {
            HomeEditCardScope(
                section = HomeSection.RecentlyAdded,
                motion = motion,
                interactive = { false },
                enterEdit = {},
                blockTopInBox = { 400f },
                blockCoordinates = { null },
                safeArea = { HomeEditSafeArea(top = 0f, bottom = 10_000f) },
                liftGain = { 1f },
            )
        }
        LaunchedEffect(motion) { motion.touch() }
        HomeEditClock(motion)
        CompositionLocalProvider(LocalHomeEditCardScope provides cardScope) {
            Row(
                modifier = Modifier.padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(3) { index ->
                    Box(
                        Modifier
                            .homeEditCard(index)
                            .size(82.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer, YoinArtworkShapes.Cover),
                    )
                }
                Box(
                    Modifier
                        .homeEditBlockWiggle(cardScope) { Size.Zero }
                        .size(width = 96.dp, height = 82.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, YoinContainerShapes.Panel),
                )
            }
        }
    }
}
