package com.gpo.yoin.ui.settings.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.data.sync.CloudAccountContent
import com.gpo.yoin.data.sync.CloudDataSummary
import com.gpo.yoin.data.sync.CloudDeviceFiles
import com.gpo.yoin.data.sync.CloudGlobalContent
import com.gpo.yoin.data.sync.CloudSyncController
import com.gpo.yoin.data.sync.CloudSyncState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Which 云端数据 page. */
sealed interface CloudDataPage {
    data object Files : CloudDataPage

    data class Account(val account: CloudAccountRef) : CloudDataPage

    data object Global : CloudDataPage
}

/** A page's content: being read, read, or not readable (the file list needs the network). */
sealed interface CloudDataLoad<out T> {
    data object Loading : CloudDataLoad<Nothing>

    data class Ready<T>(val value: T) : CloudDataLoad<T>

    data object Failed : CloudDataLoad<Nothing>
}

sealed interface CloudDataEvent {
    data class DeviceRemoved(val name: String) : CloudDataEvent

    data class AccountRemoved(val name: String) : CloudDataEvent

    data class RemoveFailed(val name: String) : CloudDataEvent
}

/** 全部文件, one account's read-only browser, and 全局数据: everything read from the controller, nothing written. */
class CloudDataViewModel(
    private val controller: CloudSyncController,
    val page: CloudDataPage,
) : ViewModel() {
    val syncState: StateFlow<CloudSyncState> = controller.state

    private val _files = MutableStateFlow<CloudDataLoad<List<CloudDeviceFiles>>>(CloudDataLoad.Loading)
    val files: StateFlow<CloudDataLoad<List<CloudDeviceFiles>>> = _files.asStateFlow()

    private val _account = MutableStateFlow<CloudDataLoad<CloudAccountContent>>(CloudDataLoad.Loading)
    val account: StateFlow<CloudDataLoad<CloudAccountContent>> = _account.asStateFlow()

    private val _global = MutableStateFlow<CloudDataLoad<CloudGlobalContent>>(CloudDataLoad.Loading)
    val global: StateFlow<CloudDataLoad<CloudGlobalContent>> = _global.asStateFlow()

    private val _summary = MutableStateFlow<CloudDataSummary?>(null)
    val summary: StateFlow<CloudDataSummary?> = _summary.asStateFlow()

    private val _removing = MutableStateFlow(false)
    val removing: StateFlow<Boolean> = _removing.asStateFlow()

    private val _events = Channel<CloudDataEvent>(Channel.BUFFERED)
    val events: Flow<CloudDataEvent> = _events.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            when (page) {
                CloudDataPage.Files -> {
                    _files.value = CloudDataLoad.Loading
                    _files.value = controller.cloudFiles()
                        .fold({ CloudDataLoad.Ready(it) }, { CloudDataLoad.Failed })
                }
                is CloudDataPage.Account -> {
                    _account.value = read { controller.cloudAccountContent(page.account.syncProfileId) }
                    _summary.value = runCatching { controller.cloudDataSummary() }.getOrNull()
                }
                CloudDataPage.Global -> _global.value = read { controller.cloudGlobalContent() }
            }
        }
    }

    private suspend fun <T> read(block: suspend () -> T): CloudDataLoad<T> = try {
        CloudDataLoad.Ready(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CloudDataLoad.Failed
    }

    /** 从同步中移除 a device: its files go; it uploads again if it's still alive. */
    fun removeDevice(deviceId: String, name: String) {
        controller.removeDevice(deviceId)
        val current = _files.value
        if (current is CloudDataLoad.Ready) _files.value = CloudDataLoad.Ready(current.value.filterNot { it.deviceId == deviceId })
        _events.trySend(CloudDataEvent.DeviceRemoved(name))
    }

    /** 从云端移除 the browsed account (after the page's confirmation). */
    fun removeAccount() {
        val account = (page as? CloudDataPage.Account)?.account ?: return
        if (_removing.value) return
        _removing.value = true
        viewModelScope.launch {
            val result = runCatching { controller.removeAccountFromCloud(account.syncProfileId) }.getOrElse { Result.failure(it) }
            _removing.value = false
            _events.trySend(
                if (result.isSuccess) CloudDataEvent.AccountRemoved(account.displayName) else CloudDataEvent.RemoveFailed(account.displayName),
            )
        }
    }

    class Factory(private val controller: CloudSyncController, private val page: CloudDataPage) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CloudDataViewModel(controller, page) as T
    }
}
