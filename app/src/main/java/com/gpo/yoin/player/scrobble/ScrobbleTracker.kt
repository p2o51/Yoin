package com.gpo.yoin.player.scrobble

/*
 * Play reporting ("scrobble") rules, kept free of android.* so they can move to the
 * KMP :shared module as they are and iOS (PlayerEngine.swift) can use the same ones.
 */

/**
 * When a play counts (the Last.fm rule Subsonic servers follow): the song is longer
 * than [minTrackMs] and was listened to for half its length or [maxListenMs],
 * whichever comes first. Listened time is time spent playing, so a seek forward
 * never earns a play and a pause doesn't count.
 */
data class ScrobbleRule(
    val minTrackMs: Long = 30_000L,
    val maxListenMs: Long = 240_000L,
) {
    /**
     * Listening needed for a play of a song [durationMs] long; null when the song is
     * too short to count. An unknown length (≤ 0) waits the full [maxListenMs].
     */
    fun thresholdMs(durationMs: Long): Long? = when {
        durationMs <= 0L -> maxListenMs
        durationMs <= minTrackMs -> null
        else -> minOf(durationMs / 2, maxListenMs)
    }
}

/**
 * What the player shows now, for one song from a Subsonic account. [playKey] tells
 * two plays of the same song apart (the queue entry plus a repeat count), so a
 * repeated song counts again.
 */
data class ScrobbleSnapshot(
    val profileId: String,
    val songId: String,
    val playKey: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
)

/** What a [ScrobbleTracker] asks the server to hear. */
sealed interface ScrobbleEvent {
    val profileId: String
    val songId: String

    /** The song started playing: "now playing" (scrobble submission=false, reportPlayback `starting`). */
    data class Started(
        override val profileId: String,
        override val songId: String,
        val positionMs: Long,
    ) : ScrobbleEvent

    /** Play / pause / stop of a song already started (reportPlayback only; plain scrobble has no such call). */
    data class StateChanged(
        override val profileId: String,
        override val songId: String,
        val state: PlaybackReportState,
        val positionMs: Long,
    ) : ScrobbleEvent

    /** The song was listened to long enough: one play, dated when it started (scrobble submission=true). */
    data class Submit(
        override val profileId: String,
        override val songId: String,
        val startedAtMs: Long,
    ) : ScrobbleEvent
}

/** OpenSubsonic `reportPlayback` states. */
enum class PlaybackReportState(val wire: String) {
    STARTING("starting"),
    PLAYING("playing"),
    PAUSED("paused"),
    STOPPED("stopped"),
}

/**
 * Follows the player one snapshot at a time and says what to report. Two clocks:
 * [monotonicMs] measures listening (immune to the user changing the time), [wallMs]
 * dates a play. Call [update] on every player change, and again after
 * [msUntilSubmit] runs out while the song keeps playing, so the play is counted on
 * time even when nothing else changes.
 */
class ScrobbleTracker(
    private val rule: ScrobbleRule = ScrobbleRule(),
    private val monotonicMs: () -> Long,
    private val wallMs: () -> Long,
) {
    private class Session(
        val snapshot: ScrobbleSnapshot,
        var startedAtWallMs: Long = 0L,
        var announced: Boolean = false,
        var submitted: Boolean = false,
        var listenedMs: Long = 0L,
        var playingSinceMs: Long? = null,
        var durationMs: Long = snapshot.durationMs,
        var lastPositionMs: Long = snapshot.positionMs,
        var lastReportedPlaying: Boolean = false,
    ) {
        val key get() = snapshot.profileId to snapshot.playKey
    }

    private var session: Session? = null

    /** [snapshot] is the player now; null when nothing countable plays (stopped, another provider). */
    fun update(snapshot: ScrobbleSnapshot?): List<ScrobbleEvent> {
        val now = monotonicMs()
        val events = mutableListOf<ScrobbleEvent>()
        val current = session
        if (current != null && (snapshot == null || current.key != (snapshot.profileId to snapshot.playKey))) {
            current.accrue(now)
            current.checkDue(events)
            if (current.announced) {
                events += ScrobbleEvent.StateChanged(
                    current.snapshot.profileId,
                    current.snapshot.songId,
                    PlaybackReportState.STOPPED,
                    current.lastPositionMs,
                )
            }
            session = null
        }
        if (snapshot == null) return events
        val active = session ?: Session(snapshot).also { session = it }
        active.accrue(now)
        if (snapshot.durationMs > 0L) active.durationMs = snapshot.durationMs
        active.lastPositionMs = snapshot.positionMs
        if (snapshot.isPlaying) {
            active.playingSinceMs = now
            if (!active.announced) {
                active.announced = true
                active.startedAtWallMs = wallMs()
                events += ScrobbleEvent.Started(snapshot.profileId, snapshot.songId, snapshot.positionMs)
            }
        } else {
            active.playingSinceMs = null
        }
        if (active.announced && active.lastReportedPlaying != snapshot.isPlaying) {
            active.lastReportedPlaying = snapshot.isPlaying
            events += ScrobbleEvent.StateChanged(
                snapshot.profileId,
                snapshot.songId,
                if (snapshot.isPlaying) PlaybackReportState.PLAYING else PlaybackReportState.PAUSED,
                snapshot.positionMs,
            )
        }
        active.checkDue(events)
        return events
    }

    /** Listening left before the current song counts; null when it has, can't, or isn't playing. */
    fun msUntilSubmit(): Long? {
        val active = session ?: return null
        if (active.submitted || active.playingSinceMs == null) return null
        val threshold = rule.thresholdMs(active.durationMs) ?: return null
        val listened = active.listenedMs + (monotonicMs() - active.playingSinceMs!!)
        return (threshold - listened).coerceAtLeast(0L)
    }

    private fun Session.accrue(now: Long) {
        playingSinceMs?.let { since ->
            listenedMs += (now - since).coerceAtLeast(0L)
            playingSinceMs = now
        }
    }

    private fun Session.checkDue(into: MutableList<ScrobbleEvent>) {
        if (submitted || !announced) return
        val threshold = rule.thresholdMs(durationMs) ?: return
        if (listenedMs < threshold) return
        submitted = true
        into += ScrobbleEvent.Submit(snapshot.profileId, snapshot.songId, startedAtWallMs)
    }
}
