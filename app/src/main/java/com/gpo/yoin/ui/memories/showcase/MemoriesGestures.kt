package com.gpo.yoin.ui.memories.showcase

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.LayoutCoordinates
import com.gpo.yoin.ui.experience.DismissRule
import com.gpo.yoin.ui.experience.RevealState
import com.gpo.yoin.ui.memories.award.MemoriesAwardLifecycle
import com.gpo.yoin.ui.navigation.back.MemoriesDismissRules
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * Memories' one gesture router (twostate4 `pointerdown` / `pointermove` / `up`). Every touch on the page goes
 * through it; at slop it picks ONE axis and, for a vertical drag, ONE controller — never two:
 *
 *  · horizontal → the deck's HorizontalPager (the router only pauses the award's beats);
 *  · top bar, either way → q, the retreat to Home, on the bar rule (56dp / 450dp/s);
 *  · card body, up → q on the body rule (112dp / 600dp/s);
 *  · card body, down → p's rubber band below the card (0.3×, −90dp), capped at the card.
 *
 * q is [RevealState] and p is [MemoriesDiaryState] (P2): the router only feeds them, it owns no displacement
 * of its own. A vertical drag consumes its moves in the Initial pass, so the pager and every button under the
 * finger stand down for that gesture.
 */

/** Where a vertical drag started. The diary zone arrives with the diary (P5b). */
enum class MemoriesDragZone { Bar, Card }

/** The axis a drag locks to once it has travelled the touch slop (prototype: 8dp, |dx| > |dy| = page). */
enum class MemoriesDragAxis { Horizontal, Vertical }

/** Which controller a vertical drag feeds, decided once at slop. */
sealed interface MemoriesVerticalRoute {
    /** The outer q: retreat to Home. [fromBar] picks the bar rule, else the card body's. */
    data class Dismiss(val fromBar: Boolean) : MemoriesVerticalRoute

    /** The card's own pull-down: p below 0 on its rubber band, never past the card. */
    data object CardRubberBand : MemoriesVerticalRoute
}

/** Null until the drag has travelled [slopPx]; then the axis, ties going vertical (prototype). */
internal fun decideDragAxis(dx: Float, dy: Float, slopPx: Float): MemoriesDragAxis? = when {
    hypot(dx, dy) < slopPx -> null
    abs(dx) > abs(dy) -> MemoriesDragAxis.Horizontal
    else -> MemoriesDragAxis.Vertical
}

/**
 * The controller for a vertical drag from [zone] whose travel at slop is [deltaY] (negative = up).
 * The bar always means Home (a pull down there is the diary's handle in P5b; on the card q is hard-clamped
 * at open, so it is a no-op). Without a card face ([cardPresent] false: loading, empty, error) the whole
 * page is the body and every vertical drag is q.
 */
internal fun routeVerticalDrag(zone: MemoriesDragZone, deltaY: Float, cardPresent: Boolean): MemoriesVerticalRoute =
    when {
        zone == MemoriesDragZone.Bar -> MemoriesVerticalRoute.Dismiss(fromBar = true)
        deltaY < 0f || !cardPresent -> MemoriesVerticalRoute.Dismiss(fromBar = false)
        else -> MemoriesVerticalRoute.CardRubberBand
    }

/** The release rule of a dismiss route. */
internal fun MemoriesDismissRules.ruleFor(route: MemoriesVerticalRoute.Dismiss): DismissRule =
    if (route.fromBar) bar else body

/**
 * The router's state: the page geometry it reads at gesture time (plain fields, written from placement and
 * never read in composition) and the two controllers it feeds. Releases settle on [scope], an outer scope,
 * so a settle outlives the gesture coroutine (invariant 7).
 */
@Stable
class MemoriesGestureRouter internal constructor(
    private val reveal: RevealState,
    private val diary: MemoriesDiaryState,
    private val rules: MemoriesDismissRules,
    private val scope: CoroutineScope,
) {
    /** The page height in px: q's travel. */
    var heightPx = 0f

    /** Bottom of the top bar in page px; 0 = no bar (the whole page is card body). */
    var barBottomPx = 0f
        private set

    /** A card face is on screen to rubber-band (Content state). */
    var cardPresent = false

    /** The award lifecycle of this open, told about presses, lifts and drags; null in previews. */
    var awards: MemoriesAwardLifecycle? = null

    /** Runs once a release commits the retreat, after the spring lands (the host closes Memories). */
    var onDismissed: () -> Unit = {}

    /** Runs the moment a release commits (the confirm haptic). */
    var onCommitted: () -> Unit = {}

    /** Debug builds' trace: one line per locked drag and release. Null in the product. */
    var debugLog: ((String) -> Unit)? = null

    private var root: LayoutCoordinates? = null

    /** A committed retreat rides out untouched: drags are ignored until it lands. */
    private var committing = false

    /**
     * The commit distance of whatever drives q now (56dp bar, 112dp body and back); the retreating page's
     * bottom corners are full exactly there. Read only in the corner layer.
     */
    var cornerThresholdPx by mutableFloatStateOf(rules.body.commitPx)
        private set

    fun onRootPlaced(coordinates: LayoutCoordinates) {
        root = coordinates
        heightPx = coordinates.size.height.toFloat()
    }

    /** The top bar was placed: its bottom edge, in page px, splits the bar zone from the card body. */
    fun onBarPlaced(bar: LayoutCoordinates) {
        val page = root?.takeIf { it.isAttached } ?: return
        if (!bar.isAttached) return
        barBottomPx = page.localPositionOf(bar, Offset(0f, bar.size.height.toFloat())).y
    }

    /** The content state left: no bar, no card face. */
    fun onContentGone() {
        barBottomPx = 0f
        cardPresent = false
    }

    /** System back drives q on the body rule's corner (MemoriesPredictiveBack's onCardBackStarted). */
    fun onBackStarted() {
        cornerThresholdPx = rules.body.commitPx
    }

    internal fun zoneAt(y: Float): MemoriesDragZone =
        if (y < barBottomPx) MemoriesDragZone.Bar else MemoriesDragZone.Card

    internal fun press() {
        awards?.onFingerDown()
    }

    internal fun lift() {
        awards?.onFingerUp(SystemClock.uptimeMillis())
    }

    internal fun beginHorizontal() {
        awards?.onDragStart()
    }

    internal fun begin(zone: MemoriesDragZone, deltaY: Float): MemoriesVerticalRoute {
        val route = routeVerticalDrag(zone, deltaY, cardPresent)
        when (route) {
            is MemoriesVerticalRoute.Dismiss -> cornerThresholdPx = rules.ruleFor(route).commitPx
            MemoriesVerticalRoute.CardRubberBand -> diary.startPull(banded = false, fromScrolled = false, ceiling = 0f)
        }
        awards?.onDragStart()
        debugLog?.invoke("lock $route from $zone")
        return route
    }

    /** [deltaY] in px, positive = finger moving down. */
    internal fun drag(route: MemoriesVerticalRoute, deltaY: Float) {
        when (route) {
            is MemoriesVerticalRoute.Dismiss -> if (!committing) reveal.dragBy(deltaY, heightPx)
            MemoriesVerticalRoute.CardRubberBand -> diary.pullBy(deltaY)
        }
    }

    /** [velocityY] in px/s, positive = finger moving down. */
    internal fun release(route: MemoriesVerticalRoute, velocityY: Float) {
        debugLog?.let { log ->
            val q = (reveal.fraction * heightPx).toInt()
            log("release $route · q ${q}px · p ${diary.fraction} · v ${velocityY.toInt()}px/s")
        }
        when (route) {
            is MemoriesVerticalRoute.Dismiss -> {
                if (committing || reveal.fraction <= 0f) return
                scope.launch {
                    try {
                        val target = reveal.settleDismiss(
                            velocityPxPerSec = velocityY,
                            containerPx = heightPx,
                            rule = rules.ruleFor(route),
                            onCommit = {
                                committing = true
                                awards?.onDismissCommitted()
                                onCommitted()
                            },
                        )
                        if (target >= 1f) onDismissed()
                    } finally {
                        committing = false
                    }
                }
            }
            MemoriesVerticalRoute.CardRubberBand -> scope.launch { diary.releasePull(velocityY) }
        }
    }
}

@Composable
internal fun rememberMemoriesGestureRouter(
    reveal: RevealState,
    diary: MemoriesDiaryState,
    rules: MemoriesDismissRules,
): MemoriesGestureRouter {
    val scope = rememberCoroutineScope()
    return remember(reveal, diary, rules, scope) { MemoriesGestureRouter(reveal, diary, rules, scope) }
}

/**
 * The page's one gesture input (put it on the Memories root). It watches every touch in the Initial pass,
 * locks the axis at slop, and from then on consumes a vertical drag's moves so nothing under the finger
 * (the pager, a button) acts on it; a horizontal drag is left to the pager. Velocity is sampled for the
 * release. On the frame the axis locks the whole travel since touch-down is applied (as the prototype's
 * `q0 − dy/H`), so the page stays under the finger and the dp commit distances are finger distances.
 */
internal fun Modifier.memoriesGestures(router: MemoriesGestureRouter): Modifier = pointerInput(router) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        router.press()
        val zone = router.zoneAt(down.position.y)
        val slop = viewConfiguration.touchSlop
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(down)
        var pointer = down.id
        var route: MemoriesVerticalRoute? = null
        var horizontal = false
        var total = Offset.Zero
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == pointer }
                    ?: event.changes.firstOrNull { it.pressed }?.also { pointer = it.id }
                    ?: break
                tracker.addPointerInputChange(change)
                if (!change.pressed) {
                    if (route != null) change.consume()
                    break
                }
                val delta = change.positionChange()
                val locked = route
                when {
                    locked != null -> {
                        router.drag(locked, delta.y)
                        change.consume()
                    }
                    horizontal -> Unit
                    else -> {
                        total += delta
                        when (decideDragAxis(total.x, total.y, slop)) {
                            MemoriesDragAxis.Horizontal -> {
                                horizontal = true
                                router.beginHorizontal()
                            }
                            MemoriesDragAxis.Vertical -> {
                                val started = router.begin(zone, total.y)
                                route = started
                                // the whole travel since touch-down, slop included (prototype q0 − dy/H):
                                // the page sits under the finger and 56 / 112dp of finger is the commit
                                router.drag(started, total.y)
                                change.consume()
                            }
                            null -> Unit
                        }
                    }
                }
            }
        } finally {
            router.lift()
            route?.let { router.release(it, tracker.calculateVelocity().y) }
        }
    }
}
