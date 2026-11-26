package com.gpo.yoin

import android.app.Activity
import android.app.ActivityManager
import android.graphics.Bitmap
import android.os.Build
import android.view.WindowInsetsController
import androidx.activity.compose.LocalActivity
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/**
 * Keeps a desktop window's caption bar (the title bar with the app name and
 * the window buttons) on the page instead of black.
 *
 * - **Android desktop windowing (Android 15+, e.g. Pixel Tablet).** The caption
 *   bar is drawn transparent over the top of the window, so every page's own
 *   surface (Home's gradient, Now Playing's room, a sheet) runs up behind the
 *   window buttons. Pages pad their tops by
 *   [com.gpo.yoin.ui.experience.topBars], which includes the caption bar.
 *   Its icons are light on a dark surface and dark on a light one.
 *
 *   The window manager reads the caption from the top OPAQUE Activity. While
 *   a translucent detail page (album, artist, playlist) covers the shell, that
 *   is the paused shell beneath, and the system then drew light icons over a
 *   light page (measured on the Pixel Tablet, Android 17). So a paused window
 *   hands the caption back to the system's own opaque bar until it resumes;
 *   the page below the caption is padded either way. That bar is light or
 *   dark from the task's background colour, which is set to the page's
 *   surface here: the theme's value went stale when MainActivity handled a
 *   light / dark switch itself, leaving a dark bar over a light app.
 * - **ChromeOS.** The Android app runs inside a ChromeOS frame that paints a
 *   solid bar from the task's colours (the status bar colour, else a darker
 *   primary colour). Edge-to-edge makes the window's status bar colour
 *   transparent, which the frame showed as black; the task's colours are set
 *   to the page's top surface instead and follow the palette and light / dark.
 *
 * The colour is the theme's `surfaceContainer`: the top of the Home and Library
 * gradients and of Now Playing's room in dark theme.
 */
@Composable
fun DesktopCaptionSync() {
    val activity = LocalActivity.current ?: return
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val argb = surface.toArgb()
    val lightSurface = surface.luminance() >= 0.5f
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    // Applied as the composition commits, before the first frame is drawn.
    DisposableEffect(activity, argb, lightSurface, resumed) {
        activity.applyDesktopCaption(argb, lightSurface, transparent = resumed)
        onDispose {}
    }
}

internal fun Activity.applyDesktopCaption(@ColorInt surface: Int, lightSurface: Boolean, transparent: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        window.insetsController?.setSystemBarsAppearance(
            desktopCaptionAppearance(lightSurface, transparent),
            desktopCaptionAppearanceMask(),
        )
    }
    val chromeOs = isChromeOs()
    if (chromeOs || Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        // The status bar colour only in ChromeOS's frame: elsewhere the task's
        // status bar colour also paints the status bar band of the recents
        // snapshot, over the page.
        setTaskDescription(captionTaskDescription(surface, withStatusBarColor = chromeOs))
        if (chromeOs && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Before Android 13 the window's status bar colour is the only way
            // to reach the task's. A ChromeOS window has no status bar, and if
            // it had one it would get this same top-of-page colour.
            @Suppress("DEPRECATION")
            window.statusBarColor = surface or OpaqueAlpha
        }
    }
}

/** A transparent caption bar (or the system's own), with icons for a light or dark page behind it. */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
@VisibleForTesting
internal fun desktopCaptionAppearance(lightSurface: Boolean, transparent: Boolean = true): Int =
    (if (transparent) WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND else 0) or
        (if (lightSurface) WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS else 0)

/** The two caption bits [desktopCaptionAppearance] owns; other bars' bits are left alone. */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
@VisibleForTesting
internal fun desktopCaptionAppearanceMask(): Int =
    WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND or
        WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS

/**
 * The task colours a desktop frame is painted from, all the page's surface.
 * Opaque, since a frame cannot show the page through it. ChromeOS paints the
 * status bar colour (its first choice) or else a darker primary colour; the
 * Android desktop bar takes light or dark from the background colour. Before
 * Android 13 only the primary colour can be set.
 */
@Suppress("DEPRECATION")
@VisibleForTesting
internal fun captionTaskDescription(
    @ColorInt surface: Int,
    withStatusBarColor: Boolean,
): ActivityManager.TaskDescription {
    val opaque = surface or OpaqueAlpha
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ActivityManager.TaskDescription.Builder()
            .setPrimaryColor(opaque)
            .setBackgroundColor(opaque)
            .apply { if (withStatusBarColor) setStatusBarColor(opaque) }
            .build()
    } else {
        ActivityManager.TaskDescription(null, null as Bitmap?, opaque)
    }
}

private const val OpaqueAlpha = 0xFF shl 24

private fun Activity.isChromeOs(): Boolean =
    packageManager.hasSystemFeature(ArcFeature) || packageManager.hasSystemFeature(ArcDeviceManagementFeature)

private const val ArcFeature = "org.chromium.arc"
private const val ArcDeviceManagementFeature = "org.chromium.arc.device_management"
