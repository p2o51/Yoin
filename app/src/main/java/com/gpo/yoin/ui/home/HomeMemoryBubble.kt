package com.gpo.yoin.ui.home

import android.content.Context
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.onClick
import com.gpo.yoin.ui.experience.rememberTopCutoutBounds
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.rememberExpressiveBackdropColors
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.experience.smoothstep
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

// ── The Memories speech bubble (owner 2026-10-04) ──────────────────────
//
// The header pill wasn't Expressive enough. The chevron goes back to the
// window's safe area — just below the camera cutout, or the page's top centre
// when there is none (or the cutout isn't over this page) — and a speech
// bubble hangs from it: a round, soft body running to the RIGHT of the arrow,
// its top-left corner rising into a rounded horn that holds the chevron
// (owner's sketch, 「向右，圆润一点」). It says only the latest memory's cover
// and title (「只要封面，标题就行」), on a wash of the cover's own colour.
//
// It speaks only when it has something to say (owner: 「有新内容出现，如果
// 很久不动也可以出现」): something written since it last spoke (per profile,
// remembered across launches), or the page left untouched for a while. Any
// touch elsewhere tucks it back into the arrow.

/** The body: a cover and a title on one line. */
private val BubbleBodyHeight = 54.dp
private val BubbleCoverSize = 32.dp
private val BubbleCoverInset = 12.dp
private val BubbleCoverTitleGap = 12.dp
private val BubbleTrail = 22.dp

/** The horn rising from the body's top-left corner; the chevron sits in it. */
private val BubbleHornHeight = 28.dp

/** The chevron's centre, from the bubble's top-left: over the cover, inside the horn. */
private val BubbleHornX = 30.dp
private val BubbleHornChevronY = 15.dp

// The horn's shape: its tip leans a little left of the chevron and rounds
// off (cap), its far side sweeps down into a concave flare.
private val BubbleHornLean = 5.dp
private val BubbleHornCap = 9.dp
private val BubbleHornReach = 30.dp
private val BubbleHornFlare = 16.dp
private val BubbleHornLift = 4.dp
private val BubbleTopDip = 2.dp
private val BubbleBellySag = 4.dp

/** Never narrower (the horn needs its flare) nor wider than these. */
private val BubbleMinWidth = 136.dp
private val BubbleMaxWidth = 300.dp
private val BubbleEdgeMargin = 12.dp

private val ArrowTouchSize = 48.dp
private val ArrowIconSize = 20.dp
private val ArrowBelowCutout = 2.dp
private val ArrowPullNudge = 2.dp

/** The arrow fades out over the first stretch of scroll — the pull only works at the top. */
private val ArrowScrollFade = 48.dp

/** "很久不动": the page left untouched this long, the bubble speaks (once per visit). */
internal const val MemoryBubbleIdleMs = 15_000L

/** Presence below this is tucked away: not drawn, not composed. */
private const val BubbleGoneBelow = 0.01f

/** News waits this long after the launch reveal lands. */
private const val MemoryBubbleNewsDelayMs = 450L

internal val LocalMemoryBubbleIdleMs = staticCompositionLocalOf { MemoryBubbleIdleMs }

// ── What the bubble remembers ──────────────────────────────────────────

/** The last [HomeMemoryPill.newsKey] the bubble spoke, per profile scope. */
internal interface MemoryBubbleSeenStore {
    fun lastSeen(scope: String): String?

    fun markSeen(scope: String, newsKey: String)
}

internal class SharedPrefsMemoryBubbleSeenStore(context: Context) : MemoryBubbleSeenStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun lastSeen(scope: String): String? = prefs.getString(KEY_PREFIX + scope, null)

    override fun markSeen(scope: String, newsKey: String) {
        prefs.edit { putString(KEY_PREFIX + scope, newsKey) }
    }

    private companion object {
        // Shared with the lyric idle hint: UI hints that remember they've spoken.
        const val PREFS_NAME = "yoin_ui_hints"
        const val KEY_PREFIX = "memory_bubble_seen:"
    }
}

internal class InMemoryMemoryBubbleSeenStore : MemoryBubbleSeenStore {
    private val seen = mutableMapOf<String, String>()

    override fun lastSeen(scope: String): String? = seen[scope]

    override fun markSeen(scope: String, newsKey: String) {
        seen[scope] = newsKey
    }
}

/** Null = the SharedPreferences store; the QA harness provides an in-memory one. */
internal val LocalMemoryBubbleSeenStore = staticCompositionLocalOf<MemoryBubbleSeenStore?> { null }

/** Whether the pill has anything to say: a latest memory or kept notes. */
internal fun HomeMemoryPill?.hasSomethingToSay(): Boolean =
    this != null && (latest != null || noteCount > 0)

/** A [HomeMemoryPill.newsKey] read back: (newest write time, note count); null = unreadable. */
internal fun parseMemoryNewsKey(key: String?): Pair<Long, Int>? {
    val parts = key?.split('#')?.takeIf { it.size == 2 } ?: return null
    val stamp = parts[0].toLongOrNull() ?: return null
    val notes = parts[1].toIntOrNull() ?: return null
    return stamp to notes
}

/**
 * News = something to say that this profile hasn't been told yet: a write
 * newer than the last one it spoke about, or more notes than it last saw.
 * Deleting a note or clearing a rating moves the key backwards — that is not
 * news (the caller quietly records the lower key).
 */
internal fun isMemoryNews(pill: HomeMemoryPill?, lastSeen: String?): Boolean {
    if (!pill.hasSomethingToSay() || pill!!.newsKey.isEmpty()) return false
    val now = parseMemoryNewsKey(pill.newsKey) ?: return pill.newsKey != lastSeen
    val seen = parseMemoryNewsKey(lastSeen) ?: return true
    return now.first > seen.first || now.second > seen.second
}

// ── Controller: touches and the header's free span ─────────────────────

/**
 * Shared between Home's root (which watches every press, [watchMemoryBubbleTouches]),
 * the header (which reports the span between the title and Settings), and the
 * overlay (which places the arrow and the bubble).
 */
@Stable
internal class MemoryBubbleController {
    internal val touches = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The bubble's and the arrow's bounds in the overlay's (= Home root's) space, written in placement. */
    internal var bubbleBounds: Rect = Rect.Zero
    internal var arrowBounds: Rect = Rect.Zero

    // What a tap on each does right now (the overlay keeps these current);
    // null = not tappable (a tucked bubble, a scrolled-away arrow).
    internal var onBubbleTap: (() -> Unit)? = null
    internal var onArrowTap: (() -> Unit)? = null

    internal fun tapTargetAt(position: Offset): (() -> Unit)? = when {
        onBubbleTap != null && bubbleBounds.contains(position) -> onBubbleTap
        onArrowTap != null && arrowBounds.contains(position) -> onArrowTap
        else -> null
    }

    /** The header's free span between the title and Settings, in WINDOW x; NaN = not placed yet. */
    internal var freeStartInWindow by mutableFloatStateOf(Float.NaN)
    internal var freeEndInWindow by mutableFloatStateOf(Float.NaN)

    /** The overlay's own window x (last placement), to bring the span into its space while measuring. */
    internal var overlayXInWindow by mutableFloatStateOf(Float.NaN)

}

/**
 * Home's root watches every pointer for the bubble, ahead of the feed
 * (Initial pass):
 *
 * - a press anywhere else, or a wheel / trackpad scroll, tucks the bubble and
 *   resets the idle clock;
 * - a press on the arrow or the speaking bubble is THEIR tap. They take no
 *   pointer input themselves — anything laid over the feed would cut drags
 *   that start on it — so the root decides: it consumes the down (the card
 *   underneath doesn't press, the long-press editor doesn't start; the
 *   list's scroll still takes the drag), and fires the tap on an up within
 *   touch slop. A drag that leaves the slop is the feed's — scroll, or pull
 *   Memories open — and tucks the bubble like any other.
 */
internal fun Modifier.watchMemoryBubbleTouches(controller: MemoryBubbleController?): Modifier =
    if (controller == null) {
        this
    } else {
        this
            .pointerInput(controller) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val tap = controller.tapTargetAt(down.position)
                    if (tap == null) {
                        controller.touches.tryEmit(Unit)
                        return@awaitEachGesture
                    }
                    down.consume()
                    val slop = viewConfiguration.touchSlop
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        if (!change.pressed) {
                            if ((change.position - down.position).getDistance() <= slop) {
                                change.consume()
                                tap()
                            }
                            return@awaitEachGesture
                        }
                        if ((change.position - down.position).getDistance() > slop) {
                            controller.touches.tryEmit(Unit)
                            return@awaitEachGesture
                        }
                    }
                }
            }
            .pointerInput(controller) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        // A wheel or trackpad scroll is activity too.
                        if (event.type == PointerEventType.Scroll) controller.touches.tryEmit(Unit)
                    }
                }
            }
    }

/**
 * What a tap on the arrow does right now: nothing once the feed has scrolled
 * it away, or while Home is being edited — the root's watcher then leaves
 * those downs to the feed (and to edit mode's own gestures).
 */
internal fun memoryArrowTap(scrolledAway: Boolean, editing: Boolean, tap: () -> Unit): (() -> Unit)? =
    if (scrolledAway || editing) null else tap

/** Put on the header's spacer between the title and Settings: the bubble stays inside it. */
internal fun Modifier.memoryBubbleFreeSpan(controller: MemoryBubbleController?): Modifier =
    if (controller == null) {
        this
    } else {
        onPlaced { coordinates ->
            val x = coordinates.positionInWindow().x
            controller.freeStartInWindow = x
            controller.freeEndInWindow = x + coordinates.size.width
        }
    }

// ── Where the arrow goes ───────────────────────────────────────────────

/**
 * The arrow's centre x in the overlay's space, and whether it sits under the
 * camera cutout. The bubble runs to the right of the arrow ([hornX] of it on
 * the left), inside the header's free span [freeStart, freeEnd] between the
 * title and Settings, at least [minWidth] wide: so the arrow goes under the
 * cutout when that leaves the bubble room, else the page's centre when that
 * does, else where the span's room is best (a corner punch-hole, or a page
 * without the cutout over it).
 */
internal fun memoryArrowCenterX(
    cutoutCenterX: Float?,
    width: Float,
    freeStart: Float,
    freeEnd: Float,
    hornX: Float,
    minWidth: Float,
): Pair<Float, Boolean> {
    fun fits(x: Float) = x - hornX >= freeStart && x - hornX + minWidth <= freeEnd
    cutoutCenterX?.takeIf(::fits)?.let { return it to true }
    val centre = width / 2f
    if (fits(centre) || freeEnd <= freeStart) return centre to false
    // Centre the bubble's minimum width in the span.
    return ((freeStart + freeEnd - minWidth) / 2f + hornX) to false
}

// ── The bubble's outline ───────────────────────────────────────────────

/**
 * The bubble: a soft body with a fully round right end, a belly that sags a
 * touch and a top edge that dips, whose left side flows up into a rounded
 * horn — its tip a little left of the chevron at [hornX] (px from the left),
 * its far side sweeping down into the top edge. Drawn inside [size].
 */
internal fun speechBubblePath(size: Size, hornX: Float, density: Density): Path = with(density) {
    val w = size.width
    val sag = BubbleBellySag.toPx()
    val h = size.height - sag * 0.75f // the belly's lowest point lands on the bottom edge
    val top = BubbleHornHeight.toPx().coerceAtMost(h / 2f)
    val body = h - top
    val r = (body / 2f).coerceAtMost(w / 4f)
    val cap = BubbleHornCap.toPx()
    val tip = (hornX - BubbleHornLean.toPx()).coerceAtLeast(cap * 1.2f)
    val reach = (hornX + BubbleHornReach.toPx()).coerceAtMost(w * 0.5f)
    val flare = BubbleHornFlare.toPx()
    val dip = BubbleTopDip.toPx()
    val lift = BubbleHornLift.toPx()

    Path().apply {
        moveTo(tip, 0f)
        // The horn's far side: level off the rounded tip, then sweep down.
        cubicTo(tip + cap * 1.2f, 0f, reach - flare, top - 1f, reach, top)
        // The top edge, dipping a touch, into the round right end.
        cubicTo(w * 0.55f, top + dip, w - r * 1.2f, top, w - r, top)
        cubicTo(w - r * 0.45f, top, w, top + body * 0.2f, w, top + body * 0.5f)
        cubicTo(w, h - body * 0.15f, w - r * 0.55f, h, w - r * 1.1f, h)
        // The belly.
        cubicTo(w * 0.62f, h + sag, w * 0.3f, h + sag, r, h)
        // The left end, flowing up into the horn and over its tip.
        cubicTo(r * 0.35f, h, 0f, h - body * 0.2f, 0f, top + body * 0.45f)
        cubicTo(0f, top + body * 0.05f - lift, tip - cap * 1.2f, 0f, tip, 0f)
        close()
    }
}

/** [speechBubblePath] as a [Shape]. */
private class SpeechBubbleShape(private val hornX: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(speechBubblePath(size, hornX, density))

    override fun equals(other: Any?): Boolean = other is SpeechBubbleShape && other.hornX == hornX

    override fun hashCode(): Int = hornX.hashCode()
}

// ── The overlay ────────────────────────────────────────────────────────

private enum class BubbleSlot { Arrow, Bubble }

private enum class BubbleReason { News, Idle }

/**
 * The Memories entry in [MemoryEntryStyle.Bubble]: the safe-area arrow and
 * its speech bubble, laid over the whole Home page (fill the page's root Box;
 * only the arrow and the bubble take taps, and drags pass through them).
 * [scrolledPx] is the feed's scroll distance (draw phase), [hintProgress] the
 * pull-to-Memories hint, [revealProgress] the once-per-process launch reveal.
 * [covered] = something above Home owns the screen (Now Playing, the detail
 * column): the bubble keeps quiet and tucks away. [editing] = Home is being
 * edited: the same, and the arrow takes no taps and fades out over the first
 * half of [editProgress] (draw phase).
 */
@Composable
internal fun MemoryBubbleOverlay(
    pill: HomeMemoryPill?,
    controller: MemoryBubbleController,
    hintProgress: () -> Float,
    revealProgress: () -> Float,
    scrolledPx: () -> Float,
    covered: Boolean,
    extractBackdropColors: Boolean,
    onOpenMemoryFocus: (sessionId: Long) -> Unit,
    onNavigateToMemories: () -> Unit,
    modifier: Modifier = Modifier,
    editProgress: () -> Float = { 0f },
    editing: Boolean = false,
) {
    val haptics = rememberYoinHaptics()
    val density = LocalDensity.current
    val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    val context = LocalContext.current
    val store = LocalMemoryBubbleSeenStore.current ?: remember(context) { SharedPrefsMemoryBubbleSeenStore(context) }
    val idleMs = LocalMemoryBubbleIdleMs.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentPill by rememberUpdatedState(pill)
    val currentHint by rememberUpdatedState(hintProgress)
    val currentReveal by rememberUpdatedState(revealProgress)
    val currentScrolled by rememberUpdatedState(scrolledPx)
    val currentCovered by rememberUpdatedState(covered || editing)
    val fadePx = with(density) { ArrowScrollFade.toPx() }
    // It may only speak where it can be read: Home on top, the feed at its
    // top (the arrow is there), nobody pulling Memories open.
    val canSpeak: () -> Boolean = {
        !currentCovered && currentScrolled() <= fadePx && currentHint() <= 0.01f
    }

    var reason by remember { mutableStateOf<BubbleReason?>(null) }
    val hasSomething = pill.hasSomethingToSay()
    if (!hasSomething && reason != null) reason = null

    // News: once the launch reveal has landed and it can be read (every time
    // Home resumes with news still untold), speak — and remember it was said.
    LaunchedEffect(pill?.scope, pill?.newsKey, store) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val news = currentPill ?: return@repeatOnLifecycle
            val lastSeen = store.lastSeen(news.scope)
            if (!isMemoryNews(news, lastSeen)) {
                // A deletion moved the key back: remember the lower one, so
                // the next real write still counts.
                if (news.newsKey.isNotEmpty() && news.newsKey != lastSeen) store.markSeen(news.scope, news.newsKey)
                return@repeatOnLifecycle
            }
            snapshotFlow { currentReveal() >= 1f }.first { it }
            delay(MemoryBubbleNewsDelayMs)
            snapshotFlow { canSpeak() }.first { it }
            reason = BubbleReason.News
            store.markSeen(news.scope, news.newsKey)
        }
    }
    // "很久不动": untouched for idleMs where it can be read, speak — once per visit.
    LaunchedEffect(hasSomething, idleMs) {
        if (!hasSomething) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                snapshotFlow { canSpeak() }.first { it }
                val touched = withTimeoutOrNull(idleMs) { controller.touches.first() }
                if (touched == null && reason == null && canSpeak()) {
                    reason = BubbleReason.Idle
                    awaitCancellation()
                }
            }
        }
    }
    // Any touch elsewhere, a pull toward Memories, the feed scrolling away or
    // something covering Home tucks it back.
    LaunchedEffect(controller) {
        launch { controller.touches.collect { reason = null } }
        snapshotFlow { canSpeak() }.filter { !it }.collect { reason = null }
    }

    val speaking = reason != null
    val presence = remember { Animatable(0f) }
    // Out of the arrow with the Expressive bounce; back in without one.
    val outSpring = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    val inSpring = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Standard)
    LaunchedEffect(speaking, reduced) {
        val target = if (speaking) 1f else 0f
        when {
            reduced -> Unit
            speaking -> presence.animateTo(target, outSpring)
            else -> presence.animateTo(target, inSpring)
        }
        // Land exactly: a spring settles within its visibility threshold,
        // and a bubble left at 0.004 would linger as a ghost.
        presence.snapTo(target)
    }
    val composedBubble by remember { derivedStateOf { reason != null || presence.value > BubbleGoneBelow } }
    val tapArrow: () -> Unit = {
        haptics.performContextClick()
        reason = null
        onNavigateToMemories()
    }
    val tapBubble: () -> Unit = {
        haptics.performContextClick()
        reason = null
        val session = pill?.latest?.sessionId
        if (session != null) onOpenMemoryFocus(session) else onNavigateToMemories()
    }

    val cutout = rememberTopCutoutBounds()
    val statusBarTop = WindowInsets.statusBars.getTop(density)
    val scrolledAway by remember { derivedStateOf { scrolledPx() > with(density) { ArrowScrollFade.toPx() } } }
    // The root routes taps (watchMemoryBubbleTouches): keep it told what each does now.
    SideEffect {
        controller.onArrowTap = memoryArrowTap(scrolledAway = scrolledAway, editing = editing, tap = tapArrow)
        // A bubble still tucking away as edit mode starts claims nothing either.
        controller.onBubbleTap = if (speaking && !editing) tapBubble else null
    }

    Layout(
        modifier = modifier.onPlaced { coordinates ->
            controller.overlayXInWindow = coordinates.positionInWindow().x
        },
        content = {
            MemoryArrow(
                speaking = { presence.value },
                hintProgress = hintProgress,
                scrolledPx = scrolledPx,
                editProgress = editProgress,
                // Editing, it is gone for readers too: Memories never opens
                // over edit mode.
                enabled = !scrolledAway && !editing,
                onClick = tapArrow,
                modifier = Modifier.layoutId(BubbleSlot.Arrow),
            )
            if (composedBubble) {
                MemoryBubble(
                    pill = pill,
                    speaking = speaking,
                    presence = { presence.value },
                    reduced = reduced,
                    extractBackdropColors = extractBackdropColors,
                    onClick = tapBubble,
                    modifier = Modifier.layoutId(BubbleSlot.Bubble),
                )
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
        val touch = ArrowTouchSize.roundToPx()
        val arrow = measurables.first { it.layoutId == BubbleSlot.Arrow }.measure(Constraints.fixed(touch, touch))
        val margin = BubbleEdgeMargin.toPx()
        // The header's free span and the cutout, brought into this overlay's
        // space (window x's from the last placement; they only move when the
        // page's width does, and a write re-measures).
        val overlayX = controller.overlayXInWindow.takeIf { !it.isNaN() }
        val freeStart = overlayX?.let { x -> controller.freeStartInWindow.takeIf { !it.isNaN() }?.minus(x) }
            ?.coerceAtLeast(margin) ?: margin
        val freeEnd = overlayX?.let { x -> controller.freeEndInWindow.takeIf { !it.isNaN() }?.minus(x) }
            ?.coerceAtMost(width - margin) ?: (width - margin)
        val hornX = BubbleHornX.toPx()
        val (arrowX, underCutout) = memoryArrowCenterX(
            cutoutCenterX = cutout?.let { it.center.x - (overlayX ?: 0f) },
            width = width.toFloat(),
            freeStart = freeStart,
            freeEnd = freeEnd,
            hornX = hornX,
            minWidth = BubbleMinWidth.toPx(),
        )
        // The bubble runs right from the arrow, as far as the span allows.
        val bubbleLeft = arrowX - hornX
        val room = (freeEnd - bubbleLeft).coerceAtLeast(0f)
        val bubble = measurables.firstOrNull { it.layoutId == BubbleSlot.Bubble }?.measure(
            Constraints(
                minWidth = minOf(BubbleMinWidth.toPx(), room).roundToInt(),
                maxWidth = minOf(BubbleMaxWidth.toPx(), room).roundToInt(),
            ),
        )
        layout(width, height) {
            val origin = coordinates?.positionInWindow() ?: Offset.Zero
            val iconHalf = ArrowIconSize.toPx() / 2f
            val arrowCenterY = if (underCutout && cutout != null) {
                cutout.bottom - origin.y + ArrowBelowCutout.toPx() + iconHalf
            } else {
                statusBarTop - origin.y + ArrowBelowCutout.toPx() + iconHalf
            }
            if (bubble != null) {
                val top = arrowCenterY - BubbleHornChevronY.toPx()
                controller.bubbleBounds = Rect(bubbleLeft, top, bubbleLeft + bubble.width, top + bubble.height)
                bubble.place(bubbleLeft.roundToInt(), top.roundToInt())
            } else {
                controller.bubbleBounds = Rect.Zero
            }
            val arrowLeft = (arrowX - touch / 2f).roundToInt()
            val arrowTop = (arrowCenterY - touch / 2f).roundToInt()
            controller.arrowBounds = Rect(
                arrowLeft.toFloat(),
                arrowTop.toFloat(),
                (arrowLeft + touch).toFloat(),
                (arrowTop + touch).toFloat(),
            )
            arrow.place(arrowLeft, arrowTop)
        }
    }
}

/**
 * The safe-area chevron: always there, answering the pull, fading as the feed
 * scrolls away and over the first half of Home's edit progress.
 */
@Composable
private fun MemoryArrow(
    speaking: () -> Float,
    hintProgress: () -> Float,
    scrolledPx: () -> Float,
    editProgress: () -> Float,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clearAndSetSemantics {
                // Scrolled away it is gone for everyone, readers included.
                // (Pointer taps are routed by Home's root, never here.)
                if (enabled) {
                    role = Role.Button
                    contentDescription = "Memories"
                    onClick {
                        onClick()
                        true
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = YoinSymbols.ChevronDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(ArrowIconSize)
                .graphicsLayer {
                    val hint = hintProgress()
                    val fade = (1f - scrolledPx() / ArrowScrollFade.toPx()).coerceIn(0f, 1f)
                    translationY = hint * ArrowPullNudge.toPx()
                    // Inside a speaking bubble the arrow is part of it — full ink.
                    val rest = 0.62f + (1f - 0.62f) * maxOf(hint, speaking().coerceIn(0f, 1f))
                    alpha = rest * fade * (1f - smoothstep(0f, 0.5f, editProgress()))
                },
        )
    }
}

/** The bubble: grows out of the arrow on presence 0 → 1; the latest memory's cover and title. */
@Composable
private fun MemoryBubble(
    pill: HomeMemoryPill?,
    speaking: Boolean,
    presence: () -> Float,
    reduced: Boolean,
    extractBackdropColors: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    // While it tucks away it keeps saying what it said.
    val shown = rememberLastNonNull(pill?.takeIf { it.hasSomethingToSay() })
    val latest = shown?.latest
    val backdrop = rememberExpressiveBackdropColors(
        model = latest?.coverArtUrl,
        fallbackBaseColor = colors.secondary,
        fallbackAccentColor = colors.tertiary,
        enabled = extractBackdropColors && latest != null,
    )
    // Floating over the feed, the body is always opaque: the raised container
    // washed with the cover's own colour (the bento's direct lerp).
    val raised = colors.surfaceContainerHigh
    val fill by animateColorAsState(
        targetValue = if (latest != null) lerp(raised, backdrop.baseColor, 0.30f) else raised,
        animationSpec = if (reduced) snap() else YoinMotion.effectsSpring(),
        label = "memoryBubbleFill",
    )
    val hornX = with(density) { BubbleHornX.toPx() }
    val shape = remember(hornX) { SpeechBubbleShape(hornX) }
    val description = latest?.let { "Memories. Latest: ${it.albumName}" + (it.artistName?.let { a -> " by $a" } ?: "") }
        ?: "Memories"

    Layout(
        modifier = modifier
            .clearAndSetSemantics {
                // Tucking away it is already gone for readers. (Pointer taps
                // are routed by Home's root, never here.)
                if (speaking) {
                    role = Role.Button
                    contentDescription = description
                    onClick {
                        onClick()
                        true
                    }
                }
            }
            .graphicsLayer {
                val p = presence().let { if (it < BubbleGoneBelow) 0f else it }
                // Grows out of the horn that holds the arrow.
                val origin = if (size.width > 0f) (hornX / size.width).coerceIn(0f, 1f) else 0f
                transformOrigin = TransformOrigin(origin, 0f)
                scaleX = 0.35f + 0.65f * p
                scaleY = 0.2f + 0.8f * p
                alpha = p.coerceIn(0f, 1f)
                this.shape = shape
                shadowElevation = 3.dp.toPx() * p.coerceIn(0f, 1f)
                clip = false
            }
            .drawWithCache {
                val path = speechBubblePath(size, hornX, this)
                onDrawBehind { drawPath(path, color = fill) }
            },
        content = {
            if (latest != null) {
                PillCover(
                    latest = latest,
                    sticker = false,
                    ink = Color.Unspecified,
                    stickerBacking = fill,
                    alpha = { 1f },
                    size = BubbleCoverSize,
                )
                Text(
                    text = latest.albumName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = "Memories",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
    ) { measurables, constraints ->
        val horn = BubbleHornHeight.roundToPx()
        val body = BubbleBodyHeight.roundToPx()
        val inset = BubbleCoverInset.roundToPx()
        val gap = BubbleCoverTitleGap.roundToPx()
        val trail = BubbleTrail.roundToPx()
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val cover = if (measurables.size > 1) measurables[0].measure(Constraints()) else null
        val start = if (cover != null) inset + cover.width + gap else inset + gap
        val title = measurables.last().measure(Constraints(maxWidth = (maxWidth - start - trail).coerceAtLeast(0)))
        val width = (start + title.width + trail).coerceIn(constraints.minWidth, maxWidth)
        val height = horn + body + BubbleBellySag.roundToPx()
        layout(width, height) {
            cover?.placeRelative(inset, horn + (body - cover.height) / 2)
            title.placeRelative(start, horn + (body - title.height) / 2)
        }
    }
}
