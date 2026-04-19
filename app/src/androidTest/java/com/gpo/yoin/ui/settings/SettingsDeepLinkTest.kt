package com.gpo.yoin.ui.settings

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.ui.sampleSettingsState
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Rule
import org.junit.Test

class SettingsDeepLinkTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun spotify_focus_section_requests_focus_for_client_id_field() {
        rule.setContent {
            YoinTheme {
                SettingsContent(
                    uiState = sampleSettingsState(),
                    switchingState = ProfileManager.SwitchState.Idle,
                    profileFormSheet = ProfileFormSheet.Hidden,
                    providerPickerVisible = false,
                    deleteConfirmState = DeleteConfirmState.Hidden,
                    focusSection = "spotify",
                    onBackClick = {},
                    onSwitchToProfile = {},
                    onEditProfile = {},
                    onRequestDeleteProfile = {},
                    onReconnectProfile = {},
                    onShowProviderPicker = {},
                    onHideProviderPicker = {},
                    onPickProvider = { _: ProviderKind -> },
                    onCloseFormSheet = {},
                    onTestConnection = { _, _, _ -> },
                    onSaveProfile = { _, _, _ -> },
                    onDismissSwitchError = {},
                    onDismissDeleteConfirm = {},
                    onConfirmDeleteProfile = {},
                    onClearCache = {},
                )
            }
        }

        rule.waitForIdle()
        rule.onNodeWithTag("spotify_client_id_field", useUnmergedTree = true).assertIsFocused()
    }
}
