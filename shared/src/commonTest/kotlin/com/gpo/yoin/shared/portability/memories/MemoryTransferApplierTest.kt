package com.gpo.yoin.shared.portability.memories

import com.gpo.yoin.shared.portability.BackupTrackAlbumLink
import com.gpo.yoin.shared.sync.testing.assertEquals
import com.gpo.yoin.shared.sync.testing.assertFalse
import com.gpo.yoin.shared.sync.testing.assertNotEquals
import com.gpo.yoin.shared.sync.testing.assertTrue
import kotlin.test.Test
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** The transfer rules Android's MemoryTransferTest checks on Room, on the shared applier. */
class MemoryTransferApplierTest {
    private val store = FakeTransferStore()
    private val applier = MemoryTransferApplier(store)

    private fun account(profile: String?, scope: String, provider: String, server: String? = null, name: String = scope) =
        TransferAccount(scope, provider, name, server, profile)

    private fun sub(id: String) = SourceRef("subsonic", id)

    private val items = MemoryItems(
        songNotes = listOf(SourceNote("n1", sub("t1"), "first listen [@Al](yoin:album/subsonic:al1)", 2_000, 2_000, "Title t1", "Artist", 61_000)),
        trackRatings = listOf(SourceTrackRating(sub("t1"), 9f, 2_100), SourceTrackRating(sub("t2"), 6f, 2_150)),
        albumRatings = listOf(SourceAlbumRating(sub("al1"), 8f, 2_300)),
        albumReviews = listOf(SourceAlbumReview(sub("al1"), "Source review.", 2_300, 2_300)),
        albumNotes = listOf(SourceAlbumNote("an1", sub("al1"), "album note", 2_400, 2_400, "Album al1", "Artist")),
        albumTitles = listOf(SourceAlbumTitle(sub("al1"), "My Title", 2_500)),
        memoryTitles = listOf(SourceMemoryTitle("subsonic", "album", "al1", "AI Title", "th", 2_600)),
        memoryCopies = listOf(SourceMemoryCopy("subsonic", "album", "al1", "AI copy", "ph", 2_600)),
        trackAlbumLinks = listOf(SourceTrackLink(sub("t1"), "al1", BackupTrackAlbumLink.SOURCE_DETAIL, 2_700)),
    )

    private suspend fun run(
        src: TransferAccount,
        dst: TransferAccount,
        items: MemoryItems = this.items,
        tracks: List<TrackPick> = emptyList(),
        albums: List<AlbumPick> = emptyList(),
        ledger: Map<String, String> = emptyMap(),
    ) = applier.apply(src, dst, items, tracks, albums, ledger) { 99_000 }

    @Test
    fun should_copyEveryKindWithKeysAsIs_when_bothAccountsAreOnOneSubsonicServer() = runTest {
        val src = account("old", "s-old", "subsonic", "music.example.com", "Old account")
        val dst = account("new", "s-new", "subsonic", "music.example.com")

        val outcome = run(src, dst)

        assertEquals(10, outcome.result.copied.total)
        val note = store.songNotes.values.single()
        assertEquals(MigrationIds.derive("s-old", "n1", "s-new"), note.id)
        assertTrue(note.id.startsWith("mig-") && note.id.length == 20)
        assertEquals("t1", note.trackId)
        assertEquals("same ids: the mention is left alone", items.songNotes.single().content, note.content)
        assertEquals(TransferTrackRating(9f, 2_100), store.trackRatings[listOf("new", "subsonic", "t1")])
        assertEquals(TransferAlbumRow(8f, "Source review.", 2_300, 2_300), store.albums[listOf("new", "subsonic", "al1")])
        assertEquals(MigrationIds.derive("s-old", "an1", "s-new"), store.albumNotes.values.single().id)
        assertEquals("My Title", store.titles[listOf("new", "subsonic", "al1")]?.title)
        val memory = store.memories[listOf("new", "subsonic", "album", "al1")]!!
        assertEquals("AI Title", memory.title)
        assertEquals("AI copy", memory.copyText)
        assertEquals("al1", store.links[listOf("new", "subsonic", "t1")]?.albumId)
        assertEquals(TransferLedger.COPIED + note.id, outcome.ledgerEntries["note:n1"])
    }

    @Test
    fun should_skipWhatWasCopied_when_runAgainWithTheLedger() = runTest {
        val src = account("old", "s-old", "subsonic", "music.example.com")
        val dst = account("new", "s-new", "subsonic", "music.example.com")
        val first = run(src, dst)
        val more = items.copy(songNotes = items.songNotes + SourceNote("n2", sub("t3"), "written later", 5_000, 5_000, "T3", "A", null))

        val again = run(src, dst, items = more, ledger = first.ledgerEntries)

        assertEquals(10, again.result.skippedPreviously)
        assertEquals(1, again.result.copied.songNotes)
        assertEquals(1, again.result.copied.total)
        assertEquals(2, store.songNotes.size)
    }

    @Test
    fun should_resolveConflictsByTheRules_when_theTargetAlreadyHasMemories() = runTest {
        store.trackRatings[listOf("new", "subsonic", "t1")] = TransferTrackRating(4f, 9_000) // newer: stays
        store.trackRatings[listOf("new", "subsonic", "t2")] = TransferTrackRating(2f, 1_000) // older: replaced
        store.albums[listOf("new", "subsonic", "al1")] = TransferAlbumRow(3f, "Target review.", 1_000, 1_000)
        store.memories[listOf("new", "subsonic", "album", "al1")] = TransferMemoryRow("subsonic", "album", "al1", "", "", 0, "Own AI", "x")
        val src = account("old", "s-old", "subsonic", "music.example.com", "Old account")
        val dst = account("new", "s-new", "subsonic", "music.example.com")

        val result = run(src, dst).result

        assertEquals(4f, store.trackRatings[listOf("new", "subsonic", "t1")]?.rating)
        assertEquals(6f, store.trackRatings[listOf("new", "subsonic", "t2")]?.rating)
        val album = store.albums[listOf("new", "subsonic", "al1")]!!
        assertEquals(8f, album.rating)
        assertEquals("Target review.\n\n— 来自 Old account\nSource review.", album.review)
        assertEquals(1, result.reviewsJoined)
        assertEquals("the target's own AI title wins", "Own AI", store.memories[listOf("new", "subsonic", "album", "al1")]?.title)
        assertEquals("AI copy", store.memories[listOf("new", "subsonic", "album", "al1")]?.copyText)
    }

    @Test
    fun should_followMatchesAndRemapMentions_when_idsDontCarryOver() = runTest {
        val src = account("old", "s-old", "subsonic", "music.example.com")
        val dst = account("spot", "s-spot", "spotify")
        val tracks = listOf(
            TrackPick(sub("t1"), MatchStatus.Matched, TrackTarget("spotify", "sp1", "Song One", "Artist", "spotify", "spA")),
            TrackPick(sub("t2"), MatchStatus.Uncertain, null),
        )
        val albums = listOf(AlbumPick(sub("al1"), MatchStatus.Picked, AlbumTarget("spotify", "spA", "Rain", "Artist")))

        val outcome = run(src, dst, tracks = tracks, albums = albums)

        val note = store.songNotes.values.single()
        assertEquals("sp1", note.trackId)
        assertEquals("Song One", note.title)
        assertEquals("first listen [@Al](yoin:album/spotify:spA)", note.content)
        assertNull(store.trackRatings[listOf("spot", "spotify", "t2")], "an uncertain song isn't copied")
        assertEquals(1, outcome.result.unmatched.trackRatings)
        assertEquals("Rain", store.albumNotes.values.single().albumName)
        assertEquals("spA", store.links[listOf("spot", "spotify", "sp1")]?.albumId)
        assertEquals(TransferLedger.MATCHED + "spotify:sp1", outcome.ledgerEntries["track:subsonic:t1"])
        assertEquals(TransferLedger.UNMATCHED, outcome.ledgerEntries["track:subsonic:t2"])
        assertEquals(TransferLedger.CHOSEN + "spotify:spA", outcome.ledgerEntries["album:subsonic:al1"])
    }

    @Test
    fun should_mapTheLinksAlbumOnItsOwn_when_theAlbumIdIsLibraryOnly() = runTest {
        val apple = MemoryItems(
            trackRatings = listOf(SourceTrackRating(SourceRef("applemusic", "1440857781"), 8f, 2_000)),
            trackAlbumLinks = listOf(
                SourceTrackLink(SourceRef("applemusic", "1440857781"), "library:l.abc", "detail", 2_000),
                SourceTrackLink(SourceRef("applemusic", "1440857782"), "1440857700", "detail", 2_000),
            ),
        )

        val result = run(account("apple1", "a-1", "applemusic"), account("apple2", "a-2", "applemusic"), items = apple).result

        assertEquals(8f, store.trackRatings[listOf("apple2", "applemusic", "1440857781")]?.rating)
        assertNull(store.links[listOf("apple2", "applemusic", "1440857781")])
        assertEquals("1440857700", store.links[listOf("apple2", "applemusic", "1440857782")]?.albumId)
        assertEquals(1, result.unmatched.trackAlbumLinks)
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

        assertTrue(TransferRules.compatible(sub, sameServer, SourceRef("subsonic", "1")))
        assertFalse(TransferRules.compatible(sub, otherServer, SourceRef("subsonic", "1")))
        assertTrue("catalog ids", TransferRules.compatible(apple1, apple2, SourceRef("applemusic", "1440857781")))
        assertFalse("library ids are per listener", TransferRules.compatible(apple1, apple2, SourceRef("applemusic", "library:i.abc")))
        assertFalse(TransferRules.compatible(apple1, apple2, SourceRef("applemusic", "i.abc")))
        assertFalse(TransferRules.compatible(apple1, apple2, SourceRef("applemusic", "l.abc")))
        assertTrue(TransferRules.compatible(spotify1, spotify2, SourceRef("spotify", "4uLU6hMCjMI75M1A2tKUQC")))
        assertFalse(TransferRules.compatible(sub, spotify1, SourceRef("subsonic", "1")))
        assertNotEquals(MigrationIds.derive("s-a", "n1", "s-b"), MigrationIds.derive("s-a", "n1", "s-c"))
        assertEquals(MigrationIds.derive("s-a", "n1", "s-b"), MigrationIds.derive("s-a", "n1", "s-b"))
    }

    @Test
    fun should_remapOnlyMappedMentions_inPlace() {
        val text = "a [@X](yoin:track/subsonic:t1) b [@Y](yoin:album/subsonic:al1) c"
        val out = TransferRules.remapMentions(
            text,
            trackTarget = { if (it.id == "t1") TargetRef("spotify", "s1") else null },
            albumTarget = { null },
        )
        assertEquals("a [@X](yoin:track/spotify:s1) b [@Y](yoin:album/subsonic:al1) c", out)
        assertEquals(text, TransferRules.remapMentions(text, { TargetRef(it.provider, it.id) }, { TargetRef(it.provider, it.id) }))
    }

    @Test
    fun should_keepTheLedgerMetaPrefixStable() {
        assertEquals("transfer:", TransferLedger.metaPrefix("s-a", "p1").take(9))
        assertEquals(26, TransferLedger.metaPrefix("s-a", "p1").length)
        assertEquals(TransferRules.parseTarget("spotify:sp3a"), TargetRef("spotify", "sp3a"))
        assertNull(TransferRules.parseTarget(":x"))
        assertNull(TransferRules.parseTarget("x:"))
    }
}
