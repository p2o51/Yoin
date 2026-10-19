package com.gpo.yoin.ui.tips

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.edit
import com.gpo.yoin.ui.home.HomeBubbleTip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Which tips the user has marked read (owner 2026-10-18): Home's bubble only
 * brings up the unread ones, and stops once all are read. Kept on this device
 * only, beside the bubble's other hints.
 */
internal interface TipReadStore {
    val read: StateFlow<Set<HomeBubbleTip>>

    fun setRead(tip: HomeBubbleTip, read: Boolean)

    fun setAllUnread()
}

/** The tips still unread, in the bubble's order. */
internal fun Set<HomeBubbleTip>.unreadTips(): List<HomeBubbleTip> = HomeBubbleTip.entries.filterNot { it in this }

/**
 * After [current] is read, the next unread tip after it (wrapping); null once
 * every tip is read.
 */
internal fun nextUnreadTip(current: HomeBubbleTip, read: Set<HomeBubbleTip>): HomeBubbleTip? {
    val tips = HomeBubbleTip.entries
    val start = tips.indexOf(current)
    return (1 until tips.size)
        .map { tips[(start + it) % tips.size] }
        .firstOrNull { it !in read && it != current }
}

/** One per process, so the reader, Settings and Home see the same set at once. */
internal class SharedPrefsTipReadStore private constructor(context: Context) : TipReadStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(decode(prefs.getStringSet(KEY_READ, emptySet()).orEmpty()))

    override val read: StateFlow<Set<HomeBubbleTip>> = state.asStateFlow()

    override fun setRead(tip: HomeBubbleTip, read: Boolean) {
        state.update { if (read) it + tip else it - tip }
        persist()
    }

    override fun setAllUnread() {
        state.value = emptySet()
        persist()
    }

    private fun persist() {
        prefs.edit { putStringSet(KEY_READ, state.value.mapTo(mutableSetOf()) { it.name }) }
    }

    companion object {
        // Shared with the bubble's other memory (HomeMemoryBubble.kt).
        private const val PREFS_NAME = "yoin_ui_hints"
        private const val KEY_READ = "tip_read"

        @Volatile
        private var instance: SharedPrefsTipReadStore? = null

        fun get(context: Context): SharedPrefsTipReadStore =
            instance ?: synchronized(this) {
                instance ?: SharedPrefsTipReadStore(context).also { instance = it }
            }

        // A tip renamed or removed since it was read simply drops out.
        private fun decode(names: Set<String>): Set<HomeBubbleTip> =
            HomeBubbleTip.entries.filterTo(mutableSetOf()) { it.name in names }
    }
}

internal class InMemoryTipReadStore(initial: Set<HomeBubbleTip> = emptySet()) : TipReadStore {
    private val state = MutableStateFlow(initial)

    override val read: StateFlow<Set<HomeBubbleTip>> = state.asStateFlow()

    override fun setRead(tip: HomeBubbleTip, read: Boolean) {
        state.update { if (read) it + tip else it - tip }
    }

    override fun setAllUnread() {
        state.value = emptySet()
    }
}

/** Null = the app's shared store; harnesses and tests provide their own. */
internal val LocalTipReadStore = staticCompositionLocalOf<TipReadStore?> { null }
