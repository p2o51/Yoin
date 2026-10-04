package com.gpo.yoin.ui.memories

import com.gpo.yoin.data.local.GeminiConfigDao
import com.gpo.yoin.data.local.MemoryCopyCache
import com.gpo.yoin.data.local.MemoryCopyCacheDao
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.remote.GeminiService
import com.gpo.yoin.ui.memories.copy.MemoryNarrationBrief
import com.gpo.yoin.ui.memories.copy.MemorySentences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** Yoin's narration and its closing question, ready for the diary. */
data class MemoryNarration(
    val narration: String,
    val question: String,
)

/**
 * Where Yoin's narration comes from when it isn't the local template. Null
 * from [narrate] means "use the template": no key, no network, a bad answer.
 */
fun interface MemoryNarrationSource {
    suspend fun narrate(albumId: MediaId, brief: MemoryNarrationBrief): MemoryNarration?
}

/**
 * Gemini-written narration (owner decision 2026-10-04: the narration stays
 * Gemini-generated, in the prototype's voice), cached per album in
 * `memory_copy_cache` next to the AI title.
 *
 * The row's [MemoryCopyCache.promptHash] holds
 * `narration-v<prompt version>:<hash of the brief>`. Rows written by the old
 * poetic prompt hold a bare number there, so they never match and are never
 * shown; a miss without a key, or a failed call, returns null (the template),
 * never the stale row. No schema change: the key lives in the existing column.
 */
class GeminiMemoryNarrationSource(
    private val geminiService: GeminiService,
    private val geminiConfigDao: GeminiConfigDao,
    private val cacheDao: MemoryCopyCacheDao,
    private val activeProfileId: StateFlow<String?>,
    private val clock: () -> Long = System::currentTimeMillis,
) : MemoryNarrationSource {

    override suspend fun narrate(albumId: MediaId, brief: MemoryNarrationBrief): MemoryNarration? {
        val profileId = activeProfileId.value ?: return null
        val key = narrationCacheKey(brief)
        val cached = cacheDao.get(
            profileId = profileId,
            provider = albumId.provider,
            entityType = MemoryCopyCache.ENTITY_ALBUM,
            entityId = albumId.rawId,
        )
        if (cached != null && cached.promptHash == key) {
            GeminiService.parseMemoryNarration(cached.copy)?.let { text ->
                return MemoryNarration(text.narration, text.question)
            }
        }

        val apiKey = geminiConfigDao.getConfig().first()?.apiKey
        if (apiKey.isNullOrBlank()) return null

        val generated = try {
            geminiService.generateAlbumMemoryNarration(
                apiKey = apiKey,
                languageName = brief.languageName,
                facts = brief.facts,
                alreadySaid = brief.alreadySaid,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return null
        }
        val narration = MemoryNarration(generated.narration, generated.question)
        if (!narration.followsVoiceRules()) return null

        // Keep the row's AI title columns: title and narration expire on their own keys.
        val row = cached ?: MemoryCopyCache(
            profileId = profileId,
            provider = albumId.provider,
            entityType = MemoryCopyCache.ENTITY_ALBUM,
            entityId = albumId.rawId,
            copy = "",
            promptHash = "",
        )
        cacheDao.upsert(
            row.copy(
                copy = GeminiService.encodeMemoryNarration(generated),
                promptHash = key,
                generatedAt = clock(),
            ),
        )
        return narration
    }
}

/** `narration-v2:<hash>`: the prompt version and every input that shapes the prompt. */
internal fun narrationCacheKey(brief: MemoryNarrationBrief): String =
    "narration-v${GeminiService.MEMORY_NARRATION_PROMPT_VERSION}:${brief.signal.hashCode()}"

private val MECHANISM_WORDS = listOf("memor", "remember", "记忆", "回忆")

/**
 * The checks a model answer can fail on its own: one or two sentences of
 * narration, a short question (it may carry its own fact first, like "You
 * gave it a 9.5. What earned it?"), and no mechanism words. Anything else
 * falls back to the local template rather than reaching the diary.
 */
internal fun MemoryNarration.followsVoiceRules(): Boolean {
    val sentences = MemorySentences.split(narration)
    if (sentences.isEmpty() || sentences.size > 2) return false
    if (MemorySentences.split(question).size !in 1..2) return false
    val text = "$narration $question".lowercase()
    return MECHANISM_WORDS.none(text::contains)
}
