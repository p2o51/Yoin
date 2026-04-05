package com.gpo.yoin.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes for Yoin.
 *
 * Home and Library are the two main sections (tab-style, crossfade transition).
 * NowPlaying is a fullscreen overlay (slide-up transition, Phase 10).
 * Settings is a forward sub-route from Home or Library.
 */
@Serializable
sealed interface YoinRoute {

    @Serializable
    data object Home : YoinRoute

    @Serializable
    data object Library : YoinRoute

    @Serializable
    data object NowPlaying : YoinRoute

    @Serializable
    data object Settings : YoinRoute
}

/** The two main content sections selectable via the Button Group. */
enum class YoinSection { HOME, LIBRARY }
