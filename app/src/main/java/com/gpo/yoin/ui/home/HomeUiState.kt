package com.gpo.yoin.ui.home

import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.Track

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
    ) : HomeUiState

    data class Error(val message: String) : HomeUiState
}

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
