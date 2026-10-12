package com.gpo.yoin.ui.landing.guide

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * How Yoin opens a guide ([ConnectGuide]) depending on whether one is in the corner, merely open (a window left
 * behind: pip2's ✕ only stops it), or gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConnectGuideLaunchTest {

    private val host: Activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private fun nextStarted(): Intent? = shadowOf(host).nextStartedActivity

    @After
    fun tearDown() {
        GuideKind.entries.forEach(ConnectGuide::closed)
    }

    @Test
    fun should_openOnlyTheDashboardBeneath_when_theSpotifyGuideAlreadyFloats() {
        // P10-3: launching the floating window again made the system pull it full screen, and it then closed.
        ConnectGuide.opened(GuideKind.Spotify)
        ConnectGuide.cornerChanged(GuideKind.Spotify, inCorner = true)

        ConnectGuide.openSpotify(host)

        val started = requireNotNull(nextStarted())
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(SpotifyDashboardUrl, started.dataString)
        assertNull(nextStarted())
    }

    @Test
    fun should_launchTheGuide_when_noSpotifyGuideIsOpen() {
        ConnectGuide.openSpotify(host)

        assertEquals(ConnectGuideActivity::class.java.name, nextStarted()?.component?.className)
    }

    @Test
    fun should_launchTheGuideAgain_when_aSpotifyGuideIsLeftBehindOutOfTheCorner() {
        // P10-2: open but not floating; the launch reaches the old window, which starts over.
        ConnectGuide.opened(GuideKind.Spotify)

        ConnectGuide.openSpotify(host)

        assertEquals(ConnectGuideActivity::class.java.name, nextStarted()?.component?.className)
    }

    @Test
    fun should_launchTheAppleGuideAgain_when_itIsOpenButNoLongerFloats() = runTest {
        // P10-4: after ✕, Retry only sent "first step" to the hidden window and the sign-in opened with no guide.
        ConnectGuide.opened(GuideKind.AppleMusic)

        val shown = async { ConnectGuide.openAndAwaitReady(host, GuideKind.AppleMusic) }
        runCurrent()

        val started = requireNotNull(nextStarted())
        assertEquals(ConnectGuideActivity::class.java.name, started.component?.className)
        ConnectGuide.ready.emit(GuideKind.AppleMusic)
        assertTrue(shown.await())
    }

    @Test
    fun should_onlyGoBackToTheFirstStep_when_theAppleGuideFloats() = runTest {
        ConnectGuide.opened(GuideKind.AppleMusic)
        ConnectGuide.cornerChanged(GuideKind.AppleMusic, inCorner = true)
        val commands = mutableListOf<ConnectGuide.Command>()
        val collecting = launch(UnconfinedTestDispatcher(testScheduler)) {
            ConnectGuide.commands.collect { commands += it }
        }

        assertTrue(ConnectGuide.openAndAwaitReady(host, GuideKind.AppleMusic))

        assertNull(nextStarted())
        assertEquals(listOf<ConnectGuide.Command>(ConnectGuide.Command.ShowStep(0)), commands)
        collecting.cancel()
    }

    @Test
    fun should_forgetTheCorner_when_theGuideCloses() {
        ConnectGuide.opened(GuideKind.Spotify)
        ConnectGuide.cornerChanged(GuideKind.Spotify, inCorner = true)

        ConnectGuide.closed(GuideKind.Spotify)

        assertNull(ConnectGuide.open.value)
        assertNull(ConnectGuide.floating.value)
    }
}
