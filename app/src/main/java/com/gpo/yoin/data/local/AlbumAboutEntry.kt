package com.gpo.yoin.data.local

import androidx.room.Entity

/**
 * One row of an album's About (the scrapbook's credits line and About card, owner + Carbon 2026-11-25):
 * the AI's credits and background blurb ([KIND_CANONICAL]) and the listener's Ask Gemini questions about
 * the album ([KIND_ASK]).
 *
 * Keyed by the album's identity on its service ([provider] + [albumId], the raw id of its `MediaId`), as
 * the owner asked, not by normalized metadata like [SongAboutEntry]: an album's credits belong to one
 * release (a deluxe edition, a reissue), and the grounding sent with the prompt (label, dates, version,
 * track list) comes from that release. Shared across profiles like the song rows: it is reference
 * material, not the listener's own data.
 *
 * Canonical rows: [entryKey] [CANON_CREDITS] holds the parsed credits as compact JSON
 * ([com.gpo.yoin.data.album.AlbumAiCredits]); [CANON_BLURB] the editorial paragraph. Ask rows: [entryKey]
 * is the normalized question, [promptText] the question as typed, [titleText] Gemini's short heading.
 *
 * Invalidation follows the song rows: kept until regenerated on demand or until the AI language changes
 * (every row is Gemini's, so a switch clears the table).
 */
@Entity(
    tableName = "album_about_entries",
    primaryKeys = ["provider", "albumId", "kind", "entryKey"],
)
data class AlbumAboutEntry(
    val provider: String,
    val albumId: String,
    val albumDisplay: String,
    val artistDisplay: String,
    val kind: String,
    val entryKey: String,
    val promptText: String?,
    val titleText: String?,
    val answerText: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val KIND_CANONICAL = "canonical"
        const val KIND_ASK = "ask"

        const val CANON_CREDITS = "credits"
        const val CANON_BLURB = "blurb"
    }
}
