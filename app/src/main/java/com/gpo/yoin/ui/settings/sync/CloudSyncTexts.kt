@file:Suppress("ktlint:standard:max-line-length")

package com.gpo.yoin.ui.settings.sync

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.gpo.yoin.R
import com.gpo.yoin.data.sync.CloudSyncPhase
import com.gpo.yoin.data.sync.CloudSyncState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
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
 *
 * [resources] null keeps the English the unit test asserts, including
 * [Locale.ENGLISH] date patterns. Production passes a configuration so
 * relative counts and absolute dates follow the device locale.
 */
internal fun formatRelativeTime(
    thenMs: Long,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    resources: Resources? = null,
): String {
    val elapsed = (nowMs - thenMs).coerceAtLeast(0L)
    if (resources == null) {
        return when {
            elapsed < MinuteMs -> "just now" // i18n-allow: CloudSyncTextsTest asserts this English
            elapsed < HourMs -> "${elapsed / MinuteMs} min ago" // i18n-allow: CloudSyncTextsTest asserts this English
            elapsed < DayMs -> "${elapsed / HourMs} hr ago" // i18n-allow: CloudSyncTextsTest asserts this English
            elapsed < 2 * DayMs -> "yesterday" // i18n-allow: CloudSyncTextsTest asserts this English
            elapsed < 7 * DayMs -> "${elapsed / DayMs} days ago" // i18n-allow: CloudSyncTextsTest asserts this English
            else -> {
                val then = Instant.ofEpochMilli(thenMs).atZone(zone)
                val now = Instant.ofEpochMilli(nowMs).atZone(zone)
                (if (then.year == now.year) SameYearDate else OtherYearDate).format(then)
            }
        }
    }
    return when {
        elapsed < MinuteMs -> resources.getString(R.string.settings_sync_relative_just_now)
        elapsed < HourMs -> {
            val count = (elapsed / MinuteMs).toInt()
            resources.getQuantityString(R.plurals.settings_sync_relative_minutes, count, count)
        }
        elapsed < DayMs -> {
            val count = (elapsed / HourMs).toInt()
            resources.getQuantityString(R.plurals.settings_sync_relative_hours, count, count)
        }
        elapsed < 2 * DayMs -> resources.getString(R.string.settings_sync_relative_yesterday)
        elapsed < 7 * DayMs -> {
            val count = (elapsed / DayMs).toInt()
            resources.getQuantityString(R.plurals.settings_sync_relative_days, count, count)
        }
        else -> formatAbsoluteDate(thenMs, nowMs, zone, resources)
    }
}

private fun formatAbsoluteDate(thenMs: Long, nowMs: Long, zone: ZoneId, resources: Resources): String {
    val then = Instant.ofEpochMilli(thenMs).atZone(zone)
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    val skeleton = if (then.year == now.year) "MMMd" else "yMMMd"
    val locale = resources.configuration.locales[0]
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    val calendar = Calendar.getInstance(TimeZone.getTimeZone(zone), locale).apply {
        timeInMillis = thenMs
    }
    return DateFormat.format(pattern, calendar).toString()
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
internal fun notesCount(count: Int, resources: Resources? = null): String {
    if (resources == null) {
        return if (count == 1) {
            "1 note" // i18n-allow: CloudSyncTextsTest asserts this English
        } else {
            "$count notes" // i18n-allow: CloudSyncTextsTest asserts this English
        }
    }
    return resources.getQuantityString(R.plurals.settings_sync_notes_count, count, count)
}

/** Status on the first line, detail on the second. No connector between them. */
internal data class CloudSyncEntryText(val status: String, val detail: String? = null)

/** The Settings row: where sync stands, then the detail under it. */
internal fun cloudSyncEntrySummary(
    state: CloudSyncState,
    nowMs: Long,
    resources: Resources? = null,
): CloudSyncEntryText {
    fun line(status: String, detail: String? = null) = CloudSyncEntryText(status, detail)
    if (resources == null) {
        return when (val phase = state.phase) {
            CloudSyncPhase.Off -> line("Off") // i18n-allow: CloudSyncTextsTest asserts this English
            is CloudSyncPhase.Unavailable ->
                if (phase.updateRequired) {
                    line("Needs a Google Play services update") // i18n-allow: CloudSyncTextsTest asserts this English
                } else {
                    line("Needs Google Play services") // i18n-allow: CloudSyncTextsTest asserts this English
                }
            is CloudSyncPhase.Misconfigured ->
                line("Not available in this build") // i18n-allow: CloudSyncTextsTest asserts this English
            is CloudSyncPhase.ResetElsewhere ->
                phase.deviceName?.let { line("Turned off on $it") } // i18n-allow: CloudSyncTextsTest asserts this English
                    ?: line("Turned off on another device") // i18n-allow: CloudSyncTextsTest asserts this English
            CloudSyncPhase.Syncing -> line("Syncing…") // i18n-allow: CloudSyncTextsTest asserts this English
            CloudSyncPhase.UpToDate -> when {
                state.pendingChanges ->
                    line("Changes waiting to upload") // i18n-allow: CloudSyncTextsTest asserts this English
                state.lastSyncAt != null ->
                    line(
                        "On", // i18n-allow: CloudSyncTextsTest asserts this English
                        "Synced ${formatRelativeTime(state.lastSyncAt, nowMs)}", // i18n-allow: CloudSyncTextsTest asserts this English
                    )
                else -> line("On") // i18n-allow: CloudSyncTextsTest asserts this English
            }
            CloudSyncPhase.Offline ->
                if (state.pendingChanges) {
                    line("Changes waiting to upload") // i18n-allow: CloudSyncTextsTest asserts this English
                } else {
                    line("On", "Offline") // i18n-allow: CloudSyncTextsTest asserts this English
                }
            CloudSyncPhase.NeedsReauth ->
                line("Paused", "Reconnect Google") // i18n-allow: CloudSyncTextsTest asserts this English
            CloudSyncPhase.StorageFull ->
                line("Google storage full") // i18n-allow: CloudSyncTextsTest asserts this English
            is CloudSyncPhase.NeedsReview ->
                line(
                    "Needs review", // i18n-allow: CloudSyncTextsTest asserts this English
                    "${notesCount(phase.count)} disappeared", // i18n-allow: CloudSyncTextsTest asserts this English
                )
            CloudSyncPhase.CloudCopyRemoved ->
                line("Paused", "Drive copy removed") // i18n-allow: CloudSyncTextsTest asserts this English
            is CloudSyncPhase.UpdateRequired ->
                line("On", "Update Yoin") // i18n-allow: CloudSyncTextsTest asserts this English
            is CloudSyncPhase.Error -> line("Couldn't sync") // i18n-allow: CloudSyncTextsTest asserts this English
        }
    }
    return when (val phase = state.phase) {
        CloudSyncPhase.Off -> line(resources.getString(R.string.settings_sync_summary_off))
        is CloudSyncPhase.Unavailable ->
            if (phase.updateRequired) {
                line(resources.getString(R.string.settings_sync_summary_play_update))
            } else {
                line(resources.getString(R.string.settings_sync_summary_play_missing))
            }
        is CloudSyncPhase.Misconfigured -> line(resources.getString(R.string.settings_sync_summary_misconfigured))
        is CloudSyncPhase.ResetElsewhere ->
            phase.deviceName?.let { line(resources.getString(R.string.settings_sync_summary_reset_on, it)) }
                ?: line(resources.getString(R.string.settings_sync_summary_reset_other))
        CloudSyncPhase.Syncing -> line(resources.getString(R.string.settings_sync_summary_syncing))
        CloudSyncPhase.UpToDate -> when {
            state.pendingChanges -> line(resources.getString(R.string.settings_sync_summary_pending))
            state.lastSyncAt != null -> line(
                resources.getString(R.string.settings_sync_summary_on),
                resources.getString(
                    R.string.settings_sync_summary_synced,
                    formatRelativeTime(state.lastSyncAt, nowMs, resources = resources),
                ),
            )
            else -> line(resources.getString(R.string.settings_sync_summary_on))
        }
        CloudSyncPhase.Offline ->
            if (state.pendingChanges) {
                line(resources.getString(R.string.settings_sync_summary_pending_offline))
            } else {
                line(
                    resources.getString(R.string.settings_sync_summary_on),
                    resources.getString(R.string.settings_sync_summary_offline),
                )
            }
        CloudSyncPhase.NeedsReauth -> line(
            resources.getString(R.string.settings_sync_status_paused),
            resources.getString(R.string.settings_sync_summary_reauth),
        )
        CloudSyncPhase.StorageFull -> line(resources.getString(R.string.settings_sync_summary_storage))
        is CloudSyncPhase.NeedsReview -> line(
            resources.getString(R.string.settings_sync_summary_needs_review_status),
            resources.getQuantityString(
                R.plurals.settings_sync_summary_needs_review,
                phase.count,
                phase.count,
            ),
        )
        CloudSyncPhase.CloudCopyRemoved -> line(
            resources.getString(R.string.settings_sync_status_paused),
            resources.getString(R.string.settings_sync_summary_copy_removed),
        )
        is CloudSyncPhase.UpdateRequired -> line(
            resources.getString(R.string.settings_sync_summary_on),
            resources.getString(R.string.settings_sync_summary_update),
        )
        is CloudSyncPhase.Error -> line(resources.getString(R.string.settings_sync_summary_error))
    }
}

/** What the page's status row says: a title and the lines under it. */
internal data class CloudSyncStatusText(val title: String, val lines: List<String>)

internal fun cloudSyncStatusText(
    state: CloudSyncState,
    nowMs: Long,
    resources: Resources? = null,
): CloudSyncStatusText {
    if (resources == null) {
        val lastSynced = state.lastSyncAt?.let {
            "Last synced ${formatRelativeTime(it, nowMs)}" // i18n-allow: CloudSyncTextsTest asserts this English
        }
        return when (val phase = state.phase) {
            CloudSyncPhase.Syncing -> CloudSyncStatusText(
                title = "Syncing…", // i18n-allow: CloudSyncTextsTest asserts this English
                lines = listOfNotNull(lastSynced),
            )
            CloudSyncPhase.UpToDate -> CloudSyncStatusText(
                title = if (state.pendingChanges) {
                    "Changes waiting to upload" // i18n-allow: CloudSyncTextsTest asserts this English
                } else {
                    "Up to date" // i18n-allow: CloudSyncTextsTest asserts this English
                },
                lines = listOf(
                    lastSynced ?: "Not synced yet", // i18n-allow: CloudSyncTextsTest asserts this English
                ),
            )
            CloudSyncPhase.Offline -> CloudSyncStatusText(
                title = "Can't reach Google", // i18n-allow: CloudSyncTextsTest asserts this English
                lines = listOfNotNull(lastSynced),
            )
            // The Reconnect panel under the row explains what to do.
            CloudSyncPhase.NeedsReauth -> CloudSyncStatusText(
                title = "Paused", // i18n-allow: CloudSyncTextsTest asserts this English
                lines = listOfNotNull(lastSynced),
            )
            CloudSyncPhase.StorageFull -> CloudSyncStatusText(
                title = "Google storage full", // i18n-allow: CloudSyncTextsTest asserts this English
                lines = listOfNotNull(lastSynced),
            )
            is CloudSyncPhase.NeedsReview -> CloudSyncStatusText(
                title = "Needs your review", // i18n-allow: CloudSyncTextsTest asserts this English
                lines = listOfNotNull(lastSynced),
            )
            CloudSyncPhase.CloudCopyRemoved -> CloudSyncStatusText(
                title = "Drive copy removed", // i18n-allow: CloudSyncTextsTest asserts this English
                lines = listOfNotNull(lastSynced),
            )
            is CloudSyncPhase.UpdateRequired -> CloudSyncStatusText(
                title = "Update Yoin", // i18n-allow: CloudSyncTextsTest asserts this English
                lines = listOfNotNull(phase.deviceName, lastSynced),
            )
            is CloudSyncPhase.Error -> CloudSyncStatusText(
                title = "Couldn't sync", // i18n-allow: CloudSyncTextsTest asserts this English
                lines = listOfNotNull(phase.message, lastSynced),
            )
            // Off states don't show the status row; keep the mapping total.
            else -> CloudSyncStatusText(
                title = "Off", // i18n-allow: CloudSyncTextsTest asserts this English
                lines = emptyList(),
            )
        }
    }
    val lastSynced = state.lastSyncAt?.let {
        resources.getString(
            R.string.settings_sync_status_last_synced,
            formatRelativeTime(it, nowMs, resources = resources),
        )
    }
    return when (val phase = state.phase) {
        CloudSyncPhase.Syncing -> CloudSyncStatusText(
            title = resources.getString(R.string.settings_sync_status_syncing),
            lines = listOfNotNull(lastSynced),
        )
        CloudSyncPhase.UpToDate -> CloudSyncStatusText(
            title = if (state.pendingChanges) {
                resources.getString(R.string.settings_sync_status_pending)
            } else {
                resources.getString(R.string.settings_sync_status_up_to_date)
            },
            lines = listOf(lastSynced ?: resources.getString(R.string.settings_sync_status_not_yet)),
        )
        CloudSyncPhase.Offline -> CloudSyncStatusText(
            title = resources.getString(R.string.settings_sync_status_offline),
            lines = listOfNotNull(lastSynced),
        )
        CloudSyncPhase.NeedsReauth -> CloudSyncStatusText(
            title = resources.getString(R.string.settings_sync_status_paused),
            lines = listOfNotNull(lastSynced),
        )
        CloudSyncPhase.StorageFull -> CloudSyncStatusText(
            title = resources.getString(R.string.settings_sync_status_storage),
            lines = listOfNotNull(lastSynced),
        )
        is CloudSyncPhase.NeedsReview -> CloudSyncStatusText(
            title = resources.getString(R.string.settings_sync_status_review),
            lines = listOfNotNull(lastSynced),
        )
        CloudSyncPhase.CloudCopyRemoved -> CloudSyncStatusText(
            title = resources.getString(R.string.settings_sync_status_removed),
            lines = listOfNotNull(lastSynced),
        )
        is CloudSyncPhase.UpdateRequired -> CloudSyncStatusText(
            title = resources.getString(R.string.settings_sync_status_update),
            lines = listOfNotNull(phase.deviceName, lastSynced),
        )
        is CloudSyncPhase.Error -> CloudSyncStatusText(
            title = resources.getString(R.string.settings_sync_status_error),
            lines = listOfNotNull(phase.message, lastSynced),
        )
        else -> CloudSyncStatusText(
            title = resources.getString(R.string.settings_sync_status_off),
            lines = emptyList(),
        )
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
