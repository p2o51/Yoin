package com.gpo.yoin.ui.component

import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.R
import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.ui.common.UiText
import com.gpo.yoin.ui.common.asString
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class AiProviderNameTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun ask(provider: AiProvider) =
        UiText.Res(R.string.ai_ask_provider, listOf(aiProviderNameText(provider))).asString(context)

    @Test
    fun should_nameTheActiveService_inEnglish() {
        assertEquals("Ask Gemini", ask(AiProvider.Gemini))
        assertEquals("Ask DeepSeek", ask(AiProvider.DeepSeek))
        assertEquals("Ask Qwen", ask(AiProvider.Qwen))
        assertEquals("Ask AI", ask(AiProvider.Custom))
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun should_nameTheActiveService_inChinese() {
        assertEquals("问 Gemini", ask(AiProvider.Gemini))
        assertEquals("问 DeepSeek", ask(AiProvider.DeepSeek))
        assertEquals("问千问", ask(AiProvider.Qwen))
        assertEquals("问 AI", ask(AiProvider.Custom))
        assertEquals(
            "千问没能回答，再试一次",
            UiText.Res(R.string.ai_failed, listOf(aiProviderNameText(AiProvider.Qwen))).asString(context),
        )
    }
}
