package com.gpo.yoin.data.local

import androidx.room.Entity
import androidx.room.Index

/**
 * The lyric highlight Gemini Nano picked for one song (data/highlight/): one or two original lines and where
 * they start, plus enough of the track to show and play it without a network call.
 *
 * A row with a null [text] records a song that was tried and gave nothing usable (no lyrics, too few lines,
 * an answer that wasn't a valid range); [attemptedAt] lets it be retried after
 * [com.gpo.yoin.data.highlight.LyricHighlightProcessor.RETRY_UNUSABLE_AFTER_MS].
 *
 * Local only: not synced, not backed up as user data (it can always be picked again).
 */
@Entity(
    tableName = "lyric_highlights",
    primaryKeys = ["profileId", "provider", "songId"],
    indices = [Index(value = ["profileId", "provider", "lastShownAt"], name = "index_lyric_highlights_shown")],
)
data class LyricHighlight(
    val profileId: String,
    val provider: String,
    /** Raw track id, as play_history stores it. */
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    /** Blank when unknown. */
    val albumId: String,
    /** A [com.gpo.yoin.data.model.CoverRef] storage key. */
    val coverArtId: String?,
    val durationMs: Long,
    /** The picked lines joined with "\n"; null = tried, nothing usable. */
    val text: String?,
    /** Where the first picked line starts; null for unsynced lyrics (play from the top). */
    val startMs: Long?,
    /** [com.gpo.yoin.data.highlight.LyricHighlightPrompt.Prepared.fingerprint] of the lyric it was picked from. */
    val lyricsFingerprint: String?,
    /** Nano's base model name (getBaseModelName), e.g. to re-pick after a model upgrade. */
    val model: String?,
    val attemptedAt: Long,
    val lastShownAt: Long? = null,
)
