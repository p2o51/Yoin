package com.gpo.yoin.ui.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.LibraryRecents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Deleting a profile clears what Library kept for it on the device (its
 * opens and its sort choices), and only its: another profile's stay.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryStoresClearTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearFiles() {
        listOf("yoin_library_opens", "yoin_library_sort").forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun should_forgetTheProfilesOpensOnEveryService_when_cleared() = runTest {
        val store = SharedPrefsLibraryOpenStore(context, io = Dispatchers.Unconfined)
        store.recordOpened("profile-a", MediaId.PROVIDER_SPOTIFY, LibraryOpenKind.Album, "al", at = 100L)
        store.recordOpened("profile-a", MediaId.PROVIDER_SUBSONIC, LibraryOpenKind.Artist, "ar", at = 200L)
        store.recordOpened("profile-ab", MediaId.PROVIDER_SPOTIFY, LibraryOpenKind.Album, "al", at = 300L)

        store.clear("profile-a")

        val reopened = SharedPrefsLibraryOpenStore(context, io = Dispatchers.Unconfined)
        assertEquals(LibraryRecents(), reopened.observe("profile-a", MediaId.PROVIDER_SPOTIFY).first())
        assertEquals(LibraryRecents(), reopened.observe("profile-a", MediaId.PROVIDER_SUBSONIC).first())
        assertEquals(mapOf("al" to 300L), reopened.observe("profile-ab", MediaId.PROVIDER_SPOTIFY).first().albums)
        // The store's own readers see it at once.
        assertEquals(LibraryRecents(), store.observe("profile-a", MediaId.PROVIDER_SPOTIFY).first())
    }

    @Test
    fun should_forgetTheProfilesSortChoices_when_cleared() {
        SharedPrefsLibrarySortStore(context).apply {
            setSort("profile-a", LibraryTab.Albums, LibrarySort.Creator)
            setSort("profile-a", LibraryTab.All, LibrarySort.Alphabetical)
            setSort("profile-ab", LibraryTab.Albums, LibrarySort.RecentlyAdded)
            clear("profile-a")
        }

        val reopened = SharedPrefsLibrarySortStore(context)
        assertNull(reopened.sortFor("profile-a", LibraryTab.Albums))
        assertNull(reopened.sortFor("profile-a", LibraryTab.All))
        assertEquals(LibrarySort.RecentlyAdded, reopened.sortFor("profile-ab", LibraryTab.Albums))
    }

    @Test
    fun should_forgetOnlyThatProfile_when_theInMemoryStoresAreCleared() = runTest {
        val opens = LibraryOpenStore.InMemory()
        opens.recordOpened("profile-a", MediaId.PROVIDER_SPOTIFY, LibraryOpenKind.Playlist, "pl", at = 1L)
        opens.recordOpened("profile-b", MediaId.PROVIDER_SPOTIFY, LibraryOpenKind.Playlist, "pl", at = 2L)
        val sorts = LibrarySortStore.InMemory()
        sorts.setSort("profile-a", LibraryTab.Playlists, LibrarySort.Creator)
        sorts.setSort("profile-b", LibraryTab.Playlists, LibrarySort.Creator)

        opens.clear("profile-a")
        sorts.clear("profile-a")

        assertEquals(LibraryRecents(), opens.observe("profile-a", MediaId.PROVIDER_SPOTIFY).first())
        assertEquals(mapOf("pl" to 2L), opens.observe("profile-b", MediaId.PROVIDER_SPOTIFY).first().playlists)
        assertNull(sorts.sortFor("profile-a", LibraryTab.Playlists))
        assertEquals(LibrarySort.Creator, sorts.sortFor("profile-b", LibraryTab.Playlists))
    }
}
