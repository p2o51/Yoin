package com.gpo.yoin.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gemini_config")
data class GeminiConfig(
    @PrimaryKey val id: Int = 1,
    val apiKey: String,
    val targetLanguage: String = DEFAULT_TARGET_LANGUAGE,
) {
    companion object {
        const val DEFAULT_TARGET_LANGUAGE = "English"

        val SUPPORTED_TARGET_LANGUAGES: List<String> = listOf(
            "English",
            "Simplified Chinese",
            "Traditional Chinese",
            "Japanese",
            "Korean",
            "Spanish",
            "French",
            "German",
        )

        fun normalizeTargetLanguage(language: String?): String =
            SUPPORTED_TARGET_LANGUAGES.firstOrNull { it == language } ?: DEFAULT_TARGET_LANGUAGE
    }
}
