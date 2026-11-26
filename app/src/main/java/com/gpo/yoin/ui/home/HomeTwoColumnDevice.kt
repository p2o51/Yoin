package com.gpo.yoin.ui.home

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.WindowMetricsCalculator
import com.gpo.yoin.shared.home.homeTwoColumn
import kotlin.math.max
import kotlin.math.min

/**
 * Whether this device can ever lay Home out in two columns (the shared 840 × 480 tier): its largest window
 * ([maxWidthDp] × [maxHeightDp], the whole display) reaches it on its side, or it folds ([foldable]: an
 * unfolded inner screen does even when the outer one never could). A phone never does — on its side it is
 * under 480 tall — so its tips leave out what only two columns do (owner 2026-11-26, iOS's iPhone copy).
 */
internal fun deviceCanShowTwoColumnHome(maxWidthDp: Float, maxHeightDp: Float, foldable: Boolean): Boolean =
    foldable || homeTwoColumn(max(maxWidthDp, maxHeightDp), min(maxWidthDp, maxHeightDp), tabletop = false)

/** [deviceCanShowTwoColumnHome] for this device, read once. */
@Composable
internal fun rememberHomeTwoColumnCapable(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.canShowTwoColumnHome() }
}

private fun Context.canShowTwoColumnHome(): Boolean {
    val density = resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
    val (widthDp, heightDp) = runCatching {
        val bounds = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(this).bounds
        bounds.width() / density to bounds.height() / density
    }.getOrElse {
        resources.configuration.screenWidthDp.toFloat() to resources.configuration.screenHeightDp.toFloat()
    }
    // PackageManager.FEATURE_SENSOR_HINGE_ANGLE (API 30), by name: the constant is newer than minSdk.
    val foldable = packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle")
    return deviceCanShowTwoColumnHome(widthDp, heightDp, foldable)
}

/** The line [tip] shows on this device: its one-column line where Home never lays out in two columns. */
@StringRes
internal fun HomeBubbleTip.textFor(twoColumnCapable: Boolean): Int =
    if (twoColumnCapable) text else oneColumnText ?: text

/** [HomeBubbleTip.textFor] on this device. */
@Composable
@StringRes
internal fun HomeBubbleTip.shownText(): Int = textFor(rememberHomeTwoColumnCapable())
