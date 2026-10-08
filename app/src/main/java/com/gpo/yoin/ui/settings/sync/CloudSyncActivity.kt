package com.gpo.yoin.ui.settings.sync

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.enableYoinEdgeToEdge

/**
 * Settings › Cloud sync: turn Google Drive sync on, see how it's doing, and
 * manage accounts and devices.
 *
 * Back surface: full-screen destination without shared chrome → native
 * cross-Activity predictive back (Pattern A). No back code here on purpose;
 * the top-bar arrow just finishes. In the Settings list-detail it opens in
 * the right pane (tag="settings-setup").
 */
class CloudSyncActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val controller = (application as YoinApplication).container.cloudSync
        setContent {
            YoinActivityRoot {
                val viewModel: CloudSyncViewModel = viewModel(
                    factory = CloudSyncViewModel.Factory(controller, application.resources),
                )
                CloudSyncScreen(
                    viewModel = viewModel,
                    onBack = { finish() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, CloudSyncActivity::class.java)
    }
}
