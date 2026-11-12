package com.gpo.yoin.ui.settings.ai

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.data.remote.ai.AiProviderPrefs
import com.gpo.yoin.data.remote.ai.AiTier
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The AI page over plain state: the page as the Tips demo and the preview show it. */
@RunWith(RobolectricTestRunner::class)
class AiSettingsPageBodyTest {

    @get:Rule
    val rule = createComposeRule()

    private val state: MutableState<AiSettingsState> = mutableStateOf(AiSettingsState(AiProviderPrefs()))
    private val opened = mutableListOf<String>()

    @Test
    fun should_switchServiceAndTier_when_theirChoicesAreTapped() {
        showPage()

        // The legend under the chart names the services too: the choice is the one that can be tapped.
        rule.onNode(hasText("DeepSeek") and hasClickAction()).performClick()
        rule.onNodeWithText("Best").performScrollTo().performClick()

        rule.runOnIdle {
            assertEquals(AiProvider.DeepSeek, state.value.prefs.provider)
            assertEquals(AiTier.Best, state.value.prefs.tier)
        }
        rule.onNode(hasText("DeepSeek") and hasClickAction()).assertIsSelected()
        rule.onNodeWithText("Best").assertIsSelected()
    }

    @Test
    fun should_swapTiersForTheEndpoint_when_customIsChosen() {
        showPage()

        rule.onNodeWithText("Custom").performClick()

        rule.onNodeWithText("Good").assertDoesNotExist()
        rule.onNodeWithText("Address").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Fetch").performScrollTo().assertIsDisplayed()
        // Thinking waits for a model to think with.
        rule.onNodeWithText("Thinking").assertDoesNotExist()
        rule.runOnIdle { state.value = state.value.copy(prefs = state.value.prefs.copy(customModel = "model-1")) }
        rule.onNodeWithText("Thinking").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun should_openTheServicesKeyPage_when_theKeyLinkIsTapped() {
        state.value = AiSettingsState(AiProviderPrefs(provider = AiProvider.DeepSeek))
        showPage()

        rule.onNodeWithText("Get a key on the DeepSeek platform").performScrollTo().performClick()

        rule.runOnIdle { assertEquals(listOf("https://platform.deepseek.com/api_keys"), opened) }
    }

    @Config(qualifiers = "zh-rCN")
    @Test
    fun should_showTheChineseLabels_when_theAppUsesSimplifiedChinese() {
        showPage()

        rule.onNode(hasText("千问") and hasClickAction()).assertIsDisplayed()
        rule.onNodeWithText("最佳").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("拿一首歌试试").performScrollTo().assertIsDisplayed()
    }

    private fun showPage() {
        rule.setContent {
            YoinTheme {
                AiSettingsPageBody(
                    state = state.value,
                    actions = AiSettingsStateActions(state),
                    onOpenTiers = {},
                    onOpenTry = {},
                    onOpenLink = { opened += it },
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                )
            }
        }
    }
}
