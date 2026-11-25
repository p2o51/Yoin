package com.gpo.yoin.ui.detail

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import androidx.core.os.ConfigurationCompat
import com.gpo.yoin.R
import com.gpo.yoin.data.album.AlbumAiCredits
import com.gpo.yoin.data.model.ReleaseDate
import java.util.Locale

/*
 * The scrapbook's credits line (owner + Carbon 2026-11-25): under the album's title beside the cover, in
 * place of Last Play (which stays on page 1 and on the receipt). One compact line that wraps naturally:
 * items apart by " · ", names within an item by "、" (zh) / ", " (en). Items in a fixed order: label,
 * producer, mastering (or mix), recorded (time and studio); then, only when they apply, source (a
 * soundtrack's or tribute's work) and first release (a reissue's). An unknown item is skipped, never a
 * placeholder. The label comes from the service's own field first, with no AI; the rest from the AI's
 * credits. Pure, so the rules are unit-tested; the composable only supplies [AlbumCreditLabels].
 */

enum class AlbumCreditKind { Label, Producer, Mastering, Mixing, Recorded, Source, FirstReleased }

/**
 * One item of the line. [names] for the people / companies / studios; [text] for the AI's free text (the
 * recording time, the source work, the first release); [date] for a first release the service dated.
 */
@Immutable
data class AlbumCreditItem(
    val kind: AlbumCreditKind,
    val names: List<String> = emptyList(),
    val text: String? = null,
    val date: ReleaseDate? = null,
)

/**
 * The line's items for one album. [sourceLabels] (the service's own label field) beat the AI's label;
 * [originalReleaseDate] (the service's first-release date) beats the AI's, and either only shows for a
 * reissue: the service's when it is earlier than this release's [releaseDate], the AI's unless every year
 * it names is this release's year or later.
 */
internal fun albumCreditItems(
    sourceLabels: List<String>,
    releaseDate: ReleaseDate?,
    originalReleaseDate: ReleaseDate?,
    ai: AlbumAiCredits,
): List<AlbumCreditItem> = buildList {
    val labels = sourceLabels.map(String::trim).filter(String::isNotEmpty).distinct().ifEmpty { ai.label }
    if (labels.isNotEmpty()) add(AlbumCreditItem(AlbumCreditKind.Label, names = labels))
    if (ai.producers.isNotEmpty()) add(AlbumCreditItem(AlbumCreditKind.Producer, names = ai.producers))
    when {
        ai.mastering.isNotEmpty() -> add(AlbumCreditItem(AlbumCreditKind.Mastering, names = ai.mastering))
        ai.mixing.isNotEmpty() -> add(AlbumCreditItem(AlbumCreditKind.Mixing, names = ai.mixing))
    }
    if (ai.recordedTime != null || ai.recordedStudios.isNotEmpty()) {
        add(AlbumCreditItem(AlbumCreditKind.Recorded, names = ai.recordedStudios, text = ai.recordedTime))
    }
    ai.source?.let { add(AlbumCreditItem(AlbumCreditKind.Source, text = it)) }
    val serviceFirst = originalReleaseDate?.takeIf { original -> releaseDate != null && original.year < releaseDate.year }
    when {
        serviceFirst != null -> add(AlbumCreditItem(AlbumCreditKind.FirstReleased, date = serviceFirst))
        // The service dates the first release no earlier than this one: not a reissue, whatever the AI says.
        originalReleaseDate != null -> Unit
        ai.firstReleased != null && isEarlierThan(ai.firstReleased, releaseDate) ->
            add(AlbumCreditItem(AlbumCreditKind.FirstReleased, text = ai.firstReleased))
    }
}

/** True unless [text] names a year and every year it names is [releaseDate]'s year or later. */
private fun isEarlierThan(text: String, releaseDate: ReleaseDate?): Boolean {
    val release = releaseDate?.year ?: return true
    val years = YearRegex.findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()
    return years.isEmpty() || years.min() < release
}

private val YearRegex = Regex("(?<!\\d)(1[89]\\d\\d|20\\d\\d)(?!\\d)")

/** The words the line is built with, from resources in the app, plain in tests. */
interface AlbumCreditLabels {
    fun label(kind: AlbumCreditKind): String

    /** Between names within one item: "、" in Chinese, ", " in English. */
    val nameSeparator: String

    /** "1975年1月，Rainbow Studio" / "Jan 1975, Rainbow Studio". */
    fun timeAndPlace(time: String, place: String): String

    /** A first release the service dated, in the app language. */
    fun date(date: ReleaseDate): String
}

/** Between items. The no-break space keeps the dot off the start of a line: a wrap falls after it. */
internal const val CreditItemSeparator = " · "

/** Between an item's label and its value: never split, so a label is never left alone at a line's end. */
internal const val CreditLabelGap = ' '

/** [label] with word joiners between its CJK characters: 录制 never splits across two lines (Latin can't). */
internal fun unbreakable(label: String): String = buildString {
    label.forEachIndexed { i, c ->
        if (i > 0 && (c.isCjk() || label[i - 1].isCjk())) append(CreditWordJoiner)
        append(c)
    }
}

private fun Char.isCjk(): Boolean = code in 0x3000..0x9FFF || code in 0xFF00..0xFFEF

private const val CreditWordJoiner = '\u2060'

/** One item's value as shown (no label); null when it holds nothing to show. */
internal fun albumCreditValue(item: AlbumCreditItem, labels: AlbumCreditLabels): String? {
    val names = item.names.joinToString(labels.nameSeparator).takeIf(String::isNotEmpty)
    return when (item.kind) {
        AlbumCreditKind.Recorded -> when {
            item.text != null && names != null -> labels.timeAndPlace(item.text, names)
            else -> item.text ?: names
        }
        AlbumCreditKind.Source -> item.text
        AlbumCreditKind.FirstReleased -> item.date?.let(labels::date) ?: item.text
        else -> names
    }
}

/** The line as shown; null when no item is known (the line is left out, never a placeholder). */
internal fun albumCreditsLine(items: List<AlbumCreditItem>, labels: AlbumCreditLabels): String? =
    items.mapNotNull { item ->
        albumCreditValue(item, labels)?.let { value -> "${unbreakable(labels.label(item.kind))}$CreditLabelGap$value" }
    }.takeIf { it.isNotEmpty() }?.joinToString(CreditItemSeparator)

internal fun resourceAlbumCreditLabels(resources: Resources): AlbumCreditLabels {
    val locale = ConfigurationCompat.getLocales(resources.configuration)[0] ?: Locale.getDefault()
    return object : AlbumCreditLabels {
        override fun label(kind: AlbumCreditKind): String = resources.getString(
            when (kind) {
                AlbumCreditKind.Label -> R.string.detail_album_credit_label
                AlbumCreditKind.Producer -> R.string.detail_album_credit_producer
                AlbumCreditKind.Mastering -> R.string.detail_album_credit_mastering
                AlbumCreditKind.Mixing -> R.string.detail_album_credit_mixing
                AlbumCreditKind.Recorded -> R.string.detail_album_credit_recorded
                AlbumCreditKind.Source -> R.string.detail_album_credit_source
                AlbumCreditKind.FirstReleased -> R.string.detail_album_credit_first_released
            },
        )

        override val nameSeparator: String = resources.getString(R.string.detail_album_credit_name_separator)

        override fun timeAndPlace(time: String, place: String): String =
            resources.getString(R.string.detail_album_credit_time_place, time, place)

        override fun date(date: ReleaseDate): String = albumReleaseDateText(date, locale)
    }
}
