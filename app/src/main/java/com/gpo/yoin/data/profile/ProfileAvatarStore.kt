package com.gpo.yoin.data.profile

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Profile id → the account's picture on its service (today: the Spotify
 * profile picture from `/me`). Only public image URLs live here — nothing
 * secret — so plain SharedPreferences, kept apart from the encrypted
 * credentials and the Room profile row.
 */
class ProfileAvatarStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _urls = MutableStateFlow(read())

    /** Every known avatar, by profile id. */
    val urls: StateFlow<Map<String, String>> = _urls.asStateFlow()

    /** Records [url] for [profileId]; null or blank forgets it (the account has no picture). */
    fun put(profileId: String, url: String?) {
        val clean = url?.takeIf { it.isNotBlank() }
        if (_urls.value[profileId] == clean) return
        prefs.edit { if (clean == null) remove(profileId) else putString(profileId, clean) }
        _urls.update { current -> if (clean == null) current - profileId else current + (profileId to clean) }
    }

    fun remove(profileId: String) = put(profileId, null)

    private fun read(): Map<String, String> =
        prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()

    private companion object {
        const val PREFS_NAME = "yoin_profile_avatars"
    }
}
