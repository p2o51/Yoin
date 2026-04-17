package com.gpo.yoin.data.model

sealed interface Lyrics {
    val language: String?

    data class Unsynced(
        val text: String,
        override val language: String? = null,
    ) : Lyrics

    data class Synced(
        val lines: List<LyricLine>,
        override val language: String? = null,
    ) : Lyrics
}

data class LyricLine(
    val startMs: Long,
    val text: String,
)
