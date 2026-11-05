package com.gpo.yoin.data.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.PlaylistUnreadableItem
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.transfer.FakeTransferTarget
import com.gpo.yoin.data.transfer.MatchDecision
import com.gpo.yoin.data.transfer.MatchMethod
import com.gpo.yoin.data.transfer.RestoreReview
import com.gpo.yoin.data.transfer.TrackResolution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaylistBackupServiceTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), YoinDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private var now = 1_000L
    private val store = PlaylistBackupStore(db) { now }

    private val spotifyProfile = Profile("sp", MediaId.PROVIDER_SPOTIFY, "Chen", "{}")
    private val appleProfile = Profile("am", MediaId.PROVIDER_APPLE_MUSIC, "Apple Music", "{}")
    private val subsonicProfile = Profile("ss", MediaId.PROVIDER_SUBSONIC, "Home server", "{}")

    private val sourcePlaylist = Playlist(
        id = MediaId.spotify("p1"),
        name = "Road trip",
        owner = null,
        coverArt = null,
        songCount = 2,
        durationSec = null,
        tracks = listOf(
            track(MediaId.spotify("t1"), "Creep", "Radiohead"),
            track(MediaId.spotify("t2"), "Lemon", "Kenshi Yonezu"),
        ),
        unreadable = listOf(PlaylistUnreadableItem(2, "Demo", "Me", PlaylistUnreadableItem.Reason.LocalFile)),
    )

    @After
    fun tearDown() = db.close()

    @Test
    fun should_keepOneBackupPerPlaylist_when_backedUpTwice() = runTest {
        val service = service(sources = mapOf("sp" to sourceService()))

        val first = service.backUp(sourcePlaylist.id)
        now = 2_000L
        val second = service.backUp(sourcePlaylist.id)

        val all = store.observeAll().first()
        assertEquals(listOf(first.id), all.map { it.id })
        assertEquals(first.id, second.id)
        assertEquals(1_000L, all.first().firstBackedUpAt)
        assertEquals(2_000L, all.first().lastBackedUpAt)
        // Nothing changed in between: the last change stays at the start.
        assertEquals(1_000L, all.first().lastChangedAt)
        assertEquals("sp", all.first().sourceProfileId)
        assertEquals(2, all.first().trackCount)
        assertEquals(1, all.first().unexportedCount)
    }

    @Test
    fun should_backUpTheChange_when_theFollowedPlaylistChanged() = runTest {
        val source = sourceService()
        val service = service(sources = mapOf("sp" to source))
        service.backUp(sourcePlaylist.id)
        source.playlists = mapOf(
            sourcePlaylist.id to sourcePlaylist.copy(tracks = sourcePlaylist.tracks + track(MediaId.spotify("t9"), "Hikari", "Utada")),
        )
        now = 1_000L + PlaylistBackupService.FOLLOW_INTERVAL_MS

        val summary = service.followDue()

        assertEquals(FollowSummary(checked = 1, changed = 1, gone = 0), summary)
        val backup = store.observeAll().first().single()
        assertEquals(3, backup.trackCount)
        assertEquals(now, backup.lastChangedAt)
        assertEquals(now, backup.lastBackedUpAt)
    }

    @Test
    fun should_waitForTheInterval_when_followedRecently() = runTest {
        val service = service(sources = mapOf("sp" to sourceService()))
        service.backUp(sourcePlaylist.id)
        now = 1_000L + PlaylistBackupService.FOLLOW_INTERVAL_MS - 1

        assertEquals(FollowSummary(0, 0, 0), service.followDue())
    }

    @Test
    fun should_stopFollowingButKeepTheBackup_when_thePlaylistIsGone() = runTest {
        val source = sourceService()
        val service = service(sources = mapOf("sp" to source))
        service.backUp(sourcePlaylist.id)
        source.playlists = emptyMap()
        now = 1_000L + PlaylistBackupService.FOLLOW_INTERVAL_MS

        assertEquals(FollowSummary(checked = 1, changed = 0, gone = 1), service.followDue())
        val backup = store.observeAll().first().single()
        assertEquals(now, backup.sourceGoneAt)
        assertEquals(2, backup.trackCount)
        // Gone stays gone: no more checks.
        now += PlaylistBackupService.FOLLOW_INTERVAL_MS
        assertEquals(FollowSummary(0, 0, 0), service.followDue())
    }

    @Test
    fun should_leaveTheBackupAlone_when_theServiceCantBeReached() = runTest {
        val source = sourceService()
        val service = service(sources = mapOf("sp" to source))
        service.backUp(sourcePlaylist.id)
        source.listFails = true
        now = 1_000L + PlaylistBackupService.FOLLOW_INTERVAL_MS

        assertEquals(FollowSummary(checked = 1, changed = 0, gone = 0), service.followDue())
        val backup = store.observeAll().first().single()
        assertEquals(null, backup.sourceGoneAt)
        assertEquals(1_000L, backup.lastBackedUpAt)
    }

    @Test
    fun should_recordRestoreWithCounts_when_reviewedTracksAreCreated() = runTest {
        val match = track(MediaId("am", "c1"), "Creep", "Radiohead")
        val target = FakeTransferTarget(id = "am", search = mapOf("Creep Radiohead" to listOf(match)))
        val service = service(sources = mapOf("sp" to sourceService(), "am" to target))
        val backup = service.backUp(sourcePlaylist.id)
        val restoreTarget = RestoreTarget("am", "Apple Music", MediaId.PROVIDER_APPLE_MUSIC)

        val rows = RestoreReview.rows(service.match(backup, restoreTarget))
        now = 5_000L
        val outcome = service.create(backup, restoreTarget, rows)

        assertEquals(listOf(MediaId("am", "c1")), target.added)
        assertEquals(listOf("Road trip" to null), target.created)
        val expected = PlaylistRestoreRecord(
            at = 5_000L,
            targetProvider = MediaId.PROVIDER_APPLE_MUSIC,
            targetLabel = "Apple Music",
            added = 1,
            skipped = 0,
            notFound = 1,
        )
        assertEquals(expected, outcome.record)
        val stored = store.get(backup.id)!!
        assertEquals(listOf(expected), stored.restores)
        assertTrue(stored.updatedAt > backup.updatedAt)
    }

    @Test
    fun should_recordFailedRestore_when_targetProfileIsGone() = runTest {
        val service = service(sources = mapOf("sp" to sourceService()))
        val backup = service.backUp(sourcePlaylist.id)
        val rows = RestoreReview.rows(
            backup.content.tracks.mapIndexed { i, backed ->
                val track = PlaylistSnapshots.toTrack(backed)
                TrackResolution(i, track, MatchDecision.Matched(track, MatchMethod.Search))
            },
        )

        val outcome = service.create(backup, RestoreTarget("gone", "Old", MediaId.PROVIDER_SUBSONIC), rows)

        assertTrue(outcome.record.failed)
        assertEquals(0, outcome.record.added)
        assertEquals(2, outcome.record.skipped)
        assertEquals(listOf(outcome.record), store.get(backup.id)!!.restores)
    }

    @Test
    fun should_restoreBySpotifyIds_when_spotifyBackupGoesToSpotify() = runTest {
        val target = FakeTransferTarget(id = "sp2")
        val service = service(sources = mapOf("sp" to sourceService(), "sp2" to target))
        val backup = service.backUp(sourcePlaylist.id)

        val resolutions = service.match(backup, RestoreTarget("sp2", "Other", MediaId.PROVIDER_SPOTIFY))

        assertEquals(emptyList<String>(), target.searches)
        assertEquals(
            listOf(MediaId.spotify("t1"), MediaId.spotify("t2")),
            resolutions.map { (it.decision as MatchDecision.Matched).track.id },
        )
        assertTrue(resolutions.all { (it.decision as MatchDecision.Matched).method == MatchMethod.SourceId })
    }

    @Test
    fun should_offerEveryImportingProfile_when_listingRestoreTargets() {
        val targets = PlaylistBackupService.restoreTargets(listOf(spotifyProfile, appleProfile, subsonicProfile))

        assertEquals(listOf("sp", "am", "ss"), targets.map { it.profileId })
    }

    private fun service(sources: Map<String, MusicSource>) = PlaylistBackupService(
        profiles = FakeProfiles(
            listOf(spotifyProfile, appleProfile, subsonicProfile),
            active = "sp",
            sources = sources,
        ),
        store = store,
        clock = { now },
        newId = { "b$now" },
    )

    @Test
    fun should_keepABackupPerAccount_when_twoServersHaveAPlaylistWithTheSameId() = runTest {
        val home = Profile("home", MediaId.PROVIDER_SUBSONIC, "Home", "{}")
        val work = Profile("work", MediaId.PROVIDER_SUBSONIC, "Work", "{}")
        val id = MediaId.subsonic("1")
        val homeSource = FakeTransferTarget(playlists = mapOf(id to subsonicPlaylist(id, "Home mix", "h")))
        val workSource = FakeTransferTarget(playlists = mapOf(id to subsonicPlaylist(id, "Work mix", "w")))
        val profiles = FakeProfiles(
            listOf(home, work),
            active = "home",
            sources = mapOf("home" to homeSource, "work" to workSource),
            accounts = mapOf("home" to "s-home", "work" to "s-work"),
        )
        val service = service(profiles)

        val homeBackup = service.backUp(id)
        profiles.active = "work"
        now = 2_000L
        val workBackup = service.backUp(id)

        assertTrue(homeBackup.id != workBackup.id)
        val all = store.getAll().associateBy { it.id }
        assertEquals("Home mix", all.getValue(homeBackup.id).name)
        assertEquals("home", all.getValue(homeBackup.id).sourceProfileId)
        assertEquals("s-home", all.getValue(homeBackup.id).sourceAccount)
        assertEquals("Work mix", all.getValue(workBackup.id).name)
        assertEquals("work", all.getValue(workBackup.id).sourceProfileId)
        assertEquals("s-work", all.getValue(workBackup.id).sourceAccount)

        // Following reads each from its own server, and neither takes the other's place.
        now = 2_000L + PlaylistBackupService.FOLLOW_INTERVAL_MS
        assertEquals(FollowSummary(checked = 2, changed = 0, gone = 0), service.followDue())
        assertEquals(setOf("Home mix", "Work mix"), store.getAll().mapTo(HashSet()) { it.name })
    }

    @Test
    fun should_keepABackupPerProfile_when_noAccountIsKnown() = runTest {
        // Two profiles here, no account ids (e.g. credentials unreadable): the profile still tells them apart.
        val home = Profile("home", MediaId.PROVIDER_SUBSONIC, "Home", "{}")
        val work = Profile("work", MediaId.PROVIDER_SUBSONIC, "Work", "{}")
        val id = MediaId.subsonic("1")
        val profiles = FakeProfiles(
            listOf(home, work),
            active = "home",
            sources = mapOf(
                "home" to FakeTransferTarget(playlists = mapOf(id to subsonicPlaylist(id, "Home mix", "h"))),
                "work" to FakeTransferTarget(playlists = mapOf(id to subsonicPlaylist(id, "Work mix", "w"))),
            ),
        )
        val service = service(profiles)

        service.backUp(id)
        profiles.active = "work"
        service.backUp(id)

        assertEquals(setOf("home", "work"), store.getAll().mapTo(HashSet()) { it.sourceProfileId })
    }

    @Test
    fun should_takeUpAnotherDevicesBackup_when_itIsFromTheSameAccount() = runTest {
        val id = MediaId.subsonic("1")
        store.save(otherDeviceBackup("theirs", id, account = "s-home", label = "My server"))
        store.save(otherDeviceBackup("other-server", id, account = "s-work", label = "Home"))
        val home = Profile("home", MediaId.PROVIDER_SUBSONIC, "Home", "{}")
        val source = FakeTransferTarget(playlists = mapOf(id to subsonicPlaylist(id, "Mix", "h")))
        val service = service(
            FakeProfiles(
                listOf(home),
                active = "home",
                sources = mapOf("home" to source),
                accounts = mapOf("home" to "s-home"),
            ),
        )

        val backup = service.backUp(id)

        assertEquals("theirs", backup.id)
        assertEquals("home", store.get("theirs")?.sourceProfileId)
        assertEquals(null, store.get("other-server")?.sourceProfileId)
        assertEquals("Old", store.get("other-server")?.name)
    }

    @Test
    fun should_takeUpAnOldBackup_only_when_itWasMadeUnderThisProfilesName() = runTest {
        val id = MediaId.subsonic("1")
        store.save(otherDeviceBackup("old-work", id, account = null, label = "Work"))
        val home = Profile("home", MediaId.PROVIDER_SUBSONIC, "Home", "{}")
        val profiles = FakeProfiles(
            listOf(home),
            active = "home",
            sources = mapOf("home" to FakeTransferTarget(playlists = mapOf(id to subsonicPlaylist(id, "Mix", "h")))),
            accounts = mapOf("home" to "s-home"),
        )
        val service = service(profiles)

        val fresh = service.backUp(id)
        assertTrue(fresh.id != "old-work")
        assertEquals("Old", store.get("old-work")?.name)

        store.delete(fresh.id)
        store.save(otherDeviceBackup("old-home", id, account = null, label = "Home"))
        val taken = service.backUp(id)
        assertEquals("old-home", taken.id)
        // It learns its account now.
        assertEquals("s-home", store.get("old-home")?.sourceAccount)
    }

    @Test
    fun should_leaveTheBackupAlone_when_itsProfileIsNowAnotherAccount() = runTest {
        val id = MediaId.subsonic("1")
        val home = Profile("home", MediaId.PROVIDER_SUBSONIC, "Home", "{}")
        val source = FakeTransferTarget(playlists = mapOf(id to subsonicPlaylist(id, "Mix", "h")))
        val profiles = FakeProfiles(
            listOf(home),
            active = "home",
            sources = mapOf("home" to source),
            accounts = mapOf("home" to "s-home"),
        )
        val service = service(profiles)
        val backup = service.backUp(id)
        // Signed in to another server under the same profile.
        profiles.accounts = mapOf("home" to "s-elsewhere")
        source.playlists = mapOf(id to subsonicPlaylist(id, "Someone else's", "x"))
        now = 1_000L + PlaylistBackupService.FOLLOW_INTERVAL_MS

        service.followDue()

        assertEquals("Mix", store.get(backup.id)?.name)
        assertEquals(1_000L, store.get(backup.id)?.lastBackedUpAt)
    }

    private fun subsonicPlaylist(id: MediaId, name: String, prefix: String) = Playlist(
        id = id,
        name = name,
        owner = null,
        coverArt = null,
        songCount = 1,
        durationSec = null,
        tracks = listOf(track(MediaId.subsonic("$prefix-1"), "Song $prefix", "Artist")),
    )

    /** A backup synced in from another device: no profile here follows it. */
    private fun otherDeviceBackup(backupId: String, id: MediaId, account: String?, label: String) =
        PlaylistSnapshots.backup(
            backupId,
            subsonicPlaylist(id, "Old", "o"),
            MediaId.PROVIDER_SUBSONIC,
            profileId = null,
            profileLabel = label,
            now = 500L,
            account = account,
        )

    private fun service(profiles: BackupProfiles) = PlaylistBackupService(
        profiles = profiles,
        store = store,
        clock = { now },
        newId = { "b$now" },
    )

    private fun sourceService() = FakeTransferTarget(playlists = mapOf(sourcePlaylist.id to sourcePlaylist))

    private class FakeProfiles(
        list: List<Profile>,
        var active: String,
        private val sources: Map<String, MusicSource>,
        var accounts: Map<String, String> = emptyMap(),
    ) : BackupProfiles {
        private val all = MutableStateFlow(list)
        override val profiles: Flow<List<Profile>> = all

        override suspend fun activeProfile(): Profile? = all.value.firstOrNull { it.id == active }

        override suspend fun <T> withSource(profileId: String, block: suspend (MusicSource) -> T): T? =
            sources[profileId]?.let { block(it) }

        override suspend fun accountKey(profile: Profile): String? = accounts[profile.id]
    }

    private fun track(id: MediaId, title: String, artist: String) = Track(
        id = id,
        title = title,
        artist = artist,
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = 238,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
    )
}
