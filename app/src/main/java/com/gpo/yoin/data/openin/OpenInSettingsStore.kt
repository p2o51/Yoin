package com.gpo.yoin.data.openin

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings › Features and appearance › Music links › Detect music links in clipboard (on by default), and
 * what the clipboard check remembers between foregrounds: the timestamp of the last clip it looked at and a
 * hash of the last link it offered. Never the clip itself. Kept on this device only.
 */
class OpenInSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val enabled = MutableStateFlow(prefs.getBoolean(KEY_CLIPBOARD_ENABLED, true))

    val clipboardDetectionEnabled: StateFlow<Boolean> = enabled.asStateFlow()

    fun setClipboardDetectionEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_CLIPBOARD_ENABLED, value) }
        enabled.value = value
    }

    var lastSeenClipTimestampMs: Long?
        get() = prefs.getLong(KEY_LAST_SEEN_TIMESTAMP, -1L).takeIf { it >= 0L }
        set(value) = prefs.edit {
            if (value == null) remove(KEY_LAST_SEEN_TIMESTAMP) else putLong(KEY_LAST_SEEN_TIMESTAMP, value)
        }

    var lastHandledLinkHash: String?
        get() = prefs.getString(KEY_LAST_HANDLED_HASH, null)
        set(value) = prefs.edit { putString(KEY_LAST_HANDLED_HASH, value) }

    private companion object {
        const val PREFS_NAME = "yoin_open_in"
        const val KEY_CLIPBOARD_ENABLED = "clipboard_enabled"
        const val KEY_LAST_SEEN_TIMESTAMP = "clipboard_last_seen_timestamp"
        const val KEY_LAST_HANDLED_HASH = "clipboard_last_handled_hash"
    }
}
