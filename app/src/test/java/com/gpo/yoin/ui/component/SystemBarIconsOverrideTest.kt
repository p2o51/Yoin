package com.gpo.yoin.ui.component

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SystemBarIconsOverrideTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Config(qualifiers = "night")
    @Test
    fun should_giveTheDarkThemeIconsBack_when_aLightPageLeaves() {
        var showing by mutableStateOf(true)
        rule.setContent {
            if (showing) SystemBarIconsOverride(lightStatusBars = true, lightNavigationBars = true)
        }
        rule.waitForIdle()
        assertEquals(true to true, bars())

        showing = false
        rule.waitForIdle()

        assertEquals(false to false, bars())
    }

    @Test
    fun should_giveTheLightThemeIconsBack_when_aDarkPageLeaves() {
        var showing by mutableStateOf(true)
        rule.setContent {
            if (showing) SystemBarIconsOverride(lightStatusBars = false, lightNavigationBars = false)
        }
        rule.waitForIdle()
        assertEquals(false to false, bars())

        showing = false
        rule.waitForIdle()

        assertEquals(true to true, bars())
    }

    private fun bars(): Pair<Boolean, Boolean> {
        val window = rule.activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        return controller.isAppearanceLightStatusBars to controller.isAppearanceLightNavigationBars
    }
}
