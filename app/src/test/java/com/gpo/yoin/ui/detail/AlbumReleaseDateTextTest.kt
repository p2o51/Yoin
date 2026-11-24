package com.gpo.yoin.ui.detail

import android.app.Application
import com.gpo.yoin.data.model.ReleaseDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AlbumReleaseDateTextTest {
    private val day = ReleaseDate(2023, 10, 27)
    private val month = ReleaseDate(2023, 10)
    private val year = ReleaseDate(2023)

    @Test
    fun should_writeTheDateInChinese_when_localeIsZh() {
        assertEquals("2023年10月27日", albumReleaseDateText(day, Locale.SIMPLIFIED_CHINESE))
        assertEquals("2023年10月", albumReleaseDateText(month, Locale.SIMPLIFIED_CHINESE))
        assertEquals("2023", albumReleaseDateText(year, Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun should_writeTheDateInEnglish_when_localeIsEn() {
        assertEquals("October 27, 2023", albumReleaseDateText(day, Locale.US))
        assertEquals("October 2023", albumReleaseDateText(month, Locale.US))
        assertEquals("2023", albumReleaseDateText(year, Locale.US))
    }

    @Test
    fun should_writeTheDateInJapanese_when_localeIsJa() {
        assertEquals("2023年10月27日", albumReleaseDateText(day, Locale.JAPAN))
        assertEquals("2023年10月", albumReleaseDateText(month, Locale.JAPAN))
        assertEquals("2023", albumReleaseDateText(year, Locale.JAPAN))
    }

    @Test
    fun should_neverShiftTheDay_when_theDeviceZoneIsFarFromUtc() {
        val saved = java.util.TimeZone.getDefault()
        try {
            listOf("Pacific/Kiritimati", "Pacific/Pago_Pago", "Asia/Tokyo").forEach { zone ->
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone))
                assertEquals("January 1, 2000", albumReleaseDateText(ReleaseDate(2000, 1, 1), Locale.US))
            }
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }

    @Test
    fun should_writeTheCompactHeaderDate_when_eachPrecision() {
        assertEquals("22.6.12", albumHeaderDate(ReleaseDate(2022, 6, 12), 2022))
        assertEquals("22.6", albumHeaderDate(ReleaseDate(2022, 6), 2022))
        assertEquals("2022", albumHeaderDate(ReleaseDate(2022), 2022))
        assertEquals("05.11.3", albumHeaderDate(ReleaseDate(2005, 11, 3), 2005))
        assertEquals("2022", albumHeaderDate(null, 2022))
        assertNull(albumHeaderDate(null, null))
    }

    @Test
    fun should_writeTheReceiptLines_when_dateAndLengthAreKnown() {
        assertEquals("Jun 12, 2022", receiptReleaseDate(ReleaseDate(2022, 6, 12)))
        assertEquals("Jun 2022", receiptReleaseDate(ReleaseDate(2022, 6)))
        assertEquals("2022", receiptReleaseDate(ReleaseDate(2022)))
        assertEquals("47 min", receiptAlbumLength(47 * 60 + 59))
        assertEquals("1 hr 12 min", receiptAlbumLength(72 * 60))
        assertEquals("2 hr", receiptAlbumLength(120 * 60 + 30))
        assertEquals("1 min", receiptAlbumLength(30))
        assertNull(receiptAlbumLength(0))
        assertNull(receiptAlbumLength(null))
    }
}
