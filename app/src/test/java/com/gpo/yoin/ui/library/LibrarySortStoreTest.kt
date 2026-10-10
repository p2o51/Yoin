package com.gpo.yoin.ui.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The sort choice persists per profile and view on the device, and the
 * production name order is ICU's ([libraryNameOrder]), which needs the
 * platform (Robolectric's android.icu).
 */
@RunWith(RobolectricTestRunner::class)
class LibrarySortStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun should_restoreSortPerProfileAndView_when_storeIsReopened() {
        SharedPrefsLibrarySortStore(context).apply {
            setSort("profile-a", LibraryTab.Albums, LibrarySort.Creator)
            setSort("profile-a", LibraryTab.All, LibrarySort.Alphabetical)
            setSort("profile-b", LibraryTab.Albums, LibrarySort.RecentlyAdded)
        }

        val reopened = SharedPrefsLibrarySortStore(context)

        assertEquals(LibrarySort.Creator, reopened.sortFor("profile-a", LibraryTab.Albums))
        assertEquals(LibrarySort.Alphabetical, reopened.sortFor("profile-a", LibraryTab.All))
        assertEquals(LibrarySort.RecentlyAdded, reopened.sortFor("profile-b", LibraryTab.Albums))
        assertNull(reopened.sortFor("profile-a", LibraryTab.Artists))
    }

    @Test
    fun should_readNoChoice_when_storedOrderIsUnknown() {
        context.getSharedPreferences("yoin_library_sort", Context.MODE_PRIVATE)
            .edit()
            .putString("profile-a/Playlists", "Custom")
            .commit()

        assertNull(SharedPrefsLibrarySortStore(context).sortFor("profile-a", LibraryTab.Playlists))
    }

    @Test
    fun should_collateWithIcu_when_orderingNames() {
        val names = listOf("Zedd", "Ólafur Arnalds", "adele", "Björk", "Oasis", "ABBA")

        val sorted = names.sortedWith(libraryNameOrder(Locale.ENGLISH))

        assertEquals(listOf("ABBA", "adele", "Björk", "Oasis", "Ólafur Arnalds", "Zedd"), sorted)
    }
}
