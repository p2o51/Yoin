package com.gpo.yoin.ui.nowplaying

/**
 * The 4Hz playhead tagged with the song it belongs to. Both fields come from
 * ONE PlaybackState emission, so a reader can tell "this position is the next
 * song's" even while the (slower, multi-source) uiState still describes the
 * previous one.
 */
data class NowPlayingPlayhead(
    val songId: String?,
    val positionMs: Long,
)

/**
 * Hands each screen the position of the song it is DRAWING, not of whatever
 * the player has already moved on to.
 *
 * The playhead flow is a one-hop eager projection; uiState is a multi-source
 * combine that lands a few dispatches later. At a song change there are
 * frames where the screen still renders song A while the playhead already
 * reads song B at ~0ms. Read raw, the lyrics view jumped A's focus back to its
 * title, recorded ~0 as A's final position and so judged the outro hand-over
 * "not reached": the next-song continuation fell back to a plain slide (the
 * owner saw the hand-over once in many tries). This holds A's last real
 * position until the screen itself moves to B.
 *
 * Mutable bookkeeping, no snapshot state: [resolve] runs inside the leaves'
 * derivedStateOf readers, which already track the two snapshot reads that
 * feed it (the playhead State and the rendered song id).
 */
internal class SongScopedPosition {
    private var lastSongId: String? = null
    private var lastPositionMs: Long = 0L

    fun resolve(sample: NowPlayingPlayhead, renderedSongId: String?): Long {
        if (renderedSongId == null || sample.songId == null || sample.songId == renderedSongId) {
            lastSongId = sample.songId
            lastPositionMs = sample.positionMs
            return sample.positionMs
        }
        // The player is ahead of the screen: keep the drawn song where it was.
        // (Screen ahead of the player — not expected, but harmless — reads as
        // the new song's very start.)
        return if (lastSongId == renderedSongId) lastPositionMs else 0L
    }
}
