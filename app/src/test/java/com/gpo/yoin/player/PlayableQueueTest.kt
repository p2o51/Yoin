package com.gpo.yoin.player

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * One Apple Music import (a library song with no catalog version) in a list
 * failed the whole start in MusicKit, even when the tapped song was playable.
 */
class PlayableQueueTest {

    @Test
    fun should_skipUnplayableAppleImports_when_playingQueue() {
        val tracks = listOf(catalog("1"), import("a"), catalog("2"), import("b"), catalog("3"))

        val queue = playableQueue(tracks, startIndex = 2, explicitStart = true)

        assertEquals(listOf(catalog("1"), catalog("2"), catalog("3")), queue.tracks)
    }

    @Test
    fun should_recomputeStartIndex_when_importsRemovedBeforeClickedTrack() {
        val tracks = listOf(import("a"), catalog("1"), import("b"), import("c"), catalog("2"), catalog("3"))

        val queue = playableQueue(tracks, startIndex = 4, explicitStart = true)

        assertEquals(1, queue.startIndex)
        assertEquals(catalog("2"), queue.tracks[queue.startIndex])
    }

    @Test
    fun should_keepTheQueueAsGiven_when_startedTrackIsAnImport() {
        // The import itself reaches the provider, which reports why it can't play.
        val tracks = listOf(catalog("1"), import("a"), catalog("2"))

        val queue = playableQueue(tracks, startIndex = 1, explicitStart = true)

        assertSame(tracks, queue.tracks)
        assertEquals(1, queue.startIndex)
    }

    @Test
    fun should_startAtFirstPlayableSong_when_noSongPickedAndFirstIsAnImport() {
        // Play / Shuffle: an album or playlist whose first (or shuffled-first) song is an import.
        val tracks = listOf(import("a"), import("b"), catalog("1"), import("c"), catalog("2"))

        val queue = playableQueue(tracks, startIndex = 0, explicitStart = false)

        assertEquals(listOf(catalog("1"), catalog("2")), queue.tracks)
        assertEquals(0, queue.startIndex)
    }

    @Test
    fun should_startAtNextPlayableSong_when_noSongPickedAndStartIsAnImport() {
        val tracks = listOf(catalog("1"), import("a"), catalog("2"), catalog("3"))

        val queue = playableQueue(tracks, startIndex = 1, explicitStart = false)

        assertEquals(catalog("2"), queue.tracks[queue.startIndex])
    }

    @Test
    fun should_startAtLastPlayableSong_when_noSongPickedAndOnlyImportsFollow() {
        val tracks = listOf(catalog("1"), catalog("2"), import("a"), import("b"))

        val queue = playableQueue(tracks, startIndex = 2, explicitStart = false)

        assertEquals(listOf(catalog("1"), catalog("2")), queue.tracks)
        assertEquals(catalog("2"), queue.tracks[queue.startIndex])
    }

    @Test
    fun should_keepTheQueueAsGiven_when_nothingIsPlayable() {
        // Nothing to give way to: the provider's error says why.
        val tracks = listOf(import("a"), import("b"))

        val queue = playableQueue(tracks, startIndex = 0, explicitStart = false)

        assertSame(tracks, queue.tracks)
        assertEquals(0, queue.startIndex)
    }

    @Test
    fun should_passTheListThrough_when_noTrackIsAnImport() {
        val tracks = listOf(
            catalog("1"),
            // A library song Apple matched: playable through its catalog id.
            track(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "library:i.matched"), mapOf("appleMusicCatalogId" to "9")),
            track(MediaId.subsonic("s1")),
            track(MediaId.spotify("sp1"))
        )

        listOf(true, false).forEach { explicitStart ->
            val queue = playableQueue(tracks, startIndex = 3, explicitStart = explicitStart)

            assertSame(tracks, queue.tracks)
            assertEquals(3, queue.startIndex)
        }
    }

    private fun catalog(id: String) = track(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "14408577$id"))

    private fun import(id: String) = track(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "library:i.$id"))

    private fun track(id: MediaId, extras: Map<String, String> = emptyMap()) = Track(
        id = id,
        title = "Track $id",
        artist = "Artist",
        artistId = null,
        album = "Album",
        albumId = null,
        coverArt = null,
        durationSec = 180,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
        extras = extras
    )
}
