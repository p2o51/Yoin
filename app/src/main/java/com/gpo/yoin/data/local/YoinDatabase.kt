package com.gpo.yoin.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ServerConfig::class,
        LocalRating::class,
        CacheMetadata::class,
        PlayHistory::class,
        ActivityEvent::class,
        SongInfo::class,
        GeminiConfig::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class YoinDatabase : RoomDatabase() {
    abstract fun serverConfigDao(): ServerConfigDao

    abstract fun localRatingDao(): LocalRatingDao

    abstract fun cacheMetadataDao(): CacheMetadataDao

    abstract fun playHistoryDao(): PlayHistoryDao

    abstract fun activityEventDao(): ActivityEventDao

    abstract fun songInfoDao(): SongInfoDao

    abstract fun geminiConfigDao(): GeminiConfigDao
}
