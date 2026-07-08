package com.gpo.yoin.ui.home

import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
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
        val jumpBackInItems: List<HomeJumpBackInItem>,
        val memoryTeaser: MemoryTeaser? = null,
        // Library items added within the last week (Spotify saved / Subsonic
        // starred), newest first. Empty when nothing was added recently or the
        // fetch failed — the section then renders nothing.
        val recentlyAdded: List<Track> = emptyList(),
        // The "Memories" widget shelf — one card per album memory. Empty when
        // nothing has become a memory yet; the section then renders nothing.
        val memories: List<HomeMemoryWidget> = emptyList(),
    ) : HomeUiState

    data class Error(val message: String) : HomeUiState
}

/**
 * One card in the home "Memories" shelf, restored from the Figma design
 * (node 405:361). Each memory renders as a cover sitting on a genre/entity-type
 * backdrop shape. A memory with a review or notes may [expanded] into the wider
 * "1×2" card that also shows the rating, its basis, and the review copy — the
 * number of expanded cards is capped upstream so the shelf can't balloon.
 */
data class HomeMemoryWidget(
    // Deck-matching id (== AlbumMemoryCandidate.sessionId). Tapping the card
    // opens the Memories deck stopped on this memory via onOpenMemoryFocus.
    val sessionId: Long,
    // Drives the backdrop shape: ALBUM → Bun, SONG → Circle, PLAYLIST → Ghostish.
    val entityType: MemoryEntityType,
    val title: String,
    val subtitle: String,
    val coverArtUrl: String?,
    // Formatted score ("7.0") or "N/A". Only surfaced on the expanded card.
    val ratingText: String,
    // What the score rests on: a date ("Jun 26") for a reviewed memory, or
    // "Based on 4/5 tracks" for an auto-averaged one. Null hides the line.
    val ratingBasis: String? = null,
    // The album review copy. Non-null only on expanded cards that actually have
    // written text; a notes-only expansion shows the basis instead.
    val comment: String? = null,
    // True to render the wide "1×2" card, false for the compact "1×1" cover.
    val expanded: Boolean = false,
)

data class MemoryTeaser(
    val albumId: String,
    // Deck-matching id == AlbumMemoryCandidate.sessionId == MemoryEntry.sourceActivityId.
    // Carried verbatim so tapping the teaser can open the deck stopped on this album;
    // never recompute it from albumId — the session-id hash uses the raw album id + profile.
    val sessionId: Long,
    val title: String,
    val supportingText: String,
    // A formed memory speaks in a recall voice; a still-forming one nudges the
    // user to finish shaping it. Drives the supporting copy + the row icon.
    val isFormed: Boolean,
)

sealed interface HomeJumpBackInItem {
    val stableId: String

    data class AlbumItem(val album: Album) : HomeJumpBackInItem {
        override val stableId: String = "album:${album.id}"
    }

    data class SongItem(val song: Track) : HomeJumpBackInItem {
        override val stableId: String = "song:${song.id}"
    }

    data class ArtistItem(val artist: Artist) : HomeJumpBackInItem {
        override val stableId: String = "artist:${artist.id}"
    }
}
