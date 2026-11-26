package com.gpo.yoin

import android.view.WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS
import android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
import android.view.WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DesktopCaptionTest {

    @Test
    fun should_drawTransparentCaptionWithDarkIcons_when_pageIsLight() {
        assertEquals(
            APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND or APPEARANCE_LIGHT_CAPTION_BARS,
            desktopCaptionAppearance(lightSurface = true),
        )
    }

    @Test
    fun should_drawTransparentCaptionWithLightIcons_when_pageIsDark() {
        assertEquals(APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND, desktopCaptionAppearance(lightSurface = false))
    }

    @Test
    fun should_handTheCaptionToTheSystem_when_notTransparent() {
        assertEquals(APPEARANCE_LIGHT_CAPTION_BARS, desktopCaptionAppearance(lightSurface = true, transparent = false))
        assertEquals(0, desktopCaptionAppearance(lightSurface = false, transparent = false))
    }

    @Test
    fun should_leaveTheStatusBarIcons_when_settingTheCaption() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val controller = activity.window.insetsController!!
        controller.setSystemBarsAppearance(APPEARANCE_LIGHT_STATUS_BARS, APPEARANCE_LIGHT_STATUS_BARS)

        activity.applyDesktopCaption(Surface, lightSurface = false, transparent = true)
        assertEquals(
            APPEARANCE_LIGHT_STATUS_BARS or APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND,
            controller.systemBarsAppearance and Watched,
        )

        // Light / dark switch, then paused under a detail page.
        activity.applyDesktopCaption(Surface, lightSurface = true, transparent = true)
        assertEquals(
            APPEARANCE_LIGHT_STATUS_BARS or APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND or
                APPEARANCE_LIGHT_CAPTION_BARS,
            controller.systemBarsAppearance and Watched,
        )
        activity.applyDesktopCaption(Surface, lightSurface = true, transparent = false)
        assertEquals(
            APPEARANCE_LIGHT_STATUS_BARS or APPEARANCE_LIGHT_CAPTION_BARS,
            controller.systemBarsAppearance and Watched,
        )
    }

    @Test
    fun should_paintEveryFrameColourOpaque_when_onChromeOs() {
        // A translucent surface still gives an opaque frame: the frame cannot show the page through it.
        val description = captionTaskDescription(0x80123456.toInt(), withStatusBarColor = true)
        assertEquals(Opaque, description.primaryColor)
        assertEquals(Opaque, description.backgroundColor)
        assertEquals(Opaque, description.statusBarColor)
    }

    @Test
    fun should_leaveTheStatusBarColourUnset_when_notOnChromeOs() {
        // Elsewhere the task's status bar colour would paint over the page in the recents snapshot.
        val description = captionTaskDescription(Surface, withStatusBarColor = false)
        assertEquals(Surface, description.primaryColor)
        assertEquals(Surface, description.backgroundColor)
        assertEquals(0, description.statusBarColor)
    }

    private companion object {
        val Surface = 0xFFE3F1EE.toInt()
        val Opaque = 0xFF123456.toInt()

        /** The bits under test; the system may add its own (e.g. forced light navigation bars). */
        const val Watched = APPEARANCE_LIGHT_STATUS_BARS or APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND or
            APPEARANCE_LIGHT_CAPTION_BARS
    }
}
