package com.gpo.yoin.ui.settings.service

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The account page's Subsonic fields take the stored credentials in as they
 * arrive, whatever the page's first frame said — a loaded, empty form kept
 * them out before (ACC-06: the page opened empty, Save off) — and keep what
 * the user typed afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h900dp")
class ServiceSetupSubsonicFormTest {

    @get:Rule
    val rule = createComposeRule()

    private var state by mutableStateOf(managing(SubsonicFormState(loaded = false)))

    private fun setPage() {
        rule.setContent {
            YoinTheme {
                ServiceSetupContent(state = state, onBackClick = {})
            }
        }
        rule.waitForIdle()
    }

    private fun field(text: String) = rule.onNode(hasSetTextAction() and hasText(text))

    private fun save() = rule.onNodeWithTag("subsonic_connect")

    @Test
    fun should_fillTheFieldsAndAllowSave_when_theStoredCredentialsArriveAfterALoadedEmptyFirstFrame() {
        // The first frame a loaded form with nothing in it (what the page used to get).
        state = managing(SubsonicFormState())
        setPage()
        save().assertIsNotEnabled()

        state = managing(stored())
        rule.waitForIdle()

        field(SERVER).assertExists()
        field(USERNAME).assertExists()
        save().assertIsEnabled()
    }

    @Test
    fun should_fillTheFieldsAndAllowSave_when_theFormTurnsLoaded() {
        setPage()
        save().assertIsNotEnabled()

        state = managing(stored())
        rule.waitForIdle()

        field(SERVER).assertExists()
        field(USERNAME).assertExists()
        save().assertIsEnabled()
    }

    @Test
    fun should_keepWhatWasTyped_when_aConnectionTestReports() {
        state = managing(stored())
        setPage()

        field(USERNAME).performTextReplacement("qa2")
        state = managing(stored().copy(status = SubsonicStatus.Reachable))
        rule.waitForIdle()

        field("qa2").assertExists()
        field(USERNAME).assertDoesNotExist()
    }

    private fun stored() = SubsonicFormState(
        initialUrl = SERVER,
        initialUsername = USERNAME,
        initialPassword = "pw",
        loaded = true
    )

    private fun managing(form: SubsonicFormState) = ServiceSetupUiState(
        service = SetupService.Subsonic,
        isManaging = true,
        account = AccountFace(title = USERNAME, detail = "127.0.0.1"),
        subsonic = form
    )

    private companion object {
        const val SERVER = "http://127.0.0.1:4933"
        const val USERNAME = "qa"
    }
}
