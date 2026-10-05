package com.gpo.yoin.ui.component

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * When a card's cover read runs. A colour is not motion (owner, 2026-10-05): adaptive motion
 * pressure / reduced motion never holds the read back — before, a Home card composed under pressure
 * wore the theme's colours. Only the caller's scroll gate (`enabled`) defers it, and that gate
 * reopening reads the cover once.
 */
@RunWith(RobolectricTestRunner::class)
class ExpressiveBackdropDeferralTest {

    @get:Rule
    val rule = createComposeRule()

    private val extracted = ExpressiveBackdropColors(
        baseColor = Color(0xFF2A4A9A),
        accentColor = Color(0xFF8AA8F0),
        isResolvedFromPalette = true,
    )

    @Test
    fun should_readCoverAtOnce_when_motionProfileIsAdaptiveReduced() {
        val model = "test://backdrop/reduced/${System.nanoTime()}"
        var profile by mutableStateOf(MotionProfile.AdaptiveReduced)
        var reads = 0
        var colors: ExpressiveBackdropColors? = null
        rule.setContent {
            CompositionLocalProvider(LocalMotionProfile provides profile) {
                colors = rememberBackdropColorsLoadedBy(
                    model = model,
                    fallbackBaseColor = Color.Gray,
                    fallbackAccentColor = Color.LightGray,
                    enabled = true,
                ) {
                    reads++
                    extracted
                }
            }
        }
        rule.waitForIdle()
        assertEquals(1, reads)
        assertTrue(colors!!.isResolvedFromPalette)
        assertEquals(extracted, cachedBackdropColors(model))

        // The profile coming and going is no reason to read again.
        profile = MotionProfile.Full
        rule.waitForIdle()
        profile = MotionProfile.AdaptiveReduced
        rule.waitForIdle()
        assertEquals(1, reads)
        assertTrue(colors!!.isResolvedFromPalette)
    }

    @Test
    fun should_readCoverOnce_when_scrollGateReopens() {
        val model = "test://backdrop/gate/${System.nanoTime()}"
        var enabled by mutableStateOf(false)
        var reads = 0
        var colors: ExpressiveBackdropColors? = null
        rule.setContent {
            CompositionLocalProvider(LocalMotionProfile provides MotionProfile.AdaptiveReduced) {
                colors = rememberBackdropColorsLoadedBy(
                    model = model,
                    fallbackBaseColor = Color.Gray,
                    fallbackAccentColor = Color.LightGray,
                    enabled = enabled,
                ) {
                    reads++
                    extracted
                }
            }
        }
        rule.waitForIdle()
        assertEquals(0, reads)
        assertFalse(colors!!.isResolvedFromPalette)

        enabled = true
        rule.waitForIdle()
        assertEquals(1, reads)
        assertTrue(colors!!.isResolvedFromPalette)

        // A later scroll closes and reopens the gate: the cached colours stand, no second decode.
        enabled = false
        rule.waitForIdle()
        enabled = true
        rule.waitForIdle()
        assertEquals(1, reads)
        assertTrue(colors!!.isResolvedFromPalette)
    }
}
