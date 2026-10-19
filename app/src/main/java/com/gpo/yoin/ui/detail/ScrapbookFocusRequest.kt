package com.gpo.yoin.ui.detail

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf

/**
 * "View in Scrapbook" from a song sheet outside the album page (Now Playing's cover): the sheet
 * leaves this request and opens the album the usual way (Go to album); the album page that
 * lists the song takes it, moves to page 2 and brings the song into view. Snapshot state, so a
 * page already open behind Now Playing takes it too. A request nobody takes within
 * [FreshForMs] is dropped, so it can't hijack a later visit.
 */
internal object ScrapbookFocusRequest {
    @Immutable
    data class Pending(val songId: String, val atMillis: Long)

    private const val FreshForMs = 10_000L

    private val state = mutableStateOf<Pending?>(null)

    /** The request waiting to be taken, if any (a snapshot read). */
    val pending: Pending? get() = state.value

    fun request(songId: String) {
        state.value = Pending(songId, SystemClock.elapsedRealtime())
    }

    /** Takes the request when it is for one of [songIds] and still fresh; null otherwise. */
    fun takeFor(songIds: Collection<String>): String? {
        val request = state.value ?: return null
        if (SystemClock.elapsedRealtime() - request.atMillis > FreshForMs) {
            state.value = null
            return null
        }
        if (request.songId !in songIds) return null
        state.value = null
        return request.songId
    }
}
