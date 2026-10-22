package com.gpo.yoin.debug

import kotlin.math.abs

/** One command/entry attempt. User commands invalidate both queued and already running natural handoffs. */
internal data class MixedPlaylistTicket(val queueEpoch: Long, val entryId: String)

internal class MixedPlaylistEpoch {
    var current: MixedPlaylistTicket? = null
        private set
    private var epoch = 0L
    private var armed = false

    fun begin(entryId: String): MixedPlaylistTicket {
        armed = false
        return MixedPlaylistTicket(++epoch, entryId).also { current = it }
    }

    fun isCurrent(ticket: MixedPlaylistTicket): Boolean = current == ticket

    fun arm(ticket: MixedPlaylistTicket) {
        if (isCurrent(ticket)) armed = true
    }

    fun claimNaturalEnd(ticket: MixedPlaylistTicket): Boolean {
        if (!isCurrent(ticket) || !armed) return false
        armed = false
        return true
    }
}

/** Intent flags never enter this gate: samples must come from fresh native MusicKit publications. */
internal class MixedNativeStopGate(
    private val beforeSequence: Long,
    private val stableMs: Long = 400,
    private val positionSlackMs: Long = 100
) {
    private var firstSequence = -1L
    private var firstAtMs = 0L
    private var firstPositionMs = 0L

    fun observe(
        sequence: Long,
        elapsedMs: Long,
        stopped: Boolean,
        playing: Boolean,
        error: Boolean,
        positionMs: Long
    ): Boolean {
        if (sequence <= beforeSequence || !stopped || playing || error) {
            firstSequence = -1
            return false
        }
        if (firstSequence < 0 || abs(positionMs - firstPositionMs) > positionSlackMs) {
            firstSequence = sequence
            firstAtMs = elapsedMs
            firstPositionMs = positionMs
            return false
        }
        return sequence > firstSequence && elapsedMs - firstAtMs >= stableMs
    }
}
