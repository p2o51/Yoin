package com.gpo.yoin.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings › Features and appearance › Music links (docs/open-in-yoin.md). */
@RunWith(RobolectricTestRunner::class)
class MusicLinksPageTest {

    @get:Rule
    val rule = createComposeRule()

    private val toggleTitle = "Detect music links in clipboard"

    @Test
    fun should_exposeASwitchWithItsState_when_theClipboardToggleIsShown() {
        val changes = mutableListOf<Boolean>()
        showPage(changes)

        rule.onNode(hasText(toggleTitle, substring = false).and(isSwitch()), useUnmergedTree = false)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
            .performClick()

        rule.onNode(hasText(toggleTitle).and(isSwitch()))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        rule.runOnIdle { assertEquals(listOf(false), changes) }
    }

    @Test
    fun should_showTheToggleThenBothExplanations_when_thePageOpens() {
        showPage()
        listOf(
            toggleTitle,
            "Checks the clipboard only when you open Yoin. Only looks for music links; nothing else is uploaded or saved.",
            "Open in Yoin",
            "Choose \"Open in Yoin\" from the share menu in other apps to send a song or link straight to Yoin.",
            "Open or search across services",
            "Links from a service you're signed into open directly; links from other services search in your current service.",
        ).forEach { text -> rule.onNodeWithText(text).performScrollTo().assertIsDisplayed() }
    }

    @Config(qualifiers = "zh-rCN")
    @Test
    fun should_showRebeccasChineseCopy_when_theAppUsesSimplifiedChinese() {
        showPage()
        listOf(
            "识别剪贴板中的音乐链接",
            "在 Yoin 中打开",
            "在其他应用的分享菜单里选择「在 Yoin 中打开」，即可直接传入歌曲或链接。",
            "跨账号打开与搜索",
            "已登录对应服务的链接会直接打开，未登录对应服务时会在你当前使用的服务中搜索。",
        ).forEach { text -> rule.onNodeWithText(text).performScrollTo().assertIsDisplayed() }
    }

    private fun isSwitch() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

    private fun showPage(changes: MutableList<Boolean> = mutableListOf()) {
        rule.setContent {
            YoinTheme {
                var enabled by remember { mutableStateOf(true) }
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    MusicLinksPageBody(
                        enabled = enabled,
                        onEnabledChange = {
                            changes += it
                            enabled = it
                        },
                    )
                }
            }
        }
    }
}
