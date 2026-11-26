package com.gpo.yoin.ui.home

import androidx.annotation.StringRes
import com.gpo.yoin.R
import com.gpo.yoin.shared.home.HomeFeedRow

// The section catalog and the layout model live in :shared (com.gpo.yoin.shared.home, parity p4 step A):
// iOS and HarmonyOS answer as they do (golden home-layout.json). The app keeps the strings and these names.

/** The catalog of home-screen sections ([com.gpo.yoin.shared.home.HomeSection]). */
typealias HomeSection = com.gpo.yoin.shared.home.HomeSection

/** A section's row preset ([com.gpo.yoin.shared.home.HomeRowPreset]). */
typealias HomeRowPreset = com.gpo.yoin.shared.home.HomeRowPreset

/** One section at its resolved position ([com.gpo.yoin.shared.home.HomeSectionState]). */
typealias HomeSectionState = com.gpo.yoin.shared.home.HomeSectionState

/** The resolved, ordered home layout the feed renders from ([com.gpo.yoin.shared.home.HomeLayout]). */
typealias HomeLayout = com.gpo.yoin.shared.home.HomeLayout

@get:StringRes
internal val HomeSection.titleRes: Int
    get() = when (this) {
        HomeSection.Activities -> R.string.home_section_activities
        HomeSection.JumpBackIn -> R.string.home_section_jump_back_in
        HomeSection.RecentlyAdded -> R.string.home_section_recently_added
        HomeSection.Rediscover -> R.string.home_section_rediscover
        HomeSection.RecentlyPlayed -> R.string.home_section_recently_played
        HomeSection.YourPlaylists -> R.string.home_section_your_playlists
        HomeSection.OtherProfiles -> R.string.home_section_other_profiles
        HomeSection.LyricHighlight -> R.string.home_section_lyric_highlight
        HomeSection.MemoryGrid -> R.string.home_section_memory_grid
        HomeSection.MadeForYou -> R.string.home_section_made_for_you
    }

@get:StringRes
internal val HomeSection.supportingRes: Int
    get() = when (this) {
        HomeSection.Activities -> R.string.home_section_activities_supporting
        HomeSection.JumpBackIn -> R.string.home_section_jump_back_in_supporting
        HomeSection.RecentlyAdded -> R.string.home_section_recently_added_supporting
        HomeSection.Rediscover -> R.string.home_section_rediscover_supporting
        HomeSection.RecentlyPlayed -> R.string.home_section_recently_played_supporting
        HomeSection.YourPlaylists -> R.string.home_section_your_playlists_supporting
        HomeSection.OtherProfiles -> R.string.home_section_other_profiles_supporting
        HomeSection.LyricHighlight -> R.string.home_section_lyric_highlight_supporting
        HomeSection.MemoryGrid -> R.string.home_section_memory_grid_supporting
        HomeSection.MadeForYou -> R.string.home_section_made_for_you_supporting
    }

@get:StringRes
internal val HomeSection.placeholderRes: Int
    get() = when (this) {
        HomeSection.Activities -> R.string.home_placeholder_activities
        HomeSection.JumpBackIn -> R.string.home_placeholder_jump_back_in
        HomeSection.RecentlyAdded -> R.string.home_placeholder_recently_added
        HomeSection.Rediscover -> R.string.home_placeholder_rediscover
        HomeSection.RecentlyPlayed -> R.string.home_placeholder_recently_played
        HomeSection.YourPlaylists -> R.string.home_placeholder_your_playlists
        HomeSection.OtherProfiles -> R.string.home_placeholder_other_profiles
        HomeSection.LyricHighlight -> R.string.home_placeholder_lyric_highlight
        HomeSection.MemoryGrid -> R.string.home_placeholder_memory_grid
        HomeSection.MadeForYou -> R.string.home_placeholder_made_for_you
    }

/** The remembered pairs that show at [order] ([com.gpo.yoin.shared.home.homeWidePairsAt]). Pure. */
internal fun homeWidePairsAt(
    order: List<HomeSection>,
    memory: Map<HomeSection, HomeSection>,
    canPair: (HomeSection, HomeSection) -> Boolean = ::homeCanPair,
): Map<HomeSection, HomeSection> = com.gpo.yoin.shared.home.homeWidePairsAt(order, memory, canPair)

/** [a] and [b] hold the same pairs, sides aside ([com.gpo.yoin.shared.home.homeSamePairs]). Pure. */
internal fun homeSamePairs(a: Map<HomeSection, HomeSection>, b: Map<HomeSection, HomeSection>): Boolean =
    com.gpo.yoin.shared.home.homeSamePairs(a, b)

/** The pairs of an enabled order given as (section, half) ([com.gpo.yoin.shared.home.homeSpanPairs]). Pure. */
internal fun homeSpanPairs(order: List<Pair<HomeSection, Boolean>>): Map<HomeSection, HomeSection> =
    com.gpo.yoin.shared.home.homeSpanPairs(order)

/** The feed's rows for the sections it [displayed] ([com.gpo.yoin.shared.home.homeFeedRows]). Pure. */
internal fun homeFeedRows(
    displayed: List<HomeSection>,
    pairs: Map<HomeSection, HomeSection>,
    twoColumn: Boolean,
): List<HomeFeedRow> = com.gpo.yoin.shared.home.homeFeedRows(displayed, pairs, twoColumn)
