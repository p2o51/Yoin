package com.gpo.yoin.data.local

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.testutil.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class YoinDatabaseMigrationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbName = "yoin-migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun should_create_spotify_home_cache_tables_when_migrating_6_to_7() = runTest {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(6) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createVersion6Schema(db)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        helper.writableDatabase.close()
        helper.close()

        val migrated = Room.databaseBuilder(context, YoinDatabase::class.java, dbName)
            .addMigrations(AppContainer.MIGRATION_6_7, AppContainer.MIGRATION_7_8)
            .allowMainThreadQueries()
            .build()

        migrated.openHelper.writableDatabase
        val albumTables = migrated.spotifyHomeCacheDao().getFreshAlbums("spotify-a", 0L)
        val artistTables = migrated.spotifyHomeCacheDao().getFreshArtists("spotify-a", 0L)

        assertTrue(albumTables.isEmpty())
        assertTrue(artistTables.isEmpty())

        migrated.spotifyHomeCacheDao().insertAlbums(
            listOf(
                SpotifyHomeAlbumCache(
                    profileId = "spotify-a",
                    albumId = MediaId.spotify("album-1").toString(),
                    name = "Album 1",
                    artist = "Artist 1",
                    artistId = MediaId.spotify("artist-1").toString(),
                    coverArtKey = "https://example.com/album-1.jpg",
                    songCount = 10,
                    year = 2024,
                    sortOrder = 0,
                    cachedAt = 1_000L,
                ),
            ),
        )
        migrated.spotifyHomeCacheDao().insertArtists(
            listOf(
                SpotifyHomeArtistCache(
                    profileId = "spotify-a",
                    artistId = MediaId.spotify("artist-1").toString(),
                    name = "Artist 1",
                    coverArtKey = "https://example.com/artist-1.jpg",
                    sortOrder = 0,
                    cachedAt = 1_000L,
                ),
            ),
        )

        assertEquals(
            1,
            migrated.spotifyHomeCacheDao().getFreshAlbums("spotify-a", 0L).size,
        )
        assertEquals(
            1,
            migrated.spotifyHomeCacheDao().getFreshArtists("spotify-a", 0L).size,
        )

        migrated.close()
    }

    private fun createVersion6Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_ratings` (
                `songId` TEXT NOT NULL,
                `provider` TEXT NOT NULL DEFAULT 'subsonic',
                `rating` REAL NOT NULL,
                `serverRating` INTEGER NOT NULL,
                `needsSync` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`songId`, `provider`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `cache_metadata` (
                `songId` TEXT NOT NULL,
                `provider` TEXT NOT NULL DEFAULT 'subsonic',
                `title` TEXT NOT NULL,
                `artist` TEXT NOT NULL,
                `album` TEXT NOT NULL,
                `fileSizeBytes` INTEGER NOT NULL,
                `cachedAt` INTEGER NOT NULL,
                `lastAccessedAt` INTEGER NOT NULL,
                PRIMARY KEY(`songId`, `provider`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `play_history` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `songId` TEXT NOT NULL,
                `provider` TEXT NOT NULL DEFAULT 'subsonic',
                `title` TEXT NOT NULL,
                `artist` TEXT NOT NULL,
                `album` TEXT NOT NULL,
                `albumId` TEXT NOT NULL,
                `coverArtId` TEXT,
                `playedAt` INTEGER NOT NULL,
                `durationMs` INTEGER NOT NULL,
                `completedPercent` REAL NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `activity_events` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `entityType` TEXT NOT NULL,
                `actionType` TEXT NOT NULL,
                `entityId` TEXT NOT NULL,
                `provider` TEXT NOT NULL DEFAULT 'subsonic',
                `title` TEXT NOT NULL,
                `subtitle` TEXT NOT NULL,
                `coverArtId` TEXT,
                `songId` TEXT,
                `albumId` TEXT,
                `artistId` TEXT,
                `timestamp` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `song_info` (
                `songId` TEXT NOT NULL,
                `provider` TEXT NOT NULL DEFAULT 'subsonic',
                `creationTime` TEXT,
                `creationLocation` TEXT,
                `lyricist` TEXT,
                `composer` TEXT,
                `producer` TEXT,
                `review` TEXT,
                `cachedAt` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`songId`, `provider`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `gemini_config` (
                `id` INTEGER NOT NULL PRIMARY KEY,
                `apiKey` TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `profiles` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `provider` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `credentialsJson` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `spotify_config` (
                `id` INTEGER NOT NULL PRIMARY KEY,
                `clientId` TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }
}
