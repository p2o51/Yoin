package com.gpo.yoin.data.offline

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Settings › Downloads: Spotify's defaults, kept across launches. */
@RunWith(RobolectricTestRunner::class)
class DownloadSettingsStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clear() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { clear() }
    }

    @Test
    fun should_start320_andWifiOnly_when_nothingStored() {
        val store = DownloadSettingsStore(context)
        assertEquals(DownloadQuality.Kbps320, store.current.quality)
        assertFalse(store.current.overMobileData)
    }

    @Test
    fun should_keepChoices_acrossLaunches() {
        DownloadSettingsStore(context).apply {
            setQuality(DownloadQuality.Original)
            setOverMobileData(true)
        }
        val relaunched = DownloadSettingsStore(context)
        assertEquals(DownloadQuality.Original, relaunched.current.quality)
        assertTrue(relaunched.current.overMobileData)
    }

    @Test
    fun should_publishEachChange_onTheFlow() {
        val store = DownloadSettingsStore(context)
        store.setQuality(DownloadQuality.Kbps128)
        assertEquals(DownloadSettings(DownloadQuality.Kbps128, overMobileData = false), store.settings.value)
        store.setOverMobileData(true)
        // Changing one leaves the other as it was.
        assertEquals(DownloadSettings(DownloadQuality.Kbps128, overMobileData = true), store.settings.value)
    }

    @Test
    fun should_fallBackTo320_when_storedQualityUnknown() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putString("download_quality", "flac-hi")
        }
        assertEquals(DownloadQuality.Kbps320, DownloadSettingsStore(context).current.quality)
    }

    private companion object {
        const val PREFS = "yoin_download_preferences"
    }
}
