package com.gpo.yoin.data.remote.ai

/**
 * Which online AI writes the About fields, answers Ask and translates lyrics
 * (owner 2026-11-12, AI settings v2). The user only picks a service, one of
 * its tiers where it has two, and pastes a key; Custom adds an endpoint, a
 * model, a thinking level and a search service.
 *
 * Search is two kinds: the service's own (Gemini's Google Search, Qwen's
 * enable_search), or Yoin calling a search API first and putting the pages in
 * the prompt (DeepSeek, Custom). The second kind works with any model.
 */
enum class AiProvider {
    Gemini,
    DeepSeek,
    Qwen,
    Custom,
    ;

    /**
     * The tiers this service offers. Qwen has one (owner 2026-11-13): on the 500-song benchmark
     * qwen-flash was more accurate, faster and cheaper than qwen-plus and qwen3.8-flash, so there
     * was nothing for a second tier to be. Custom has none: its model is typed.
     */
    val tiers: List<AiTier>
        get() = when (this) {
            Gemini, DeepSeek -> AiTier.entries
            Qwen -> listOf(AiTier.Good)
            Custom -> emptyList()
        }
}

/** 「好」/「最佳」. What each means per service is in [AiSettings.modelId] / [AiSettings.searchQueries]. */
enum class AiTier { Good, Best }

enum class AiApiFormat { OpenAi, Anthropic }

enum class AiThinking { Off, Low, Medium, High }

enum class AiSearchService { DeepSeek, Exa, Tavily, None }

/** What the user set, minus the Gemini key (that one still lives in `gemini_config`). */
data class AiProviderPrefs(
    val provider: AiProvider = AiProvider.Gemini,
    val tier: AiTier = AiTier.Good,
    val deepSeekKey: String = "",
    val qwenKey: String = "",
    val customFormat: AiApiFormat = AiApiFormat.OpenAi,
    val customBaseUrl: String = "",
    val customKey: String = "",
    val customModel: String = "",
    val customThinking: AiThinking = AiThinking.Off,
    val searchService: AiSearchService = AiSearchService.DeepSeek,
    // One key per search service: a key typed for one is never sent to another.
    val searchKeyDeepSeek: String = "",
    val searchKeyExa: String = "",
    val searchKeyTavily: String = "",
) {
    /**
     * The tier in use: [tier] where [provider] offers it, otherwise the one it has. [tier] itself
     * is kept as chosen, so going to Qwen and back to Gemini does not lose 「最佳」.
     */
    val activeTier: AiTier
        get() = if (tier in provider.tiers) tier else provider.tiers.firstOrNull() ?: tier

    /** The key Custom's search [service] was given. */
    fun searchKeyFor(service: AiSearchService): String = when (service) {
        AiSearchService.DeepSeek -> searchKeyDeepSeek
        AiSearchService.Exa -> searchKeyExa
        AiSearchService.Tavily -> searchKeyTavily
        AiSearchService.None -> ""
    }

    fun withSearchKey(service: AiSearchService, key: String): AiProviderPrefs = when (service) {
        AiSearchService.DeepSeek -> copy(searchKeyDeepSeek = key)
        AiSearchService.Exa -> copy(searchKeyExa = key)
        AiSearchService.Tavily -> copy(searchKeyTavily = key)
        AiSearchService.None -> this
    }
}

/** Everything one AI call needs. Built per call from the stored config. */
data class AiSettings(
    val prefs: AiProviderPrefs,
    val geminiKey: String,
) {
    val provider: AiProvider get() = prefs.provider

    val apiKey: String
        get() = when (provider) {
            AiProvider.Gemini -> geminiKey
            AiProvider.DeepSeek -> prefs.deepSeekKey
            AiProvider.Qwen -> prefs.qwenKey
            AiProvider.Custom -> prefs.customKey
        }.trim()

    /** A call can be made: the key (and for Custom the endpoint and model) is set. */
    val isReady: Boolean
        get() = apiKey.isNotEmpty() && (
            provider != AiProvider.Custom ||
                (prefs.customBaseUrl.isNotBlank() && prefs.customModel.isNotBlank())
            )

    val modelId: String
        get() = when (provider) {
            AiProvider.Gemini -> if (prefs.activeTier == AiTier.Best) GEMINI_BEST else GEMINI_GOOD
            AiProvider.DeepSeek -> DEEPSEEK_MODEL
            AiProvider.Qwen -> QWEN_MODEL
            AiProvider.Custom -> prefs.customModel.trim()
        }

    /** Where search comes from for a call that wants it. */
    val searchKind: SearchKind
        get() = when (provider) {
            AiProvider.Gemini, AiProvider.Qwen -> SearchKind.BuiltIn
            AiProvider.DeepSeek -> SearchKind.External
            AiProvider.Custom ->
                if (prefs.searchService == AiSearchService.None || searchApiKey.isEmpty()) {
                    SearchKind.None
                } else {
                    SearchKind.External
                }
        }

    val searchService: AiSearchService
        get() = if (provider == AiProvider.DeepSeek) AiSearchService.DeepSeek else prefs.searchService

    val searchApiKey: String
        get() = if (provider == AiProvider.DeepSeek) {
            prefs.deepSeekKey.trim()
        } else {
            prefs.searchKeyFor(prefs.searchService).trim()
        }

    /**
     * How many searches Yoin runs per About. 35-song test (2026-11-12): one
     * search reading full pages in the song's own language got most of the
     * gain; DeepSeek's 「最佳」 adds a second query on the album.
     */
    val searchQueries: Int
        get() = if (provider == AiProvider.DeepSeek && prefs.activeTier == AiTier.Best) 2 else 1

    enum class SearchKind { BuiltIn, External, None }

    companion object {
        const val GEMINI_GOOD = "gemini-3.1-flash-lite"
        const val GEMINI_BEST = "gemini-3.5-flash-lite"
        const val DEEPSEEK_MODEL = "deepseek-flash"
        const val QWEN_MODEL = "qwen-flash"
        const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"
        const val QWEN_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"

        /** The settings every install had before v2: Gemini 3.1 Flash-Lite with Google Search. */
        fun geminiOnly(apiKey: String): AiSettings = AiSettings(AiProviderPrefs(), apiKey)
    }
}
