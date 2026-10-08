package com.gpo.yoin.ui.home

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.core.content.edit

/**
 * What Home edit mode remembers about teaching itself, per device (the person
 * learns the gesture, not the account): how many edit sessions have started,
 * and which section ids the user has already met in the tray.
 */
interface HomeEditHintStore {
    fun editSessionCount(): Int

    fun recordEditSession()

    fun seenSectionIds(): Set<String>

    fun markSectionsSeen(ids: Collection<String>)

    /** Process-local fallback for previews and tests. */
    class InMemory : HomeEditHintStore {
        private var sessions = 0
        private val seen = LinkedHashSet<String>()

        override fun editSessionCount(): Int = sessions

        override fun recordEditSession() {
            sessions += 1
        }

        override fun seenSectionIds(): Set<String> = seen.toSet()

        override fun markSectionsSeen(ids: Collection<String>) {
            seen += ids
        }
    }
}

class SharedPrefsHomeEditHintStore(context: Context) : HomeEditHintStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun editSessionCount(): Int = prefs.getInt(KEY_EDIT_SESSIONS, 0)

    override fun recordEditSession() {
        prefs.edit { putInt(KEY_EDIT_SESSIONS, editSessionCount() + 1) }
    }

    // A copy: the set SharedPreferences hands out must never be mutated.
    override fun seenSectionIds(): Set<String> = prefs.getStringSet(KEY_SECTIONS_SEEN, null)?.toSet().orEmpty()

    override fun markSectionsSeen(ids: Collection<String>) {
        val seen = seenSectionIds()
        if (seen.containsAll(ids)) return
        prefs.edit { putStringSet(KEY_SECTIONS_SEEN, HashSet(seen + ids)) }
    }

    private companion object {
        // Shared with the lyric idle hint and the memory bubble.
        const val PREFS_NAME = "yoin_ui_hints"
        const val KEY_EDIT_SESSIONS = "home_edit_sessions"
        const val KEY_SECTIONS_SEEN = "home_sections_seen"
    }
}

/** One edit session's hints, snapshotted when it starts. */
@Immutable
data class HomeEditSessionHints(
    val showHeaderHint: Boolean = false,
    val newBadges: Set<HomeSection> = emptySet(),
)

/** Edit sessions recorded for the retired header hint (proto.js HINT_SESSIONS). */
internal const val HomeEditHeaderHintSessions = 2

/** Whether the session starting after [sessionsBefore] earlier ones shows the header hint. */
internal fun showEditHeaderHint(sessionsBefore: Int): Boolean = sessionsBefore < HomeEditHeaderHintSessions
