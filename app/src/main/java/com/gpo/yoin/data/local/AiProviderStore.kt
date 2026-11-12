package com.gpo.yoin.data.local

import android.content.Context
import com.gpo.yoin.data.remote.ai.AiProviderPrefs
import com.gpo.yoin.data.remote.ai.AiSettings
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * The AI settings v2 choices (service, tier, the non-Gemini keys, Custom).
 * The Gemini key stays in `gemini_config` where it always was.
 *
 * Kept as one small file in `noBackupFilesDir`: out of Room (no migration,
 * not in the cloud sync) and out of every backup, because it holds API keys.
 */
interface AiProviderStore {
    val prefs: StateFlow<AiProviderPrefs>

    fun update(transform: (AiProviderPrefs) -> AiProviderPrefs)

    /** Process-local, for previews and tests. */
    class InMemory(initial: AiProviderPrefs = AiProviderPrefs()) : AiProviderStore {
        private val state = MutableStateFlow(initial)
        override val prefs: StateFlow<AiProviderPrefs> = state.asStateFlow()
        override fun update(transform: (AiProviderPrefs) -> AiProviderPrefs) = state.update(transform)
    }
}

class FileAiProviderStore(context: Context) : AiProviderStore {
    private val file = File(context.applicationContext.noBackupFilesDir, FILE_NAME)
    private val state = MutableStateFlow(read())
    override val prefs: StateFlow<AiProviderPrefs> = state.asStateFlow()

    @Synchronized
    override fun update(transform: (AiProviderPrefs) -> AiProviderPrefs) {
        val next = transform(state.value)
        if (next == state.value) return
        state.value = next
        runCatching { file.writeText(encode(next).toString()) }
    }

    private fun read(): AiProviderPrefs =
        runCatching { decode(JSONObject(file.readText())) }.getOrDefault(AiProviderPrefs())

    companion object {
        private const val FILE_NAME = "ai_provider.json"
        private const val LEGACY_SEARCH_KEY = "searchKey"

        internal fun encode(p: AiProviderPrefs): JSONObject = JSONObject()
            .put("provider", p.provider.name)
            .put("tier", p.tier.name)
            .put("deepSeekKey", p.deepSeekKey)
            .put("qwenKey", p.qwenKey)
            .put("customFormat", p.customFormat.name)
            .put("customBaseUrl", p.customBaseUrl)
            .put("customKey", p.customKey)
            .put("customModel", p.customModel)
            .put("customThinking", p.customThinking.name)
            .put("searchService", p.searchService.name)
            .put("searchKeyDeepSeek", p.searchKeyDeepSeek)
            .put("searchKeyExa", p.searchKeyExa)
            .put("searchKeyTavily", p.searchKeyTavily)

        internal fun decode(o: JSONObject): AiProviderPrefs {
            val d = AiProviderPrefs()
            val prefs = AiProviderPrefs(
                provider = enumOr(o.optString("provider"), d.provider),
                tier = enumOr(o.optString("tier"), d.tier),
                deepSeekKey = o.optString("deepSeekKey", d.deepSeekKey),
                qwenKey = o.optString("qwenKey", d.qwenKey),
                customFormat = enumOr(o.optString("customFormat"), d.customFormat),
                customBaseUrl = o.optString("customBaseUrl", d.customBaseUrl),
                customKey = o.optString("customKey", d.customKey),
                customModel = o.optString("customModel", d.customModel),
                customThinking = enumOr(o.optString("customThinking"), d.customThinking),
                searchService = enumOr(o.optString("searchService"), d.searchService),
                searchKeyDeepSeek = o.optString("searchKeyDeepSeek", d.searchKeyDeepSeek),
                searchKeyExa = o.optString("searchKeyExa", d.searchKeyExa),
                searchKeyTavily = o.optString("searchKeyTavily", d.searchKeyTavily),
            )
            // Files written while the search services shared one key: it was typed for the
            // service chosen then, so that service keeps it and the others start empty.
            val shared = o.optString(LEGACY_SEARCH_KEY)
            return if (shared.isNotEmpty() && prefs.searchKeyFor(prefs.searchService).isEmpty()) {
                prefs.withSearchKey(prefs.searchService, shared)
            } else {
                prefs
            }
        }

        private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: fallback
    }
}

/** The settings for one AI call: the stored choices plus the Gemini key. */
fun AiProviderStore?.settingsFor(config: GeminiConfig?): AiSettings =
    AiSettings(this?.prefs?.value ?: AiProviderPrefs(), config?.apiKey.orEmpty())
