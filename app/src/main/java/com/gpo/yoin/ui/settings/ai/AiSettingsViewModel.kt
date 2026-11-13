package com.gpo.yoin.ui.settings.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.local.AiProviderStore
import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.local.GeminiConfigDao
import com.gpo.yoin.data.local.PlayHistory
import com.gpo.yoin.data.local.settingsFor
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.remote.GeminiService
import com.gpo.yoin.data.remote.ai.AiApiFormat
import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.data.remote.ai.AiProviderPrefs
import com.gpo.yoin.data.remote.ai.AiSearchService
import com.gpo.yoin.data.remote.ai.AiSettings
import com.gpo.yoin.data.remote.ai.AiThinking
import com.gpo.yoin.data.remote.ai.AiTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the AI page's controls do: the ViewModel in the app, local state in
 * the Tips demo and the previews.
 */
interface AiSettingsActions {
    fun setProvider(provider: AiProvider)

    fun setTier(tier: AiTier)

    /** The key for [provider], as typed. */
    fun setKey(provider: AiProvider, key: String)

    fun setCustomFormat(format: AiApiFormat)

    fun setCustomBaseUrl(url: String)

    fun setCustomModel(model: String)

    fun setCustomThinking(thinking: AiThinking)

    fun setSearchService(service: AiSearchService)

    /** The key for search [service], as typed. Each service keeps its own. */
    fun setSearchKey(service: AiSearchService, key: String)

    fun setTargetLanguage(language: String)

    /** 「拉取」: ask the Custom endpoint which models it has. */
    fun pullModels()
}

/**
 * Settings › AI: the service, its tier and key, Custom's endpoint, and the 「拿一首歌试试」 page.
 *
 * The Gemini key is written to Room a pause after the last keystroke, on
 * `viewModelScope`. Leaving the page inside that pause cancels the write, so
 * [onCleared] hands the key to [saveScope], which outlives this ViewModel.
 */
class AiSettingsViewModel(
    private val store: AiProviderStore,
    private val geminiDao: GeminiConfigDao,
    private val geminiService: GeminiService,
    /** Drops the answers the AI wrote: they are in the old language after a language switch. */
    private val clearGeneratedAbout: suspend () -> Unit,
    /**
     * The active account's songs for 「拿一首歌试试」, newest first (see [recentTestSongs]). Empty
     * with no play history.
     */
    recentSongs: Flow<List<RecentTestSong>>,
    private val saveScope: CoroutineScope = KeySaveScope,
) : ViewModel(), AiSettingsActions {

    /** The Gemini key as typed: shown at once, written to Room after a pause. */
    private val geminiKeyDraft = MutableStateFlow<String?>(null)
    private var geminiKeyJob: Job? = null

    /** The typed Gemini key Room doesn't have yet; null once it is written. Main thread only. */
    private var unsavedGeminiKey: String? = null

    private val models = MutableStateFlow(ModelList())
    private val _test = MutableStateFlow<TestRun>(TestRun.Idle)
    val test: StateFlow<TestRun> = _test.asStateFlow()
    private var testJob: Job? = null

    /** The songs the test page offers; null until the history has been read. */
    val recentSongs: StateFlow<List<RecentTestSong>?> =
        recentSongs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    override fun setProvider(provider: AiProvider) = store.update { it.copy(provider = provider) }

    override fun setTier(tier: AiTier) = store.update { it.copy(tier = tier) }

    /** The key for the service on screen. Gemini's lives in Room (cloud backup, as before v2). */
    override fun setKey(provider: AiProvider, key: String) {
        val trimmed = key.trim()
        when (provider) {
            AiProvider.Gemini -> {
                geminiKeyDraft.value = key
                unsavedGeminiKey = trimmed
                geminiKeyJob?.cancel()
                geminiKeyJob = viewModelScope.launch {
                    delay(KEY_SAVE_DELAY_MS)
                    saveGeminiKey(trimmed)
                    if (unsavedGeminiKey == trimmed) unsavedGeminiKey = null
                }
            }
            AiProvider.DeepSeek -> store.update { it.copy(deepSeekKey = trimmed) }
            AiProvider.Qwen -> store.update { it.copy(qwenKey = trimmed) }
            AiProvider.Custom -> store.update { it.copy(customKey = trimmed) }
        }
    }

    private suspend fun saveGeminiKey(key: String) {
        val current = geminiDao.getConfig().first()
        if (current?.apiKey != key) {
            geminiDao.upsert((current ?: GeminiConfig(apiKey = "")).copy(apiKey = key))
        }
    }

    /**
     * Leaving the page inside the save delay must not drop the key just typed.
     * `viewModelScope` is cancelled before this runs, the pending save with
     * it, so the job can't be asked whether it was still waiting: the key not
     * yet written is kept in [unsavedGeminiKey] and written from [saveScope].
     */
    override fun onCleared() {
        val key = unsavedGeminiKey ?: return
        unsavedGeminiKey = null
        saveScope.launch { saveGeminiKey(key) }
    }

    override fun setCustomFormat(format: AiApiFormat) {
        store.update { it.copy(customFormat = format) }
        models.value = ModelList()
    }

    override fun setCustomBaseUrl(url: String) = store.update { it.copy(customBaseUrl = url.trim()) }

    override fun setCustomModel(model: String) = store.update { it.copy(customModel = model.trim()) }

    override fun setCustomThinking(thinking: AiThinking) = store.update { it.copy(customThinking = thinking) }

    override fun setSearchService(service: AiSearchService) = store.update { it.copy(searchService = service) }

    override fun setSearchKey(service: AiSearchService, key: String) =
        store.update { it.withSearchKey(service, key.trim()) }

    override fun setTargetLanguage(language: String) {
        val normalized = GeminiConfig.normalizeTargetLanguage(language)
        viewModelScope.launch {
            val existing = geminiDao.getConfig().first() ?: GeminiConfig(apiKey = "")
            if (GeminiConfig.normalizeTargetLanguage(existing.targetLanguage) == normalized) return@launch
            geminiDao.upsert(existing.copy(targetLanguage = normalized))
            // Answers written in the old language would linger otherwise (as before v2).
            clearGeneratedAbout()
        }
    }

    override fun pullModels() {
        val prefs = store.prefs.value
        if (prefs.customBaseUrl.isBlank() || prefs.customKey.isBlank()) return
        models.value = ModelList(loading = true)
        viewModelScope.launch {
            models.value = try {
                // Only lists them. The model is the user's to pick: the first id of an
                // aggregator's hundreds is nobody's choice, and it would start billing the key.
                ModelList(ids = geminiService.listCustomModels(AiSettings(prefs, geminiKey = "")))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                ModelList(error = error.message ?: error.javaClass.simpleName)
            }
        }
    }

    /**
     * Write About for [song] with the settings on screen, without saving anything. One run at a
     * time: a tap while one is running does nothing.
     */
    fun runTest(song: TestSong) {
        if (testJob?.isActive == true) return
        testJob = viewModelScope.launch {
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
                    search = run.searchKind,
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
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AiSettingsViewModel(
            store = container.aiProviderStore,
            geminiDao = container.database.geminiConfigDao(),
            geminiService = container.geminiService,
            clearGeneratedAbout = { container.database.songAboutEntryDao().deleteGenerated() },
            // The repository scopes the history to the active profile and its provider.
            recentSongs = container.repository.getRecentHistory(RECENT_HISTORY_ROWS).map { rows ->
                recentTestSongs(rows) { key -> container.repository.resolveCoverUrl(CoverRef.fromStorageKey(key)) }
            },
        ) as T
    }

    private companion object {
        const val KEY_SAVE_DELAY_MS = 600L

        /** History rows read for the test page: enough for six songs after a few on repeat. */
        const val RECENT_HISTORY_ROWS = 200

        /** Process-lifetime scope for the key a closing page hands over (see the class KDoc). */
        val KeySaveScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    }
}

data class AiSettingsState(
    val prefs: AiProviderPrefs,
    val geminiKey: String = "",
    val targetLanguage: String = GeminiConfig.DEFAULT_TARGET_LANGUAGE,
    val models: ModelList = ModelList(),
) {
    /** The key field's value for the service on screen. */
    val key: String get() = keyFor(prefs.provider)

    fun keyFor(provider: AiProvider): String = when (provider) {
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

/** One song the test page offers: [key] is its song id, [coverUrl] its cover when it has one. */
data class RecentTestSong(val key: String, val song: TestSong, val coverUrl: String?)

/** How many recently played songs 「拿一首歌试试」 offers (owner 2026-11-14). */
const val RECENT_TEST_SONGS = 6

/**
 * The test page's songs from play history [rows] (newest first): each song once, at its newest
 * play, at most [RECENT_TEST_SONGS]. [coverUrl] turns a row's stored cover key into a URL.
 */
internal fun recentTestSongs(rows: List<PlayHistory>, coverUrl: (String?) -> String?): List<RecentTestSong> =
    rows.asSequence()
        .filter { it.title.isNotBlank() }
        .distinctBy { it.songId }
        .take(RECENT_TEST_SONGS)
        .map { row ->
            RecentTestSong(
                key = row.songId,
                song = TestSong(row.title, row.artist, row.album),
                coverUrl = coverUrl(row.coverArtId),
            )
        }
        .toList()

sealed interface TestRun {
    data object Idle : TestRun
    data class Running(val song: TestSong) : TestRun
    data class Done(
        val song: TestSong,
        val millis: Long,
        val fields: Map<String, String>,
        /** Who searched: the model, Yoin ([queries], [pages]), or nobody. */
        val search: AiSettings.SearchKind,
        val queries: List<String>,
        val pages: Int,
        val model: String,
    ) : TestRun

    /** [message] null: the chosen service has no key yet. */
    data class Failed(val song: TestSong, val message: String?, val millis: Long) : TestRun
}
