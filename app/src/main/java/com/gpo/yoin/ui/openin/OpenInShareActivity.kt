package com.gpo.yoin.ui.openin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.gpo.yoin.MainActivity
import com.gpo.yoin.YoinApplication

/**
 * "Open in Yoin" in the share sheet (docs/open-in-yoin.md), reached through the exported `.OpenInYoin`
 * activity-alias (text/plain only; Yoin never registers to VIEW Spotify or Apple Music links, so it takes no
 * link the user didn't send it). No window of its own: it hands the shared text to
 * [com.gpo.yoin.data.openin.OpenInCoordinator] and brings the shell forward, which opens a music link in the
 * account on its service (or searches the account in use) and puts any other text into Library search.
 * The text is held in memory only until the shell takes it.
 */
class OpenInShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            sharedText(intent)?.let { (application as YoinApplication).container.openInCoordinator.offerShared(it) }
            MainActivity.bringForward(this)
        }
        finish()
    }

    private fun sharedText(intent: Intent?): String? {
        val send = intent?.takeIf { it.action == Intent.ACTION_SEND } ?: return null
        val subject = send.getStringExtra(Intent.EXTRA_SUBJECT)
        val text = send.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: send.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        // Some apps put the title in the subject and the link alone in the text.
        return listOfNotNull(text, subject).firstOrNull { it.isNotBlank() }
    }
}
