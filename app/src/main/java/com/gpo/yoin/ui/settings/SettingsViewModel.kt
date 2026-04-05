package com.gpo.yoin.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.local.ServerConfig
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.SubsonicApiFactory
import com.gpo.yoin.data.repository.YoinRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
            _uiState.value = SettingsUiState.Content(
                serverUrl = config?.serverUrl.orEmpty(),
                username = config?.username.orEmpty(),
                isConnected = config != null,
                cacheSizeBytes = cacheSize,
            )
        }
    }

    fun saveServer(url: String, username: String, password: String) {
        viewModelScope.launch {
            container.database.serverConfigDao().insert(
                ServerConfig(
                    serverUrl = url.trimEnd('/'),
                    username = username,
                    passwordHash = password,
                ),
            )
            container.invalidateCredentials()

            val cacheSize = container.database.cacheMetadataDao().getTotalCacheSize().first()
            _uiState.value = SettingsUiState.Content(
                serverUrl = url,
                username = username,
                isConnected = true,
                cacheSizeBytes = cacheSize,
            )
        }
    }

    fun testConnection(url: String, username: String, password: String) {
        _uiState.value = SettingsUiState.Connecting
        _connectionResult.value = null
        viewModelScope.launch {
            try {
                val creds = ServerCredentials(
                    serverUrl = url.trimEnd('/'),
                    username = username,
                    password = password,
                )
                val testApi = SubsonicApiFactory.create(
                    credentialsProvider = { creds },
                    loggingEnabled = false,
                )
                val repo = YoinRepository(
                    api = testApi,
                    database = container.database,
                    credentials = { creds },
                )
                val success = repo.testConnection()
                _connectionResult.value = if (success) {
                    ConnectionResult.Success
                } else {
                    ConnectionResult.Failure("Server returned an error response")
                }
                val cacheSize = container.database.cacheMetadataDao().getTotalCacheSize().first()
                _uiState.value = SettingsUiState.Content(
                    serverUrl = url,
                    username = username,
                    isConnected = success,
                    cacheSizeBytes = cacheSize,
                )
            } catch (e: Exception) {
                _connectionResult.value = ConnectionResult.Failure(
                    e.message ?: "Connection failed",
                )
                val cacheSize = container.database.cacheMetadataDao().getTotalCacheSize().first()
                _uiState.value = SettingsUiState.Content(
                    serverUrl = url,
                    username = username,
                    isConnected = false,
                    cacheSizeBytes = cacheSize,
                )
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
