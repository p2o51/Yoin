package com.gpo.yoin.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes for Yoin.
 *
 * The app boots into a single Shell route that owns the floating button group plus the
 * Home/Library section switch. Settings and details use pushed routes; Now Playing and
 * Memories live as shell-owned overlays instead of separate top-level destinations.
 *
 * All subtypes are [NavKey] so they can ride in [androidx.navigation3.runtime.rememberNavBackStack]
 * and survive process death thanks to [@Serializable].
 */
@Serializable
sealed interface YoinRoute : NavKey {

    @Serializable
    data object Shell : YoinRoute

    // Detail pages (Album / Artist / Playlist / Settings) are no longer
    // NavDisplay routes — they are separate Activities (ui.detail.*Activity and
    // ui.settings.SettingsActivity) so back navigation plays the device-native
    // cross-Activity predictive back animation. This NavDisplay hosts only the
    // [Shell] (Home / Library / Now Playing / Memories).
}

/** The two main content sections selectable via the Button Group. */
enum class YoinSection { HOME, LIBRARY }
