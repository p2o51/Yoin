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
            AiProvider.Custom -> when (ai.prefs.customFormat) {
                AiApiFormat.OpenAi -> openAi(
                    ai = ai,
                    url = "${ai.prefs.customBaseUrl.trim().trimEnd('/')}/chat/completions",
                    prompt = prompt,
                    temperature = temperature.takeIf { ai.prefs.customThinking == AiThinking.Off },
                    timeoutSeconds = timeoutSeconds,
                ) {
                    reasoningEffort(ai.prefs.customThinking)?.let { put("reasoning_effort", it) }
                }
                AiApiFormat.Anthropic -> anthropic(ai, prompt, temperature, timeoutSeconds)
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
            AiSearchService.None -> emptyList()
        }.filter { it.text.isNotBlank() }
            .map { it.copy(text = it.text.take(MAX_PAGE_CHARS)) }
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

    private fun anthropic(ai: AiSettings, prompt: String, temperature: Float, timeoutSeconds: Long?): String {
        val budget = when (ai.prefs.customThinking) {
            AiThinking.Off -> 0
            AiThinking.Low -> 1024
            AiThinking.Medium -> 4096
            AiThinking.High -> 8192
        }
        val body = buildJsonObject {
            put("model", ai.modelId)
            put("max_tokens", ANTHROPIC_MAX_TOKENS + budget)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            }
            if (budget > 0) {
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", budget)
                }
            } else {
                put("temperature", temperature.coerceAtMost(1f))
            }
        }
        val root = post(
            "${anthropicBase(ai.prefs.customBaseUrl.trim().trimEnd('/'))}/messages",
            body,
            providerLabel(ai),
            timeoutSeconds,
        ) {
            header("x-api-key", ai.apiKey)
            header("anthropic-version", ANTHROPIC_VERSION)
        }
        return root.array("content")
            .filter { it.str("type") == "text" }
            .joinToString("") { it.str("text") }
            .takeIf { it.isNotEmpty() }
            ?: throw GeminiException("No content in ${providerLabel(ai)} response")
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
                throw GeminiException("$label error (${response.code}): ${errorMessage(text)}")
            }
            return runCatching { json.parseToJsonElement(text).jsonObject }
                .getOrElse { throw GeminiException("Unreadable response from $label") }
        }
    }

    private fun errorMessage(body: String): String = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        (root["error"] as? JsonObject)?.str("message")?.takeIf(String::isNotBlank)
            ?: (root["error"] as? JsonPrimitive)?.contentOrNull
            ?: root.str("message").takeIf(String::isNotBlank)
    }.getOrNull() ?: body.take(200)

    companion object {
        const val SEARCH_RESULTS = 8
        const val MAX_PAGES_IN_PROMPT = 10
        const val MAX_PAGE_CHARS = 2000
        private const val DEEPSEEK_QUERY_LIMIT = 128
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val ANTHROPIC_MAX_TOKENS = 2048
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

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
