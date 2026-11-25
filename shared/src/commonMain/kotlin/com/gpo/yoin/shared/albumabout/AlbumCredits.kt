package com.gpo.yoin.shared.albumabout

/*
 * The scrapbook's credits line (owner + Carbon 2026-11-25): under the album's title beside the cover, in
 * place of Last Play (which stays on page 1 and on the receipt). One compact line that wraps naturally:
 * items apart by " · ", names within an item by "、" (zh) / ", " (en). Items in a fixed order: label,
 * producer, mastering (or mix), recorded (time and studio); then, only when they apply, source (a
 * soundtrack's or tribute's work) and first release (a reissue's). An unknown item is skipped, never a
 * placeholder. The label comes from the service's own field first, with no AI; the rest from the AI's
 * credits. Pure: the platforms only supply [AlbumCreditLabels] (their own string resources) and the
 * service-dated first release in their own date format.
 *
 * Moved from Android's ui/detail/AlbumCredits.kt (2026-11-25); pinned by golden/album-about.json.
 */

enum class AlbumCreditKind { Label, Producer, Mastering, Mixing, Recorded, Source, FirstReleased }

/**
 * One item of the line. [names] for the people / companies / studios; [text] for the AI's free text (the
 * recording time, the source work, the first release); [date] for a first release the service dated (ISO at
 * the service's precision, "2014-05-20" / "2014-05" / "2014"; each platform writes it in its own date style).
 */
data class AlbumCreditItem(
    val kind: AlbumCreditKind,
    val names: List<String> = emptyList(),
    val text: String? = null,
    val date: String? = null,
)

/**
 * The words the line is built with, from each platform's string resources ("厂牌", "Label"…).
 * [nameSeparator] goes between names within one item ("、" in Chinese, ", " in English); [timeAndPlace] is
 * the recording time and place as a format with `%1$s` (time) and `%2$s` (place): "%1$s，%2$s" / "%1$s, %2$s".
 */
data class AlbumCreditLabels(
    val label: String,
    val producer: String,
    val mastering: String,
    val mixing: String,
    val recorded: String,
    val source: String,
    val firstReleased: String,
    val nameSeparator: String,
    val timeAndPlace: String,
) {
    fun labelOf(kind: AlbumCreditKind): String = when (kind) {
        AlbumCreditKind.Label -> label
        AlbumCreditKind.Producer -> producer
        AlbumCreditKind.Mastering -> mastering
        AlbumCreditKind.Mixing -> mixing
        AlbumCreditKind.Recorded -> recorded
        AlbumCreditKind.Source -> source
        AlbumCreditKind.FirstReleased -> firstReleased
    }

    internal fun formatTimeAndPlace(time: String, place: String): String =
        PlaceholderRegex.replace(timeAndPlace) { if (it.groupValues[1] == "1") time else place }

    private companion object {
        val PlaceholderRegex = Regex("%([12])\\\$s")
    }
}

/** One credit as the About sheet lists it (label left, value right). */
data class AlbumCreditRow(val kind: AlbumCreditKind, val label: String, val value: String)

/**
 * The credits line, ready to lay out.
 *
 * [text] is the line Android renders: items apart by [AlbumCredits.ITEM_SEPARATOR] (a no-break space before
 * the dot), the label glued to its value by a no-break space, word joiners (U+2060) wherever the line must not
 * break. Any UAX #14 line breaker (Android's ICU, Core Text, Flutter's SkParagraph) wraps it as Android does.
 *
 * [segments] are the same line as runs that never break inside: a line may break only between two segments
 * (a segment wider than the whole line still has to break somewhere, as Android's emergency break does). They
 * concatenate to [plain], the line as read (no word joiners, no-break spaces as plain spaces). A segment that
 * ends an item carries the " · " after it, so the dot never starts a line; Latin words keep their trailing
 * space. [rows] are the items one by one, for the sheet.
 */
data class AlbumCreditsLine(
    val text: String,
    val plain: String,
    val segments: List<String>,
    val rows: List<AlbumCreditRow>,
)

internal object AlbumCredits {

    /** Between items. The no-break space keeps the dot off the start of a line: a wrap falls after it. */
    const val ITEM_SEPARATOR = "\u00A0· "

    /** Between an item's label and its value: never split, so a label is never left alone at a line's end. */
    const val LABEL_GAP = '\u00A0'

    const val WORD_JOINER = '\u2060'

    /**
     * The line's items for one album. [sourceLabels] (the service's own label field) beat the AI's label;
     * [originalReleaseDate] (the service's first-release date) beats the AI's, and either only shows for a
     * reissue: the service's when its year is earlier than this release's [releaseDate], the AI's unless every
     * year it names is this release's year or later. Dates are ISO at the service's precision.
     */
    fun items(
        sourceLabels: List<String>,
        releaseDate: String?,
        originalReleaseDate: String?,
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
        val releaseYear = yearOf(releaseDate)
        val originalYear = yearOf(originalReleaseDate)
        val serviceFirst = originalReleaseDate?.takeIf {
            originalYear != null && releaseYear != null && originalYear < releaseYear
        }
        when {
            serviceFirst != null -> add(AlbumCreditItem(AlbumCreditKind.FirstReleased, date = serviceFirst))
            // The service dates the first release no earlier than this one: not a reissue, whatever the AI says.
            originalYear != null -> Unit
            ai.firstReleased != null && isEarlierThan(ai.firstReleased, releaseYear) ->
                add(AlbumCreditItem(AlbumCreditKind.FirstReleased, text = ai.firstReleased))
        }
    }

    /** The year of an ISO date ("2014-05-20" → 2014); null when it doesn't start with one. */
    internal fun yearOf(iso: String?): Int? =
        iso?.trim()?.let { IsoYear.find(it) }?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    /** True unless [text] names a year and every year it names is [releaseYear] or later. */
    private fun isEarlierThan(text: String, releaseYear: Int?): Boolean {
        val release = releaseYear ?: return true
        val years = YearRegex.findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()
        return years.isEmpty() || years.min() < release
    }

    private val IsoYear = Regex("^(\\d{1,4})(?:-|$)")
    private val YearRegex = Regex("(?<!\\d)(1[89]\\d\\d|20\\d\\d)(?!\\d)")

    /**
     * One item's value as shown (no label); null when it holds nothing to show. [dateText] is the
     * service-dated first release as the platform writes it; without it the ISO date shows as it is.
     */
    fun value(item: AlbumCreditItem, labels: AlbumCreditLabels, dateText: String?): String? {
        val names = item.names.joinToString(labels.nameSeparator).takeIf(String::isNotEmpty)
        return when (item.kind) {
            AlbumCreditKind.Recorded -> when {
                item.text != null && names != null -> labels.formatTimeAndPlace(item.text, names)
                else -> item.text ?: names
            }
            AlbumCreditKind.Source -> item.text
            AlbumCreditKind.FirstReleased -> item.date?.let { dateText ?: it } ?: item.text
            else -> names
        }
    }

    /** The line; null when no item is known (the line is left out, never a placeholder). */
    fun line(items: List<AlbumCreditItem>, labels: AlbumCreditLabels, dateText: String?): AlbumCreditsLine? {
        val rows = items.mapNotNull { item ->
            value(item, labels, dateText)?.let { AlbumCreditRow(item.kind, labels.labelOf(item.kind), it) }
        }
        if (rows.isEmpty()) return null
        val text = rows.joinToString(ITEM_SEPARATOR) { itemText(it.label, it.value) }
        return AlbumCreditsLine(text = text, plain = plain(text), segments = segments(text), rows = rows)
    }

    /**
     * One item as the line shows it, with line breaks where they read well (device check 2026-11-25: 原作 电影 /
     * 《夜航》 split mid-name on the tablet, and keeping whole items together forced mid-word breaks such as
     * "Kamaku / ra" in a phone's narrow column). The label never leaves its value and never splits; inside the
     * value, Chinese / Japanese runs hold together except after a comma (，、；), dashes hold their neighbours
     * ("Sep–Dec"), and Latin text breaks between words as usual.
     */
    fun itemText(label: String, value: String): String =
        joinCjk(label.replace(' ', '\u00A0'), breakAfterComma = false) + LABEL_GAP +
            joinCjk(value, breakAfterComma = true)

    /** [label] glued together: no-break spaces, word joiners between CJK characters. */
    fun unbreakable(label: String): String = joinCjk(label.replace(' ', '\u00A0'), breakAfterComma = false)

    /** The line as read: word joiners gone, no-break spaces as spaces. */
    fun plain(text: String): String = text.replace(WORD_JOINER.toString(), "").replace('\u00A0', ' ')

    /**
     * [text] cut where it may break, as UAX #14 reads the joined text: after a space or a Chinese comma (，、；)
     * that no word joiner holds to the next character; never before a space, and never before closing
     * punctuation, "!", "?", ",", ".", ":", ";" or "/" (which stay with the word before them even after a
     * space). Returned as read ([plain]). Scripts the line doesn't join (Hangul, Thai…) are cut only at spaces
     * here, while a platform's own breaker may also break inside their words.
     */
    fun segments(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        text.forEachIndexed { i, c ->
            val previous = text.getOrNull(i - 1)
            val breaks = previous != null && (previous == ' ' || previous in CjkCommas) &&
                c != WORD_JOINER && c != ' ' && c != '\u00A0' && !(previous == ' ' && c in NoBreakAfterSpace)
            if (breaks && current.isNotEmpty()) {
                out += current.toString()
                current.clear()
            }
            when (c) {
                WORD_JOINER -> Unit
                '\u00A0' -> current.append(' ')
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    private fun joinCjk(text: String, breakAfterComma: Boolean): String = buildString {
        text.forEachIndexed { i, c ->
            val previous = text.getOrNull(i - 1)
            val joined = previous != null && (c.isCjk() || previous.isCjk() || previous in BreakAfter) &&
                !(breakAfterComma && previous in CjkCommas)
            if (joined) append(WORD_JOINER)
            append(c)
        }
    }

    private const val BreakAfter = "-‐–—/"
    private const val CjkCommas = "，、；"

    /** UAX #14 classes CL, CP, EX, IS and SY in Latin text: no break before them, even after spaces (LB13). */
    private const val NoBreakAfterSpace = ")]}!?,.:;/"

    private fun Char.isCjk(): Boolean = code in 0x2E80..0x9FFF || code in 0xF900..0xFAFF || code in 0xFF00..0xFFEF
}
