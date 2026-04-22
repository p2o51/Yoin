package com.gpo.yoin.data.local

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.testutil.MainDispatcherRule
import kotlinx.coroutines.flow.first
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
            .addMigrations(
                AppContainer.MIGRATION_6_7,
                AppContainer.MIGRATION_7_8,
                AppContainer.MIGRATION_8_9,
                AppContainer.MIGRATION_9_10,
                AppContainer.MIGRATION_10_11,
                AppContainer.MIGRATION_11_12,
            )
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

    @Test
    fun should_create_song_notes_table_when_migrating_8_to_10() = runTest {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(8) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createVersion8Schema(db)
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
            .addMigrations(
                AppContainer.MIGRATION_8_9,
                AppContainer.MIGRATION_9_10,
                AppContainer.MIGRATION_10_11,
                AppContainer.MIGRATION_11_12,
            )
            .allowMainThreadQueries()
            .build()

        migrated.openHelper.writableDatabase

        migrated.songNoteDao().insert(
            SongNote(
                id = "note-1",
                trackId = "track-1",
                provider = MediaId.PROVIDER_SUBSONIC,
                content = "hello",
                createdAt = 100L,
                updatedAt = 100L,
                title = "Song",
                artist = "Artist",
            ),
        )

        val observed = migrated.songNoteDao()
            .observeForTrack("track-1", MediaId.PROVIDER_SUBSONIC)
            .first()

        assertEquals(listOf("hello"), observed.map(SongNote::content))

        migrated.close()
    }

    @Test
    fun should_preserve_v9_note_rows_when_migrating_9_to_10() = runTest {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(9) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createVersion9Schema(db)
                            db.execSQL(
                                """
                                INSERT INTO `song_notes`
                                    (trackId, provider, content, createdAt, updatedAt, title, artist)
                                VALUES
                                    ('track-1', 'subsonic', 'legacy note', 100, 200, 'Song', 'Artist')
                                """.trimIndent(),
                            )
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
            .addMigrations(
                AppContainer.MIGRATION_9_10,
                AppContainer.MIGRATION_10_11,
                AppContainer.MIGRATION_11_12,
            )
            .allowMainThreadQueries()
            .build()

        migrated.openHelper.writableDatabase

        val rows = migrated.songNoteDao()
            .observeForTrack("track-1", MediaId.PROVIDER_SUBSONIC)
            .first()

        assertEquals(1, rows.size)
        val preserved = rows.single()
        assertEquals("legacy note", preserved.content)
        assertEquals(100L, preserved.createdAt)
        assertEquals(200L, preserved.updatedAt)
        assertTrue("migration must assign a non-empty synthetic id", preserved.id.isNotBlank())

        migrated.close()
    }

    @Test
    fun should_create_album_notes_and_ratings_when_migrating_10_to_11() = runTest {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(10) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createVersion10Schema(db)
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
            .addMigrations(
                AppContainer.MIGRATION_10_11,
                AppContainer.MIGRATION_11_12,
            )
            .allowMainThreadQueries()
            .build()

        migrated.openHelper.writableDatabase

        migrated.albumNoteDao().insert(
            AlbumNote(
                id = "album-note-1",
                albumId = "album-1",
                provider = MediaId.PROVIDER_SUBSONIC,
                content = "draft review",
                createdAt = 100L,
                updatedAt = 100L,
                albumName = "Album One",
                artist = "Artist One",
            ),
        )

        val notes = migrated.albumNoteDao()
            .observeForAlbum("album-1", MediaId.PROVIDER_SUBSONIC)
            .first()
        assertEquals(listOf("draft review"), notes.map(AlbumNote::content))

        migrated.albumRatingDao().upsert(
            AlbumRating(
                albumId = "album-1",
                provider = MediaId.PROVIDER_SUBSONIC,
                rating = 7.5f,
                review = "long review body",
                neoDbReviewUuid = null,
                ratingNeedsSync = true,
                reviewNeedsSync = true,
            ),
        )
        val rating = migrated.albumRatingDao()
            .observe("album-1", MediaId.PROVIDER_SUBSONIC)
            .first()
        assertEquals(7.5f, rating?.rating ?: 0f, 0.001f)
        assertEquals("long review body", rating?.review)
        assertTrue(rating?.ratingNeedsSync == true)
        assertTrue(rating?.reviewNeedsSync == true)

        migrated.close()
    }

    @Test
    fun should_allow_multiple_yoin_entities_to_share_one_external_uuid() = runTest {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(10) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createVersion10Schema(db)
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
            .addMigrations(
                AppContainer.MIGRATION_10_11,
                AppContainer.MIGRATION_11_12,
            )
            .allowMainThreadQueries()
            .build()

        migrated.openHelper.writableDatabase
        val dao = migrated.externalMappingDao()

        // 同一张专辑的 Subsonic 版 + Spotify 版共享同一个 NeoDB uuid。
        dao.upsert(
            ExternalMapping(
                externalService = ExternalMapping.SERVICE_NEODB,
                externalId = "uuid-shared",
                provider = MediaId.PROVIDER_SUBSONIC,
                entityType = ExternalMapping.ENTITY_ALBUM,
                entityId = "subsonic-album-1",
            ),
        )
        dao.upsert(
            ExternalMapping(
                externalService = ExternalMapping.SERVICE_NEODB,
                externalId = "uuid-shared",
                provider = MediaId.PROVIDER_SPOTIFY,
                entityType = ExternalMapping.ENTITY_ALBUM,
                entityId = "spotify-album-1",
            ),
        )

        // 正查：uuid → 所有挂在下面的 Yoin 实体
        val all = dao.findAllForExternalId(ExternalMapping.SERVICE_NEODB, "uuid-shared")
        assertEquals(2, all.size)
        assertTrue(all.any { it.provider == MediaId.PROVIDER_SUBSONIC })
        assertTrue(all.any { it.provider == MediaId.PROVIDER_SPOTIFY })

        // 反查：Yoin 实体 → uuid
        val subsonic = dao.findForYoinEntity(
            provider = MediaId.PROVIDER_SUBSONIC,
            entityType = ExternalMapping.ENTITY_ALBUM,
            entityId = "subsonic-album-1",
            service = ExternalMapping.SERVICE_NEODB,
        )
        assertEquals("uuid-shared", subsonic?.externalId)

        migrated.close()
    }

    @Test
    fun should_drop_accessToken_column_when_migrating_11_to_12() = runTest {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(11) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createVersion11Schema(db)
                            // 模拟 0.3 开发版留下的 token 行 —— v12 会把列
                            // 清掉（token 迁出 Room），但 instance 必须保留。
                            db.execSQL(
                                """
                                INSERT INTO `neodb_config` (`id`, `instance`, `accessToken`)
                                VALUES (1, 'https://neodb.example', 'legacy-token')
                                """.trimIndent(),
                            )
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
            .addMigrations(AppContainer.MIGRATION_11_12)
            .allowMainThreadQueries()
            .build()

        migrated.openHelper.writableDatabase
        val cfg = migrated.neoDbConfigDao().get()
        assertEquals("https://neodb.example", cfg?.instance)

        migrated.close()
    }

    private fun createVersion11Schema(db: SupportSQLiteDatabase) {
        createVersion10Schema(db)
        // 复用 AppContainer.MIGRATION_10_11 创建的 v11 新表结构。直接调
        // migrate() 而不是重写 SQL，避免 schema 漂移。
        AppContainer.MIGRATION_10_11.migrate(db)
    }

    private fun createVersion10Schema(db: SupportSQLiteDatabase) {
        createVersion9Schema(db)
        // v9 song_notes → v10 UUID-keyed song_notes. Minimal form needed
        // for migration-level tests that don't touch song_notes rows.
        db.execSQL("DROP TABLE IF EXISTS `song_notes`")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `song_notes` (
                `id` TEXT NOT NULL,
                `trackId` TEXT NOT NULL,
                `provider` TEXT NOT NULL DEFAULT 'subsonic',
                `content` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `title` TEXT NOT NULL,
                `artist` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_song_notes_title_artist`
            ON `song_notes` (`title`, `artist`)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_song_notes_trackId_provider`
            ON `song_notes` (`trackId`, `provider`)
            """.trimIndent(),
        )
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

    private fun createVersion8Schema(db: SupportSQLiteDatabase) {
        createVersion6Schema(db)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `spotify_home_album_cache` (
                `profileId` TEXT NOT NULL,
                `albumId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `artist` TEXT,
                `artistId` TEXT,
                `coverArtKey` TEXT,
                `songCount` INTEGER,
                `year` INTEGER,
                `sortOrder` INTEGER NOT NULL,
                `cachedAt` INTEGER NOT NULL,
                PRIMARY KEY(`profileId`, `albumId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `spotify_home_artist_cache` (
                `profileId` TEXT NOT NULL,
                `artistId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `coverArtKey` TEXT,
                `sortOrder` INTEGER NOT NULL,
                `cachedAt` INTEGER NOT NULL,
                PRIMARY KEY(`profileId`, `artistId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `lyrics_cache` (
                `trackProvider` TEXT NOT NULL,
                `trackRawId` TEXT NOT NULL,
                `lyricsProvider` TEXT NOT NULL,
                `lrc` TEXT NOT NULL,
                `cachedAt` INTEGER NOT NULL,
                PRIMARY KEY(`trackProvider`, `trackRawId`)
            )
            """.trimIndent(),
        )
    }

    private fun createVersion9Schema(db: SupportSQLiteDatabase) {
        createVersion8Schema(db)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `song_notes` (
                `trackId` TEXT NOT NULL,
                `provider` TEXT NOT NULL DEFAULT 'subsonic',
                `content` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `title` TEXT NOT NULL,
                `artist` TEXT NOT NULL,
                PRIMARY KEY(`trackId`, `provider`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_song_notes_title_artist`
            ON `song_notes` (`title`, `artist`)
            """.trimIndent(),
        )
    }
}
