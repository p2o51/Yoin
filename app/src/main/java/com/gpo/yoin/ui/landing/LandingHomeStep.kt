package com.gpo.yoin.ui.landing

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.zIndex
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.home.HomeLayout
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.HomeSectionState
import com.gpo.yoin.ui.home.supportingRes
import com.gpo.yoin.ui.home.titleRes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The Home step: every section of Home's catalog, on/off and in order. Drag a row by its handle (or long-press
 * it) to move it. The same haptics as Home's edit mode (haptic-feedback.md §F): lift = DRAG_START, every slot
 * passed = SEGMENT_TICK, a drop that changed the order = CONFIRM; the switches = TOGGLE_ON / OFF. Row counts stay
 * with Home's edit mode — one line below says so.
 */
@Composable
internal fun HomeSectionsScene(
    layout: HomeLayout,
    onEnabled: (HomeSection, Boolean) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
) {
    LandingSceneColumn {
        SectionList(layout.sections, onEnabled, onMove)
        Text(
            text = stringResource(R.string.landing_home_rows_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp),
        )
    }
}

@Composable
private fun SectionList(
    sections: List<HomeSectionState>,
    onEnabled: (HomeSection, Boolean) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
) {
    val haptics = rememberYoinHaptics()
    val heights = remember { mutableStateMapOf<HomeSection, Int>() }
    var dragging by remember { mutableStateOf<HomeSection?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // The order as the drag sees it: moved here at once, so two moves inside one frame never read a list that
    // hasn't recomposed yet.
    val liveOrder = remember { mutableListOf<HomeSection>() }
    var orderAtLift by remember { mutableStateOf<List<HomeSection>>(emptyList()) }
    val currentSections by rememberUpdatedState(sections)
    val currentOnMove by rememberUpdatedState(onMove)
    val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { RowGap.toPx() }

    fun lift(section: HomeSection) {
        dragging = section
        dragOffset = 0f
        orderAtLift = currentSections.map { it.section }
        liveOrder.clear()
        liveOrder.addAll(orderAtLift)
        haptics.performDragStart()
    }

    fun move(from: Int, to: Int) {
        liveOrder.add(to, liveOrder.removeAt(from))
        currentOnMove(from, to)
        haptics.performSegmentTick()
    }

    fun dragBy(dy: Float) {
        val section = dragging ?: return
        dragOffset += dy
        val index = liveOrder.indexOf(section)
        if (index < 0) return
        // Pass a neighbour once the dragged row's centre crosses the neighbour's centre.
        if (dragOffset > 0 && index < liveOrder.lastIndex) {
            val step = (heights[liveOrder[index + 1]] ?: 0) + gapPx
            if (dragOffset > step / 2f) {
                move(index, index + 1)
                dragOffset -= step
            }
        } else if (dragOffset < 0 && index > 0) {
            val step = (heights[liveOrder[index - 1]] ?: 0) + gapPx
            if (-dragOffset > step / 2f) {
                move(index, index - 1)
                dragOffset += step
            }
        }
    }

    // The released row settles into its slot on the spatial spring, then lands.
    val scope = rememberCoroutineScope()
    val settleSpec = YoinMotion.defaultSpatialSpec<Float>()
    fun drop() {
        val section = dragging ?: return
        if (liveOrder != orderAtLift) haptics.performConfirm()
        scope.launch {
            animate(dragOffset, 0f, animationSpec = settleSpec) { value, _ -> dragOffset = value }
            if (dragging == section) dragging = null
        }
    }

    val moveUp = stringResource(R.string.home_edit_move_up)
    val moveDown = stringResource(R.string.home_edit_move_down)
    Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
        sections.forEachIndexed { index, state ->
            val section = state.section
            val lifted = dragging == section
            // Keyed: the row under the finger must stay the same composable (and the same gesture) as it moves.
            key(section) {
                SectionRow(
                    state = state,
                    first = index == 0,
                    last = index == sections.lastIndex,
                    lifted = lifted,
                    modifier = Modifier
                        .onSizeChanged { heights[section] = it.height }
                        .zIndex(if (lifted) 1f else 0f)
                        .then(if (lifted) Modifier.graphicsLayer { translationY = dragOffset } else Modifier.animatePlacement())
                        .pointerInput(section) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { lift(section) },
                                onDrag = { change, amount -> change.consume(); dragBy(amount.y) },
                                onDragEnd = { drop() },
                                onDragCancel = { drop() },
                            )
                        }
                        .semantics {
                            customActions = buildList {
                                if (index > 0) add(CustomAccessibilityAction(moveUp) { onMove(index, index - 1); haptics.performSegmentTick(); true })
                                if (index < sections.lastIndex) add(CustomAccessibilityAction(moveDown) { onMove(index, index + 1); haptics.performSegmentTick(); true })
                            }
                        },
                    handleModifier = Modifier.pointerInput(section) {
                        detectDragGestures(
                            onDragStart = { lift(section) },
                            onDrag = { change, amount -> change.consume(); dragBy(amount.y) },
                            onDragEnd = { drop() },
                            onDragCancel = { drop() },
                        )
                    },
                    onEnabled = { on ->
                        haptics.performToggle(on)
                        onEnabled(section, on)
                    },
                )
            }
        }
    }
}

private val RowGap = 2.dp

@Composable
private fun SectionRow(
    state: HomeSectionState,
    first: Boolean,
    last: Boolean,
    lifted: Boolean,
    modifier: Modifier,
    handleModifier: Modifier,
    onEnabled: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val top by animateDpAsState(if (first || lifted) 20.dp else 4.dp, YoinMotion.defaultSpatialSpec(), label = "rowTop")
    val bottom by animateDpAsState(if (last || lifted) 20.dp else 4.dp, YoinMotion.defaultSpatialSpec(), label = "rowBottom")
    val color by animateColorAsState(if (lifted) scheme.surfaceContainerHighest else scheme.surfaceBright, YoinMotion.defaultEffectsSpec(), label = "rowColor")
    val scale by animateFloatAsState(if (lifted) 1.02f else 1f, YoinMotion.fastSpatialSpec(), label = "rowScale")
    val dim by animateFloatAsState(if (state.enabled) 1f else 0.5f, YoinMotion.defaultEffectsSpec(), label = "rowDim")
    val title = stringResource(state.section.titleRes)
    Surface(
        shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
        color = color,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 2.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Box(
                modifier = handleModifier
                    .size(width = 36.dp, height = 44.dp)
                    .semantics { },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    YoinSymbols.DragHandle,
                    contentDescription = stringResource(R.string.landing_home_drag, title),
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            SectionGlyph(state.section, scheme.primary, Modifier.graphicsLayer { alpha = dim })
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 10.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurface.copy(alpha = dim),
                )
                Text(
                    text = stringResource(state.section.supportingRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.enabled, onCheckedChange = onEnabled)
        }
    }
}

/** A 36dp sketch of the section's layout on Home, in the primary colour. */
@Composable
private fun SectionGlyph(section: HomeSection, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(36.dp)) {
        val u = size.width / 36f
        fun box(x: Float, y: Float, w: Float, h: Float, a: Float, r: Float = 2.5f) =
            drawRoundRect(color.copy(alpha = a), Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(r * u))
        fun dot(x: Float, y: Float, r: Float, a: Float) = drawCircle(color.copy(alpha = a), r * u, Offset(x * u, y * u))
        when (section) {
            HomeSection.Activities -> {
                box(4f, 6f, 16f, 24f, 1f, 3f); box(22f, 6f, 10f, 11f, 0.55f, 3f); box(22f, 19f, 10f, 11f, 0.3f, 3f)
            }
            HomeSection.JumpBackIn -> for (c in 0..2) for (r in 0..1) box(4f + c * 10f, 8f + r * 10f, 8f, 8f, if (r == 0) 0.9f else 0.45f, 2f)
            HomeSection.RecentlyAdded -> {
                for (c in 0..2) box(3f + c * 10.5f, 12f, 9f, 9f, 0.9f - c * 0.25f, 2f)
                box(3f, 24f, 14f, 2.5f, 0.35f, 1.2f)
            }
            HomeSection.Rediscover -> {
                box(5f, 10f, 15f, 15f, 0.45f, 3f); box(14f, 12f, 15f, 15f, 1f, 3f)
            }
            HomeSection.RecentlyPlayed -> {
                for (c in 0..2) dot(8.5f + c * 9.5f, 17f, 4.3f, 0.9f - c * 0.25f)
                box(6f, 25f, 22f, 2.4f, 0.35f, 1.2f)
            }
            HomeSection.YourPlaylists -> for (r in 0..2) {
                box(5f, 8f + r * 7.5f, 6f, 6f, 0.9f - r * 0.2f, 1.5f)
                box(14f, 9.8f + r * 7.5f, 16f - r * 3f, 2.4f, 0.4f, 1.2f)
            }
        }
    }
}

/**
 * Moves a row to its new slot on the spatial spring whenever the list reorders under it (a Column has no item
 * animation of its own). The offset is applied in placement, never in composition.
 */
private fun Modifier.animatePlacement(): Modifier = composed {
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(IntOffset.Zero) }
    var animatable by remember { mutableStateOf<Animatable<IntOffset, AnimationVector2D>?>(null) }
    val spec = YoinMotion.defaultSpatialSpec<IntOffset>()
    this
        .onPlaced { target = it.positionInParent().round() }
        .offset {
            val anim = animatable ?: Animatable(target, IntOffset.VectorConverter).also { animatable = it }
            if (anim.targetValue != target) scope.launch { anim.animateTo(target, spec) }
            anim.value - target
        }
}

@Preview(showBackground = true)
@Composable
private fun HomeSectionsScenePreview() {
    YoinTheme {
        HomeSectionsScene(layout = HomeLayout.Default, onEnabled = { _, _ -> }, onMove = { _, _ -> })
    }
}
