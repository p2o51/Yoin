package com.gpo.yoin.ui.home

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
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
import com.gpo.yoin.ui.experience.withSteadyFrames
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
// untouched for a while — that one at most once each time the app comes to
// the foreground. Said in full, it holds six seconds and flies back into the
// arrow (owner 2026-10-11). Any touch elsewhere — the bar included — tucks it
// back sooner; the app going to the background puts it away unseen.

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

/**
 * "不动就出现": Home readable and untouched this long, the bubble speaks — and
 * again each time it has gone and Home is left alone again (owner 2026-10-17;
 * replaces the once-per-foreground reminder of 2026-10-11). In the app the
 * wait is picked from [MemoryBubbleIdleRangeMs] each time, a pulse rather than
 * a metronome; this midpoint is the fixed wait harnesses use.
 */
internal const val MemoryBubbleIdleMs = 4_500L

/**
 * News said in full (the bubble out, its spring at rest) holds this long, then
 * flies back into the arrow on its own (owner 2026-10-11: 说完停 6 秒). What it
 * says when left alone holds [MemoryBubbleChatterDwellRangeMs].
 */
internal val MemoryBubbleDwellMs: Long = 6_000L

/** Presence below this is tucked away: not drawn, not composed. */
private const val BubbleGoneBelow = 0.01f

/** News waits this long after the launch reveal lands. */
private const val MemoryBubbleNewsDelayMs = 450L

/**
 * News that had to wait for Home to be readable waits this long more, readable
 * and untouched: a free-form or split window comes into focus under the tap
 * that clicked it, which is likely opening something — the news, spoken then,
 * would be tucked away at once, told to no one.
 */
internal const val MEMORY_BUBBLE_NEWS_SETTLE_MS = 300L

/** Null = a fresh pick from [MemoryBubbleIdleRangeMs] each time; harnesses pin it. */
internal val LocalMemoryBubbleIdleMs = staticCompositionLocalOf<Long?> { null }
internal val LocalMemoryBubbleDwellMs = staticCompositionLocalOf { MemoryBubbleDwellMs }

/** Null = a fresh pick from [MemoryBubbleChatterDwellRangeMs] each time; harnesses pin it. */
internal val LocalMemoryBubbleChatterDwellMs = staticCompositionLocalOf<Long?> { null }

/** How often a turn of idle chatter becomes the next one before it goes; harnesses pin it. */
internal val LocalMemoryBubbleChainChance = staticCompositionLocalOf { MemoryBubbleChainChance }

// ── What the bubble remembers ──────────────────────────────────────────

/**
 * The last [HomeMemoryPill.newsKey] the bubble spoke, per profile scope — and
 * how far its idle reminder has got through [HomeMemoryPill.recall].
 */
internal interface MemoryBubbleSeenStore {
    fun lastSeen(scope: String): String?

    fun markSeen(scope: String, newsKey: String)

    /** The idle reminder's turn in [scope]: 0, 1, 2… one more each time it is asked. */
    fun nextRecallTurn(scope: String): Int

    /** Which tip comes next ([HomeBubbleTip]): 0, 1, 2… one more each time it is asked. */
    fun nextTipTurn(): Int
}

internal class SharedPrefsMemoryBubbleSeenStore(context: Context) : MemoryBubbleSeenStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun lastSeen(scope: String): String? = prefs.getString(KEY_PREFIX + scope, null)

    override fun markSeen(scope: String, newsKey: String) {
        prefs.edit { putString(KEY_PREFIX + scope, newsKey) }
    }

    override fun nextRecallTurn(scope: String): Int {
        val turn = prefs.getInt(RECALL_PREFIX + scope, 0)
        prefs.edit { putInt(RECALL_PREFIX + scope, if (turn == Int.MAX_VALUE) 0 else turn + 1) }
        return turn
    }

    override fun nextTipTurn(): Int {
        val turn = prefs.getInt(TIP_KEY, 0)
        prefs.edit { putInt(TIP_KEY, if (turn == Int.MAX_VALUE) 0 else turn + 1) }
        return turn
    }

    private companion object {
        // Shared with the lyric idle hint: UI hints that remember they've spoken.
        const val PREFS_NAME = "yoin_ui_hints"
        const val KEY_PREFIX = "memory_bubble_seen:"
        const val RECALL_PREFIX = "memory_bubble_recall:"
        const val TIP_KEY = "memory_bubble_tip"
    }
}

internal class InMemoryMemoryBubbleSeenStore : MemoryBubbleSeenStore {
    private val seen = mutableMapOf<String, String>()

    override fun lastSeen(scope: String): String? = seen[scope]

    override fun markSeen(scope: String, newsKey: String) {
        seen[scope] = newsKey
    }

    private val turns = mutableMapOf<String, Int>()

    override fun nextRecallTurn(scope: String): Int {
        val turn = turns[scope] ?: 0
        turns[scope] = turn + 1
        return turn
    }

    private var tipTurn = 0

    override fun nextTipTurn(): Int = tipTurn++
}

/**
 * What the bubble says for [reason]: news is always the latest memory; the
 * idle reminder brings back one of the others, the next in line on each
 * [turn] — the latest itself when there are no others yet.
 */
internal fun memoryBubbleSubject(pill: HomeMemoryPill?, reason: BubbleReason, turn: Int): HomeMemoryPill.Latest? {
    if (pill == null) return null
    if (reason == BubbleReason.News || pill.recall.isEmpty()) return pill.latest
    return pill.recall[Math.floorMod(turn, pill.recall.size)]
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

// ── When it speaks, when it goes (owner 2026-10-11) ────────────────────

internal enum class BubbleReason { News, Idle }

/**
 * What the bubble is saying (null = tucked away), and whether it has spoken
 * since the app came to the foreground — which uses up the idle reminder.
 */
@Immutable
internal data class MemoryBubbleTurn(val reason: BubbleReason? = null, val spokeThisForeground: Boolean = false)

/** What happens to the bubble; the overlay's effects turn each into one of these. */
internal enum class MemoryBubbleCue {
    /** Something written that this profile hasn't been told, and Home can be read. */
    News,

    /** Home sat readable and untouched for the idle window. */
    IdleElapsed,

    /** Said in full, it held for the dwell. */
    DwellOver,

    /** A press anywhere — Home, the bar, the Now Playing pill — or a wheel scroll; a tap on it too. */
    Touch,

    /** Home went out of reach: covered, scrolled, pulled toward Memories, another window in front. */
    OutOfReach,

    /** The app went to the background (STOPPED). */
    Backgrounded
}

/**
 * The bubble's rules. News is always told. Left alone, it speaks again each
 * time it has gone quiet (owner 2026-10-17) — never over a bubble already
 * speaking. Everything else tucks it away; the background also starts the
 * next time in the foreground afresh (unseen, so it isn't a hard cut).
 */
internal fun MemoryBubbleTurn.after(cue: MemoryBubbleCue): MemoryBubbleTurn = when (cue) {
    MemoryBubbleCue.News -> MemoryBubbleTurn(reason = BubbleReason.News, spokeThisForeground = true)
    MemoryBubbleCue.IdleElapsed -> if (reason == null) {
        MemoryBubbleTurn(reason = BubbleReason.Idle, spokeThisForeground = true)
    } else {
        this
    }
    MemoryBubbleCue.DwellOver, MemoryBubbleCue.Touch, MemoryBubbleCue.OutOfReach -> copy(reason = null)
    MemoryBubbleCue.Backgrounded -> MemoryBubbleTurn()
}

/**
 * The dwell starts once it has said it all: the bubble fully out, its spring
 * at rest — and not flying away. Flying off, the bubble keeps [unroll] at 1
 * (only flyAway moves), so a bubble called back mid-flight would otherwise
 * start its clock before it has even bloomed again.
 */
internal fun memoryBubbleFullySaid(unroll: Float, unrolling: Boolean, flyAway: Float, flyingAway: Boolean): Boolean =
    unroll >= 1f && !unrolling && flyAway <= 0f && !flyingAway

/**
 * Waits until it has said it all ([fullySaid]), then holds it [dwellMs] — and
 * never leaves from under a finger: while [held] (a press on the bubble or the
 * arrow, not yet let go) it waits for the release.
 */
internal suspend fun awaitMemoryBubbleDwell(fullySaid: Flow<Boolean>, held: Flow<Boolean>, dwellMs: Long) {
    fullySaid.first { it }
    delay(dwellMs)
    held.first { !it }
}

/**
 * How long it holds for this user: [dwellMs], or longer when the system's
 * accessibility timeout ("Time to take action") asks for it — the bubble is
 * a control that goes away on its own, so it honours that like a snackbar.
 */
internal fun memoryBubbleDwellMs(dwellMs: Long, accessibility: AccessibilityManager?): Long =
    accessibility?.calculateRecommendedTimeoutMillis(
        dwellMs,
        containsIcons = true,
        containsText = true,
        containsControls = true
    ) ?: dwellMs

/**
 * Returns once Home has stayed [readable] and untouched for [idleMs] straight:
 * a touch, or Home going out of reach, starts the clock over.
 */
internal suspend fun awaitMemoryBubbleIdle(readable: Flow<Boolean>, touches: Flow<Unit>, idleMs: Long) {
    while (true) {
        readable.first { it }
        val interrupted = withTimeoutOrNull(idleMs) {
            merge(touches, readable.filter { !it }.map { }).first()
        }
        if (interrupted == null) return
    }
}

/**
 * Home's own window can be read: in front ([Lifecycle.State.RESUMED] — a
 * detail page, translucent, only pauses it) and focused (not under the
 * account card's dialog, the notification shade, the other side of a split).
 */
internal fun memoryBubbleWindowReadable(state: Lifecycle.State, focused: Boolean): Boolean =
    focused && state.isAtLeast(Lifecycle.State.RESUMED)

/**
 * A press the window's root reported: Home's root saw it too if the down
 * times match — Home deals with those (a tap on the bubble is not a tuck) —
 * else it landed outside Home (the bar, the Now Playing pill) and counts as
 * a touch (owner 2026-10-11: 碰底栏也算碰屏). The root reports on the Final
 * pass, after every Initial pass inside, so Home's [MemoryBubbleController.lastDownAt]
 * is already written whenever (and on whatever dispatcher) this is asked.
 */
internal fun isShellOnlyPress(shellDownAt: Long, homeDownAt: Long): Boolean = shellDownAt != homeDownAt

/**
 * The shell's half of the bubble, one per window, above the section switch
 * (a trip to Library and back keeps it): every press anywhere in the window —
 * the bar, the Now Playing pill and the snackbars are not inside Home — and
 * whether the bubble has spoken since the app came to the foreground.
 * YoinNavHost makes it, reports its root's presses into it and provides it
 * ([LocalMemoryBubbleShell]); without one (the debug harness, previews) the
 * overlay keeps its own and only Home's presses count.
 */
@Stable
internal class MemoryBubbleShell {
    /** Each press's down time, as the window's root sees it — after Home's own watcher has. */
    internal val presses = MutableSharedFlow<Long>(extraBufferCapacity = 1)

    internal var spokeThisForeground: Boolean = false
}

internal val LocalMemoryBubbleShell = staticCompositionLocalOf<MemoryBubbleShell?> { null }

/** The shell's [MemoryBubbleShell]: the app going to the background frees the idle reminder again. */
@Composable
internal fun rememberMemoryBubbleShell(): MemoryBubbleShell {
    val shell = remember { MemoryBubbleShell() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { shell.spokeThisForeground = false }
    return shell
}

/**
 * Put on the window's root: reports every press to [shell], consuming
 * nothing. On the Final pass — consumed or not — so Home's root (Initial
 * pass) has always recorded a press of its own first ([isShellOnlyPress]).
 */
internal fun Modifier.reportMemoryBubbleShellPresses(shell: MemoryBubbleShell): Modifier = pointerInput(shell) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        shell.presses.tryEmit(down.uptimeMillis)
    }
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

    /** The last press Home's root saw, by its down time: the shell's report of it is not news ([isShellOnlyPress]). */
    internal var lastDownAt: Long = Long.MIN_VALUE

    /** A finger is down on the bubble or the arrow, not yet let go: the dwell waits for it. */
    internal var held by mutableStateOf(false)

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

    /** The header row's span in WINDOW x ([memoryBubblePage]): the page's content; NaN = not placed yet. */
    internal var pageStartInWindow by mutableFloatStateOf(Float.NaN)
    internal var pageEndInWindow by mutableFloatStateOf(Float.NaN)

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

    /**
     * Flying away, the place it left from: it flies into the arrow it left,
     * whatever Home lays out meanwhile (a detail column opening re-lays the
     * header under the same tap). Null = not flying; the header places it.
     */
    internal var flightGeometry by mutableStateOf<MemoryBubbleGeometry?>(null)
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
                    controller.lastDownAt = down.uptimeMillis
                    val tap = controller.tapTargetAt(down.position)
                    if (tap == null) {
                        controller.touches.tryEmit(Unit)
                        return@awaitEachGesture
                    }
                    down.consume()
                    val slop = viewConfiguration.touchSlop
                    // Held, the bubble doesn't leave from under the finger
                    // (the dwell waits); let go — tap, drag or cancel — and
                    // it's free again.
                    controller.held = true
                    try {
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
                    } finally {
                        controller.held = false
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

/**
 * Put on the header's row: it spans the page's content, and the arrow takes
 * its centre. At rest that is the window's too; but a detail column re-tiers
 * Home at the tap, before the column takes any width, and the feed's frame
 * then runs start-aligned in the still-wide container (feedFrameInsets). The
 * window's centre falls outside the header's free span there, so an arrow
 * centred on the window would cut to its fallback beside the title and back;
 * on the page it moves with the frame's own spring.
 */
internal fun Modifier.memoryBubblePage(controller: MemoryBubbleController?): Modifier {
    if (controller == null) return this
    return onPlaced { coordinates ->
        val x = coordinates.positionInWindow().x
        controller.pageStartInWindow = x
        controller.pageEndInWindow = x + coordinates.size.width
    }
}

// ── Where the arrow goes ───────────────────────────────────────────────

/**
 * The arrow's centre x in the overlay's space, and whether it sits under the
 * camera cutout. The bubble's body runs from the header's title ([titleLeft])
 * to [reach] past the arrow, with at least [minLeft] left of it, and ends
 * before Settings ([freeEnd]): so the arrow goes under the cutout when that
 * leaves the bubble room, else the page's centre ([pageCentre]) when that
 * does, else as far left as the bubble allows (a corner punch-hole, or a page
 * without the cutout over it).
 */
internal fun memoryArrowCenterX(
    cutoutCenterX: Float?,
    pageCentre: Float,
    titleLeft: Float,
    freeEnd: Float,
    minLeft: Float,
    reach: Float,
): Pair<Float, Boolean> {
    fun fits(x: Float) = x - minLeft >= titleLeft && x + reach <= freeEnd
    cutoutCenterX?.takeIf(::fits)?.let { return it to true }
    if (fits(pageCentre)) return pageCentre to false
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
 * [memoryBubbleTitle] so the bubble can take its place. When it speaks and
 * when it goes follow [MemoryBubbleTurn.after]; the shell's presses and the
 * once-per-foreground reminder come from [LocalMemoryBubbleShell].
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
    val dwellMs = LocalMemoryBubbleDwellMs.current
    val chatterDwellMs = LocalMemoryBubbleChatterDwellMs.current
    val chainChance = LocalMemoryBubbleChainChance.current
    val random = remember { Random(System.nanoTime()) }
    val accessibility = LocalAccessibilityManager.current
    // The shell's presses and the once-per-foreground reminder; on its own
    // (the debug harness, previews) it keeps a holder of its own.
    val shell = LocalMemoryBubbleShell.current ?: remember { MemoryBubbleShell() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val windowInfo = LocalWindowInfo.current
    val currentPill by rememberUpdatedState(pill)
    val currentHint by rememberUpdatedState(hintProgress)
    val currentReveal by rememberUpdatedState(revealProgress)
    val currentScrolled by rememberUpdatedState(scrolledPx)
    val currentCovered by rememberUpdatedState(covered || editing)
    val fadePx = with(density) { ArrowScrollFade.toPx() }
    // It may only speak where it can be read: Home on top, its window in
    // front and focused, the feed at its top (the arrow is there), nobody
    // pulling Memories open — and not over the header's own cold-start bloom
    // (the same mark, the same place).
    val canSpeak: () -> Boolean = {
        !currentCovered && currentScrolled() <= fadePx && currentHint() <= 0.01f && !HomeIntroRunning.value &&
            memoryBubbleWindowReadable(lifecycleState, windowInfo.isWindowFocused)
    }

    var reason by remember { mutableStateOf<BubbleReason?>(null) }
    // What it is saying (a memory or a tip), picked as it starts to speak:
    // kept while it tucks away, so it says the same thing all the way back.
    var say by remember { mutableStateOf<BubbleSay?>(null) }
    // One more each time it says something new — a chained line too.
    var sayCount by remember { mutableIntStateOf(0) }
    // Whether this turn of chatter has already become its next line.
    var chained by remember { mutableStateOf(false) }
    // Memories and tips take turns while there are memories to recall.
    var idleTurns by remember { mutableIntStateOf(0) }
    val hasSomething = pill.hasSomethingToSay()
    // News is about a memory: with none left, it goes. Tips need none.
    if (!hasSomething && reason == BubbleReason.News) reason = null
    val nextIdleSay: () -> BubbleSay = {
        val shownPill = currentPill
        val sayMemory = idleTurns % 2 == 0
        idleTurns += 1
        val memory = sayMemory && shownPill.hasSomethingToSay()
        val memoryTurn = if (memory && shownPill!!.recall.isNotEmpty()) store.nextRecallTurn(shownPill.scope) else 0
        val tipTurn = if (memory) 0 else store.nextTipTurn()
        idleBubbleSay(shownPill, sayMemory = memory, memoryTurn = memoryTurn, tipTurn = tipTurn)
    }
    // Every change of what it says goes through the rules (MemoryBubbleTurn.after).
    val cue: (MemoryBubbleCue) -> Unit = { what ->
        val next = MemoryBubbleTurn(reason, shell.spokeThisForeground).after(what)
        val starts = next.reason
        if (starts != null && starts != reason) {
            say = if (starts == BubbleReason.News) BubbleSay.Memory(currentPill?.latest) else nextIdleSay()
            sayCount += 1
            chained = false
        }
        reason = next.reason
        shell.spokeThisForeground = next.spokeThisForeground
    }

    // News: once the launch reveal has landed and it can be read (every time
    // Home resumes with news still untold), speak — and remember it was said.
    // Waiting for it to be readable, it must stay so, untouched, a beat first
    // (MEMORY_BUBBLE_NEWS_SETTLE_MS).
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
            if (!canSpeak()) {
                awaitMemoryBubbleIdle(snapshotFlow { canSpeak() }, controller.touches, MEMORY_BUBBLE_NEWS_SETTLE_MS)
            }
            cue(MemoryBubbleCue.News)
            store.markSeen(news.scope, news.newsKey)
        }
    }
    // "不动就出现": once it is quiet, readable and untouched for a fresh pick
    // of 4–5 s, speak; gone again, the clock starts over (owner 2026-10-17).
    // A touch, or Home out of reach, starts the wait over; tips mean there is
    // always something to say.
    LaunchedEffect(idleMs, shell) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val readable = snapshotFlow { canSpeak() }
            while (true) {
                snapshotFlow { reason == null }.first { it }
                awaitMemoryBubbleIdle(readable, controller.touches, idleMs ?: MemoryBubbleIdleRangeMs.pick(random))
                if (canSpeak()) cue(MemoryBubbleCue.IdleElapsed)
            }
        }
    }
    // Said in full, it holds — news dwellMs, chatter a fresh 2–3 s (longer if
    // the accessibility timeout asks) — and flies back into the arrow on its
    // own, never from under a finger. Now and then chatter turns into its next
    // line in place first, and holds that one too.
    LaunchedEffect(reason, sayCount, dwellMs, chatterDwellMs) {
        val speakingFor = reason ?: return@LaunchedEffect
        val hold = if (speakingFor == BubbleReason.News) {
            dwellMs
        } else {
            chatterDwellMs ?: MemoryBubbleChatterDwellRangeMs.pick(random)
        }
        val fullySaid = snapshotFlow {
            memoryBubbleFullySaid(
                unroll = controller.unroll.value,
                unrolling = controller.unroll.isRunning,
                flyAway = controller.flyAway.value,
                flyingAway = controller.flyAway.isRunning
            )
        }
        awaitMemoryBubbleDwell(fullySaid, snapshotFlow { controller.held }, memoryBubbleDwellMs(hold, accessibility))
        if (speakingFor == BubbleReason.Idle && !chained && canSpeak() && random.nextFloat() < chainChance) {
            chained = true
            say = nextIdleSay()
            sayCount += 1
        } else {
            cue(MemoryBubbleCue.DwellOver)
        }
    }
    // Any touch elsewhere (the bar's too), a pull toward Memories, the feed
    // scrolling away or something covering Home tucks it back.
    LaunchedEffect(controller, shell) {
        launch { controller.touches.collect { cue(MemoryBubbleCue.Touch) } }
        launch {
            shell.presses.collect { downAt ->
                if (isShellOnlyPress(downAt, controller.lastDownAt)) controller.touches.tryEmit(Unit)
            }
        }
        snapshotFlow { canSpeak() }.filter { !it }.collect { cue(MemoryBubbleCue.OutOfReach) }
    }
    // The app gone to the background (STOPPED): the window can't be seen, so
    // it is put away at once, unseen — no spring left for later — and Home
    // comes back at rest.
    LaunchedEffect(lifecycleOwner, controller) {
        lifecycleOwner.lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.STARTED) }
            .distinctUntilChanged()
            .filter { started -> !started }
            .collect {
                cue(MemoryBubbleCue.Backgrounded)
                controller.flyAway.snapTo(0f)
                controller.unroll.snapTo(0f)
                controller.markIn.snapTo(0f)
            }
    }

    val speaking = reason != null
    // Out with the Expressive bounce; back in without one. Flying away is a
    // trip, not a snap: the slow spatial spring, no bounce.
    val springs = MemoryBubbleSprings(
        out = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive),
        back = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Standard),
        fly = YoinMotion.slowSpatialSpec(role = YoinMotionRole.Standard)
    )
    LaunchedEffect(speaking, reduced) {
        controller.settle(speaking = speaking, reduced = reduced, springs = springs, flyHome = say !is BubbleSay.Tip)
    }
    // A tip is a plain capsule (owner 2026-10-17): no tail, fully round ends. A line that turns
    // into a tip mid-speech (or back) reshapes on the spatial spring; from rest it starts in shape.
    val capsule = remember { Animatable(0f) }
    val isTip = say is BubbleSay.Tip
    LaunchedEffect(isTip) {
        val target = if (isTip) 1f else 0f
        if (reduced || controller.unroll.value < BubbleGoneBelow) capsule.snapTo(target) else capsule.animateTo(target, springs.out)
    }
    val composedBubble by remember {
        derivedStateOf {
            reason != null || controller.markIn.value > BubbleGoneBelow || controller.unroll.value > BubbleGoneBelow
        }
    }
    val tapArrow: () -> Unit = {
        haptics.performContextClick()
        cue(MemoryBubbleCue.Touch)
        onNavigateToMemories()
    }
    val tapBubble: () -> Unit = {
        haptics.performContextClick()
        cue(MemoryBubbleCue.Touch)
        when (val said = say) {
            // A tip only tucks away — except the one that is about Memories.
            is BubbleSay.Tip -> if (said.tip == HomeBubbleTip.PullForMemories) onNavigateToMemories()
            is BubbleSay.Memory, null -> {
                val session = ((said as? BubbleSay.Memory)?.latest ?: pill?.latest)?.sessionId
                if (session != null) onOpenMemoryFocus(session) else onNavigateToMemories()
            }
        }
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
                // Coming home from a flight, it is the arrow at rest again.
                speaking = { controller.unroll.value * (1f - memoryArrowHomecoming(controller.flyAway.value)) },
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
                    say = say,
                    capsule = { capsule.value },
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
        // The header's title, its span before Settings and the page it heads,
        // and the cutout, brought into this overlay's space (window positions
        // from the last placement; they only move when the page's width
        // does, and a write re-measures).
        val overlayX = controller.overlayXInWindow.takeIf { !it.isNaN() } ?: 0f
        val overlayY = controller.overlayYInWindow.takeIf { !it.isNaN() } ?: 0f
        val titleLeft = controller.titleLeftInWindow.takeIf { !it.isNaN() }?.minus(overlayX)
            ?: TitleFallbackLeft.toPx()
        val titleTop = controller.titleTopInWindow.takeIf { !it.isNaN() }?.minus(overlayY)
            ?: (statusBarTop - overlayY + TitleFallbackTop.toPx())
        val titleHeight = controller.titleHeight.takeIf { !it.isNaN() } ?: TitleFallbackHeight.toPx()
        val freeEnd = controller.freeEndInWindow.takeIf { !it.isNaN() }?.minus(overlayX)
            ?.coerceAtMost(width - margin) ?: (width - margin)
        val pageStart = controller.pageStartInWindow
        val pageEnd = controller.pageEndInWindow
        val pageCentre = if (pageStart.isNaN() || pageEnd.isNaN()) width / 2f else (pageStart + pageEnd) / 2f - overlayX
        val reach = BubbleReach.toPx()
        val (arrowX, underCutout) = memoryArrowCenterX(
            cutoutCenterX = cutout?.let { it.center.x - overlayX },
            pageCentre = pageCentre,
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
        val bodyTop = maxOf(titleTop, arrowCenterY + BubbleChevronAboveBody.toPx())
        val headerBody = Rect(bodyLeft, bodyTop, bodyRight, bodyTop + BubbleBodyHeight.toPx())
        val markPx = BubbleMarkSize.roundToPx()
        val markHalf = markPx / 2f
        // Flying away, it keeps the place it left from (flightGeometry).
        val flight = controller.flightGeometry
        val geometry = flight ?: MemoryBubbleGeometry(
            body = headerBody,
            tailX = arrowX,
            tailTop = arrowCenterY - BubbleTipAboveChevron.toPx(),
            markStart = Offset(titleLeft + markHalf, titleTop + titleHeight / 2f),
            markEnd = Offset(
                headerBody.right - BubbleMarkCornerInsetX.toPx(),
                headerBody.bottom - BubbleMarkCornerInsetY.toPx()
            )
        )
        val body = geometry.body
        val tailTop = geometry.tailTop
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
            // The chevron sits in the tail: flying, it stays where the bubble
            // left it while any of the bubble is seen, then comes home to
            // where the header puts it now as the flight lands.
            val home = Offset(arrowX, arrowCenterY)
            val chevron = if (flight == null) {
                home
            } else {
                val left = Offset(flight.tailX, flight.tailTop + BubbleTipAboveChevron.toPx())
                lerp(left, home, memoryArrowHomecoming(controller.flyAway.value))
            }
            val arrowLeft = (chevron.x - touch / 2f).roundToInt()
            val arrowTop = (chevron.y - touch / 2f).roundToInt()
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

/** The bubble's springs: [out] as it speaks, [back] folding away, [fly] flying off into the arrow. */
@Immutable
internal class MemoryBubbleSprings(
    val out: FiniteAnimationSpec<Float>,
    val back: FiniteAnimationSpec<Float>,
    val fly: FiniteAnimationSpec<Float>
)

/**
 * Moves the bubble to where [speaking] puts it: out from the title, or —
 * spoken — off into the arrow it left from ([MemoryBubbleController.flightGeometry]),
 * or folded back beside the title. All of it on steady frames: the tap that
 * tucks it may also open a detail column, whose first frames re-lay Home out
 * and come late — by the wall clock the whole flight would fall between two
 * of them and read as a cut.
 */
internal suspend fun MemoryBubbleController.settle(
    speaking: Boolean,
    reduced: Boolean,
    springs: MemoryBubbleSprings,
    // A tip is a plain capsule with no tail (owner 2026-10-17): it folds back into the title
    // instead of flying off into the arrow.
    flyHome: Boolean = true,
) {
    val target = if (speaking) 1f else 0f
    var departure: MemoryBubbleGeometry? = null
    try {
        withSteadyFrames {
            when {
                reduced -> Unit
                speaking -> {
                    // Caught flying away: start over from the title.
                    if (flyAway.value > 0f) {
                        flightGeometry = null
                        unroll.snapTo(0f)
                        markIn.snapTo(0f)
                        flyAway.snapTo(0f)
                    }
                    // The mark squeezes in and blooms, holds a beat beside the
                    // title, then rolls off with the bubble unrolling behind it.
                    if (unroll.value <= 0f) {
                        markIn.animateTo(1f, springs.out)
                        delay(MarkHoldMs)
                    } else {
                        // Caught mid-tuck: straight back out.
                        launch { markIn.animateTo(1f, springs.out) }
                    }
                    unroll.animateTo(1f, springs.out)
                }
                // Spoken: it flies off into the arrow and the title comes back.
                flyHome && unroll.value >= FlyAwayFrom -> {
                    departure = geometry
                    flightGeometry = departure
                    flyAway.animateTo(1f, springs.fly)
                }
                // Still only the mark beside the title (or a tip): it folds away.
                else -> {
                    unroll.animateTo(0f, springs.back)
                    markIn.animateTo(0f, springs.back)
                }
            }
        }
        // Land exactly: a spring settles within its visibility threshold,
        // and a bubble left at 0.004 would linger as a ghost. (Flown away,
        // the reset is invisible: the title already reads as at rest.)
        unroll.snapTo(target)
        markIn.snapTo(target)
        flyAway.snapTo(0f)
    } finally {
        // Landed, or cut short (called back, the app gone to the
        // background): the header places it again.
        if (departure != null && flightGeometry === departure) flightGeometry = null
    }
}

/** Flying away, it shrinks to this (about the tail's tip) and lifts this far. */
private const val FlyShrink = 0.75f
private val FlyLift = 20.dp

/** Flying away, it fades from this far into the flight and is gone by this far. */
private const val FLY_FADE_FROM = 0.35f
private const val FLY_GONE_AT = 0.9f

/** How much of the bubble is still seen [fly] into its flight: it fades over the second half. */
internal fun memoryBubbleFlightAlpha(fly: Float): Float = 1f - smoothstep(FLY_FADE_FROM, FLY_GONE_AT, fly)

/**
 * How far the chevron is on its way home [fly] into the flight: 0 while any of
 * the bubble is seen — it stays in the tail it sat in — and 1 as it lands, at
 * the place (and the resting ink) the header gives it now. Home may have
 * moved it meanwhile: a detail column re-lays the header out under the tap.
 */
internal fun memoryArrowHomecoming(fly: Float): Float = smoothstep(FLY_GONE_AT, 1f, fly)

/**
 * Flying away into the arrow on [fly]: shrinks toward the tail's tip at [tip]
 * (in this layer's own space), lifts, and fades out ([memoryBubbleFlightAlpha]).
 */
private fun GraphicsLayerScope.flyIntoTip(fly: Float, tip: Offset) {
    if (fly <= 0f || size.width <= 0f || size.height <= 0f) return
    val scale = 1f - FlyShrink * fly
    scaleX *= scale
    scaleY *= scale
    transformOrigin = TransformOrigin(tip.x / size.width, tip.y / size.height)
    translationY -= FlyLift.toPx() * fly
    alpha *= memoryBubbleFlightAlpha(fly)
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
    // What it says (a memory, [memoryBubbleSubject], or a tip); null = the pill's latest.
    say: BubbleSay?,
    // 0 = the speech bubble with its tail, 1 = a tip's plain capsule.
    capsule: () -> Float,
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
    val shownSay = say ?: BubbleSay.Memory(shown?.latest)
    val latest = when (shownSay) {
        is BubbleSay.Memory -> shownSay.latest ?: shown?.latest
        is BubbleSay.Tip -> null
    }
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
    val description = if (shownSay is BubbleSay.Tip) {
        stringResource(shownSay.tip.text)
    } else if (latest == null) {
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
                    val c = capsule()
                    val round = corner + (body.height / 2f - corner).coerceAtLeast(0f) * c
                    memoryBubbleOutline(bodyPath, body, round, null, 0f, flank, lean, drop)
                    drawPath(bodyPath, color = fill)
                    // The tail rises last, out of the body's top, its point
                    // over the chevron — pulled along if the body hasn't
                    // reached it yet.
                    val rise = smoothstep(TailRisesFrom, 1f, s) * (1f - c)
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
    Box(
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
    ) {
        // A chained line turns into the next one in place: the old one rises
        // out as the new one rises in (spatial spring), each fading on the
        // effects spring. The first line simply unrolls with the body.
        AnimatedContent(
            targetState = shownSay,
            transitionSpec = {
                if (reduced) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    (
                        YoinMotion.slideInVertically(role = YoinMotionRole.Standard) { it / 2 } +
                            YoinMotion.fadeIn(role = YoinMotionRole.Standard)
                        ) togetherWith (
                        YoinMotion.slideOutVertically(role = YoinMotionRole.Standard) { -it / 2 } +
                            YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                        ) using SizeTransform(clip = false)
                }
            },
            contentAlignment = Alignment.CenterStart,
            label = "memoryBubbleSay",
        ) { line ->
            when (line) {
                is BubbleSay.Tip -> BubbleTipLine(tip = line.tip)
                is BubbleSay.Memory -> BubbleMemoryLine(
                    latest = line.latest ?: shown?.latest,
                    fill = fill,
                    headline = headline,
                )
            }
        }
    }
}

/** A tip: the symbol in a small round tile where a memory's cover sits, and one or two lines. */
@Composable
private fun BubbleTipLine(tip: HomeBubbleTip) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(BubbleCoverSize)
                .background(colors.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = tip.icon,
                contentDescription = null,
                tint = colors.onSecondaryContainer,
                modifier = Modifier.size(BubbleTipIconSize),
            )
        }
        Spacer(modifier = Modifier.width(BubbleCoverTitleGap))
        Text(
            text = stringResource(tip.text),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A memory: its cover, its album, and — when there is room — when it was written and its own title. */
@Composable
private fun BubbleMemoryLine(
    latest: HomeMemoryPill.Latest?,
    fill: Color,
    headline: TextStyle,
) {
    val colors = MaterialTheme.colorScheme
    val resources = LocalResources.current
    val writtenAgo = latest?.writtenAtMillis?.let { remember(it) { formatTimeAgo(it, resources) } }
    Layout(
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
private val BubbleTipIconSize = 22.dp
private val BubbleAgoGap = 8.dp

/** The tail rises over the last stretch of the unroll. */
private const val TailRisesFrom = 0.82f
