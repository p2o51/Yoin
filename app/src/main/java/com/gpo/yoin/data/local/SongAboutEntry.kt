package com.gpo.yoin.data.local

import androidx.room.Entity
import androidx.room.Index

/**
 * Unified row for both canonical song About fields (Creation Time / Lyricist /
 * ...) and user-submitted Ask Gemini Q&A. Keyed by normalized
 * `title + artist + album` so that the same song shared across different
 * profiles / providers hits the same cached entries — Gemini Grounding is
 * metadata-driven, so provider-scoped keys would force redundant refetches.
 *
 * Canonical rows use [entryKey] = one of the CANON_* constants and leave
 * [promptText] null. Ask rows use the normalized question as [entryKey] and
 * keep the original question (pre-normalize) in [promptText] for display.
 */
@Entity(
    tableName = "song_about_entries",
    primaryKeys = ["titleKey", "artistKey", "albumKey", "kind", "entryKey"],
    indices = [
        Index(
            value = ["titleKey", "artistKey", "albumKey", "kind", "updatedAt"],
            name = "idx_about_lookup",
        ),
    ],
)
data class SongAboutEntry(
    val titleKey: String,
    val artistKey: String,
    val albumKey: String,
    val titleDisplay: String,
    val artistDisplay: String,
    val albumDisplay: String,
    val kind: String,
    val entryKey: String,
    val promptText: String?,
    /**
     * Gemini-generated concise headline for an `ask` row — "给我一个比较简洁
     * 的标题" rather than re-showing the user's raw question as the display
     * heading. Null on canonical rows (they use a fixed label per [entryKey])
     * and on ask rows written before the v15→v16 migration.
     */
    val titleText: String?,
    val answerText: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val KIND_CANONICAL = "canonical"
        const val KIND_ASK = "ask"

        const val CANON_CREATION_TIME = "creation_time"
        const val CANON_CREATION_LOCATION = "creation_location"
        const val CANON_LYRICIST = "lyricist"
        const val CANON_COMPOSER = "composer"
        const val CANON_PRODUCER = "producer"
        const val CANON_REVIEW = "review"

        /** Display order for canonical rows in the About UI. */
        val CANONICAL_ORDER: List<String> = listOf(
            CANON_CREATION_TIME,
            CANON_CREATION_LOCATION,
            CANON_LYRICIST,
            CANON_COMPOSER,
            CANON_PRODUCER,
            CANON_REVIEW,
        )

        /**
         * Cheap identity normalization so "Fake Love" / "fake love" /
         * "Fake Love (feat. X)" collapse to the same key. Not exhaustive —
         * remaster / remix / edition tags still slip through, which is
         * acceptable until real collisions show up in practice.
         */
        fun normalize(text: String): String =
            text.trim()
                .lowercase()
                .replace(FEAT_SUFFIX_REGEX, "")
                .replace(WHITESPACE_REGEX, " ")
                .trim()

        private val FEAT_SUFFIX_REGEX =
            Regex("\\s*\\(feat\\.[^)]*\\)", RegexOption.IGNORE_CASE)
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
