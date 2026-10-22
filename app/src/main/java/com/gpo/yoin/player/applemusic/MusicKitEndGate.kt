package com.gpo.yoin.player.applemusic

/** Identity supplied by MusicKit, rather than a catalog id shared by repeated requests. */
internal data class MusicKitItemIdentity(val queueId: Long, val catalogId: String)

/**
 * MusicKit sends onItemEnded for Stop, Skip and queue replacement too. A native end is therefore
 * only a candidate: it must belong to this prepared queue, follow recent real progress near the
 * end, and settle on the same final item with repeat disabled. No timer manufactures completion.
 *
 * The SDK does not echo our command epoch. Queue ids seen before a replacement are retired for
 * this controller's lifetime; an ambiguous/reused id cannot prove completion of a new request.
 */
internal class MusicKitEndGate {
    var queueEpoch: Long = 0
        private set
    var ended: Boolean = false
        private set
    var completedPositionMs: Long = 0
        private set
    var startItem: MusicKitItemIdentity? = null
        private set

    private var expectedCatalogIds = emptyList<String>()
    private val retiredIds = mutableSetOf<Long>()
    private var queueItems = emptyMap<Long, MusicKitItemIdentity>()
    private var canConfirmQueue = false
    private var finalItem: MusicKitItemIdentity? = null
    private var commandEpoch = 0L
    private var progress: Progress? = null
    private var lastAdvancing: Progress? = null
    private var candidate: Candidate? = null
    private var awaitingItem: ItemChange? = null

    fun beginQueue(catalogIds: List<String>, previousQueueIds: Set<Long>): Long {
        invalidate(previousQueueIds)
        expectedCatalogIds = catalogIds
        canConfirmQueue = true
        return queueEpoch
    }

    /** Call before the SDK operation, including operations that can call back synchronously. */
    fun invalidate(previousQueueIds: Set<Long> = emptySet()) {
        retiredIds += previousQueueIds
        retiredIds += queueItems.keys
        startItem?.let { retiredIds += it.queueId }
        queueEpoch++
        expectedCatalogIds = emptyList()
        queueItems = emptyMap()
        canConfirmQueue = false
        finalItem = null
        startItem = null
        interrupt()
    }

    /** The first item must be the requested item and have a fresh native queue identity. */
    fun confirmStart(item: MusicKitItemIdentity?): Boolean {
        if (startItem != null) return item == startItem
        if (item == null || item.catalogId != expectedCatalogIds.firstOrNull() || item.queueId in retiredIds) {
            return false
        }
        startItem = item
        return true
    }

    /**
     * A full native tail proves which concrete item is last. Missing, substituted or duplicated
     * native ids leave completion unknown; they do not prevent ordinary playback.
     */
    fun confirmQueue(items: List<MusicKitItemIdentity>) {
        if (!canConfirmQueue || queueItems.isNotEmpty() || items.firstOrNull() != startItem || items.isEmpty()) return
        if (items.map { it.queueId }.distinct().size != items.size || items.any { it.queueId in retiredIds }) return
        if (items.map { it.catalogId }.groupingBy { it }.eachCount() !=
            expectedCatalogIds.groupingBy { it }.eachCount()
        ) {
            return
        }
        queueItems = items.associateBy { it.queueId }
        finalItem = items.last()
    }

    /** Pause, seek, mode changes and queue edits all invalidate previously collected evidence. */
    fun interrupt() {
        commandEpoch++
        ended = false
        completedPositionMs = 0
        progress = null
        lastAdvancing = null
        candidate = null
        awaitingItem = null
    }

    fun expectItemChange(from: MusicKitItemIdentity?, targetQueueId: Long?) {
        interrupt()
        awaitingItem = ItemChange(from, targetQueueId)
    }

    /** A queue edit cannot inherit the old final-item proof. A new prepare can establish it again. */
    fun queueEdited() {
        interrupt()
        retiredIds += queueItems.keys
        queueItems = emptyMap()
        finalItem = null
        canConfirmQueue = false
    }

    fun observe(sample: Sample) {
        if (ended) return
        val item = sample.item
        val known = item != null && queueItems[item.queueId] == item
        val waiting = awaitingItem
        if (waiting != null) {
            val reached = item != null && item != waiting.from &&
                (waiting.targetQueueId == null || item.queueId == waiting.targetQueueId)
            if (!reached) return
            awaitingItem = null
        }
        if (!known || !sample.permitProgress || !sample.repeatOff || sample.durationMs <= 0) {
            progress = null
            lastAdvancing = null
            candidate = null
            return
        }
        val end = candidate
        if (end != null) {
            if (item != end.item || sample.stopped || !sample.finalItem ||
                sample.nowMs - end.atMs !in 0..END_SETTLE_MS
            ) {
                candidate = null
            } else if (!sample.playing && !sample.buffering && end.queueEpoch == queueEpoch &&
                end.commandEpoch == commandEpoch
            ) {
                ended = true
                completedPositionMs = end.durationMs
                return
            }
        }
        if (sample.durationMs <= 0 || sample.positionMs < 0) {
            progress = null
            lastAdvancing = null
            candidate = null
            return
        }
        if (!sample.playing || sample.buffering) return
        val previous = progress
        // Duplicate native callbacks do not erase the last genuine advancing sample, or refresh it.
        if (previous?.item == item && previous.positionMs == sample.positionMs &&
            kotlin.math.abs(sample.durationMs - previous.durationMs) <= DURATION_SLACK_MS
        ) {
            return
        }
        val elapsed = previous?.let { sample.nowMs - it.atMs }
        val advanced = previous != null && previous.item == item && elapsed != null &&
            elapsed in 1..PROGRESS_FRESH_MS && sample.positionMs > previous.positionMs &&
            sample.positionMs - previous.positionMs <= elapsed + POSITION_JUMP_SLACK_MS &&
            kotlin.math.abs(sample.durationMs - previous.durationMs) <= DURATION_SLACK_MS
        progress = Progress(
            item = requireNotNull(item),
            positionMs = sample.positionMs,
            durationMs = sample.durationMs,
            atMs = sample.nowMs
        )
        lastAdvancing = if (advanced) progress else null
    }

    /** Merely stores evidence. ENDED is published only after an observed terminal state. */
    fun onItemEnded(item: MusicKitItemIdentity?, endPositionMs: Long, nowMs: Long, permitted: Boolean) {
        if (ended || candidate != null || !permitted || awaitingItem != null) return
        if (item == null || item != finalItem) return
        val last = lastAdvancing ?: return
        if (last.item != item || nowMs - last.atMs !in 0..PROGRESS_FRESH_MS) return
        val latestAllowedPosition = last.durationMs + DURATION_SLACK_MS
        val tailStart = (last.durationMs - END_WINDOW_MS).coerceAtLeast(0)
        if (last.positionMs !in tailStart..latestAllowedPosition) {
            return
        }
        val nativeEndStart = (last.durationMs - NATIVE_END_SLACK_MS).coerceAtLeast(0)
        if (endPositionMs !in nativeEndStart..latestAllowedPosition) {
            return
        }
        candidate = Candidate(queueEpoch, commandEpoch, item, last.durationMs, nowMs)
    }

    data class Sample(
        val nowMs: Long,
        val item: MusicKitItemIdentity?,
        val playing: Boolean,
        val buffering: Boolean,
        val stopped: Boolean = false,
        val positionMs: Long,
        val durationMs: Long,
        val repeatOff: Boolean,
        val finalItem: Boolean,
        val permitProgress: Boolean
    )

    private data class Progress(
        val item: MusicKitItemIdentity,
        val positionMs: Long,
        val durationMs: Long,
        val atMs: Long
    )

    private data class Candidate(
        val queueEpoch: Long,
        val commandEpoch: Long,
        val item: MusicKitItemIdentity,
        val durationMs: Long,
        val atMs: Long
    )

    private data class ItemChange(val from: MusicKitItemIdentity?, val targetQueueId: Long?)
}

private const val END_WINDOW_MS = 2_000L
private const val NATIVE_END_SLACK_MS = 1_000L
private const val DURATION_SLACK_MS = 2_000L
private const val POSITION_JUMP_SLACK_MS = 500L
private const val PROGRESS_FRESH_MS = 3_500L
private const val END_SETTLE_MS = 3_500L
