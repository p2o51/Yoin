package com.gpo.yoin.ui.nowplaying

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.unit.Dp
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
): Boolean = isPlaying && hasSyncedLyrics && autoScroll && lyricsPageSelected &&
    toolsOnScreen && !selectingLines

/**
 * The idle count behind the auto-hide. A touch anywhere on the player calls
 * [onInteraction] (a plain field write, no snapshot churn per pointer move);
 * [track] runs from `LaunchedEffect(eligible)` and starts a FRESH count every
 * time eligibility returns, so leaving the Lyrics page and coming back re-arms.
 */
@Stable
internal class LyricToolsIdleState(
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    var idle: Boolean by mutableStateOf(false)
        private set

    private var lastInteractionMs = 0L

    fun onInteraction() {
        lastInteractionMs = clock()
        if (idle) idle = false
    }

    suspend fun track(eligible: Boolean, idleMs: Long = LyricToolsIdleMs) {
        idle = false
        if (!eligible) return
        lastInteractionMs = clock()
        while (true) {
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
 * Any touch on the player re-arms the idle count. FINAL pass on purpose: for
 * the same down, [lyricToolsWakeGuard] (Initial pass) must still read the
 * state the finger landed on — tools away — before this clears it.
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
 * So while the tools are away, the first tap in the band ONLY brings them
 * back: its down and lift are consumed on the way in (Initial pass), before
 * anything underneath can see a click. Moves are left alone, so a drag that
 * starts there (dismiss, lyrics scroll, page swipe) still goes through, and
 * once the tools are back every tap behaves normally.
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
 * footprint: while they are away the first tap there only brings them back.
 */
internal fun Modifier.lyricToolsInPlaceWakeGuard(state: LyricToolsIdleState): Modifier =
    pointerInput(state) {
        guardWakeTaps(state) { size.height.toFloat() }
    }

/** Swallows each idle tap that lands in the bottom [bandPx] of this node (see [lyricToolsWakeTapSwallowed]). */
private suspend fun PointerInputScope.guardWakeTaps(
    state: LyricToolsIdleState,
    bandPx: PointerInputScope.() -> Float,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val swallow = lyricToolsWakeTapSwallowed(
            toolsIdle = state.idle,
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
 * while the tools are away AND it lands inside the bottom band they left.
 */
internal fun lyricToolsWakeTapSwallowed(
    toolsIdle: Boolean,
    downY: Float,
    nodeHeight: Float,
    bandPx: Float,
): Boolean = toolsIdle && bandPx > 0f && downY >= nodeHeight - bandPx && downY <= nodeHeight

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
