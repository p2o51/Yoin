package com.gpo.yoin.ui.home.edit

import android.content.res.Resources
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.ui.component.seamDissolve
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.theme.YoinTheme

/** "1 row", "4 rows": TalkBack's count for a resized section. */
internal fun homeRowsLabel(rows: Int, resources: Resources? = null): String =
    if (resources == null) {
        if (rows == 1) "1 row" else "$rows rows"
    } else {
        resources.getQuantityString(R.plurals.home_edit_rows, rows, rows)
    }

/** Test tag of [section]'s resize handle. */
internal fun homeRowsHandleTag(section: HomeSection): String = "home-rows-handle-${section.id}"

private data class RowsHandleKey(val section: HomeSection)

/**
 * [section]'s resize handle (D1 ②, owner H5): a rounded ⌟ hugging the
 * plate's bottom-right corner, concentric with it, in onSurfaceVariant. It
 * pops in with the block's badges, takes input only while editing, and is
 * an exclusion for the edit gestures, so a press on it never lifts the
 * block. Held it turns primary and grows a little; every stop it crosses
 * pulses it (the tick's visual twin). ↑ / ↓ step a stop for a keyboard.
 * Not shown with a single stop. TalkBack uses the block's own actions.
 */
@Composable
internal fun BoxScope.HomeRowsHandleSlot(
    section: HomeSection,
    deps: HomeEditDeps,
    track: HomeRowsTrack,
    engine: HomeRowsEngine,
    itemSpacing: Dp,
) {
    val motion = deps.motion
    val press = deps.press
    // Composed with the badges: a little before they show, or ahead of a long-press entry.
    val shown by remember(section, deps) {
        derivedStateOf { motion.ripple(section) > BadgeComposeAt || press.charge.value > BadgePrepareCharge }
    }
    if (!shown || track.stopCount < 2) return
    HomeRowsHandle(section, deps, track, engine, itemSpacing)
}

@Composable
private fun BoxScope.HomeRowsHandle(
    section: HomeSection,
    deps: HomeEditDeps,
    track: HomeRowsTrack,
    engine: HomeRowsEngine,
    itemSpacing: Dp,
) {
    val controller = deps.controller
    val motion = deps.motion
    val look = deps.plate
    val colors = MaterialTheme.colorScheme
    val rest = colors.onSurfaceVariant
    val held = colors.primary
    val coordinates = remember { HandleCoordinates() }
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    val hoverTarget = if (hovered && controller.isEditing) HomeRowsTokens.HoverHot else 0f
    LaunchedEffect(hoverTarget) {
        if (!track.dragging) track.hot.animateTo(hoverTarget, deps.specs.liftTint)
    }
    Box(Modifier.matchParentSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                // The touch box's bottom-right edge is the plate's corner — never past
                // it — and the glyph's outer corner sits HandleInset inside it.
                .offset {
                    IntOffset(
                        x = look.plateOutsetH().roundToPx(),
                        y = look.outsetV(itemSpacing).roundToPx(),
                    )
                }
                .size(HomeRowsTokens.HandleTouch)
                .testTag(homeRowsHandleTag(section))
                .homeEditExclusion(deps.targets, RowsHandleKey(section)) { controller.isEditing }
                .onPlaced { coordinates.value = it }
                .graphicsLayer {
                    val local = badgeLocal(motion.ripple(section))
                    alpha = badgeAlpha(local)
                    val scale = badgeScale(local)
                    scaleX = scale
                    scaleY = scale
                }
                .seamDissolve()
                .hoverable(hoverSource)
                .pointerInput(section, engine) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!motion.inputEditing) return@awaitEachGesture
                        val startY = coordinates.rootY(down.position) ?: return@awaitEachGesture
                        if (!engine.grab(section, startY, down.uptimeMillis)) return@awaitEachGesture
                        down.consume()
                        motion.pointerDown = true
                        var released = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                // Other fingers are ignored, but must not scroll the feed.
                                event.changes.forEach { if (it.id != down.id) it.consume() }
                                if (change.changedToUp() || !change.pressed) {
                                    change.consume()
                                    released = true
                                    engine.release(cancelled = false, uptime = change.uptimeMillis)
                                    break
                                }
                                if (change.positionChanged()) {
                                    coordinates.rootY(change.position)?.let { engine.dragTo(it, change.uptimeMillis) }
                                    change.consume()
                                }
                            }
                        } finally {
                            if (!released) engine.release(cancelled = true)
                            motion.pointerDown = false
                            motion.touch()
                        }
                    }
                }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> engine.step(section, -1)
                        Key.DirectionDown -> engine.step(section, 1)
                        else -> false
                    }
                }
                .focusable(enabled = motion.inputEditing)
                // The block's own TalkBack actions cover it ("More rows", "Fewer rows").
                .clearAndSetSemantics { },
        ) {
            HomeRowsGlyph(
                color = { lerp(rest, held, track.hot.value.coerceIn(0f, 1f)) },
                scale = {
                    (1f + HomeRowsTokens.HeldScale * track.hot.value.coerceIn(0f, 1f)) * track.pulse.value
                },
                modifier = Modifier.padding(
                    start = HomeRowsTokens.HandleCornerInTouch - HomeRowsTokens.HandleGlyph,
                    top = HomeRowsTokens.HandleCornerInTouch - HomeRowsTokens.HandleGlyph,
                ),
            )
        }
    }
}

/**
 * The ⌟: two strokes meeting at a rounded corner, the corner's arc concentric
 * with the plate's (radius = plate radius − inset, on the stroke's outer
 * edge), round caps. Its outer corner is the box's bottom-right. Colour and
 * scale are read in draw / layer.
 */
@Composable
internal fun HomeRowsGlyph(color: () -> Color, scale: () -> Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(HomeRowsTokens.HandleGlyph)
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(1f, 1f)
            }
            .drawWithCache {
                val stroke = HomeRowsTokens.HandleStroke.toPx()
                val half = stroke / 2f
                val corner = size.width - half
                val radius = ((HomeEditTokens.PlateRadius - HomeRowsTokens.HandleInset).toPx() - half)
                    .coerceIn(0f, corner - half)
                val path = Path().apply {
                    moveTo(corner, half)
                    lineTo(corner, corner - radius)
                    arcTo(
                        rect = Rect(corner - 2f * radius, corner - 2f * radius, corner, corner),
                        startAngleDegrees = 0f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false,
                    )
                    lineTo(half, corner)
                }
                val style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                onDrawBehind { drawPath(path, color(), style = style) }
            },
    )
}

/** The handle's coordinates, for finger positions in root px (the handle moves with the block). */
private class HandleCoordinates {
    var value: LayoutCoordinates? = null

    fun rootY(local: androidx.compose.ui.geometry.Offset): Float? =
        value?.takeIf { it.isAttached }?.localToRoot(local)?.y
}

// Ripple progress above which the handle composes, as the badges (port sheet §2.6).
private const val BadgeComposeAt = HomeEditTokens.BadgeStart / 2f

// Charge past which the handle composes ahead of a long-press entry, as the badges.
private const val BadgePrepareCharge = .35f

@Preview(name = "Rows handle", showBackground = true, widthDp = 120, heightDp = 120)
@Composable
private fun HomeRowsGlyphPreview() {
    YoinTheme {
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        Box(Modifier.size(120.dp)) {
            HomeRowsGlyph(color = { color }, scale = { 1f }, modifier = Modifier.align(Alignment.Center))
        }
    }
}
