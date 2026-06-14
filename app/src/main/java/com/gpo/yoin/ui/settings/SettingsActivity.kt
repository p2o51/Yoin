package com.gpo.yoin.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.enableYoinEdgeToEdge

/** Standalone Activity for Settings (device-native cross-Activity back). */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val focusSection = intent.getStringExtra(EXTRA_FOCUS_SECTION)
        setContent {
            YoinActivityRoot {
                val app = LocalContext.current.applicationContext as YoinApplication
                val viewModel: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.Factory(app.container),
                )
                SettingsScreen(
                    viewModel = viewModel,
                    onBackClick = { finish() },
                    focusSection = focusSection,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    companion object {
        private const val EXTRA_FOCUS_SECTION = "focusSection"

        fun intent(context: Context, focusSection: String? = null): Intent =
            Intent(context, SettingsActivity::class.java).apply {
                focusSection?.let { putExtra(EXTRA_FOCUS_SECTION, it) }
            }
    }
}
