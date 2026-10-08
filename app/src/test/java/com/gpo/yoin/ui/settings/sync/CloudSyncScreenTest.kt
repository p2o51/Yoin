package com.gpo.yoin.ui.settings.sync

import android.app.Activity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.sync.CloudSyncPhase
import com.gpo.yoin.data.sync.CloudSyncState
import com.gpo.yoin.ui.settings.service.ServiceSetupActivity
import com.gpo.yoin.ui.settings.service.ServiceSetupContract
import com.gpo.yoin.ui.settings.service.ServiceSetupRequest
import com.gpo.yoin.ui.settings.service.SetupService
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CloudSyncScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val now = System.currentTimeMillis()

    private fun showScreen(state: CloudSyncState): FakeCloudSyncController {
        val fake = FakeCloudSyncController(initial = state)
        val viewModel = CloudSyncViewModel(fake, CoroutineScope(Dispatchers.Unconfined))
        rule.setContent {
            YoinTheme {
                CloudSyncScreen(viewModel = viewModel, onBack = {})
            }
        }
        return fake
    }

    @Test
    fun should_callBeginTurnOn_when_turnOnWithGoogleTapped() {
        val fake = showScreen(CloudSyncSamples.off)

        rule.onNodeWithText("Turn on with Google").performScrollTo().assertIsDisplayed().performClick()
        rule.waitForIdle()

        assertEquals(listOf("beginTurnOn"), fake.calls)
    }

    @Test
    fun should_callResolveReviewTrue_when_restoreHereTapped() {
        val fake = showScreen(CloudSyncSamples.needsReview(now))

        rule.onNodeWithText("3 notes disappeared on this device").performScrollTo().performClick()
        rule.onNodeWithText("Restore here").performClick()
        rule.waitForIdle()

        assertEquals(listOf("resolveReview(true)"), fake.calls)
    }

    @Test
    fun should_callResolveReviewFalse_when_deleteOnAllDevicesTapped() {
        val fake = showScreen(CloudSyncSamples.needsReview(now))

        rule.onNodeWithText("3 notes disappeared on this device").performScrollTo().performClick()
        rule.onNodeWithText("Delete on all devices").performClick()
        rule.waitForIdle()

        assertEquals(listOf("resolveReview(false)"), fake.calls)
    }

    @Test
    fun should_callDeleteCloudData_when_deleteDialogConfirmed() {
        val fake = showScreen(CloudSyncSamples.upToDate(now))

        rule.onNodeWithText("Delete cloud data…").performScrollTo().performClick()
        rule.onNodeWithText("Delete cloud data?").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()

        assertEquals(listOf("deleteCloudData"), fake.calls)
    }

    @Test
    fun should_notDelete_when_deleteDialogCancelled() {
        val fake = showScreen(CloudSyncSamples.upToDate(now))

        rule.onNodeWithText("Delete cloud data…").performScrollTo().performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()

        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun should_showBuildDetails_when_misconfigured() {
        showScreen(CloudSyncSamples.misconfigured)

        rule.onNodeWithText("Cloud sync isn't available in this build of Yoin.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Turn on with Google").assertDoesNotExist()
    }

    @Test
    fun should_showSyncedTime_when_entryRowIsUpToDate() {
        val state = CloudSyncState(phase = CloudSyncPhase.UpToDate, lastSyncAt = now - 2 * 60_000L)
        rule.setContent {
            YoinTheme {
                CloudSyncEntryRow(state = state, onClick = {}, now = now)
            }
        }

        rule.onNodeWithText("Cloud sync").assertIsDisplayed()
        rule.onNodeWithText("On").assertIsDisplayed()
        rule.onNodeWithText("Synced 2 min ago").assertIsDisplayed()
    }

    @Test
    fun should_askToReconnect_when_entryRowNeedsReauth() {
        rule.setContent {
            YoinTheme {
                CloudSyncEntryRow(state = CloudSyncSamples.needsReauth(now), onClick = {}, now = now)
            }
        }

        rule.onNodeWithText("Paused").assertIsDisplayed()
        rule.onNodeWithText("Reconnect Google").assertIsDisplayed()
    }

    @Test
    fun should_notReopenReviewDialog_when_theReviewWasSettledMeanwhile() {
        val fake = showScreen(CloudSyncSamples.needsReview(now))
        rule.onNodeWithText("3 notes disappeared on this device").performScrollTo().performClick()
        rule.onNodeWithText("Restore here").assertIsDisplayed()

        fake.setState(CloudSyncSamples.upToDate(now))
        rule.waitForIdle()
        rule.onNodeWithText("Restore here").assertDoesNotExist()

        // A later review must wait for the user to open it.
        fake.setState(CloudSyncSamples.needsReview(now))
        rule.waitForIdle()
        rule.onNodeWithText("Restore here").assertDoesNotExist()
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun should_keepReviewDialogOpen_when_aSyncRunsWhileDeciding() {
        val fake = showScreen(CloudSyncSamples.needsReview(now))
        rule.onNodeWithText("3 notes disappeared on this device").performScrollTo().performClick()

        fake.setState(CloudSyncSamples.syncing(now))
        rule.waitForIdle()
        rule.onNodeWithText("Delete on all devices").assertIsDisplayed().performClick()
        rule.waitForIdle()

        assertEquals(listOf("resolveReview(false)"), fake.calls)
    }

    @Test
    fun should_dropStartFreshDialog_when_theAccountIsNoLongerChanged() {
        val fake = showScreen(CloudSyncSamples.accountChanged(now))
        rule.onNodeWithText("Start fresh").performScrollTo().performClick()
        rule.onNodeWithText("Start fresh as this account?").assertIsDisplayed()

        // Settled elsewhere (e.g. "Keep syncing as before" won the race): p1 syncs again.
        fake.setState(CloudSyncSamples.upToDate(now))
        rule.waitForIdle()

        rule.onNodeWithText("Start fresh as this account?").assertDoesNotExist()
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun should_notReopenDeleteDialog_when_syncTurnedOffMeanwhile() {
        val fake = showScreen(CloudSyncSamples.upToDate(now))
        rule.onNodeWithText("Delete cloud data…").performScrollTo().performClick()
        rule.onNodeWithText("Delete cloud data?").assertIsDisplayed()

        fake.setState(CloudSyncSamples.resetElsewhere)
        rule.waitForIdle()
        rule.onNodeWithText("Delete cloud data?").assertDoesNotExist()

        fake.setState(CloudSyncSamples.upToDate(now))
        rule.waitForIdle()
        rule.onNodeWithText("Delete cloud data?").assertDoesNotExist()
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun should_addWithOnlyTheService_when_addTappedOnADriveAccount() {
        var added: SetupService? = null
        rule.setContent {
            YoinTheme {
                CloudSyncContent(
                    state = CloudSyncSamples.upToDate(now),
                    onBackClick = {},
                    now = now,
                    onAddCloudAccount = { added = it },
                )
            }
        }

        rule.onNodeWithText("Add").performScrollTo().performClick()

        assertEquals(SetupService.Subsonic, added)
    }

    @Test
    fun should_reportSaved_when_theSetupPageFinishesWithoutAProfileToSwitchTo() {
        val contract = AddAccountContract()
        val intent = contract.createIntent(
            ApplicationProvider.getApplicationContext(),
            ServiceSetupRequest(SetupService.AppleMusic),
        )

        assertEquals(ServiceSetupActivity::class.java.name, intent.component?.className)
        assertEquals("applemusic", intent.getStringExtra(ServiceSetupContract.EXTRA_SERVICE))
        assertEquals(null, intent.getStringExtra(ServiceSetupContract.EXTRA_PROFILE_ID))
        // The very first account comes back RESULT_OK with no profile to switch to.
        assertTrue(contract.parseResult(Activity.RESULT_OK, null))
        assertFalse(contract.parseResult(Activity.RESULT_CANCELED, null))
    }
}
