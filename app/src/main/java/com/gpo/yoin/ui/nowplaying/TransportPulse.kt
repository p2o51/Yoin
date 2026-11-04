package com.gpo.yoin.ui.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * What the Now Playing background answers on a committed playback change
 * (D4 §1, owner pick 2026-10-05: option B + the P0 corrections).
 *
 *  - Play/pause keeps the approved breath — a ring (play) or sink (pause) from
 *    the PLAY button that was tapped, or from the neutral point when nothing
 *    was tapped (headset, notification, audio focus).
 *  - A song change while the lyrics are the primary surface flows the new
 *    song's colours in WITH the lyric stream (next: up from below, previous:
 *    down from above) — from any source, tapped or not, except when the outro
 *    already handed the next song over in place (that reveal IS the transition).
 *  - A song change while the cover is the primary surface flows the new
 *    colours in from the tapped side (NEXT: from the end, PREVIOUS: from the
 *    start; owner 2026-11-02, gradient lab round 3) — only for a fresh tap.
 *    Untapped changes (auto-advance, external controls, queue taps) never
 *    reuse an old button: the colours just cross over in place.
 */
internal sealed interface TransportPulse {
    data object None : TransportPulse

    /** Ring (play) or sink (pause) from [focalRoot]; null = the neutral point above the controls. */
    data class Burst(val isPlay: Boolean, val focalRoot: Offset?) : TransportPulse

    /** The colours flow in with the lyric stream; [forward] = rising from below (next / auto-advance). */
    data class FlowLight(val forward: Boolean) : TransportPulse

    /** The colours flow in sideways; [fromEnd] = from the end side (NEXT), else from the start (PREVIOUS). */
    data class SideFlow(val fromEnd: Boolean) : TransportPulse
}

/** The committed state the pulse reacts to — never the playhead, so ticks can't fire it. */
data class TransportPulseKey(
    val songId: String,
    val isPlaying: Boolean,
    val queueIndex: Int,
)

internal enum class TransportTapKind { PlayPause, SkipNext, SkipPrevious }

/**
 * A transport button tap, stamped when it happened ([uptimeMs] on the
 * `SystemClock.uptimeMillis` clock). The player commits hundreds of ms later
 * (Spotify: up to a second), so the pulse only trusts a tap younger than
 * [TransportTapValidityMs] and uses it once.
 *
 * [songId] / [positionMs]: the song on screen and its playhead when tapped
 * (null / [UnknownTapPositionMs] when not known), so a PREVIOUS tap that only
 * restarts that song can be told apart from one that changes it
 * ([restartedInPlace]).
 */
internal data class TransportTap(
    val centerRoot: Offset,
    val uptimeMs: Long,
    val kind: TransportTapKind,
    val songId: Any? = null,
    val positionMs: Long = UnknownTapPositionMs,
)

/** [TransportTap.positionMs] when the playhead wasn't known at the tap. */
internal const val UnknownTapPositionMs = -1L

/**
 * A PREVIOUS tap that only sent the playhead back on the song it was tapped on
 * (Spotify restarts a song past its first seconds; a one-song queue wraps onto
 * itself) commits no song change, so no pulse ever spends it. Left standing
 * for its [TransportTapValidityMs] it would swallow the next headset or
 * notification play/pause breath as a "skip flicker", and lend its button to
 * an untapped song change. It is spent the moment the playhead is seen back
 * on the same song: [songId] / [positionMs] are the song on screen and its
 * (song-scoped) playhead now. A jump of [PreviousRestartJumpMs] or more is a
 * seek, never playback jitter; a real previous-song change shows up under a
 * different [songId] and is left for the pulse.
 */
internal fun TransportTap.restartedInPlace(songId: Any?, positionMs: Long): Boolean =
    kind == TransportTapKind.SkipPrevious &&
        this.songId != null &&
        this.songId == songId &&
        this.positionMs != UnknownTapPositionMs &&
        positionMs <= this.positionMs - PreviousRestartJumpMs

/** How far back the playhead must land on the tapped song to read as a restart. */
internal const val PreviousRestartJumpMs = 1_000L

/**
 * The open lyrics page staged [toSongId] in place of [fromSongId] (its up-next
 * block handed over at the top), so that song change swaps without motion.
 */
internal data class LyricsHandover(val fromSongId: Any, val toSongId: Any)

/**
 * Where the lyrics column sits (root px) and whether it is what the eye is on.
 * [clipLight]: a column beside the cover (two panes, landscape) keeps the flow
 * light inside itself; a single full-width column lets it bleed.
 */
internal data class LyricsSurface(
    val owner: Any,
    val boundsRoot: Rect,
    val primary: Boolean,
    val landingFromTopPx: Float,
    val clipLight: Boolean,
)

internal data class TransportPulseDecision(
    val pulse: TransportPulse,
    /** The tap was spent on this change; clear it so nothing reuses it. */
    val tapUsed: Boolean,
    /** What the next resolution should read as [resolveTransportPulse]'s `settle`. */
    val settle: PlaySettle? = null,
)

/**
 * The player owes [songId] a return to playing that is NOT a play/pause.
 * Media3 commits a new item before it has buffered it, so a skip (tapped or
 * not) lands paused and resumes a few hundred ms later as a separate commit —
 * after the skip's tap was already spent on the song change. A PREVIOUS the
 * player answered with a restart buffers the same way. Without this, that
 * resume read as an untapped play and rang the neutral breath right after the
 * flow light (2026-10-05 device QA, Subsonic, every skip).
 *
 * Absorbed, unless PLAY itself was just tapped: the resume to playing until
 * [resumeUntilUptimeMs] (once — it ends the settle), and a pause until
 * [dipUntilUptimeMs] — the buffering dip itself when it commits only after the
 * skip / restart was seen ([PlaySettleDipWindowMs]: far shorter than any hand
 * reaching for a headset). Any later pause is a real pause and ends it.
 */
internal data class PlaySettle(
    val songId: Any,
    val resumeUntilUptimeMs: Long,
    val dipUntilUptimeMs: Long,
) {
    fun absorbs(songId: Any, isPlaying: Boolean, nowUptimeMs: Long): Boolean =
        this.songId == songId &&
            nowUptimeMs <= if (isPlaying) resumeUntilUptimeMs else dipUntilUptimeMs

    companion object {
        /** After a skip / restart seen at [nowUptimeMs] whose resume is owed until [resumeUntilUptimeMs]. */
        fun after(
            songId: Any,
            nowUptimeMs: Long,
            resumeUntilUptimeMs: Long = nowUptimeMs + TransportTapValidityMs,
        ) = PlaySettle(songId, resumeUntilUptimeMs, dipUntilUptimeMs = nowUptimeMs + PlaySettleDipWindowMs)
    }
}

/** How long after a skip / restart is seen its buffering dip may still commit. */
internal const val PlaySettleDipWindowMs = 600L

/**
 * A user seek (a lyric line, a note row, the wave bar) on [songId] while it
 * [playing]: the player may buffer at the new spot — a pause commits, then
 * the resume — and that dip is the seek's, not a play/pause. It settles in
 * the same windows as a skip's ([PlaySettle.after]); a headset pause past the
 * dip window still breathes. Seeking while paused has no resume owed: null.
 */
internal fun seekPlaySettle(songId: Any?, playing: Boolean, nowUptimeMs: Long): PlaySettle? =
    if (songId != null && playing) PlaySettle.after(songId, nowUptimeMs) else null

/** A tap counts for the change it caused for this long (covers Spotify's round trip). */
internal const val TransportTapValidityMs = 3_000L

/**
 * Pure P0 + option-B rule (unit-tested). Only a change between two non-null
 * keys pulses, so entering / leaving playback and the first frame never flash.
 * [settle]: the previous decision's [TransportPulseDecision.settle] (or the
 * one a restarted PREVIOUS armed); hand the returned one to the next call.
 */
internal fun resolveTransportPulse(
    previous: TransportPulseKey?,
    current: TransportPulseKey?,
    tap: TransportTap?,
    nowUptimeMs: Long,
    lyricsPrimary: Boolean,
    handover: LyricsHandover?,
    settle: PlaySettle? = null,
): TransportPulseDecision {
    if (previous == null || current == null || previous == current) {
        return TransportPulseDecision(TransportPulse.None, tapUsed = false, settle = settle)
    }
    val freshTap = tap?.takeIf { nowUptimeMs - it.uptimeMs in 0L..TransportTapValidityMs }
    val skipTap = freshTap?.takeIf { it.kind != TransportTapKind.PlayPause }
    // songId changed → a skip, whatever isPlaying did alongside it.
    if (previous.songId != current.songId) {
        val pulse = when {
            lyricsPrimary &&
                handover?.fromSongId == previous.songId &&
                handover.toSongId == current.songId -> TransportPulse.None
            lyricsPrimary -> TransportPulse.FlowLight(
                forward = lyricFlowForward(previous.queueIndex, current.queueIndex),
            )
            skipTap != null -> TransportPulse.SideFlow(fromEnd = skipTap.kind == TransportTapKind.SkipNext)
            else -> TransportPulse.None
        }
        // Skipped away from playing: the new song may still be buffering (it
        // landed paused, or dips right after), and that dip / resume belongs
        // to this skip (see PlaySettle).
        return TransportPulseDecision(
            pulse,
            tapUsed = skipTap != null,
            settle = if (previous.isPlaying) PlaySettle.after(current.songId, nowUptimeMs) else null,
        )
    }
    if (previous.isPlaying == current.isPlaying) {
        return TransportPulseDecision(TransportPulse.None, tapUsed = false, settle = settle)
    }
    // A skip in flight: the player may flicker paused → playing on its way to
    // the next song. That flicker is not a play/pause.
    if (skipTap != null) return TransportPulseDecision(TransportPulse.None, tapUsed = false, settle = settle)
    val playTap = freshTap?.takeIf { it.kind == TransportTapKind.PlayPause }
    // The skip / restart buffering (its dip, then its resume) — unless PLAY was just tapped.
    if (playTap == null && settle?.absorbs(current.songId, current.isPlaying, nowUptimeMs) == true) {
        return TransportPulseDecision(
            TransportPulse.None,
            tapUsed = false,
            settle = if (current.isPlaying) null else settle,
        )
    }
    return TransportPulseDecision(
        TransportPulse.Burst(isPlay = current.isPlaying, focalRoot = playTap?.centerRoot),
        tapUsed = playTap != null,
    )
}

/**
 * Which way the lyric stream moves on a song change: forward (the next song
 * rises from below) unless the queue position went back. The same rule as
 * [com.gpo.yoin.ui.component.LyricsTrackTransition], so the light always
 * travels with the lines.
 */
internal fun lyricFlowForward(previousQueueIndex: Int, queueIndex: Int): Boolean =
    queueIndex >= previousQueueIndex

/**
 * Where the new song's title card settles below the top of the expanded
 * lyrics page: the list's 48dp top content padding + half a title card
 * (6dp row padding, a 32sp headline, 4dp, a 24sp artist line, 6dp → 72dp).
 */
internal val LyricsPageLandingFromTop = 84.dp

/** The compact lyrics window has no title card: its first line sits under 12dp of padding. */
internal val LyricsWindowLandingFromTop = 28.dp

/**
 * Publishes this node as the lyrics column to the transport signal: its bounds
 * (root px, every layout pass — no recomposition) and whether the lyrics are
 * the primary surface right now ([primary]: the decision is the layout's), and
 * where the new title lands ([landingFromTop]).
 * Several reporters may exist during a posture swap; the last to compose
 * owns the signal, and leaving composition releases only what it owns.
 */
@Composable
internal fun Modifier.reportLyricsSurface(
    primary: Boolean,
    landingFromTop: Dp,
    clipLight: Boolean,
): Modifier {
    val signal = LocalNowPlayingTransportSignal.current ?: return this
    val owner = remember { Any() }
    val landingPx = with(LocalDensity.current) { landingFromTop.toPx() }
    SideEffect { signal.claimLyricsSurface(owner, primary, landingPx, clipLight) }
    DisposableEffect(signal, owner) {
        onDispose { signal.releaseLyricsSurface(owner) }
    }
    return onGloballyPositioned { signal.moveLyricsSurface(owner, it.boundsInRoot()) }
}
