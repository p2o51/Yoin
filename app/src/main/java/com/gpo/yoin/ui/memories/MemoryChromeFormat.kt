package com.gpo.yoin.ui.memories

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.gpo.yoin.R
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The blank album row ("Album") when an album has no artist and no year.
 * The deck writes this sentinel; the card paints `mem_album_fallback`, the same English.
 */
internal const val MemoryAlbumFallback = "Album"

/** The painted artist line: the blank "Album" sentinel follows the app language; artist and year stay as written. */
@Composable
@ReadOnlyComposable
internal fun String.memoryArtistLine(): String {
    val album = stringResource(R.string.mem_album_fallback)
    return if (this == MemoryAlbumFallback) album else this
}

/** App-language date for Memories chrome. This year drops the year, matching the old "Oct 3" / "Nov 8, 2025" split. */
internal fun formatMemoryChromeDate(
    date: LocalDate,
    today: LocalDate,
    locale: Locale,
    zone: ZoneId,
    withYear: Boolean = date.year != today.year,
): String {
    val pattern = DateFormat.getBestDateTimePattern(locale, if (withYear) "yMMMd" else "MMMd")
    val calendar = Calendar.getInstance(TimeZone.getTimeZone(zone.id), locale)
    calendar.set(date.year, date.monthValue - 1, date.dayOfMonth, 12, 0, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return DateFormat.format(pattern, calendar).toString()
}
