package com.gpo.yoin.data.openin

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.textclassifier.TextClassifier
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.gpo.yoin.shared.openin.YoinMusicLinkParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The clipboard check (docs/open-in-yoin.md): once each time Yoin comes to the foreground, when the shell's
 * window has focus (Android only lets the focused app read the clipboard).
 *
 * First only the clip's description ([ClipDescriptionFacts]): its MIME types, its timestamp and, on API 31+,
 * the system's own guess that it holds a URL. That read shows no "pasted from your clipboard" notice. The
 * content is read only when [ClipboardLinkGate.shouldRead] says the clip is plausibly a link not looked at
 * before; then only a supported music link not offered last time ([ClipboardLinkGate.shouldOffer]) becomes a
 * prompt. The clip's text is never kept or sent: only the clip's timestamp and a hash of the offered link
 * are remembered ([OpenInSettingsStore]).
 */
class ClipboardLinkWatcher(
    private val context: Context,
    private val store: OpenInSettingsStore,
    private val coordinator: OpenInCoordinator,
    private val scope: CoroutineScope,
) {
    // Armed when the process comes to the foreground; a focus inside the app (a dialog closing, returning
    // from a page) doesn't look again.
    @Volatile private var armed = false
    private var installed = false

    /** Main thread. Idempotent. */
    fun install() {
        if (installed) return
        installed = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    armed = true
                }
            },
        )
    }

    /** The shell's window got focus. Main thread. */
    fun onShellFocused() {
        if (!armed) return
        armed = false
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val facts = runCatching { clipboard.primaryClipDescription?.let(::facts) }.getOrNull()
        if (!ClipboardLinkGate.shouldRead(facts, store.clipboardDetectionEnabled.value, store.lastSeenClipTimestampMs)) {
            return
        }
        facts?.timestampMs?.let { store.lastSeenClipTimestampMs = it }
        val item = runCatching { clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) }.getOrNull()
        // The item's own text or URI only: coerceToText could open a content URI.
        val text = item?.text?.toString() ?: item?.uri?.toString() ?: return
        val link = YoinMusicLinkParser.findFirst(text) ?: return
        val hash = ClipboardLinkGate.hash(link.key)
        if (!ClipboardLinkGate.shouldOffer(hash, store.lastHandledLinkHash)) return
        scope.launch {
            if (coordinator.offerClipboard(link)) store.lastHandledLinkHash = hash
        }
    }

    private fun facts(description: ClipDescription): ClipDescriptionFacts = ClipDescriptionFacts(
        mimeTypes = (0 until description.mimeTypeCount).map(description::getMimeType),
        timestampMs = description.timestamp.takeIf { it > 0L },
        urlConfidence = urlConfidence(description),
        sensitive = description.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true,
    )

    private fun urlConfidence(description: ClipDescription): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        if (description.classificationStatus != ClipDescription.CLASSIFICATION_COMPLETE) return null
        return runCatching { description.getConfidenceScore(TextClassifier.TYPE_URL) }.getOrNull()
    }

    private companion object {
        // ClipDescription.EXTRA_IS_SENSITIVE (API 33); the key is read on every version.
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
