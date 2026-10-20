package com.gpo.yoin.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import hct.Hct

/**
 * The scheme while nothing is playing (owner 2026-10-19: the default looked
 * flat, worst on a Samsung right after sign-in). It keeps the wallpaper's
 * hue but is built as the same SPEC_2025 Expressive scheme a cover makes
 * ([ExpressiveColorSchemeFactory]) — the system's own Tonal Spot palette is
 * the muted one. A wallpaper with no real hue (chroma below
 * [MinSeedChroma]), or Android before 12, starts from Yoin's violet.
 */
internal fun defaultColorScheme(context: Context, dark: Boolean): ColorScheme =
    ExpressiveColorSchemeFactory.fromSeed(seedArgb = defaultSeedArgb(context), isDark = dark)

/** The wallpaper's source colour when it has a hue, else [YoinSeedArgb]. */
internal fun defaultSeedArgb(context: Context): Int {
    val system = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getColor(android.R.color.system_accent1_500)
    } else {
        null
    }
    return pickDefaultSeed(system)
}

/** Pure: [systemSeed] when it carries a hue, else Yoin's own. */
internal fun pickDefaultSeed(systemSeed: Int?): Int =
    systemSeed?.takeIf { Hct.fromInt(it).chroma >= MinSeedChroma } ?: YoinSeedArgb

/** The icon's violet arm. */
internal const val YoinSeedArgb: Int = 0xFF7B3FE4.toInt()

/** Below this the wallpaper reads as grey (same line as the service colours' harmonising). */
private const val MinSeedChroma = 6.0
