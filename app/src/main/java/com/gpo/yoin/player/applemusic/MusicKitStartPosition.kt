package com.gpo.yoin.player.applemusic

import kotlin.math.abs

/**
 * A prepared queue can inherit MusicKit's saved position. Prepare paused, bind the requested
 * native item, then explicitly seek and observe that position before allowing autoplay. The
 * first old current-item callback cannot spend this request. Explicit positions use the same path.
 */
internal class MusicKitStartPosition {
    var pending: Boolean = false
        private set
    var confirmed: Boolean = false
        private set
    private var epoch = 0L
    private var targetMs = 0L
    private var item: MusicKitItemIdentity? = null
    private var issuedAtMs: Long? = null
    private var matchingSinceMs: Long? = null

    fun begin(queueEpoch: Long, positionMs: Long, targetItem: MusicKitItemIdentity? = null) {
        epoch = queueEpoch
        targetMs = positionMs.coerceAtLeast(0)
        item = targetItem
        issuedAtMs = null
        matchingSinceMs = null
        pending = true
        confirmed = false
    }

    fun bind(queueEpoch: Long, targetItem: MusicKitItemIdentity?) {
        if (pending && epoch == queueEpoch && item == null && targetItem != null) item = targetItem
    }

    fun cancel() {
        pending = false
        confirmed = false
        item = null
        issuedAtMs = null
        matchingSinceMs = null
    }

    fun observe(
        queueEpoch: Long,
        currentItem: MusicKitItemIdentity?,
        positionMs: Long,
        canSeek: Boolean,
        nowMs: Long
    ): Action? {
        if (!pending || epoch != queueEpoch) return null
        if (item == null || currentItem != item || !canSeek) {
            matchingSinceMs = null
            return null
        }
        val issued = issuedAtMs
        if (issued == null) {
            issuedAtMs = nowMs
            return Action.Seek(targetMs)
        }
        if (abs(positionMs - targetMs) <= POSITION_CONFIRM_SLACK_MS) {
            val matching = matchingSinceMs
            if (matching == null) {
                matchingSinceMs = nowMs
            } else if (nowMs - matching >= POSITION_CONFIRM_MS) {
                pending = false
                confirmed = true
                return Action.Confirmed
            }
        } else {
            matchingSinceMs = null
            if (nowMs - issued >= POSITION_RETRY_MS) {
                issuedAtMs = nowMs
                return Action.Seek(targetMs)
            }
        }
        return null
    }

    sealed interface Action {
        data class Seek(val positionMs: Long) : Action
        data object Confirmed : Action
    }
}

private const val POSITION_CONFIRM_SLACK_MS = 250L
private const val POSITION_CONFIRM_MS = 100L
private const val POSITION_RETRY_MS = 500L
