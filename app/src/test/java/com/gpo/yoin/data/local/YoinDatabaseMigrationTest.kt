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
                AppContainer.MIGRATION_12_13,
                AppContainer.MIGRATION_13_14,
                AppContainer.MIGRATION_14_15,
                AppContainer.MIGRATION_15_16,
                AppContainer.MIGRATION_16_17,
                AppContainer.MIGRATION_17_18,
                AppContainer.MIGRATION_18_19,
                AppContainer.MIGRATION_19_20,
                AppContainer.MIGRATION_20_21,
                AppContainer.MIGRATION_21_22,
                AppContainer.MIGRATION_22_23,
                AppContainer.MIGRATION_23_24,
                AppContainer.MIGRATION_24_25,
                AppContainer.MIGRATION_25_26,
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
                AppContainer.MIGRATION_12_13,
                AppContainer.MIGRATION_13_14,
                AppContainer.MIGRATION_14_15,
                AppContainer.MIGRATION_15_16,
                AppContainer.MIGRATION_16_17,
                AppContainer.MIGRATION_17_18,
                AppContainer.MIGRATION_18_19,
                AppContainer.MIGRATION_19_20,
                AppContainer.MIGRATION_20_21,
                AppContainer.MIGRATION_21_22,
                AppContainer.MIGRATION_22_23,
                AppContainer.MIGRATION_23_24,
                AppContainer.MIGRATION_24_25,
                AppContainer.MIGRATION_25_26,
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
            .observeForTrack("track-1", MediaId.PROVIDER_SUBSONIC, "")
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
                AppContainer.MIGRATION_12_13,
                AppContainer.MIGRATION_13_14,
                AppContainer.MIGRATION_14_15,
                AppContainer.MIGRATION_15_16,
                AppContainer.MIGRATION_16_17,
                AppContainer.MIGRATION_17_18,
                AppContainer.MIGRATION_18_19,
                AppContainer.MIGRATION_19_20,
                AppContainer.MIGRATION_20_21,
                AppContainer.MIGRATION_21_22,
                AppContainer.MIGRATION_22_23,
                AppContainer.MIGRATION_23_24,
                AppContainer.MIGRATION_24_25,
                AppContainer.MIGRATION_25_26,
            )
            .allowMainThreadQueries()
            .build()

        migrated.openHelper.writableDatabase

        val rows = migrated.songNoteDao()
            .observeForTrack("track-1", MediaId.PROVIDER_SUBSONIC, "")
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
                AppContainer.MIGRATION_12_13,
                AppContainer.MIGRATION_13_14,
                AppContainer.MIGRATION_14_15,
                AppContainer.MIGRATION_15_16,
                AppContainer.MIGRATION_16_17,
                AppContainer.MIGRATION_17_18,
                AppContainer.MIGRATION_18_19,
                AppContainer.MIGRATION_19_20,
                AppContainer.MIGRATION_20_21,
                AppContainer.MIGRATION_21_22,
                AppContainer.MIGRATION_22_23,
                AppContainer.MIGRATION_23_24,
                AppContainer.MIGRATION_24_25,
                AppContainer.MIGRATION_25_26,
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
            .observeForAlbum("album-1", MediaId.PROVIDER_SUBSONIC, "")
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
            .observe("album-1", MediaId.PROVIDER_SUBSONIC, "")
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
                AppContainer.MIGRATION_12_13,
                AppContainer.MIGRATION_13_14,
                AppContainer.MIGRATION_14_15,
                AppContainer.MIGRATION_15_16,
                AppContainer.MIGRATION_16_17,
                AppContainer.MIGRATION_17_18,
                AppContainer.MIGRATION_18_19,
                AppContainer.MIGRATION_19_20,
                AppContainer.MIGRATION_20_21,
                AppContainer.MIGRATION_21_22,
                AppContainer.MIGRATION_22_23,
                AppContainer.MIGRATION_23_24,
                AppContainer.MIGRATION_24_25,
                AppContainer.MIGRATION_25_26,
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
    fun should_drop_accessToken_column_when_migrating_11_to_13() = runTest {
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
            .addMigrations(
                AppContainer.MIGRATION_11_12,
                AppContainer.MIGRATION_12_13,
                AppContainer.MIGRATION_13_14,
                AppContainer.MIGRATION_14_15,
                AppContainer.MIGRATION_15_16,
                AppContainer.MIGRATION_16_17,
                AppContainer.MIGRATION_17_18,
                AppContainer.MIGRATION_18_19,
                AppContainer.MIGRATION_19_20,
                AppContainer.MIGRATION_20_21,
                AppContainer.MIGRATION_21_22,
                AppContainer.MIGRATION_22_23,
                AppContainer.MIGRATION_23_24,
                AppContainer.MIGRATION_24_25,
                AppContainer.MIGRATION_25_26,
            )
            .allowMainThreadQueries()
            .build()

        migrated.openHelper.writableDatabase
        val cfg = migrated.neoDbConfigDao().get()
        assertEquals("https://neodb.example", cfg?.instance)

        migrated.close()
    }

    @Test
    fun should_add_profile_id_columns_when_migrating_12_to_13() = runTest {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(12) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createVersion12Schema(db)
                            db.execSQL(
                                """
                                INSERT INTO `play_history`
                                    (`songId`, `provider`, `title`, `artist`, `album`, `albumId`,
                                     `coverArtId`, `playedAt`, `durationMs`, `completedPercent`)
                                VALUES
                                    ('song-1', 'subsonic', 'Song', 'Artist', 'Album', 'album-1',
                                     NULL, 1000, 180000, 0.8)
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                INSERT INTO `activity_events`
                                    (`entityType`, `actionType`, `entityId`, `provider`, `title`,
                                     `subtitle`, `coverArtId`, `songId`, `albumId`, `artistId`, `timestamp`)
                                VALUES
                                    ('ALBUM', 'PLAYED', 'album-1', 'subsonic', 'Album', 'Artist',
                                     NULL, 'song-1', 'album-1', NULL, 1000)
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
                AppContainer.MIGRATION_12_13,
                AppContainer.MIGRATION_13_14,
                AppContainer.MIGRATION_14_15,
                AppContainer.MIGRATION_15_16,
                AppContainer.MIGRATION_16_17,
                AppContainer.MIGRATION_17_18,
                AppContainer.MIGRATION_18_19,
                AppContainer.MIGRATION_19_20,
                AppContainer.MIGRATION_20_21,
                AppContainer.MIGRATION_21_22,
                AppContainer.MIGRATION_22_23,
                AppContainer.MIGRATION_23_24,
                AppContainer.MIGRATION_24_25,
                AppContainer.MIGRATION_25_26,
            )
            .allowMainThreadQueries()
            .build()

        val sqlDb = migrated.openHelper.writableDatabase
        sqlDb.query("SELECT `profileId` FROM `play_history` LIMIT 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("", cursor.getString(0))
        }
        sqlDb.query("SELECT `profileId` FROM `activity_events` LIMIT 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("", cursor.getString(0))
        }

        migrated.close()
    }

    @Test
    fun should_upgrade_local_track_ratings_to_ten_point_scale_when_migrating_13_to_14() = runTest {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(13) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createVersion13Schema(db)
                            db.execSQL(
                                """
                                INSERT INTO `local_ratings`
                                    (`songId`, `provider`, `rating`, `serverRating`, `needsSync`, `updatedAt`)
                                VALUES
                                    ('song-1', 'subsonic', 3.7, 4, 1, 1000),
                                    ('song-2', 'spotify', 5.0, 0, 0, 1001),
                                    ('song-3', 'subsonic', 0.0, 0, 0, 1002)
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
                AppContainer.MIGRATION_13_14,
                AppContainer.MIGRATION_14_15,
                AppContainer.MIGRATION_15_16,
                AppContainer.MIGRATION_16_17,
                AppContainer.MIGRATION_17_18,
                AppContainer.MIGRATION_18_19,
                AppContainer.MIGRATION_19_20,
                AppContainer.MIGRATION_20_21,
                AppContainer.MIGRATION_21_22,
                AppContainer.MIGRATION_22_23,
                AppContainer.MIGRATION_23_24,
                AppContainer.MIGRATION_24_25,
                AppContainer.MIGRATION_25_26,
            )
            .allowMainThreadQueries()
            .build()

        val ratings = migrated.localRatingDao()
            .getRatings(listOf("song-1", "song-2", "song-3"), MediaId.PROVIDER_SUBSONIC, "")
        val subsonicRating = ratings.first { it.songId == "song-1" }
        assertEquals(7.4f, subsonicRating.rating, 0.001f)
        assertEquals(4, subsonicRating.serverRating)

        val zeroRating = ratings.first { it.songId == "song-3" }
        assertEquals(0f, zeroRating.rating, 0.001f)

        val spotifyRatings = migrated.localRatingDao()
            .getRatings(listOf("song-2"), MediaId.PROVIDER_SPOTIFY, "")
        assertEquals(10f, spotifyRatings.single().rating, 0.001f)

        migrated.close()
    }

    @Test
    fun should_drop_song_info_and_create_song_about_entries_when_migrating_14_to_15() = runTest {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(14) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createVersion14Schema(db)
                            db.execSQL(
                                """
                                INSERT INTO `song_info`
                                    (`songId`, `provider`, `creationTime`, `creationLocation`,
                                     `lyricist`, `composer`, `producer`, `review`, `cachedAt`)
                                VALUES ('song-1', 'subsonic', '2024', 'LA', null, null, null, null, 0)
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
                AppContainer.MIGRATION_14_15,
                AppContainer.MIGRATION_15_16,
                AppContainer.MIGRATION_16_17,
                AppContainer.MIGRATION_17_18,
                AppContainer.MIGRATION_18_19,
                AppContainer.MIGRATION_19_20,
                AppContainer.MIGRATION_20_21,
                AppContainer.MIGRATION_21_22,
                AppContainer.MIGRATION_22_23,
                AppContainer.MIGRATION_23_24,
                AppContainer.MIGRATION_24_25,
                AppContainer.MIGRATION_25_26,
            )
            .allowMainThreadQueries()
            .build()

        val sqlDb = migrated.openHelper.writableDatabase
        sqlDb.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='song_info'",
        ).use { cursor ->
            assertEquals(0, cursor.count)
        }
        sqlDb.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='song_about_entries'",
        ).use { cursor ->
            assertEquals(1, cursor.count)
        }

        // Write-through round-trip on the new table: canonical + ask rows
        // coexist under the same (titleKey, artistKey, albumKey).
        sqlDb.execSQL(
            """
            INSERT INTO `song_about_entries`
                (titleKey, artistKey, albumKey, titleDisplay, artistDisplay, albumDisplay,
                 kind, entryKey, promptText, answerText, createdAt, updatedAt)
            VALUES
                ('fake love', 'drake', 'clb', 'Fake Love', 'Drake', 'CLB',
                 'canonical', 'creation_time', NULL, '2024', 1000, 1000),
                ('fake love', 'drake', 'clb', 'Fake Love', 'Drake', 'CLB',
                 'ask', 'what does the chorus mean?', 'What does the chorus mean?',
                 'Betrayal.', 2000, 2000)
            """.trimIndent(),
        )
        sqlDb.query(
            "SELECT COUNT(*) FROM `song_about_entries` WHERE titleKey = 'fake love'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }

        migrated.close()
    }

    @Test
    fun should_add_titleText_column_to_song_about_entries_when_migrating_15_to_16() = runTest {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(15) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createVersion14Schema(db)
                            AppContainer.MIGRATION_14_15.migrate(db)
                            db.execSQL(
                                """
                                INSERT INTO `song_about_entries`
                                    (titleKey, artistKey, albumKey, titleDisplay, artistDisplay,
                                     albumDisplay, kind, entryKey, promptText, answerText,
                                     createdAt, updatedAt)
                                VALUES
                                    ('fake love', 'drake', 'clb', 'Fake Love', 'Drake', 'CLB',
                                     'ask', 'what is this song about?', 'What is this song about?',
                                     'Betrayal.', 1000, 1000)
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
                AppContainer.MIGRATION_15_16,
                AppContainer.MIGRATION_16_17,
                AppContainer.MIGRATION_17_18,
                AppContainer.MIGRATION_18_19,
                AppContainer.MIGRATION_19_20,
                AppContainer.MIGRATION_20_21,
                AppContainer.MIGRATION_21_22,
                AppContainer.MIGRATION_22_23,
                AppContainer.MIGRATION_23_24,
                AppContainer.MIGRATION_24_25,
                AppContainer.MIGRATION_25_26,
            )
            .allowMainThreadQueries()
            .build()

        val sqlDb = migrated.openHelper.writableDatabase
        // New column exists + pre-migration rows have titleText = null.
        sqlDb.query(
            "SELECT titleText FROM `song_about_entries` WHERE entryKey = 'what is this song about?'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
        // Writing a titleText on a new row works end-to-end.
        sqlDb.execSQL(
            """
            INSERT INTO `song_about_entries`
                (titleKey, artistKey, albumKey, titleDisplay, artistDisplay,
                 albumDisplay, kind, entryKey, promptText, titleText, answerText,
                 createdAt, updatedAt)
            VALUES
                ('starlight', 'muse', 'bhr', 'Starlight', 'Muse', 'BHR',
                 'ask', 'why is it called starlight?',
                 'Why is it called Starlight?', 'Origin of the title',
                 'Matt Bellamy...', 3000, 3000)
            """.trimIndent(),
        )
        sqlDb.query(
            "SELECT titleText FROM `song_about_entries` WHERE titleKey = 'starlight'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Origin of the title", cursor.getString(0))
        }

        migrated.close()
    }

    @Test
    fun should_scope_private_memory_tables_by_profile_when_migrating_16_to_17() = runTest {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(16) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createVersion16Schema(db)
                            db.execSQL(
                                """
                                INSERT INTO `profiles`
                                    (`id`, `provider`, `displayName`, `credentialsJson`, `createdAt`)
                                VALUES
                                    ('sub-profile-a', 'subsonic', 'Sub A', '{}', 100),
                                    ('sub-profile-b', 'subsonic', 'Sub B', '{}', 200)
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                INSERT INTO `local_ratings`
                                    (`songId`, `provider`, `rating`, `serverRating`, `needsSync`, `updatedAt`)
                                VALUES ('song-1', 'subsonic', 8.0, 4, 1, 1000)
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                INSERT INTO `album_ratings`
                                    (`albumId`, `provider`, `rating`, `review`, `neoDbReviewUuid`,
                                     `ratingNeedsSync`, `reviewNeedsSync`, `updatedAt`)
                                VALUES ('album-1', 'subsonic', 8.0, 'album review', 'uuid-1', 0, 0, 1000)
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                INSERT INTO `song_notes`
                                    (`id`, `trackId`, `provider`, `content`, `createdAt`, `updatedAt`, `title`, `artist`)
                                VALUES ('note-1', 'song-1', 'subsonic', 'song note', 1000, 1000, 'Song', 'Artist')
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                INSERT INTO `album_notes`
                                    (`id`, `albumId`, `provider`, `content`, `createdAt`, `updatedAt`, `albumName`, `artist`)
                                VALUES ('album-note-1', 'album-1', 'subsonic', 'album note', 1000, 1000, 'Album', 'Artist')
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
                AppContainer.MIGRATION_16_17,
                AppContainer.MIGRATION_17_18,
                AppContainer.MIGRATION_18_19,
                AppContainer.MIGRATION_19_20,
                AppContainer.MIGRATION_20_21,
                AppContainer.MIGRATION_21_22,
                AppContainer.MIGRATION_22_23,
                AppContainer.MIGRATION_23_24,
                AppContainer.MIGRATION_24_25,
                AppContainer.MIGRATION_25_26,
            )
            .allowMainThreadQueries()
            .build()

        val rating = migrated.localRatingDao()
            .getRating("song-1", MediaId.PROVIDER_SUBSONIC, "sub-profile-a")
            .first()
        assertEquals(8.0f, rating?.rating ?: 0f, 0.001f)

        val otherProfileRating = migrated.localRatingDao()
            .getRating("song-1", MediaId.PROVIDER_SUBSONIC, "sub-profile-b")
            .first()
        assertEquals(null, otherProfileRating)

        val albumRating = migrated.albumRatingDao()
            .get("album-1", MediaId.PROVIDER_SUBSONIC, "sub-profile-a")
        assertEquals("album review", albumRating?.review)

        val songNotes = migrated.songNoteDao()
            .observeForTrack("song-1", MediaId.PROVIDER_SUBSONIC, "sub-profile-a")
            .first()
        assertEquals(listOf("song note"), songNotes.map(SongNote::content))

        val albumNotes = migrated.albumNoteDao()
            .observeForAlbum("album-1", MediaId.PROVIDER_SUBSONIC, "sub-profile-a")
            .first()
        assertEquals(listOf("album note"), albumNotes.map(AlbumNote::content))

        migrated.close()
    }

    @Test
    fun should_create_lyrics_translation_cache_when_migrating_18_to_19() = runTest {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(18) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createVersion18Schema(db)
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
                AppContainer.MIGRATION_18_19,
                AppContainer.MIGRATION_19_20,
                AppContainer.MIGRATION_20_21,
                AppContainer.MIGRATION_21_22,
                AppContainer.MIGRATION_22_23,
                AppContainer.MIGRATION_23_24,
                AppContainer.MIGRATION_24_25,
                AppContainer.MIGRATION_25_26,
            )
            .allowMainThreadQueries()
            .build()

        migrated.lyricsTranslationCacheDao().upsert(
            LyricsTranslationCache(
                trackProvider = "spotify",
                trackRawId = "track-1",
                sourceHash = "source-hash",
                targetLanguage = "Simplified Chinese",
                model = "gemini-3.1-flash-lite",
                translationsJson = """["第一句"]""",
                cachedAt = 1_000L,
            ),
        )
        val cached = migrated.lyricsTranslationCacheDao().get(
            trackProvider = "spotify",
            trackRawId = "track-1",
            sourceHash = "source-hash",
            targetLanguage = "Simplified Chinese",
            model = "gemini-3.1-flash-lite",
        )
        assertEquals("""["第一句"]""", cached?.translationsJson)

        migrated.close()
    }

    @Test
    fun should_scope_memory_copy_cache_by_profile_when_migrating_24_to_25() = runTest {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(24) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createVersion24Schema(db)
                            // 旧 PK 没有 profileId —— 这行没法可靠归属到某个
                            // profile，v25 会整表重建把它丢掉（文案可再生）。
                            db.execSQL(
                                """
                                INSERT INTO `memory_copy_cache`
                                    (`provider`, `entityType`, `entityId`, `copy`, `promptHash`, `generatedAt`)
                                VALUES ('subsonic', 'album', 'album-1', 'legacy copy', 'hash-1', 1000)
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                INSERT INTO `detail_cache`
                                    (`profileId`, `kind`, `entityId`, `json`, `cachedAt`, `accessedAt`)
                                VALUES ('sub-profile-a', 'album', 'album-1', '{}', 1000, 1000)
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
                AppContainer.MIGRATION_24_25,
                AppContainer.MIGRATION_25_26,
            )
            .allowMainThreadQueries()
            .build()

        val sqlDb = migrated.openHelper.writableDatabase
        // 重建表 = 丢掉无法归属 profile 的旧文案行。
        sqlDb.query("SELECT COUNT(*) FROM `memory_copy_cache`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        // detail_cache 行原样保留，新的 accessedAt 索引已建好。
        sqlDb.query("SELECT COUNT(*) FROM `detail_cache`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        sqlDb.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='index_detail_cache_accessedAt'",
        ).use { cursor ->
            assertEquals(1, cursor.count)
        }

        // 新 PK 按 profile 隔离：同一 (provider, entityType, entityId) 在
        // 两个 profile 下各存一份，互不覆盖。
        migrated.memoryCopyCacheDao().upsert(
            MemoryCopyCache(
                profileId = "sub-profile-a",
                provider = MediaId.PROVIDER_SUBSONIC,
                entityType = MemoryCopyCache.ENTITY_ALBUM,
                entityId = "album-1",
                copy = "copy for A",
                promptHash = "hash-a",
                generatedAt = 2000L,
            ),
        )
        migrated.memoryCopyCacheDao().upsert(
            MemoryCopyCache(
                profileId = "sub-profile-b",
                provider = MediaId.PROVIDER_SUBSONIC,
                entityType = MemoryCopyCache.ENTITY_ALBUM,
                entityId = "album-1",
                copy = "copy for B",
                promptHash = "hash-b",
                generatedAt = 2001L,
            ),
        )
        assertEquals(
            "copy for A",
            migrated.memoryCopyCacheDao()
                .get("sub-profile-a", MediaId.PROVIDER_SUBSONIC, MemoryCopyCache.ENTITY_ALBUM, "album-1")
                ?.copy,
        )
        assertEquals(
            "copy for B",
            migrated.memoryCopyCacheDao()
                .get("sub-profile-b", MediaId.PROVIDER_SUBSONIC, MemoryCopyCache.ENTITY_ALBUM, "album-1")
                ?.copy,
        )

        migrated.close()
    }

    private fun createVersion11Schema(db: SupportSQLiteDatabase) {
        createVersion10Schema(db)
        // 复用 AppContainer.MIGRATION_10_11 创建的 v11 新表结构。直接调
        // migrate() 而不是重写 SQL，避免 schema 漂移。
        AppContainer.MIGRATION_10_11.migrate(db)
    }

    private fun createVersion12Schema(db: SupportSQLiteDatabase) {
        createVersion11Schema(db)
        AppContainer.MIGRATION_11_12.migrate(db)
    }

    private fun createVersion13Schema(db: SupportSQLiteDatabase) {
        createVersion12Schema(db)
        AppContainer.MIGRATION_12_13.migrate(db)
    }

    private fun createVersion14Schema(db: SupportSQLiteDatabase) {
        createVersion13Schema(db)
        AppContainer.MIGRATION_13_14.migrate(db)
    }

    private fun createVersion15Schema(db: SupportSQLiteDatabase) {
        createVersion14Schema(db)
        AppContainer.MIGRATION_14_15.migrate(db)
    }

    private fun createVersion16Schema(db: SupportSQLiteDatabase) {
        createVersion15Schema(db)
        AppContainer.MIGRATION_15_16.migrate(db)
    }

    private fun createVersion18Schema(db: SupportSQLiteDatabase) {
        createVersion16Schema(db)
        AppContainer.MIGRATION_16_17.migrate(db)
        AppContainer.MIGRATION_17_18.migrate(db)
    }

    private fun createVersion24Schema(db: SupportSQLiteDatabase) {
        createVersion18Schema(db)
        AppContainer.MIGRATION_18_19.migrate(db)
        AppContainer.MIGRATION_19_20.migrate(db)
        AppContainer.MIGRATION_20_21.migrate(db)
        AppContainer.MIGRATION_21_22.migrate(db)
        AppContainer.MIGRATION_22_23.migrate(db)
        AppContainer.MIGRATION_23_24.migrate(db)
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
