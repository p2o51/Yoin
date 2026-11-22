package com.gpo.yoin.ui.notes

import android.content.Context
import android.content.Intent
import com.gpo.yoin.ui.detail.AlbumDetailActivity
import com.gpo.yoin.ui.detail.ArtistDetailActivity
import com.gpo.yoin.ui.detail.ScrapbookFocusRequest

/**
 * Opens a [NoteDestination] in its own detail window: an album on its scrapbook page (a song mentioned →
 * that song highlighted there, the same request Now Playing's About sheet makes), an artist's page.
 */
fun openNoteDestination(context: Context, destination: NoteDestination) {
    val intent = when (destination) {
        is NoteDestination.AlbumScrapbook -> {
            val track = destination.focusTrack
            if (track != null) {
                ScrapbookFocusRequest.request(track.toString(), newPageOnly = true)
            } else {
                ScrapbookFocusRequest.requestAlbum(destination.albumId.toString(), newPageOnly = true)
            }
            AlbumDetailActivity.intent(context, destination.albumId.toString())
        }
        is NoteDestination.Artist -> ArtistDetailActivity.intent(context, destination.artistId.toString())
    }
    if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
