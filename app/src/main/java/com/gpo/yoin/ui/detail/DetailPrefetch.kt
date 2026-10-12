package com.gpo.yoin.ui.detail

import android.content.Intent
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.YoinRepository

// Tap-time prefetch. Every way into a detail page — a shell card, the detail
// column's re-root and push, one page opening another, Now Playing, a widget —
// starts the page's load the moment the tap lands, ahead of the launch gate,
// the Activity start and the column's open spring (the column only builds its
// page once that spring settles). The page's ViewModel then joins the load in
// flight (YoinRepository's single flight, keyed by profile + id) or, once it
// has landed, reads it from the in-memory cache. A tap a launch gate swallows
// still prefetches, and a repeat tap joins the same load: neither sends a
// second request. An id that doesn't parse is left for the page to report.

/** Start loading album [albumId]'s page; see above. */
fun YoinRepository.prefetchAlbumDetail(albumId: String?) {
    MediaId.parseOrNull(albumId)?.let(::prefetchAlbum)
}

/** Start loading artist [artistId]'s page; see above. */
fun YoinRepository.prefetchArtistDetail(artistId: String?) {
    MediaId.parseOrNull(artistId)?.let(::prefetchArtist)
}

/** Start loading playlist [playlistId]'s page; see above. */
fun YoinRepository.prefetchPlaylistDetail(playlistId: String?) {
    MediaId.parseOrNull(playlistId)?.let(::prefetchPlaylist)
}

/**
 * Start loading the page a detail Activity [intent] opens (one built by that
 * Activity's `intent(...)`): a detail page launches the pages it links to, and
 * Now Playing's, through here. Any other intent is ignored.
 */
fun YoinRepository.prefetchDetail(intent: Intent) {
    when (intent.component?.className) {
        AlbumDetailActivity::class.java.name ->
            prefetchAlbumDetail(intent.getStringExtra(AlbumDetailActivity.EXTRA_ALBUM_ID))
        ArtistDetailActivity::class.java.name ->
            prefetchArtistDetail(intent.getStringExtra(ArtistDetailActivity.EXTRA_ARTIST_ID))
        PlaylistDetailActivity::class.java.name ->
            prefetchPlaylistDetail(intent.getStringExtra(PlaylistDetailActivity.EXTRA_PLAYLIST_ID))
    }
}
