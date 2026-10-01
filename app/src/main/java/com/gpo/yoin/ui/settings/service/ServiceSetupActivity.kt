package com.gpo.yoin.ui.settings.service

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
 * Settings' second level: what a service does + how to connect it.
 *
 * Back surface: full-screen destination without shared chrome → native
 * cross-Activity predictive back (Pattern A). No back code here on purpose;
 * the top-bar arrow just finishes.
 */
class ServiceSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val request = ServiceSetupContract.readRequest(intent)
        val container = (application as YoinApplication).container
        setContent {
            YoinActivityRoot {
                val viewModel: ServiceSetupViewModel = viewModel(
                    factory = ServiceSetupViewModel.Factory(container, request),
                )
                ServiceSetupScreen(
                    viewModel = viewModel,
                    focusClientId = request.focusClientId,
                    onBackClick = { finish() },
                    onDone = { activateProfileId ->
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(
                                ServiceSetupContract.EXTRA_ACTIVATE_PROFILE_ID,
                                activateProfileId,
                            ),
                        )
                        finish()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
