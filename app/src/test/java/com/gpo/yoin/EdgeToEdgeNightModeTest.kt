package com.gpo.yoin

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * S25 report: switching the system between light and dark left the in-app status bar icons in the
 * old theme. MainActivity handles uiMode itself (no recreate), and SystemBarStyle.auto decides the
 * icons only once, in onCreate.
 *
 * API 26 as in DetailShellHandoffTest: enableYoinEdgeToEdge's display-mode request needs a display
 * Robolectric's activities lack from API 30.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class EdgeToEdgeNightModeTest {

    /** An Activity set up like MainActivity: Yoin's edge-to-edge, uiMode handled without a recreate. */
    class UiModeHandlingActivity : ComponentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            enableYoinEdgeToEdge()
        }
    }

    @Before
    fun registerActivity() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(context.packageManager).addOrUpdateActivity(
            ActivityInfo().apply {
                packageName = context.packageName
                name = UiModeHandlingActivity::class.java.name
                configChanges = ActivityInfo.CONFIG_UI_MODE
            },
        )
        assertEquals(
            ActivityInfo.CONFIG_UI_MODE,
            context.packageManager
                .getActivityInfo(ComponentName(context, UiModeHandlingActivity::class.java), 0)
                .configChanges,
        )
    }

    @Test
    fun should_switchToLightIcons_when_theSystemTurnsDarkWhileTheAppRuns() {
        val controller = Robolectric.buildActivity(UiModeHandlingActivity::class.java).setup()
        val activity = controller.get()
        assertEquals(DarkIcons, barsOf(activity))

        controller.configurationChange(activity.configurationWith(night = true))

        assertSame("handled in place, not recreated", activity, controller.get())
        assertEquals(LightIcons, barsOf(activity))
    }

    @Config(sdk = [26], qualifiers = "night")
    @Test
    fun should_switchToDarkIcons_when_theSystemTurnsLightWhileTheAppRuns() {
        val controller = Robolectric.buildActivity(UiModeHandlingActivity::class.java).setup()
        val activity = controller.get()
        assertEquals(LightIcons, barsOf(activity))

        controller.configurationChange(activity.configurationWith(night = false))

        assertSame(activity, controller.get())
        assertEquals(DarkIcons, barsOf(activity))
    }

    @Test
    fun should_leaveAPageOverrideAlone_when_aConfigurationChangeIsNotALightDarkSwitch() {
        val controller = Robolectric.buildActivity(UiModeHandlingActivity::class.java).setup()
        val activity = controller.get()
        // A page forces light icons (as the album's spectrum bar does).
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars = false

        // Same night mode, another uiMode bit (e.g. a dock): nothing to do for the icons.
        val sameNight = Configuration(activity.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_DESK
        }
        controller.configurationChange(sameNight)

        assertEquals(false, barsOf(activity).first)
    }

    private fun ComponentActivity.configurationWith(night: Boolean) = Configuration(resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }

    /** (light status bars, light navigation bars) of the Activity's window. */
    private fun barsOf(activity: ComponentActivity): Pair<Boolean, Boolean> {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        return controller.isAppearanceLightStatusBars to controller.isAppearanceLightNavigationBars
    }

    private companion object {
        val LightIcons = false to false
        val DarkIcons = true to true
    }
}
