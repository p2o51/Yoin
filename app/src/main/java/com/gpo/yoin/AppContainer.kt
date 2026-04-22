package com.gpo.yoin

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.lyrics.LyricsProviderRegistry
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.profile.AndroidKeyStoreCredentialsCipher
import com.gpo.yoin.data.profile.EncryptedProfileCredentialsCodec
import com.gpo.yoin.data.profile.FileBackedProfileCredentialsStore
import com.gpo.yoin.data.profile.PlaintextProfileCredentialsCodec
import com.gpo.yoin.data.profile.ProfileCredentialsCodec
import com.gpo.yoin.data.profile.ProfileCredentialsStore
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.profile.SharedPrefsProfileActiveIdStore
import com.gpo.yoin.data.profile.SpotifyProviderStatus
import com.gpo.yoin.data.integration.neodb.NeoDBApi
import com.gpo.yoin.data.integration.neodb.NeoDBSyncService
import com.gpo.yoin.data.integration.neodb.NeoDbTokenStore
import com.gpo.yoin.data.remote.GeminiService
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.spotify.SpotifyAuthConfig
import com.gpo.yoin.player.AudioVisualizerManager
import com.gpo.yoin.player.CastManager
import com.gpo.yoin.player.PlaybackEvent
import com.gpo.yoin.player.PlaybackManager
import com.gpo.yoin.player.SpotifyConnectFailure
import com.gpo.yoin.ui.experience.ExperienceSessionStore
import com.gpo.yoin.ui.experience.MotionCapabilityProvider
import com.gpo.yoin.ui.memories.MemoriesDeckCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(private val context: Context) {

    private val applicationScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: YoinDatabase by lazy {
        Room.databaseBuilder(context, YoinDatabase::class.java, "yoin-database")
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
            )
            // v11 冻结了 0.3 schema；0.5 上架前的备份降级保险（用户拿着 v11
            // 备份在旧版设备恢复）走这条：数据丢但应用不崩。没数据丢失比
            // 崩溃到用户面前可接受。
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = false)
            .build()
    }

    private val _musicConfigurationRevision = MutableStateFlow(0L)
    private val _playlistMutationRevision = MutableStateFlow(0L)

    /**
     * Increments whenever the active profile's configuration changes (switch,
     * credential edit of the active profile, delete-of-active). Feature VMs
     * subscribe via `YoinNavHost` to refresh their content.
     */
    val musicConfigurationRevision: StateFlow<Long> = _musicConfigurationRevision.asStateFlow()
    val playlistMutationRevision: StateFlow<Long> = _playlistMutationRevision.asStateFlow()

    fun notifyMusicConfigurationChanged() {
        _musicConfigurationRevision.value += 1
    }

    fun notifyPlaylistMutation() {
        _playlistMutationRevision.value += 1
    }

    /**
     * Legacy plaintext codec — used only by the one-shot startup
     * migration to thaw inline `credentialsJson` blobs persisted by
     * pre-3D builds. Steady-state read/write traffic goes through
     * [credentialsStore], not this codec.
     */
    private val legacyCredentialsCodec: ProfileCredentialsCodec = PlaintextProfileCredentialsCodec()

    /**
     * Encrypted credential persistence (Batch 3D).
     *
     * Files live under `noBackupFilesDir/profile_credentials/` so
     * Android's auto-backup pipeline excludes them — secrets stay
     * device-local and require re-authorisation after a device restore,
     * even though the profile metadata in Room rides the backup.
     *
     * Wire-up order: [AndroidKeyStoreCredentialsCipher] (raw AES-GCM) →
     * [EncryptedProfileCredentialsCodec] (envelope) →
     * [FileBackedProfileCredentialsStore] (per-profile `.bin` files).
     */
    private val credentialsStore: ProfileCredentialsStore by lazy {
        FileBackedProfileCredentialsStore(
            storageDir = java.io.File(context.noBackupFilesDir, "profile_credentials"),
            codec = EncryptedProfileCredentialsCodec(
                cipher = AndroidKeyStoreCredentialsCipher(),
            ),
        )
    }

    private val spotifyHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cache(
                Cache(
                    directory = File(context.cacheDir, "http/spotify"),
                    maxSize = ProviderHttpCacheBytes,
                ),
            )
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val subsonicBaseHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cache(
                Cache(
                    directory = File(context.cacheDir, "http/subsonic"),
                    maxSize = ProviderHttpCacheBytes,
                ),
            )
            .build()
    }

    /**
     * Current Spotify OAuth client id. Resolves user-entered value from the
     * `spotify_config` Room row; falls back to the dev-time
     * `BuildConfig.SPOTIFY_CLIENT_ID` when the user hasn't set one. Refresh
     * loops and the OAuth activity both read this.
     */
    val spotifyClientIdFlow: StateFlow<String> by lazy {
        database.spotifyConfigDao().getConfig()
            .map { it?.clientId?.takeIf { id -> id.isNotBlank() } ?: SpotifyAuthConfig.FALLBACK_CLIENT_ID }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = SpotifyAuthConfig.FALLBACK_CLIENT_ID,
            )
    }

    init {
        // Force the lazy `spotifyClientIdFlow` to resolve at container
        // construction time so `SharingStarted.Eagerly` begins collecting
        // from Room immediately. Without this, the first reader (usually
        // PlaybackManager's warmConnection on cold start) triggers the
        // lazy init itself and has to race against the same IO threads
        // the Batch 3D migration is using — resulting in "Client ID not
        // configured" banners on upgrade launches even though the row
        // is populated. Touching the field here ensures Room starts
        // emitting before any other code path needs `.value`.
        @Suppress("UNUSED_EXPRESSION")
        spotifyClientIdFlow
    }

    /**
     * Latest non-transient connect failure for the Spotify backend. Drives
     * the sticky parts of [spotifyProviderStatus] (app-missing / premium
     * required / auth failure). Transient transport errors are kept out of
     * this store so retrying a connection doesn't need a manual reset.
     */
    private val _lastSpotifyStickyFailure = MutableStateFlow<SpotifyConnectFailure?>(null)

    /**
     * Single source of truth for "can the user use Spotify right now?".
     * Composed from:
     *  - active profile's provider (only evaluated when Spotify is active)
     *  - [spotifyClientIdFlow]: any blank value produces [SpotifyProviderStatus.NoClientId]
     *  - [_lastSpotifyStickyFailure]: mapped into the corresponding status
     *    variant, or `Ready` when nothing sticky has been observed
     *
     * UI consumers should read this rather than independently deriving
     * equivalent checks from `spotifyClientIdFlow` +
     * `PlaybackEvent.SpotifyConnectError`.
     */
    val spotifyProviderStatus: StateFlow<SpotifyProviderStatus> by lazy {
        // Listen once — events come through PlaybackManager as the SDK
        // dispatches them; the sticky slot is the only cache we want.
        applicationScope.launch {
            playbackManager.events.collect { event ->
                when (event) {
                    is PlaybackEvent.SpotifyConnectError -> {
                        // Drop TransportFailure from the sticky slot — those
                        // usually recover on the next connect, no point
                        // surfacing a persistent badge for them.
                        val failure = event.failure
                        if (failure !is SpotifyConnectFailure.TransportFailure) {
                            _lastSpotifyStickyFailure.value = failure
                        }
                    }
                    is PlaybackEvent.SpotifyActionRequired -> Unit
                }
            }
        }
        // Clear the sticky slot when the client id becomes non-blank — user
        // probably just fixed the obvious cause, and we don't want a stale
        // AuthFailure to linger through the next successful connect.
        applicationScope.launch {
            spotifyClientIdFlow.collect { clientId ->
                if (clientId.isNotBlank() &&
                    _lastSpotifyStickyFailure.value == SpotifyConnectFailure.NoClientId
                ) {
                    _lastSpotifyStickyFailure.value = null
                }
            }
        }

        combine(
            profileManager.activeSource,
            spotifyClientIdFlow,
            _lastSpotifyStickyFailure,
            profileManager.reconnectReason,
        ) { source, clientId, lastFailure, reconnectReason ->
            val isSpotify = source?.id == MediaId.PROVIDER_SPOTIFY
            when {
                !isSpotify -> SpotifyProviderStatus.Ready
                // NeedsReconnect 优先级高于 clientId / lastFailure：换机恢复
                // 和 invalid_grant 都要求重授权，UI 走统一入口。
                reconnectReason != null -> SpotifyProviderStatus.NeedsReconnect(reconnectReason)
                clientId.isBlank() -> SpotifyProviderStatus.NoClientId
                lastFailure == null -> SpotifyProviderStatus.Ready
                lastFailure is SpotifyConnectFailure.NoClientId ->
                    SpotifyProviderStatus.NoClientId
                lastFailure is SpotifyConnectFailure.SpotifyAppMissing ->
                    SpotifyProviderStatus.SpotifyAppMissing
                lastFailure is SpotifyConnectFailure.PremiumRequired ->
                    SpotifyProviderStatus.NoPremium
                lastFailure is SpotifyConnectFailure.AuthFailure ->
                    SpotifyProviderStatus.AuthFailure(lastFailure.message)
                else -> SpotifyProviderStatus.Ready
            }
        }.stateIn(
            scope = applicationScope,
            started = SharingStarted.Eagerly,
            initialValue = SpotifyProviderStatus.Ready,
        )
    }

    val profileManager: ProfileManager by lazy {
        ProfileManager(
            profileDao = database.profileDao(),
            activeIdStore = SharedPrefsProfileActiveIdStore(context),
            credentialsStore = credentialsStore,
            legacyCodec = legacyCredentialsCodec,
            scope = applicationScope,
            spotifyClientIdProvider = { spotifyClientIdFlow.value },
            spotifyHttpClientProvider = { spotifyHttpClient },
            subsonicBaseHttpClientProvider = { subsonicBaseHttpClient },
            onSwitchPrepare = {
                // Tear down the current playback session — its stream URLs
                // are scoped to the outgoing profile.
                playbackManager.disconnect()
            },
            onSwitchCommit = {
                // Fan out to feature VMs so the first render after the switch
                // shows new-profile content.
                notifyMusicConfigurationChanged()
            },
        ).also { manager ->
            applicationScope.launch {
                // One-shot carry-forward: pre-3D inline credentialsJson
                // blobs → encrypted file store + marker rows.
                manager.runStartupMigrations()
            }
        }
    }

    val castManager: CastManager by lazy {
        CastManager(context).also { it.initialize() }
    }

    val playbackManager: PlaybackManager by lazy {
        PlaybackManager(
            context = context,
            repository = repository,
            castManager = castManager,
            spotifyClientIdProvider = { spotifyClientIdFlow.value },
        )
    }

    val audioVisualizerManager: AudioVisualizerManager by lazy {
        AudioVisualizerManager()
    }

    val experienceSessionStore: ExperienceSessionStore by lazy {
        ExperienceSessionStore()
    }

    val motionCapabilityProvider: MotionCapabilityProvider by lazy {
        MotionCapabilityProvider(context.applicationContext)
    }

    val geminiService: GeminiService by lazy {
        GeminiService(
            client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .build(),
            json = Json { ignoreUnknownKeys = true; isLenient = true },
        )
    }

    private val neoDbJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val neoDbHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // NeoDBApi 是 internal（和 internal DTO 对齐），AppContainer 只在内部
    // 构造 NeoDBSyncService 时用一次，所以这里直接 private 不对外。
    private val neoDbApi: NeoDBApi by lazy {
        NeoDBApi(client = neoDbHttpClient, json = neoDbJson)
    }

    /**
     * NeoDB token 落在 `noBackupFilesDir/neodb/token.bin`，换机恢复后需要
     * 重新登录 —— 和 profile credentials 对齐，走同一把 AES-GCM key。
     */
    internal val neoDbTokenStore: NeoDbTokenStore by lazy {
        NeoDbTokenStore(
            cipher = AndroidKeyStoreCredentialsCipher(),
            storageDir = java.io.File(context.noBackupFilesDir, "neodb"),
        )
    }

    val neoDbSyncService: NeoDBSyncService by lazy {
        NeoDBSyncService(
            api = neoDbApi,
            configDao = database.neoDbConfigDao(),
            tokenStore = neoDbTokenStore,
            mappingDao = database.externalMappingDao(),
            albumRatingDao = database.albumRatingDao(),
        )
    }

    val memoriesDeckCoordinator: MemoriesDeckCoordinator by lazy {
        MemoriesDeckCoordinator(
            repository = repository,
            sessionStore = experienceSessionStore,
        )
    }

    val lyricsProviderRegistry: LyricsProviderRegistry by lazy { LyricsProviderRegistry() }

    val repository: YoinRepository by lazy {
        YoinRepository(
            activeSource = profileManager.activeSource,
            activeProfileId = profileManager.activeProfileId,
            database = database,
            geminiService = geminiService,
            songInfoDao = database.songInfoDao(),
            geminiConfigDao = database.geminiConfigDao(),
            lyricsCacheDao = database.lyricsCacheDao(),
            songNoteDao = database.songNoteDao(),
            albumNoteDao = database.albumNoteDao(),
            albumRatingDao = database.albumRatingDao(),
            memoryCopyCacheDao = database.memoryCopyCacheDao(),
            neoDbSyncService = neoDbSyncService,
            lyricsProviderRegistry = lyricsProviderRegistry,
        )
    }

    companion object {
        const val ProviderHttpCacheBytes: Long = 50L * 1024L * 1024L

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `activity_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `entityType` TEXT NOT NULL,
                        `actionType` TEXT NOT NULL,
                        `entityId` TEXT NOT NULL,
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
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `song_info` (
                        `songId` TEXT NOT NULL PRIMARY KEY,
                        `creationTime` TEXT,
                        `creationLocation` TEXT,
                        `lyricist` TEXT,
                        `composer` TEXT,
                        `producer` TEXT,
                        `review` TEXT,
                        `cachedAt` INTEGER NOT NULL DEFAULT 0
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
            }
        }

        // v3 → v4: add `provider` column to every table that stores a remote
        // entity id, so Subsonic / Spotify / ... data can coexist. Existing
        // rows are tagged "subsonic". Also introduces the `profiles` table.
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // local_ratings: rebuild with composite PK (songId, provider)
                db.execSQL(
                    """
                    CREATE TABLE `local_ratings_new` (
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
                    INSERT INTO `local_ratings_new`
                        (`songId`, `provider`, `rating`, `serverRating`, `needsSync`, `updatedAt`)
                    SELECT `songId`, 'subsonic', `rating`, `serverRating`, `needsSync`, `updatedAt`
                    FROM `local_ratings`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `local_ratings`")
                db.execSQL("ALTER TABLE `local_ratings_new` RENAME TO `local_ratings`")

                // song_info: rebuild with composite PK
                db.execSQL(
                    """
                    CREATE TABLE `song_info_new` (
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
                    INSERT INTO `song_info_new`
                        (`songId`, `provider`, `creationTime`, `creationLocation`,
                         `lyricist`, `composer`, `producer`, `review`, `cachedAt`)
                    SELECT `songId`, 'subsonic', `creationTime`, `creationLocation`,
                           `lyricist`, `composer`, `producer`, `review`, `cachedAt`
                    FROM `song_info`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `song_info`")
                db.execSQL("ALTER TABLE `song_info_new` RENAME TO `song_info`")

                // cache_metadata: rebuild with composite PK
                db.execSQL(
                    """
                    CREATE TABLE `cache_metadata_new` (
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
                    INSERT INTO `cache_metadata_new`
                        (`songId`, `provider`, `title`, `artist`, `album`,
                         `fileSizeBytes`, `cachedAt`, `lastAccessedAt`)
                    SELECT `songId`, 'subsonic', `title`, `artist`, `album`,
                           `fileSizeBytes`, `cachedAt`, `lastAccessedAt`
                    FROM `cache_metadata`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `cache_metadata`")
                db.execSQL("ALTER TABLE `cache_metadata_new` RENAME TO `cache_metadata`")

                // play_history: add `provider` column in place
                db.execSQL(
                    """
                    ALTER TABLE `play_history`
                    ADD COLUMN `provider` TEXT NOT NULL DEFAULT 'subsonic'
                    """.trimIndent(),
                )

                // activity_events: add `provider` column in place
                db.execSQL(
                    """
                    ALTER TABLE `activity_events`
                    ADD COLUMN `provider` TEXT NOT NULL DEFAULT 'subsonic'
                    """.trimIndent(),
                )

                // profiles: new table
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
            }
        }

        // v4 → v5: single-row spotify_config table for user-supplied OAuth client id.
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
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

        // v5 → v6: drop legacy single-row server_config table. Batch 3D moved
        // credentials into profile rows + encrypted file store; the remaining
        // migration tolerates inline profile blobs, but we no longer carry the
        // pre-profile plaintext table forward.
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `server_config`")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
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
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
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
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
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

        /**
         * v10 → v11（0.3 冻结 schema，为 0.4 抛光期打基础）：
         *
         *  - `album_notes`: 专辑级多条笔记（镜像 song_notes 结构）。
         *  - `album_ratings`: 专辑 rating + review；NeoDB 双向同步的本地真源，
         *    分离 ratingNeedsSync / reviewNeedsSync 因为 Mark vs Review
         *    是两个 NeoDB 资源，离线写入时可能只脏一边。
         *  - `external_mappings`: Yoin 实体 ↔ 第三方 uuid（目前仅 NeoDB），
         *    复合 PK (provider, entityType, entityId, externalService)。
         *  - `memory_copy_cache`: 「余音 Gemini 文案」缓存；按
         *    (provider, entityType, entityId) 唯一。
         *  - `neodb_config`: 单行 BYOK 配置。v11 里还带 `accessToken` 列
         *    —— v12 会把它干掉，token 迁到 noBackupFilesDir 里的加密文件。
         *  - activity_events 加 index(provider, timestamp) 跑 Memory 抽样时
         *    不再全表扫；加 AFTER INSERT trigger 强制总条数 ≤ 10000
         *    （按 provider 分别滚动淘汰）。
         *  - play_history 加 index(provider, playedAt) 辅助历史页。
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `album_notes` (
                        `id` TEXT NOT NULL,
                        `albumId` TEXT NOT NULL,
                        `provider` TEXT NOT NULL DEFAULT 'subsonic',
                        `content` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `albumName` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                // v11 新表的 DEFAULT 只出现在 provider 列（和 entity 上的
                // `@ColumnInfo(defaultValue = …)` 对齐）。其它列故意不带
                // SQL DEFAULT，防止 Room schema 校验报 "expected DEFAULT …"。
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_album_notes_albumId_provider`
                    ON `album_notes` (`albumId`, `provider`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_album_notes_albumName_artist`
                    ON `album_notes` (`albumName`, `artist`)
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `album_ratings` (
                        `albumId` TEXT NOT NULL,
                        `provider` TEXT NOT NULL DEFAULT 'subsonic',
                        `rating` REAL NOT NULL,
                        `review` TEXT,
                        `neoDbReviewUuid` TEXT,
                        `ratingNeedsSync` INTEGER NOT NULL,
                        `reviewNeedsSync` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`albumId`, `provider`)
                    )
                    """.trimIndent(),
                )

                // external_mappings 是「多对一」关联表：多个 Yoin 实体可以
                // 共享同一个 externalId（Subsonic 版 + Spotify 版同一张专辑
                // 指向同一个 NeoDB uuid）。PK 以 (externalService, externalId,
                // ...) 打头锚定 external 侧，正查走 PK 前缀；反查走
                // UNIQUE INDEX 保证「每个 Yoin 实体 per service 最多一个 uuid」。
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `external_mappings` (
                        `externalService` TEXT NOT NULL,
                        `externalId` TEXT NOT NULL,
                        `provider` TEXT NOT NULL,
                        `entityType` TEXT NOT NULL,
                        `entityId` TEXT NOT NULL,
                        `syncedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`externalService`, `externalId`, `provider`, `entityType`, `entityId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_external_mappings_yoin_entity`
                    ON `external_mappings` (`provider`, `entityType`, `entityId`, `externalService`)
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_copy_cache` (
                        `provider` TEXT NOT NULL,
                        `entityType` TEXT NOT NULL,
                        `entityId` TEXT NOT NULL,
                        `copy` TEXT NOT NULL,
                        `promptHash` TEXT NOT NULL,
                        `generatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`provider`, `entityType`, `entityId`)
                    )
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `neodb_config` (
                        `id` INTEGER NOT NULL PRIMARY KEY,
                        `instance` TEXT NOT NULL DEFAULT 'https://neodb.social',
                        `accessToken` TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_activity_events_provider_timestamp`
                    ON `activity_events` (`provider`, `timestamp`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_play_history_provider_playedAt`
                    ON `play_history` (`provider`, `playedAt`)
                    """.trimIndent(),
                )

                // 滚动淘汰：每插一条新 activity_event，若总数超过 10000
                // 就按 (timestamp ASC, id ASC) 顺序淘汰最老的。触发器
                // 全局（不按 provider 分），因为 Memory 的候选池由 provider
                // 过滤决定；整表容量才是真正的上限。
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS `trg_activity_events_cap`
                    AFTER INSERT ON `activity_events`
                    WHEN (SELECT COUNT(*) FROM `activity_events`) > $ACTIVITY_EVENTS_CAP
                    BEGIN
                        DELETE FROM `activity_events`
                        WHERE `id` IN (
                            SELECT `id` FROM `activity_events`
                            ORDER BY `timestamp` ASC, `id` ASC
                            LIMIT ((SELECT COUNT(*) FROM `activity_events`) - $ACTIVITY_EVENTS_CAP)
                        );
                    END
                    """.trimIndent(),
                )
            }
        }

        /** 滚动淘汰 activity_events 的目标上限。 */
        const val ACTIVITY_EVENTS_CAP: Int = 10_000

        /**
         * v11 → v12：把 `neodb_config.accessToken` 列干掉。Token 改走
         * [com.gpo.yoin.data.integration.neodb.NeoDbTokenStore] 落到
         * `noBackupFilesDir`，和 profile credentials 一样不进云备份。
         *
         * 迁移逻辑：用户在 0.3 开发版里已经填过的 token 直接丢弃（我们
         * 不自动搬家到加密文件 —— Room 是 backup inclusion zone，丢掉
         * 相对更干净），下次打开 Settings 重新登录即可。Release 前没有
         * 正式用户，这个 trade-off 可接受。
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE `neodb_config_new` (
                        `id` INTEGER NOT NULL PRIMARY KEY,
                        `instance` TEXT NOT NULL DEFAULT 'https://neodb.social'
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `neodb_config_new` (`id`, `instance`)
                    SELECT `id`, `instance` FROM `neodb_config`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `neodb_config`")
                db.execSQL("ALTER TABLE `neodb_config_new` RENAME TO `neodb_config`")
            }
        }

        // v10: 笔记支持多条 per 单曲。主键从 (trackId, provider) 组合换成独立
        // UUID `id`；(trackId, provider) 降级成普通索引。现有单条笔记通过
        // `hex(randomblob(16))` 赋 id 保留下来。
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `song_notes_new` (
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
                    INSERT INTO `song_notes_new`
                        (id, trackId, provider, content, createdAt, updatedAt, title, artist)
                    SELECT lower(hex(randomblob(16))), trackId, provider, content,
                           createdAt, updatedAt, title, artist
                    FROM `song_notes`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `song_notes`")
                db.execSQL("ALTER TABLE `song_notes_new` RENAME TO `song_notes`")
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
        }
    }
}
