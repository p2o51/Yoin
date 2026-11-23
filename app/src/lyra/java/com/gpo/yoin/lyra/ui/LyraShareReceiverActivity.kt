package com.gpo.yoin.lyra.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Receives Android share-sheet intents ("Save to Lyra") and routes them directly into [LyraHubActivity]:
 * - Lyra Share Pass links (`.../s/<token>`) → Passes tab (ready to Pull)
 * - Other URLs (Bilibili / YouTube / web links) → Inbox tab (ready to save link)
 * - Audio files (audio MIME types, including Quick Share) → Inbox tab (immediate streaming upload)
 */
class LyraShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launch = buildHubIntent(intent)
        if (launch != null) {
            startActivity(launch)
        }
        finish()
    }

    internal fun buildHubIntent(incoming: Intent?): Intent? {
        if (incoming == null) return null
        return when (incoming.action) {
            Intent.ACTION_SEND -> {
                val text = incoming.getStringExtra(Intent.EXTRA_TEXT)?.trim()
                val streamUri = readParcelableUri(incoming, Intent.EXTRA_STREAM)
                when {
                    !text.isNullOrBlank() -> {
                        val url = extractFirstUrl(text) ?: text
                        if (isLyraPassUrl(url)) {
                            LyraHubActivity.intent(this, initialTab = LyraHubTab.Passes, prefillPassUrl = url)
                        } else {
                            LyraHubActivity.intent(this, initialTab = LyraHubTab.Inbox, prefillInboxUrl = url)
                        }
                    }
                    streamUri != null -> buildAudioUploadIntent(listOf(streamUri))
                    else -> null
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = readParcelableUriList(incoming, Intent.EXTRA_STREAM)
                if (uris.isEmpty()) null else buildAudioUploadIntent(uris)
            }
            else -> null
        }
    }

    /**
     * Attaches every shared URI to [Intent.clipData] alongside [Intent.FLAG_GRANT_READ_URI_PERMISSION]
     * so Android forwards read grants to [LyraHubActivity] even after this `noHistory` trampoline finishes.
     */
    private fun buildAudioUploadIntent(uris: List<Uri>): Intent {
        val hubIntent = LyraHubActivity.intent(
            this,
            initialTab = LyraHubTab.Inbox,
            uploadAudioUris = ArrayList(uris),
        )
        val clip = ClipData.newRawUri("lyra-audio", uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        hubIntent.clipData = clip
        hubIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return hubIntent
    }

    @Suppress("DEPRECATION")
    private fun readParcelableUri(intent: Intent, key: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            intent.getParcelableExtra(key)
        }

    @Suppress("DEPRECATION")
    private fun readParcelableUriList(intent: Intent, key: String): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(key, Uri::class.java).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<Uri>(key).orEmpty()
        }

    companion object {
        private val URL_REGEX = Regex("""https?://[^\s<>"]+""")
        private val PASS_PATH_REGEX = Regex("""https?://[^/\s]+/s/[A-Za-z0-9_-]{4,}""")

        internal fun extractFirstUrl(text: String): String? =
            URL_REGEX.find(text)?.value?.trimEnd('.', ',', ')', ']', '，', '。')

        internal fun isLyraPassUrl(url: String): Boolean = PASS_PATH_REGEX.containsMatchIn(url)
    }
}
