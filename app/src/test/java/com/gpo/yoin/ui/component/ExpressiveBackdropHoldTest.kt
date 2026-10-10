package com.gpo.yoin.ui.component

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A card whose cover changes in place (owner Q16: a Spotify artist's portrait
 * replacing the play's album cover) springs its wash from the old cover's
 * colour straight to the new one's, never through the theme's fallback.
 */
@RunWith(RobolectricTestRunner::class)
class ExpressiveBackdropHoldTest {

    @get:Rule
    val rule = createComposeRule()

    private val albumColors = ExpressiveBackdropColors(
        baseColor = Color(0xFF2A4A9A),
        accentColor = Color(0xFF8AA8F0),
        isResolvedFromPalette = true
    )
    private val portraitColors = ExpressiveBackdropColors(
        baseColor = Color(0xFF9A2A4A),
        accentColor = Color(0xFFF08AA8),
        isResolvedFromPalette = true
    )
    private val reads = mutableMapOf<String, CompletableDeferred<ExpressiveBackdropColors?>>()

    private var model by mutableStateOf<String?>(null)
    private var colors: ExpressiveBackdropColors? = null

    private fun setCard(holdUntilResolved: Boolean) {
        rule.setContent {
            CompositionLocalProvider(LocalMotionProfile provides MotionProfile.AdaptiveReduced) {
                colors = rememberBackdropColorsLoadedBy(
                    model = model,
                    fallbackBaseColor = Color.Gray,
                    fallbackAccentColor = Color.LightGray,
                    enabled = true,
                    holdUntilResolved = holdUntilResolved
                ) { cover -> reads.getOrPut(cover) { CompletableDeferred() }.await() }
            }
        }
        rule.waitForIdle()
    }

    /** The card on its album cover, resolved. */
    private fun showAlbum(album: String) {
        model = album
        rule.waitForIdle()
        reads.getOrPut(album) { CompletableDeferred() }.complete(albumColors)
        rule.waitForIdle()
        assertSameColor(albumColors.baseColor, colors!!.baseColor)
    }

    // The springs land on the target up to the colour space's rounding.
    private fun assertSameColor(expected: Color, actual: Color) = assertEquals(expected.toArgb(), actual.toArgb())

    @Test
    fun should_keepTheOldColours_when_theNewCoverIsStillBeingRead() {
        val album = "test://hold/album/${System.nanoTime()}"
        val portrait = "test://hold/portrait/${System.nanoTime()}"
        model = null
        setCard(holdUntilResolved = true)
        showAlbum(album)

        model = portrait
        rule.waitForIdle()
        assertSameColor(albumColors.baseColor, colors!!.baseColor)
        assertSameColor(albumColors.accentColor, colors!!.accentColor)

        reads.getOrPut(portrait) { CompletableDeferred() }.complete(portraitColors)
        rule.waitForIdle()
        assertSameColor(portraitColors.baseColor, colors!!.baseColor)
        assertSameColor(portraitColors.accentColor, colors!!.accentColor)
    }

    @Test
    fun should_startFromTheFallback_when_theCardDoesNotHold() {
        val album = "test://nohold/album/${System.nanoTime()}"
        val portrait = "test://nohold/portrait/${System.nanoTime()}"
        model = null
        setCard(holdUntilResolved = false)
        showAlbum(album)

        model = portrait
        rule.waitForIdle()
        assertSameColor(Color.Gray, colors!!.baseColor)

        reads.getOrPut(portrait) { CompletableDeferred() }.complete(portraitColors)
        rule.waitForIdle()
        assertSameColor(portraitColors.baseColor, colors!!.baseColor)
    }

    @Test
    fun should_returnToTheFallback_when_aHeldCardLosesItsCover() {
        val album = "test://hold/gone/${System.nanoTime()}"
        model = null
        setCard(holdUntilResolved = true)
        showAlbum(album)

        model = null
        rule.waitForIdle()

        assertSameColor(Color.Gray, colors!!.baseColor)
        assertSameColor(Color.LightGray, colors!!.accentColor)
    }
}
