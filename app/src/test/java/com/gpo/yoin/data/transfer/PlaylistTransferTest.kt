package com.gpo.yoin.data.transfer

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.PartialPlaylistImportException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistTransferTest {

    @Test
    fun should_matchByIsrcBeforeSearching_when_targetKnowsTheCode() = runTest {
        val target = FakeTransferTarget(
            isrc = mapOf("USQE92100257" to listOf(track("t1", "Entropy", "Beach Bunny", 221))),
        )
        val source = track("s1", "Entropy", "Beach Bunny", 221, isrc = "USQE92100257")

        val resolutions = PlaylistTransfer().resolve(listOf(source), target)

        assertEquals(MatchDecision.Matched(target.isrc.values.single().single(), MatchMethod.Isrc), resolutions.single().decision)
        assertEquals(emptyList<String>(), target.searches)
        assertEquals(listOf(listOf("USQE92100257")), target.isrcLookups)
    }

    @Test
    fun should_fallBackToSearch_when_isrcIsUnknownOnTarget() = runTest {
        val match = track("t1", "Creep", "Radiohead", 238)
        val target = FakeTransferTarget(search = mapOf("Creep Radiohead" to listOf(match)))

        val resolutions = PlaylistTransfer().resolve(
            listOf(track("s1", "Creep", "Radiohead", 239, isrc = "GBAYE9200070")),
            target,
        )

        assertEquals(MatchDecision.Matched(match, MatchMethod.Search), resolutions.single().decision)
    }

    @Test
    fun should_searchTheTitleAlone_when_titleAndArtistFindNothing() = runTest {
        val match = track("t1", "Lemon", "米津玄師", 255)
        val target = FakeTransferTarget(search = mapOf("Lemon" to listOf(match)))

        // Romanised on one service, original script on the other: only the title search finds it,
        // and the artist floor still has the last word.
        val resolutions = PlaylistTransfer().resolve(listOf(track("s1", "Lemon", "Kenshi Yonezu", 255)), target)

        assertEquals(listOf("Lemon Kenshi Yonezu", "Lemon"), target.searches)
        assertEquals(MatchDecision.NotFound, resolutions.single().decision)
    }

    @Test
    fun should_leaveTrackUnmatched_when_searchFails() = runTest {
        val target = FakeTransferTarget(searchFails = true)

        val resolutions = PlaylistTransfer().resolve(listOf(track("s1", "Creep", "Radiohead", 238)), target)

        assertEquals(MatchDecision.NotFound, resolutions.single().decision)
    }

    @Test
    fun should_addOnlyMatchedTracksInSourceOrder_when_writing() = runTest {
        val a = track("ta", "A Song", "Artist", 200)
        val c = track("tc", "C Song", "Artist", 200)
        val target = FakeTransferTarget(
            search = mapOf(
                "A Song Artist" to listOf(a),
                "C Song Artist" to listOf(c),
                // Close but not close enough: 7 s off.
                "B Song Artist" to listOf(track("tb", "B Song", "Artist", 207)),
            ),
        )
        val playlist = Playlist(
            id = MediaId("src", "p"),
            name = "Mix",
            owner = null,
            coverArt = null,
            songCount = 4,
            durationSec = null,
            comment = "For the road",
            tracks = listOf(
                track("s1", "C Song", "Artist", 200),
                track("s2", "B Song", "Artist", 200),
                track("s3", "A Song", "Artist", 200),
                track("s4", "Unknown", "Nobody", 100),
            ),
        )
        val progress = mutableListOf<Int>()

        val report = PlaylistTransfer().copy(playlist, target) { done, _ -> progress += done }

        assertEquals(listOf("Mix" to "For the road"), target.created)
        assertEquals(listOf(c.id, a.id), target.added)
        assertEquals(2, report.added)
        assertEquals(listOf(1), report.ambiguous.map { it.position })
        assertEquals(listOf(3), report.unmatched.map { it.position })
        assertEquals(listOf(1, 3), report.missing.map { it.position })
        assertEquals(listOf(1, 2, 3, 4), progress)
        assertNull(report.failure)
    }

    @Test
    fun should_notCreatePlaylist_when_nothingMatched() = runTest {
        val target = FakeTransferTarget()

        val report = PlaylistTransfer().write(
            "Mix",
            null,
            listOf(TrackResolution(0, track("s1", "X", "Y", 1), MatchDecision.NotFound)),
            target,
        )

        assertTrue(target.created.isEmpty())
        assertNull(report.created)
        assertEquals(0, report.added)
    }

    @Test
    fun should_reportWhatWasAdded_when_aLaterBatchFails() = runTest {
        val target = FakeTransferTarget(failAddAfterBatches = 1)
        val matched = (0 until 150).map { index ->
            val found = track("t$index", "Song $index", "Artist", 200)
            TrackResolution(index, track("s$index", "Song $index", "Artist", 200), MatchDecision.Matched(found, MatchMethod.Search))
        }

        val report = PlaylistTransfer().write("Big", null, matched, target)

        assertEquals(100, report.added)
        assertEquals("Big", report.created?.name)
        assertTrue(report.failure is PartialPlaylistImportException)
    }

    private fun track(id: String, title: String, artist: String, durationSec: Int, isrc: String? = null) = Track(
        id = MediaId("t", id),
        title = title,
        artist = artist,
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = durationSec,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
        extras = isrc?.let { mapOf(Track.EXTRA_ISRC to it) }.orEmpty(),
    )
}
