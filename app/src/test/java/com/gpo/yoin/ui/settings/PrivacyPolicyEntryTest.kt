package com.gpo.yoin.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class PrivacyPolicyEntryTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_openThePublishedPolicy_when_thePrivacyRowIsTappedWithoutAnAccount() {
        val opened = mutableListOf<String>()
        showSettings(openUri = opened::add)

        rule.onNodeWithText("Privacy policy").performScrollTo().assertIsDisplayed().performClick()

        rule.runOnIdle {
            assertEquals(listOf("https://yoin.gojyuplus.com/privacy"), opened)
        }
    }

    @Test
    fun should_showAnErrorWithoutCrashing_when_noAppCanOpenThePolicy() {
        val snackbar = SnackbarHostState()
        showSettings(snackbar) { throw IllegalArgumentException("No handler") }

        rule.onNodeWithText("Privacy policy").performScrollTo().performClick()

        rule.onNodeWithText("Couldn't open the privacy policy").assertIsDisplayed()
    }

    @Config(qualifiers = "zh-rCN")
    @Test
    fun should_showTheChinesePolicyLabel_when_theAppUsesSimplifiedChinese() {
        val opened = mutableListOf<String>()
        showSettings(openUri = opened::add)

        rule.onNodeWithText("隐私政策").performScrollTo().assertIsDisplayed().performClick()

        rule.runOnIdle {
            assertEquals(listOf("https://yoin.gojyuplus.com/privacy"), opened)
        }
    }

    @Test
    fun should_openTheWebsiteAndDocs_when_theirRowsAreTapped() {
        val opened = mutableListOf<String>()
        showSettings(openUri = opened::add)

        rule.onNodeWithText("Website").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText("Docs").performScrollTo().assertIsDisplayed().performClick()

        rule.runOnIdle {
            assertEquals(listOf("https://yoin.gojyuplus.com/", "https://yoin.gojyuplus.com/docs"), opened)
        }
    }

    @Config(qualifiers = "zh-rCN")
    @Test
    fun should_openTheChineseWebsiteAndDocs_when_theAppUsesSimplifiedChinese() {
        val opened = mutableListOf<String>()
        showSettings(openUri = opened::add)

        rule.onNodeWithText("官网").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText("使用文档").performScrollTo().assertIsDisplayed().performClick()

        rule.runOnIdle {
            assertEquals(listOf("https://yoin.gojyuplus.com/zh/", "https://yoin.gojyuplus.com/zh/docs"), opened)
        }
    }

    private fun showSettings(
        snackbarHostState: SnackbarHostState = SnackbarHostState(),
        openUri: (String) -> Unit,
    ) {
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) = openUri.invoke(uri)
        }
        rule.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                YoinTheme {
                    SettingsContent(
                        uiState = SettingsUiState.Content(
                            profileCards = emptyList(),
                            activeProfileId = null,
                            canAddProfile = true,
                        ),
                        switchingState = ProfileManager.SwitchState.Idle,
                        providerPickerVisible = false,
                        deleteConfirmState = DeleteConfirmState.Hidden,
                        onBackClick = {},
                        onSwitchToProfile = {},
                        onOpenService = {},
                        onRequestDeleteProfile = {},
                        onShowProviderPicker = {},
                        onHideProviderPicker = {},
                        onDismissSwitchError = {},
                        onDismissDeleteConfirm = {},
                        onConfirmDeleteProfile = {},
                        onClearCache = {},
                        snackbarHostState = snackbarHostState,
                    )
                }
            }
        }
    }
}
