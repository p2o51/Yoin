package com.gpo.yoin.ui.nowplaying

import android.content.Context
import androidx.core.content.edit

/**
 * The soft pink glow on Now Playing when About needs a Gemini key that
 * isn't set up yet (owner 2026-11-04): the "no key" text stays as it is;
 * the glow is a gentle nudge on top, and comes back at most every
 * [GEMINI_KEY_HINT_COOLDOWN_MS] so it never nags. Kept on this device only.
 */
internal object GeminiKeyHint {
    private const val PREFS_NAME = "yoin_gemini_key_hint"
    private const val KEY_LAST_SHOWN = "last_shown_ms"

    /** True when the glow may show now; records it as shown if so. */
    fun claim(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_SHOWN, 0L).takeIf { prefs.contains(KEY_LAST_SHOWN) }
        if (!isDue(last, nowMs)) return false
        prefs.edit { putLong(KEY_LAST_SHOWN, nowMs) }
        return true
    }
}

internal const val GEMINI_KEY_HINT_COOLDOWN_MS = 3L * 24 * 60 * 60 * 1000

/** Hold the glow this long, then let it fade out. */
internal const val GEMINI_KEY_HINT_HOLD_MS = 3_200L

/** Never shown, or shown at least the cooldown ago (a clock set back counts as due). */
internal fun isDue(lastShownMs: Long?, nowMs: Long): Boolean =
    lastShownMs == null || nowMs < lastShownMs || nowMs - lastShownMs >= GEMINI_KEY_HINT_COOLDOWN_MS
