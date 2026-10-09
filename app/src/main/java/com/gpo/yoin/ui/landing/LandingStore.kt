package com.gpo.yoin.ui.landing

import android.content.Context
import androidx.core.content.edit

/**
 * Whether this install has been through the landing. Set when the landing
 * finishes, and at the first launch that finds an account (an existing user
 * never sees it), so deleting every account later does not bring it back —
 * Settings › About › Welcome guide re-runs it instead. Device-local on purpose
 * (yoin_ui_hints is not synced).
 */
interface LandingStore {
    fun isDone(): Boolean
    fun markDone()
}

class SharedPrefsLandingStore(context: Context) : LandingStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isDone(): Boolean = prefs.getBoolean(KEY_DONE, false)

    override fun markDone() {
        if (!isDone()) prefs.edit { putBoolean(KEY_DONE, true) }
    }

    private companion object {
        const val PREFS_NAME = "yoin_ui_hints"
        const val KEY_DONE = "landing_done_v1"
    }
}

class InMemoryLandingStore(private var done: Boolean = false) : LandingStore {
    override fun isDone(): Boolean = done
    override fun markDone() {
        done = true
    }
}
