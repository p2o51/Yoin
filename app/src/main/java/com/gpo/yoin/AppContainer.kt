package com.gpo.yoin

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gpo.yoin.data.local.YoinDatabase
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
            )
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
        ) { source, clientId, lastFailure ->
            val isSpotify = source?.id == MediaId.PROVIDER_SPOTIFY
            when {
                !isSpotify -> SpotifyProviderStatus.Ready
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

    val memoriesDeckCoordinator: MemoriesDeckCoordinator by lazy {
        MemoriesDeckCoordinator(
            repository = repository,
            sessionStore = experienceSessionStore,
        )
    }

    val repository: YoinRepository by lazy {
        YoinRepository(
            activeSource = profileManager.activeSource,
            activeProfileId = profileManager.activeProfileId,
            database = database,
            geminiService = geminiService,
            songInfoDao = database.songInfoDao(),
            geminiConfigDao = database.geminiConfigDao(),
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
    }
}
