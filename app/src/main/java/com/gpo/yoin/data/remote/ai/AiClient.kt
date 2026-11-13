package com.gpo.yoin.data.remote.ai

import com.gpo.yoin.data.remote.GeminiException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * One text-in, text-out call to whichever service [AiSettings] names, plus
 * the search APIs Yoin calls itself. No streaming; every caller wants the
 * whole answer at once.
 */
class AiClient(
    private val client: OkHttpClient,
    private val json: Json,
) {
    /** One page Yoin found through a search API. */
    data class SearchHit(val title: String, val url: String, val text: String)

    /**
     * Send [prompt]. With [useSearch], Gemini and Qwen search on their own;
     * for the other services the caller has already put [search] results in
     * the prompt. [timeoutSeconds] caps the whole call.
     */
    suspend fun generate(
        ai: AiSettings,
        prompt: String,
        temperature: Float,
        useSearch: Boolean,
        timeoutSeconds: Long? = null,
    ): String = withContext(Dispatchers.IO) {
        when (ai.provider) {
            AiProvider.Gemini -> gemini(ai, prompt, temperature, useSearch, timeoutSeconds)
            AiProvider.DeepSeek -> openAi(
                ai = ai,
                url = "${AiSettings.DEEPSEEK_BASE_URL}/chat/completions",
                prompt = prompt,
                temperature = temperature,
                timeoutSeconds = timeoutSeconds,
            ) {
                // About doesn't gain from thinking (2026-11-12): slower, same accuracy.
                putJsonObject("thinking") { put("type", "disabled") }
            }
            AiProvider.Qwen -> openAi(
                ai = ai,
                url = "${AiSettings.QWEN_BASE_URL}/chat/completions",
                prompt = prompt,
                temperature = temperature,
                timeoutSeconds = timeoutSeconds,
            ) {
                put("enable_thinking", false)
                if (useSearch) {
                    put("enable_search", true)
                    putJsonObject("search_options") { put("forced_search", true) }
                }
            }
            // Custom sends no temperature in either format: OpenAI's reasoning models and
            // Anthropic's current models answer anything but their default with a 400.
            AiProvider.Custom -> when (ai.prefs.customFormat) {
                AiApiFormat.OpenAi -> openAi(
                    ai = ai,
                    url = "${ai.prefs.customBaseUrl.trim().trimEnd('/')}/chat/completions",
                    prompt = prompt,
                    temperature = null,
                    timeoutSeconds = timeoutSeconds,
                ) {
                    reasoningEffort(ai.prefs.customThinking)?.let { put("reasoning_effort", it) }
                }
                AiApiFormat.Anthropic -> anthropic(ai, prompt, timeoutSeconds)
            }
        }
    }

    /** Run one query on the search service [ai] names. Returns pages with up to [MAX_PAGE_CHARS] of text each. */
    suspend fun search(ai: AiSettings, query: String): List<SearchHit> = withContext(Dispatchers.IO) {
        val key = ai.searchApiKey
        when (ai.searchService) {
            AiSearchService.DeepSeek -> {
                val body = buildJsonObject {
                    put("query", query.take(DEEPSEEK_QUERY_LIMIT))
                    put("count", SEARCH_RESULTS)
                    put("need_content", true)
                }
                val root = post("${AiSettings.DEEPSEEK_BASE_URL}/v1/tools/search", body, "DeepSeek Search", null) {
                    header("Authorization", "Bearer $key")
                }
                root.array("results").map { item ->
                    SearchHit(item.str("title"), item.str("url"), item.str("content").ifEmpty { item.str("summary") })
                }
            }
            AiSearchService.Exa -> {
                val body = buildJsonObject {
                    put("query", query)
                    put("numResults", SEARCH_RESULTS)
                    putJsonObject("contents") { putJsonObject("text") { put("maxCharacters", MAX_PAGE_CHARS) } }
                }
                val root = post("https://api.exa.ai/search", body, "Exa", null) { header("x-api-key", key) }
                root.array("results").map { SearchHit(it.str("title"), it.str("url"), it.str("text")) }
            }
            AiSearchService.Tavily -> {
                // advanced + chunks: the basic summaries missed most credits (2026-11-12).
                val body = buildJsonObject {
                    put("query", query)
                    put("max_results", SEARCH_RESULTS)
                    put("search_depth", "advanced")
                    put("chunks_per_source", 3)
                }
                val root = post("https://api.tavily.com/search", body, "Tavily", null) {
                    header("Authorization", "Bearer $key")
                }
                root.array("results").map { SearchHit(it.str("title"), it.str("url"), it.str("content")) }
            }
            AiSearchService.Brave -> braveSearch(key, query)
            AiSearchService.Doubao -> {
                // 豆包搜索, Custom edition (Volcengine). NeedContent asks for each page's text.
                val body = buildJsonObject {
                    put("Query", query.take(DOUBAO_QUERY_LIMIT))
                    put("SearchType", "web")
                    put("Count", SEARCH_RESULTS)
                    putJsonObject("Filter") { put("NeedContent", true) }
                }
                val root = post(DOUBAO_SEARCH_URL, body, "Doubao", null) { header("Authorization", "Bearer $key") }
                // A refused call still answers 200: Result is null and the reason is in ResponseMetadata.
                doubaoError(root)?.let { throw GeminiException("Doubao error: $it") }
                root.obj("Result")?.array("WebResults").orEmpty().map { item ->
                    SearchHit(item.str("Title"), item.str("Url"), item.str("Content").ifEmpty { item.str("Summary") })
                }
            }
            AiSearchService.None -> emptyList()
        }.filter { it.text.isNotBlank() }
            .map { it.copy(text = it.text.take(MAX_PAGE_CHARS)) }
    }

    /**
     * Brave: the LLM Context endpoint, which returns page text. A plan without it (403, 404 or
     * 422) falls back once to web search, each page then being its description and extra snippets.
     */
    private fun braveSearch(key: String, query: String): List<SearchHit> {
        fun get(path: String, extra: Map<String, String>): JsonObject {
            val url = BRAVE_BASE_URL.toHttpUrl().newBuilder().addPathSegments(path)
                .addQueryParameter("q", query)
                .addQueryParameter("count", SEARCH_RESULTS.toString())
                .apply { extra.forEach { (name, value) -> addQueryParameter(name, value) } }
                .build()
            val request = Request.Builder().url(url).get()
                .header("X-Subscription-Token", key)
                .header("Accept", "application/json")
                .build()
            return execute(request, "Brave", null)
        }
        val context = try {
            get("res/v1/llm/context", emptyMap())
        } catch (error: GeminiException) {
            if (error.httpCode?.let { it in BRAVE_FALLBACK_CODES } != true) throw error
            null
        }
        if (context != null) {
            return context.obj("grounding")?.array("generic").orEmpty().map { item ->
                SearchHit(item.str("title"), item.str("url"), item.strings("snippets").joinToString("\n"))
            }
        }
        val web = get("res/v1/web/search", mapOf("extra_snippets" to "true"))
        return web.obj("web")?.array("results").orEmpty().map { item ->
            val text = (listOf(item.str("description")) + item.strings("extra_snippets"))
                .filter(String::isNotBlank)
                .joinToString("\n")
            SearchHit(item.str("title"), item.str("url"), text)
        }
    }

    /** The model ids a Custom endpoint offers, for the 「拉取」 button. */
    suspend fun listModels(format: AiApiFormat, baseUrl: String, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            val base = baseUrl.trim().trimEnd('/')
            val url = when (format) {
                AiApiFormat.OpenAi -> "$base/models"
                AiApiFormat.Anthropic -> "${anthropicBase(base)}/models"
            }
            val request = Request.Builder().url(url).get().apply {
                when (format) {
                    AiApiFormat.OpenAi -> header("Authorization", "Bearer ${apiKey.trim()}")
                    AiApiFormat.Anthropic -> {
                        header("x-api-key", apiKey.trim())
                        header("anthropic-version", ANTHROPIC_VERSION)
                    }
                }
            }.build()
            val root = execute(request, "Models", null)
            root.array("data").mapNotNull { it.str("id").takeIf(String::isNotBlank) }.sorted()
        }

    private fun gemini(
        ai: AiSettings,
        prompt: String,
        temperature: Float,
        useSearch: Boolean,
        timeoutSeconds: Long?,
    ): String {
        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject { putJsonArray("parts") { addJsonObject { put("text", prompt) } } }
            }
            if (useSearch) putJsonArray("tools") { addJsonObject { putJsonObject("googleSearch") {} } }
            putJsonObject("generationConfig") { put("temperature", temperature) }
        }
        val root = post(
            "https://generativelanguage.googleapis.com/v1beta/models/${ai.modelId}:generateContent",
            body,
            "Gemini",
            timeoutSeconds,
        ) { header("x-goog-api-key", ai.apiKey) }
        return root.array("candidates").firstOrNull()
            ?.obj("content")?.array("parts")
            ?.joinToString("") { it.str("text") }
            ?.takeIf { it.isNotEmpty() }
            ?: throw GeminiException("No content in Gemini response")
    }

    private fun openAi(
        ai: AiSettings,
        url: String,
        prompt: String,
        temperature: Float?,
        timeoutSeconds: Long?,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): String {
        val body = buildJsonObject {
            put("model", ai.modelId)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            }
            if (temperature != null) put("temperature", temperature)
            extra()
        }
        val root = post(url, body, providerLabel(ai), timeoutSeconds) { header("Authorization", "Bearer ${ai.apiKey}") }
        return root.array("choices").firstOrNull()
            ?.obj("message")?.str("content")
            ?.takeIf { it.isNotEmpty() }
            ?: throw GeminiException("No content in ${providerLabel(ai)} response")
    }

    private fun anthropic(ai: AiSettings, prompt: String, timeoutSeconds: Long?): String {
        val thinking = ai.prefs.customThinking
        val root = try {
            postAnthropic(ai, anthropicBody(ai.modelId, prompt, thinking, budgetThinking = false), timeoutSeconds)
        } catch (error: GeminiException) {
            if (thinking == AiThinking.Off || !rejectsAdaptiveThinking(error)) throw error
            postAnthropic(ai, anthropicBody(ai.modelId, prompt, thinking, budgetThinking = true), timeoutSeconds)
        }
        // A reply cut at the cap is not an answer: a caller would cache half a translation.
        if (root.str("stop_reason") == "max_tokens") {
            throw GeminiException("${providerLabel(ai)} reply was cut off at the output limit")
        }
        return root.array("content")
            .filter { it.str("type") == "text" }
            .joinToString("") { it.str("text") }
            .takeIf { it.isNotEmpty() }
            ?: throw GeminiException("No content in ${providerLabel(ai)} response")
    }

    private fun postAnthropic(ai: AiSettings, body: JsonObject, timeoutSeconds: Long?): JsonObject = post(
        "${anthropicBase(ai.prefs.customBaseUrl.trim().trimEnd('/'))}/messages",
        body,
        providerLabel(ai),
        timeoutSeconds,
    ) {
        header("x-api-key", ai.apiKey)
        header("anthropic-version", ANTHROPIC_VERSION)
    }

    private fun post(
        url: String,
        body: JsonObject,
        label: String,
        timeoutSeconds: Long?,
        headers: Request.Builder.() -> Unit,
    ): JsonObject {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .apply(headers)
            .build()
        return execute(request, label, timeoutSeconds)
    }

    private fun execute(request: Request, label: String, timeoutSeconds: Long?): JsonObject {
        val call = client.newCall(request)
        if (timeoutSeconds != null) call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
        call.execute().use { response ->
            val text = response.body?.string() ?: throw GeminiException("Empty response from $label")
            if (!response.isSuccessful) {
                throw GeminiException("$label error (${response.code}): ${errorMessage(text)}", response.code)
            }
            return runCatching { json.parseToJsonElement(text).jsonObject }
                .getOrElse { throw GeminiException("Unreadable response from $label") }
        }
    }

    private fun errorMessage(body: String): String = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        (root["error"] as? JsonObject)?.str("message")?.takeIf(String::isNotBlank)
            // Brave names the reason `detail`.
            ?: (root["error"] as? JsonObject)?.str("detail")?.takeIf(String::isNotBlank)
            ?: (root["error"] as? JsonPrimitive)?.contentOrNull
            ?: root.str("message").takeIf(String::isNotBlank)
            ?: doubaoError(root)
    }.getOrNull() ?: body.take(200)

    companion object {
        const val SEARCH_RESULTS = 8
        const val MAX_PAGES_IN_PROMPT = 10
        const val MAX_PAGE_CHARS = 2000
        private const val DEEPSEEK_QUERY_LIMIT = 128
        private const val DOUBAO_QUERY_LIMIT = 100
        private const val DOUBAO_SEARCH_URL = "https://open.feedcoopapi.com/search_api/web_search"
        private const val BRAVE_BASE_URL = "https://api.search.brave.com/"
        private val BRAVE_FALLBACK_CODES = setOf(403, 404, 422)
        private const val ANTHROPIC_VERSION = "2023-06-01"

        // Thinking counts against the cap and current Claude models think by default; a long
        // lyric translation alone runs to a few thousand tokens.
        private const val ANTHROPIC_MAX_TOKENS = 16000
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** 豆包搜索's refusal, "Message (Code)", from ResponseMetadata.Error; null when there is none. */
        private fun doubaoError(root: JsonObject): String? {
            val error = root.obj("ResponseMetadata")?.obj("Error") ?: return null
            val message = error.str("Message")
            val code = error.str("Code").ifEmpty { error.str("CodeN") }
            if (message.isEmpty() && code.isEmpty()) return null
            return if (code.isEmpty()) message else "${message.ifEmpty { code }} ($code)"
        }

        fun providerLabel(ai: AiSettings): String = when (ai.provider) {
            AiProvider.Gemini -> "Gemini"
            AiProvider.DeepSeek -> "DeepSeek"
            AiProvider.Qwen -> "Qwen"
            AiProvider.Custom -> ai.modelId.ifBlank { "Custom" }
        }

        internal fun reasoningEffort(thinking: AiThinking): String? = when (thinking) {
            AiThinking.Off -> null
            AiThinking.Low -> "low"
            AiThinking.Medium -> "medium"
            AiThinking.High -> "high"
        }

        /**
         * The Messages API body. No temperature: Claude Opus 4.7 and later answer it with a
         * 400. Thinking is adaptive with an effort level; 「不思考」 leaves both out (models
         * that always think then run at their default). [budgetThinking] is the shape
         * models before Claude 4.6 take instead: a fixed thinking budget.
         */
        internal fun anthropicBody(
            model: String,
            prompt: String,
            thinking: AiThinking,
            budgetThinking: Boolean,
        ): JsonObject = buildJsonObject {
            put("model", model)
            put("max_tokens", ANTHROPIC_MAX_TOKENS)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            }
            val effort = reasoningEffort(thinking) ?: return@buildJsonObject
            if (budgetThinking) {
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", thinkingBudget(thinking))
                }
            } else {
                putJsonObject("thinking") { put("type", "adaptive") }
                putJsonObject("output_config") { put("effort", effort) }
            }
        }

        private fun thinkingBudget(thinking: AiThinking): Int = when (thinking) {
            AiThinking.Off -> 0
            AiThinking.Low -> 1024
            AiThinking.Medium -> 4096
            AiThinking.High -> 8192
        }

        /**
         * A 400 that names the thinking fields: the model (Claude before 4.6) or the gateway
         * doesn't know adaptive thinking. Any other 400 is the caller's to see as it is.
         */
        internal fun rejectsAdaptiveThinking(error: GeminiException): Boolean {
            val message = error.message.orEmpty()
            return error.httpCode == 400 && ADAPTIVE_THINKING_WORDS.any { message.contains(it, ignoreCase = true) }
        }

        private val ADAPTIVE_THINKING_WORDS = listOf("thinking", "adaptive", "effort", "output_config")

        /** Anthropic's API lives under /v1; accept a base URL with or without it. */
        internal fun anthropicBase(base: String): String = if (base.endsWith("/v1")) base else "$base/v1"

        /**
         * The words that find credits pages in the song's own language, picked
         * from the script of its title and artist (a Latin name gets English).
         */
        fun creditsKeywords(title: String, artist: String): String {
            val text = title + artist
            return when {
                text.any { it in '가'..'힣' } -> "작사 작곡 프로듀서"
                text.any { it in '぀'..'ヿ' } -> "作詞 作曲 編曲"
                text.any { it in '一'..'鿿' } -> "作詞 作曲 製作人"
                else -> "songwriting credits producer"
            }
        }

        /** Pages for the prompt: deduplicated by URL, numbered, each cut to [MAX_PAGE_CHARS]. */
        fun contextBlock(hits: List<SearchHit>): String =
            hits.distinctBy { it.url }
                .take(MAX_PAGES_IN_PROMPT)
                .mapIndexed { i, hit -> "[${i + 1}] ${hit.title} (${hit.url})\n${hit.text.take(MAX_PAGE_CHARS)}" }
                .joinToString("\n\n")
    }
}

private fun JsonObject.array(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
