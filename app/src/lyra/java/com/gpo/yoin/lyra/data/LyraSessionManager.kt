package com.gpo.yoin.lyra.data

import android.content.Context
import android.util.Log
import com.gpo.yoin.AppContainer
import com.gpo.yoin.lyra.ui.LyraJobLiveUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class LyraSessionState(
    val serverUrl: String? = null,
    val username: String? = null,
    val hasAdminToken: Boolean = false,
    val lyraVersion: String? = null,
    val protocolVersion: Int = 0,
    val capabilities: Map<String, LyraCapabilityEntry> = emptyMap(),
    val sources: List<LyraSourceInfo> = emptyList(),
    val jobs: List<LyraJob> = emptyList(),
    val sseConnected: Boolean = false,
    val loading: Boolean = false,
    val lastError: String? = null,
) {
    val isConnected: Boolean get() = !serverUrl.isNullOrBlank()
    val isLyraServer: Boolean get() = !lyraVersion.isNullOrBlank()
    val activeJobs: List<LyraJob> get() = jobs.filter { it.isActive }
    fun capabilityEnabled(name: String): Boolean = capabilities[name]?.enabled == true
}

/**
 * Process-scoped Lyra coordinator for the `lyra` flavor.
 *
 * Probes `/api/v1/capabilities`, loads anonymized `/api/v1/sources` and download `/api/v1/jobs`,
 * maintains the authenticated `/api/v1/events` SSE stream while foregrounded or while download
 * jobs are running, drives [LyraJobLiveUpdate], and refreshes Yoin's library catalog in place
 * (without closing open detail panes) when a newly downloaded track/album lands.
 */
class LyraSessionManager(
    context: Context,
    private val container: AppContainer,
    val credentialsProvider: LyraCredentialsProvider,
    val api: LyraApiClient = LyraApiClient(),
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val liveUpdate = LyraJobLiveUpdate(appContext)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val _state = MutableStateFlow(LyraSessionState())
    val state: StateFlow<LyraSessionState> = _state.asStateFlow()

    private val stateLock = Any()

    @Volatile private var foregrounded = false
    @Volatile private var lastEventId: Long? = null
    @Volatile private var activeServerKey: String? = null
    private var sseJob: Job? = null
    private val knownActiveJobIds = mutableSetOf<Long>()

    init {
        scope.launch {
            container.musicConfigurationRevision.collect {
                if (foregrounded) refresh()
            }
        }
    }

    fun onAppForeground() {
        foregrounded = true
        scope.launch {
            refresh()
            ensureSseRunning()
        }
    }

    fun onAppBackground() {
        foregrounded = false
        if (_state.value.activeJobs.isEmpty()) {
            stopSse()
        }
    }

    fun onProfileDeleted(profileId: String) {
        stopSse()
        synchronized(stateLock) {
            knownActiveJobIds.clear()
            lastEventId = null
            activeServerKey = null
        }
        liveUpdate.clear()
        _state.value = LyraSessionState()
        if (foregrounded) {
            scope.launch { refresh() }
        }
    }

    fun setAdminToken(serverUrl: String, token: String?) {
        credentialsProvider.adminTokenStore.write(serverUrl, token)
        val hasToken = credentialsProvider.adminTokenStore.hasToken(serverUrl)
        _state.update { current ->
            if (normalizeServer(current.serverUrl) == normalizeServer(serverUrl)) {
                current.copy(hasAdminToken = hasToken)
            } else {
                current
            }
        }
    }

    suspend fun refresh() {
        val auth = credentialsProvider.currentAuth()
        if (auth == null) {
            stopSse()
            synchronized(stateLock) {
                knownActiveJobIds.clear()
                lastEventId = null
                activeServerKey = null
            }
            _state.value = LyraSessionState()
            liveUpdate.clear()
            return
        }
        val serverKey = normalizeServer(auth.serverUrl)
        val serverChanged = synchronized(stateLock) {
            val changed = activeServerKey != null && activeServerKey != serverKey
            if (changed) {
                lastEventId = null
                knownActiveJobIds.clear()
            }
            activeServerKey = serverKey
            changed
        }
        if (serverChanged) {
            stopSse()
            liveUpdate.clear()
        }
        _state.update {
            it.copy(
                serverUrl = auth.serverUrl,
                username = auth.username,
                hasAdminToken = !auth.adminToken.isNullOrBlank(),
                loading = true,
                lastError = null,
            )
        }
        val caps = runCatching { api.capabilities(auth) }.getOrElse { err ->
            if (err is CancellationException) throw err
            _state.update { it.copy(loading = false, lastError = err.message) }
            return
        }
        val sources = if (caps.isEnabled("sources")) {
            runCatching { api.listSources(auth) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val jobs = runCatching { api.listJobs(auth, kind = DOWNLOAD_JOB_KIND, limit = 30) }
            .getOrDefault(emptyList())
            .filter { it.kind.isBlank() || it.kind == DOWNLOAD_JOB_KIND }
        updateJobsAndNotification(jobs)
        _state.update {
            it.copy(
                serverUrl = auth.serverUrl,
                username = auth.username,
                hasAdminToken = !auth.adminToken.isNullOrBlank(),
                lyraVersion = caps.lyraVersion,
                protocolVersion = caps.protocolVersion,
                capabilities = caps.capabilities,
                sources = sources,
                jobs = jobs,
                loading = false,
                lastError = null,
            )
        }
        if (foregrounded || jobs.any { it.isActive }) {
            ensureSseRunning()
        }
    }

    fun noteJobQueued(jobId: Long, displayTitle: String, kind: String = DOWNLOAD_JOB_KIND) {
        val synthetic = LyraJob(
            id = jobId,
            kind = kind,
            status = "queued",
            payload = buildJsonObject { put("title", displayTitle) },
        )
        val merged = (listOf(synthetic) + _state.value.jobs.filterNot { it.id == jobId }).take(40)
        updateJobsAndNotification(merged)
        _state.update { it.copy(jobs = merged) }
        ensureSseRunning()
        scope.launch {
            delay(1_200L)
            refreshJobsOnly()
        }
    }

    private suspend fun refreshJobsOnly() {
        val auth = credentialsProvider.currentAuth() ?: return
        val jobs = runCatching { api.listJobs(auth, kind = DOWNLOAD_JOB_KIND, limit = 30) }
            .getOrNull()
            ?.filter { it.kind.isBlank() || it.kind == DOWNLOAD_JOB_KIND }
            ?: return
        updateJobsAndNotification(jobs)
        _state.update { it.copy(jobs = jobs) }
    }

    private fun stopSse() {
        synchronized(stateLock) {
            sseJob?.cancel()
            sseJob = null
        }
        _state.update { it.copy(sseConnected = false) }
    }

    private fun ensureSseRunning() {
        if (!_state.value.capabilityEnabled("push_events")) return
        synchronized(stateLock) {
            if (sseJob?.isActive == true) return
            sseJob = scope.launch {
                var backoffMs = 2_000L
                while (isActive && (foregrounded || _state.value.activeJobs.isNotEmpty())) {
                    val auth = credentialsProvider.currentAuth()
                    if (auth == null) {
                        delay(5_000L)
                        continue
                    }
                    val streamServerKey = normalizeServer(auth.serverUrl)
                    synchronized(stateLock) {
                        if (activeServerKey != null && activeServerKey != streamServerKey) {
                            lastEventId = null
                            knownActiveJobIds.clear()
                        }
                        activeServerKey = streamServerKey
                    }
                    try {
                        val ticket = api.issueEventsTicket(auth).ticket
                        _state.update { it.copy(sseConnected = true) }
                        backoffMs = 2_000L
                        api.streamEvents(
                            auth = auth,
                            ticket = ticket,
                            topics = "job,library,inbox,share",
                            lastEventId = lastEventId,
                        ) { event ->
                            if (event.id > 0) lastEventId = event.id
                            handleEvent(event)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.d(TAG, "Lyra SSE disconnected: ${error.message}")
                    } finally {
                        _state.update { it.copy(sseConnected = false) }
                    }
                    if (!foregrounded && _state.value.activeJobs.isEmpty()) break
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    private suspend fun handleEvent(event: LyraEvent) {
        when (event.topic) {
            "job" -> {
                val parsed = event.payload?.let {
                    runCatching { json.decodeFromJsonElement(LyraJob.serializer(), it) }.getOrNull()
                }
                if (parsed != null && parsed.id > 0) {
                    val effectiveKind = parsed.kind.ifBlank { event.kind }
                    if (effectiveKind.isNotBlank() && effectiveKind != DOWNLOAD_JOB_KIND) return
                    val normalized = if (parsed.kind.isBlank()) parsed.copy(kind = DOWNLOAD_JOB_KIND) else parsed
                    val merged = (listOf(normalized) + _state.value.jobs.filterNot { it.id == normalized.id }).take(40)
                    updateJobsAndNotification(merged)
                    _state.update { it.copy(jobs = merged) }
                } else {
                    refreshJobsOnly()
                }
            }
            "library" -> {
                container.notifyLibraryContentChanged()
            }
        }
    }

    private fun updateJobsAndNotification(jobs: List<LyraJob>) {
        val active = jobs.filter { it.isActive }
        val completedNow = synchronized(stateLock) {
            val done = jobs.filter { it.id in knownActiveJobIds && (it.isDone || it.isFailed) }
            knownActiveJobIds.clear()
            knownActiveJobIds.addAll(active.map { it.id })
            done
        }

        if (active.isNotEmpty()) {
            liveUpdate.showActive(active)
        } else if (completedNow.isNotEmpty()) {
            liveUpdate.showCompleted(completedNow.first())
            if (completedNow.any { it.isDone }) {
                container.notifyLibraryContentChanged()
            }
        } else {
            liveUpdate.clear()
        }
    }

    private fun normalizeServer(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let { LyraApiClient.normalizeServerBase(it) }

    companion object {
        private const val TAG = "LyraSessionManager"
        private const val DOWNLOAD_JOB_KIND = "download"
    }
}
