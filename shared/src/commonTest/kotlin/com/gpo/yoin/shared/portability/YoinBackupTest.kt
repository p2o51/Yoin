package com.gpo.yoin.shared.portability

import com.gpo.yoin.shared.portability.memories.MatchStatus
import com.gpo.yoin.shared.portability.memories.MigrationIds
import com.gpo.yoin.shared.portability.memories.SourceRef
import com.gpo.yoin.shared.portability.memories.TrackPick
import com.gpo.yoin.shared.portability.memories.TrackTarget
import com.gpo.yoin.shared.sync.InMemorySyncStore
import com.gpo.yoin.shared.sync.adapters.AlbumRatingRow
import com.gpo.yoin.shared.sync.adapters.SongNoteRow
import com.gpo.yoin.shared.sync.adapters.TrackRatingRow
import com.gpo.yoin.shared.sync.identity.SyncProfile
import com.gpo.yoin.shared.sync.testing.FakeSyncDomain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The Swift facade end to end: what iOS will call (step B), over the shared stores. */
class YoinBackupTest {
    private val url = "https://music.example.com"

    private class Links : YoinTrackAlbumLinkStore {
        val rows = LinkedHashMap<List<String>, BackupTrackAlbumLink>()

        override fun links(profileId: String) = rows.filterKeys { it[0] == profileId }.values.toList()

        override fun link(profileId: String, provider: String, trackId: String) = rows[listOf(profileId, provider, trackId)]

        override fun putLink(profileId: String, link: BackupTrackAlbumLink): Boolean {
            rows[listOf(profileId, link.provider, link.trackId)] = link
            return true
        }
    }

    private class Device(id: String, name: String, withReplica: Boolean) {
        val domain = FakeSyncDomain()
        val links = Links()
        val store = if (withReplica) InMemorySyncStore() else null
        val backup = YoinBackup(domain, store, links, id, name, "1.0", 1)
    }

    private fun Device.seed(): String {
        domain.profileList += SyncProfile("p1", "subsonic", "Alice", 1_000, subsonicServerUrl = url, subsonicUsername = "alice")
        domain.putNote(SongNoteRow("n1", "p1", "t1", "subsonic", "rain [@One](yoin:track/subsonic:t1)", 2_000, 2_000, "One", "Band", null))
        domain.putTrackRating("p1", TrackRatingRow("subsonic", "t1", 9.0, 2_100))
        domain.putAlbumRating("p1", AlbumRatingRow("subsonic", "al1", 8.0, "Review.", 2_300, 2_300))
        links.putLink("p1", BackupTrackAlbumLink("subsonic", "t1", "al1", BackupTrackAlbumLink.SOURCE_DETAIL, 2_400))
        return "p1"
    }

    @Test
    fun should_exportReadPlanAndImport_throughTheFacade() = runTest {
        val phone = Device("dev-a", "iPhone", withReplica = false).apply { seed() }

        val export = phone.backup.exportBackup(1_795_400_000_000, false, "zh-Hans", "Asia/Shanghai", emptyList(), emptyList())

        assertEquals("yoin-backup-2026-11-23.zip", export.fileName)
        assertEquals(BackupFormat.LOCATION_THIS_DEVICE, export.manifest.source.location)
        assertTrue(export.readable.single().path.startsWith("readable/Alice/"))
        assertTrue(export.readable.single().text.contains("评分: 8 / 10"))
        val parsed = phone.backup.readBackup(export.manifestJson.encodeToByteArray(), export.snapshotJson.encodeToByteArray(), export.readable.size)

        val tablet = Device("dev-b", "iPad", withReplica = true)
        tablet.domain.profileList += SyncProfile("t1", "subsonic", "Alice on iPad", 1_000, subsonicServerUrl = url, subsonicUsername = "alice")
        val plan = tablet.backup.planImport(parsed, 5_000)
        val account = plan.accounts.single()
        assertEquals(YoinImportChoice(account.account.scope, YoinImportTargetKind.INTO_PROFILE, "t1"), account.target)
        assertTrue(account.sameAccount)
        assertTrue(account.cloudOnlyPossible)

        val first = tablet.backup.applyImport(plan, emptyList(), true, 5_000)
        assertTrue(first.changed)
        assertEquals("n1", tablet.domain.notes("t1").single().id)
        assertEquals("al1", tablet.links.link("t1", "subsonic", "t1")?.albumId)
        val again = tablet.backup.applyImport(tablet.backup.planImport(parsed, 6_000), emptyList(), true, 6_000)
        assertFalse(again.changed)
    }

    @Test
    fun should_reportReadFailuresAsFlatErrors() = runTest {
        val phone = Device("dev-a", "iPhone", withReplica = false).apply { seed() }
        val export = phone.backup.exportBackup(1, false, "en", "UTC", emptyList(), emptyList())

        val tampered = assertFailsWith<BackupReadException> {
            phone.backup.readBackup(export.manifestJson.encodeToByteArray(), "{}".encodeToByteArray(), 0)
        }
        assertEquals(YoinBackupErrorKind.CHECKSUM_MISMATCH, YoinBackup.errorOf(tampered).kind)
        val newer = assertFailsWith<BackupReadException> {
            phone.backup.readBackup(
                export.manifestJson.replace("\"formatVersion\": 1", "\"formatVersion\": 7").encodeToByteArray(),
                export.snapshotJson.encodeToByteArray(),
                0,
            )
        }
        assertEquals(YoinBackupError(YoinBackupErrorKind.TOO_NEW, 7, newer.message.orEmpty()), YoinBackup.errorOf(newer))
        assertEquals(YoinBackupErrorKind.OTHER, YoinBackup.errorOf(IllegalStateException("x")).kind)
    }

    @Test
    fun should_planMatchAndApplyATransfer_andReuseTheMatchNextTime() = runTest {
        val device = Device("dev-a", "iPhone", withReplica = true).apply { seed() }
        device.domain.profileList += SyncProfile("sp", "spotify", "Spotify", 2_000)
        val sources = device.backup.transferSources()
        assertEquals(listOf("p1", "sp"), sources.map { it.account.localProfileId })
        val from = sources.first().account
        val to = device.backup.transferAccountOf("sp")
        val items = device.backup.memoryItems(sources.first(), null)

        val plan = device.backup.planTransfer(from, items, to)
        assertEquals(listOf(SourceRef("subsonic", "t1")), plan.tracksToMatch.map { it.source })
        assertEquals("One", plan.tracksToMatch.single().title)
        assertEquals("al1", plan.tracksToMatch.single().albumId)
        assertEquals(listOf(SourceRef("subsonic", "al1")), plan.albumsToMatch.map { it.source })
        val pick = TrackPick(SourceRef("subsonic", "t1"), MatchStatus.Picked, TrackTarget("spotify", "s1", "One", "Band", "spotify", "sA"))

        val result = device.backup.applyTransfer(plan, listOf(pick), emptyList(), 9_000)

        assertEquals(1, result.copied.songNotes)
        assertEquals(1, result.copied.trackRatings)
        assertEquals(1, result.unmatched.albumRatings)
        val note = device.domain.notes("sp").single()
        assertEquals(MigrationIds.derive(from.scope, "n1", to.scope), note.id)
        assertEquals("rain [@One](yoin:track/spotify:s1)", note.content)
        assertEquals("sA", device.links.link("sp", "spotify", "s1")?.albumId)

        // A later note on the same song: the earlier pick is reused, nothing copied before is copied again.
        device.domain.putNote(SongNoteRow("n2", "p1", "t1", "subsonic", "second listen", 10_000, 10_000, "One", "Band", null))
        val again = device.backup.planTransfer(from, device.backup.memoryItems(sources.first(), null), to)
        assertTrue(again.tracksToMatch.isEmpty())
        assertEquals(MatchStatus.Picked, again.reusedTracks.single().status)
        assertEquals("s1", again.reusedTracks.single().chosen?.id)
        assertEquals(3, again.previouslyCopied)
        assertEquals(1, again.toCopy.songNotes)
        val second = device.backup.applyTransfer(again, again.reusedTracks, emptyList(), 11_000)
        assertEquals(1, second.copied.songNotes)
        assertEquals(3, second.skippedPreviously)
        assertEquals(2, device.domain.notes("sp").size)
    }
}
