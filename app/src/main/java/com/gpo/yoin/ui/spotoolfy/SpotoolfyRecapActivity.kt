package com.gpo.yoin.ui.spotoolfy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.migration.spotoolfy.SpotoolfyImporter
import com.gpo.yoin.data.migration.spotoolfy.SpotoolfyRecap
import com.gpo.yoin.enableYoinEdgeToEdge
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * The Spotoolfy recap (docs/spotoolfy-migration.md §6): two pages, opened by the landing once a file is read, and
 * again from Settings › About. Back class: Pattern A, a full-screen destination with no shared chrome, so it
 * registers no back callback and the system's cross-Activity predictive back returns to the landing or to
 * Settings. Its explicit affordance is the close button.
 *
 * Everything comes from the batch's stored recap ([SpotoolfyImporter.recap]) by [EXTRA_BATCH_ID]: the Activity
 * boundary is the data boundary.
 */
class SpotoolfyRecapActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val batchId = intent.getStringExtra(EXTRA_BATCH_ID).orEmpty()
        val fromSettings = intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)
        val importer = (application as YoinApplication).container.spotoolfyImporter
        setContent {
            YoinActivityRoot {
                val vm: SpotoolfyRecapViewModel =
                    viewModel(factory = SpotoolfyRecapViewModel.Factory(importer, batchId))
                val state by vm.state.collectAsState()
                SpotoolfyRecapScreen(
                    state = state,
                    fromSettings = fromSettings,
                    onClose = ::finish,
                    onJoin = {
                        setResult(Activity.RESULT_OK)
                        finish()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    /** The landing's launch: the result is true when the user tapped "Join Yoin" on the last page. */
    class JoinContract : ActivityResultContract<String, Boolean>() {
        override fun createIntent(context: Context, input: String): Intent =
            intent(context, batchId = input, fromSettings = false)

        override fun parseResult(resultCode: Int, intent: Intent?): Boolean = resultCode == Activity.RESULT_OK
    }

    companion object {
        private const val EXTRA_BATCH_ID = "batchId"
        private const val EXTRA_FROM_SETTINGS = "fromSettings"

        /** [fromSettings]: reopened from Settings › About, so the last page ends with "Done" instead of joining. */
        fun intent(context: Context, batchId: String, fromSettings: Boolean): Intent =
            Intent(context, SpotoolfyRecapActivity::class.java)
                .putExtra(EXTRA_BATCH_ID, batchId)
                .putExtra(EXTRA_FROM_SETTINGS, fromSettings)
    }
}

internal sealed interface RecapUiState {
    data object Loading : RecapUiState

    data class Ready(val recap: SpotoolfyRecap) : RecapUiState

    /** The batch went (the user used another file, or storage was cleared). */
    data object Missing : RecapUiState
}

internal class SpotoolfyRecapViewModel(importer: SpotoolfyImporter, batchId: String) : ViewModel() {
    val state: StateFlow<RecapUiState> = flow {
        emit(importer.recap(batchId)?.let { RecapUiState.Ready(it) } ?: RecapUiState.Missing)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RecapUiState.Loading)

    class Factory(private val importer: SpotoolfyImporter, private val batchId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SpotoolfyRecapViewModel(importer, batchId) as T
    }
}
