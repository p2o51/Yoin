package com.gpo.yoin.ui.tips

import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
import com.gpo.yoin.ui.theme.YoinMotion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/*
 * The tip page's demo phone. Its screen is laid out at a real phone's size
 * ([ScreenWidth] wide) out of the app's own components, then drawn scaled
 * down to the 168×300 frame — so every component is the one in the app, at
 * its true proportions. The scale is a layer on the screen, so a press lands
 * on whatever is drawn under it.
 */

/** Where the scene's named parts are, for the demo touch to go to. */
internal class DemoTargets {
    var root: LayoutCoordinates? = null
    private val parts = HashMap<String, LayoutCoordinates>()

    fun report(key: String, coordinates: LayoutCoordinates) {
        parts[key] = coordinates
    }

    /**
     * The point ([fx], [fy]) of part [key] (0..1 across it), moved by
     * ([dx], [dy]) px of the screen, as fractions of the screen. Null until laid out.
     */
    fun fraction(key: String, fx: Float, fy: Float, dx: Float, dy: Float): Offset? {
        val screen = root?.takeIf { it.isAttached } ?: return null
        val part = parts[key]?.takeIf { it.isAttached } ?: return null
        val rect = screen.localBoundingBoxOf(part, clipBounds = false)
        val w = screen.size.width.toFloat().coerceAtLeast(1f)
        val h = screen.size.height.toFloat().coerceAtLeast(1f)
        return Offset(
            (rect.left + rect.width * fx + dx) / w,
            (rect.top + rect.height * fy + dy) / h,
        )
    }
}

internal val LocalDemoTargets = staticCompositionLocalOf<DemoTargets?> { null }

/** Names this element [key] for the demo touch. */
internal fun Modifier.demoTarget(key: String): Modifier = composed {
    val targets = LocalDemoTargets.current
    if (targets == null) this else onGloballyPositioned { targets.report(key, it) }
}

/**
 * A long-press that watches without taking the press: [onLongPress] fires
 * at the system long-press timeout, and [interactionSource] (when given)
 * gets the press so the element dips as it does in the app.
 */
internal fun Modifier.demoLongPress(
    interactionSource: MutableInteractionSource? = null,
    onLongPress: () -> Unit,
): Modifier = composed {
    val timeout = LocalViewConfiguration.current.longPressTimeoutMillis
    pointerInput(interactionSource, timeout) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val press = PressInteraction.Press(down.position)
            interactionSource?.tryEmit(press)
            val lifted = withTimeoutOrNull(timeout) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
                true
            }
            if (lifted == null) {
                onLongPress()
                // Wait for the finger to come up.
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.none { it.pressed }) break
                }
            }
            interactionSource?.tryEmit(PressInteraction.Release(press))
        }
    }
}

/** The demo's touch: where it is (fractions of the phone), whether it is down, its hold ring. */
internal class DemoFinger(
    private val spring: FiniteAnimationSpec<Float>,
    private val targets: DemoTargets,
) {
    val x = Animatable(0.5f)
    val y = Animatable(0.5f)
    val alpha = Animatable(0f)
    val press = Animatable(0f)
    val ring = Animatable(0f)
    val ripple = Animatable(1f)

    /** Where part [key] is (see [DemoTargets.fraction]); the phone's middle until it is laid out. */
    fun at(key: String, fx: Float = 0.5f, fy: Float = 0.5f, dx: Float = 0f, dy: Float = 0f): Offset =
        targets.fraction(key, fx, fy, dx, dy) ?: Offset(0.5f, 0.5f)

    suspend fun moveTo(key: String, fx: Float = 0.5f, fy: Float = 0.5f, dx: Float = 0f, dy: Float = 0f) {
        val p = at(key, fx, fy, dx, dy)
        moveTo(p.x, p.y)
    }

    suspend fun moveTo(fx: Float, fy: Float) {
        if (alpha.value < 0.05f) {
            x.snapTo(fx)
            y.snapTo(fy)
            alpha.animateTo(1f, tween(180))
        } else {
            coroutineScope {
                launch { x.animateTo(fx, spring) }
                y.animateTo(fy, spring)
            }
        }
    }

    suspend fun tap(action: () -> Unit) {
        press.animateTo(1f, tween(70))
        action()
        ripple.snapTo(0f)
        coroutineScope {
            launch { ripple.animateTo(1f, tween(420)) }
            press.animateTo(0f, spring)
        }
    }

    /** Holds for the long-press timeout (the ring fills), then [action]; [pressed] is told down and up. */
    suspend fun hold(pressed: (Boolean) -> Unit = {}, action: () -> Unit) {
        press.animateTo(1f, tween(90))
        pressed(true)
        ring.snapTo(0f)
        // The long-press timeout, shown as the ring filling: a clock, so linear.
        ring.animateTo(1f, tween(HoldMs, easing = LinearEasing))
        action()
        ring.snapTo(0f)
        pressed(false)
        press.animateTo(0f, spring)
    }

    /** Down where it is, slide to ([fx], [fy]) over [ms]; [onProgress] gets 0..1 every frame. */
    suspend fun drag(fx: Float, fy: Float, ms: Int, onProgress: (Float) -> Unit) {
        press.animateTo(1f, tween(80))
        val progress = Animatable(0f)
        coroutineScope {
            launch { x.animateTo(fx, tween(ms)) }
            launch { y.animateTo(fy, tween(ms)) }
            progress.animateTo(1f, tween(ms)) { onProgress(value) }
        }
        press.animateTo(0f, spring)
    }

    suspend fun lift() {
        alpha.animateTo(0f, tween(220))
    }

    suspend fun hide() {
        alpha.snapTo(0f)
        press.snapTo(0f)
        ring.snapTo(0f)
        ripple.snapTo(1f)
    }
}

/**
 * A 168×300dp phone (larger in a tall window) that plays [script] on a loop while [playing] and the
 * user isn't touching it. [script] puts the scene back at rest first. The
 * user's first touch takes the phone over; [TakeoverIdleMs] untouched and
 * the demo starts again.
 */
@OptIn(androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun DemoPhone(
    playing: Boolean,
    script: suspend DemoFinger.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    val density = LocalDensity.current
    // A tall window (a tablet held upright) gets a bigger phone: the demo is the page's subject, and at
    // 168×300 it sat small in a sea of empty page. Width keeps it to half the window.
    val window = currentWindowDpSize()
    val grow = minOf(window.height.value * PhoneHeightShare / PhoneHeight.value, window.width.value * 0.5f / PhoneWidth.value)
        .coerceIn(1f, PhoneMaxGrow)
    val fingerSpring = YoinMotion.spatialSpring<Float>()
    val targets = remember { DemoTargets() }
    val finger = remember { DemoFinger(fingerSpring, targets) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    var userHas by remember { mutableStateOf(false) }
    LaunchedEffect(playing, userHas, reduced) {
        if (!playing || userHas || reduced) {
            finger.hide()
            return@LaunchedEffect
        }
        delay(StartDelayMs)
        while (true) {
            finger.script()
            finger.lift()
            delay(LoopGapMs)
        }
    }
    LaunchedEffect(lastTouch) {
        if (lastTouch == 0L) return@LaunchedEffect
        delay(TakeoverIdleMs)
        userHas = false
    }
    Box(
        modifier = Modifier
            .size(PhoneWidth * grow, PhoneHeight * grow)
            .clip(PhoneShape)
            .background(colors.surface)
            // Watch (never consume) the user's touches: the phone becomes theirs.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    userHas = true
                    lastTouch = System.nanoTime()
                }
            },
    ) {
        DemoScreen(targets = targets, content = content)
        Box(modifier = Modifier.fillMaxSize().border(1.5.dp, colors.outlineVariant, PhoneShape))
        Canvas(modifier = Modifier.fillMaxSize()) {
            val a = finger.alpha.value
            if (a <= 0f) return@Canvas
            val c = Offset(size.width * finger.x.value, size.height * finger.y.value)
            val r = with(density) { 14.dp.toPx() } * grow
            val down = finger.press.value
            val primary = colors.primary
            drawCircle(primary.copy(alpha = 0.22f * a), r * (1f - 0.15f * down), c)
            drawCircle(primary.copy(alpha = 0.9f * a), r * 0.42f * (1f - 0.15f * down), c)
            val ripple = finger.ripple.value
            if (ripple < 1f) {
                drawCircle(
                    primary.copy(alpha = (1f - ripple) * 0.5f * a),
                    r * (1f + ripple * 1.4f),
                    c,
                    style = Stroke(width = with(density) { 2.dp.toPx() }),
                )
            }
            val ring = finger.ring.value
            if (ring > 0f) {
                drawArc(
                    primary.copy(alpha = a),
                    startAngle = -90f,
                    sweepAngle = 360f * ring,
                    useCenter = false,
                    topLeft = Offset(c.x - r * 1.3f, c.y - r * 1.3f),
                    size = Size(r * 2.6f, r * 2.6f),
                    style = Stroke(width = with(density) { 2.5.dp.toPx() }, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/**
 * The phone's screen: [content] laid out [ScreenWidth] wide (and as tall as
 * the frame's aspect gives), as a phone window of that size reads it, then
 * scaled down to the frame.
 */
@Composable
private fun DemoScreen(targets: DemoTargets, content: @Composable BoxScope.() -> Unit) {
    val heightDp = (ScreenWidth.value * PhoneHeight.value / PhoneWidth.value).roundToInt()
    Layout(
        content = {
            ProvidePreviewWindow(widthDp = ScreenWidth.value.roundToInt(), heightDp = heightDp) {
                androidx.compose.runtime.CompositionLocalProvider(LocalDemoTargets provides targets) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            .onGloballyPositioned { targets.root = it },
                        content = content,
                    )
                }
            }
        },
        modifier = Modifier.fillMaxSize().clipToBounds(),
    ) { measurables, constraints ->
        val w = ScreenWidth.roundToPx()
        val h = (w.toFloat() * constraints.maxHeight / constraints.maxWidth).roundToInt()
        val scale = constraints.maxWidth / w.toFloat()
        val placeable = measurables.first().measure(Constraints.fixed(w, h))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

private val PhoneWidth = 168.dp
private val PhoneHeight = 300.dp
private val PhoneShape = RoundedCornerShape(24.dp)

/** How much of the window's height the phone may take before it stops growing, and its largest size. */
private const val PhoneHeightShare = 0.36f
private const val PhoneMaxGrow = 1.7f

/** The demo screen's width: a common phone's. */
internal val ScreenWidth: Dp = 360.dp

internal const val HoldMs = 550
private const val StartDelayMs = 500L
private const val LoopGapMs = 700L
private const val TakeoverIdleMs = 5_000L
