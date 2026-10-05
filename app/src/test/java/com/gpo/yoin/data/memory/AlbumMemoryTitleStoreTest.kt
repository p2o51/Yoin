package com.gpo.yoin.data.memory

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.gpo.yoin.data.local.AlbumMemoryTitleDao
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.MediaId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The user's album Memory titles over a real Room table: the sync contract and profile scoping. */
@RunWith(RobolectricTestRunner::class)
class AlbumMemoryTitleStoreTest {

    private lateinit var database: YoinDatabase
    private lateinit var dao: AlbumMemoryTitleDao
    private val profile = MutableStateFlow<String?>("profile-a")
    private var now = 1_000L
    private lateinit var store: AlbumMemoryTitleStore

    private val album = MediaId.subsonic("album-1")

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), YoinDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.albumMemoryTitleDao()
        store = AlbumMemoryTitleStore(dao, profile, clock = { now })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun should_store_trimmed_title_with_edit_time_when_set() = runTest {
        now = 5_000L
        store.setTitle(album, "  Three summers \n")

        val row = dao.get("profile-a", MediaId.PROVIDER_SUBSONIC, "album-1")
        assertEquals("Three summers", row?.title)
        assertEquals(5_000L, row?.updatedAt)
        assertEquals("Three summers", store.getTitle(album))
    }

    @Test
    fun should_hard_delete_row_when_title_cleared() = runTest {
        store.setTitle(album, "Three summers")

        store.clearTitle(album)

        // restore = the row is gone (never a null title the sync would have to carry)
        assertNull(dao.get("profile-a", MediaId.PROVIDER_SUBSONIC, "album-1"))
        assertTrue(dao.getAllForProfile("profile-a").isEmpty())
        assertNull(store.getTitle(album))
    }

    @Test
    fun should_delete_row_when_blank_title_set() = runTest {
        store.setTitle(album, "Three summers")

        store.setTitle(album, "   ")

        assertTrue(dao.getAllForProfile("profile-a").isEmpty())
    }

    @Test
    fun should_keep_edit_time_when_same_title_saved_again() = runTest {
        now = 1_000L
        store.setTitle(album, "Three summers")
        now = 9_000L

        store.setTitle(album, " Three summers ")

        assertEquals(1_000L, dao.get("profile-a", MediaId.PROVIDER_SUBSONIC, "album-1")?.updatedAt)

        store.setTitle(album, "Four summers")
        val row = dao.get("profile-a", MediaId.PROVIDER_SUBSONIC, "album-1")
        assertEquals("Four summers", row?.title)
        assertEquals(9_000L, row?.updatedAt)
    }

    @Test
    fun should_scope_titles_by_profile_and_provider_when_reading() = runTest {
        store.setTitle(album, "Profile A")
        store.setTitle(MediaId.spotify("album-1"), "Spotify twin")
        profile.value = "profile-b"

        assertNull(store.getTitle(album))
        store.setTitle(album, "Profile B")

        assertEquals("Profile B", store.getTitle(album))
        profile.value = "profile-a"
        assertEquals("Profile A", store.getTitle(album))
        assertEquals("Spotify twin", store.getTitle(MediaId.spotify("album-1")))
        // the legacy "provider:" prefix on a stored id keys the same row
        assertEquals("Profile A", store.getTitle(MediaId.subsonic("subsonic:album-1")))
    }

    @Test
    fun should_write_nothing_when_no_profile_is_active() = runTest {
        profile.value = null

        store.setTitle(album, "Orphan")

        assertNull(store.getTitle(album))
        assertTrue(dao.getAllForProfile("profile-a").isEmpty())
    }

    @Test
    fun should_emit_title_changes_when_observing_one_album() = runTest {
        store.observeTitle(album).test {
            assertNull(awaitItem())
            store.setTitle(album, "Three summers")
            assertEquals("Three summers", awaitItem())
            store.clearTitle(album)
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun should_follow_active_profile_when_observing_all_titles() = runTest {
        store.setTitle(album, "Profile A")
        store.observeTitles().test {
            assertEquals(mapOf(album to "Profile A"), awaitItem())
            profile.value = "profile-b"
            assertEquals(emptyMap<MediaId, String>(), awaitItem())
            store.setTitle(album, "Profile B")
            assertEquals(mapOf(album to "Profile B"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun should_resolve_user_then_ai_then_motif_then_album_name() {
        val user = resolveAlbumMemoryTitle("Mine", "AI line", "Motif line", "Album")
        assertEquals(ResolvedTitle("Mine", AlbumMemoryTitleSource.USER, canRestoreGenerated = true), user)

        val ai = resolveAlbumMemoryTitle(null, "AI line", "Motif line", "Album")
        assertEquals(ResolvedTitle("AI line", AlbumMemoryTitleSource.AI), ai)

        val motif = resolveAlbumMemoryTitle("  ", " ", "Motif line", "Album")
        assertEquals(ResolvedTitle("Motif line", AlbumMemoryTitleSource.MOTIF), motif)

        val album = resolveAlbumMemoryTitle(null, null, null, "Album")
        assertEquals(ResolvedTitle("Album", AlbumMemoryTitleSource.ALBUM), album)
    }

    @Test
    fun should_offer_restore_only_when_user_title_covers_yoin_title() {
        assertTrue(resolveAlbumMemoryTitle("Mine", null, "Motif line", "Album").canRestoreGenerated)
        // only the album name under it: restoring is just clearing the field
        assertFalse(resolveAlbumMemoryTitle("Mine", null, null, "Album").canRestoreGenerated)
        assertFalse(resolveAlbumMemoryTitle(null, "AI line", null, "Album").canRestoreGenerated)
        assertEquals("Mine", resolveAlbumMemoryTitle("  Mine  ", null, null, "Album").text)
    }
}
