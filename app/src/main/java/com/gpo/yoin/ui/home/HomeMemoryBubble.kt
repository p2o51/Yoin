package com.gpo.yoin.ui.home

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.YoinArmTransform
import com.gpo.yoin.ui.component.YoinMark
import com.gpo.yoin.ui.component.rememberExpressiveBackdropColors
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.rememberTopCutoutBounds
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.experience.smoothstep
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

// ── The Memories speech bubble (owner 2026-10-04, re-cut 2026-10-09) ────
//
// The chevron lives in the window's safe area — just below the camera cutout,
// or the page's top centre when there is none (or the cutout isn't over this
// page). When the bubble speaks, the header's title turns into the latest
// memory (owner's Figma prototype, file 88nMgCqBFkNPAyBjvtYE9f node 766:465):
//
// 1. the Yoin mark blooms at the title's left and pushes the two lines right;
// 2. it rolls right, and the bubble unrolls behind it from the title's place —
//    a plain rounded body whose tail rises at the end to hold the chevron at
//    the centre of its round tip; the mark lands on the body's bottom-right
//    corner. Tucking away runs it all backwards.
//
// It says only the latest memory's cover and title (「只要封面，标题就行」), on
// a wash of the cover's own colour. It speaks only when it has something to
// say (owner: 「有新内容出现，如果很久不动也可以出现」): something written since
// it last spoke (per profile, remembered across launches), or the page left
// untouched for a while. Any touch elsewhere tucks it back.

/** The body: a cover and a title on one line, where the header's title was. */
private val BubbleBodyHeight = 60.dp
/** Fully round ends (owner 10-09, option B). */
private val BubbleCorner = 30.dp
private val BubbleCoverSize = 40.dp
private val BubbleCoverInset = 15.dp
private val BubbleCoverTitleGap = 12.dp

/** The title stops clear of the mark sitting on the corner. */
private val BubbleTrail = 48.dp

/**
 * The tail: a sharp tip this far above the chevron (it may run up into the
 * status bar), the chevron this far above the body's top, and each flank's
 * base this far to either side of the chevron (owner 10-09: 左右对称).
 */
private val BubbleTipAboveChevron = 26.dp
private val BubbleChevronAboveBody = 10.dp
private val BubbleTailFlank = 46.dp

/** How the flanks leave the tip: steeply, a little out to each side. */
private val BubbleTipLean = 6.dp
private val BubbleTipDrop = 18.dp

/** The body ends this far right of the chevron: the right flank's base, then the round corner. */
private val BubbleReach = 80.dp

/** Chevron to the body's left end, at least: room for the cover and some title. */
private val BubbleMinLeft = 150.dp
private val BubbleMaxWidth = 440.dp
private val BubbleEdgeMargin = 12.dp

/** The mark that says it: beside the title first, then on the body's bottom-right corner. */
private val BubbleMarkSize = 44.dp

/** It squeezes in from this far left as it blooms. */
private val BubbleMarkSqueeze = 16.dp
private val BubbleMarkTitleGap = 12.dp
private val BubbleMarkCornerInsetX = 20.dp
private val BubbleMarkCornerInsetY = 10.dp

/** Before the header has reported its title (the first frame). */
private val TitleFallbackLeft = 16.dp
private val TitleFallbackTop = 12.dp
private val TitleFallbackHeight = 60.dp

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

    /** The overlay's own window position (last placement), to bring the header into its space while measuring. */
    internal var overlayXInWindow by mutableFloatStateOf(Float.NaN)
    internal var overlayYInWindow by mutableFloatStateOf(Float.NaN)

    /** The header title's window bounds ([memoryBubbleTitle]); NaN = not placed yet. */
    internal var titleLeftInWindow by mutableFloatStateOf(Float.NaN)
    internal var titleTopInWindow by mutableFloatStateOf(Float.NaN)
    internal var titleHeight by mutableFloatStateOf(Float.NaN)

    /**
     * The two beats of speaking, each 0 → 1: [markIn] = the mark blooms beside
     * the title and pushes it right; [unroll] = the mark rolls to the corner
     * and the bubble unrolls behind it. Read in layout / draw only.
     */
    internal val markIn = Animatable(0f)
    internal val unroll = Animatable(0f)

    /**
     * Tucking away from a spoken bubble (owner 10-09: 「直接飞走恢复标题」):
     * 0 → 1 the bubble and its mark fly up into the arrow while the title
     * slides back; then everything resets to 0 at once.
     */
    internal val flyAway = Animatable(0f)

    /** Where everything lands, written in the overlay's placement; read in draw. */
    internal var geometry by mutableStateOf<MemoryBubbleGeometry?>(null)
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

/**
 * Put on the header's title: it reports where the title is (the bubble takes
 * its place), steps right for the mark, and gives way as the bubble unrolls.
 */
internal fun Modifier.memoryBubbleTitle(controller: MemoryBubbleController?): Modifier =
    if (controller == null) {
        this
    } else {
        this
            .onPlaced { coordinates ->
                val position = coordinates.positionInWindow()
                controller.titleLeftInWindow = position.x
                controller.titleTopInWindow = position.y
                controller.titleHeight = coordinates.size.height.toFloat()
            }
            .graphicsLayer {
                // Flying away hands the title back as the bubble goes.
                val stay = 1f - controller.flyAway.value
                translationX = (BubbleMarkSize + BubbleMarkTitleGap).toPx() * controller.markIn.value * stay
                alpha = 1f - smoothstep(0f, 0.45f, controller.unroll.value) * stay
            }
    }

/** Put on the header's spacer between the title and Settings: the bubble ends before it does. */
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
 * camera cutout. The bubble's body runs from the header's title ([titleLeft])
 * to [reach] past the arrow, with at least [minLeft] left of it, and ends
 * before Settings ([freeEnd]): so the arrow goes under the cutout when that
 * leaves the bubble room, else the page's centre when that does, else as far
 * left as the bubble allows (a corner punch-hole, or a page without the
 * cutout over it).
 */
internal fun memoryArrowCenterX(
    cutoutCenterX: Float?,
    width: Float,
    titleLeft: Float,
    freeEnd: Float,
    minLeft: Float,
    reach: Float,
): Pair<Float, Boolean> {
    fun fits(x: Float) = x - minLeft >= titleLeft && x + reach <= freeEnd
    cutoutCenterX?.takeIf(::fits)?.let { return it to true }
    val centre = width / 2f
    if (fits(centre)) return centre to false
    return (titleLeft + minLeft) to false
}

// ── The bubble's outline ───────────────────────────────────────────────

/** Where the speaking bubble lands, in the overlay's px. */
@Immutable
internal data class MemoryBubbleGeometry(
    val body: Rect,
    // The chevron's x, and the tail's sharp tip above it.
    val tailX: Float,
    val tailTop: Float,
    // The mark's centre beside the title, and on the body's corner.
    val markStart: Offset,
    val markEnd: Offset,
)

/**
 * The body at unroll [s]: the mark's own box at 0, grown out to [body] at 1,
 * its right end riding with the mark (past 1 on the spring's overshoot).
 */
internal fun MemoryBubbleGeometry.bodyAt(s: Float, markHalf: Float): Rect {
    val mark = lerp(markStart, markEnd, s)
    return Rect(
        left = lerp(markStart.x - markHalf, body.left, s),
        top = lerp(markStart.y - markHalf, body.top, s),
        right = mark.x + (body.right - markEnd.x),
        bottom = lerp(markStart.y + markHalf, body.bottom, s),
    )
}

// A quarter circle's cubic handle, as a fraction of its radius.
private const val ArcHandle = 0.5523f

// How far along its base each flank keeps level before it rises: long and
// flat, so it settles softly into the top edge (owner 10-09, option B).
private const val FlankSettle = 0.62f

/**
 * The bubble's outline into [path]: a rounded body; with a [tailX], a tail
 * rising from its top to a sharp tip at ([tailX], [tailTop]) — two mirrored
 * concave flanks from [flank] either side of it, leaving the tip [lean] out
 * and [drop] down and levelling off into the top edge. A tail with no room
 * (into either corner, or its tip not above the body) is left off.
 */
internal fun memoryBubbleOutline(
    path: Path,
    body: Rect,
    radius: Float,
    tailX: Float?,
    tailTop: Float,
    flank: Float,
    lean: Float,
    drop: Float,
) {
    path.rewind()
    val l = body.left
    val t = body.top
    val r = body.right
    val b = body.bottom
    val rr = radius.coerceAtMost(minOf(body.width, body.height) / 2f).coerceAtLeast(0f)
    val h = rr * ArcHandle
    val flankStart = (tailX ?: 0f) - flank
    val hasTail = tailX != null && flankStart >= l + rr && tailX + flank <= r - rr && tailTop < t
    path.moveTo(l, t + rr)
    path.cubicTo(l, t + rr - h, l + rr - h, t, l + rr, t)
    if (hasTail && tailX != null) {
        path.lineTo(flankStart, t)
        // The left flank: level off the top edge, then up to the point.
        path.cubicTo(flankStart + flank * FlankSettle, t, tailX - lean, tailTop + drop, tailX, tailTop)
        // The right flank, its mirror: down off the point, levelling off.
        path.cubicTo(tailX + lean, tailTop + drop, tailX + flank * (1f - FlankSettle), t, tailX + flank, t)
    }
    path.lineTo(r - rr, t)
    path.cubicTo(r - rr + h, t, r, t + rr - h, r, t + rr)
    path.lineTo(r, b - rr)
    path.cubicTo(r, b - rr + h, r - rr + h, b, r - rr, b)
    path.lineTo(l + rr, b)
    path.cubicTo(l + rr - h, b, l, b - rr + h, l, b - rr)
    path.close()
}

// ── The overlay ────────────────────────────────────────────────────────

private enum class BubbleSlot { Arrow, Surface, Content, Mark }

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
 * half of [editProgress] (draw phase). The header's title carries
 * [memoryBubbleTitle] so the bubble can take its place.
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
    // top (the arrow is there), nobody pulling Memories open — and not over
    // the header's own cold-start bloom (the same mark, the same place).
    val canSpeak: () -> Boolean = {
        !currentCovered && currentScrolled() <= fadePx && currentHint() <= 0.01f && !HomeIntroRunning.value
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
    // Out with the Expressive bounce; back in without one.
    val outSpring = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    val inSpring = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Standard)
    // Flying away is a trip, not a snap: the slow spatial spring, no bounce.
    val flySpring = YoinMotion.slowSpatialSpec<Float>(role = YoinMotionRole.Standard)
    LaunchedEffect(speaking, reduced) {
        val target = if (speaking) 1f else 0f
        when {
            reduced -> Unit
            speaking -> {
                // Caught flying away: start over from the title.
                if (controller.flyAway.value > 0f) {
                    controller.unroll.snapTo(0f)
                    controller.markIn.snapTo(0f)
                    controller.flyAway.snapTo(0f)
                }
                // The mark squeezes in and blooms, holds a beat beside the
                // title, then rolls off with the bubble unrolling behind it.
                if (controller.unroll.value <= 0f) {
                    controller.markIn.animateTo(1f, outSpring)
                    delay(MarkHoldMs)
                } else {
                    // Caught mid-tuck: straight back out.
                    launch { controller.markIn.animateTo(1f, outSpring) }
                }
                controller.unroll.animateTo(1f, outSpring)
            }
            // Spoken: it flies off into the arrow and the title comes back.
            controller.unroll.value >= FlyAwayFrom -> controller.flyAway.animateTo(1f, flySpring)
            // Still only the mark beside the title: it folds away.
            else -> {
                controller.unroll.animateTo(0f, inSpring)
                controller.markIn.animateTo(0f, inSpring)
            }
        }
        // Land exactly: a spring settles within its visibility threshold,
        // and a bubble left at 0.004 would linger as a ghost. (Flown away,
        // the reset is invisible: the title already reads as at rest.)
        controller.unroll.snapTo(target)
        controller.markIn.snapTo(target)
        controller.flyAway.snapTo(0f)
    }
    val composedBubble by remember {
        derivedStateOf {
            reason != null || controller.markIn.value > BubbleGoneBelow || controller.unroll.value > BubbleGoneBelow
        }
    }
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
            val position = coordinates.positionInWindow()
            controller.overlayXInWindow = position.x
            controller.overlayYInWindow = position.y
        },
        content = {
            MemoryArrow(
                speaking = { controller.unroll.value },
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
                    controller = controller,
                    speaking = speaking,
                    reduced = reduced,
                    extractBackdropColors = extractBackdropColors,
                    onClick = tapBubble,
                    surfaceModifier = Modifier.layoutId(BubbleSlot.Surface),
                    contentModifier = Modifier.layoutId(BubbleSlot.Content),
                )
                BubbleMark(
                    markIn = { controller.markIn.value },
                    modifier = Modifier.layoutId(BubbleSlot.Mark),
                )
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
        val touch = ArrowTouchSize.roundToPx()
        val arrow = measurables.first { it.layoutId == BubbleSlot.Arrow }.measure(Constraints.fixed(touch, touch))
        val margin = BubbleEdgeMargin.toPx()
        // The header's title and its span before Settings, and the cutout,
        // brought into this overlay's space (window positions from the last
        // placement; they only move when the page's width does, and a write
        // re-measures).
        val overlayX = controller.overlayXInWindow.takeIf { !it.isNaN() } ?: 0f
        val overlayY = controller.overlayYInWindow.takeIf { !it.isNaN() } ?: 0f
        val titleLeft = controller.titleLeftInWindow.takeIf { !it.isNaN() }?.minus(overlayX)
            ?: TitleFallbackLeft.toPx()
        val titleTop = controller.titleTopInWindow.takeIf { !it.isNaN() }?.minus(overlayY)
            ?: (statusBarTop - overlayY + TitleFallbackTop.toPx())
        val titleHeight = controller.titleHeight.takeIf { !it.isNaN() } ?: TitleFallbackHeight.toPx()
        val freeEnd = controller.freeEndInWindow.takeIf { !it.isNaN() }?.minus(overlayX)
            ?.coerceAtMost(width - margin) ?: (width - margin)
        val reach = BubbleReach.toPx()
        val (arrowX, underCutout) = memoryArrowCenterX(
            cutoutCenterX = cutout?.let { it.center.x - overlayX },
            width = width.toFloat(),
            titleLeft = titleLeft,
            freeEnd = freeEnd,
            minLeft = BubbleMinLeft.toPx(),
            reach = reach,
        )
        val iconHalf = ArrowIconSize.toPx() / 2f
        val arrowCenterY = if (underCutout && cutout != null) {
            cutout.bottom - overlayY + ArrowBelowCutout.toPx() + iconHalf
        } else {
            statusBarTop - overlayY + ArrowBelowCutout.toPx() + iconHalf
        }
        // The body: from the title's place to just past the arrow; its top
        // under the title's, or lower if the tail needs the room.
        val bodyRight = arrowX + reach
        val bodyLeft = maxOf(titleLeft, bodyRight - BubbleMaxWidth.toPx())
        val tailTop = arrowCenterY - BubbleTipAboveChevron.toPx()
        val bodyTop = maxOf(titleTop, arrowCenterY + BubbleChevronAboveBody.toPx())
        val body = Rect(bodyLeft, bodyTop, bodyRight, bodyTop + BubbleBodyHeight.toPx())
        val markPx = BubbleMarkSize.roundToPx()
        val markHalf = markPx / 2f
        val geometry = MemoryBubbleGeometry(
            body = body,
            tailX = arrowX,
            tailTop = tailTop,
            markStart = Offset(titleLeft + markHalf, titleTop + titleHeight / 2f),
            markEnd = Offset(body.right - BubbleMarkCornerInsetX.toPx(), body.bottom - BubbleMarkCornerInsetY.toPx()),
        )
        val inset = BubbleCoverInset.toPx()
        val surface = measurables.firstOrNull { it.layoutId == BubbleSlot.Surface }
            ?.measure(Constraints.fixed(width, height))
        val content = measurables.firstOrNull { it.layoutId == BubbleSlot.Content }?.measure(
            Constraints(maxWidth = (body.width - inset - BubbleTrail.toPx()).roundToInt().coerceAtLeast(0)),
        )
        val mark = measurables.firstOrNull { it.layoutId == BubbleSlot.Mark }?.measure(Constraints.fixed(markPx, markPx))
        layout(width, height) {
            controller.geometry = geometry
            if (surface != null) {
                controller.bubbleBounds = Rect(
                    left = minOf(body.left, geometry.markStart.x - markHalf),
                    top = tailTop,
                    right = geometry.markEnd.x + markHalf,
                    bottom = geometry.markEnd.y + markHalf,
                )
                surface.place(0, 0)
            } else {
                controller.bubbleBounds = Rect.Zero
            }
            val contentX = (body.left + inset).roundToInt()
            val contentY = (body.top + (body.height - (content?.height ?: 0)) / 2f).roundToInt()
            content?.placeWithLayer(contentX, contentY) {
                flyIntoTip(controller.flyAway.value, Offset(geometry.tailX - contentX, geometry.tailTop - contentY))
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
            // The mark rolls one full turn from the title to the corner.
            mark?.placeWithLayer(0, 0) {
                val s = controller.unroll.value
                val at = lerp(geometry.markStart, geometry.markEnd, s)
                // Squeezing in from the left as it blooms (and out again).
                val squeeze = (1f - controller.markIn.value) * BubbleMarkSqueeze.toPx()
                val left = at.x - markHalf - squeeze
                val top = at.y - markHalf
                translationX = left
                translationY = top
                rotationZ = 360f * s
                // Its last specks fade rather than linger as the arms close.
                alpha = smoothstep(0.1f, 0.35f, controller.markIn.value)
                flyIntoTip(controller.flyAway.value, Offset(geometry.tailX - left, geometry.tailTop - top))
            }
        }
    }
}

/** Tucked from this far unrolled it flies away; less, the mark just folds back. */
private const val FlyAwayFrom = 0.5f

/** Flying away, it shrinks to this (about the tail's tip) and lifts this far. */
private const val FlyShrink = 0.75f
private val FlyLift = 20.dp

/**
 * Flying away into the arrow on [fly]: shrinks toward the tail's tip at [tip]
 * (in this layer's own space), lifts, and fades out over the second half.
 */
private fun GraphicsLayerScope.flyIntoTip(fly: Float, tip: Offset) {
    if (fly <= 0f || size.width <= 0f || size.height <= 0f) return
    val scale = 1f - FlyShrink * fly
    scaleX *= scale
    scaleY *= scale
    transformOrigin = TransformOrigin(tip.x / size.width, tip.y / size.height)
    translationY -= FlyLift.toPx() * fly
    alpha *= 1f - smoothstep(0.35f, 0.9f, fly)
}

/** The mark rests beside the title this long before it rolls off. */
private const val MarkHoldMs = 700L

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
    val memoriesArrow = stringResource(R.string.home_cd_memories_arrow)
    Box(
        modifier = modifier
            .clearAndSetSemantics {
                // Scrolled away it is gone for everyone, readers included.
                // (Pointer taps are routed by Home's root, never here.)
                if (enabled) {
                    role = Role.Button
                    contentDescription = memoriesArrow
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

/** The Yoin mark that says it: its arms bloom open on [markIn], one beat apart. */
@Composable
private fun BubbleMark(markIn: () -> Float, modifier: Modifier = Modifier) {
    val p = markIn()
    val scheme = MaterialTheme.colorScheme
    YoinMark(
        transforms = List(3) { i ->
            // Each arm a beat after the last; all three land on exactly 1.
            val arm = ((p - i * MarkArmStagger) / (1f - i * MarkArmStagger)).coerceAtLeast(0f)
            YoinArmTransform(scale = arm, rotationDeg = (1f - arm) * -80f)
        },
        // Arm roles as in the launcher mark: tertiary, primary, secondary.
        colors = listOf(scheme.tertiary, scheme.primary, scheme.secondary),
        lineColor = Color.White,
        modifier = modifier,
    )
}

private const val MarkArmStagger = 0.12f

/**
 * The bubble: its surface (the whole overlay, drawing the body at the current
 * unroll) and its content (the latest memory's cover and title, placed where
 * the body lands), both laid out by the overlay.
 */
@Composable
private fun MemoryBubble(
    pill: HomeMemoryPill?,
    controller: MemoryBubbleController,
    speaking: Boolean,
    reduced: Boolean,
    extractBackdropColors: Boolean,
    onClick: () -> Unit,
    surfaceModifier: Modifier,
    contentModifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // While it tucks away it keeps saying what it said.
    val shown = rememberLastNonNull(pill?.takeIf { it.hasSomethingToSay() })
    val latest = shown?.latest
    val backdrop = rememberExpressiveBackdropColors(
        model = latest?.coverArtUrl,
        fallbackBaseColor = colors.secondary,
        fallbackAccentColor = colors.tertiary,
        enabled = extractBackdropColors && latest != null,
    )
    // Over the feed, the body is always opaque: the raised container washed
    // with the cover's own colour (the bento's direct lerp).
    val raised = colors.surfaceContainerHigh
    val fill by animateColorAsState(
        targetValue = if (latest != null) lerp(raised, backdrop.baseColor, 0.30f) else raised,
        animationSpec = if (reduced) snap() else YoinMotion.effectsSpring(),
        label = "memoryBubbleFill",
    )
    val headline = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    val resources = LocalContext.current.resources
    val writtenAgo = latest?.writtenAtMillis?.let { remember(it) { formatTimeAgo(it, resources) } }
    val description = if (latest == null) {
        stringResource(R.string.home_cd_bubble_memories)
    } else {
        val artist = latest.artistName
        if (artist != null) {
            stringResource(R.string.home_cd_bubble_latest_by, latest.albumName, artist)
        } else {
            stringResource(R.string.home_cd_bubble_latest, latest.albumName)
        }
    }

    Box(
        modifier = surfaceModifier
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
                // Gone before the body shrinks into the mark's box.
                alpha = smoothstep(0.04f, 0.3f, controller.unroll.value)
                controller.geometry?.let { flyIntoTip(controller.flyAway.value, Offset(it.tailX, it.tailTop)) }
                // The tail is drawn over the body: blend them as one.
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithCache {
                val bodyPath = Path()
                val tailPath = Path()
                val markHalf = BubbleMarkSize.toPx() / 2f
                val corner = BubbleCorner.toPx()
                val flank = BubbleTailFlank.toPx()
                val lean = BubbleTipLean.toPx()
                val drop = BubbleTipDrop.toPx()
                val reach = BubbleReach.toPx()
                onDrawBehind {
                    val geometry = controller.geometry ?: return@onDrawBehind
                    val s = controller.unroll.value
                    if (s < BubbleGoneBelow) return@onDrawBehind
                    val body = geometry.bodyAt(s, markHalf)
                    memoryBubbleOutline(bodyPath, body, corner, null, 0f, flank, lean, drop)
                    drawPath(bodyPath, color = fill)
                    // The tail rises last, out of the body's top, its point
                    // over the chevron — pulled along if the body hasn't
                    // reached it yet.
                    val rise = smoothstep(TailRisesFrom, 1f, s)
                    if (rise > 0f) {
                        val tailX = minOf(geometry.tailX, body.right - reach)
                        memoryBubbleOutline(tailPath, body, corner, tailX, geometry.tailTop, flank, lean, drop)
                        clipRect(bottom = body.top + corner) {
                            translate(top = (1f - rise) * (body.top - geometry.tailTop)) {
                                drawPath(tailPath, color = fill)
                            }
                        }
                    }
                }
            },
    )
    Layout(
        modifier = contentModifier
            .clearAndSetSemantics {}
            .graphicsLayer { alpha = smoothstep(0.55f, 0.95f, controller.unroll.value) }
            .drawWithContent {
                // Never past the body's right end as it unrolls.
                val geometry = controller.geometry
                val right = geometry?.let {
                    it.bodyAt(controller.unroll.value, BubbleMarkSize.toPx() / 2f).right -
                        (it.body.left + BubbleCoverInset.toPx())
                } ?: size.width
                clipRect(right = right.coerceAtLeast(0f)) { this@drawWithContent.drawContent() }
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
                    modifier = Modifier.layoutId(BubbleText.Cover),
                )
                Text(
                    text = latest.albumName,
                    style = headline,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.layoutId(BubbleText.Album),
                )
                // A wider bubble says more, when it fits whole: when it was
                // written, and the Memory's own title over the album.
                if (writtenAgo != null) {
                    Text(
                        text = writtenAgo,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.layoutId(BubbleText.Ago),
                    )
                }
                val title = latest.memoryTitle
                if (title != null) {
                    // The app's own face here, not the cards' serif (owner 10-09).
                    Text(
                        text = title,
                        style = headline,
                        color = colors.onSurface,
                        maxLines = 1,
                        modifier = Modifier.layoutId(BubbleText.Title),
                    )
                    Text(
                        text = latest.albumName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.layoutId(BubbleText.AlbumUnder),
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.home_memory_bubble_title),
                    style = headline,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.layoutId(BubbleText.Album),
                )
            }
        },
    ) { measurables, constraints ->
        val gap = BubbleCoverTitleGap.roundToPx()
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        fun slot(id: BubbleText) = measurables.firstOrNull { it.layoutId == id }
        val cover = slot(BubbleText.Cover)?.measure(Constraints())
        val start = if (cover != null) cover.width + gap else gap
        val room = (maxWidth - start).coerceAtLeast(0)
        // Natural widths decide how much it says: everything whole, or less.
        val ago = slot(BubbleText.Ago)?.measure(Constraints())
        val title = slot(BubbleText.Title)?.measure(Constraints())
        val under = slot(BubbleText.AlbumUnder)?.measure(Constraints())
        val agoGap = BubbleAgoGap.roundToPx()
        val wide = room >= BubbleSaysMoreFrom.roundToPx()
        val withTitle = wide && title != null && under != null && title.width <= room &&
            under.width + (ago?.let { agoGap + it.width } ?: 0) <= room
        val lines: List<List<Placeable>> = when {
            withTitle -> listOf(listOf(title!!), listOfNotNull(under, ago))
            wide && ago != null -> listOf(
                listOf(slot(BubbleText.Album)!!.measure(Constraints(maxWidth = room))),
                listOf(ago),
            )
            else -> listOf(listOf(slot(BubbleText.Album)!!.measure(Constraints(maxWidth = room))))
        }
        val lineWidths = lines.map { line -> line.sumOf { it.width } + agoGap * (line.size - 1) }
        val textHeight = lines.sumOf { line -> line.maxOf { it.height } }
        val height = maxOf(cover?.height ?: 0, textHeight)
        layout((start + (lineWidths.maxOrNull() ?: 0)).coerceAtMost(maxWidth), height) {
            cover?.placeRelative(0, (height - cover.height) / 2)
            var y = (height - textHeight) / 2
            lines.forEach { line ->
                val lineHeight = line.maxOf { it.height }
                var x = start
                line.forEach { piece ->
                    // Pieces on one line share a baseline-ish bottom.
                    piece.placeRelative(x, y + lineHeight - piece.height)
                    x += piece.width + agoGap
                }
                y += lineHeight
            }
        }
    }
}

private enum class BubbleText { Cover, Album, Ago, Title, AlbumUnder }

/** With this much room beside the cover, the bubble says when, and the Memory's title if it fits. */
private val BubbleSaysMoreFrom = 200.dp
private val BubbleAgoGap = 8.dp

/** The tail rises over the last stretch of the unroll. */
private const val TailRisesFrom = 0.82f
