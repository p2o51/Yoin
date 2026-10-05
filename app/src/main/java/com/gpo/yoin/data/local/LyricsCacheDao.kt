package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * `lyrics_cache` 的读写。新鲜度、用户选择标记和大小预算都在
 * [com.gpo.yoin.data.lyrics.LyricsCachePolicy]，这里不带任何时间过滤。
 */
@Dao
interface LyricsCacheDao {

    /** 任何年龄的行；能不能用由 `LyricsCachePolicy.isUsable` 判断。 */
    @Query(
        "SELECT * FROM lyrics_cache " +
            "WHERE trackProvider = :trackProvider AND trackRawId = :trackRawId " +
            "LIMIT 1",
    )
    suspend fun get(trackProvider: String, trackRawId: String): LyricsCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: LyricsCache)

    /**
     * 把一行的 `cachedAt` 往后挪到 [usedAt]（只往后、不往前）。只给用户选的行用：
     * 对它们 `cachedAt` 表示"最近一次用到"；自动行的 `cachedAt` 是 TTL 起点，不能碰。
     */
    @Query(
        "UPDATE lyrics_cache SET cachedAt = :usedAt " +
            "WHERE trackProvider = :trackProvider AND trackRawId = :trackRawId " +
            "AND cachedAt < :usedAt",
    )
    suspend fun markUsed(trackProvider: String, trackRawId: String, usedAt: Long): Int

    /** 每行歌词的 UTF-8 字节数，外加判断用户选择和淘汰顺序要的列。不读 lrc 本身。 */
    @Query(
        "SELECT trackProvider, trackRawId, lyricsProvider, lyricsProviderSongId, cachedAt, " +
            "length(CAST(lrc AS BLOB)) AS lrcBytes " +
            "FROM lyrics_cache",
    )
    suspend fun lyricsFootprints(): List<LyricsCacheFootprintRow>

    /** 每首歌免费的 `provider:*` 译文行合计的 UTF-8 字节数。付费（Gemini）行不在预算里。 */
    @Query(
        "SELECT trackProvider, trackRawId, " +
            "SUM(length(CAST(translationsJson AS BLOB))) AS bytes, MAX(cachedAt) AS lastCachedAt " +
            "FROM lyrics_translation_cache " +
            "WHERE model LIKE 'provider:%' " +
            "GROUP BY trackProvider, trackRawId",
    )
    suspend fun providerTranslationFootprints(): List<LyricsTranslationFootprintRow>

    /** 只删 `cachedAt` 还是 [cachedAt] 的那一行；这期间被重写过的行留着。返回删掉的行数。 */
    @Query(
        "DELETE FROM lyrics_cache " +
            "WHERE trackProvider = :trackProvider AND trackRawId = :trackRawId " +
            "AND cachedAt = :cachedAt",
    )
    suspend fun deleteIfUnchanged(trackProvider: String, trackRawId: String, cachedAt: Long): Int

    /** 删这首歌免费的 `provider:*` 译文行（能重新拉）。付费行不动。 */
    @Query(
        "DELETE FROM lyrics_translation_cache " +
            "WHERE trackProvider = :trackProvider AND trackRawId = :trackRawId " +
            "AND model LIKE 'provider:%'",
    )
    suspend fun deleteProviderTranslations(trackProvider: String, trackRawId: String): Int
}

data class LyricsCacheFootprintRow(
    val trackProvider: String,
    val trackRawId: String,
    val lyricsProvider: String,
    val lyricsProviderSongId: String?,
    val cachedAt: Long,
    val lrcBytes: Long,
)

data class LyricsTranslationFootprintRow(
    val trackProvider: String,
    val trackRawId: String,
    val bytes: Long,
    val lastCachedAt: Long,
)
