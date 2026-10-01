package com.gpo.yoin.ui.service

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.source.ServiceFeatureCatalog
import com.gpo.yoin.player.CastState
import com.gpo.yoin.ui.nowplaying.BottomPills
import com.gpo.yoin.ui.sampleSettingsState
import com.gpo.yoin.ui.settings.DeleteConfirmState
import com.gpo.yoin.ui.settings.SettingsContent
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ServiceFeaturesTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_omitServiceExplanationUI_when_openingSettings() {
        var switches = 0
        var connections = 0
        var edits = 0
        rule.setContent {
            YoinTheme {
                SettingsContent(
                    uiState = sampleSettingsState(),
                    switchingState = ProfileManager.SwitchState.Idle,
                    providerPickerVisible = false,
                    deleteConfirmState = DeleteConfirmState.Hidden,
                    onBackClick = {},
                    onSwitchToProfile = { switches++ },
                    onOpenService = { edits++ },
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
        rule.onNodeWithText("Compare services").assertDoesNotExist()
        rule.onNodeWithText("View features").assertDoesNotExist()
        rule.onNodeWithTag("compare_service_applemusic").assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(0, switches)
            assertEquals(0, connections)
            assertEquals(0, edits)
        }
    }

    @Test
    fun should_hideYoinCastForSpotify_when_castDeviceIsAvailable() {
        rule.setContent {
            YoinTheme {
                BottomPills(
                    onQueueClick = {},
                    onDevicesClick = {},
                    onWriteClick = {},
                    castState = CastState.Available,
                    supportsYoinCast = ServiceFeatureCatalog.spotify.supportsYoinCast
                )
            }
        }
        rule.onNodeWithContentDescription("Cast to device").assertDoesNotExist()
        rule.onNodeWithText("Devices").assertIsDisplayed()
    }

    @Test
    fun should_keepYoinCastForSubsonic_when_castDeviceIsAvailable() {
        rule.setContent {
            YoinTheme {
                BottomPills(
                    onQueueClick = {},
                    onDevicesClick = {},
                    onWriteClick = {},
                    castState = CastState.Available,
                    supportsYoinCast = ServiceFeatureCatalog.subsonic.supportsYoinCast
                )
            }
        }
        rule.onNodeWithContentDescription("Cast to device").assertIsDisplayed()
    }
}
