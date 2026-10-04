package com.gpo.yoin.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.gpo.yoin.ui.experience.FeedFrameClass
import com.gpo.yoin.ui.experience.FeedFrameInsets
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.feedFrameInsets
import com.gpo.yoin.ui.theme.YoinMotion

/**
 * The Home feed's horizontal frame (adaptive principle 5 + the Home density
 * rules): the feed list itself runs the container's full width and the page
 * margins are its content padding — 16dp with the content capped at 688dp
 * (Capped), 32dp (Wide), 24dp (landscape handset) — so a horizontal shelf can
 * bleed to the container's real edge instead of stopping at a centred 720dp
 * column (the old mid-page hard cut at 753 / 800dp).
 *
 * The container width is the LIVE width (written by [feedFrameWidth] in the
 * layout pass), so a column or side-panel spring re-lays the margins every
 * frame without recomposing. The frame CLASS only changes at a resting width
 * threshold; when it does, the margins ease from the old formula to the new
 * one on a spatial spring instead of jumping.
 */
@Stable
internal class HomeFeedFrame(initial: FeedFrameClass) {
    /**
     * The container's live width, written in the layout pass. Snapshot state
     * so a reader deeper in the feed (a shelf item whose own constraints don't
     * change) re-lays when it moves; writing an equal value is a no-op.
     */
    var containerWidth: Dp by mutableStateOf(0.dp)
        internal set

    internal var from by mutableStateOf(initial)
    internal var to by mutableStateOf(initial)

    /**
     * Where an interrupted blend started from: the margins as they stood when
     * the class changed again mid-blend, so the new blend leaves from there
     * instead of jumping to the old target's formula. Null = blend from [from].
     */
    internal var fromFrozen by mutableStateOf<FeedFrameInsets?>(null)

    // Fine threshold: blend scales up to ~600dp of margin travel, and the
    // default 0.01 would land the last few dp in one visible frame.
    internal val blend = Animatable(1f, visibilityThreshold = 0.0005f)

    private fun fromInsets(): FeedFrameInsets = fromFrozen ?: feedFrameInsets(containerWidth, from)

    /** Live start margin (read in layout / measure only). */
    val start: Dp
        get() = lerp(fromInsets().start, feedFrameInsets(containerWidth, to).start, blend.value)

    /** Live end margin (read in layout / measure only). */
    val end: Dp
        get() = lerp(fromInsets().end, feedFrameInsets(containerWidth, to).end, blend.value)

    /** Live content width between the margins. */
    val contentWidth: Dp
        get() = (containerWidth - start - end).coerceAtLeast(0.dp)

    /** The blend is moving the margins (and with them every width-driven height). */
    val isBlending: Boolean get() = blend.isRunning
}

@Composable
internal fun rememberHomeFeedFrame(frameClass: FeedFrameClass): HomeFeedFrame {
    val frame = remember { HomeFeedFrame(frameClass) }
    val reduced by rememberUpdatedState(LocalMotionProfile.current == MotionProfile.AdaptiveReduced)
    val spring = YoinMotion.spatialSpring<Float>()
    // Keyed on the class only: a motion-profile flip must not cancel a blend
    // half way and leave the margins between two formulas.
    LaunchedEffect(frameClass) {
        if (frame.to == frameClass) {
            if (frame.blend.value < 1f) frame.blend.snapTo(1f)
            return@LaunchedEffect
        }
        frame.fromFrozen = if (frame.blend.value < 1f) {
            FeedFrameInsets(frame.start, frame.end)
        } else {
            null
        }
        frame.from = frame.to
        frame.to = frameClass
        if (reduced) {
            frame.blend.snapTo(1f)
        } else {
            frame.blend.snapTo(0f)
            frame.blend.animateTo(1f, spring)
        }
        frame.fromFrozen = null
    }
    return frame
}

/** Hands the container's live width to [frame]; put it on the full-width feed list. */
internal fun Modifier.feedFrameWidth(frame: HomeFeedFrame): Modifier = layout { measurable, constraints ->
    if (constraints.hasBoundedWidth) frame.containerWidth = constraints.maxWidth.toDp()
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

/**
 * The feed list's content padding: the frame's live margins at the sides, fixed
 * [top] / [bottom]. Read by the list in its measure pass, so margin changes
 * re-lay instead of recompose.
 */
@Stable
internal class FeedFramePadding(
    private val frame: HomeFeedFrame,
    private val top: Dp,
    private val bottom: Dp,
) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        if (layoutDirection == LayoutDirection.Ltr) frame.start else frame.end

    override fun calculateTopPadding(): Dp = top

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        if (layoutDirection == LayoutDirection.Ltr) frame.end else frame.start

    override fun calculateBottomPadding(): Dp = bottom

    override fun equals(other: Any?): Boolean =
        other is FeedFramePadding && other.frame === frame && other.top == top && other.bottom == bottom

    override fun hashCode(): Int = (System.identityHashCode(frame) * 31 + top.hashCode()) * 31 + bottom.hashCode()
}

/** Only the frame's live side margins — a bleeding shelf's own content padding. */
@Stable
internal class FeedFrameSidePadding(private val frame: HomeFeedFrame) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        if (layoutDirection == LayoutDirection.Ltr) frame.start else frame.end

    override fun calculateTopPadding(): Dp = 0.dp

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        if (layoutDirection == LayoutDirection.Ltr) frame.end else frame.start

    override fun calculateBottomPadding(): Dp = 0.dp

    override fun equals(other: Any?): Boolean = other is FeedFrameSidePadding && other.frame === frame

    override fun hashCode(): Int = System.identityHashCode(frame)
}

/**
 * Eases this section's HEIGHT across a density re-composition (width always
 * follows the constraints exactly). Height changes that come with the width —
 * a column or side-panel spring, the split handle — pass through 1:1; only a
 * change of [key] (the crossfading composition's identity: feed units, the
 * bento's stagger) opens a gap between the shown height and the content's,
 * and that gap springs back to zero. Live width keeps flowing through while
 * the gap closes, so a re-pack that lands as a column starts moving neither
 * jumps nor chases. [enabled] false (reduced motion) shows the content height.
 *
 * Height only, on purpose: an animated width (AnimatedContent's SizeTransform)
 * lags a shrinking container, and an over-wide child is centred by its parent
 * — the section slid half its overflow off the leading edge on a column open.
 * Pair it with [heightOfIncomingOnly] on the crossfading children so the
 * outgoing layer never props the container up and drops it mid-motion.
 */
internal fun Modifier.springHeight(
    spec: AnimationSpec<Float>,
    key: Any?,
    enabled: Boolean,
): Modifier = this then SpringHeightElement(spec, key, enabled)

private data class SpringHeightElement(
    val spec: AnimationSpec<Float>,
    val key: Any?,
    val enabled: Boolean,
) : ModifierNodeElement<SpringHeightNode>() {
    override fun create() = SpringHeightNode(spec, key, enabled)

    override fun update(node: SpringHeightNode) {
        node.spec = spec
        node.enabled = enabled
        node.key = key
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "springHeight"
    }
}

private class SpringHeightNode(
    var spec: AnimationSpec<Float>,
    var key: Any?,
    var enabled: Boolean,
) : Modifier.Node(), LayoutModifierNode {
    /** shown − content, springing to 0 after a re-composition. */
    private val gap = Animatable(0f, visibilityThreshold = 0.5f)
    private var measuredKey: Any? = null
    private var lastShown = -1f

    /** A just-seeded gap, used until the coroutine has snapped [gap] to it. */
    private var pendingGap: Float? = null

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        val content = placeable.height.toFloat()
        if (lastShown >= 0f && key != measuredKey && enabled) {
            val seeded = lastShown - content
            pendingGap = seeded
            coroutineScope.launch {
                gap.snapTo(seeded)
                pendingGap = null
                gap.animateTo(0f, spec)
            }
        } else if (!enabled && (gap.value != 0f || pendingGap != null)) {
            pendingGap = null
            coroutineScope.launch { gap.snapTo(0f) }
        }
        measuredKey = key
        // Reading the Animatable here re-lays (never recomposes) while it runs.
        val offset = if (enabled) pendingGap ?: gap.value else 0f
        val shown = (content + offset).coerceAtLeast(0f)
        lastShown = shown
        return layout(placeable.width, constraints.constrainHeight(shown.roundToInt())) {
            placeable.placeRelative(0, 0)
        }
    }
}

/**
 * For an AnimatedContent child: report zero height while it is the OUTGOING
 * layer (it still draws, fading), so the container's height is the incoming
 * composition's alone and [springHeight] owns the ease.
 */
internal fun Modifier.heightOfIncomingOnly(outgoing: () -> Boolean): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, if (outgoing()) 0 else placeable.height) { placeable.placeRelative(0, 0) }
}
