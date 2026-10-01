package com.gpo.yoin.ui.detail

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.ui.experience.DetailBackPhase
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DetailBackOwnershipTest {
    @get:Rule val rule = createComposeRule()

    @Test fun should_leaveShellPoseUntouched_whenNestedDetailCommitsBack() {
        val store = ApplicationProvider.getApplicationContext<YoinApplication>()
            .container.experienceSessionStore
        store.setDetailChromeActive(true)
        store.detailBackPhase.value = DetailBackPhase.Idle
        store.detailBackProgress.floatValue = 0f
        lateinit var dispatcher: OnBackPressedDispatcher
        lateinit var back: DetailBackCollapseState
        var finishes = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            YoinTheme {
                dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                back = rememberDetailBackCollapse(onBack = { finishes++ }, bridgeToShell = false)
            }
        }
        rule.runOnIdle { dispatcher.onBackPressed() }
        rule.mainClock.advanceTimeBy(48)
        rule.runOnIdle {
            assertEquals(0, finishes)
            assertEquals(DetailBackPhase.Idle, store.detailBackPhase.value)
            assertEquals(0f, store.detailBackProgress.floatValue)
            assertTrue(store.state.value.detailChromeActive)
            dispatcher.onBackPressed()
            assertEquals(0, finishes)
        }
        rule.mainClock.advanceTimeBy(1_500)
        rule.runOnIdle {
            assertEquals(1, finishes)
            assertEquals(1f, back.progress, 0.001f)
            assertEquals(1f, back.exit.value, 0.001f)
            assertEquals(DetailBackPhase.Idle, store.detailBackPhase.value)
            assertTrue(store.state.value.detailChromeActive)
            store.setDetailChromeActive(false)
        }
    }
}
