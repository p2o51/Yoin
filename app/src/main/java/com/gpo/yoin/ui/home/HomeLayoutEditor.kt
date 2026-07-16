package com.gpo.yoin.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinContainerShapes
import kotlinx.coroutines.launch

private val EditorRowHeight = 72.dp
private val EditorRowSpacing = 10.dp

/**
 * Inline home-layout editor: one fixed-height row per catalog section with a
 * drag handle (reorder) and a switch (show / hide). Every mutation is persisted
 * immediately via [onLayoutChange], so Done, predictive back, and process death
 * all leave the same state behind — there is no separate save step to lose.
 *
 * Drag follows the app's motion language: the active row tracks the finger 1:1,
 * siblings spring out of the way as the row crosses their midpoint (live swap),
 * and release settles on a spring seeded with the drag's release velocity.
 */
@Composable
internal fun HomeLayoutEditor(
    sections: List<HomeSectionState>,
    onLayoutChange: (HomeLayout) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    val scope = rememberCoroutineScope()
    val stepPx = with(LocalDensity.current) { (EditorRowHeight + EditorRowSpacing).toPx() }
    val settleSpec = YoinMotion.defaultSpatialSpec<Float>()

    // The draft is the source of truth while the editor is open; the incoming
    // [sections] only seeds it. Each commit round-trips through Room and back
    // into the homeLayout flow, and re-seeding from that emission would fight
    // in-flight drags.
    val draft = remember { sections.toMutableStateList() }
    // Per-row visual offset (px). Displaced siblings ride these springs; the
    // released row rides its own for the velocity-seeded settle.
    val rowOffsets = remember { mutableMapOf<String, Animatable<Float, AnimationVector1D>>() }
    fun offsetFor(sectionId: String): Animatable<Float, AnimationVector1D> =
        rowOffsets.getOrPut(sectionId) { Animatable(0f) }

    var activeId by remember { mutableStateOf<String?>(null) }
    // Keeps the just-released row above its siblings until its settle lands.
    var settlingId by remember { mutableStateOf<String?>(null) }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    fun commit() = onLayoutChange(HomeLayout(draft.toList()))

    fun settleDrag(releaseVelocityY: Float) {
        val id = activeId ?: return
        val residual = dragOffset
        activeId = null
        settlingId = id
        dragIndex = -1
        dragOffset = 0f
        val anim = offsetFor(id)
        scope.launch {
            anim.snapTo(residual)
            anim.animateTo(0f, settleSpec, initialVelocity = releaseVelocityY)
            if (settlingId == id) settlingId = null
        }
        haptics.performConfirm()
        commit()
    }

    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 4.dp)
            .padding(bottom = 108.dp + navBarBottom),
        verticalArrangement = Arrangement.spacedBy(EditorRowSpacing),
    ) {
        EditorHeader(
            onDone = {
                haptics.performConfirm()
                onDone()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Drag the handle to reorder · toggle to show or hide",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )

        draft.forEachIndexed { _, sectionState ->
            key(sectionState.section.id) {
                val sectionId = sectionState.section.id
                val offsetAnim = offsetFor(sectionId)
                val isActive = activeId == sectionId
                val rowScale by animateFloatAsState(
                    targetValue = if (isActive) 1.02f else 1f,
                    animationSpec = settleSpec,
                    label = "editorRowScale",
                )
                Surface(
                    shape = YoinContainerShapes.Card,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = if (isActive) 6.dp else 2.dp,
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(EditorRowHeight)
                        .zIndex(
                            when (sectionId) {
                                activeId -> 2f
                                settlingId -> 1f
                                else -> 0f
                            },
                        )
                        .graphicsLayer {
                            translationY = if (activeId == sectionId) dragOffset else offsetAnim.value
                            scaleX = rowScale
                            scaleY = rowScale
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 4.dp, end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DragHandle,
                            contentDescription = "Reorder ${sectionState.section.title}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .minimumTouchTarget()
                                // Keyed on the stable section id, NOT the row
                                // index: a keyed restart mid-gesture would
                                // cancel the drag on the first live swap.
                                .pointerInput(sectionId) {
                                    val velocityTracker = VelocityTracker()
                                    var dragTotal = Offset.Zero
                                    detectDragGestures(
                                        onDragStart = {
                                            // Single-drag policy: a second finger on
                                            // another handle must not hijack the shared
                                            // drag state mid-gesture.
                                            if (activeId != null) return@detectDragGestures
                                            dragIndex = draft.indexOfFirst { it.section.id == sectionId }
                                            if (dragIndex < 0) return@detectDragGestures
                                            activeId = sectionId
                                            // Catch the row where it visually is: fold any
                                            // in-flight settle / swap spring translation
                                            // into the drag instead of letting the row
                                            // teleport to its rest slot under the finger.
                                            dragOffset = offsetAnim.value
                                            dragTotal = Offset.Zero
                                            velocityTracker.resetTracking()
                                            scope.launch {
                                                offsetAnim.stop()
                                                offsetAnim.snapTo(0f)
                                            }
                                            haptics.performContextClick()
                                        },
                                        onDrag = { change, dragAmount ->
                                            // Consume even for a dead gesture (whose start
                                            // was refused) so its deltas can't scroll the
                                            // editor underneath the active drag.
                                            change.consume()
                                            if (activeId != sectionId) return@detectDragGestures
                                            dragOffset += dragAmount.y
                                            // Track cumulative drag, not raw positions:
                                            // positions are row-local and the row chases
                                            // the finger, which would read as ~zero velocity.
                                            dragTotal += dragAmount
                                            velocityTracker.addPosition(change.uptimeMillis, dragTotal)

                                            // Live swap: once the dragged row crosses a
                                            // neighbour's midpoint, swap them in the draft
                                            // and spring the neighbour into its new slot.
                                            var keepSwapping = true
                                            while (keepSwapping) {
                                                val from = dragIndex
                                                val target = when {
                                                    dragOffset > stepPx / 2 && from < draft.lastIndex -> from + 1
                                                    dragOffset < -stepPx / 2 && from > 0 -> from - 1
                                                    else -> -1
                                                }
                                                if (target < 0) {
                                                    keepSwapping = false
                                                } else {
                                                    val displaced = draft[target]
                                                    draft[target] = draft[from]
                                                    draft[from] = displaced
                                                    dragIndex = target
                                                    val shift = (target - from) * stepPx
                                                    dragOffset -= shift
                                                    val displacedAnim = offsetFor(displaced.section.id)
                                                    scope.launch {
                                                        displacedAnim.snapTo(displacedAnim.value + shift)
                                                        displacedAnim.animateTo(0f, settleSpec)
                                                    }
                                                    haptics.performTick()
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            // Only the gesture that owns the drag may
                                            // settle it — a refused second finger's
                                            // release must not settle the active row.
                                            if (activeId == sectionId) {
                                                settleDrag(velocityTracker.calculateVelocity().y)
                                            }
                                        },
                                        onDragCancel = {
                                            if (activeId == sectionId) settleDrag(0f)
                                        },
                                    )
                                },
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            val contentAlpha = if (sectionState.enabled) 1f else 0.55f
                            Text(
                                text = sectionState.section.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = sectionState.section.supportingText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Switch(
                            checked = sectionState.enabled,
                            onCheckedChange = { checked ->
                                haptics.performContextClick()
                                val i = draft.indexOfFirst { it.section.id == sectionId }
                                if (i >= 0) {
                                    draft[i] = draft[i].copy(enabled = checked)
                                    commit()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorHeader(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Edit Home",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        IconButton(onClick = onDone) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Done",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
