package com.gpo.yoin.data.profile

import android.content.Context
import androidx.core.content.edit

/**
 * Persists which profile id is currently active. Split out from
 * [ProfileManager] so unit tests can substitute an in-memory store without
 * Robolectric.
 */
interface ProfileActiveIdStore {
    fun read(): String?
    fun write(id: String?)
}

class SharedPrefsProfileActiveIdStore(context: Context) : ProfileActiveIdStore {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)

    override fun write(id: String?) {
        prefs.edit {
            if (id == null) remove(KEY_ACTIVE_PROFILE_ID) else putString(KEY_ACTIVE_PROFILE_ID, id)
        }
    }

    companion object {
        private const val PREFS_NAME = "yoin_profiles"
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    }
}
