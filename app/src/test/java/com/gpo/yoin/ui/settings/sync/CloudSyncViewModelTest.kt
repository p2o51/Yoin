package com.gpo.yoin.ui.settings.sync

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import androidx.activity.result.contract.ActivityResultContracts
import app.cash.turbine.test
import com.gpo.yoin.data.sync.CloudSyncController
import com.gpo.yoin.data.sync.CloudSyncPhase
import com.gpo.yoin.data.sync.TurnOnStep
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncViewModelTest {

    private val fake = FakeCloudSyncController()
    private val viewModel = CloudSyncViewModel(fake, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun should_passControllerStateThrough_when_controllerChanges() {
        fake.setState(CloudSyncSamples.needsReauth(now = 0L))
        assertEquals(CloudSyncPhase.NeedsReauth, viewModel.state.value.phase)
    }

    @Test
    fun should_turnOnQuietly_when_controllerStarts() = runTest {
        viewModel.events.test {
            viewModel.turnOn()
            expectNoEvents()
        }
        assertEquals(listOf("beginTurnOn"), fake.calls)
        assertFalse(viewModel.turningOn.value)
        assertTrue(viewModel.state.value.enabled)
    }

    @Test
    fun should_showMessage_when_turnOnFails() = runTest {
        fake.beginTurnOnResult = TurnOnStep.Failed("Couldn't reach Google")
        viewModel.events.test {
            viewModel.turnOn()
            assertEquals(CloudSyncEvent.ShowMessage("Couldn't reach Google"), awaitItem())
        }
        assertFalse(viewModel.turningOn.value)
    }

    @Test
    fun should_sayNothing_when_userCancelsTheGoogleSheet() = runTest {
        fake.completeTurnOnResult = TurnOnStep.Canceled
        viewModel.events.test {
            viewModel.onConsentResult(resultCode = 0, data = null)
            expectNoEvents()
        }
        assertEquals(listOf("completeTurnOn(0)"), fake.calls)
        assertFalse(viewModel.turningOn.value)
        assertFalse(viewModel.state.value.enabled)
    }

    @Test
    fun should_reportFailure_when_deleteCloudDataFails() = runTest {
        fake.setState(CloudSyncSamples.upToDate(now = 0L))
        fake.deleteCloudDataResult = Result.failure(IllegalStateException("offline"))
        viewModel.events.test {
            viewModel.deleteCloudData()
            val event = awaitItem() as CloudSyncEvent.ShowMessage
            assertTrue(event.message.startsWith("Couldn't delete"))
        }
        assertEquals(listOf("deleteCloudData"), fake.calls)
        assertFalse(viewModel.deleting.value)
        assertTrue(viewModel.state.value.enabled)
    }

    @Test
    fun should_forwardActions_when_called() {
        viewModel.resolveReview(true)
        viewModel.startSyncingAccount("p3")
        viewModel.linkAccount("p3", "a-7f3c")
        viewModel.keepAccountAsBefore("p1")
        viewModel.startFreshAccount("p1")
        viewModel.removeDevice("d-phone")
        viewModel.uploadAgain()
        viewModel.turnOff()
        assertEquals(
            listOf(
                "resolveReview(true)",
                "startSyncingAccount(p3)",
                "linkAccount(p3,a-7f3c)",
                "keepAccountAsBefore(p1)",
                "startFreshAccount(p1)",
                "removeDevice(d-phone)",
                "uploadAgain",
                "turnOff",
            ),
            fake.calls,
        )
    }

    @Test
    fun should_syncOnlyWhenEnabled_when_anAccountIsAdded() {
        viewModel.onAccountAdded()
        assertTrue(fake.calls.isEmpty())
        fake.setState(CloudSyncSamples.upToDate(now = 0L))
        viewModel.onAccountAdded()
        assertEquals(listOf("syncNow"), fake.calls)
    }

    @Test
    fun should_deliverConsentLaunch_when_thePageStartsCollectingLater() = runTest {
        val sender = mockk<IntentSender>()
        fake.beginTurnOnResult = TurnOnStep.NeedsConsent(sender)

        // Nobody collects yet: the Activity is being recreated, or in the background.
        viewModel.turnOn()

        viewModel.events.test {
            assertEquals(CloudSyncEvent.LaunchConsent(sender), awaitItem())
        }
        assertTrue(viewModel.turningOn.value)
    }

    @Test
    fun should_showMessage_when_theConsentSheetCouldNotBeSent() = runTest {
        fake.completeTurnOnResult = TurnOnStep.Canceled
        val data = mockk<Intent> {
            every { hasExtra(any()) } returns false
            every {
                hasExtra(ActivityResultContracts.StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION)
            } returns true
        }

        viewModel.events.test {
            viewModel.onConsentResult(Activity.RESULT_CANCELED, data)
            assertTrue(awaitItem() is CloudSyncEvent.ShowMessage)
        }
        assertEquals(listOf("completeTurnOn(${Activity.RESULT_CANCELED})"), fake.calls)
        assertFalse(viewModel.turningOn.value)
    }

    @Test
    fun should_showGenericMessage_when_theControllerThrows() = runTest {
        val throwing = object : CloudSyncController by fake {
            override suspend fun beginTurnOn(): TurnOnStep = throw IOException("GET /drive/v3?access_token=secret")
        }
        val vm = CloudSyncViewModel(throwing, CoroutineScope(Dispatchers.Unconfined))

        vm.events.test {
            vm.turnOn()
            val message = (awaitItem() as CloudSyncEvent.ShowMessage).message
            assertFalse(message.contains("secret"))
            assertEquals("Couldn't reach Google. Try again in a moment.", message)
        }
        assertFalse(vm.turningOn.value)
    }
}
