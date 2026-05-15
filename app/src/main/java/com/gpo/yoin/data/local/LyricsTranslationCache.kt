package com.gpo.yoin.data.local

import androidx.room.Entity

/**
 * Gemini lyric translation cache, keyed by track plus the exact source lyrics
 * signal. Changing lyrics, target language, or model produces a fresh row.
 */
@Entity(
    tableName = "lyrics_translation_cache",
    primaryKeys = ["trackProvider", "trackRawId", "sourceHash", "targetLanguage", "model"],
)
data class LyricsTranslationCache(
    val trackProvider: String,
    val trackRawId: String,
    val sourceHash: String,
    val targetLanguage: String,
    val model: String,
    val translationsJson: String,
    val cachedAt: Long,
)
