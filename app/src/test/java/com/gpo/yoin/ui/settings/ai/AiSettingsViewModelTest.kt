package com.gpo.yoin.ui.settings.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.gpo.yoin.data.local.AiProviderStore
import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.local.GeminiConfigDao
import com.gpo.yoin.data.local.PlayHistory
import com.gpo.yoin.data.remote.GeminiService
import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.data.remote.ai.AiSearchService
import com.gpo.yoin.data.remote.ai.AiSettings
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.launchIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiSettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val dao = FakeGeminiConfigDao(GeminiConfig(apiKey = "old", targetLanguage = "Japanese"))
    private val store = AiProviderStore.InMemory()
    private val service = mockk<GeminiService>(relaxed = true)
    private val recent = MutableStateFlow<List<RecentTestSong>>(emptyList())

    /** Stands in for the page's Activity: clearing it is the page being closed. */
    private val page = ViewModelStore()

    private fun viewModel(): AiSettingsViewModel = ViewModelProvider(
        page,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AiSettingsViewModel(
                store = store,
                geminiDao = dao,
                geminiService = service,
                clearGeneratedAbout = {},
                recentSongs = recent,
                saveScope = CoroutineScope(mainDispatcherRule.dispatcher),
            ) as T
        },
    )[AiSettingsViewModel::class.java]

    @Test
    fun should_showTheKeyAtOnceAndWriteItAfterThePause_when_aGeminiKeyIsTyped() = runTest {
        val vm = viewModel()

        vm.setKey(AiProvider.Gemini, " new-key ")
        runCurrent()

        assertEquals(" new-key ", vm.state.value.key)
        assertEquals("old", dao.config.value?.apiKey)

        advanceTimeBy(601)
        runCurrent()

        assertEquals(GeminiConfig(apiKey = "new-key", targetLanguage = "Japanese"), dao.config.value)
    }

    @Test
    fun should_writeOnlyTheLastKey_when_typedAgainInsideThePause() = runTest {
        val vm = viewModel()

        vm.setKey(AiProvider.Gemini, "new")
        advanceTimeBy(300)
        vm.setKey(AiProvider.Gemini, "new-key")
        advanceTimeBy(400)
        runCurrent()

        assertEquals("old", dao.config.value?.apiKey)

        advanceTimeBy(201)
        runCurrent()

        assertEquals("new-key", dao.config.value?.apiKey)
        assertEquals(1, dao.writes)
    }

    @Test
    fun should_writeTheTypedKey_when_thePageClosesInsideThePause() = runTest {
        val vm = viewModel()
        vm.setKey(AiProvider.Gemini, " new-key ")
        runCurrent()

        page.clear()
        advanceUntilIdle()

        assertEquals(GeminiConfig(apiKey = "new-key", targetLanguage = "Japanese"), dao.config.value)
        assertEquals(1, dao.writes)
    }

    @Test
    fun should_writeNothingMore_when_thePageClosesAfterTheKeyWasSaved() = runTest {
        val vm = viewModel()
        vm.setKey(AiProvider.Gemini, "new-key")
        advanceTimeBy(601)
        runCurrent()
        assertEquals(1, dao.writes)

        page.clear()
        advanceUntilIdle()

        assertEquals(1, dao.writes)
    }

    @Test
    fun should_keepAnotherServicesKeyOutOfRoom_when_typed() = runTest {
        val vm = viewModel()

        vm.setKey(AiProvider.DeepSeek, " sk-1 ")
        advanceUntilIdle()

        assertEquals("sk-1", store.prefs.value.deepSeekKey)
        assertEquals(0, dao.writes)
    }

    @Test
    fun should_tryWithTheKeyJustTyped_when_roomDoesNotHaveItYet() = runTest {
        dao.config.value = null
        val used = slot<AiSettings>()
        coEvery { service.runCanonicalAbout(capture(used), any(), any(), any(), any()) } returns
            GeminiService.AboutRun(
                values = emptyList(),
                rawText = "",
                searchKind = AiSettings.SearchKind.BuiltIn,
                searchQueries = emptyList(),
                pagesRead = 0,
            )
        val vm = viewModel()
        val song = TestSong("Lemon", "Kenshi Yonezu", "STRAY SHEEP")

        vm.runTest(song)
        runCurrent()
        assertEquals(TestRun.Failed(song, message = null, millis = 0), vm.test.value)

        vm.setKey(AiProvider.Gemini, "typed-key")
        vm.runTest(song)
        runCurrent()

        assertTrue(vm.test.value is TestRun.Done)
        assertEquals("typed-key", used.captured.apiKey)
        assertNull(dao.config.value)
    }

    @Test
    fun should_sayNobodySearched_when_theTryRanWithoutSearch() = runTest {
        coEvery { service.runCanonicalAbout(any(), any(), any(), any(), any()) } returns
            GeminiService.AboutRun(
                values = emptyList(),
                rawText = "",
                searchKind = AiSettings.SearchKind.None,
                searchQueries = emptyList(),
                pagesRead = 0,
            )
        val vm = viewModel()

        vm.runTest(TestSong("Lemon", "Kenshi Yonezu", "STRAY SHEEP"))
        runCurrent()

        assertEquals(AiSettings.SearchKind.None, (vm.test.value as TestRun.Done).search)
    }

    @Test
    fun should_listTheModelsAndPickNone_when_fetched() = runTest {
        store.update {
            it.copy(provider = AiProvider.Custom, customBaseUrl = "https://x/v1", customKey = "sk")
        }
        coEvery { service.listCustomModels(any()) } returns listOf("ai21/jamba", "anthropic/claude", "openai/gpt")
        val vm = viewModel()

        vm.pullModels()
        advanceUntilIdle()

        assertEquals(listOf("ai21/jamba", "anthropic/claude", "openai/gpt"), vm.state.value.models.ids)
        assertEquals("", store.prefs.value.customModel)
        assertFalse(AiSettings(store.prefs.value, "").isReady)
    }

    @Test
    fun should_runOneTestAtATime_when_anotherSongIsTappedWhileOneRuns() = runTest {
        val answer = CompletableDeferred<GeminiService.AboutRun>()
        coEvery { service.runCanonicalAbout(any(), any(), any(), any(), any()) } coAnswers { answer.await() }
        val vm = viewModel()
        val first = TestSong("Lemon", "Kenshi Yonezu", "STRAY SHEEP")

        vm.runTest(first)
        runCurrent()
        vm.runTest(TestSong("Plastic Love", "Mariya Takeuchi", "Variety"))
        runCurrent()

        assertEquals(TestRun.Running(first), vm.test.value)
        answer.complete(
            GeminiService.AboutRun(
                values = emptyList(),
                rawText = "",
                searchKind = AiSettings.SearchKind.BuiltIn,
                searchQueries = emptyList(),
                pagesRead = 0,
            ),
        )
        runCurrent()
        assertEquals(first, (vm.test.value as TestRun.Done).song)
        coVerify(exactly = 1) { service.runCanonicalAbout(any(), any(), any(), any(), any()) }
    }

    @Test
    fun should_offerTheRecentSongs_when_thePageWatchesThem() = runTest {
        val vm = viewModel()
        assertNull(vm.recentSongs.value)
        vm.recentSongs.launchIn(backgroundScope)
        val songs = listOf(RecentTestSong("s1", TestSong("Lemon", "Kenshi Yonezu", "STRAY SHEEP"), null))

        recent.value = songs
        runCurrent()

        assertEquals(songs, vm.recentSongs.value)
    }

    @Test
    fun should_offerSixSongsNewestFirstEachOnce_from_playHistory() {
        val rows = listOf("a", "b", "a", "c", "d", "b", "e", "f", "g", "h").mapIndexed { i, id ->
            PlayHistory(
                songId = id,
                title = "Song $id",
                artist = "Artist $id",
                album = "Album $id",
                albumId = "",
                coverArtId = "cover-$id".takeIf { id != "c" },
                playedAt = 1_000L - i,
                durationMs = 0,
                completedPercent = 1f,
            )
        }

        val songs = recentTestSongs(rows) { key -> key?.let { "https://img/$it" } }

        assertEquals(listOf("a", "b", "c", "d", "e", "f"), songs.map { it.key })
        assertEquals(TestSong("Song a", "Artist a", "Album a"), songs.first().song)
        assertEquals("https://img/cover-a", songs.first().coverUrl)
        assertNull(songs[2].coverUrl)
    }

    @Test
    fun should_offerNoSongs_when_thereIsNoPlayHistory() {
        assertEquals(emptyList<RecentTestSong>(), recentTestSongs(emptyList()) { it })
    }

    @Test
    fun should_keepEachSearchServicesKey_when_anotherServicesKeyIsTyped() = runTest {
        val vm = viewModel()

        vm.setSearchKey(AiSearchService.DeepSeek, " ds-key ")
        vm.setSearchService(AiSearchService.Exa)
        vm.setSearchKey(AiSearchService.Exa, "exa-key")

        assertEquals("ds-key", store.prefs.value.searchKeyDeepSeek)
        assertEquals("exa-key", store.prefs.value.searchKeyExa)
        assertEquals("", store.prefs.value.searchKeyTavily)

        vm.setSearchService(AiSearchService.Doubao)
        vm.setSearchKey(AiSearchService.Doubao, " db-key ")
        vm.setSearchKey(AiSearchService.Brave, "br-key")

        assertEquals(AiSearchService.Doubao, store.prefs.value.searchService)
        assertEquals("db-key", store.prefs.value.searchKeyDoubao)
        assertEquals("br-key", store.prefs.value.searchKeyBrave)
        assertEquals("exa-key", store.prefs.value.searchKeyExa)
    }
}

private class FakeGeminiConfigDao(initial: GeminiConfig?) : GeminiConfigDao {
    val config = MutableStateFlow(initial)
    var writes = 0

    override fun getConfig(): Flow<GeminiConfig?> = config

    override suspend fun upsert(config: GeminiConfig) {
        writes++
        this.config.value = config
    }
}
