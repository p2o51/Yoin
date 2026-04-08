package com.gpo.yoin

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gpo.yoin.data.local.ServerConfig
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.SubsonicApi
import com.gpo.yoin.data.remote.SubsonicApiFactory
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.player.AudioVisualizerManager
import com.gpo.yoin.player.CastManager
import com.gpo.yoin.player.PlaybackManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class AppContainer(private val context: Context) {

    val database: YoinDatabase by lazy {
        Room.databaseBuilder(context, YoinDatabase::class.java, "yoin-database")
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    @Volatile
    private var cachedCredentials: ServerCredentials? = null

    @Volatile
    private var cachedApiCredentials: ServerCredentials? = null

    @Volatile
    private var _api: SubsonicApi? = null

    fun getCredentials(): ServerCredentials {
        cachedCredentials?.let { return it }
        val config: ServerConfig? = runBlocking {
            database.serverConfigDao().getActiveServer().first()
        }
        val creds = config?.let {
            ServerCredentials(
                serverUrl = it.serverUrl,
                username = it.username,
                password = it.passwordHash,
            )
        } ?: ServerCredentials("", "", "")
        cachedCredentials = creds
        return creds
    }

    fun getApi(): SubsonicApi {
        val creds = getCredentials()
        if (_api == null || cachedApiCredentials != creds) {
            cachedApiCredentials = creds
            _api = SubsonicApiFactory.create(
                credentialsProvider = ::getCredentials,
                loggingEnabled = false,
            )
        }
        return _api!!
    }

    fun invalidateCredentials() {
        cachedCredentials = null
        cachedApiCredentials = null
        _api = null
    }

    val repository: YoinRepository by lazy {
        YoinRepository(
            apiProvider = ::getApi,
            database = database,
            credentials = ::getCredentials,
        )
    }

    fun rebuildRepository(): YoinRepository {
        invalidateCredentials()
        return YoinRepository(
            apiProvider = ::getApi,
            database = database,
            credentials = ::getCredentials,
        )
    }

    val castManager: CastManager by lazy {
        CastManager(context).also { it.initialize() }
    }

    val playbackManager: PlaybackManager by lazy {
        PlaybackManager(
            context = context,
            repository = repository,
            castManager = castManager,
        )
    }

    val audioVisualizerManager: AudioVisualizerManager by lazy {
        AudioVisualizerManager()
    }

    private companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
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
    }
}
