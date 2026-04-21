package com.gpo.yoin.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.testutil.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SongNoteDaoTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: YoinDatabase
    private lateinit var dao: SongNoteDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            YoinDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = database.songNoteDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun inserted_notes_are_observed_in_reverse_chronological_order() = runTest {
        dao.insert(note(id = "n1", createdAt = 100L, content = "earliest"))
        dao.insert(note(id = "n2", createdAt = 300L, content = "latest"))
        dao.insert(note(id = "n3", createdAt = 200L, content = "middle"))

        val observed = dao.observeForTrack("track-1", MediaId.PROVIDER_SUBSONIC).first()

        assertEquals(listOf("latest", "middle", "earliest"), observed.map(SongNote::content))
    }

    @Test
    fun update_rewrites_content_for_existing_id() = runTest {
        dao.insert(note(id = "n1", content = "draft", createdAt = 100L))

        val stored = dao.observeForTrack("track-1", MediaId.PROVIDER_SUBSONIC).first().first()
        dao.update(stored.copy(content = "revised", updatedAt = 200L))

        val after = dao.observeForTrack("track-1", MediaId.PROVIDER_SUBSONIC).first().first()
        assertEquals("revised", after.content)
        assertEquals(200L, after.updatedAt)
    }

    @Test
    fun cross_provider_query_excludes_current_track_and_returns_all_matches() = runTest {
        dao.insert(
            note(
                id = "sub-a",
                trackId = "sub-track",
                provider = MediaId.PROVIDER_SUBSONIC,
                content = "sub note A",
                createdAt = 100L,
                updatedAt = 100L,
            ),
        )
        dao.insert(
            note(
                id = "sp-a",
                trackId = "spotify-track",
                provider = MediaId.PROVIDER_SPOTIFY,
                content = "spotify note A",
                createdAt = 110L,
                updatedAt = 110L,
            ),
        )
        dao.insert(
            note(
                id = "sp-b",
                trackId = "spotify-track",
                provider = MediaId.PROVIDER_SPOTIFY,
                content = "spotify note B",
                createdAt = 120L,
                updatedAt = 300L,
            ),
        )

        val crossProvider = dao.observeCrossProvider(
            title = "Same Song",
            artist = "Same Artist",
            trackId = "sub-track",
            provider = MediaId.PROVIDER_SUBSONIC,
        ).first()

        assertEquals(
            listOf("spotify note B", "spotify note A"),
            crossProvider.map(SongNote::content),
        )
    }

    @Test
    fun observe_keys_returns_distinct_pairs_even_with_many_notes() = runTest {
        dao.insert(note(id = "n1", trackId = "t1", createdAt = 100L))
        dao.insert(note(id = "n2", trackId = "t1", createdAt = 200L))
        dao.insert(note(id = "n3", trackId = "t2", createdAt = 100L))

        val keys = dao.observeKeys(
            trackIds = listOf("t1", "t2", "t3"),
            provider = MediaId.PROVIDER_SUBSONIC,
        ).first()

        assertEquals(2, keys.size)
        assertTrue(keys.any { it.trackId == "t1" })
        assertTrue(keys.any { it.trackId == "t2" })
    }

    @Test
    fun delete_by_id_removes_only_target_note() = runTest {
        dao.insert(note(id = "n1", content = "keep", createdAt = 100L))
        dao.insert(note(id = "n2", content = "remove", createdAt = 200L))

        dao.deleteById("n2")

        val remaining = dao.observeForTrack("track-1", MediaId.PROVIDER_SUBSONIC).first()
        assertEquals(listOf("keep"), remaining.map(SongNote::content))
    }

    private fun note(
        id: String,
        trackId: String = "track-1",
        provider: String = MediaId.PROVIDER_SUBSONIC,
        content: String = "note",
        createdAt: Long = 100L,
        updatedAt: Long = createdAt,
        title: String = "Same Song",
        artist: String = "Same Artist",
    ) = SongNote(
        id = id,
        trackId = trackId,
        provider = provider,
        content = content,
        createdAt = createdAt,
        updatedAt = updatedAt,
        title = title,
        artist = artist,
    )
}
