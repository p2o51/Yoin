package com.gpo.yoin.data.transfer.memories

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.sync.ActiveBinding
import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.data.sync.backup.BackupTestDevice
import com.gpo.yoin.data.sync.engine.SyncEngine
import com.gpo.yoin.data.sync.engine.toWire
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MemoryTransferTest {
    private val device = BackupTestDevice()
    private val ledger = InMemoryTransferLedger()
    private val transfer get() = MemoryTransfer(device.db, ledger, clock = { 99_000 })
    private val dao get() = device.db.syncDomainDao()

    @After
    fun tearDown() = device.close()

    private fun account(profile: String?, scope: String, provider: String, server: String? = null, name: String = scope) =
        TransferAccount(scope, provider, name, server, profile)

    private suspend fun seedSubsonic(profile: String) {
        device.note(profile, "n1", "t1", "first listen", at = 2_000)
        device.rate(profile, "t1", 9f, at = 2_100)
        device.rate(profile, "t2", 6f, at = 2_150)
        device.albumRating(profile, "al1", 8f, "Source review.", at = 2_300)
        device.albumNote(profile, "an1", "al1", "album note", at = 2_400)
        device.albumTitle(profile, "al1", "My Title", at = 2_500)
        device.memory(profile, "al1", "AI copy", "AI Title", at = 2_600)
        device.link(profile, "t1", "al1", at = 2_700)
    }

    // ---------------------------------------------------------------- same id space

    @Test
    fun should_copyEveryKindWithKeysAsIs_when_bothAccountsAreOnOneSubsonicServer() = runTest {
        device.addSubsonic("old", "https://music.example.com", "alice")
        device.addSubsonic("new", "https://music.example.com", "alice2")
        seedSubsonic("old")
        val src = account("old", "s-old", "subsonic", "music.example.com", "Old account")
        val dst = account("new", "s-new", "subsonic", "music.example.com")

        val plan = transfer.plan(src, MemoryItems.fromProfile(device.db, "old"), dst, catalog = null)
        assertTrue("nothing to match", plan.tracks.isEmpty() && plan.albums.isEmpty())
        assertEquals(10, plan.toCopy.total)
        val result = transfer.execute(plan)

        assertEquals(10, result.copied.total)
        val note = dao.notesForProfile("new").single()
        assertEquals(MigrationIds.derive("s-old", "n1", "s-new"), note.id)
        assertTrue(note.id.startsWith("mig-") && note.id.length == 20)
        assertEquals("t1", note.trackId)
        assertEquals("the original stays", "old", dao.noteById("n1")?.profileId)
        assertEquals(9f, dao.trackRating("new", "t1", "subsonic")?.rating)
        assertFalse(dao.trackRating("new", "t1", "subsonic")!!.needsSync)
        val album = dao.albumRating("new", "al1", "subsonic")!!
        assertEquals(8f, album.rating)
        assertEquals("Source review.", album.review)
        assertFalse(album.ratingNeedsSync || album.reviewNeedsSync)
        assertEquals(MigrationIds.derive("s-old", "an1", "s-new"), dao.albumNotesForProfile("new").single().id)
        assertEquals("My Title", device.db.albumMemoryTitleDao().get("new", "subsonic", "al1")?.title)
        val memory = dao.memoryCopy("new", "subsonic", "album", "al1")!!
        assertEquals("AI Title", memory.title)
        assertEquals("AI copy", memory.copy)
        assertEquals("al1", dao.trackAlbumLink("new", "subsonic", "t1")?.albumId)
        assertEquals("source untouched", 1, dao.notesForProfile("old").size)
    }

    @Test
    fun should_skipWhatWasCopied_when_runAgain() = runTest {
        device.addSubsonic("old", "https://music.example.com", "alice")
        device.addSubsonic("new", "https://music.example.com", "alice2")
        seedSubsonic("old")
        val src = account("old", "s-old", "subsonic", "music.example.com")
        val dst = account("new", "s-new", "subsonic", "music.example.com")
        transfer.execute(transfer.plan(src, MemoryItems.fromProfile(device.db, "old"), dst, null))
        device.note("old", "n2", "t3", "written later", at = 5_000)

        val plan = transfer.plan(src, MemoryItems.fromProfile(device.db, "old"), dst, null)
        assertEquals(10, plan.previouslyCopied)
        val again = transfer.execute(plan)

        assertEquals(10, again.skippedPreviously)
        assertEquals(1, again.copied.songNotes)
        assertEquals(1, again.copied.total)
        assertEquals(2, dao.notesForProfile("new").size)
    }

    @Test
    fun should_resolveConflictsByTheRules_when_theTargetAlreadyHasMemories() = runTest {
        device.addSubsonic("old", "https://music.example.com", "alice")
        device.addSubsonic("new", "https://music.example.com", "alice2")
        seedSubsonic("old")
        device.note("new", "own", "t1", "target's own note", at = 1_000)
        device.rate("new", "t1", 4f, at = 9_000) // newer: stays
        device.rate("new", "t2", 2f, at = 1_000) // older: replaced
        device.albumRating("new", "al1", 3f, "Target review.", at = 1_000)
        val src = account("old", "s-old", "subsonic", "music.example.com", "Old account")
        val dst = account("new", "s-new", "subsonic", "music.example.com")

        val result = transfer.execute(transfer.plan(src, MemoryItems.fromProfile(device.db, "old"), dst, null))

        assertEquals("notes are all kept", 2, dao.notesForProfile("new").size)
        assertEquals(4f, dao.trackRating("new", "t1", "subsonic")?.rating)
        assertEquals(6f, dao.trackRating("new", "t2", "subsonic")?.rating)
        val album = dao.albumRating("new", "al1", "subsonic")!!
        assertEquals(8f, album.rating)
        assertEquals("Target review.\n\n— 来自 Old account\nSource review.", album.review)
        assertEquals(1, result.reviewsJoined)
        assertFalse(album.ratingNeedsSync || album.reviewNeedsSync)
    }

    // ---------------------------------------------------------------- matching

    private val target = "spotify"

    private fun track(id: String, title: String, artist: String, album: String?, albumName: String? = null, isrc: String? = null, duration: Int? = 200) =
        Track(
            id = MediaId(target, id),
            title = title,
            artist = artist,
            artistId = null,
            album = albumName,
            albumId = album?.let { MediaId(target, it) },
            coverArt = null,
            durationSec = duration,
            trackNumber = null,
            year = null,
            genre = null,
            userRating = null,
            extras = isrc?.let { mapOf(Track.EXTRA_ISRC to it) } ?: emptyMap(),
        )

    private fun spotifyAlbum(id: String, name: String, artist: String) =
        Album(MediaId(target, id), name, artist, null, null, null, null, null, null)

    private inner class FakeCatalog : MemoryCatalog {
        val tracks = mutableListOf<Track>()
        val albums = mutableListOf<Album>()
        val described = HashMap<String, Track>()
        var searches = 0

        override suspend fun describeSourceTrack(provider: String, trackId: String): Track? = described[trackId]

        override suspend fun tracksByIsrc(isrcs: List<String>): Map<String, List<Track>> =
            isrcs.associateWith { isrc -> tracks.filter { it.extras[Track.EXTRA_ISRC] == isrc } }

        // A loose search: anything sharing a title word with the query (the matcher has to pick).
        private fun words(text: String) = text.lowercase().split(Regex("\\W+")).filter { it.length > 1 && it != "artist" }.toSet()

        override suspend fun searchTracks(query: String): List<Track> {
            searches++
            return tracks.filter { words(it.title!!).intersect(words(query)).isNotEmpty() }
        }

        override suspend fun searchAlbums(query: String): List<Album> {
            searches++
            return albums.filter { words(it.name).intersect(words(query)).isNotEmpty() }
        }
    }

    @Test
    fun should_matchSongsAndInferAlbums_when_copyingToAnotherService() = runTest {
        device.addSubsonic("old", "https://music.example.com", "alice")
        device.addProfile("spot", "spotify", "Spotify")
        val p = "old"
        device.played(p, "t1", "Rain Song", "al1", "Rain Album", at = 1)
        device.played(p, "t2", "Sun Song", "al1", "Rain Album", at = 1)
        device.played(p, "t3", "Wind Song", "al2", "Wind Album", at = 1)
        device.played(p, "t4", "Obscure", "al3", "Nobody's Album", at = 1)
        device.rate(p, "t1", 9f, at = 2_000)
        device.rate(p, "t2", 8f, at = 2_000)
        device.rate(p, "t3", 7f, at = 2_000)
        device.rate(p, "t4", 5f, at = 2_000)
        device.note(p, "n-isrc", "t5", "matched by isrc", at = 2_000)
        device.albumRating(p, "al1", 9f, null, at = 2_000) // inferred from its matched songs
        device.albumRating(p, "al4", 6f, null, at = 2_000) // found by album search
        device.albumNote(p, "an4", "al4", "note on al4", at = 2_000)
        device.albumRating(p, "al5", 4f, null, at = 2_000) // unknown album: unmatched
        val catalog = FakeCatalog().apply {
            tracks += track("sp1", "Rain Song", "Artist", "spA", "Rain Album")
            tracks += track("sp2", "Sun Song", "Artist", "spA", "Rain Album")
            tracks += track("sp3a", "Wind Song (Live)", "Artist", "spW1")
            tracks += track("sp5", "Totally Different Name", "Artist", "spX", isrc = "ISRC5")
            described["t5"] = track("t5", "Fifth", "Artist", null, isrc = "ISRC5").copy(id = MediaId("subsonic", "t5"))
            albums += spotifyAlbum("spAl4", "Album al4", "Artist")
        }
        val src = account(p, "s-old", "subsonic", "music.example.com", "Navidrome")
        val dst = account("spot", "s-spot", "spotify")

        val plan = transfer.plan(src, MemoryItems.fromProfile(device.db, p), dst, catalog)

        fun status(id: String) = plan.tracks.single { it.source.id == id }.status
        assertEquals(MatchStatus.Matched, status("t1"))
        assertEquals(MatchStatus.Matched, status("t2"))
        assertEquals("a live take is only offered", MatchStatus.Uncertain, status("t3"))
        assertEquals(MatchStatus.Unmatched, status("t4"))
        assertEquals("ISRC first", MediaId(target, "sp5"), plan.tracks.single { it.source.id == "t5" }.chosen?.id)
        val al1 = plan.albums.single { it.source.id == "al1" }
        assertEquals(MatchStatus.Matched, al1.status)
        assertEquals(AlbumMatchMethod.Tracks, al1.method)
        assertEquals(MediaId(target, "spA"), al1.chosen?.id)
        val al4 = plan.albums.single { it.source.id == "al4" }
        assertEquals(AlbumMatchMethod.Search, al4.method)
        assertEquals(MediaId(target, "spAl4"), al4.chosen?.id)
        assertEquals(MatchStatus.Unmatched, plan.albums.single { it.source.id == "al5" }.status)

        val result = transfer.execute(plan)

        assertEquals(9f, dao.trackRating("spot", "sp1", target)?.rating)
        assertEquals(8f, dao.trackRating("spot", "sp2", target)?.rating)
        assertNull("uncertain isn't copied unasked", dao.trackRating("spot", "sp3a", target))
        assertEquals(2, result.unmatched.trackRatings)
        assertEquals("sp5", dao.notesForProfile("spot").single().trackId)
        assertEquals("Totally Different Name", dao.notesForProfile("spot").single().title)
        assertEquals(9f, dao.albumRating("spot", "spA", target)?.rating)
        assertEquals(6f, dao.albumRating("spot", "spAl4", target)?.rating)
        assertEquals("spAl4", dao.albumNotesForProfile("spot").single().albumId)
        assertEquals(1, result.unmatched.albumRatings)
        assertEquals("matching teaches the album", "spA", dao.trackAlbumLink("spot", target, "sp1")?.albumId)
    }

    @Test
    fun should_retryUnmatchedAndReusePicks_when_runAgain() = runTest {
        device.addSubsonic("old", "https://music.example.com", "alice")
        device.addProfile("spot", "spotify", "Spotify")
        device.played("old", "t3", "Wind Song", "al2", "Wind Album", at = 1)
        device.played("old", "t4", "Obscure", "al3", "Nobody's Album", at = 1)
        device.rate("old", "t3", 7f, at = 2_000)
        device.rate("old", "t4", 5f, at = 2_000)
        val catalog = FakeCatalog().apply { tracks += track("sp3a", "Wind Song (Live)", "Artist", "spW1") }
        val src = account("old", "s-old", "subsonic", "music.example.com")
        val dst = account("spot", "s-spot", "spotify")
        val first = transfer.plan(src, MemoryItems.fromProfile(device.db, "old"), dst, catalog)
        val picked = first.pickTrack(SourceRef("subsonic", "t3"), first.uncertain.single().candidates.first())
        transfer.execute(picked)
        assertEquals(7f, dao.trackRating("spot", "sp3a", target)?.rating)

        catalog.tracks += track("sp4", "Obscure", "Artist", "spO")
        val searchesBefore = catalog.searches
        val second = transfer.plan(src, MemoryItems.fromProfile(device.db, "old"), dst, catalog)

        assertEquals("t3's score was copied: skipped", 1, second.previouslyCopied)
        assertEquals(listOf("t4"), second.tracks.map { it.source.id })
        assertEquals(MatchStatus.Matched, second.tracks.single().status)
        assertTrue(catalog.searches > searchesBefore)
        val result = transfer.execute(second)
        assertEquals(1, result.skippedPreviously)
        assertEquals(5f, dao.trackRating("spot", "sp4", target)?.rating)
        assertEquals(TransferLedger.CHOSEN + "spotify:sp3a", ledger.read("s-old", "spot")["track:subsonic:t3"])
    }

    @Test
    fun should_copyFromTheReplica_when_theSourceIsOnlyInTheCloud() = runTest {
        device.addSubsonic("cloud", "https://music.example.com", "alice")
        device.addSubsonic("new", "https://music.example.com", "bob")
        seedSubsonic("cloud")
        // Publish "cloud" into the replica, then forget the profile: an account only in the cloud.
        val engine = SyncEngine(device.syncDb, device.adapters, "dev-a", { "Pixel 9" }, { 5_000 })
        engine.capture(listOf(ActiveBinding("cloud", "s-cloud", "subsonic")))
        val records = device.syncDb.syncDao().recordsInScope("s-cloud").map { it.toWire() }
        val items = MemoryItems.fromRecords(records, "s-cloud")

        val result = transfer.execute(
            transfer.plan(account(null, "s-cloud", "subsonic", "music.example.com", "Alice"), items, account("new", "s-new", "subsonic", "music.example.com"), null),
        )

        // Album notes and links aren't synced: everything else comes from the cloud copy.
        assertEquals(8, result.copied.total)
        assertEquals("first listen", dao.notesForProfile("new").single().content)
        assertEquals(8f, dao.albumRating("new", "al1", "subsonic")?.rating)
        assertEquals("AI Title", dao.memoryCopy("new", "subsonic", "album", "al1")?.title)
    }

    @Test
    fun should_bePublishedAsTheTargetsOwnChanges_when_syncCapturesAfterATransfer() = runTest {
        device.addSubsonic("old", "https://music.example.com", "alice")
        device.addSubsonic("new", "https://music.example.com", "alice2")
        seedSubsonic("old")
        transfer.execute(
            transfer.plan(
                account("old", "s-old", "subsonic", "music.example.com"),
                MemoryItems.fromProfile(device.db, "old"),
                account("new", "s-new", "subsonic", "music.example.com"),
                null,
            ),
        )
        val engine = SyncEngine(device.syncDb, device.adapters, "dev-a", { "Pixel 9" }, { 50_000 })

        val capture = engine.capture(listOf(ActiveBinding("new", "s-new", "subsonic")))

        val versions = capture.newVersions["s-new"].orEmpty()
        assertEquals(1, versions[SyncKinds.SONG_NOTE])
        assertEquals(2, versions[SyncKinds.TRACK_RATING])
        assertEquals(1, versions[SyncKinds.ALBUM_RATING])
        assertEquals(1, versions[SyncKinds.ALBUM_REVIEW])
        assertEquals(1, versions[SyncKinds.ALBUM_TITLE])
        assertEquals(1, versions[SyncKinds.MEMORY_TITLE])
        assertEquals(1, versions[SyncKinds.MEMORY_COPY])
    }

    @Test
    fun should_tellWhichIdsCanBeCopied() {
        val sub = account("a", "s-a", "subsonic", "music.example.com")
        val sameServer = account("b", "s-b", "subsonic", "MUSIC.example.com")
        val otherServer = account("c", "s-c", "subsonic", "other.example.com")
        val apple1 = account("x", "a-1", "applemusic")
        val apple2 = account("y", "a-2", "applemusic")
        val spotify1 = account("s1", "s-s1", "spotify")
        val spotify2 = account("s2", "s-s2", "spotify")

        assertTrue(MemoryTransfer.compatible(sub, sameServer, SourceRef("subsonic", "1")))
        assertFalse(MemoryTransfer.compatible(sub, otherServer, SourceRef("subsonic", "1")))
        assertTrue("catalog ids", MemoryTransfer.compatible(apple1, apple2, SourceRef("applemusic", "1440857781")))
        assertFalse("library ids are per listener", MemoryTransfer.compatible(apple1, apple2, SourceRef("applemusic", "library:i.abc")))
        assertTrue(MemoryTransfer.compatible(spotify1, spotify2, SourceRef("spotify", "4uLU6hMCjMI75M1A2tKUQC")))
        assertFalse(MemoryTransfer.compatible(sub, spotify1, SourceRef("subsonic", "1")))
        assertNotEquals(MigrationIds.derive("s-a", "n1", "s-b"), MigrationIds.derive("s-a", "n1", "s-c"))
        assertEquals(MigrationIds.derive("s-a", "n1", "s-b"), MigrationIds.derive("s-a", "n1", "s-b"))
    }

    @Test
    fun should_keepTheLedgerInTheSyncDatabase() = runTest {
        val stored = SyncMetaTransferLedger(device.syncDb)
        stored.write("s-a", "p1", mapOf("note:n1" to "copied|mig-1", "track:subsonic:1" to "unmatched"))
        stored.write("s-a", "p2", mapOf("note:n1" to "copied|mig-2"))

        assertEquals(mapOf("note:n1" to "copied|mig-1", "track:subsonic:1" to "unmatched"), stored.read("s-a", "p1"))
        assertEquals(mapOf("note:n1" to "copied|mig-2"), stored.read("s-a", "p2"))
        assertTrue(stored.read("s-b", "p1").isEmpty())
    }
}
