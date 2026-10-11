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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
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
 * And Reconnect (owner 2026-10-11): the dot follows ProfileManager's signal;
 * a retry that fixes the account clears it, one that doesn't opens its page.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountSwitcherViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val active = MutableStateFlow<String?>("a")
    private val state = MutableStateFlow<ProfileManager.SwitchState>(ProfileManager.SwitchState.Idle)
    private val cards = MutableStateFlow(cardsWithActive("a"))

    // ProfileManager.activeNeedsReconnect, as the VM gets it.
    private val needsReconnect = MutableStateFlow(false)

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

    private fun viewModel() = AccountSwitcherViewModel(profiles, cards, needsReconnect)

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
            // Once: the snackbar says it one time.
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun should_dropTheTargetAndSayItCouldNotSwitch_when_theSwitchIsTurnedAway() = runTest {
        // A switch already under way (begun in Settings) turns this one away:
        // switchingState never moves for it.
        val other = ProfileManager.SwitchState.Switching("c", ProfileManager.SwitchState.Stage.Priming)
        state.value = other
        coEvery { profiles.switchTo("b") } coAnswers { }
        val vm = viewModel()
        vm.switchFailures.test {
            vm.switchTo("b")

            // Not swallowed: Home says it couldn't switch, as for a failure.
            awaitItem()
            expectNoEvents()
            assertNull(vm.pendingTarget.value)
            assertEquals(other, state.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun should_sayItCouldNotSwitch_when_theAccountIsGone() = runTest {
        // Deleted in Settings while Home's card was up: ProfileManager finds
        // no such profile and runs nothing.
        coEvery { profiles.switchTo("b") } coAnswers { }
        val vm = viewModel()
        vm.switchFailures.test {
            vm.switchTo("b")

            awaitItem()
            assertNull(vm.pendingTarget.value)
            assertEquals(ProfileManager.SwitchState.Idle, state.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun should_reportNothing_when_theSwitchLands() = runTest {
        val vm = viewModel()
        vm.switchFailures.test {
            vm.switchTo("b")
            land("b")
            cards.value = cardsWithActive("b")

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
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

    @Test
    fun should_followProfileManager_when_theAccountInUseNeedsReconnectOrNot() = runTest {
        val vm = viewModel()
        subscribe(vm)
        assertFalse(vm.needsReconnect.value)

        needsReconnect.value = true
        assertTrue(vm.needsReconnect.value)

        needsReconnect.value = false
        assertFalse(vm.needsReconnect.value)
    }

    @Test
    fun should_clearTheDotAndCloseTheCardWithoutAPage_when_theRetryFixesTheAccount() = runTest {
        needsReconnect.value = true
        val retry = CompletableDeferred<Unit>()
        coEvery { profiles.retryActiveSource() } coAnswers {
            retry.await()
            // ProfileManager clears the signal before it answers.
            needsReconnect.value = false
            true
        }
        val vm = viewModel()
        val outcomes = subscribe(vm)

        assertTrue(vm.reconnect())
        // Under way: the card waits, as through a switch.
        assertTrue(vm.reconnecting.value)

        retry.complete(Unit)
        runCurrent()

        assertFalse(vm.reconnecting.value)
        assertFalse(vm.needsReconnect.value)
        // The card goes back into the avatar; no page.
        assertEquals(listOf(ReconnectOutcome.Fixed), outcomes)
        // Not a switch: the account stays the one in use.
        coVerify(exactly = 0) { profiles.switchTo(any()) }
        assertEquals("a", active.value)
    }

    @Test
    fun should_openTheAccountsPage_when_theRetryCannotFixIt() = runTest {
        needsReconnect.value = true
        coEvery { profiles.retryActiveSource() } returns false
        val vm = viewModel()
        val outcomes = subscribe(vm)

        vm.reconnect()
        runCurrent()

        assertEquals(listOf("a"), outcomes.map { (it as ReconnectOutcome.OpenPage).account.id })
        assertTrue(vm.needsReconnect.value)
        assertFalse(vm.reconnecting.value)
    }

    @Test
    fun should_openNoPage_when_anotherAccountIsInUseByTheTimeTheRetryFails() = runTest {
        needsReconnect.value = true
        coEvery { profiles.retryActiveSource() } coAnswers {
            // Switched in Settings meanwhile.
            active.value = "b"
            false
        }
        val vm = viewModel()
        val outcomes = subscribe(vm)

        vm.reconnect()
        runCurrent()

        assertTrue(outcomes.isEmpty())
        assertFalse(vm.reconnecting.value)
    }

    @Test
    fun should_takeNoPickNorSecondReconnect_when_aReconnectIsUnderWay() = runTest {
        needsReconnect.value = true
        val retry = CompletableDeferred<Boolean>()
        coEvery { profiles.retryActiveSource() } coAnswers { retry.await() }
        val vm = viewModel()
        subscribe(vm)

        assertTrue(vm.reconnect())

        assertFalse(vm.reconnect())
        assertFalse(vm.switchTo("b"))
        coVerify(exactly = 1) { profiles.retryActiveSource() }
        coVerify(exactly = 0) { profiles.switchTo(any()) }
        retry.complete(true)
        runCurrent()
        assertFalse(vm.reconnecting.value)
    }

    @Test
    fun should_startNoReconnect_when_aSwitchIsPending() = runTest {
        needsReconnect.value = true
        val vm = viewModel()
        subscribe(vm)
        vm.switchTo("b")

        assertFalse(vm.reconnect())

        assertFalse(vm.reconnecting.value)
        coVerify(exactly = 0) { profiles.retryActiveSource() }
    }

    /** Subscribes as Home does (the cards and the signal), and collects how each Reconnect ended. */
    private fun TestScope.subscribe(vm: AccountSwitcherViewModel): List<ReconnectOutcome> {
        val outcomes = mutableListOf<ReconnectOutcome>()
        val eager = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(eager) { vm.cards.collect {} }
        backgroundScope.launch(eager) { vm.needsReconnect.collect {} }
        backgroundScope.launch(eager) { vm.reconnectOutcomes.collect { outcomes += it } }
        return outcomes
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
