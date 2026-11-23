package com.gpo.yoin.lyra.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.R
import com.gpo.yoin.lyra.data.LyraApiException
import com.gpo.yoin.lyra.data.LyraDownloadRequest
import com.gpo.yoin.lyra.data.LyraInboxItem
import com.gpo.yoin.lyra.data.LyraIncomingPull
import com.gpo.yoin.lyra.data.LyraListenLaterItem
import com.gpo.yoin.lyra.data.LyraSessionManager
import com.gpo.yoin.lyra.data.LyraSessionState
import com.gpo.yoin.lyra.data.LyraShareView
import com.gpo.yoin.lyra.data.LyraSourceAlbumResponse
import com.gpo.yoin.lyra.data.LyraSourceItem
import com.gpo.yoin.lyra.data.LyraUploadPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LyraHubTab(val key: String) {
    Sources("sources"),
    Inbox("inbox"),
    Passes("passes"),
    Server("server"),
    ;

    companion object {
        fun fromKey(key: String?): LyraHubTab = entries.firstOrNull { it.key == key } ?: Sources
    }
}

data class LyraHubUiState(
    val selectedTab: LyraHubTab = LyraHubTab.Sources,
    val selectedSourceId: String? = null,
    val searchType: String = "song",
    val searchQuery: String = "",
    val searching: Boolean = false,
    val searchResults: List<LyraSourceItem> = emptyList(),
    val selectedAlbum: LyraSourceAlbumResponse? = null,
    val loadingAlbum: Boolean = false,
    val queueingMid: String? = null,
    val inboxUrlInput: String = "",
    val inboxBusy: Boolean = false,
    val inboxNeedsAdminToken: Boolean = false,
    val inboxItems: List<LyraInboxItem> = emptyList(),
    val laterItems: List<LyraListenLaterItem> = emptyList(),
    val passUrlInput: String = "",
    val passesBusy: Boolean = false,
    val passesNeedsAdminToken: Boolean = false,
    val incomingPulls: List<LyraIncomingPull> = emptyList(),
    val outgoingShares: List<LyraShareView> = emptyList(),
    val adminTokenInput: String = "",
)

class LyraHubViewModel(
    private val appContext: Context,
    private val session: LyraSessionManager,
    initialTab: LyraHubTab = LyraHubTab.Sources,
    prefillInboxUrl: String? = null,
    prefillPassUrl: String? = null,
    initialUploadUris: List<Uri> = emptyList(),
) : ViewModel() {
    val sessionState: StateFlow<LyraSessionState> = session.state

    private val _ui = MutableStateFlow(
        LyraHubUiState(
            selectedTab = initialTab,
            inboxUrlInput = prefillInboxUrl.orEmpty(),
            passUrlInput = prefillPassUrl.orEmpty(),
        ),
    )
    val ui: StateFlow<LyraHubUiState> = _ui.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var initialUploadsConsumed = false

    init {
        viewModelScope.launch {
            session.refresh()
            val firstSource = session.state.value.sources.firstOrNull()?.sourceId
            if (firstSource != null) {
                _ui.update { if (it.selectedSourceId == null) it.copy(selectedSourceId = firstSource) else it }
            }
            when (initialTab) {
                LyraHubTab.Inbox -> loadInboxAndLater()
                LyraHubTab.Passes -> loadPasses()
                else -> Unit
            }
            if (initialUploadUris.isNotEmpty() && !initialUploadsConsumed) {
                initialUploadsConsumed = true
                uploadInboxAudioUris(initialUploadUris)
            }
        }
    }

    fun selectTab(tab: LyraHubTab) {
        _ui.update { it.copy(selectedTab = tab) }
        when (tab) {
            LyraHubTab.Inbox -> loadInboxAndLater()
            LyraHubTab.Passes -> loadPasses()
            LyraHubTab.Server -> refreshSession()
            LyraHubTab.Sources -> {
                if (_ui.value.selectedSourceId == null) {
                    session.state.value.sources.firstOrNull()?.sourceId?.let { id ->
                        _ui.update { it.copy(selectedSourceId = id) }
                    }
                }
            }
        }
    }

    fun refreshSession() {
        viewModelScope.launch {
            session.refresh()
            val firstSource = session.state.value.sources.firstOrNull()?.sourceId
            _ui.update {
                if (it.selectedSourceId == null && firstSource != null) {
                    it.copy(selectedSourceId = firstSource)
                } else {
                    it
                }
            }
            when (_ui.value.selectedTab) {
                LyraHubTab.Inbox -> loadInboxAndLater()
                LyraHubTab.Passes -> loadPasses()
                else -> Unit
            }
        }
    }

    // ── Sources ──────────────────────────────────────────────────────────

    fun selectSource(sourceId: String) {
        _ui.update { it.copy(selectedSourceId = sourceId, searchResults = emptyList(), selectedAlbum = null) }
    }

    fun setSearchType(type: String) {
        if (_ui.value.searchType == type) return
        _ui.update { it.copy(searchType = type, searchResults = emptyList(), selectedAlbum = null) }
        if (_ui.value.searchQuery.isNotBlank()) searchSources()
    }

    fun updateSearchQuery(query: String) {
        _ui.update { it.copy(searchQuery = query) }
    }

    fun searchSources() {
        val query = _ui.value.searchQuery.trim()
        val sourceId = _ui.value.selectedSourceId ?: session.state.value.sources.firstOrNull()?.sourceId ?: return
        if (query.isEmpty()) return
        viewModelScope.launch {
            val auth = session.credentialsProvider.currentAuth() ?: return@launch
            _ui.update { it.copy(selectedSourceId = sourceId, searching = true, selectedAlbum = null) }
            runCatching {
                session.api.searchSource(auth, sourceId = sourceId, query = query, type = _ui.value.searchType)
            }.onSuccess { resp ->
                _ui.update { it.copy(searching = false, searchResults = resp.results) }
            }.onFailure { err ->
                _ui.update { it.copy(searching = false) }
                postError(err)
            }
        }
    }

    fun openSourceAlbum(albumMid: String) {
        val sourceId = _ui.value.selectedSourceId ?: session.state.value.sources.firstOrNull()?.sourceId ?: return
        viewModelScope.launch {
            val auth = session.credentialsProvider.currentAuth() ?: return@launch
            _ui.update { it.copy(loadingAlbum = true) }
            runCatching {
                session.api.getSourceAlbum(auth, sourceId = sourceId, albumMid = albumMid)
            }.onSuccess { album ->
                _ui.update { it.copy(loadingAlbum = false, selectedAlbum = album) }
            }.onFailure { err ->
                _ui.update { it.copy(loadingAlbum = false) }
                postError(err)
            }
        }
    }

    fun closeSourceAlbum() {
        _ui.update { it.copy(selectedAlbum = null) }
    }

    fun queueSourceItem(item: LyraSourceItem) {
        val sourceId = _ui.value.selectedSourceId ?: session.state.value.sources.firstOrNull()?.sourceId ?: return
        val sourceInfo = session.state.value.sources.firstOrNull { it.sourceId == sourceId }
        val isAlbum = item.type == "album"
        val key = if (isAlbum) item.mid else item.mid
        if (key.isBlank()) return
        viewModelScope.launch {
            val auth = session.credentialsProvider.currentAuth() ?: return@launch
            _ui.update { it.copy(queueingMid = key) }
            val req = if (isAlbum) {
                LyraDownloadRequest(
                    albumMid = item.mid,
                    quality = sourceInfo?.defaultQuality.orEmpty(),
                    title = item.title,
                    artist = item.artist,
                    albumTitle = item.title,
                    coverUrl = item.coverUrl,
                )
            } else {
                LyraDownloadRequest(
                    mid = item.mid,
                    quality = sourceInfo?.defaultQuality.orEmpty(),
                    title = item.title,
                    artist = item.artist,
                    albumTitle = item.album,
                    coverUrl = item.coverUrl,
                )
            }
            runCatching {
                session.api.enqueueSourceDownload(auth, sourceId = sourceId, request = req)
            }.onSuccess { resp ->
                _ui.update { it.copy(queueingMid = null) }
                val label = if (item.artist.isNotBlank()) "${item.title} — ${item.artist}" else item.title
                session.noteJobQueued(resp.jobId, label, kind = "download")
                _messages.trySend(appContext.getString(R.string.lyra_sources_queued))
            }.onFailure { err ->
                _ui.update { it.copy(queueingMid = null) }
                postError(err)
            }
        }
    }

    fun queueSelectedAlbum() {
        val album = _ui.value.selectedAlbum?.album ?: return
        queueSourceItem(
            LyraSourceItem(
                type = "album",
                mid = album.mid,
                title = album.title,
                artist = album.artist,
                coverUrl = album.coverUrl,
            ),
        )
    }

    // ── Inbox & Listen Later ─────────────────────────────────────────────

    fun updateInboxUrl(value: String) {
        _ui.update { it.copy(inboxUrlInput = value) }
    }

    fun loadInboxAndLater() {
        viewModelScope.launch {
            val auth = session.credentialsProvider.currentAuth() ?: return@launch
            _ui.update { it.copy(inboxBusy = true, inboxNeedsAdminToken = false) }
            val inboxResult = runCatching { session.api.listInbox(auth) }
            val laterResult = runCatching { session.api.listLater(auth) }
            val err = inboxResult.exceptionOrNull() ?: laterResult.exceptionOrNull()
            if (err is LyraApiException && err.code == 401) {
                _ui.update { it.copy(inboxBusy = false, inboxNeedsAdminToken = true) }
                return@launch
            }
            _ui.update {
                it.copy(
                    inboxBusy = false,
                    inboxItems = inboxResult.getOrDefault(it.inboxItems),
                    laterItems = laterResult.getOrDefault(it.laterItems),
                )
            }
            if (err != null) postError(err)
        }
    }

    fun submitInboxUrl() {
        val rawUrl = _ui.value.inboxUrlInput.trim()
        if (rawUrl.isEmpty()) return
        viewModelScope.launch {
            val auth = session.credentialsProvider.currentAuth() ?: return@launch
            _ui.update { it.copy(inboxBusy = true) }
            runCatching { session.api.addInboxUrl(auth, rawUrl) }
                .onSuccess {
                    _ui.update { it.copy(inboxBusy = false, inboxUrlInput = "") }
                    _messages.trySend(appContext.getString(R.string.lyra_inbox_url_saved))
                    loadInboxAndLater()
                }
                .onFailure { err ->
                    _ui.update { it.copy(inboxBusy = false, inboxNeedsAdminToken = err is LyraApiException && err.code == 401) }
                    postError(err)
                }
        }
    }

    fun uploadInboxAudioUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val auth = session.credentialsProvider.currentAuth() ?: return@launch
            _ui.update { it.copy(inboxBusy = true) }
            val (parts, localFailures) = withContext(Dispatchers.IO) {
                val ready = mutableListOf<LyraUploadPart>()
                var failedCount = 0
                uris.forEachIndexed { idx, uri ->
                    val canOpen = runCatching {
                        appContext.contentResolver.openInputStream(uri)?.use { true } == true
                    }.getOrDefault(false)
                    if (!canOpen) {
                        failedCount += 1
                    } else {
                        val meta = resolveFileMeta(uri)
                        val name = meta.first ?: "audio-${System.currentTimeMillis()}-$idx.bin"
                        val size = meta.second
                        ready += LyraUploadPart(
                            filename = name,
                            contentLength = size,
                            openStream = { appContext.contentResolver.openInputStream(uri) },
                        )
                    }
                }
                ready to failedCount
            }
            if (parts.isEmpty()) {
                _ui.update { it.copy(inboxBusy = false) }
                _messages.trySend(appContext.getString(R.string.lyra_inbox_upload_failed, localFailures.coerceAtLeast(uris.size)))
                return@launch
            }
            runCatching { session.api.uploadInboxParts(auth, parts) }
                .onSuccess { resp ->
                    _ui.update { it.copy(inboxBusy = false) }
                    val totalFailed = localFailures + resp.failed.size
                    val succeededCount = if (resp.items.isNotEmpty()) {
                        resp.items.size
                    } else {
                        (parts.size - resp.failed.size).coerceAtLeast(0)
                    }
                    when {
                        totalFailed == 0 ->
                            _messages.trySend(appContext.getString(R.string.lyra_inbox_upload_done))
                        succeededCount > 0 ->
                            _messages.trySend(
                                appContext.getString(R.string.lyra_inbox_upload_partial, succeededCount, totalFailed),
                            )
                        else ->
                            _messages.trySend(appContext.getString(R.string.lyra_inbox_upload_failed, totalFailed))
                    }
                    loadInboxAndLater()
                }
                .onFailure { err ->
                    _ui.update { it.copy(inboxBusy = false, inboxNeedsAdminToken = err is LyraApiException && err.code == 401) }
                    postError(err)
                }
        }
    }

    fun promoteInboxItem(id: String) {
        viewModelScope.launch {
            val auth = session.credentialsProvider.currentAuth() ?: return@launch
            runCatching { session.api.promoteInboxItem(auth, id) }
                .onSuccess { loadInboxAndLater() }
                .onFailure(::postError)
        }
    }

    fun discardInboxItem(id: String) {
        viewModelScope.launch {
            val auth = session.credentialsProvider.currentAuth() ?: return@launch
            runCatching { session.api.discardInboxItem(auth, id) }
                .onSuccess { loadInboxAndLater() }
                .onFailure(::postError)
        }
    }

    // ── Passes & Pulls ───────────────────────────────────────────────────

    fun updatePassUrl(value: String) {
        _ui.update { it.copy(passUrlInput = value) }
    }

    fun loadPasses() {
        viewModelScope.launch {
            val auth = session.credentialsProvider.currentAuth() ?: return@launch
            _ui.update { it.copy(passesBusy = true, passesNeedsAdminToken = false) }
            val pullsResult = runCatching { session.api.listPulls(auth) }
            val sharesResult = runCatching { session.api.listShares(auth) }
            val err = pullsResult.exceptionOrNull() ?: sharesResult.exceptionOrNull()
            if (err is LyraApiException && err.code == 401) {
                _ui.update { it.copy(passesBusy = false, passesNeedsAdminToken = true) }
                return@launch
            }
            _ui.update {
                it.copy(
                    passesBusy = false,
                    incomingPulls = pullsResult.getOrDefault(it.incomingPulls),
                    outgoingShares = sharesResult.getOrDefault(it.outgoingShares),
                )
            }
            if (err != null) postError(err)
        }
    }

    fun pullPass(target: String) {
        val raw = _ui.value.passUrlInput.trim()
        if (raw.isEmpty()) return
        val manifestUrl = if (raw.endsWith("/manifest.json")) raw else "${raw.trimEnd('/')}/manifest.json"
        viewModelScope.launch {
            val auth = session.credentialsProvider.currentAuth() ?: return@launch
            _ui.update { it.copy(passesBusy = true) }
            runCatching { session.api.enqueuePull(auth, manifestUrl = manifestUrl, target = target) }
                .onSuccess {
                    _ui.update { it.copy(passesBusy = false, passUrlInput = "") }
                    _messages.trySend(appContext.getString(R.string.lyra_passes_pull_queued))
                    loadPasses()
                }
                .onFailure { err ->
                    _ui.update { it.copy(passesBusy = false, passesNeedsAdminToken = err is LyraApiException && err.code == 401) }
                    postError(err)
                }
        }
    }

    fun revokeShare(token: String) {
        viewModelScope.launch {
            val auth = session.credentialsProvider.currentAuth() ?: return@launch
            runCatching { session.api.revokeShare(auth, token) }
                .onSuccess { loadPasses() }
                .onFailure(::postError)
        }
    }

    // ── Server / Admin Token ─────────────────────────────────────────────

    fun updateAdminTokenInput(value: String) {
        _ui.update { it.copy(adminTokenInput = value) }
    }

    fun saveAdminToken() {
        val token = _ui.value.adminTokenInput.trim()
        if (token.isEmpty()) return
        viewModelScope.launch {
            val auth = session.credentialsProvider.currentAuth() ?: return@launch
            val candidate = auth.copy(adminToken = token)
            runCatching { session.api.validateAdminToken(candidate) }
                .onSuccess {
                    session.setAdminToken(auth.serverUrl, token)
                    _ui.update { it.copy(adminTokenInput = "", inboxNeedsAdminToken = false, passesNeedsAdminToken = false) }
                    _messages.trySend(appContext.getString(R.string.lyra_server_admin_saved))
                }
                .onFailure { err ->
                    if (err is LyraApiException && err.code == 401) {
                        _messages.trySend(appContext.getString(R.string.lyra_server_admin_invalid))
                    } else {
                        postError(err)
                    }
                }
        }
    }

    fun clearAdminToken() {
        viewModelScope.launch {
            val auth = session.credentialsProvider.currentAuth() ?: return@launch
            session.setAdminToken(auth.serverUrl, null)
            _ui.update { it.copy(adminTokenInput = "") }
            _messages.trySend(appContext.getString(R.string.lyra_server_admin_cleared))
        }
    }

    private fun resolveFileMeta(uri: Uri): Pair<String?, Long> = runCatching {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null to -1L
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) cursor.getString(nameIdx) else null
            val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else -1L
            name to size
        } ?: (null to -1L)
    }.getOrDefault(null to -1L)

    private fun postError(error: Throwable) {
        _messages.trySend(
            appContext.getString(R.string.lyra_error_generic, error.message ?: error.javaClass.simpleName),
        )
    }

    class Factory(
        private val context: Context,
        private val session: LyraSessionManager,
        private val initialTab: LyraHubTab,
        private val prefillInboxUrl: String?,
        private val prefillPassUrl: String?,
        private val initialUploadUris: List<Uri> = emptyList(),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LyraHubViewModel(
            appContext = context.applicationContext,
            session = session,
            initialTab = initialTab,
            prefillInboxUrl = prefillInboxUrl,
            prefillPassUrl = prefillPassUrl,
            initialUploadUris = initialUploadUris,
        ) as T
    }
}
