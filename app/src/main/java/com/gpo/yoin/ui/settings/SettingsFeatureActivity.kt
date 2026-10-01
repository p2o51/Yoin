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

/**
 * One Settings feature (AI features / NeoDB) as a page — opened only from the
 * Settings list-detail, where the tag="settings-setup" rule puts it in the
 * right pane (断点交接 §7). Pattern A: native cross-Activity back, no back code.
 */
class SettingsFeatureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val feature = intent.getStringExtra(EXTRA_FEATURE)
            ?.let { name -> SettingsFeature.entries.firstOrNull { it.name == name } }
        if (feature == null) {
            finish()
            return
        }
        setContent {
            YoinActivityRoot {
                val app = LocalContext.current.applicationContext as YoinApplication
                val viewModel: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.Factory(app.container),
                )
                SettingsFeatureScreen(
                    viewModel = viewModel,
                    feature = feature,
                    onBackClick = { finish() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    companion object {
        private const val EXTRA_FEATURE = "feature"

        internal fun intent(context: Context, feature: SettingsFeature): Intent =
            Intent(context, SettingsFeatureActivity::class.java).putExtra(EXTRA_FEATURE, feature.name)
    }
}
