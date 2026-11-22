package com.gpo.yoin.lyra.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.edition.EditionFeatures
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.ui.settings.SettingsActivity
import com.gpo.yoin.ui.settings.sync.CloudSyncActivity

/**
 * Full-screen native Lyra Hub Activity (Pattern A predictive back, inheriting Yoin's
 * cover-adaptive Material 3 Expressive theme via [YoinActivityRoot]).
 */
class LyraHubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val app = application as YoinApplication
        val session = EditionFeatures.sessionManager(this, app.container)
        val initialTab = LyraHubTab.fromKey(intent.getStringExtra(EXTRA_TAB))
        val prefillInboxUrl = intent.getStringExtra(EXTRA_INBOX_URL)
        val prefillPassUrl = intent.getStringExtra(EXTRA_PASS_URL)
        val uploadUris = readUploadUris(intent)

        setContent {
            YoinActivityRoot {
                val vm: LyraHubViewModel = viewModel(
                    factory = LyraHubViewModel.Factory(
                        context = this,
                        session = session,
                        initialTab = initialTab,
                        prefillInboxUrl = prefillInboxUrl,
                        prefillPassUrl = prefillPassUrl,
                    ),
                )
                LaunchedEffect(uploadUris) {
                    if (uploadUris.isNotEmpty()) {
                        vm.uploadInboxAudioUris(uploadUris)
                    }
                }
                LyraHubScreen(
                    viewModel = vm,
                    onBack = { finish() },
                    onOpenSettings = { startActivity(SettingsActivity.intent(this, null)) },
                    onOpenSync = { startActivity(CloudSyncActivity.intent(this)) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun readUploadUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(EXTRA_UPLOAD_URIS, Uri::class.java).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<Uri>(EXTRA_UPLOAD_URIS).orEmpty()
        }
    }

    companion object {
        private const val EXTRA_TAB = "tab"
        private const val EXTRA_INBOX_URL = "inboxUrl"
        private const val EXTRA_PASS_URL = "passUrl"
        private const val EXTRA_UPLOAD_URIS = "uploadUris"

        fun intent(
            context: Context,
            initialTab: LyraHubTab = LyraHubTab.Sources,
            prefillInboxUrl: String? = null,
            prefillPassUrl: String? = null,
            uploadAudioUris: ArrayList<Uri>? = null,
        ): Intent = Intent(context, LyraHubActivity::class.java).apply {
            putExtra(EXTRA_TAB, initialTab.key)
            if (!prefillInboxUrl.isNullOrBlank()) putExtra(EXTRA_INBOX_URL, prefillInboxUrl)
            if (!prefillPassUrl.isNullOrBlank()) putExtra(EXTRA_PASS_URL, prefillPassUrl)
            if (!uploadAudioUris.isNullOrEmpty()) putParcelableArrayListExtra(EXTRA_UPLOAD_URIS, uploadAudioUris)
        }
    }
}
