package com.gpo.yoin.ui.library

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.repository.LibraryRecents
import com.gpo.yoin.data.source.ServiceFeatureCatalog
import java.text.Collator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [LibrarySorter]'s four orders, its fallbacks, and which orders each view offers ([librarySortOptions]). */
class LibrarySorterTest {

    // The JDK collator stands in for android.icu off device; LibrarySortStoreTest runs the ICU one.
    private val nameOrder: Comparator<String> = Collator.getInstance(Locale.ENGLISH).let { collator ->
        Comparator { a, b -> collator.compare(a, b) }
    }

    private fun sorter(recents: LibraryRecents = LibraryRecents.None, ignoredArticles: List<String> = emptyList()) =
        LibrarySorter(nameOrder, recents, ignoredArticles)

    // ── Recents ─────────────────────────────────────────────────────────

    @Test
    fun should_putLastOpenedFirst_when_sortingAlbumsByRecents() {
        val old = album("old", "Old", added = "2020-01-01T00:00:00Z")
        val new = album("new", "New", added = "2024-01-01T00:00:00Z")
        val visited = album("visited", "Visited", added = "2019-01-01T00:00:00Z")
        val played = album("played", "Played", added = "2018-01-01T00:00:00Z")
        val recents = LibraryRecents(albums = mapOf("visited" to 200L, "played" to 300L))

        val sorted = sorter(recents).albums(listOf(old, new, visited, played), LibrarySort.Recents)

        // Records newest first, then what has none by Recently added.
        assertEquals(listOf("played", "visited", "new", "old"), sorted.ids())
    }

    @Test
    fun should_fallBackToRecentlyAddedThenNames_when_noRecordsExist() {
        val albums = listOf(
            album("b", "Beta", added = null),
            album("a", "Alpha", added = null),
            album("dated", "Zulu", added = "2023-05-01T00:00:00Z")
        )

        val sorted = sorter().albums(albums, LibrarySort.Recents)

        assertEquals(listOf("dated", "a", "b"), sorted.ids())
    }

    @Test
    fun should_orderUndatedArtistsByName_when_artistsHaveNoRecords() {
        val artists = listOf(artist("c", "Caroline"), artist("a", "Arca"), artist("b", "Bruit"))
        val recents = LibraryRecents(artists = mapOf("b" to 10L))

        val sorted = sorter(recents).artists(artists, LibrarySort.Recents)

        assertEquals(listOf("b", "a", "c"), sorted.map { it.id.rawId })
    }

    @Test
    fun should_mixKindsByTheirOwnRecords_when_allSortsByRecents() {
        val recents = LibraryRecents(
            albums = mapOf("al" to 100L),
            artists = mapOf("ar" to 300L),
            playlists = mapOf("pl" to 200L)
        )

        val sorted = sorter(recents).all(
            artists = listOf(artist("ar", "Artist"), artist("ar2", "Zz artist")),
            albums = listOf(album("al", "Album"), album("al2", "Newer album", added = "2024-01-01T00:00:00Z")),
            playlists = listOf(playlist("pl", "Playlist")),
            sort = LibrarySort.Recents
        )

        assertEquals(
            listOf(
                "artist:subsonic:ar",
                "playlist:subsonic:pl",
                "album:subsonic:al",
                "album:subsonic:al2",
                "artist:subsonic:ar2"
            ),
            sorted.map(LibraryItem::key)
        )
    }

    // ── Recently added ──────────────────────────────────────────────────

    @Test
    fun should_readEveryProviderDateForm_when_sortingByRecentlyAdded() {
        val albums = listOf(
            album("date-only", "D", added = "2021-06-01"),
            album("subsonic", "S", added = "2023-02-03T04:05:06.789Z"),
            album("offset", "O", added = "2022-07-08T09:10:11+09:00"),
            album("local", "L", added = "2020-01-01T00:00:00"),
            album("broken", "B", added = "yesterday")
        )

        val sorted = sorter().albums(albums, LibrarySort.RecentlyAdded)

        // An unreadable date is no date: last.
        assertEquals(listOf("subsonic", "offset", "date-only", "local", "broken"), sorted.ids())
    }

    @Test
    fun should_sortByLibraryDateNotStarTime_when_subsonicAlbumHasBoth() {
        // addedAt is Subsonic's star time (Home's Recently Added); Library reads libraryAddedAt.
        val starredLately = album("starred", "Starred", added = "2019-01-01T00:00:00Z")
            .copy(addedAt = "2025-01-01T00:00:00Z")
        val addedLately = album("added", "Added", added = "2024-01-01T00:00:00Z")

        val sorted = sorter().albums(listOf(starredLately, addedLately), LibrarySort.RecentlyAdded)

        assertEquals(listOf("added", "starred"), sorted.ids())
    }

    @Test
    fun should_orderPlaylistsByTheirLibraryDate_when_sortingByRecentlyAdded() {
        val playlists = listOf(
            playlist("old", "Old", added = "2020-01-01T00:00:00Z"),
            playlist("new", "New", added = "2024-01-01T00:00:00Z")
        )

        val sorted = sorter().playlists(playlists, LibrarySort.RecentlyAdded)

        assertEquals(listOf("new", "old"), sorted.map { it.id.rawId })
    }

    // ── Alphabetical ────────────────────────────────────────────────────

    @Test
    fun should_collateCaseAndAccents_when_sortingAlphabetically() {
        val artists = listOf(
            artist("z", "Zedd"),
            artist("o", "Ólafur Arnalds"),
            artist("a", "adele"),
            artist("b", "Björk"),
            artist("o2", "Oasis")
        )

        val sorted = sorter().artists(artists, LibrarySort.Alphabetical)

        assertEquals(listOf("a", "b", "o2", "o", "z"), sorted.map { it.id.rawId })
    }

    @Test
    fun should_skipLeadingArticles_when_serviceSortsPastThem() {
        val artists = listOf(artist("cold", "Coldplay"), artist("beatles", "The Beatles"), artist("bach", "Bach"))

        val withArticles = sorter(ignoredArticles = LibraryIgnoredArticles).artists(artists, LibrarySort.Alphabetical)
        val plain = sorter().artists(artists, LibrarySort.Alphabetical)

        assertEquals(listOf("bach", "beatles", "cold"), withArticles.map { it.id.rawId })
        assertEquals(listOf("bach", "cold", "beatles"), plain.map { it.id.rawId })
    }

    @Test
    fun should_keepTheWholeName_when_articleIsAllThereIs() {
        assertEquals("The", stripLeadingArticle("The", LibraryIgnoredArticles))
        assertEquals("Theory", stripLeadingArticle("Theory", LibraryIgnoredArticles))
        assertEquals("Beatles", stripLeadingArticle("the  Beatles", LibraryIgnoredArticles))
    }

    // ── Creator ─────────────────────────────────────────────────────────

    @Test
    fun should_orderAlbumsByArtistThenTitle_when_sortingByCreator() {
        val albums = listOf(
            album("kid", "Kid A", artist = "Radiohead"),
            album("ok", "OK Computer", artist = "Radiohead"),
            album("wall", "The Wall", artist = "Pink Floyd"),
            album("anon", "Untitled", artist = null)
        )

        val sorted = sorter().albums(albums, LibrarySort.Creator)

        // No artist: last.
        assertEquals(listOf("wall", "kid", "ok", "anon"), sorted.ids())
    }

    @Test
    fun should_orderPlaylistsByOwnerAndArtistsByName_when_allSortsByCreator() {
        val sorted = sorter().all(
            artists = listOf(artist("m", "Mitski")),
            albums = listOf(album("al", "Be the Cowboy", artist = "Mitski")),
            playlists = listOf(playlist("pl", "Road trip", owner = "Ana"), playlist("anon", "Mix", owner = null)),
            sort = LibrarySort.Creator
        )

        assertEquals(
            listOf("playlist:subsonic:pl", "album:subsonic:al", "artist:subsonic:m", "playlist:subsonic:anon"),
            sorted.map(LibraryItem::key)
        )
    }

    // ── All ─────────────────────────────────────────────────────────────

    @Test
    fun should_keepEachKindOnce_when_allHoldsSharedRawIdsAndDuplicates() {
        val sorted = sorter().all(
            artists = listOf(artist("1", "Same id")),
            albums = listOf(album("1", "Same id"), album("1", "Same id")),
            playlists = listOf(playlist("1", "Same id")),
            sort = LibrarySort.Alphabetical
        )

        assertEquals(
            listOf("artist:subsonic:1", "album:subsonic:1", "playlist:subsonic:1"),
            sorted.map(LibraryItem::key)
        )
    }

    // ── Options ─────────────────────────────────────────────────────────

    @Test
    fun should_offerEveryOrder_when_viewIsAllOrAlbums() {
        for (features in ServiceFeatureCatalog.entries.take(3)) {
            assertEquals(LibrarySort.entries, librarySortOptions(LibraryTab.All, features))
            assertEquals(LibrarySort.entries, librarySortOptions(LibraryTab.Albums, features))
        }
    }

    @Test
    fun should_offerRecentsAndNamesOnly_when_viewIsArtists() {
        for (features in ServiceFeatureCatalog.entries) {
            assertEquals(
                listOf(LibrarySort.Recents, LibrarySort.Alphabetical),
                librarySortOptions(LibraryTab.Artists, features)
            )
        }
    }

    @Test
    fun should_hideRecentlyAddedForPlaylists_when_serviceDatesNoPlaylists() {
        assertEquals(
            listOf(LibrarySort.Recents, LibrarySort.RecentlyAdded, LibrarySort.Alphabetical),
            librarySortOptions(LibraryTab.Playlists, ServiceFeatureCatalog.subsonic)
        )
        assertEquals(
            listOf(LibrarySort.Recents, LibrarySort.RecentlyAdded, LibrarySort.Alphabetical),
            librarySortOptions(LibraryTab.Playlists, ServiceFeatureCatalog.appleMusic)
        )
        // /me/playlists carries no date.
        assertEquals(
            listOf(LibrarySort.Recents, LibrarySort.Alphabetical),
            librarySortOptions(LibraryTab.Playlists, ServiceFeatureCatalog.spotify)
        )
    }

    @Test
    fun should_offerNoSortRow_when_viewIsSongsOrFavorites() {
        for (features in ServiceFeatureCatalog.entries) {
            assertEquals(emptyList<LibrarySort>(), librarySortOptions(LibraryTab.Songs, features))
            assertEquals(emptyList<LibrarySort>(), librarySortOptions(LibraryTab.Favorites, features))
        }
    }

    @Test
    fun should_readNoDate_when_libraryDateIsBlank() {
        assertNull(parseLibraryDate(null))
        assertNull(parseLibraryDate("  "))
    }

    private fun List<Album>.ids() = map { it.id.rawId }

    private fun artist(id: String, name: String) = Artist(
        MediaId.subsonic(id),
        name,
        albumCount = null,
        coverArt = null
    )

    private fun album(id: String, name: String, added: String? = null, artist: String? = "Artist") = Album(
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

    private fun playlist(id: String, name: String, added: String? = null, owner: String? = "owner") = Playlist(
        id = MediaId.subsonic(id),
        name = name,
        owner = owner,
        coverArt = null,
        songCount = null,
        durationSec = null,
        libraryAddedAt = added
    )
}
