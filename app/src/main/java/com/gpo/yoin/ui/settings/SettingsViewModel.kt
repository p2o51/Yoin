package com.gpo.yoin.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.local.ServerConfig
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.SubsonicApiFactory
import com.gpo.yoin.data.repository.SubsonicException
import com.gpo.yoin.data.repository.YoinRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.UnknownServiceException

class SettingsViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SettingsUiState>(SettingsUiState.Loading)
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _connectionResult = MutableStateFlow<ConnectionResult?>(null)
    val connectionResult: StateFlow<ConnectionResult?> = _connectionResult.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val config = container.database.serverConfigDao().getActiveServer().first()
            val cacheSize = container.database.cacheMetadataDao().getTotalCacheSize().first()
            val geminiConfig = container.database.geminiConfigDao().getConfig().first()
            _uiState.value = SettingsUiState.Content(
                serverUrl = config?.serverUrl.orEmpty(),
                username = config?.username.orEmpty(),
                isConnected = config != null,
                cacheSizeBytes = cacheSize,
                geminiApiKey = geminiConfig?.apiKey.orEmpty(),
            )
        }
    }

    fun saveServer(url: String, username: String, password: String) {
        val normalizedUrl = url.trim().trimEnd('/')
        val normalizedUsername = username.trim()
        viewModelScope.launch {
            container.database.serverConfigDao().insert(
                ServerConfig(
                    serverUrl = normalizedUrl,
                    username = normalizedUsername,
                    passwordHash = password,
                ),
            )
            container.invalidateCredentials()
            container.playbackManager.disconnect()

            val cacheSize = container.database.cacheMetadataDao().getTotalCacheSize().first()
            _uiState.value = SettingsUiState.Content(
                serverUrl = normalizedUrl,
                username = normalizedUsername,
                isConnected = true,
                cacheSizeBytes = cacheSize,
            )
        }
    }

    fun testConnection(url: String, username: String, password: String) {
        val normalizedUrl = url.trim().trimEnd('/')
        val normalizedUsername = username.trim()
        validateConnectionInput(
            url = normalizedUrl,
            username = normalizedUsername,
            password = password,
        )?.let { message ->
            _connectionResult.value = ConnectionResult.Failure(message)
            return
        }

        _uiState.value = SettingsUiState.Connecting
        _connectionResult.value = null
        viewModelScope.launch {
            try {
                val creds = ServerCredentials(
                    serverUrl = normalizedUrl,
                    username = normalizedUsername,
                    password = password,
                )
                val testApi = SubsonicApiFactory.create(
                    credentialsProvider = { creds },
                    loggingEnabled = false,
                )
                val repo = YoinRepository(
                    apiProvider = { testApi },
                    database = container.database,
                    credentials = { creds },
                )
                repo.testConnection()
                _connectionResult.value = ConnectionResult.Success
                val cacheSize = container.database.cacheMetadataDao().getTotalCacheSize().first()
                _uiState.value = SettingsUiState.Content(
                    serverUrl = normalizedUrl,
                    username = normalizedUsername,
                    isConnected = true,
                    cacheSizeBytes = cacheSize,
                )
            } catch (e: Exception) {
                _connectionResult.value = ConnectionResult.Failure(
                    e.toConnectionErrorMessage(),
                )
                val cacheSize = container.database.cacheMetadataDao().getTotalCacheSize().first()
                _uiState.value = SettingsUiState.Content(
                    serverUrl = normalizedUrl,
                    username = normalizedUsername,
                    isConnected = false,
                    cacheSizeBytes = cacheSize,
                )
            }
        }
    }

    fun saveGeminiApiKey(key: String) {
        viewModelScope.launch {
            container.database.geminiConfigDao().upsert(GeminiConfig(apiKey = key.trim()))
            val state = _uiState.value
            if (state is SettingsUiState.Content) {
                _uiState.value = state.copy(geminiApiKey = key.trim())
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            container.database.cacheMetadataDao().deleteOldest(0)
            val state = _uiState.value
            if (state is SettingsUiState.Content) {
                _uiState.value = state.copy(cacheSizeBytes = 0L)
            }
        }
    }

    fun dismissConnectionResult() {
        _connectionResult.value = null
    }

    fun retry() {
        _uiState.value = SettingsUiState.Loading
        loadSettings()
    }

    private fun validateConnectionInput(
        url: String,
        username: String,
        password: String,
    ): String? = when {
        url.isBlank() -> "Server URL is required"
        username.isBlank() -> "Username is required"
        password.isBlank() -> "Password is required"
        else -> null
    }

    private fun Exception.toConnectionErrorMessage(): String = when (this) {
        is SubsonicException -> message ?: "Subsonic server returned an error"
        is UnknownServiceException -> "HTTP connections are blocked by Android network security policy"
        is IllegalArgumentException -> "Invalid server URL. Include http:// or https://"
        else -> message ?: "Connection failed"
    }

    sealed interface ConnectionResult {
        data object Success : ConnectionResult
        data class Failure(val message: String) : ConnectionResult
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(container) as T
    }
}
