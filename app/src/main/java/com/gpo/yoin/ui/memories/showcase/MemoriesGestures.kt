package com.gpo.yoin.ui.memories.showcase

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
 *  · top bar, up (either state) → q, the retreat to Home, on the bar rule (56dp / 450dp/s);
 *  · top bar, down, diary open → p, the diary's handle: 1:1, the scroll frozen where it is;
 *  · card body, up → q on the body rule (112dp / 600dp/s);
 *  · card body, down → p's rubber band below the card (0.3×, −90dp), capped at the card;
 *  · diary body → the diary's own scroll (pull past its top feeds p through [MemoriesDiaryDeck]'s nested
 *    scroll), except a NEW upward drag that starts with the diary already resting at its end: that one is
 *    q on the body rule, 1:1 with the rubber band. A fling that reaches the end only stops there.
 *
 * q is [RevealState] and p is [MemoriesDiaryState]: the router only feeds them, it owns no displacement of
 * its own. A routed drag consumes its moves in the Initial pass, so the pager, the diary scroll and every
 * button under the finger stand down for that gesture; the diary-scroll route consumes nothing.
 *
 * Thresholds count from finger-down (56 / 112dp of FINGER travel commit), but the page only follows the
 * finger from the moment the axis locks: the slop travel is never applied as a jump. The release rule is
 * shortened by the travel the slop ate instead.
 */

/** Where a vertical drag started. */
enum class MemoriesDragZone { Bar, Card, Diary }

/** The axis a drag locks to once it has travelled the touch slop (prototype: 8dp, |dx| > |dy| = page). */
enum class MemoriesDragAxis { Horizontal, Vertical }

/** Which controller a vertical drag feeds, decided once at slop. */
sealed interface MemoriesVerticalRoute {
    /** Whether the router takes the drag's moves (false: the diary's own scroll handles them). */
    val consumes: Boolean get() = true

    /** The outer q: retreat to Home. [fromBar] picks the bar rule, else the card body's. */
    data class Dismiss(val fromBar: Boolean) : MemoriesVerticalRoute

    /** The card's own pull-down: p below 0 on its rubber band, never past the card. */
    data object CardRubberBand : MemoriesVerticalRoute

    /** The diary's handle: a pull down on the top bar scrubs p 1:1 (no band, the scroll frozen). */
    data object DiaryHandle : MemoriesVerticalRoute

    /** The diary's text scrolls; its nested scroll hands an overflow past the top to p. */
    data object DiaryScroll : MemoriesVerticalRoute {
        override val consumes: Boolean get() = false
    }
}

/**
 * The live diary as the router sees it at finger-down (implemented by [MemoriesDiaryDeck]). Read in the
 * Initial pass, before the diary's scroll stops a running fling: a fling still running is never "at the end".
 */
interface MemoriesDiaryProbe {
    /** A finger went down: a new gesture (resets the diary's pull bookkeeping). */
    fun onFingerDown()

    /** The open diary rests at its very end (no fling running, p fully open). */
    fun atEnd(): Boolean
}

/** Null until the drag has travelled [slopPx]; then the axis, ties going vertical (prototype). */
internal fun decideDragAxis(dx: Float, dy: Float, slopPx: Float): MemoriesDragAxis? = when {
    hypot(dx, dy) < slopPx -> null
    abs(dx) > abs(dy) -> MemoriesDragAxis.Horizontal
    else -> MemoriesDragAxis.Vertical
}

/**
 * The controller for a vertical drag from [zone] whose travel at slop is [deltaY] (negative = up).
 * - Bar: Home, except a pull down while the diary is open ([diaryLevel]), which is the diary's handle (on the
 *   card q is hard-clamped at open, so a bar pull down there is a no-op).
 * - Diary: its own scroll, except an upward drag that started at the end ([diaryAtEnd]): Home, body rule.
 * - Card: up = Home; down = the card's rubber band. Without a card face ([cardPresent] false: loading, empty,
 *   error) the whole page is the body and every vertical drag is q.
 */
internal fun routeVerticalDrag(
    zone: MemoriesDragZone,
    deltaY: Float,
    cardPresent: Boolean,
    diaryLevel: Boolean = false,
    diaryAtEnd: Boolean = false,
): MemoriesVerticalRoute = when {
    zone == MemoriesDragZone.Bar && diaryLevel && deltaY > 0f -> MemoriesVerticalRoute.DiaryHandle
    zone == MemoriesDragZone.Bar -> MemoriesVerticalRoute.Dismiss(fromBar = true)
    zone == MemoriesDragZone.Diary && diaryAtEnd && deltaY < 0f -> MemoriesVerticalRoute.Dismiss(fromBar = false)
    zone == MemoriesDragZone.Diary -> MemoriesVerticalRoute.DiaryScroll
    deltaY < 0f || !cardPresent -> MemoriesVerticalRoute.Dismiss(fromBar = false)
    else -> MemoriesVerticalRoute.CardRubberBand
}

/** The release rule of a dismiss route. */
internal fun MemoriesDismissRules.ruleFor(route: MemoriesVerticalRoute.Dismiss): DismissRule =
    if (route.fromBar) bar else body

/**
 * [rule] for a drag whose page started following the finger only after [slopUpPx] of upward travel (the
 * lock): the commit distance stays a finger distance from touch-down, so the page needs that much less.
 */
internal fun DismissRule.afterSlop(slopUpPx: Float): DismissRule =
    copy(commitPx = (commitPx - slopUpPx).coerceAtLeast(0f))

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

    /** The live diary (the deck's current page); null without a deck. */
    var diaryProbe: MemoriesDiaryProbe? = null

    /** Runs once a release commits the retreat, after the spring lands (the host closes Memories). */
    var onDismissed: () -> Unit = {}

    /** Runs the moment a release commits (the confirm haptic). */
    var onCommitted: () -> Unit = {}

    /** Debug builds' trace: one line per locked drag and release. Null in the product. */
    var debugLog: ((String) -> Unit)? = null

    private var root: LayoutCoordinates? = null

    /** A committed retreat rides out untouched: drags are ignored until it lands. */
    private var committing = false

    /** The diary rested at its end when the current finger went down. */
    private var diaryAtEndAtDown = false

    /** Upward travel the slop ate before the current drag locked (negative = it locked going down). */
    private var slopUpPx = 0f

    /**
     * The commit distance of whatever drives q now, as page travel (56dp bar, 112dp body and back, less the
     * slop the lock ate); the retreating page's bottom corners are full exactly there. Read only in the
     * corner layer.
     */
    var cornerThresholdPx by mutableFloatStateOf(rules.body.commitPx)
        private set

    /**
     * A card-level system back is previewing (prototype `busyQ`): from its first frame until it commits, or
     * its cancel spring lands. The award holds while it is true. Snapshot state.
     */
    var backBusy by mutableStateOf(false)
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
        backBusy = true
    }

    /** The card-level back committed, or its cancel spring is over (onCardBackFinished). */
    fun onBackFinished() {
        backBusy = false
    }

    internal fun zoneAt(y: Float): MemoriesDragZone = when {
        y < barBottomPx -> MemoriesDragZone.Bar
        cardPresent && diary.isDiaryLevel -> MemoriesDragZone.Diary
        else -> MemoriesDragZone.Card
    }

    internal fun press() {
        awards?.onFingerDown()
        val probe = diaryProbe
        diaryAtEndAtDown = probe?.atEnd() ?: false
        probe?.onFingerDown()
    }

    internal fun lift() {
        awards?.onFingerUp(SystemClock.uptimeMillis())
    }

    internal fun beginHorizontal() {
        awards?.onDragStart()
    }

    /**
     * The drag locked vertical from [zone] with [deltaY] of travel since touch-down. That travel is not
     * applied (the page follows from here on), but q's commit distance still counts it.
     */
    internal fun begin(zone: MemoriesDragZone, deltaY: Float): MemoriesVerticalRoute {
        val route = routeVerticalDrag(
            zone = zone,
            deltaY = deltaY,
            cardPresent = cardPresent,
            diaryLevel = diary.isDiaryLevel,
            diaryAtEnd = diaryAtEndAtDown,
        )
        slopUpPx = -deltaY
        when (route) {
            is MemoriesVerticalRoute.Dismiss ->
                cornerThresholdPx = rules.ruleFor(route).afterSlop(slopUpPx).commitPx
            MemoriesVerticalRoute.CardRubberBand -> diary.startPull(banded = false, fromScrolled = false, ceiling = 0f)
            MemoriesVerticalRoute.DiaryHandle -> diary.startPull(banded = false, fromScrolled = false)
            MemoriesVerticalRoute.DiaryScroll -> Unit
        }
        awards?.onDragStart()
        debugLog?.invoke("lock $route from $zone")
        return route
    }

    /** [deltaY] in px, positive = finger moving down. */
    internal fun drag(route: MemoriesVerticalRoute, deltaY: Float) {
        when (route) {
            is MemoriesVerticalRoute.Dismiss -> if (!committing) reveal.dragBy(deltaY, heightPx)
            MemoriesVerticalRoute.CardRubberBand, MemoriesVerticalRoute.DiaryHandle -> diary.pullBy(deltaY)
            MemoriesVerticalRoute.DiaryScroll -> Unit
        }
    }

    /** [velocityY] in px/s, positive = finger moving down. */
    internal fun release(route: MemoriesVerticalRoute, velocityY: Float) {
        debugLog?.let { log ->
            val q = (reveal.fraction * heightPx).toInt()
            val slop = slopUpPx.toInt()
            log("release $route · q ${q}px (+slop $slop) · p ${diary.fraction} · v ${velocityY.toInt()}px/s")
        }
        when (route) {
            is MemoriesVerticalRoute.Dismiss -> {
                // q may still be 0 when the lock came on the last move (a flick): its speed decides
                if (committing) return
                val rule = rules.ruleFor(route).afterSlop(slopUpPx)
                scope.launch {
                    try {
                        val target = reveal.settleDismiss(
                            velocityPxPerSec = velocityY,
                            containerPx = heightPx,
                            rule = rule,
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
            MemoriesVerticalRoute.CardRubberBand, MemoriesVerticalRoute.DiaryHandle ->
                scope.launch { diary.releasePull(velocityY) }
            MemoriesVerticalRoute.DiaryScroll -> Unit
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
 * locks the axis at slop, and from then on consumes a routed vertical drag's moves so nothing under the
 * finger (the pager, the diary scroll, a button) acts on it; a horizontal drag is left to the pager and a
 * diary-scroll drag to the diary. Velocity is sampled for the release. The page follows the finger from the
 * lock on — the slop travel is never applied as a jump (the release rule counts it instead).
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
                    if (route?.consumes == true) change.consume()
                    break
                }
                val delta = change.positionChange()
                val locked = route
                when {
                    locked != null -> {
                        router.drag(locked, delta.y)
                        if (locked.consumes) change.consume()
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
                                // the travel so far is not applied: the page follows from the lock on,
                                // and the commit distance counts it (begin)
                                val started = router.begin(zone, total.y)
                                route = started
                                if (started.consumes) change.consume()
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
