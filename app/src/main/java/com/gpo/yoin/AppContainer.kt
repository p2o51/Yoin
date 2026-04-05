package com.gpo.yoin

import android.content.Context
import androidx.room.Room
import com.gpo.yoin.data.local.ServerConfig
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.SubsonicApi
import com.gpo.yoin.data.remote.SubsonicApiFactory
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.player.PlaybackManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class AppContainer(private val context: Context) {

    val database: YoinDatabase by lazy {
        Room.databaseBuilder(context, YoinDatabase::class.java, "yoin-database").build()
    }

    @Volatile
    private var cachedCredentials: ServerCredentials? = null

    @Volatile
    private var cachedServerUrl: String? = null

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
        cachedServerUrl = creds.serverUrl
        return creds
    }

    fun getApi(): SubsonicApi {
        val creds = getCredentials()
        if (_api == null || cachedServerUrl != creds.serverUrl) {
            cachedServerUrl = creds.serverUrl
            _api = SubsonicApiFactory.create(
                credentialsProvider = { creds },
                loggingEnabled = false,
            )
        }
        return _api!!
    }

    fun invalidateCredentials() {
        cachedCredentials = null
        _api = null
    }

    val repository: YoinRepository by lazy {
        YoinRepository(
            api = getApi(),
            database = database,
            credentials = ::getCredentials,
        )
    }

    fun rebuildRepository(): YoinRepository {
        invalidateCredentials()
        return YoinRepository(
            api = getApi(),
            database = database,
            credentials = ::getCredentials,
        )
    }

    val playbackManager: PlaybackManager by lazy {
        PlaybackManager(context)
    }
}
