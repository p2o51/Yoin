package com.gpo.yoin.ui.theme

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.RoundedCorner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

/**
 * The screen's corner radius, as the user set it (Settings › Motion › Screen corners, or the landing's step)
 * or as the display reports it. The shapes that stand in for "this page is the screen, shrinking or
 * sliding" take it, so their corners meet the glass: Now Playing while it opens, closes or is dragged,
 * Memories' retreat, and the predictive-back pose of the pushed pages and the detail column.
 *
 * Per device, never synced: two phones have two different screens. A snapshot read: call [radius] from
 * composition, layout, draw or layer blocks.
 */
object ScreenCorners {
    private const val PREFS_NAME = "yoin_ui_preferences"
    const val KEY_RADIUS = "screen_corner_radius_dp"

    /** No display report (pre-31, or a display without rounded corners): the old stand-in. */
    val Fallback: Dp = 28.dp

    /** The slider's range. */
    val Min: Dp = 0.dp
    val Max: Dp = 64.dp

    private val userState = mutableStateOf<Float?>(null)
    private val deviceState = mutableStateOf<Float?>(null)

    @Volatile
    private var loaded = false

    /** What the user picked, or null to follow the screen. */
    val userRadius: Dp? get() = userState.value?.dp

    /** What the display reports (its top-left corner, in dp), or null. */
    val deviceRadius: Dp? get() = deviceState.value?.dp

    /** The radius every screen-shaped surface uses. */
    val radius: Dp get() = userRadius ?: deviceRadius ?: Fallback

    /** The radius the screen itself has, for "follow the screen". */
    val screenDefault: Dp get() = deviceRadius ?: Fallback

    /**
     * Reads the stored choice and the display's corners once per process, and keeps the display's
     * reading fresh across fold / unfold and rotation. Call from Application.onCreate.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        ensureLoaded(app)
        app.registerComponentCallbacks(
            object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    refreshDevice(app)
                }

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() = Unit
            },
        )
    }

    fun ensureLoaded(context: Context) {
        if (loaded) return
        val app = context.applicationContext
        val prefs = prefs(app)
        val stored = if (prefs.contains(KEY_RADIUS)) prefs.getFloat(KEY_RADIUS, Fallback.value) else null
        Snapshot.global { userState.value = stored }
        refreshDevice(app)
        loaded = true
    }

    /**
     * The user's choice, shown at once everywhere; null follows the screen again. [persist] false while a
     * slider is still moving (no write per frame); the release stores it.
     */
    fun select(context: Context, radius: Dp?, persist: Boolean = true) {
        loaded = true
        val value = radius?.value?.coerceIn(Min.value, Max.value)
        userState.value = value
        if (!persist) return
        prefs(context).edit {
            if (value == null) remove(KEY_RADIUS) else putFloat(KEY_RADIUS, value)
        }
    }

    private fun refreshDevice(context: Context) {
        val reported = deviceCornerDp(context)
        Snapshot.global { deviceState.value = reported }
    }

    private fun deviceCornerDp(context: Context): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return null
        val px = listOf(
            RoundedCorner.POSITION_TOP_LEFT,
            RoundedCorner.POSITION_TOP_RIGHT,
            RoundedCorner.POSITION_BOTTOM_LEFT,
            RoundedCorner.POSITION_BOTTOM_RIGHT,
        ).mapNotNull { display.getRoundedCorner(it)?.radius }.maxOrNull() ?: return null
        if (px <= 0) return null
        val density = context.resources.displayMetrics.density
        return (px / density).coerceIn(Min.value, Max.value)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/** The current screen corner radius; loads the stored choice before the first read. */
@Composable
fun currentScreenCornerRadius(): Dp {
    val context = LocalContext.current
    val corners = remember(context) { ScreenCorners.also { it.ensureLoaded(context) } }
    return corners.radius
}
