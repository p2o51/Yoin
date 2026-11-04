package com.gpo.yoin.data.offline

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The stored [DownloadSettings], for this device (storage and data use are the device's, not the account's). */
class DownloadSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(
        DownloadSettings(
            quality = DownloadQuality.fromKey(prefs.getString(KEY_QUALITY, null)) ?: DownloadSettings.DefaultQuality,
            overMobileData = prefs.getBoolean(KEY_MOBILE_DATA, false),
        ),
    )

    val settings: StateFlow<DownloadSettings> = state.asStateFlow()

    val current: DownloadSettings get() = state.value

    fun setQuality(quality: DownloadQuality) = update { it.copy(quality = quality) }

    fun setOverMobileData(allowed: Boolean) = update { it.copy(overMobileData = allowed) }

    private fun update(change: (DownloadSettings) -> DownloadSettings) {
        val next = change(state.value)
        state.value = next
        prefs.edit {
            putString(KEY_QUALITY, next.quality.key)
            putBoolean(KEY_MOBILE_DATA, next.overMobileData)
        }
    }

    private companion object {
        const val PREFS_NAME = "yoin_download_preferences"
        const val KEY_QUALITY = "download_quality"
        const val KEY_MOBILE_DATA = "download_over_mobile_data"
    }
}
