package com.gpo.yoin.ui.landing.guide

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.MainActivity
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.util.ReflectionHelpers

/**
 * The guide window ([ConnectGuideActivity]) through what Android 17's picture-in-picture does to it: ✕ only takes
 * it out of the corner and stops it; launching it while it floats makes the system expand it.
 */
@RunWith(RobolectricTestRunner::class)
class ConnectGuideActivityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        GuideKind.entries.forEach(ConnectGuide::closed)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    /** Robolectric's window never leaves the corner on its own: report what the system would. */
    private fun ConnectGuideActivity.reportCorner(inCorner: Boolean) {
        ReflectionHelpers.setField(shadowOf(this), "isInPictureInPictureMode", inCorner)
        onPictureInPictureModeChanged(inCorner, resources.configuration)
    }

    private fun ConnectGuideActivity.nextStarted(): Intent? = shadowOf(this).nextStartedActivity

    /** The Spotify guide after its card's beat: in the corner, the dashboard opened beneath it. */
    private fun floatingSpotifyGuide(): ActivityController<ConnectGuideActivity> {
        val launch = ConnectGuide.intent(context, GuideKind.Spotify)
        val controller = Robolectric.buildActivity(ConnectGuideActivity::class.java, launch).setup()
        val guide = controller.get()
        idle()
        guide.reportCorner(true)
        controller.pause()
        assertEquals(Intent.ACTION_VIEW, guide.nextStarted()?.action)
        assertNull(guide.nextStarted())
        assertEquals(GuideKind.Spotify, ConnectGuide.open.value)
        assertEquals(GuideKind.Spotify, ConnectGuide.floating.value)
        return controller
    }

    @Test
    fun should_endTheGuideWithoutRaisingYoin_when_dismissedFromTheCorner() {
        // P10-2/P10-4: the stopped window used to live on with ConnectGuide.open still set.
        val controller = floatingSpotifyGuide()
        val guide = controller.get()

        guide.reportCorner(false)
        controller.stop()

        assertTrue(guide.isFinishing)
        assertNull(guide.nextStarted())
        controller.destroy()
        assertNull(ConnectGuide.open.value)
        assertNull(ConnectGuide.floating.value)
    }

    @Test
    fun should_startOverAndOpenTheDashboardAgain_when_yoinLaunchesTheGuideWhileItIsStillThere() {
        // P10-3: the full screen that follows was read as "expanded, done": back to Yoin, and the guide was gone.
        val controller = floatingSpotifyGuide()
        val guide = controller.get()

        controller.newIntent(ConnectGuide.intent(context, GuideKind.Spotify))
        guide.reportCorner(false)
        controller.resume()

        assertFalse(guide.isFinishing)
        assertNull(guide.nextStarted())
        idle()
        guide.reportCorner(true)
        assertEquals(Intent.ACTION_VIEW, guide.nextStarted()?.action)
        assertFalse(guide.isFinishing)
    }

    @Test
    fun should_bringYoinForwardOnlyOnceExpanded_when_backToYoinIsTapped() {
        // P10-1: starting Yoin from the floating window is blocked on Android 17, and the guide finished anyway.
        val controller = floatingSpotifyGuide()
        val guide = controller.get()
        val back = guideRemoteActions(context, GuideKind.Spotify, SpotifyGuideSteps.lastIndex, max = 3)[1]

        controller.newIntent(shadowOf(back.actionIntent).savedIntent)

        assertNull(guide.nextStarted())
        assertFalse(guide.isFinishing)
        // The system expands the window it was asked to launch; full screen, it may start Yoin.
        guide.reportCorner(false)
        controller.resume()
        val yoin = requireNotNull(guide.nextStarted())
        assertEquals(MainActivity::class.java.name, yoin.component?.className)
        assertTrue(guide.isFinishing)
    }

    @Test
    fun should_keepFloating_when_theScreenGoesOffWhileInTheCorner() {
        val controller = floatingSpotifyGuide()
        val guide = controller.get()

        controller.stop()

        assertFalse(guide.isFinishing)
        assertEquals(GuideKind.Spotify, ConnectGuide.floating.value)
    }
}
