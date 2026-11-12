package com.gpo.yoin.ui.settings.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.local.AiProviderStore
import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.local.settingsFor
import com.gpo.yoin.data.remote.GeminiService
import com.gpo.yoin.data.remote.ai.AiApiFormat
import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.data.remote.ai.AiProviderPrefs
import com.gpo.yoin.data.remote.ai.AiSearchService
import com.gpo.yoin.data.remote.ai.AiSettings
import com.gpo.yoin.data.remote.ai.AiThinking
import com.gpo.yoin.data.remote.ai.AiTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings › AI: the service, its tier and key, Custom's endpoint, and the 「拿一首歌试试」 page. */
class AiSettingsViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val store: AiProviderStore = container.aiProviderStore
    private val geminiDao = container.database.geminiConfigDao()
    private val geminiService: GeminiService = container.geminiService

    /** The Gemini key as typed: shown at once, written to Room after a pause. */
    private val geminiKeyDraft = MutableStateFlow<String?>(null)
    private var geminiKeyJob: Job? = null

    private val models = MutableStateFlow(ModelList())
    private val _test = MutableStateFlow<TestRun>(TestRun.Idle)
    val test: StateFlow<TestRun> = _test.asStateFlow()

    val state: StateFlow<AiSettingsState> = combine(
        store.prefs,
        geminiDao.getConfig(),
        geminiKeyDraft,
        models,
    ) { prefs, config, draft, modelList ->
        AiSettingsState(
            prefs = prefs,
            geminiKey = draft ?: config?.apiKey.orEmpty(),
            targetLanguage = GeminiConfig.normalizeTargetLanguage(config?.targetLanguage),
            models = modelList,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AiSettingsState(store.prefs.value))

    fun setProvider(provider: AiProvider) = store.update { it.copy(provider = provider) }

    fun setTier(tier: AiTier) = store.update { it.copy(tier = tier) }

    /** The key for the service on screen. Gemini's lives in Room (cloud backup, as before v2). */
    fun setKey(provider: AiProvider, key: String) {
        val trimmed = key.trim()
        when (provider) {
            AiProvider.Gemini -> {
                geminiKeyDraft.value = key
                geminiKeyJob?.cancel()
                geminiKeyJob = viewModelScope.launch {
                    delay(KEY_SAVE_DELAY_MS)
                    val current = geminiDao.getConfig().first()
                    if (current?.apiKey != trimmed) {
                        geminiDao.upsert((current ?: GeminiConfig(apiKey = "")).copy(apiKey = trimmed))
                    }
                }
            }
            AiProvider.DeepSeek -> store.update { it.copy(deepSeekKey = trimmed) }
            AiProvider.Qwen -> store.update { it.copy(qwenKey = trimmed) }
            AiProvider.Custom -> store.update { it.copy(customKey = trimmed) }
        }
    }

    fun setCustomFormat(format: AiApiFormat) {
        store.update { it.copy(customFormat = format) }
        models.value = ModelList()
    }

    fun setCustomBaseUrl(url: String) = store.update { it.copy(customBaseUrl = url.trim()) }

    fun setCustomModel(model: String) = store.update { it.copy(customModel = model.trim()) }

    fun setCustomThinking(thinking: AiThinking) = store.update { it.copy(customThinking = thinking) }

    fun setSearchService(service: AiSearchService) = store.update { it.copy(searchService = service) }

    fun setSearchKey(key: String) = store.update { it.copy(searchKey = key.trim()) }

    fun setTargetLanguage(language: String) {
        val normalized = GeminiConfig.normalizeTargetLanguage(language)
        viewModelScope.launch {
            val existing = geminiDao.getConfig().first() ?: GeminiConfig(apiKey = "")
            if (GeminiConfig.normalizeTargetLanguage(existing.targetLanguage) == normalized) return@launch
            geminiDao.upsert(existing.copy(targetLanguage = normalized))
            // Answers written in the old language would linger otherwise (as before v2).
            container.database.songAboutEntryDao().deleteGenerated()
        }
    }

    /** 「拉取」: ask the Custom endpoint which models it has. */
    fun pullModels() {
        val prefs = store.prefs.value
        if (prefs.customBaseUrl.isBlank() || prefs.customKey.isBlank()) return
        models.value = ModelList(loading = true)
        viewModelScope.launch {
            models.value = try {
                val ids = geminiService.listCustomModels(AiSettings(prefs, geminiKey = ""))
                if (prefs.customModel.isBlank()) ids.firstOrNull()?.let(::setCustomModel)
                ModelList(ids = ids)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                ModelList(error = error.message ?: error.javaClass.simpleName)
            }
        }
    }

    /** The song playing now, to prefill the test page. */
    fun nowPlaying(): TestSong? = container.playbackManager.playbackState.value.currentTrack?.let { track ->
        TestSong(track.title.orEmpty(), track.artist.orEmpty(), track.album.orEmpty())
    }

    /** Write About for [song] with the settings on screen, without saving anything. */
    fun runTest(song: TestSong) {
        viewModelScope.launch {
            val config = geminiDao.getConfig().first()
            val ai = store.settingsFor(config.withDraft(geminiKeyDraft.value))
            if (!ai.isReady) {
                _test.value = TestRun.Failed(song, null, 0)
                return@launch
            }
            _test.value = TestRun.Running(song)
            val started = System.nanoTime()
            _test.value = try {
                val run = geminiService.runCanonicalAbout(
                    ai = ai,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    targetLanguage = GeminiConfig.normalizeTargetLanguage(config?.targetLanguage),
                )
                TestRun.Done(
                    song = song,
                    millis = (System.nanoTime() - started) / 1_000_000,
                    fields = run.values.associate { it.entryKey to it.answer },
                    queries = run.searchQueries,
                    pages = run.pagesRead,
                    model = ai.modelId,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                TestRun.Failed(song, error.message ?: error.javaClass.simpleName, (System.nanoTime() - started) / 1_000_000)
            }
        }
    }

    private fun GeminiConfig?.withDraft(draft: String?): GeminiConfig? =
        if (draft == null) this else (this ?: GeminiConfig(apiKey = "")).copy(apiKey = draft.trim())

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AiSettingsViewModel(container) as T
    }

    private companion object {
        const val KEY_SAVE_DELAY_MS = 600L
    }
}

data class AiSettingsState(
    val prefs: AiProviderPrefs,
    val geminiKey: String = "",
    val targetLanguage: String = GeminiConfig.DEFAULT_TARGET_LANGUAGE,
    val models: ModelList = ModelList(),
) {
    /** The key field's value for the service on screen. */
    val key: String
        get() = when (prefs.provider) {
            AiProvider.Gemini -> geminiKey
            AiProvider.DeepSeek -> prefs.deepSeekKey
            AiProvider.Qwen -> prefs.qwenKey
            AiProvider.Custom -> prefs.customKey
        }
}

data class ModelList(
    val ids: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class TestSong(val title: String, val artist: String, val album: String)

sealed interface TestRun {
    data object Idle : TestRun
    data class Running(val song: TestSong) : TestRun
    data class Done(
        val song: TestSong,
        val millis: Long,
        val fields: Map<String, String>,
        val queries: List<String>,
        val pages: Int,
        val model: String,
    ) : TestRun

    /** [message] null: the chosen service has no key yet. */
    data class Failed(val song: TestSong, val message: String?, val millis: Long) : TestRun
}
