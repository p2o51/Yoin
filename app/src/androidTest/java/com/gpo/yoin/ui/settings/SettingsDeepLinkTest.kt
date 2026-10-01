package com.gpo.yoin.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.gpo.yoin.ui.settings.service.ServiceSetupContent
import com.gpo.yoin.ui.settings.service.ServiceSetupUiState
import com.gpo.yoin.ui.settings.service.SetupService
import com.gpo.yoin.ui.settings.service.SpotifySetupState
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Rule
import org.junit.Test

class SettingsDeepLinkTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_focusClientIdField_when_spotifySetupOpenedFromDeepLink() {
        rule.setContent {
            YoinTheme {
                ServiceSetupContent(
                    state = ServiceSetupUiState(
                        service = SetupService.Spotify,
                        isManaging = true,
                        existingProfileName = "Jazz Server",
                        spotify = SpotifySetupState(clientId = "abc"),
                    ),
                    focusClientId = true,
                    onBackClick = {},
                )
            }
        }

        rule.waitForIdle()
        // The tag belongs to ExpressiveTextField's wrapper; focus belongs to BasicTextField.
        rule.onNode(
            hasSetTextAction() and hasAnyAncestor(hasTestTag("spotify_client_id_field")),
            useUnmergedTree = true,
        ).assertIsFocused()
    }

    @Test
    fun should_pitchServiceBeforeConnecting_when_addingSpotify() {
        rule.setContent {
            YoinTheme {
                ServiceSetupContent(
                    state = ServiceSetupUiState(service = SetupService.Spotify, isManaging = false),
                    onBackClick = {},
                )
            }
        }

        rule.onNodeWithText("What you get").assertIsDisplayed()
        rule.onNodeWithText("Spotify Premium").assertIsDisplayed()
        // No Client ID yet: the connect button waits for developer setup.
        rule.onNodeWithTag("spotify_connect").assertExists()
    }
}
