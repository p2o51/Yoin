package com.gpo.yoin.ui.settings

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.gpo.yoin.ui.theme.YoinDarkColorScheme
import com.gpo.yoin.ui.theme.YoinLightColorScheme
import hct.Hct
import utils.MathUtils
import kotlin.math.min

// Settings' colour system, after Pixel Settings (Android 16 Material 3
// Expressive, read off the Pixel Tablet's SettingsGoogle.apk): one flat page
// colour, rows lifted on the brighter surface, plain monochrome row icons.
// Colour belongs to services only — each music service wears one fixed hue
// family (an M3 "custom colour", harmonised toward the wallpaper primary) on
// its accounts, its badge and its marks.

/** Page and row surfaces, by role (the tones differ between colour specs). */
@Immutable
internal data class SettingsSurfaces(
    val page: Color,
    val row: Color,
    /** The open row in the two-pane list: the detail pane's own colour. */
    val rowSelected: Color,
)

/**
 * Pixel's rule, the same in light and dark: page = surfaceContainer, rows =
 * surfaceBright. In the two-pane split the LIST pane steps down to
 * surfaceDim and its open row takes the detail pane's surfaceContainer, so
 * the selection reads as attached to the page beside it.
 */
@Composable
internal fun settingsSurfaces(listPane: Boolean = false): SettingsSurfaces {
    val scheme = MaterialTheme.colorScheme
    return SettingsSurfaces(
        page = if (listPane) scheme.surfaceDim else settingsPageColor(scheme),
        row = scheme.surfaceBright,
        rowSelected = scheme.surfaceContainer,
    )
}

/**
 * The Settings page colour for [scheme]. Plain function so the Settings
 * split's animation background (SplitLayout) can paint the same colour.
 */
internal fun settingsPageColor(scheme: ColorScheme): Color = scheme.surfaceContainer

/** One service's colour family. Every pair is 50+ tones apart (≥ 4.5:1). */
@Immutable
internal data class SettingsTone(
    /** Large tinted surfaces: the account card in use. */
    val container: Color,
    val onContainer: Color,
    /** Strong fills: account avatars, the "In use" pill. */
    val accent: Color,
    val onAccent: Color,
    /** Pixel's pastel icon circle (T90 light / T80 dark) and its T30 glyph. */
    val iconContainer: Color,
    val iconContent: Color,
)

/** The fixed hue families, one per music service (Pixel's reference hues). */
internal enum class SettingsHue(val sourceHue: Double, val chroma: Double) {
    Blue(sourceHue = 256.0, chroma = 40.0),
    Green(sourceHue = 148.0, chroma = 40.0),
    Rose(sourceHue = 10.0, chroma = 48.0),
    Amber(sourceHue = 60.0, chroma = 44.0),
}

/**
 * What the hue families harmonise toward: the wallpaper (system dynamic)
 * primary, provided once by [SettingsPageBackground]. NOT the playing
 * cover's primary — a service's colour must not drift from song to song.
 * Outside that provider (sheets, dialogs, previews) [SettingsHue.tone]
 * reads the wallpaper primary itself.
 */
internal val LocalSettingsHueSource = staticCompositionLocalOf<Color?> { null }

@Composable
internal fun SettingsHue.tone(): SettingsTone {
    val source = LocalSettingsHueSource.current ?: rememberSystemPrimary()
    val dark = isSystemInDarkTheme()
    return remember(this, source, dark) { settingsTone(this, source.toArgb(), dark) }
}

/** The system (wallpaper) scheme's primary for light or dark — Yoin's own below API 31. */
@Composable
internal fun rememberSystemPrimary(dark: Boolean = isSystemInDarkTheme()): Color {
    val context = LocalContext.current
    return remember(context, dark) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(context).primary else dynamicLightColorScheme(context).primary
        } else {
            if (dark) YoinDarkColorScheme.primary else YoinLightColorScheme.primary
        }
    }
}

internal fun settingsTone(hue: SettingsHue, sourceArgb: Int, dark: Boolean): SettingsTone {
    val source = Hct.fromInt(sourceArgb)
    // A near-grey wallpaper has no meaningful hue to lean toward.
    val harmonized = if (source.chroma < MinHarmonizeChroma) {
        hue.sourceHue
    } else {
        harmonizeHue(hue.sourceHue, source.hue)
    }
    fun t(tone: Int) = Color(Hct.from(harmonized, hue.chroma, tone.toDouble()).toInt())
    return if (dark) {
        SettingsTone(
            container = t(30),
            onContainer = t(90),
            accent = t(80),
            onAccent = t(20),
            iconContainer = t(80),
            iconContent = t(30),
        )
    } else {
        SettingsTone(
            container = t(90),
            onContainer = t(30),
            accent = t(40),
            onAccent = t(100),
            iconContainer = t(90),
            iconContent = t(30),
        )
    }
}

/**
 * MCU `Blend.harmonize`, with a tighter cap: turn [designHue] toward
 * [sourceHue] by half their distance, at most [MaxHarmonizeDegrees], so a
 * service colour leans into the wallpaper without drifting into a
 * neighbouring service's hue.
 */
internal fun harmonizeHue(designHue: Double, sourceHue: Double): Double {
    val difference = MathUtils.differenceDegrees(designHue, sourceHue)
    val rotation = min(difference * 0.5, MaxHarmonizeDegrees)
    return MathUtils.sanitizeDegreesDouble(
        designHue + rotation * MathUtils.rotationDirection(designHue, sourceHue),
    )
}

/** MCU harmonises up to 15°; services sit ~50° apart, so 10° keeps them ≥ 30° apart. */
internal const val MaxHarmonizeDegrees = 10.0
private const val MinHarmonizeChroma = 6.0
