package com.gpo.yoin.ui.home

import android.content.SharedPreferences
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whose turn Jump Back In's memory 1×2s are (owner 2026-11-20, #yoin:5f7233e7): one more each time
 * the app starts, and each time it comes back after [awayMs] in the background — a player lives in
 * the background for hours, so a cold start alone would hardly ever come. Coming back sooner (a
 * reply to a message) keeps the turn, so nothing changes under the user's eyes. Kept on this device.
 */
class JbiMemoryTurn(
    private val prefs: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
    private val awayMs: Long = AWAY_MS,
) : DefaultLifecycleObserver {
    private val state = MutableStateFlow(advance())
    val turn: StateFlow<Long> = state.asStateFlow()
    private var backgroundAt: Long? = null

    override fun onStop(owner: LifecycleOwner) {
        backgroundAt = clock()
    }

    override fun onStart(owner: LifecycleOwner) {
        val away = backgroundAt ?: return
        backgroundAt = null
        if (clock() - away >= awayMs) state.value = advance()
    }

    private fun advance(): Long {
        val next = prefs.getLong(KEY, 0L) + 1L
        prefs.edit().putLong(KEY, next).apply()
        return next
    }

    companion object {
        const val AWAY_MS = 30 * 60 * 1000L
        private const val KEY = "memory_turn"
    }
}
