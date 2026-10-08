package com.gpo.yoin.ui.settings.sync

import com.gpo.yoin.data.sync.CloudSyncPhase
import com.gpo.yoin.data.sync.CloudSyncState
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudSyncTextsTest {

    private val now = 1_791_115_200_000L // 2026-10-04 12:00 UTC
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun relative(agoMs: Long) = formatRelativeTime(now - agoMs, now, ZoneOffset.UTC)

    @Test
    fun should_sayJustNow_when_underAMinuteOrInTheFuture() {
        assertEquals("just now", relative(0))
        assertEquals("just now", relative(59_000))
        assertEquals("just now", relative(-5 * minute))
    }

    @Test
    fun should_countMinutesHoursAndDays_when_withinAWeek() {
        assertEquals("2 min ago", relative(2 * minute + 10_000))
        assertEquals("59 min ago", relative(59 * minute))
        assertEquals("3 hr ago", relative(3 * hour))
        assertEquals("yesterday", relative(30 * hour))
        assertEquals("4 days ago", relative(4 * day))
    }

    @Test
    fun should_showTheDate_when_aWeekOrOlder() {
        assertEquals("Sep 24", relative(10 * day))
        assertEquals("Sep 4, 2025", relative(395 * day))
    }

    private fun summary(state: CloudSyncState) = cloudSyncEntrySummary(state, now)

    @Test
    fun should_summarizeOffStates_when_syncIsNotRunning() {
        assertEquals(CloudSyncEntryText("Off"), summary(CloudSyncState()))
        assertEquals(
            CloudSyncEntryText("Not available in this build"),
            summary(CloudSyncState(phase = CloudSyncPhase.Misconfigured("com.gpo.yoin", null))),
        )
        assertEquals(
            CloudSyncEntryText("Needs Google Play services"),
            summary(CloudSyncState(phase = CloudSyncPhase.Unavailable(updateRequired = false))),
        )
        assertEquals(
            CloudSyncEntryText("Needs a Google Play services update"),
            summary(CloudSyncState(phase = CloudSyncPhase.Unavailable(updateRequired = true))),
        )
        assertEquals(
            CloudSyncEntryText("Turned off on Pixel 9"),
            summary(CloudSyncState(phase = CloudSyncPhase.ResetElsewhere("Pixel 9"))),
        )
        assertEquals(
            CloudSyncEntryText("Turned off on another device"),
            summary(CloudSyncState(phase = CloudSyncPhase.ResetElsewhere(null))),
        )
    }

    @Test
    fun should_summarizeOnStates_when_syncIsRunning() {
        val synced = CloudSyncState(phase = CloudSyncPhase.UpToDate, lastSyncAt = now - 2 * minute)
        assertEquals(CloudSyncEntryText("On", "Synced 2 min ago"), summary(synced))
        assertEquals(CloudSyncEntryText("On"), summary(synced.copy(lastSyncAt = null)))
        assertEquals(CloudSyncEntryText("Changes waiting to upload"), summary(synced.copy(pendingChanges = true)))
        assertEquals(CloudSyncEntryText("Syncing…"), summary(synced.copy(phase = CloudSyncPhase.Syncing)))
        assertEquals(
            CloudSyncEntryText("Changes waiting to upload"),
            summary(synced.copy(phase = CloudSyncPhase.Offline, pendingChanges = true)),
        )
        assertEquals(CloudSyncEntryText("On", "Offline"), summary(synced.copy(phase = CloudSyncPhase.Offline)))
        assertEquals(
            CloudSyncEntryText("Paused", "Reconnect Google"),
            summary(synced.copy(phase = CloudSyncPhase.NeedsReauth)),
        )
        assertEquals(
            CloudSyncEntryText("Google storage full"),
            summary(synced.copy(phase = CloudSyncPhase.StorageFull)),
        )
        assertEquals(
            CloudSyncEntryText("Needs review", "3 notes disappeared"),
            summary(synced.copy(phase = CloudSyncPhase.NeedsReview("demo", 3))),
        )
        assertEquals(
            CloudSyncEntryText("Paused", "Drive copy removed"),
            summary(synced.copy(phase = CloudSyncPhase.CloudCopyRemoved)),
        )
        assertEquals(CloudSyncEntryText("Couldn't sync"), summary(synced.copy(phase = CloudSyncPhase.Error("boom"))))
    }

    @Test
    fun should_sayNotSyncedYet_when_upToDateWithoutAFirstSync() {
        val status = cloudSyncStatusText(CloudSyncState(phase = CloudSyncPhase.UpToDate), now)
        assertEquals("Up to date", status.title)
        assertEquals(listOf("Not synced yet"), status.lines)
    }

    @Test
    fun should_formatCertSha1AsColonPairs_when_givenBareOrLowercaseHex() {
        val pairs = "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01"
        assertEquals(pairs, formatCertSha1("abcdef0123456789abcdef0123456789abcdef01"))
        assertEquals(pairs, formatCertSha1("ab:cd:ef:01:23:45:67:89:ab:cd:ef:01:23:45:67:89:ab:cd:ef:01"))
        assertEquals(pairs, formatCertSha1(" ABCDEF0123456789ABCDEF0123456789ABCDEF01 "))
    }

    @Test
    fun should_keepCertValueAsGiven_when_itIsNotASha1() {
        assertEquals("not-a-cert", formatCertSha1(" not-a-cert "))
        assertEquals("ABCD", formatCertSha1("ABCD"))
    }
}
