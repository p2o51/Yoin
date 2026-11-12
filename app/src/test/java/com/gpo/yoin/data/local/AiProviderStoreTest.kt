package com.gpo.yoin.data.local

import com.gpo.yoin.data.remote.ai.AiApiFormat
import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.data.remote.ai.AiProviderPrefs
import com.gpo.yoin.data.remote.ai.AiSearchService
import com.gpo.yoin.data.remote.ai.AiThinking
import com.gpo.yoin.data.remote.ai.AiTier
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AiProviderStoreTest {
    @Test
    fun should_roundTrip_everyField() {
        val prefs = AiProviderPrefs(
            provider = AiProvider.Custom,
            tier = AiTier.Best,
            deepSeekKey = "d",
            qwenKey = "q",
            customFormat = AiApiFormat.Anthropic,
            customBaseUrl = "https://x",
            customKey = "c",
            customModel = "m",
            customThinking = AiThinking.High,
            searchService = AiSearchService.Exa,
            searchKeyDeepSeek = "sd",
            searchKeyExa = "se",
            searchKeyTavily = "st",
        )
        assertEquals(prefs, FileAiProviderStore.decode(FileAiProviderStore.encode(prefs)))
    }

    @Test
    fun should_giveTheSharedSearchKeyToTheServiceChosenThen_when_theFileIsFromBeforePerServiceKeys() {
        val old = JSONObject().put("searchService", "Tavily").put("searchKey", "tv-key")

        val decoded = FileAiProviderStore.decode(old)

        assertEquals("tv-key", decoded.searchKeyTavily)
        assertEquals("", decoded.searchKeyDeepSeek)
        assertEquals("", decoded.searchKeyExa)
        // Written back, the shared key is gone and the Tavily one stays.
        val rewritten = FileAiProviderStore.encode(decoded)
        assertFalse(rewritten.has("searchKey"))
        assertEquals(decoded, FileAiProviderStore.decode(rewritten))
    }

    @Test
    fun should_fallBack_on_unknownOrMissingValues() {
        val decoded = FileAiProviderStore.decode(JSONObject().put("provider", "Removed").put("tier", "Best"))
        assertEquals(AiProviderPrefs(tier = AiTier.Best), decoded)
    }
}
