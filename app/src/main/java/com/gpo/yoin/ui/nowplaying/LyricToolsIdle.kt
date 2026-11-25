package com.gpo.yoin.ui.nowplaying

import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlinx.coroutines.delay

/**
 * Auto-immersive lyric tools (design.md "自动沉浸"): while synced lyrics play
 * on the Lyrics page and nothing touches the player for this long, the four
 * lyric tools step away. 2026-10-05 owner QA: the old 5s read as sluggish.
 */
internal const val LyricToolsIdleMs = 3_000L

/** Once idle, how often the count re-checks for a touch that re-armed it. */
internal const val LyricToolsIdlePollMs = 200L

/**
 * Whether the lyric tools may step away at all: playing synced lyrics that
 * follow the playhead, on the Lyrics page, where the tools are on screen —
 * and not while the user is selecting lines (their actions live in that bar).
 */
internal fun lyricToolsIdleEligible(
    isPlaying: Boolean,
    hasSyncedLyrics: Boolean,
    autoScroll: Boolean,
    lyricsPageSelected: Boolean,
    toolsOnScreen: Boolean,
    selectingLines: Boolean,
    singEnabled: Boolean = false,
): Boolean = isPlaying && hasSyncedLyrics && autoScroll && lyricsPageSelected &&
    toolsOnScreen && !selectingLines && !singEnabled

/**
 * The idle count behind the auto-hide. A touch anywhere on the player calls
 * [onInteraction] (a plain field write, no snapshot churn per pointer move);
 * [track] runs from [LyricToolsIdleEffect] and starts a FRESH count every
 * time eligibility returns, so leaving the Lyrics page and coming back re-arms.
 *
 * [idle] is where the tools are heading; [hidden] is whether they are gone.
 * Tap routing reads [hidden]: while any part of the tools still shows (the
 * fade out), a tap on a key is that key's (2026-11-25 QA: a tap on a key that
 * was plainly still fading did nothing, and the tools seemed to take taps
 * only for a moment after a scroll).
 */
@Stable
internal class LyricToolsIdleState(
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    var idle: Boolean by mutableStateOf(false)
        private set

    private var lastInteractionMs = 0L
    private var shownAlpha: State<Float>? = null
    private var focusHeld = false

    /**
     * Fully stepped away: idle AND faded to nothing ([rememberLyricToolsAlpha]).
     * Only then do the keys give up their hit area and their semantics, and a
     * tap where they stood only brings them back. With no alpha attached
     * (tests, the transport's focus count) this is [idle].
     */
    val hidden: Boolean
        get() = idle && (shownAlpha?.value ?: 0f) <= 0f

    fun onInteraction() {
        lastInteractionMs = clock()
        if (idle) idle = false
    }

    /**
     * A key holds input focus (hardware keyboard, Switch Access): keep the
     * tools while it does — no pointer touches the player then, and a focused
     * key must not vanish under the focus ring.
     */
    fun holdAwake(held: Boolean) {
        if (held == focusHeld) return
        focusHeld = held
        onInteraction()
    }

    internal fun attachAlpha(alpha: State<Float>) {
        shownAlpha = alpha
    }

    suspend fun track(eligible: Boolean, idleMs: Long = LyricToolsIdleMs) {
        idle = false
        // Long.MAX_VALUE: a screen reader explores by touch ([rememberLyricToolsIdleMs]).
        if (!eligible || idleMs == Long.MAX_VALUE) return
        lastInteractionMs = clock()
        while (true) {
            if (focusHeld) lastInteractionMs = clock()
            val elapsed = clock() - lastInteractionMs
            if (elapsed >= idleMs) {
                if (!idle) idle = true
                delay(LyricToolsIdlePollMs)
            } else {
                delay(idleMs - elapsed)
            }
        }
    }
}

@Composable
internal fun rememberLyricToolsIdleState(): LyricToolsIdleState = remember { LyricToolsIdleState() }

/**
 * Runs [state]'s idle count while [eligible], for [idleMs] — stretched to the
 * system's "Time to take action" setting, and never at all while a screen
 * reader explores by touch ([rememberLyricToolsIdleMs]).
 */
@Composable
internal fun LyricToolsIdleEffect(
    state: LyricToolsIdleState,
    eligible: Boolean,
    idleMs: Long = LyricToolsIdleMs,
) {
    val accessibleIdleMs = rememberLyricToolsIdleMs(idleMs)
    LaunchedEffect(state, eligible, accessibleIdleMs) { state.track(eligible, accessibleIdleMs) }
}

/**
 * [baseMs] as the user's accessibility settings want it. TalkBack and other
 * explore-by-touch readers send the app no touches, so nothing would ever
 * bring the tools back: they stay (Long.MAX_VALUE). Compose's
 * calculateRecommendedTimeoutMillis covers that only below API 29, so touch
 * exploration is read here directly; on top of it the "Time to take action"
 * setting lengthens the count.
 */
@Composable
internal fun rememberLyricToolsIdleMs(baseMs: Long): Long {
    val touchExploration = rememberTouchExplorationEnabled()
    val accessibility = LocalAccessibilityManager.current
    if (touchExploration) return Long.MAX_VALUE
    return remember(accessibility, baseMs) {
        accessibility?.calculateRecommendedTimeoutMillis(
            originalTimeoutMillis = baseMs,
            containsIcons = true,
            containsControls = true,
        ) ?: baseMs
    }
}

@Composable
private fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose {}
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager.addTouchExplorationStateChangeListener(listener)
        enabled = manager.isTouchExplorationEnabled
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/**
 * The tools' fade, on the effects spring, attached to [state] so tap routing
 * can tell a fading key (still the key's tap) from a gone one ([hidden]).
 */
@Composable
internal fun rememberLyricToolsAlpha(state: LyricToolsIdleState, label: String): State<Float> {
    val alpha = animateFloatAsState(
        targetValue = if (state.idle) 0f else 1f,
        animationSpec = YoinMotion.defaultEffectsSpec(role = YoinMotionRole.Standard),
        label = label,
    )
    SideEffect { state.attachAlpha(alpha) }
    return alpha
}

/**
 * Any touch on the player re-arms the idle count (a mouse or stylus hovering
 * over it too). FINAL pass on purpose: for the same down, [lyricToolsWakeGuard]
 * (Initial pass) must still read the state the finger landed on — tools
 * gone — before this clears it.
 */
internal fun Modifier.lyricToolsTouchTracker(state: LyricToolsIdleState): Modifier =
    pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Final)
                state.onInteraction()
            }
        }
    }

/**
 * The band where the tools stood, as measured from the bottom edge of the
 * node this guard sits on. Collapsing the idle tools lets other content sink
 * into that place — the single column's title and artist, the wide layout's
 * lyric lines — and a tap aimed at a tool that just stepped away landed on
 * them instead (2026-10-05 QA: the title's album route opened).
 *
 * So once the tools are gone ([LyricToolsIdleState.hidden]), the first tap
 * in the band ONLY brings them back: its down and lift are consumed on the
 * way in (Initial pass), before anything underneath can see a click. Moves
 * are left alone, so a drag that starts there (dismiss, lyrics scroll, page
 * swipe) still goes through, and once the tools are back every tap behaves
 * normally. While they are still fading out the band is not guarded: a tap
 * on a key that still shows is that key's.
 *
 * [enabled] = the layout collapses the tools' slot when they idle (where they
 * fade in place instead, [lyricToolsInPlaceWakeGuard] guards the tools).
 */
internal fun Modifier.lyricToolsWakeGuard(
    state: LyricToolsIdleState,
    enabled: Boolean,
    band: Dp,
): Modifier = if (!enabled) {
    this
} else {
    pointerInput(state, band) {
        guardWakeTaps(state) { band.toPx() }
    }
}

/**
 * The other way the tools step away: where they FADE IN PLACE (the tab row's
 * tools — the enlarged tablet at rest, the 16:9 expanded page) nothing sinks
 * into their spot, but they are just as unseen, and a blind tap on one still
 * fired it (2026-10-05 QA: the search sheet opened, or select mode). Sits on
 * the tools themselves and applies [lyricToolsWakeGuard]'s rule to their whole
 * footprint: once they are gone the first tap there only brings them back
 * (while they still show, the tap is the key's).
 */
internal fun Modifier.lyricToolsInPlaceWakeGuard(state: LyricToolsIdleState): Modifier =
    pointerInput(state) {
        guardWakeTaps(state) { size.height.toFloat() }
    }

/** Swallows each tap that lands in the bottom [bandPx] of this node while the tools are gone (see [lyricToolsWakeTapSwallowed]). */
private suspend fun PointerInputScope.guardWakeTaps(
    state: LyricToolsIdleState,
    bandPx: PointerInputScope.() -> Float,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val swallow = lyricToolsWakeTapSwallowed(
            toolsHidden = state.hidden,
            downY = down.position.y,
            nodeHeight = size.height.toFloat(),
            bandPx = bandPx(),
        )
        if (swallow) {
            down.consume()
            consumeLiftUnlessDragged(down.id, down.position.y, down.position.x)
        }
    }
}

/**
 * Pure routing rule behind [lyricToolsWakeGuard]: a down is swallowed only
 * while the tools are gone (faded out, not just fading) AND it lands inside
 * the bottom band they left.
 */
internal fun lyricToolsWakeTapSwallowed(
    toolsHidden: Boolean,
    downY: Float,
    nodeHeight: Float,
    bandPx: Float,
): Boolean = toolsHidden && bandPx > 0f && downY >= nodeHeight - bandPx && downY <= nodeHeight

/** Consumes [id]'s lift; gives up (consuming nothing more) once it turns into a drag. */
private suspend fun AwaitPointerEventScope.consumeLiftUnlessDragged(id: PointerId, downY: Float, downX: Float) {
    val slop = viewConfiguration.touchSlop
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == id } ?: return
        if (change.changedToUpIgnoreConsumed()) {
            change.consume()
            return
        }
        val dx = change.position.x - downX
        val dy = change.position.y - downY
        if (dx * dx + dy * dy > slop * slop) return
    }
}
