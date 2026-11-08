package com.gpo.yoin.ui.memories.ledger

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.YoinApplication
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
                MemoryLedgerScreen(
                    state = state,
                    onBackClick = { finish() },
                    onOpenAlbum = { entry ->
                        // Only the account in use's albums open here (the screen doesn't offer the rest).
                        val album = entry.album ?: return@MemoryLedgerScreen
                        startActivity(AlbumDetailActivity.intent(this, album.mediaId.toString()))
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, MemoryLedgerActivity::class.java)
    }
}
