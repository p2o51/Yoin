package com.gpo.yoin.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        LocalRating::class,
        CacheMetadata::class,
        PlayHistory::class,
        ActivityEvent::class,
        SongAboutEntry::class,
        GeminiConfig::class,
        Profile::class,
        SpotifyConfig::class,
        SpotifyHomeAlbumCache::class,
        SpotifyHomeArtistCache::class,
        SpotifyLibrarySyncMeta::class,
        SpotifyLibraryTrackCache::class,
        SpotifyLibraryAlbumCache::class,
        SpotifyLibraryArtistCache::class,
        SpotifyLibraryPlaylistCache::class,
        LyricsCache::class,
        LyricsTranslationCache::class,
        SongNote::class,
        AlbumNote::class,
        AlbumRating::class,
        ExternalMapping::class,
        MemoryCopyCache::class,
        NeoDBConfig::class,
        DetailCacheEntry::class,
        HomeLayoutPreference::class,
        HomeGridPoolCache::class,
    ],
    version = 28,
    exportSchema = true,
)
abstract class YoinDatabase : RoomDatabase() {
    abstract fun localRatingDao(): LocalRatingDao

    abstract fun cacheMetadataDao(): CacheMetadataDao

    abstract fun playHistoryDao(): PlayHistoryDao

    abstract fun activityEventDao(): ActivityEventDao

    abstract fun songAboutEntryDao(): SongAboutEntryDao

    abstract fun geminiConfigDao(): GeminiConfigDao

    abstract fun profileDao(): ProfileDao

    abstract fun spotifyConfigDao(): SpotifyConfigDao

    abstract fun spotifyHomeCacheDao(): SpotifyHomeCacheDao

    abstract fun spotifyLibraryCacheDao(): SpotifyLibraryCacheDao

    abstract fun lyricsCacheDao(): LyricsCacheDao

    abstract fun lyricsTranslationCacheDao(): LyricsTranslationCacheDao

    abstract fun songNoteDao(): SongNoteDao

    abstract fun albumNoteDao(): AlbumNoteDao

    abstract fun albumRatingDao(): AlbumRatingDao

    abstract fun externalMappingDao(): ExternalMappingDao

    abstract fun memoryCopyCacheDao(): MemoryCopyCacheDao

    abstract fun neoDbConfigDao(): NeoDBConfigDao

    abstract fun detailCacheDao(): DetailCacheDao

    abstract fun homeLayoutDao(): HomeLayoutDao

    abstract fun homeGridPoolDao(): HomeGridPoolDao
}
