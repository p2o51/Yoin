package com.gpo.yoin.ui.home

import app.cash.turbine.test
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.testutil.MainDispatcherRule
import com.gpo.yoin.ui.settings.ProfileCard
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The switch's target as Home shows it (T7): pending from the tap's own frame
 * until the switch is over — landed and in the cards, failed, or turned away.
 */
class AccountSwitcherViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val active = MutableStateFlow<String?>("a")
    private val state = MutableStateFlow<ProfileManager.SwitchState>(ProfileManager.SwitchState.Idle)
    private val cards = MutableStateFlow(cardsWithActive("a"))

    // The switch ProfileManager runs: held open until the test lets it end.
    private val outcome = CompletableDeferred<() -> Unit>()
    private val profiles = mockk<ProfileManager>().also { manager ->
        every { manager.activeProfileId } returns active
        every { manager.switchingState } returns state
        every { manager.acknowledgeSwitchError() } answers { state.value = ProfileManager.SwitchState.Idle }
        coEvery { manager.switchTo(any()) } coAnswers {
            state.value = ProfileManager.SwitchState.Switching(firstArg(), ProfileManager.SwitchState.Stage.Priming)
            outcome.await().invoke()
        }
    }

    private fun viewModel() = AccountSwitcherViewModel(profiles, cards)

    private fun land(id: String) {
        outcome.complete {
            active.value = id
            state.value = ProfileManager.SwitchState.Idle
        }
    }

    @Test
    fun should_pendTheTargetAtOnce_when_aSwitchBegins() {
        val vm = viewModel()

        assertTrue(vm.switchTo("b"))

        // Before the switch has got anywhere: the tap's own frame shows it.
        assertEquals("b", vm.pendingTarget.value)
    }

    @Test
    fun should_holdTheTargetUntilTheCardsHaveItInUse_when_theSwitchLands() = runTest {
        val vm = viewModel()
        vm.cards.test {
            skipItems(1)
            vm.switchTo("b")

            land("b")

            // In use, but the cards still have the old account: the header
            // mustn't blink back to it.
            assertEquals("b", active.value)
            assertEquals("b", vm.pendingTarget.value)

            cards.value = cardsWithActive("b")
            awaitItem()
            assertNull(vm.pendingTarget.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun should_dropTheTargetAndReportOnce_when_theSwitchFails() = runTest {
        val vm = viewModel()
        vm.switchFailures.test {
            vm.switchTo("b")

            outcome.complete { state.value = ProfileManager.SwitchState.Error("b", "Server did not respond within 8s") }

            awaitItem()
            assertNull(vm.pendingTarget.value)
            // Back to the account in use: Home's header springs back to it.
            assertEquals("a", active.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun should_dropTheTarget_when_theSwitchIsTurnedAway() = runTest {
        // A switch already under way (begun in Settings) turns this one away:
        // switchingState never moves for it.
        val other = ProfileManager.SwitchState.Switching("c", ProfileManager.SwitchState.Stage.Priming)
        state.value = other
        coEvery { profiles.switchTo("b") } coAnswers { }
        val vm = viewModel()

        vm.switchTo("b")

        assertNull(vm.pendingTarget.value)
        assertEquals(other, state.value)
    }

    @Test
    fun should_leaveOtherAccountsAlone_when_aSwitchIsPending() {
        val vm = viewModel()
        vm.switchTo("b")

        assertFalse(vm.switchTo("c"))

        assertEquals("b", vm.pendingTarget.value)
        coVerify(exactly = 0) { profiles.switchTo("c") }
    }

    @Test
    fun should_takeTheNextPick_when_theLastSwitchIsOver() = runTest {
        val vm = viewModel()
        vm.switchTo("b")
        outcome.complete { state.value = ProfileManager.SwitchState.Error("b", "boom") }
        assertNull(vm.pendingTarget.value)

        coEvery { profiles.switchTo("c") } coAnswers { }

        assertTrue(vm.switchTo("c"))
    }

    @Test
    fun should_startNothing_when_theAccountIsInUse() {
        val vm = viewModel()

        assertFalse(vm.switchTo("a"))

        assertNull(vm.pendingTarget.value)
        coVerify(exactly = 0) { profiles.switchTo(any()) }
    }

    private fun cardsWithActive(id: String) = listOf("a", "b", "c").map { profileId ->
        ProfileCard(
            id = profileId,
            displayName = profileId,
            subtitle = null,
            provider = ProviderKind.SUBSONIC,
            isActive = profileId == id
        )
    }
}
