package com.gpo.yoin.ui.home.edit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.gpo.yoin.R
import com.gpo.yoin.ui.component.seamDissolve
import com.gpo.yoin.ui.home.HomeRowPreset
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.lyricCards
import com.gpo.yoin.ui.theme.YoinMotion
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Test tag of 「歌词一句」's count handle. */
internal const val HomeLyricCountHandleTag = "home-lyric-count-handle"

private object LyricCountHandleKey

/** The dots' state while the handle is held: how far it is pulled open, and the count under the finger. */
@Stable
private class LyricCountDrag {
    /** 0 closed, 1 open; follows the finger down. */
    val reveal = Animatable(0f)
    var held by mutableStateOf(false)
    var count by mutableIntStateOf(HomeRowPreset.Default.lyricCards)
}

/**
 * 「歌词一句」's count handle (owner 2026-11-08): the same ⌟ as the row resize, on the plate's bottom-right
 * corner, but held and pulled down it opens a row of four dots under the corner, like a thinking-effort
 * picker. Sliding sideways picks one to four lines (a tick at each), letting go keeps it as one Undo step.
 * ← / → step it for a keyboard. Composed with the block's badges, like the resize handle.
 */
@Composable
internal fun BoxScope.HomeLyricCountHandleSlot(deps: HomeEditDeps, itemSpacing: Dp) {
    val motion = deps.motion
    val press = deps.press
    val section = HomeSection.LyricHighlight
    val shown by remember(deps) {
        derivedStateOf { motion.ripple(section) > LyricBadgeComposeAt || press.charge.value > LyricBadgePrepareCharge }
    }
    if (!shown) return
    HomeLyricCountHandle(deps, itemSpacing)
}

@Composable
private fun BoxScope.HomeLyricCountHandle(deps: HomeEditDeps, itemSpacing: Dp) {
    val controller = deps.controller
    val motion = deps.motion
    val look = deps.plate
    val section = HomeSection.LyricHighlight
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val drag = remember { LyricCountDrag() }
    val hot = remember { Animatable(0f) }
    val spatial = YoinMotion.spatialSpring<Float>()
    val effects = YoinMotion.effectsSpring<Float>()
    LaunchedEffect(drag.held) { hot.animateTo(if (drag.held) 1f else 0f, effects) }
    val saved = { (controller.draft?.rowsOf(section) ?: HomeRowPreset.Default).lyricCards }

    Box(Modifier.matchParentSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset {
                    IntOffset(
                        x = look.plateOutsetH().roundToPx(),
                        y = look.outsetV(itemSpacing).roundToPx(),
                    )
                }
                .size(HomeRowsTokens.HandleTouch)
                .testTag(HomeLyricCountHandleTag)
                .homeEditExclusion(deps.targets, LyricCountHandleKey) { controller.isEditing }
                .graphicsLayer {
                    val local = badgeLocal(motion.ripple(section))
                    alpha = badgeAlpha(local)
                    val scale = badgeScale(local)
                    scaleX = scale
                    scaleY = scale
                }
                .seamDissolve()
                .pointerInput(controller) {
                    val revealPx = LyricRevealDistance.toPx()
                    val pitchPx = LyricDotPitch.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!motion.inputEditing) return@awaitEachGesture
                        down.consume()
                        motion.pointerDown = true
                        val start = saved()
                        drag.count = start
                        drag.held = true
                        var dx = 0f
                        var dy = 0f
                        // Sideways travel counts from where the dots finished opening.
                        var anchorX: Float? = null
                        var released = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                event.changes.forEach { if (it.id != down.id) it.consume() }
                                if (change.changedToUp() || !change.pressed) {
                                    change.consume()
                                    released = true
                                    break
                                }
                                val delta = change.positionChange()
                                dx += delta.x
                                dy += delta.y
                                change.consume()
                                val open = (dy / revealPx).coerceIn(0f, 1f)
                                scope.launch { drag.reveal.snapTo(maxOf(drag.reveal.value, open)) }
                                if (drag.reveal.value >= 1f || open >= 1f) {
                                    val from = anchorX ?: dx.also { anchorX = it }
                                    val next = (start + ((dx - from) / pitchPx).roundToInt()).coerceIn(1, LyricMaxCount)
                                    if (next != drag.count) {
                                        drag.count = next
                                        controller.lyricCountTick()
                                    }
                                }
                            }
                        } finally {
                            val opened = drag.reveal.value >= 1f
                            if (released && opened) controller.setLyricCount(presetFor(drag.count))
                            drag.held = false
                            scope.launch { drag.reveal.animateTo(0f, spatial) }
                            motion.pointerDown = false
                            motion.touch()
                        }
                    }
                }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val step = when (event.key) {
                        Key.DirectionLeft -> -1
                        Key.DirectionRight -> 1
                        else -> return@onKeyEvent false
                    }
                    controller.setLyricCount(presetFor((saved() + step).coerceIn(1, LyricMaxCount)))
                }
                .focusable(enabled = motion.inputEditing)
                .clearAndSetSemantics { },
        ) {
            HomeRowsGlyph(
                color = { lerp(colors.onSurfaceVariant, colors.primary, hot.value.coerceIn(0f, 1f)) },
                scale = { 1f + HomeRowsTokens.HeldScale * hot.value.coerceIn(0f, 1f) },
                modifier = Modifier.padding(
                    start = HomeRowsTokens.HandleCornerInTouch - HomeRowsTokens.HandleGlyph,
                    top = HomeRowsTokens.HandleCornerInTouch - HomeRowsTokens.HandleGlyph,
                ),
            )
            if (drag.held || drag.reveal.value > 0f) {
                val gap = with(density) { LyricPickerGap.roundToPx() }
                Popup(
                    alignment = Alignment.TopEnd,
                    offset = IntOffset(0, with(density) { HomeRowsTokens.HandleTouch.roundToPx() } + gap),
                    properties = PopupProperties(focusable = false, clippingEnabled = false),
                ) {
                    LyricCountPicker(
                        count = drag.count,
                        reveal = { drag.reveal.value },
                    )
                }
            }
        }
    }
}

/** The four dots: every dot up to the count filled, the count's own one larger, its label beside them. */
@Composable
private fun LyricCountPicker(count: Int, reveal: () -> Float) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = colors.surfaceContainerHighest,
        shadowElevation = 6.dp,
        modifier = Modifier.graphicsLayer {
            val r = reveal().coerceIn(0f, 1f)
            alpha = r
            val scale = .8f + .2f * r
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(1f, 0f)
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(LyricMaxCount) { index ->
                    val n = index + 1
                    val on = n <= count
                    val size by animateDpAsState(
                        targetValue = if (n == count) 12.dp else 8.dp,
                        animationSpec = YoinMotion.spatialSpring(),
                        label = "lyricCountDot",
                    )
                    val color by animateColorAsState(
                        targetValue = if (on) colors.primary else colors.outlineVariant,
                        animationSpec = YoinMotion.effectsSpring(),
                        label = "lyricCountDotColor",
                    )
                    Box(Modifier.width(LyricDotPitch), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(size)
                                .background(color, CircleShape),
                        )
                    }
                }
            }
            Text(
                text = pluralStringResource(R.plurals.home_edit_lyric_lines, count, count),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurface,
            )
        }
    }
}

private fun presetFor(count: Int): HomeRowPreset =
    HomeRowPreset.entries.firstOrNull { it.lyricCards == count } ?: HomeRowPreset.Default

private const val LyricMaxCount = 4

/** Pulled this far down, the dots are fully open and start following the finger sideways. */
private val LyricRevealDistance = 40.dp

/** One dot's slot, and the sideways travel from one count to the next. */
private val LyricDotPitch = 28.dp

private val LyricPickerGap = 4.dp

// As the resize handle and the badges (port sheet §2.6).
private const val LyricBadgeComposeAt = HomeEditTokens.BadgeStart / 2f
private const val LyricBadgePrepareCharge = .35f
