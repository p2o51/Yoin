package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface LyricsTranslationCacheDao {
    @Query(
        """
        SELECT * FROM lyrics_translation_cache
        WHERE trackProvider = :trackProvider
          AND trackRawId = :trackRawId
          AND sourceHash = :sourceHash
          AND targetLanguage = :targetLanguage
          AND model = :model
        LIMIT 1
        """,
    )
    suspend fun get(
        trackProvider: String,
        trackRawId: String,
        sourceHash: String,
        targetLanguage: String,
        model: String,
    ): LyricsTranslationCache?

    @Upsert
    suspend fun upsert(entry: LyricsTranslationCache)
}
