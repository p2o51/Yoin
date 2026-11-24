package com.gpo.yoin.ui.detail

import android.icu.text.DateFormat
import android.icu.util.Calendar
import android.icu.util.TimeZone
import com.gpo.yoin.data.model.ReleaseDate
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The album header's date, in place of the year: "22.6.12" (two-digit year,
 * month and day without leading zeros), "22.6" for a year and month, the full
 * year "2022" for a year alone. Numeric, so the same in every language. Null
 * when neither [date] nor [year] is known.
 */
internal fun albumHeaderDate(date: ReleaseDate?, year: Int?): String? {
    if (date == null) return year?.takeIf { it > 0 }?.toString()
    val shortYear = (date.year % 100).toString().padStart(2, '0')
    return when {
        date.month == null -> date.year.toString()
        date.day == null -> "$shortYear.${date.month}"
        else -> "$shortYear.${date.month}.${date.day}"
    }
}

/**
 * The date under the album's last track, in [locale]'s own words: "October 27,
 * 2023" / "2023年10月27日" with a day, "October 2023" / "2023年10月" with a month,
 * just the year with a year alone.
 */
internal fun albumReleaseDateText(date: ReleaseDate, locale: Locale): String {
    val skeleton = when {
        date.month == null -> return date.year.toString()
        date.day == null -> "yMMMM"
        else -> "yMMMMd"
    }
    // Noon UTC on the day (the 1st for a month), formatted in UTC: no zone can move it to another day.
    val utc = TimeZone.getTimeZone("UTC")
    val calendar = Calendar.getInstance(utc, locale).apply {
        clear()
        set(date.year, date.month - 1, date.day ?: 1, 12, 0, 0)
    }
    return DateFormat.getInstanceForSkeleton(skeleton, locale).apply { timeZone = utc }.format(calendar.time)
}

// The scrapbook receipt is English by design (its other lines are too); the
// labels are provisional, owned by copy, and kept here to be found and renamed.
internal const val ReceiptReleasedLabel = "Released" // i18n-allow: AlbumScrapbookTest asserts this receipt English
internal const val ReceiptLengthLabel = "Length" // i18n-allow: AlbumScrapbookTest asserts this receipt English

private val ReceiptDay = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
private val ReceiptMonth = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

/** The receipt's Released value: "Jun 12, 2022", "Jun 2022", or "2022", in the receipt's English date style. */
internal fun receiptReleaseDate(date: ReleaseDate): String = when {
    date.month == null -> date.year.toString()
    date.day == null -> LocalDate.of(date.year, date.month, 1).format(ReceiptMonth)
    else -> LocalDate.of(date.year, date.month, date.day).format(ReceiptDay)
}

/**
 * The receipt's Length value: "47 min", "1 hr 12 min", "2 hr" (whole minutes, as
 * the track-count line counts them; under a minute reads "1 min"). Null for no length.
 */
internal fun receiptAlbumLength(seconds: Int?): String? {
    val total = seconds?.takeIf { it > 0 } ?: return null
    val minutes = (total / 60).coerceAtLeast(1)
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "$minutes min" // i18n-allow: AlbumScrapbookTest asserts this receipt English
        rest == 0 -> "$hours hr" // i18n-allow: AlbumScrapbookTest asserts this receipt English
        else -> "$hours hr $rest min" // i18n-allow: AlbumScrapbookTest asserts this receipt English
    }
}
