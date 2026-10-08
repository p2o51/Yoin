package com.gpo.yoin.ui.memories

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import com.gpo.yoin.ui.component.MetaGroup
import com.gpo.yoin.ui.component.MetaLine
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

/** A trailing " · 2019" is a year. Any other tail, including " · by", stays with the artist. */
private val SupportingYearTail = Regex(""" · (\d{4})$""")

internal fun String.supportingArtistAndYear(): Pair<String, String?> {
    val match = SupportingYearTail.find(this) ?: return this to null
    return substring(0, match.range.first) to match.groupValues[1]
}

/** Artist and year for one MetaLine. [albumLabel] replaces the blank-album sentinel. */
internal data class MemorySupportParts(val artist: String, val year: String?)

internal fun MemoryEntry.supportParts(albumLabel: String): MemorySupportParts {
    val explicitYear = supportingYear?.takeIf { it.isNotBlank() }
    val (rawArtist, parsedYear) = if (explicitYear == null) {
        supportingText.supportingArtistAndYear()
    } else {
        supportingText to null
    }
    val artist = if (rawArtist == MemoryAlbumFallback) albumLabel else rawArtist
    return MemorySupportParts(artist = artist, year = explicitYear ?: parsedYear)
}

/** Artist at full emphasis, year at 60%. A group that does not fit is dropped from the end. */
@Composable
internal fun MemoryArtistYearLine(
    parts: MemorySupportParts,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val groups = buildList {
        if (parts.artist.isNotBlank()) add(MetaGroup.Plain(parts.artist))
        val year = parts.year
        if (!year.isNullOrBlank()) add(MetaGroup.Plain(year, muted = true))
    }
    if (groups.isEmpty()) return
    MetaLine(groups = groups, modifier = modifier, style = style)
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
