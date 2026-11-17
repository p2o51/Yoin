package com.gpo.yoin.data.remote.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSettingsTest {
    @Test
    fun should_keepPreV2Behaviour_when_nothingChosen() {
        val ai = AiSettings.geminiOnly("key")
        assertEquals(AiSettings.GEMINI_GOOD, ai.modelId)
        assertEquals(AiSettings.SearchKind.BuiltIn, ai.searchKind)
        assertTrue(ai.isReady)
        assertFalse(AiSettings.geminiOnly(" ").isReady)
    }

    @Test
    fun should_pickModelAndSearches_per_tier() {
        val best = AiSettings(AiProviderPrefs(provider = AiProvider.Gemini, tier = AiTier.Best), "k")
        assertEquals(AiSettings.GEMINI_BEST, best.modelId)
        val ds = AiProviderPrefs(provider = AiProvider.DeepSeek, deepSeekKey = "sk")
        assertEquals(1, AiSettings(ds, "").searchQueries)
        assertEquals(2, AiSettings(ds.copy(tier = AiTier.Best), "").searchQueries)
        assertEquals(AiSettings.SearchKind.External, AiSettings(ds, "").searchKind)
        assertEquals("sk", AiSettings(ds, "").searchApiKey)
    }

    @Test
    fun should_useQwensOneModel_whicheverTierWasChosen() {
        val qwen = AiProviderPrefs(provider = AiProvider.Qwen, tier = AiTier.Best, qwenKey = "sk")

        assertEquals(listOf(AiTier.Good), AiProvider.Qwen.tiers)
        assertEquals(AiTier.Good, qwen.activeTier)
        assertEquals("qwen-flash", AiSettings(qwen, "").modelId)
        assertEquals("qwen-flash", AiSettings(qwen.copy(tier = AiTier.Good), "").modelId)
        assertEquals(1, AiSettings(qwen, "").searchQueries)
    }

    @Test
    fun should_keepTheChosenTier_when_theServiceHasOnlyOne() {
        val qwen = AiProviderPrefs(provider = AiProvider.Qwen, tier = AiTier.Best)

        // Back on a service with two tiers, the choice is still there.
        val gemini = qwen.copy(provider = AiProvider.Gemini)
        assertEquals(AiTier.Best, gemini.activeTier)
        assertEquals(AiSettings.GEMINI_BEST, AiSettings(gemini, "k").modelId)
        // Custom has no tiers; the stored one is simply unused.
        assertEquals(AiTier.Best, qwen.copy(provider = AiProvider.Custom).activeTier)
    }

    @Test
    fun should_needEndpointAndModel_for_custom() {
        val bare = AiProviderPrefs(provider = AiProvider.Custom, customKey = "sk")
        assertFalse(AiSettings(bare, "").isReady)
        val full = bare.copy(customBaseUrl = "https://x/v1", customModel = "m")
        assertTrue(AiSettings(full, "").isReady)
        // A search service without its key means no search.
        assertEquals(AiSettings.SearchKind.None, AiSettings(full, "").searchKind)
        assertEquals(AiSettings.SearchKind.External, AiSettings(full.copy(searchKeyDeepSeek = "s"), "").searchKind)
        assertEquals(
            AiSettings.SearchKind.None,
            AiSettings(full.copy(searchKeyDeepSeek = "s", searchService = AiSearchService.None), "").searchKind,
        )
        // A key belongs to the service it was typed for.
        assertEquals(
            AiSettings.SearchKind.None,
            AiSettings(full.copy(searchKeyDeepSeek = "s", searchService = AiSearchService.Tavily), "").searchKind,
        )
    }

    @Test
    fun should_pickCreditsKeywords_by_script() {
        assertEquals("작사 작곡 프로듀서", AiClient.creditsKeywords("밤양갱", "BIBI"))
        assertEquals("作詞 作曲 編曲", AiClient.creditsKeywords("夜に駆ける", "YOASOBI"))
        assertEquals("作詞 作曲 製作人", AiClient.creditsKeywords("倔强", "五月天"))
        assertEquals("songwriting credits producer", AiClient.creditsKeywords("Lemon", "Kenshi Yonezu"))
    }

    @Test
    fun should_dedupeNumberAndCut_pages() {
        val long = "x".repeat(AiClient.MAX_PAGE_CHARS + 50)
        val block = AiClient.contextBlock(
            listOf(
                AiClient.SearchHit("A", "https://a", long),
                AiClient.SearchHit("A again", "https://a", "dup"),
                AiClient.SearchHit("B", "https://b", "body"),
            ),
        )
        assertTrue(block.startsWith("[1] A (https://a)\n"))
        assertTrue(block.contains("[2] B (https://b)\nbody"))
        assertFalse(block.contains("dup"))
        assertFalse(block.contains("x".repeat(AiClient.MAX_PAGE_CHARS + 1)))
    }

    @Test
    fun should_acceptAnthropicBase_with_or_without_v1() {
        assertEquals("https://api.anthropic.com/v1", AiClient.anthropicBase("https://api.anthropic.com"))
        assertEquals("https://api.anthropic.com/v1", AiClient.anthropicBase("https://api.anthropic.com/v1"))
    }
}
