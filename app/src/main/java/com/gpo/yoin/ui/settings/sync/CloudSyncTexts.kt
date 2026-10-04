package com.gpo.yoin.ui.settings.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.gpo.yoin.data.sync.CloudSyncPhase
import com.gpo.yoin.data.sync.CloudSyncState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

// Plain-text wording for Cloud sync, kept out of the composables so the
// Settings row, the page and the tests read the same strings.

private const val MinuteMs = 60_000L
private const val HourMs = 60 * MinuteMs
private const val DayMs = 24 * HourMs

private val SameYearDate = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
private val OtherYearDate = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

/**
 * "just now" / "2 min ago" / "3 hr ago" / "yesterday" / "4 days ago" /
 * "Sep 12" / "Sep 12, 2025". A time slightly in the future (clock skew, a
 * sync that landed after the last tick) reads as "just now".
 */
internal fun formatRelativeTime(
    thenMs: Long,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val elapsed = (nowMs - thenMs).coerceAtLeast(0L)
    return when {
        elapsed < MinuteMs -> "just now"
        elapsed < HourMs -> "${elapsed / MinuteMs} min ago"
        elapsed < DayMs -> "${elapsed / HourMs} hr ago"
        elapsed < 2 * DayMs -> "yesterday"
        elapsed < 7 * DayMs -> "${elapsed / DayMs} days ago"
        else -> {
            val then = Instant.ofEpochMilli(thenMs).atZone(zone)
            val now = Instant.ofEpochMilli(nowMs).atZone(zone)
            (if (then.year == now.year) SameYearDate else OtherYearDate).format(then)
        }
    }
}

/**
 * A certificate SHA-1 as Google Cloud Console's Android client form takes it:
 * uppercase hex pairs joined by colons ("AB:CD:…"), whatever separators or
 * case the raw value came with. Anything that isn't 20 bytes of hex is shown
 * as given (trimmed), never guessed at.
 */
internal fun formatCertSha1(raw: String): String {
    val hex = raw.filterNot { it == ':' || it == ' ' || it == '-' }.uppercase(Locale.ROOT)
    if (hex.length != 40 || hex.any { it !in '0'..'9' && it !in 'A'..'F' }) return raw.trim()
    return hex.chunked(2).joinToString(":")
}

/** "1 note" / "3 notes". */
internal fun notesCount(count: Int): String = if (count == 1) "1 note" else "$count notes"

/** The Settings row's one-line summary of where sync stands. */
internal fun cloudSyncEntrySummary(state: CloudSyncState, nowMs: Long): String =
    when (val phase = state.phase) {
        CloudSyncPhase.Off -> "Off"
        is CloudSyncPhase.Unavailable ->
            if (phase.updateRequired) "Needs a Google Play services update" else "Needs Google Play services"
        is CloudSyncPhase.Misconfigured -> "Not available in this build"
        is CloudSyncPhase.ResetElsewhere ->
            phase.deviceName?.let { "Turned off on $it" } ?: "Turned off on another device"
        CloudSyncPhase.Syncing -> "Syncing…"
        CloudSyncPhase.UpToDate -> when {
            state.pendingChanges -> "Changes waiting to upload"
            state.lastSyncAt != null -> "On · Synced ${formatRelativeTime(state.lastSyncAt, nowMs)}"
            else -> "On"
        }
        CloudSyncPhase.Offline ->
            if (state.pendingChanges) "Changes waiting to upload" else "On · Offline"
        CloudSyncPhase.NeedsReauth -> "Paused · Reconnect Google"
        CloudSyncPhase.StorageFull -> "Google storage full"
        is CloudSyncPhase.NeedsReview -> "Needs review · ${notesCount(phase.count)} disappeared"
        CloudSyncPhase.CloudCopyRemoved -> "Paused · Drive copy removed"
        is CloudSyncPhase.UpdateRequired -> "On · Update Yoin to sync everything"
        is CloudSyncPhase.Error -> "Couldn't sync"
    }

/** What the page's status row says: a title and the lines under it. */
internal data class CloudSyncStatusText(val title: String, val lines: List<String>)

internal fun cloudSyncStatusText(state: CloudSyncState, nowMs: Long): CloudSyncStatusText {
    val lastSynced = state.lastSyncAt?.let { "Last synced ${formatRelativeTime(it, nowMs)}" }
    return when (val phase = state.phase) {
        CloudSyncPhase.Syncing -> CloudSyncStatusText(
            title = "Syncing…",
            lines = listOf(lastSynced ?: "First sync — this can take a minute"),
        )
        CloudSyncPhase.UpToDate -> CloudSyncStatusText(
            title = if (state.pendingChanges) "Changes waiting to upload" else "Up to date",
            lines = listOf(lastSynced ?: "Not synced yet"),
        )
        CloudSyncPhase.Offline -> CloudSyncStatusText(
            title = "Can't reach Google",
            lines = listOfNotNull("Changes wait on this device and sync when they can.", lastSynced),
        )
        // The Reconnect panel under the row explains what to do.
        CloudSyncPhase.NeedsReauth -> CloudSyncStatusText(
            title = "Paused",
            lines = listOfNotNull(lastSynced),
        )
        CloudSyncPhase.StorageFull -> CloudSyncStatusText(
            title = "Google storage full",
            lines = listOfNotNull("Changes stay on this device and sync when there's space.", lastSynced),
        )
        is CloudSyncPhase.NeedsReview -> CloudSyncStatusText(
            title = "Needs your review",
            lines = listOfNotNull("Everything else keeps syncing.", lastSynced),
        )
        CloudSyncPhase.CloudCopyRemoved -> CloudSyncStatusText(
            title = "Drive copy removed",
            lines = listOfNotNull(lastSynced),
        )
        is CloudSyncPhase.UpdateRequired -> CloudSyncStatusText(
            title = "Update Yoin",
            lines = listOfNotNull(
                "${phase.deviceName ?: "Another device"} has a newer Yoin — some items wait until you update.",
                lastSynced,
            ),
        )
        is CloudSyncPhase.Error -> CloudSyncStatusText(
            title = "Couldn't sync",
            lines = listOfNotNull(phase.message, lastSynced),
        )
        // Off states don't show the status row; keep the mapping total.
        else -> CloudSyncStatusText(title = "Off", lines = emptyList())
    }
}

/** Wall-clock time that refreshes every [periodMs], so "2 min ago" keeps moving while the page is open. */
@Composable
internal fun rememberTickingNow(periodMs: Long = 30_000L): Long {
    val now by produceState(System.currentTimeMillis(), periodMs) {
        while (true) {
            delay(periodMs)
            value = System.currentTimeMillis()
        }
    }
    return now
}
