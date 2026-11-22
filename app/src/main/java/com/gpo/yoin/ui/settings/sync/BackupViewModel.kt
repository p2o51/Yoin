package com.gpo.yoin.ui.settings.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.data.sync.backup.BackupReadException
import com.gpo.yoin.data.sync.backup.ExportSummary
import com.gpo.yoin.data.sync.backup.ImportAccountPlan
import com.gpo.yoin.data.sync.backup.ImportPlan
import com.gpo.yoin.data.sync.backup.ImportResult
import com.gpo.yoin.data.sync.backup.ImportTarget
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Why a backup file couldn't be read. */
enum class BackupReadFailure { NotABackup, TooNew, Checksum, Unreadable }

/**
 * 下载全部云端数据 and 从备份文件导入, after the system's save / open sheet picked the file:
 * export → [Exporting] → [Exported] | [ExportFailed];
 * import → [Reading] → [Review] (the user picks where each account goes) → [Importing] → [Imported] | [ImportFailed].
 */
sealed interface BackupUiState {
    data object Idle : BackupUiState

    data class Exporting(val fileName: String) : BackupUiState

    data class Exported(val fileName: String, val summary: ExportSummary, val bytes: Long?) : BackupUiState

    data class ExportFailed(val fileName: String) : BackupUiState

    data class Reading(val fileName: String) : BackupUiState

    data class ReadFailed(val fileName: String, val reason: BackupReadFailure) : BackupUiState

    /** [choices]: scope → where it goes, for every account of [plan] (defaults filled in). */
    data class Review(
        val fileName: String,
        val plan: ImportPlan,
        val choices: Map<String, ImportTarget>,
        /** Cloud sync is on here: "merge into Google Drive", else "into this device". */
        val intoCloud: Boolean,
    ) : BackupUiState

    data class Importing(val review: Review) : BackupUiState

    data class Imported(val review: Review, val result: ImportResult) : BackupUiState

    data class ImportFailed(val review: Review) : BackupUiState
}

/** The choices an account offers: its local profiles, cloud-only (when possible), skip. */
fun ImportAccountPlan.targetOptions(): List<ImportTarget> = buildList {
    options.forEach { add(ImportTarget.IntoProfile(it.localProfileId)) }
    if (cloudOnlyPossible && !wasPurged) add(ImportTarget.CloudOnly)
    add(ImportTarget.Skip)
}

class BackupViewModel(
    private val ops: MemoriesOps,
    private val cloudSyncOn: () -> Boolean = { false },
) : ViewModel() {
    private val _state = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    /** Busy steps refuse Back: closing mid-write would leave a half file or a half import. */
    val busy: Boolean
        get() = when (_state.value) {
            is BackupUiState.Exporting, is BackupUiState.Reading, is BackupUiState.Importing -> true
            else -> false
        }

    /** Writes the backup to [open]'s stream; [size] reads the saved file's size afterwards (for the summary). */
    fun export(fileName: String, open: suspend () -> OutputStream?, size: suspend () -> Long? = { null }) {
        if (_state.value != BackupUiState.Idle) return
        _state.value = BackupUiState.Exporting(fileName)
        viewModelScope.launch {
            // Once started the file is finished even if the page goes away.
            _state.value = withContext(NonCancellable) {
                try {
                    val summary = open()?.use { out -> ops.export(out) } ?: error("No stream")
                    BackupUiState.Exported(fileName, summary, runCatching { size() }.getOrNull())
                } catch (_: Exception) {
                    BackupUiState.ExportFailed(fileName)
                }
            }
        }
    }

    fun read(fileName: String, open: suspend () -> InputStream?) {
        if (_state.value != BackupUiState.Idle) return
        _state.value = BackupUiState.Reading(fileName)
        viewModelScope.launch {
            _state.value = try {
                val plan = open()?.use { ops.readBackup(it) } ?: throw BackupReadException.Corrupt("No stream")
                BackupUiState.Review(
                    fileName = fileName,
                    plan = plan,
                    choices = plan.accounts.associate { it.account.scope to it.target },
                    intoCloud = cloudSyncOn(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                BackupUiState.ReadFailed(fileName, failureOf(error))
            }
        }
    }

    /** Where the account [scope] goes; ignored when [target] isn't one of its options. */
    fun choose(scope: String, target: ImportTarget) {
        val review = _state.value as? BackupUiState.Review ?: return
        val account = review.plan.accounts.firstOrNull { it.account.scope == scope } ?: return
        if (target !in account.targetOptions() && target != account.target) return
        _state.value = review.copy(choices = review.choices + (scope to target))
    }

    fun confirmImport() {
        val review = _state.value as? BackupUiState.Review ?: return
        _state.value = BackupUiState.Importing(review)
        viewModelScope.launch {
            _state.value = withContext(NonCancellable) {
                try {
                    BackupUiState.Imported(review, ops.import(review.plan, review.choices))
                } catch (_: Exception) {
                    BackupUiState.ImportFailed(review)
                }
            }
        }
    }

    private fun failureOf(error: Exception): BackupReadFailure = when (error) {
        is BackupReadException.NotABackup -> BackupReadFailure.NotABackup
        is BackupReadException.TooNew -> BackupReadFailure.TooNew
        is BackupReadException.ChecksumMismatch -> BackupReadFailure.Checksum
        else -> BackupReadFailure.Unreadable
    }

    class Factory(private val ops: MemoriesOps, private val cloudSyncOn: () -> Boolean) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BackupViewModel(ops, cloudSyncOn) as T
    }
}
