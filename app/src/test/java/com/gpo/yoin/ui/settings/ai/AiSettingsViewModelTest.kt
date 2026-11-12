package com.gpo.yoin.ui.settings.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.gpo.yoin.data.local.AiProviderStore
import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.local.GeminiConfigDao
import com.gpo.yoin.data.remote.GeminiService
import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.data.remote.ai.AiSettings
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
                nowPlayingSong = { null },
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
            GeminiService.AboutRun(values = emptyList(), rawText = "", searchQueries = emptyList(), pagesRead = 0)
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
