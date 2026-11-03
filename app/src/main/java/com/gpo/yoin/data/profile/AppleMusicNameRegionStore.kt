package com.gpo.yoin.data.profile

import android.content.Context
import androidx.core.content.edit
import com.gpo.yoin.data.source.applemusic.AppleMusicNameRegion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Profile id → the Apple Music account's [AppleMusicNameRegion]. A display choice, nothing secret: plain
 * SharedPreferences, apart from the encrypted credentials, as [ProfileAvatarStore] keeps avatars. An account
 * with no entry shows names as its storefront does ([AppleMusicNameRegion.Default]).
 */
class AppleMusicNameRegionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _regions = MutableStateFlow(read())

    val regions: StateFlow<Map<String, AppleMusicNameRegion>> = _regions.asStateFlow()

    fun get(profileId: String): AppleMusicNameRegion = _regions.value[profileId] ?: AppleMusicNameRegion.Default

    fun put(profileId: String, region: AppleMusicNameRegion) {
        if (get(profileId) == region) return
        prefs.edit {
            if (region == AppleMusicNameRegion.Default) remove(profileId) else putString(profileId, region.key)
        }
        _regions.update { current ->
            if (region == AppleMusicNameRegion.Default) current - profileId else current + (profileId to region)
        }
    }

    fun remove(profileId: String) = put(profileId, AppleMusicNameRegion.Default)

    private fun read(): Map<String, AppleMusicNameRegion> = prefs.all.mapNotNull { (key, value) ->
        (value as? String)?.let { key to AppleMusicNameRegion.fromKey(it) }
    }.toMap()

    private companion object {
        const val PREFS_NAME = "yoin_apple_music_name_regions"
    }
}
