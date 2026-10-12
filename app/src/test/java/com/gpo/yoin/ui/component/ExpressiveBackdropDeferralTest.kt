package com.gpo.yoin.ui.component

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import java.io.IOException
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

    @Test
    fun should_readCoverAgain_when_aFailedReadIsSignalled() {
        // QA P-8a-4: a cover that failed (fail503, then healed) kept its card's
        // wash on the theme's colours until a scroll reopened the gate. The
        // read now retries when failed artwork does: here the signal (network
        // back, app foregrounded), with the gate open all along.
        val model = "test://backdrop/signal/${System.nanoTime()}"
        var reads = 0
        var colors: ExpressiveBackdropColors? = null
        rule.setContent {
            colors = rememberBackdropColorsLoadedBy(
                model = model,
                fallbackBaseColor = Color.Gray,
                fallbackAccentColor = Color.LightGray,
                enabled = true
            ) {
                reads++
                if (reads == 1) throw BackdropReadException(IllegalStateException("no image"))
                extracted
            }
        }
        rule.waitForIdle()
        assertEquals(1, reads)
        assertFalse(colors!!.isResolvedFromPalette)

        rule.runOnIdle { ArtworkRetrySignal.bump() }
        rule.waitForIdle()

        assertEquals(2, reads)
        assertTrue(colors!!.isResolvedFromPalette)
        assertEquals(extracted, cachedBackdropColors(model))
    }

    @Test
    fun should_readCoverAgainOnTheBackoff_when_theReadFailedTransiently() {
        // A 5xx or a network error retries on the artwork's own backoff, no signal needed.
        val model = "test://backdrop/backoff/${System.nanoTime()}"
        var reads = 0
        var colors: ExpressiveBackdropColors? = null
        rule.mainClock.autoAdvance = false
        rule.setContent {
            colors = rememberBackdropColorsLoadedBy(
                model = model,
                fallbackBaseColor = Color.Gray,
                fallbackAccentColor = Color.LightGray,
                enabled = true
            ) {
                reads++
                if (reads == 1) throw BackdropReadException(IOException("503"))
                extracted
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals(1, reads)

        rule.mainClock.advanceTimeBy(ArtworkRetryPolicy.BackoffMillis.first() + 100)
        rule.waitForIdle()

        assertEquals(2, reads)
        assertTrue(colors!!.isResolvedFromPalette)
    }

    @Test
    fun should_notReadAgain_when_theCoverHasNoColour() {
        // Read fine, nothing to take: that is the cover's answer, not a failure to retry.
        val model = "test://backdrop/none/${System.nanoTime()}"
        var reads = 0
        rule.setContent {
            rememberBackdropColorsLoadedBy(
                model = model,
                fallbackBaseColor = Color.Gray,
                fallbackAccentColor = Color.LightGray,
                enabled = true
            ) {
                reads++
                null
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { ArtworkRetrySignal.bump() }
        rule.waitForIdle()

        assertEquals(1, reads)
    }
}
