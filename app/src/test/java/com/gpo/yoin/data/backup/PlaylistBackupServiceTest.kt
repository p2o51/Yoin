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
        tracks = listOf(track(MediaId.spotify("t1"), "Creep", "Radiohead"), track(MediaId.spotify("t2"), "Lemon", "Kenshi Yonezu")),
        unreadable = listOf(PlaylistUnreadableItem(2, "Demo", "Me", PlaylistUnreadableItem.Reason.LocalFile)),
    )

    @After
    fun tearDown() = db.close()

    @Test
    fun should_saveNewVersionEachTime_when_playlistIsBackedUpTwice() = runTest {
        val service = service(sources = mapOf("sp" to FakeTransferTarget(playlists = mapOf(sourcePlaylist.id to sourcePlaylist))))

        val first = service.backUp(sourcePlaylist.id)
        now = 2_000L
        val second = service.backUp(sourcePlaylist.id)

        val all = store.observeAll().first()
        assertEquals(listOf(second.id, first.id), all.map { it.id })
        assertEquals("Chen", all.first().sourceProfileLabel)
        assertEquals(MediaId.PROVIDER_SPOTIFY, all.first().sourceProvider)
        assertEquals(2, all.first().trackCount)
        assertEquals(1, all.first().unexportedCount)
    }

    @Test
    fun should_recordRestoreWithCounts_when_reviewedTracksAreCreated() = runTest {
        val match = track(MediaId("am", "c1"), "Creep", "Radiohead")
        val target = FakeTransferTarget(id = "am", search = mapOf("Creep Radiohead" to listOf(match)))
        val service = service(
            sources = mapOf(
                "sp" to FakeTransferTarget(playlists = mapOf(sourcePlaylist.id to sourcePlaylist)),
                "am" to target,
            ),
        )
        val backup = service.backUp(sourcePlaylist.id)
        val restoreTarget = RestoreTarget("am", "Apple Music", MediaId.PROVIDER_APPLE_MUSIC)

        val rows = RestoreReview.rows(service.match(backup, restoreTarget))
        now = 5_000L
        val outcome = service.create(backup, restoreTarget, rows)

        assertEquals(listOf(MediaId("am", "c1")), target.added)
        assertEquals(listOf("Road trip" to null), target.created)
        val expected = PlaylistRestoreRecord(5_000L, MediaId.PROVIDER_APPLE_MUSIC, "Apple Music", added = 1, skipped = 0, notFound = 1)
        assertEquals(expected, outcome.record)
        val stored = store.get(backup.id)!!
        assertEquals(listOf(expected), stored.restores)
        assertTrue(stored.updatedAt > backup.updatedAt)
    }

    @Test
    fun should_recordFailedRestore_when_targetProfileIsGone() = runTest {
        val service = service(sources = mapOf("sp" to FakeTransferTarget(playlists = mapOf(sourcePlaylist.id to sourcePlaylist))))
        val backup = service.backUp(sourcePlaylist.id)
        val rows = RestoreReview.rows(
            backup.content.tracks.mapIndexed { i, t ->
                com.gpo.yoin.data.transfer.TrackResolution(i, PlaylistSnapshots.toTrack(t), MatchDecision.Matched(PlaylistSnapshots.toTrack(t), MatchMethod.Search))
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
        val service = service(sources = mapOf("sp" to FakeTransferTarget(playlists = mapOf(sourcePlaylist.id to sourcePlaylist)), "sp2" to target))
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
        profiles = FakeProfiles(listOf(spotifyProfile, appleProfile, subsonicProfile), active = "sp", sources = sources),
        store = store,
        clock = { now },
        newId = { "b$now" },
    )

    private class FakeProfiles(
        list: List<Profile>,
        private val active: String,
        private val sources: Map<String, MusicSource>,
    ) : BackupProfiles {
        private val all = MutableStateFlow(list)
        override val profiles: Flow<List<Profile>> = all

        override suspend fun activeProfile(): Profile? = all.value.firstOrNull { it.id == active }

        override suspend fun <T> withSource(profileId: String, block: suspend (MusicSource) -> T): T? =
            sources[profileId]?.let { block(it) }
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
