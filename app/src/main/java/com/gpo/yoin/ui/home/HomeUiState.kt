package com.gpo.yoin.ui.home

import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.Track

sealed interface HomeUiState {
    data object Loading : HomeUiState

    data class Content(
        val activities: List<ActivityEvent>,
        val jumpBackInItems: List<HomeJumpBackInItem>,
        val isLoadingMoreJumpBackIn: Boolean = false,
    ) : HomeUiState

    data class Error(val message: String) : HomeUiState
}

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
