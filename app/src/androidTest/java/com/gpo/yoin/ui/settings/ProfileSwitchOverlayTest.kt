package com.gpo.yoin.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.ui.sampleSettingsState
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Rule
import org.junit.Test

class ProfileSwitchOverlayTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun switching_state_shows_blocking_overlay_copy() {
        rule.setContent {
            YoinTheme {
                SettingsContent(
                    uiState = sampleSettingsState(),
                    switchingState = ProfileManager.SwitchState.Switching(
                        profileId = "spotify-profile",
                        stage = ProfileManager.SwitchState.Stage.Connecting,
                    ),
                    profileFormSheet = ProfileFormSheet.Hidden,
                    providerPickerVisible = false,
                    deleteConfirmState = DeleteConfirmState.Hidden,
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

        rule.onNodeWithText("Switching profile").assertIsDisplayed()
        rule.onNodeWithText("Connecting to Jazz Server…").assertIsDisplayed()
    }
}
