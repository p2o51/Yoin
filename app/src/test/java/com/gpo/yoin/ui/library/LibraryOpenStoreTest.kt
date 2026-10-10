package com.gpo.yoin.ui.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.LibraryRecents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What Library opened persists per profile and service, by Library's own ids, the newest few hundred. */
@RunWith(RobolectricTestRunner::class)
class LibraryOpenStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clear() {
        context.getSharedPreferences("yoin_library_opens", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun store() = SharedPrefsLibraryOpenStore(context, io = Dispatchers.Unconfined)

    @Test
    fun should_restoreOpensPerProfileAndService_when_storeIsReopened() = runTest {
        store().apply {
            recordOpened("profile-a", MediaId.PROVIDER_APPLE_MUSIC, LibraryOpenKind.Album, "library:l.1", at = 100L)
            recordOpened("profile-a", MediaId.PROVIDER_APPLE_MUSIC, LibraryOpenKind.Playlist, "library:p.1", 200L)
            recordOpened("profile-a", MediaId.PROVIDER_APPLE_MUSIC, LibraryOpenKind.Artist, "library:r.1", 300L)
            // Opened again: the later time.
            recordOpened("profile-a", MediaId.PROVIDER_APPLE_MUSIC, LibraryOpenKind.Album, "library:l.1", 400L)
            recordOpened("profile-b", MediaId.PROVIDER_APPLE_MUSIC, LibraryOpenKind.Album, "library:l.2", 500L)
            recordOpened("profile-a", MediaId.PROVIDER_SUBSONIC, LibraryOpenKind.Album, "al", 600L)
        }

        val reopened = store()

        assertEquals(
            LibraryRecents(
                albums = mapOf("library:l.1" to 400L),
                artists = mapOf("library:r.1" to 300L),
                playlists = mapOf("library:p.1" to 200L)
            ),
            reopened.observe("profile-a", MediaId.PROVIDER_APPLE_MUSIC).first()
        )
        assertEquals(
            LibraryRecents(albums = mapOf("library:l.2" to 500L)),
            reopened.observe("profile-b", MediaId.PROVIDER_APPLE_MUSIC).first()
        )
        assertEquals(
            LibraryRecents(albums = mapOf("al" to 600L)),
            reopened.observe("profile-a", MediaId.PROVIDER_SUBSONIC).first()
        )
    }

    @Test
    fun should_keepOnlyTheNewestOpens_when_overTheCap() = runTest {
        val store = store()
        val cap = LibraryOpenStore.MAX_PER_PROFILE
        repeat(cap + 2) { index ->
            val at = index.toLong()
            store.recordOpened("profile-a", MediaId.PROVIDER_SUBSONIC, LibraryOpenKind.Album, "al$index", at)
        }
        store.recordOpened("profile-b", MediaId.PROVIDER_SUBSONIC, LibraryOpenKind.Album, "other", 0L)

        val albums = store.observe("profile-a", MediaId.PROVIDER_SUBSONIC).first().albums

        assertEquals(cap, albums.size)
        assertEquals(setOf("al0", "al1"), (0 until cap + 2).map { "al$it" }.toSet() - albums.keys)
        // Another profile's opens don't count against this one's.
        assertEquals(
            mapOf("other" to 0L),
            store.observe("profile-b", MediaId.PROVIDER_SUBSONIC).first().albums
        )
    }

    @Test
    fun should_keepTheWholeRawId_when_itHoldsSlashes() = runTest {
        val store = store()
        store.recordOpened("profile-a", MediaId.PROVIDER_SUBSONIC, LibraryOpenKind.Playlist, "a/b/c", 7L)

        assertEquals(
            mapOf("a/b/c" to 7L),
            store.observe("profile-a", MediaId.PROVIDER_SUBSONIC).first().playlists
        )
    }
}
