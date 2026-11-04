package com.gpo.yoin.player.scrobble

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** The account plays are reported to: the active Subsonic profile. */
interface ScrobbleTarget {
    val profileId: String

    /** "Now playing" through plain scrobble (submission=false). */
    suspend fun nowPlaying(songId: String)

    /**
     * Plays (submission=true), oldest first. Throws [ScrobbleRejectedException] when
     * the server refuses them for good.
     */
    suspend fun submit(plays: List<PendingScrobble>)

    /** Whether the server has the OpenSubsonic `playbackReport` extension. */
    suspend fun supportsPlaybackReport(): Boolean

    /** OpenSubsonic reportPlayback, now-playing only: plays are still counted through [submit]. */
    suspend fun reportPlayback(songId: String, state: PlaybackReportState, positionMs: Long)
}

/** The server refused a play for good (a song it no longer has): retrying won't help. */
class ScrobbleRejectedException(message: String?, cause: Throwable? = null) : Exception(message, cause)

/**
 * Tells the Subsonic server what is played. [onState] takes every player change
 * (call it on one thread, the main one); plays that count go into a queue kept on
 * disk and are sent from there, so a play heard offline or while the server was
 * down is sent later, dated when it was heard. The queue is sent again whenever
 * [flush] is called: a new play, a network coming back, another account becoming
 * active.
 *
 * Plays are always counted by the app's own [ScrobbleRule] and sent through
 * scrobble, also on servers with reportPlayback (sent with ignoreScrobble=true), so
 * a play is counted once whether it was heard online or off.
 */
class ScrobbleReporter(
    private val scope: CoroutineScope,
    private val store: PendingScrobbleStore,
    private val target: () -> ScrobbleTarget?,
    monotonicMs: () -> Long,
    wallMs: () -> Long,
    rule: ScrobbleRule = ScrobbleRule(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private sealed interface Work {
        data class Report(val event: ScrobbleEvent) : Work
        data object Flush : Work
    }

    private val tracker = ScrobbleTracker(rule, monotonicMs, wallMs)
    private val work = Channel<Work>(Channel.UNLIMITED)
    private var lastSnapshot: ScrobbleSnapshot? = null
    private var dueJob: Job? = null
    private var queue: List<PendingScrobble>? = null

    init {
        scope.launch {
            for (item in work) {
                try {
                    when (item) {
                        is Work.Report -> handle(item.event)
                        Work.Flush -> flushQueue()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Reporting must never take playback down with it.
                    log("scrobble work failed", error)
                }
            }
        }
    }

    /** The player now; null when nothing a Subsonic server should hear about plays. */
    fun onState(snapshot: ScrobbleSnapshot?) {
        if (snapshot == lastSnapshot) return
        lastSnapshot = snapshot
        evaluate()
    }

    /** Send the queued plays the active account can take. */
    fun flush() {
        work.trySend(Work.Flush)
    }

    private fun evaluate() {
        tracker.update(lastSnapshot).forEach { event -> work.trySend(Work.Report(event)) }
        dueJob?.cancel()
        val wait = tracker.msUntilSubmit() ?: return
        dueJob = scope.launch {
            delay(wait)
            dueJob = null
            evaluate()
        }
    }

    private suspend fun handle(event: ScrobbleEvent) {
        if (event is ScrobbleEvent.Submit) {
            enqueue(PendingScrobble(event.profileId, event.songId, event.startedAtMs))
            flushQueue()
            return
        }
        val account = target()?.takeIf { it.profileId == event.profileId } ?: return
        // Now playing is live or nothing: a failure (offline) is dropped, never queued.
        try {
            val reports = account.supportsPlaybackReport()
            when (event) {
                is ScrobbleEvent.Started ->
                    if (reports) {
                        account.reportPlayback(event.songId, PlaybackReportState.STARTING, event.positionMs)
                    } else {
                        account.nowPlaying(event.songId)
                    }
                is ScrobbleEvent.StateChanged ->
                    if (reports) account.reportPlayback(event.songId, event.state, event.positionMs)
                is ScrobbleEvent.Submit -> Unit
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log("now playing not sent", error)
        }
    }

    private suspend fun pending(): List<PendingScrobble> =
        queue ?: withContext(ioDispatcher) { store.load() }.also { queue = it }

    private suspend fun persist(next: List<PendingScrobble>) {
        queue = next
        withContext(ioDispatcher) { store.save(next) }
    }

    private suspend fun enqueue(play: PendingScrobble) {
        persist(PendingScrobbles.add(pending(), play))
    }

    private suspend fun flushQueue() {
        val account = target() ?: return
        while (true) {
            val batch = PendingScrobbles.batchFor(pending(), account.profileId)
            if (batch.isEmpty()) return
            when (send(account, batch)) {
                SendResult.SENT, SendResult.REJECTED -> persist(PendingScrobbles.remove(pending(), batch))
                SendResult.SPLIT -> {
                    // A refused batch: one bad play in it mustn't hold the others back.
                    if (batch.size == 1) return
                    for (play in batch) {
                        when (send(account, listOf(play))) {
                            SendResult.SENT, SendResult.REJECTED ->
                                persist(PendingScrobbles.remove(pending(), listOf(play)))
                            SendResult.SPLIT, SendResult.OFFLINE -> return
                        }
                    }
                }
                SendResult.OFFLINE -> return
            }
        }
    }

    private enum class SendResult { SENT, REJECTED, SPLIT, OFFLINE }

    private suspend fun send(account: ScrobbleTarget, plays: List<PendingScrobble>): SendResult = try {
        account.submit(plays)
        SendResult.SENT
    } catch (error: CancellationException) {
        throw error
    } catch (error: ScrobbleRejectedException) {
        if (plays.size == 1) {
            log("play dropped: ${plays.single().songId}", error)
            SendResult.REJECTED
        } else {
            SendResult.SPLIT
        }
    } catch (error: Exception) {
        log("plays kept for later (${plays.size})", error)
        SendResult.OFFLINE
    }
}
