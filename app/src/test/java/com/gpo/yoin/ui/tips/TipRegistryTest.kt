package com.gpo.yoin.ui.tips

import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.ui.home.HomeBubbleTip
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Every tip says something in English and Chinese, and the 2026-11-23 notes and sync tips show on every Android. */
@RunWith(RobolectricTestRunner::class)
class TipRegistryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun resourcesFor(locale: Locale) =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
            .resources

    @Test
    fun should_haveATitleAndALine_when_readInEnglishOrChinese() {
        listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE).forEach { locale ->
            val res = resourcesFor(locale)
            HomeBubbleTip.entries.forEach { tip ->
                assertFalse("$tip title ($locale)", res.getString(tip.title).isBlank())
                assertFalse("$tip line ($locale)", res.getString(tip.text).isBlank())
            }
        }
    }

    @Test
    fun should_neverShareCopy_when_twoTipsSayDifferentThings() {
        val titles = HomeBubbleTip.entries.map { it.title }
        val lines = HomeBubbleTip.entries.map { it.text }
        assertEquals(titles.size, titles.toSet().size)
        assertEquals(lines.size, lines.toSet().size)
    }

    @Test
    fun should_comeUpOnEveryAndroid_when_theTipIsAboutAlbumNotesOrCloudSync() {
        val new = listOf(HomeBubbleTip.AlbumStickies, HomeBubbleTip.MentionedIn, HomeBubbleTip.CloudExportTransfer)
        new.forEach { tip ->
            assertEquals(LocalDate.of(2026, 11, 23), tip.updated)
            assertTrue(tip in HomeBubbleTip.available(sdk = 26))
        }
    }
}
