package com.gpo.yoin.ui.home

import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.ui.memories.MemoryEntityType

sealed interface HomeUiState {
    data object Loading : HomeUiState

    data class Content(
        val activities: List<ActivityEvent>,
        // True only when [activities] came from a provider endpoint (Spotify
        // recently-played) rather than the local activity log. The live local
        // observer uses this to decide whether it owns the feed — see
        // HomeViewModel.observeRecentHistory.
        val activitiesFromRemote: Boolean = false,
        // "2024 · 12 songs, 44 min" line for the hero (first) activity when it
        // is an album whose metadata resolved from the detail cache. Null just
        // hides the line — the hero card renders fine without it.
        val activityHeroFootnote: String? = null,
        // The "Jump Back In" 3×4 widget grid: plain recommendations mixed with
        // memory-flavoured cards. Empty hides the section.
        val widgetGrid: List<HomeWidgetCard> = emptyList(),
        // Library items added within the last week (Spotify saved / Subsonic
        // starred), newest first. Empty when nothing was added recently or the
        // fetch failed — the section then renders nothing.
        val recentlyAdded: List<Track> = emptyList(),
    ) : HomeUiState

    data class Error(val message: String) : HomeUiState
}

/** Where tapping a widget-grid card leads. */
sealed interface HomeWidgetTarget {
    data class AlbumDetail(val albumId: String) : HomeWidgetTarget

    data class PlaylistDetail(val playlistId: String) : HomeWidgetTarget

    data class PlaySong(val song: Track) : HomeWidgetTarget

    /** Open the Memories deck (the pull-down attic) stopped on this memory. */
    data class MemoryFocus(val sessionId: Long) : HomeWidgetTarget
}

/**
 * One card in the home widget grid, following the Figma Widget 1×1 / 1×2
 * components: a cover on an entity-type backdrop shape, expanding to the wide
 * "1×2" variant when it carries a rating/review/note. The grid packs to
 * 3 columns × 4 rows = 12 cells (a 1×2 spans two cells), with the expanded
 * count bounded upstream so the shelf can't balloon.
 */
data class HomeWidgetCard(
    val stableId: String,
    // Drives the backdrop shape: ALBUM → Bun, SONG → Circle, PLAYLIST → Ghostish.
    val entityType: MemoryEntityType,
    val title: String,
    val subtitle: String,
    val coverArtUrl: String?,
    // Formatted score ("7.0") or "N/A"; only surfaced on expanded cards.
    val ratingText: String? = null,
    // What the score rests on: a date ("Jun 26") for a reviewed/noted card, or
    // "Based on 4/5 tracks" for an auto-averaged one. Null hides the line.
    val ratingBasis: String? = null,
    // Review / note copy, rendered in the serif voice. Expanded cards only.
    val comment: String? = null,
    // True renders the wide "1×2" card (2 grid cells), false the "1×1" cover.
    val expanded: Boolean = false,
    val target: HomeWidgetTarget,
)
