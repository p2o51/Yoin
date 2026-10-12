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
    fun should_startTheTappedSong_when_unplayableSongsComeBeforeAndAfterIt() {
        // Library Songs, rows 233–247 on the tablet: imports and songs the storefront has no catalog song
        // for sit around "Angel Of Satisfaction". MusicKit dropped the second kind without a word, and the
        // start index then landed eight songs further on.
        val tracks = listOf(
            catalog("0"), import("a"), pulled("1"), import("b"), import("c"), pulled("2"),
            catalog("1"), import("d"), pulled("3"), pulled("4"), catalog("2"), pulled("5")
        )

        val queue = playableQueue(tracks, startIndex = 6, explicitStart = true)

        assertEquals(listOf(catalog("0"), catalog("1"), catalog("2")), queue.tracks)
        assertEquals(catalog("1"), queue.tracks[queue.startIndex])
    }

    @Test
    fun should_keepTheQueueAsGiven_when_tappedSongHasNoCatalogSongInTheStorefront() {
        // The source turns it down with its reason; nothing else starts in its place.
        val tracks = listOf(catalog("1"), pulled("1"), catalog("2"))

        val queue = playableQueue(tracks, startIndex = 1, explicitStart = true)

        assertSame(tracks, queue.tracks)
        assertEquals(1, queue.startIndex)
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

    @Test
    fun should_startTheTappedSongInAFullWindow_when_applePlaylistExceedsCatalogRequestLimit() {
        for (size in listOf(301, 650)) {
            val tracks = List(size) { catalog(it.toString()) }
            for (start in listOf(0, size / 2, size - 1)) {
                val queue = playableStartQueue(tracks, start, explicitStart = true, sourceId = MediaId.PROVIDER_APPLE_MUSIC)

                assertEquals(100, queue.tracks.size)
                assertEquals(tracks[start], queue.tracks[queue.startIndex])
                val first = tracks.indexOf(queue.tracks.first())
                assertEquals(tracks.subList(first, first + 100), queue.tracks)
                assertEquals(if (start == 0) 0 else if (start == size - 1) 99 else 20, queue.startIndex)
            }
        }
    }

    @Test
    fun should_preserveTheOrderedPlayableWindow_when_unavailableSongsSurroundTheAppleStart() {
        val playable = List(400) { catalog(it.toString()) }
        val tracks = playable.flatMapIndexed { index, song -> listOf(import("$index"), song, pulled("$index")) }

        val queue = playableStartQueue(tracks, 601, explicitStart = true, sourceId = MediaId.PROVIDER_APPLE_MUSIC)

        assertEquals(playable.subList(180, 280), queue.tracks)
        assertEquals(20, queue.startIndex)
        assertEquals(playable[200], queue.tracks[queue.startIndex])
    }

    @Test
    fun should_keepTheWholePlayableQueue_when_filteringBringsAppleStartWithinTheLimit() {
        val playable = List(300) { catalog(it.toString()) }
        val tracks = playable.flatMapIndexed { index, song -> listOf(import("$index"), song) }

        val queue = playableStartQueue(tracks, 501, explicitStart = true, sourceId = MediaId.PROVIDER_APPLE_MUSIC)

        assertEquals(playable, queue.tracks)
        assertEquals(250, queue.startIndex)
    }

    @Test
    fun should_keepTheUnavailableTargetForItsError_when_itIsTappedInALargeAppleList() {
        val tracks = List(400) { if (it == 200) pulled("$it") else catalog("$it") }

        val queue = playableStartQueue(tracks, 200, explicitStart = true, sourceId = MediaId.PROVIDER_APPLE_MUSIC)

        assertSame(tracks, queue.tracks)
        assertEquals(200, queue.startIndex)
    }

    @Test
    fun should_keepTheFullQueue_when_appleIsWithinTheLimitOrAnotherProviderStarts() {
        for (provider in listOf(MediaId.PROVIDER_APPLE_MUSIC, MediaId.PROVIDER_SUBSONIC, MediaId.PROVIDER_SPOTIFY)) {
            val tracks = List(if (provider == MediaId.PROVIDER_APPLE_MUSIC) 300 else 650) {
                track(MediaId(provider, "$it"))
            }
            val queue = playableStartQueue(tracks, 200, explicitStart = true, sourceId = provider)

            assertSame(tracks, queue.tracks)
            assertEquals(200, queue.startIndex)
        }
    }

    @Test
    fun should_startTheFirstPlayableSong_when_largeApplePlayBeginsWithAnUnavailableItem() {
        val tracks = listOf(import("first")) + List(400) { catalog(it.toString()) }

        val queue = playableStartQueue(tracks, 0, explicitStart = false, sourceId = MediaId.PROVIDER_APPLE_MUSIC)

        assertEquals(tracks.subList(1, 101), queue.tracks)
        assertEquals(0, queue.startIndex)
    }

    private fun catalog(id: String) = track(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "14408577$id"))

    private fun import(id: String) = track(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "library:i.$id"))

    /** A library song whose play parameters name a catalog id the account's storefront doesn't have. */
    private fun pulled(id: String) = track(
        MediaId(MediaId.PROVIDER_APPLE_MUSIC, "18482562$id"),
        mapOf("appleMusicCatalogId" to "18482562$id", "appleMusicCatalogUnavailable" to "true")
    )

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
