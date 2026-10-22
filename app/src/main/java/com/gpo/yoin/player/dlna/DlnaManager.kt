package com.gpo.yoin.player.dlna

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The UI owns localized copy for these renderer failure categories. */
enum class DlnaError { Discovery, Group, StopUnconfirmed, Playback }

/** Discovery is explicit and bounded; it does not keep multicast running while Yoin is idle. */
@UnstableApi
class DlnaManager(context: Context) {
    private val client = DlnaClient()
    private val discovery = DlnaDiscovery(context, client)
    private val discoveryMutex = Mutex()
    private val connectionMutex = Mutex()
    private val _devices = MutableStateFlow<List<DlnaDevice>>(emptyList())
    private val _isDiscovering = MutableStateFlow(false)
    private val _connectedDevice = MutableStateFlow<DlnaDevice?>(null)
    private val _error = MutableStateFlow<DlnaError?>(null)
    val devices: StateFlow<List<DlnaDevice>> = _devices.asStateFlow()
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()
    val connectedDevice: StateFlow<DlnaDevice?> = _connectedDevice.asStateFlow()
    val error: StateFlow<DlnaError?> = _error.asStateFlow()
    val player: DlnaMedia3Player = DlnaMedia3Player(client) { message ->
        _error.value = message?.let { DlnaError.Playback }
    }

    suspend fun discover() = discoveryMutex.withLock {
        _isDiscovering.value = true
        try {
            _devices.value = discovery.discover()
            if (_connectedDevice.value == null) _error.value = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _error.value = DlnaError.Discovery
        } finally {
            _isDiscovering.value = false
        }
    }

    suspend fun connect(
        device: DlnaDevice,
        items: List<MediaItem>,
        index: Int,
        positionMs: Long,
        playWhenReady: Boolean
    ): Result<Unit> = connectionMutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            val target = try {
                client.resolveCoordinator(device)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _error.value = DlnaError.Group
                return@withContext Result.failure(DlnaGroupException())
            }
            val oldDevice = _connectedDevice.value
            if (oldDevice != null && oldDevice.id != target.id) {
                try {
                    player.disconnect(stopRemote = true)
                    _connectedDevice.value = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _error.value = DlnaError.StopUnconfirmed
                    return@withContext Result.failure(DlnaCommandException("Stop"))
                }
            }
            try {
                player.connect(target, items, index, positionMs, playWhenReady)
                _connectedDevice.value = target
                _error.value = null
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { recoverFailedConnection(target) }
                throw cancelled
            } catch (_: Exception) {
                // A Play may have reached the renderer even if its response was lost. A local
                // handback is safe only once a Stop has been acknowledged.
                withContext(NonCancellable) { recoverFailedConnection(target) }
                if (_error.value != DlnaError.StopUnconfirmed) _error.value = DlnaError.Playback
                Result.failure(DlnaCommandException("Connect"))
            }
        }
    }

    private suspend fun recoverFailedConnection(target: DlnaDevice) {
        val stopped = try {
            player.disconnect(stopRemote = true)
            true
        } catch (_: Exception) {
            false
        }
        _connectedDevice.value = if (stopped || !player.hasDevice()) null else target
        if (!stopped && player.hasDevice()) {
            player.connectionFailed()
            _error.value = DlnaError.StopUnconfirmed
        }
    }

    suspend fun disconnect(stopRemote: Boolean = true): Result<Unit> = connectionMutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            try {
                player.disconnect(stopRemote)
                _connectedDevice.value = null
                _error.value = null
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _error.value = DlnaError.StopUnconfirmed
                Result.failure(DlnaCommandException("Stop"))
            }
        }
    }
}
