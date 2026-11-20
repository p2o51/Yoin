package com.gpo.yoin.ui.detail

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import com.gpo.yoin.data.album.AlbumScrapbookAboutRef

/**
 * "View in Scrapbook" from a song sheet outside the album page (Now Playing's cover): the sheet
 * leaves this request and opens the album the usual way (Go to album); the album page that
 * lists the song takes it, moves to page 2 and brings the song into view. Snapshot state, so a
 * page already open behind Now Playing takes it too. A request nobody takes within
 * [FreshForMs] is dropped, so it can't hijack a later visit.
 *
 * Now Playing's About ("Manage in Scrapbook", owner 2026-11-02) adds the row to [Pending.edit]:
 * page 2 then opens the song's sheet with that row's editor.
 *
 * Library search and Home open an album's memory the same way, on no particular song
 * ([requestAlbum], owner 2026-11-20 #Yoin:#18): the album page that is that album moves to page 2.
 */
internal object ScrapbookFocusRequest {
    @Immutable
    data class Pending(val songId: String, val atMillis: Long, val edit: AlbumScrapbookAboutRef? = null)

    private const val FreshForMs = 10_000L

    private val state = mutableStateOf<Pending?>(null)

    /** The request waiting to be taken, if any (a snapshot read). */
    val pending: Pending? get() = state.value

    fun request(songId: String, edit: AlbumScrapbookAboutRef? = null) {
        state.value = Pending(songId, SystemClock.elapsedRealtime(), edit)
    }

    @Immutable
    data class PendingAlbum(val albumId: String, val atMillis: Long)

    private val albumState = mutableStateOf<PendingAlbum?>(null)

    /** The album-level request waiting to be taken, if any (a snapshot read). */
    val pendingAlbum: PendingAlbum? get() = albumState.value

    /** Page 2 of the album [albumId] (a [com.gpo.yoin.data.model.MediaId] string), on no particular song. */
    fun requestAlbum(albumId: String) {
        albumState.value = PendingAlbum(albumId, SystemClock.elapsedRealtime())
    }

    /** Takes the album-level request when it is for [albumId] and still fresh. */
    fun takeForAlbum(albumId: String): Boolean {
        val request = albumState.value ?: return false
        if (SystemClock.elapsedRealtime() - request.atMillis > FreshForMs) {
            albumState.value = null
            return false
        }
        if (request.albumId != albumId) return false
        albumState.value = null
        return true
    }

    /** Takes the request when it is for one of [songIds] and still fresh; null otherwise. */
    fun takeFor(songIds: Collection<String>): Pending? {
        val request = state.value ?: return null
        if (SystemClock.elapsedRealtime() - request.atMillis > FreshForMs) {
            state.value = null
            return null
        }
        if (request.songId !in songIds) return null
        state.value = null
        return request
    }
}
