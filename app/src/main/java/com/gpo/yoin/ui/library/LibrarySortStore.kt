package com.gpo.yoin.ui.library

import android.content.Context
import androidx.core.content.edit

/**
 * Each profile's chosen order per Library view. Kept on this device: its own
 * SharedPreferences file, out of Room and the cloud sync. Auto Backup leaves it
 * out on Android 12+ only (data_extraction_rules.xml lists only what it backs
 * up); Android 8–11 read no rules (the manifest has no fullBackupContent) and
 * back up every SharedPreferences file, this one too. Harmless: its keys are
 * profile ids, and the database they name is backed up with it.
 */
interface LibrarySortStore {
    /** The order last chosen for [view], or null when none was. */
    fun sortFor(profileId: String, view: LibraryTab): LibrarySort?

    fun setSort(profileId: String, view: LibraryTab, sort: LibrarySort)

    /** Process-local, for previews and tests. */
    class InMemory : LibrarySortStore {
        private val sorts = HashMap<Pair<String, LibraryTab>, LibrarySort>()

        override fun sortFor(profileId: String, view: LibraryTab): LibrarySort? = sorts[profileId to view]

        override fun setSort(profileId: String, view: LibraryTab, sort: LibrarySort) {
            sorts[profileId to view] = sort
        }
    }
}

class SharedPrefsLibrarySortStore(context: Context) : LibrarySortStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun sortFor(profileId: String, view: LibraryTab): LibrarySort? {
        val stored = prefs.getString(key(profileId, view), null) ?: return null
        // An order a later build dropped reads as none chosen.
        return LibrarySort.entries.firstOrNull { it.name == stored }
    }

    override fun setSort(profileId: String, view: LibraryTab, sort: LibrarySort) {
        prefs.edit { putString(key(profileId, view), sort.name) }
    }

    private fun key(profileId: String, view: LibraryTab): String = "$profileId/${view.name}"

    private companion object {
        const val PREFS_NAME = "yoin_library_sort"
    }
}
