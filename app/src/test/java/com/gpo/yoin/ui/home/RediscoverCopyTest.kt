package com.gpo.yoin.ui.home

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.memories.MemoryScoreKind
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Rediscover's card copy: the away text, the eyebrow and the footnote. */
class RediscoverCopyTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun away(days: Long): String = rediscoverAwayText(lastPlayedAt = now - days * day, nowMillis = now)

    @Test
    fun should_countMonths_when_awayUnderAYear() {
        assertEquals("3 months", away(90))
        assertEquals("7 months", away(214))
        assertEquals("12 months", away(364))
    }

    @Test
    fun should_countYears_when_awayAYearOrMore() {
        assertEquals("1 year", away(365))
        assertEquals("1 year", away(729))
        assertEquals("2 years", away(730))
    }

    @Test
    fun should_countWholeDays_when_lastPlayIsPartWayThroughADay() {
        // 89 days and 23 hours is still 89 whole days: 2 months, not 3.
        assertEquals("2 months", rediscoverAwayText(now - 90 * day + 60 * 60 * 1000, now))
    }

    @Test
    fun should_formatScoreWithDot_when_defaultLocaleUsesComma() {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val item = item(score = 9f, lastPlayedAt = now - 214 * day)

            assertEquals("9.0", item.scoreText)
            assertEquals("Rated 9.0 · Not played in Yoin for 7 months", rediscoverEyebrow(item, now))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun should_pluralizePlays_when_formattingFootnote() {
        val firstPlayed = LocalDate.of(2025, 11, 20).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        assertEquals("First played 2025.11 · 23 plays", rediscoverFootnote(firstPlayed, 23, ZoneOffset.UTC))
        assertEquals("First played 2025.11 · 1 play", rediscoverFootnote(firstPlayed, 1, ZoneOffset.UTC))
    }

    @Test
    fun should_useLocalMonth_when_firstPlayCrossesMidnight() {
        // 2025-11-30 23:30 UTC is already December in Tokyo.
        val firstPlayed = LocalDate.of(2025, 11, 30).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() +
            (23 * 60 + 30) * 60 * 1000L

        assertEquals("First played 2025.12 · 4 plays", rediscoverFootnote(firstPlayed, 4, ZoneId.of("Asia/Tokyo")))
    }

    @Test
    fun should_dropMissingHalves_when_historyIsPartial() {
        assertEquals("5 plays", rediscoverFootnote(null, 5))
        assertNull(rediscoverFootnote(null, 0))
    }

    private fun item(score: Float, lastPlayedAt: Long) = HomeRediscoverItem(
        albumId = MediaId.subsonic("a1"),
        albumName = "Emotion",
        artistName = "Carly Rae Jepsen",
        coverArtUrl = null,
        score = score,
        scoreText = rediscoverScoreText(score),
        scoreKind = MemoryScoreKind.ALBUM_RATING,
        lastPlayedAt = lastPlayedAt,
        firstPlayedAt = null,
        playCount = 0,
    )
}
