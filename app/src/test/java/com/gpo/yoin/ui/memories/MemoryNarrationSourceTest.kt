package com.gpo.yoin.ui.memories

import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.local.GeminiConfigDao
import com.gpo.yoin.data.local.MemoryCopyCache
import com.gpo.yoin.data.local.MemoryCopyCacheDao
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.remote.GeminiException
import com.gpo.yoin.data.remote.GeminiService
import com.gpo.yoin.ui.memories.copy.MemoryNarrationBrief
import com.gpo.yoin.ui.memories.copy.MemoryProseLanguage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryNarrationSourceTest {

    private val albumId = MediaId("subsonic", "al-1")
    private val brief = MemoryNarrationBrief(
        language = MemoryProseLanguage.ZH,
        facts = listOf("Plays in Yoin: 9", "Listening since: 九月", "Last played: 四天前"),
        alreadySaid = "四天，四条笔记",
    )
    private val gemini = mockk<GeminiService>()
    private val configDao = mockk<GeminiConfigDao>()
    private val cacheDao = FakeMemoryCopyCacheDao()

    private fun source(apiKey: String? = "key") = GeminiMemoryNarrationSource(
        geminiService = gemini,
        geminiConfigDao = configDao.also { dao ->
            every { dao.getConfig() } returns flowOf(apiKey?.let { key -> GeminiConfig(apiKey = key) })
        },
        cacheDao = cacheDao,
        activeProfileId = MutableStateFlow("profile-a"),
        clock = { 42L },
    )

    @Test
    fun should_not_reuse_copy_cached_by_old_prompt() = runTest {
        // a v1 row: the poetic one-liner, keyed by a bare signal hash, with an AI title beside it
        cacheDao.rows += MemoryCopyCache(
            profileId = "profile-a",
            provider = "subsonic",
            entityType = MemoryCopyCache.ENTITY_ALBUM,
            entityId = "al-1",
            copy = "雨声里的吉他，像一场没说完的梦。",
            promptHash = "123456789",
            title = "雨天里的水底吉他",
            titlePromptHash = "987",
        )
        coEvery { gemini.generateAlbumMemoryNarration(any(), any(), any(), any()) } returns
            GeminiService.MemoryNarrationText("你九月以来听了 9 遍，最近一次是四天前。", "是什么让你又回来？")

        val narration = source().narrate(albumId, brief)

        assertEquals("你九月以来听了 9 遍，最近一次是四天前。", narration?.narration)
        coVerify(exactly = 1) {
            gemini.generateAlbumMemoryNarration("key", "Simplified Chinese", brief.facts, "四天，四条笔记")
        }
        val row = cacheDao.rows.single()
        assertTrue(row.promptHash.startsWith("narration-v${GeminiService.MEMORY_NARRATION_PROMPT_VERSION}:"))
        // the AI title shares the row and survives
        assertEquals("雨天里的水底吉他", row.title)
        assertEquals("987", row.titlePromptHash)
    }

    @Test
    fun should_return_null_instead_of_old_copy_when_no_key() = runTest {
        cacheDao.rows += MemoryCopyCache(
            profileId = "profile-a",
            provider = "subsonic",
            entityType = MemoryCopyCache.ENTITY_ALBUM,
            entityId = "al-1",
            copy = "雨声里的吉他，像一场没说完的梦。",
            promptHash = "123456789",
        )

        assertNull(source(apiKey = null).narrate(albumId, brief))
    }

    @Test
    fun should_serve_cached_narration_when_brief_unchanged() = runTest {
        coEvery { gemini.generateAlbumMemoryNarration(any(), any(), any(), any()) } returns
            GeminiService.MemoryNarrationText("你九月以来听了 9 遍。", "是什么让你又回来？")
        val first = source().narrate(albumId, brief)
        val second = source(apiKey = null).narrate(albumId, brief)

        assertEquals(first, second)
        coVerify(exactly = 1) { gemini.generateAlbumMemoryNarration(any(), any(), any(), any()) }

        // a new play changes the brief: the cached line no longer matches
        val changed = brief.copy(facts = listOf("Plays in Yoin: 10", "Listening since: 九月", "Last played: 今天"))
        assertNull(source(apiKey = null).narrate(albumId, changed))
    }

    @Test
    fun should_fall_back_when_gemini_fails_or_breaks_voice_rules() = runTest {
        coEvery { gemini.generateAlbumMemoryNarration(any(), any(), any(), any()) } throws GeminiException("offline")
        assertNull(source().narrate(albumId, brief))

        coEvery { gemini.generateAlbumMemoryNarration(any(), any(), any(), any()) } returns
            GeminiService.MemoryNarrationText("这张专辑正在形成记忆。", "你还记得吗？")
        assertNull(source().narrate(albumId, brief))
        assertTrue(cacheDao.rows.isEmpty())
    }

    @Test
    fun should_check_voice_rules() {
        assertTrue(
            MemoryNarration(
                narration = "37 plays since March, the last one two days ago.",
                question = "You gave it a 9.5. What earned it?",
            ).followsVoiceRules(),
        )
        assertFalse(MemoryNarration("One. Two. Three.", "Why?").followsVoiceRules())
        assertFalse(MemoryNarration("A memory is forming.", "Why?").followsVoiceRules())
        assertFalse(MemoryNarration("你回来了。", "你还记得吗？还有呢？再说说？").followsVoiceRules())
        assertTrue(narrationCacheKey(brief) != narrationCacheKey(brief.copy(language = MemoryProseLanguage.EN)))
    }

    private class FakeMemoryCopyCacheDao : MemoryCopyCacheDao {
        val rows = mutableListOf<MemoryCopyCache>()

        override suspend fun get(
            profileId: String,
            provider: String,
            entityType: String,
            entityId: String,
        ): MemoryCopyCache? = rows.firstOrNull { row ->
            row.profileId == profileId && row.provider == provider &&
                row.entityType == entityType && row.entityId == entityId
        }

        override suspend fun upsert(entry: MemoryCopyCache) {
            rows.removeAll { row ->
                row.profileId == entry.profileId && row.provider == entry.provider &&
                    row.entityType == entry.entityType && row.entityId == entry.entityId
            }
            rows += entry
        }

        override suspend fun delete(profileId: String, provider: String, entityType: String, entityId: String) {
            rows.removeAll { row ->
                row.profileId == profileId && row.provider == provider &&
                    row.entityType == entityType && row.entityId == entityId
            }
        }

        override suspend fun pruneOlderThan(olderThanEpochMs: Long) {
            rows.removeAll { row -> row.generatedAt < olderThanEpochMs }
        }
    }
}
