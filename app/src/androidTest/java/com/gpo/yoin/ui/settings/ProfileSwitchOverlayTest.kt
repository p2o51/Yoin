package com.gpo.yoin.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.gpo.yoin.data.profile.ProfileManager
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
                )
            }
        }

        rule.onNodeWithText("Switching account").assertIsDisplayed()
        rule.onNodeWithText("Connecting to Jazz Server…").assertIsDisplayed()
    }
}
