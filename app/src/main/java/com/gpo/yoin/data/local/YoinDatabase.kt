package com.gpo.yoin.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ServerConfig::class,
        LocalRating::class,
        CacheMetadata::class,
        PlayHistory::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class YoinDatabase : RoomDatabase() {
    abstract fun serverConfigDao(): ServerConfigDao

    abstract fun localRatingDao(): LocalRatingDao

    abstract fun cacheMetadataDao(): CacheMetadataDao

    abstract fun playHistoryDao(): PlayHistoryDao
}
