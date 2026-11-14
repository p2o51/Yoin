package com.gpo.yoin.ui.settings.ai

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.data.remote.ai.AiProviderPrefs
import com.gpo.yoin.data.remote.ai.AiSearchService
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
    fun should_offerNoTierChoice_when_theServiceHasOneTier() {
        state.value = AiSettingsState(AiProviderPrefs(tier = AiTier.Best))
        showPage()
        rule.onNodeWithText("Best").performScrollTo().assertIsSelected()

        rule.onNode(hasText("Qwen") and hasClickAction()).performClick()

        rule.onNodeWithText("Good").assertDoesNotExist()
        rule.onNodeWithText("Best").assertDoesNotExist()
        rule.onNodeWithText("Get a key in Alibaba Cloud Model Studio").performScrollTo().assertIsDisplayed()
        // The choice made on Gemini is kept for when the user goes back.
        rule.runOnIdle { assertEquals(AiTier.Best, state.value.prefs.tier) }
        rule.onNode(hasText("Gemini") and hasClickAction()).performClick()
        rule.onNodeWithText("Best").performScrollTo().assertIsSelected()
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
    fun should_giveEachSearchServiceItsOwnKeyField_when_theSearchChoiceChanges() {
        state.value = AiSettingsState(
            AiProviderPrefs(
                provider = AiProvider.Custom,
                searchService = AiSearchService.DeepSeek,
                searchKeyDeepSeek = "ds-key",
            ),
        )
        showPage()

        pickSearch("Exa")
        // The DeepSeek key must not turn up under Exa's label.
        searchKeyField("Exa").performScrollTo().assertTextEquals("")
        searchKeyField("Exa").performTextInput("exa-key")
        pickSearch("Tavily")
        searchKeyField("Tavily").performScrollTo().assertTextEquals("")

        rule.runOnIdle {
            assertEquals("ds-key", state.value.prefs.searchKeyDeepSeek)
            assertEquals("exa-key", state.value.prefs.searchKeyExa)
            assertEquals("", state.value.prefs.searchKeyTavily)
        }

        pickSearch("No search")
        rule.onNodeWithText("Tavily key").assertDoesNotExist()
    }

    @Test
    fun should_listEverySearchServiceInTheMenu_andShowTheChoiceOnTheRow() {
        state.value = AiSettingsState(AiProviderPrefs(provider = AiProvider.Custom))
        showPage()

        // The row shows the current service on its right.
        rule.onNode(hasText("Search") and hasClickAction()).performScrollTo().assertIsDisplayed()
        rule.onNode(hasText("Search") and hasClickAction()).performClick()
        listOf("DeepSeek", "Exa", "Tavily", "Brave", "Doubao", "No search").forEach { name ->
            rule.onNode(isPopupItem(name)).assertIsDisplayed()
        }
        rule.onNode(isPopupItem("Brave")).performClick()

        rule.runOnIdle { assertEquals(AiSearchService.Brave, state.value.prefs.searchService) }
        searchKeyField("Brave").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Get a Brave key").performScrollTo().performClick()

        pickSearch("Doubao")
        searchKeyField("Doubao").performScrollTo().performTextInput("db-key")
        rule.onNodeWithText("Get a Doubao key").performScrollTo().performClick()

        rule.runOnIdle {
            assertEquals("db-key", state.value.prefs.searchKeyDoubao)
            assertEquals("", state.value.prefs.searchKeyBrave)
            assertEquals(
                listOf(
                    "https://api-dashboard.search.brave.com/app/keys",
                    "https://console.volcengine.com/search-infinity/api-key",
                ),
                opened,
            )
        }
    }

    @Test
    fun should_chartTheSearchServicesWithANote_onlyWhileCustomIsChosen() {
        val note = "Tested with deepseek-flash as the writing model, changing only the search service. " +
            "Tavily and Brave are estimated from 35 songs; the rest were measured on 499."
        showPage()

        rule.onNodeWithText(note).assertDoesNotExist()
        rule.onNodeWithText("Exa").assertDoesNotExist()

        rule.onNode(hasText("Custom") and hasClickAction()).performClick()
        rule.waitForIdle()

        rule.onNodeWithText(note).assertIsDisplayed()
        // The legend lists every search service while the menu is closed.
        listOf("Exa", "Tavily", "Brave", "Doubao", "No search").forEach { name ->
            rule.onNodeWithText(name).assertIsDisplayed()
        }

        rule.onNode(hasText("Gemini") and hasClickAction()).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(note).assertDoesNotExist()
        rule.onNodeWithText("Tavily").assertDoesNotExist()
    }

    @Test
    fun should_offerEveryFetchedModel_when_theListIsShort() {
        val ids = listOf("claude-haiku", "claude-opus", "claude-sonnet")
        assertEquals(ids, modelChips(ids, typed = "opus"))
    }

    @Test
    fun should_narrowALongModelListByWhatIsTyped() {
        val ids = (1..300).map { "ai21/model-$it" } + listOf("anthropic/claude-sonnet", "openai/gpt")

        assertEquals(24, modelChips(ids, typed = "").size)
        assertEquals(listOf("anthropic/claude-sonnet"), modelChips(ids, typed = " Claude "))
        assertEquals(emptyList<String>(), modelChips(ids, typed = "no-such-model"))
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

    /** Open the search row's menu and pick [service]. */
    private fun pickSearch(service: String) {
        rule.onNode(hasText("Search") and hasClickAction()).performScrollTo().performClick()
        rule.onNode(isPopupItem(service)).performClick()
        rule.waitForIdle()
    }

    /** A menu item: clickable, in the popup the row opens (the row itself also shows the choice). */
    private fun isPopupItem(text: String) = hasText(text) and hasClickAction() and hasAnyAncestor(isPopup())

    @Config(qualifiers = "zh-rCN")
    @Test
    fun should_nameDoubaoInChinese_when_theAppUsesSimplifiedChinese() {
        state.value = AiSettingsState(AiProviderPrefs(provider = AiProvider.Custom, searchService = AiSearchService.Doubao))
        showPage()

        // The chart's legend names 豆包 too: the search row is the one that can be tapped.
        rule.onNode(hasText("豆包") and hasClickAction()).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("获取豆包密钥").performScrollTo().assertIsDisplayed()
    }

    /** The masked field under "[service] key": the one whose eye button names it. */
    private fun searchKeyField(service: String) =
        rule.onNode(hasSetTextAction() and hasAnyDescendant(hasContentDescription("Show $service key")))

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
