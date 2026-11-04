package com.gpo.yoin.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * The colour scheme the window shows: [targetColorScheme] itself, or the
 * instance already shown when the two are token-for-token equal.
 *
 * A new cover's palette lands in one cut, on the frame its colours arrive.
 * Both [MaterialTheme][androidx.compose.material3.MaterialTheme]'s colour
 * local and [LocalYoinColors] are static locals, so every new [ColorScheme]
 * instance turns skipping off and recomposes the whole window. The old
 * 12-step wash did that twelve times; on the tablet's three panes (Home |
 * album | Now Playing) a step cost ~40 ms in a release build, and switching
 * to another album's song was a run of back-to-back long frames while a song
 * from the same album (same palette) stayed smooth. 51 chose the cut
 * (2026-11-04): one recomposition, like a same-album switch. Now Playing's
 * own colour flow (the aurora's rest wash, the focus field) still moves from
 * one cover to the next in the draw phase.
 *
 * Keep it to one instance per palette: value-equal targets (rebuilt schemes,
 * another window attaching) return the instance already shown, so they cost
 * nothing.
 */
@Composable
fun stableColorScheme(
    targetColorScheme: ColorScheme,
    shared: SharedColorScheme? = null,
): ColorScheme {
    val holder = shared ?: remember { SharedColorScheme() }
    return holder.resolve(targetColorScheme)
}

/** The scheme last shown, shared by every window of the app session. */
class SharedColorScheme {
    // Not snapshot state: it only dedups instances, it never drives recomposition.
    private var shown: ColorScheme? = null

    internal fun resolve(target: ColorScheme): ColorScheme {
        shown?.takeIf { sameTokens(it, target) }?.let { return it }
        shown = target
        return target
    }
}

/**
 * True when the 36 scheme tokens the app uses are identical. The 12
 * Fixed-tone tokens are ignored (unused app-wide).
 */
private fun sameTokens(a: ColorScheme, b: ColorScheme): Boolean =
    a.primary == b.primary &&
        a.onPrimary == b.onPrimary &&
        a.primaryContainer == b.primaryContainer &&
        a.onPrimaryContainer == b.onPrimaryContainer &&
        a.inversePrimary == b.inversePrimary &&
        a.secondary == b.secondary &&
        a.onSecondary == b.onSecondary &&
        a.secondaryContainer == b.secondaryContainer &&
        a.onSecondaryContainer == b.onSecondaryContainer &&
        a.tertiary == b.tertiary &&
        a.onTertiary == b.onTertiary &&
        a.tertiaryContainer == b.tertiaryContainer &&
        a.onTertiaryContainer == b.onTertiaryContainer &&
        a.background == b.background &&
        a.onBackground == b.onBackground &&
        a.surface == b.surface &&
        a.onSurface == b.onSurface &&
        a.surfaceVariant == b.surfaceVariant &&
        a.onSurfaceVariant == b.onSurfaceVariant &&
        a.surfaceTint == b.surfaceTint &&
        a.inverseSurface == b.inverseSurface &&
        a.inverseOnSurface == b.inverseOnSurface &&
        a.error == b.error &&
        a.onError == b.onError &&
        a.errorContainer == b.errorContainer &&
        a.onErrorContainer == b.onErrorContainer &&
        a.outline == b.outline &&
        a.outlineVariant == b.outlineVariant &&
        a.scrim == b.scrim &&
        a.surfaceBright == b.surfaceBright &&
        a.surfaceDim == b.surfaceDim &&
        a.surfaceContainer == b.surfaceContainer &&
        a.surfaceContainerHigh == b.surfaceContainerHigh &&
        a.surfaceContainerHighest == b.surfaceContainerHighest &&
        a.surfaceContainerLow == b.surfaceContainerLow &&
        a.surfaceContainerLowest == b.surfaceContainerLowest
