package com.gpo.yoin.data.repository

import com.gpo.yoin.data.album.AlbumAiCredits
import com.gpo.yoin.data.integration.neodb.NeoDBSyncService
import com.gpo.yoin.data.local.AlbumAboutEntry
import com.gpo.yoin.data.local.AlbumAboutEntryDao
import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.local.GeminiConfigDao
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.remote.GeminiService
import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.data.remote.ai.AiProviderPrefs
import com.gpo.yoin.data.remote.ai.AiSettings
import com.gpo.yoin.data.local.AiProviderStore
import com.gpo.yoin.data.source.MusicSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoinRepositoryAlbumAboutTest {
    private val database = mockk<YoinDatabase>(relaxed = true)
    private val dao = mockk<AlbumAboutEntryDao>(relaxed = true)
    private val geminiService = mockk<GeminiService>(relaxed = true)
    private val geminiConfigDao = mockk<GeminiConfigDao>(relaxed = true)

    private val repository = YoinRepository(
        activeSource = MutableStateFlow(mockk<MusicSource>(relaxed = true)),
        activeProfileId = MutableStateFlow("p1"),
        database = database,
        geminiService = geminiService,
        songAboutEntryDao = mockk(relaxed = true),
        geminiConfigDao = geminiConfigDao,
        lyricsCacheDao = mockk(relaxed = true),
        lyricsTranslationCacheDao = mockk(relaxed = true),
        songNoteDao = mockk(relaxed = true),
        albumNoteDao = mockk(relaxed = true),
        albumRatingDao = mockk(relaxed = true),
        memoryCopyCacheDao = mockk(relaxed = true),
        neoDbSyncService = mockk<NeoDBSyncService>(relaxed = true),
        clock = { 5_000L },
    )

    private val album = Album(
        id = MediaId.spotify("al-1"),
        name = "Blonde",
        artist = "Frank Ocean",
        artistId = null,
        coverArt = null,
        songCount = 17,
        durationSec = null,
        year = 2016,
        genre = null,
        recordLabels = listOf("Boys Don't Cry"),
    )

    private val ai = AiSettings.geminiOnly("key")

    init {
        every { database.albumAboutEntryDao() } returns dao
        every { geminiConfigDao.getConfig() } returns flowOf(GeminiConfig(apiKey = "key", targetLanguage = "Simplified Chinese"))
    }

    private fun canonical(key: String, answer: String) = AlbumAboutEntry(
        provider = "spotify",
        albumId = "al-1",
        albumDisplay = "Blonde",
        artistDisplay = "Frank Ocean",
        kind = AlbumAboutEntry.KIND_CANONICAL,
        entryKey = key,
        promptText = null,
        titleText = null,
        answerText = answer,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun should_writeCreditsAndParagraph_perAlbumIdentity_when_askedTheFirstTime() = runTest {
        coEvery { dao.getCanonical("spotify", "al-1") } returns emptyList()
        coEvery { geminiService.runAlbumAbout(ai, any(), "Simplified Chinese") } returns GeminiService.AlbumAboutRun(
            credits = AlbumAiCredits(producers = listOf("Frank Ocean")),
            blurb = "段落。",
            rawText = "",
            searchQueries = emptyList(),
        )
        val written = slot<List<AlbumAboutEntry>>()
        coEvery { dao.upsertAll(capture(written)) } returns Unit

        val result = repository.ensureAlbumAbout(album)

        assertEquals(YoinRepository.AboutLoadResult.Success, result)
        coVerify { geminiService.runAlbumAbout(ai, match { it.labels == listOf("Boys Don't Cry") && it.album == "Blonde" }, "Simplified Chinese") }
        val rows = written.captured.associateBy { it.entryKey }
        assertEquals(setOf("spotify"), written.captured.map { it.provider }.toSet())
        assertEquals(setOf("al-1"), written.captured.map { it.albumId }.toSet())
        assertEquals(listOf("Frank Ocean"), AlbumAiCredits.decode(rows.getValue(AlbumAboutEntry.CANON_CREDITS).answerText).producers)
        assertEquals("段落。", rows.getValue(AlbumAboutEntry.CANON_BLURB).answerText)
    }

    @Test
    fun should_notAskAgain_when_theAlbumsAboutIsCached() = runTest {
        coEvery { dao.getCanonical("spotify", "al-1") } returns listOf(canonical(AlbumAboutEntry.CANON_BLURB, ""))

        val result = repository.ensureAlbumAbout(album)

        assertEquals(YoinRepository.AboutLoadResult.Success, result)
        coVerify(exactly = 0) { geminiService.runAlbumAbout(any(), any(), any()) }
    }

    @Test
    fun should_reportTheMissingKey_when_noAiIsSetUp() = runTest {
        every { geminiConfigDao.getConfig() } returns flowOf(null)
        coEvery { dao.getCanonical(any(), any()) } returns emptyList()

        assertEquals(YoinRepository.AboutLoadResult.ApiKeyMissing, repository.ensureAlbumAbout(album))
    }

    @Test
    fun should_keepTheOldAbout_when_aRegenerateComesBackEmpty() = runTest {
        coEvery { dao.getCanonical("spotify", "al-1") } returns listOf(canonical(AlbumAboutEntry.CANON_BLURB, "Old."))
        coEvery { geminiService.runAlbumAbout(any(), any(), any()) } returns
            GeminiService.AlbumAboutRun(AlbumAiCredits.None, null, "", emptyList())

        val result = repository.regenerateAlbumAbout(album)

        assertEquals(YoinRepository.AlbumAboutRegenerateResult.NothingNew, result)
        coVerify(exactly = 0) { dao.upsertAll(any()) }
    }

    @Test
    fun should_returnWhatWasThere_when_aRegenerateWrites() = runTest {
        val before = listOf(canonical(AlbumAboutEntry.CANON_BLURB, "Old."))
        coEvery { dao.getCanonical("spotify", "al-1") } returns before
        coEvery { geminiService.runAlbumAbout(any(), any(), any()) } returns
            GeminiService.AlbumAboutRun(AlbumAiCredits.None, "New.", "", emptyList())

        val result = repository.regenerateAlbumAbout(album)

        assertEquals(YoinRepository.AlbumAboutRegenerateResult.Success(before), result)
        // The rows keep their first createdAt.
        coVerify { dao.upsertAll(match { rows -> rows.all { it.createdAt == 1L && it.updatedAt == 5_000L } }) }
    }

    @Test
    fun should_keepAnAlbumQuestion_keyedByTheNormalizedQuestion() = runTest {
        coEvery { geminiService.askAboutAlbum(ai, any(), "Who played  organ?", "Simplified Chinese") } returns
            GeminiService.AskAnswer("风琴", "答案。")
        val row = slot<AlbumAboutEntry>()
        coEvery { dao.upsert(capture(row)) } returns Unit

        val result = repository.askAboutAlbum(album, "  Who played  organ? ")

        assertTrue(result is YoinRepository.AskAboutResult.Success)
        assertEquals(AlbumAboutEntry.KIND_ASK, row.captured.kind)
        assertEquals("who played organ?", row.captured.entryKey)
        assertEquals("Who played  organ?", row.captured.promptText)
        assertEquals("风琴", row.captured.titleText)
    }

    @Test
    fun should_regenerateTheCreditsOnly_when_appleWroteTheNotes() = runTest {
        val apple = album.copy(id = MediaId(MediaId.PROVIDER_APPLE_MUSIC, "1440"), editorialNotes = "Apple's notes.")
        coEvery { dao.getCanonical(any(), any()) } returns emptyList()
        coEvery { geminiService.runAlbumAbout(ai, any(), any()) } returns
            GeminiService.AlbumAboutRun(AlbumAiCredits(producers = listOf("P")), null, "", emptyList())
        val written = slot<List<AlbumAboutEntry>>()
        coEvery { dao.upsertAll(capture(written)) } returns Unit

        val result = repository.regenerateAlbumAbout(apple)

        assertTrue(result is YoinRepository.AlbumAboutRegenerateResult.Success)
        coVerify { geminiService.runAlbumAbout(ai, match { it.serviceNotes == "Apple's notes." }, any()) }
        // Apple's text is never copied into the table (nor synced): the paragraph row stays empty.
        assertEquals("", written.captured.single { it.entryKey == AlbumAboutEntry.CANON_BLURB }.answerText)
    }

    @Test
    fun should_nameTheChosenService_andGateOnItsOwnKey_notGeminis() = runTest {
        val store = AiProviderStore.InMemory(AiProviderPrefs(provider = AiProvider.DeepSeek))
        val withStore = YoinRepository(
            activeSource = MutableStateFlow(mockk<MusicSource>(relaxed = true)),
            activeProfileId = MutableStateFlow("p1"),
            database = database,
            geminiService = geminiService,
            songAboutEntryDao = mockk(relaxed = true),
            geminiConfigDao = geminiConfigDao,
            lyricsCacheDao = mockk(relaxed = true),
            lyricsTranslationCacheDao = mockk(relaxed = true),
            songNoteDao = mockk(relaxed = true),
            albumNoteDao = mockk(relaxed = true),
            albumRatingDao = mockk(relaxed = true),
            memoryCopyCacheDao = mockk(relaxed = true),
            neoDbSyncService = mockk<NeoDBSyncService>(relaxed = true),
            aiProviderStore = store,
        )
        coEvery { dao.getCanonical(any(), any()) } returns emptyList()

        // A Gemini key is set (init), but DeepSeek is chosen and has none: nothing is ready.
        assertEquals(AiProvider.DeepSeek, withStore.activeAiProvider())
        assertFalse(withStore.isAiReady())
        assertEquals(YoinRepository.AboutLoadResult.ApiKeyMissing, withStore.ensureAlbumAbout(album))

        store.update { it.copy(deepSeekKey = "ds-key") }
        assertTrue(withStore.isAiReady())
    }

    @Test
    fun should_storeNothing_when_theCallFails_soTheNextVisitAsksAgain() = runTest {
        coEvery { dao.getCanonical("spotify", "al-1") } returns emptyList()
        coEvery { geminiService.runAlbumAbout(any(), any(), any()) } throws
            com.gpo.yoin.data.remote.GeminiException("No album About in AI response")

        val result = repository.ensureAlbumAbout(album)

        assertTrue(result is YoinRepository.AboutLoadResult.Error)
        coVerify(exactly = 0) { dao.upsertAll(any()) }
    }

    @Test
    fun should_storeAnEmptyAnswer_asDone() = runTest {
        coEvery { dao.getCanonical("spotify", "al-1") } returns emptyList()
        coEvery { geminiService.runAlbumAbout(any(), any(), any()) } returns
            GeminiService.AlbumAboutRun(AlbumAiCredits.None, null, "[CREDITS]{}[/CREDITS][ABOUT]N/A[/ABOUT]", emptyList())

        assertEquals(YoinRepository.AboutLoadResult.Success, repository.ensureAlbumAbout(album))
        coVerify(exactly = 1) { dao.upsertAll(any()) }
    }
}
