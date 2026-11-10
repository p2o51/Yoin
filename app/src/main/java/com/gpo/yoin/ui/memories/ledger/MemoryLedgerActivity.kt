package com.gpo.yoin.ui.memories.ledger

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.edit
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.memory.MemoryLedgerView
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.ui.detail.AlbumDetailActivity

/**
 * All Memories (owner 2026-11-08), opened from Home's Memories grid.
 *
 * Back surface: full-screen destination without shared chrome → native cross-Activity predictive back
 * (Pattern A). No back code here: the top-bar arrow just finishes.
 */
class MemoryLedgerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val container = (application as YoinApplication).container
        setContent {
            YoinActivityRoot {
                val viewModel: MemoryLedgerViewModel = viewModel(factory = MemoryLedgerViewModel.Factory(container))
                val state by viewModel.state.collectAsState()
                // The view picked last time, kept on this device.
                val viewState = rememberSaveable { mutableStateOf(storedView()) }
                LaunchedEffect(viewState.value) { storeView(viewState.value) }
                MemoryLedgerScreen(
                    state = state,
                    onBackClick = { finish() },
                    onOpenAlbum = { album ->
                        // Only the account in use's albums open here (the screen doesn't offer the rest).
                        startActivity(AlbumDetailActivity.intent(this, album.mediaId.toString()))
                    },
                    viewState = viewState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun storedView(): MemoryLedgerView {
        val stored = prefs().getString(KEY_VIEW, null)
        // A view a later build dropped reads as the timeline.
        return MemoryLedgerView.entries.firstOrNull { it.name == stored } ?: MemoryLedgerView.Timeline
    }

    private fun storeView(view: MemoryLedgerView) {
        prefs().edit { putString(KEY_VIEW, view.name) }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, MemoryLedgerActivity::class.java)

        private const val PREFS_NAME = "yoin_memory_ledger"
        private const val KEY_VIEW = "view"
    }
}
