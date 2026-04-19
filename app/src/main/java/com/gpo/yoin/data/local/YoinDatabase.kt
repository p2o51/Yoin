package com.gpo.yoin.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        LocalRating::class,
        CacheMetadata::class,
        PlayHistory::class,
        ActivityEvent::class,
        SongInfo::class,
        GeminiConfig::class,
        Profile::class,
        SpotifyConfig::class,
        SpotifyHomeAlbumCache::class,
        SpotifyHomeArtistCache::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class YoinDatabase : RoomDatabase() {
    abstract fun localRatingDao(): LocalRatingDao

    abstract fun cacheMetadataDao(): CacheMetadataDao

    abstract fun playHistoryDao(): PlayHistoryDao

    abstract fun activityEventDao(): ActivityEventDao

    abstract fun songInfoDao(): SongInfoDao

    abstract fun geminiConfigDao(): GeminiConfigDao

    abstract fun profileDao(): ProfileDao

    abstract fun spotifyConfigDao(): SpotifyConfigDao

    abstract fun spotifyHomeCacheDao(): SpotifyHomeCacheDao
}
