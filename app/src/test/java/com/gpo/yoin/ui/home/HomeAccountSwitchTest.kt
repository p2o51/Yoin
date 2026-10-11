package com.gpo.yoin.ui.home

import com.gpo.yoin.data.profile.ProfileManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeAccountSwitchTest {

    private val active = MutableStateFlow<String?>("a")
    private val state = MutableStateFlow<ProfileManager.SwitchState>(ProfileManager.SwitchState.Idle)
    private val profiles = mockk<ProfileManager>().also { manager ->
        every { manager.activeProfileId } returns active
        every { manager.switchingState } returns state
        every { manager.acknowledgeSwitchError() } answers { state.value = ProfileManager.SwitchState.Idle }
    }

    @Test
    fun should_reportAndAcknowledgeFailure_when_switchFromHomeFails() = runTest {
        // Home tells the failure, so Settings mustn't open on it later.
        coEvery { profiles.switchTo("b") } answers {
            state.value = ProfileManager.SwitchState.Error("b", "Server did not respond within 8s")
        }

        assertEquals(HomeSwitchOutcome.Failed, profiles.switchFromHome("b"))
        verify(exactly = 1) { profiles.acknowledgeSwitchError() }
        assertEquals(ProfileManager.SwitchState.Idle, state.value)
    }

    @Test
    fun should_reportALanding_when_switchFromHomeCommits() = runTest {
        coEvery { profiles.switchTo("b") } answers {
            active.value = "b"
            state.value = ProfileManager.SwitchState.Idle
        }

        assertEquals(HomeSwitchOutcome.Landed, profiles.switchFromHome("b"))
        verify(exactly = 0) { profiles.acknowledgeSwitchError() }
    }

    @Test
    fun should_leaveAnotherSwitchAlone_when_switchFromHomeIsTurnedAway() = runTest {
        // A switch already in flight (begun in Settings) turns this one away.
        val other = ProfileManager.SwitchState.Switching("c", ProfileManager.SwitchState.Stage.Priming)
        state.value = other
        coEvery { profiles.switchTo("b") } answers { }

        assertEquals(HomeSwitchOutcome.TurnedAway, profiles.switchFromHome("b"))
        verify(exactly = 0) { profiles.acknowledgeSwitchError() }
        assertEquals(other, state.value)
    }

    @Test
    fun should_reportItTurnedAway_when_theAccountIsGone() = runTest {
        // ProfileManager finds no such profile (deleted meanwhile): nothing runs.
        coEvery { profiles.switchTo("b") } answers { }

        assertEquals(HomeSwitchOutcome.TurnedAway, profiles.switchFromHome("b"))
        assertEquals(ProfileManager.SwitchState.Idle, state.value)
    }

    @Test
    fun should_waitOutTheSameSwitch_when_itWasBegunElsewhere() = runTest {
        // Settings is already switching to "b": Home's pick of "b" is turned
        // away, but it is the same switch — its landing is Home's too.
        state.value = ProfileManager.SwitchState.Switching("b", ProfileManager.SwitchState.Stage.Priming)
        coEvery { profiles.switchTo("b") } answers { }

        val outcome = async { profiles.switchFromHome("b") }
        runCurrent()
        assertFalse(outcome.isCompleted)

        active.value = "b"
        state.value = ProfileManager.SwitchState.Idle

        assertEquals(HomeSwitchOutcome.Landed, outcome.await())
        verify(exactly = 0) { profiles.acknowledgeSwitchError() }
    }
}
