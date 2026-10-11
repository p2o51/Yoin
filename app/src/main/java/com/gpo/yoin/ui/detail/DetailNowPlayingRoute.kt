package com.gpo.yoin.ui.detail

import android.content.Context
import android.content.Intent

/** A detail page Now Playing can name: what the debug log calls it, and its window's intent. */
internal enum class DetailPageKind(val perfKind: String) {
    Album("album"),
    Artist("artist"),
    Playlist("playlist");

    fun intent(context: Context, id: String): Intent = when (this) {
        Album -> AlbumDetailActivity.intent(context, id)
        Artist -> ArtistDetailActivity.intent(context, id)
        Playlist -> PlaylistDetailActivity.intent(context, id)
    }
}

/**
 * Now Playing, hosted in a detail window that shows a [shown] page, was asked
 * to open the [requested] page [requestedId]. When that is the window's own
 * page ([shownIds]: the id the window opened with, and the id its content
 * resolved to, e.g. an Apple Music library album shown as its catalog
 * album), nothing is pushed: a second copy of the page would stack on the
 * first, and back would land on the same page with Now Playing still open.
 * The page is made visible instead, the Wide column's rule for the page it
 * already shows: a full player steps back to its side panel where one fits
 * ([panelAvailable], [onStepBackToPanel]), otherwise Now Playing collapses
 * onto the page ([onCollapse]) — the same expanded flag its dismiss and back
 * drive, so the same motion. Any other page: [onLaunch].
 */
internal fun openFromHostedNowPlaying(
    requested: DetailPageKind,
    requestedId: String,
    shown: DetailPageKind,
    shownIds: List<String?>,
    panelAvailable: Boolean,
    onStepBackToPanel: () -> Unit,
    onCollapse: () -> Unit,
    onLaunch: () -> Unit
) {
    // Full MediaId strings on both sides (provider:rawId).
    when {
        requested != shown || requestedId !in shownIds -> onLaunch()
        panelAvailable -> onStepBackToPanel()
        else -> onCollapse()
    }
}
