package com.gpo.yoin.data.repository

import com.gpo.yoin.data.local.SongNoteDao
import com.gpo.yoin.data.local.SongNoteKey
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.source.MusicSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The note marks every track list asks for (playlist, album, Songs): a list
 * has no ceiling, and SQLite before 3.32 (Android 11 and older) binds at most
 * 999 values to one query, so the repository asks a few hundred ids at a time.
 */
class YoinRepositoryNoteKeysTest {
    private val songNoteDao = mockk<SongNoteDao>()
    private val asked = mutableListOf<Pair<String, List<String>>>()

    private val repository = YoinRepository(
        activeSource = MutableStateFlow(mockk<MusicSource>(relaxed = true)),
        activeProfileId = MutableStateFlow(PROFILE),
        database = mockk(relaxed = true),
        geminiService = mockk(relaxed = true),
        songAboutEntryDao = mockk(relaxed = true),
        geminiConfigDao = mockk(relaxed = true),
        lyricsCacheDao = mockk(relaxed = true),
        lyricsTranslationCacheDao = mockk(relaxed = true),
        songNoteDao = songNoteDao,
        albumNoteDao = mockk(relaxed = true),
        albumRatingDao = mockk(relaxed = true),
        memoryCopyCacheDao = mockk(relaxed = true),
        neoDbSyncService = mockk(relaxed = true),
        clock = { 1_000L }
    )

    init {
        every { songNoteDao.observeKeys(any(), any(), any()) } answers {
            val ids = firstArg<List<String>>()
            val provider = secondArg<String>()
            asked += provider to ids
            flowOf(ids.filter { it in NOTED }.map { SongNoteKey(PROFILE, it, provider) })
        }
    }

    @Test
    fun should_askAtMostFiveHundredIdsPerQuery_when_aListHoldsTwelveHundredTracks() = runTest {
        val ids = (1..1200).map { MediaId.subsonic("t$it") }

        val noted = repository.observeTracksWithNotes(ids).first()

        assertTrue(asked.all { (_, chunk) -> chunk.size <= 500 })
        assertEquals(ids.map(MediaId::rawId), asked.flatMap { (_, chunk) -> chunk })
        assertEquals(NOTED.map(MediaId::subsonic).toSet(), noted)
    }

    @Test
    fun should_keepEachProvidersIdsApart_when_batchingAMixedList() = runTest {
        val subsonic = (1..700).map { MediaId.subsonic("t$it") }
        val spotify = (1..600).map { MediaId.spotify("t$it") }

        val noted = repository.observeTracksWithNotes(subsonic + spotify).first()

        assertTrue(asked.all { (_, chunk) -> chunk.size <= 500 })
        assertEquals(
            subsonic.map(MediaId::rawId),
            asked.filter { it.first == MediaId.PROVIDER_SUBSONIC }.flatMap { it.second }
        )
        assertEquals(
            spotify.map(MediaId::rawId),
            asked.filter { it.first == MediaId.PROVIDER_SPOTIFY }.flatMap { it.second }
        )
        assertEquals(
            setOf(
                MediaId.subsonic("t1"),
                MediaId.subsonic("t500"),
                MediaId.subsonic("t501"),
                MediaId.subsonic("t700"),
                MediaId.spotify("t1"),
                MediaId.spotify("t500"),
                MediaId.spotify("t501")
            ),
            noted
        )
    }

    private companion object {
        const val PROFILE = "profile"

        /** Notes on both sides of each batch edge, and on the very last id. */
        val NOTED = setOf("t1", "t500", "t501", "t700", "t1001", "t1200")
    }
}
