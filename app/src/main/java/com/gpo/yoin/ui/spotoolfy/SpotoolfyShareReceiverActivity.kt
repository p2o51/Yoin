package com.gpo.yoin.ui.spotoolfy

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.gpo.yoin.MainActivity
import com.gpo.yoin.R
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.migration.spotoolfy.SpotoolfyImporter.ReceiveResult
import com.gpo.yoin.ui.landing.LandingPresence
import com.gpo.yoin.ui.landing.SharedPrefsLandingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Import to Yoin" in Spotoolfy's share sheet (docs/spotoolfy-migration.md §5.4). No window of its own: it copies
 * the shared file into Yoin's no-backup storage at once (the read permission belongs to this activity and ends with
 * it), then brings MainActivity forward, where the landing's Spotoolfy step reads it. Only staged there: nothing
 * reaches an account before Spotify connects.
 *
 * A file only has somewhere to go during the landing, or before the first one (shared before Yoin was ever opened);
 * any other time it is refused with a line and dropped.
 */
class SpotoolfyShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = sharedUri(intent)
        if (savedInstanceState != null || uri == null) {
            finish()
            return
        }
        if (!LandingPresence.showing && SharedPrefsLandingStore(this).isDone()) {
            Toast.makeText(this, R.string.spotoolfy_share_outside_landing, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val importer = (application as YoinApplication).container.spotoolfyImporter
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)?.let(importer::receive) }.getOrNull()
                    ?: ReceiveResult.Unreadable
            }
            when (result) {
                ReceiveResult.Received -> Unit
                ReceiveResult.TooLarge -> toast(R.string.landing_spotoolfy_failed_too_large)
                ReceiveResult.Unreadable -> toast(R.string.landing_spotoolfy_failed_unreadable)
            }
            runCatching { startActivity(MainActivity.bringForwardIntent(this@SpotoolfyShareReceiverActivity)) }
            finish()
        }
    }

    private fun toast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun sharedUri(intent: Intent?): Uri? {
        val send = intent?.takeIf { it.action == Intent.ACTION_SEND } ?: return null
        return IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java)
            ?: send.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    }
}
