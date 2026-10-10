package com.gpo.yoin.ui.library

import android.app.Application
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.ui.component.FastScrollSection
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [LibraryScrollIndexer] on the real android.icu (English): each sort's
 * sections over the order it leaves, every label one run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LibraryScrollIndexerTest {

    private val index = LibraryScrollIndex.icu(Locale.ENGLISH)
    private val sorter = LibrarySorter(libraryNameOrder(Locale.ENGLISH), ignoredArticles = LibraryIgnoredArticles)
    private val indexer = LibraryScrollIndexer(index, LibraryIgnoredArticles)

    @Test
    fun should_putDigitsLastAndKeepLettersWhole_when_artistsSortedAlphabetically() {
        val artists = listOf("2NE1", "Beta", "The Beatles", "alpha", "!!!", "Ólafur Arnalds", "Bravo").map(::artist)

        val result = indexer.artists(sorter.artists(artists, LibrarySort.Alphabetical), LibrarySort.Alphabetical)

        assertEquals(
            listOf("alpha", "The Beatles", "Beta", "Bravo", "Ólafur Arnalds"),
            result.items.take(5).map { it.name }
        )
        assertEquals(listOf("A", "B", "O", "#"), result.sections.map { it.label })
        assertEquals(LibraryIndex.NUMBER_LABEL, result.labelOf(result.items.indexOfFirst { it.name == "2NE1" }))
        assertRuns(result)
    }

    @Test
    fun should_showTheHandleAlone_when_artistsSortedByRecents() {
        val artists = listOf("Beta", "alpha").map(::artist)

        val result = indexer.artists(sorter.artists(artists, LibrarySort.Recents), LibrarySort.Recents)

        assertEquals(emptyList<FastScrollSection>(), result.sections)
        assertEquals(sorter.artists(artists, LibrarySort.Recents), result.items)
    }

    @Test
    fun should_cutByTheArtistsLetter_when_albumsSortedByCreator() {
        val albums = listOf(
            album("1", "Zenith", artist = "The Cure"),
            album("2", "Alpha", artist = "Radiohead"),
            album("3", "Kid A", artist = "Radiohead"),
            album("4", "Loose", artist = null),
            album("5", "Number", artist = "2Pac"),
            album("6", "Ballads", artist = "Adele")
        )

        val result = indexer.albums(sorter.albums(albums, LibrarySort.Creator), LibrarySort.Creator)

        // Adele, The Cure (under C), Radiohead's two by title, 2Pac, then no artist.
        assertEquals(listOf("6", "1", "2", "3", "5", "4"), result.items.map { it.id.rawId })
        assertEquals(
            listOf(section("A", 0), section("C", 1), section("R", 2), section("#", 4)),
            result.sections
        )
    }

    @Test
    fun should_trailUnnamedUnderAnOwnHash_when_noCreatorIsADigit() {
        val albums = listOf(album("1", "One", artist = "Adele"), album("2", "Two", artist = " "))

        val result = indexer.albums(sorter.albums(albums, LibrarySort.Creator), LibrarySort.Creator)

        assertEquals(listOf("1", "2"), result.items.map { it.id.rawId })
        assertEquals(listOf(section("A", 0), section("#", 1)), result.sections)
    }

    @Test
    fun should_dateArtistsByTheirNewestAlbum_when_allSortedByRecentlyAdded() {
        val albums = (2016..2024).map { year ->
            album("al$year", "Album $year", artist = "Artist $year", added = "$year-05-01T00:00:00Z")
        }
        val artists = listOf(artist("Artist 2024"), artist("Artist 2017"))
        val playlists = listOf(playlist("pl", "Mix", added = "2020-02-01T00:00:00Z"))
        val sorted = sorter.all(artists, albums, playlists, LibrarySort.RecentlyAdded)

        val result = indexer.all(sorted, LibrarySort.RecentlyAdded, albums)

        assertEquals(sorted, result.items)
        // Every year a tick (2020 holds two months), the artists inside their albums' years.
        assertEquals((2024 downTo 2016).map(Int::toString), result.sections.map { it.tickLabel }.distinct())
        val artist2017 = result.items.indexOfFirst { (it as? LibraryItem.ArtistItem)?.artist?.name == "Artist 2017" }
        assertEquals("2017", result.sections[sectionAt(result.sections, artist2017)].tickLabel)
        assertRuns(result)
    }

    @Test
    fun should_keepEachLetterOneRun_when_allMixesKindsAlphabetically() {
        val sorted = sorter.all(
            artists = listOf(artist("Echo"), artist("2Pac"), artist("Björk")),
            albums = listOf(album("1", "Bloom", artist = "x"), album("2", "echoes", artist = "y")),
            playlists = listOf(playlist("p", "Afternoon")),
            sort = LibrarySort.Alphabetical
        )

        val result = indexer.all(sorted, LibrarySort.Alphabetical, albums = emptyList())

        assertEquals(listOf("A", "B", "E", "#"), result.sections.map { it.label })
        assertRuns(result)
    }

    @Test
    fun should_listPlaylistsInTheSameAlphabet_when_sortedAlphabetically() {
        val playlists = listOf(playlist("1", "2000s"), playlist("2", "Chill"), playlist("3", "after hours"))

        val result = indexer.playlists(sorter.playlists(playlists, LibrarySort.Alphabetical), LibrarySort.Alphabetical)

        assertEquals(listOf("after hours", "Chill", "2000s"), result.items.map { it.name })
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Every label is one run: a letter or a year never comes back further down. */
    private fun assertRuns(result: LibraryIndex.Sorted<*>) {
        val labels = result.sections.map { it.label }
        assertEquals("labels repeat: $labels", labels.distinct(), labels)
        assertTrue(result.sections.zipWithNext().all { (a, b) -> a.startIndex < b.startIndex })
        assertEquals(0, result.sections.firstOrNull()?.startIndex ?: 0)
    }

    private fun LibraryIndex.Sorted<*>.labelOf(index: Int): String = sections[sectionAt(sections, index)].label

    private fun sectionAt(sections: List<FastScrollSection>, index: Int): Int =
        sections.indexOfLast { it.startIndex <= index }

    private fun section(label: String, start: Int) = FastScrollSection(label, startIndex = start)

    private fun artist(name: String) = Artist(MediaId.subsonic("ar:$name"), name, albumCount = null, coverArt = null)

    private fun album(id: String, name: String, artist: String?, added: String? = null) = Album(
        id = MediaId.subsonic(id),
        name = name,
        artist = artist,
        artistId = null,
        coverArt = null,
        songCount = null,
        durationSec = null,
        year = null,
        genre = null,
        libraryAddedAt = added
    )

    private fun playlist(id: String, name: String, added: String? = null) = Playlist(
        id = MediaId.subsonic(id),
        name = name,
        owner = null,
        coverArt = null,
        songCount = null,
        durationSec = null,
        libraryAddedAt = added
    )
}
