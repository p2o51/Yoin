package com.gpo.yoin.ui.library

import android.icu.text.AlphabeticIndex
import android.icu.text.CollationKey
import android.icu.text.DateFormat
import android.icu.text.Transliterator
import android.os.Build
import androidx.annotation.RequiresApi
import com.gpo.yoin.ui.component.FastScrollSection
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Library sort order and fast-scroller sections, as pure functions (no
 * Android state, safe off the main thread). Callers run them on
 * Dispatchers.Default and keep the result in UI state.
 *
 * Alphabetical: one ICU [AlphabeticIndex] buckets AND orders the names, so a
 * section is always one contiguous run. Latin letters, the Japanese kana rows
 * (あ か さ …) and the Hangul initials (ㄱ–ㅎ) each get their buckets; digits and
 * symbols go to a trailing "#". Han names: API 29+ transliterates them to
 * pinyin (`Han-Latin/Names`), so 周杰伦 files under Z and sorts as "zhōu jié
 * lún"; API 26–28 has no public transliterator, so an app language of
 * Chinese indexes with the zh (pinyin) collator, and any other language
 * leaves Han in ICU's trailing overflow bucket, after kana and Hangul.
 *
 * Japanese names written in kanji are Han too: API 29+ files them by their
 * Mandarin reading (坂本龍一 under B), API 26–28 with a non-Chinese app
 * language puts them in the trailing overflow section with the other Han.
 *
 * Timeline: the dates of a list in its own order (newest first, usually),
 * cut into runs of one month. The ticks show the year, or the month when the
 * whole list spans under two years. Unhelpful timelines give no sections at
 * all — the scroller then shows its handle only.
 */
object LibraryIndex {

    /** Items in index order, and the scroller sections over that order. */
    data class Sorted<T>(
        val items: List<T>,
        val sections: List<FastScrollSection>
    )

    /** The label of the trailing digits-and-symbols section. */
    const val NUMBER_LABEL = "#"

    // AOSP ContactLocaleUtils' cap: comfortably above Latin + kana + Hangul.
    private const val MAX_LABEL_COUNT = 300

    // Timeline degradation rules (U2 verdict): a span under two years cuts by
    // month; one segment holding most of the list, or too many undated items,
    // means the timeline says nothing.
    private const val TWO_YEARS_MS = 63_115_200_000L // 2 × 365.25 days
    private const val MAX_SEGMENT_SHARE = 0.6f
    private const val MAX_UNDATED_SHARE = 0.2f

    private val ExtraLabelLocales = arrayOf(Locale.ENGLISH, Locale.JAPANESE, Locale.KOREAN)

    /**
     * Sort [items] alphabetically and cut them into sections.
     *
     * @param sortName the server's sort key when it has one (OpenSubsonic
     *   `sortName`); blank falls back to [name].
     * @param ignoredArticles leading words to skip ("The", "A", …), matched
     *   case-insensitively and only when a word follows — Subsonic's
     *   `ignoredArticles` (see [parseIgnoredArticles]).
     * @param locale the app's language (not the system's): it picks the
     *   primary collator.
     */
    fun <T> alphabetical(
        items: List<T>,
        name: (T) -> String,
        sortName: (T) -> String? = { null },
        ignoredArticles: Collection<String> = emptyList(),
        locale: Locale = Locale.getDefault()
    ): Sorted<T> {
        if (items.isEmpty()) return Sorted(emptyList(), emptyList())
        val transliterate = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val primary = if (!transliterate && locale.language == Locale.CHINESE.language) {
            Locale.SIMPLIFIED_CHINESE
        } else {
            locale
        }
        val alphabet = AlphabeticIndex<Any>(primary)
            .addLabels(*ExtraLabelLocales)
            .setMaxLabelCount(MAX_LABEL_COUNT)
        val collator = alphabet.collator
        val index = alphabet.buildImmutableIndex()
        val groups = bucketGroups(index)

        val entries = items.mapIndexed { position, item ->
            val raw = sortName(item)?.takeIf { it.isNotBlank() } ?: name(item)
            var key = stripArticle(raw.trim(), ignoredArticles)
            if (transliterate) key = HanToPinyin(key)
            val group = groups[index.getBucketIndex(key)]
            Entry(item, position, group, collator.getCollationKey(key))
        }
        val sorted = entries.sortedWith(EntryOrder)

        val sections = ArrayList<FastScrollSection>()
        var current: Group? = null
        sorted.forEachIndexed { position, entry ->
            if (entry.group != current) {
                current = entry.group
                sections += FastScrollSection(label = entry.group.label, startIndex = position)
            }
        }
        return Sorted(sorted.map { it.item }, sections)
    }

    /** Subsonic's `ignoredArticles` ("The El La Los …") as a list. */
    fun parseIgnoredArticles(raw: String?): List<String> = raw?.split(Whitespace)?.filter { it.isNotBlank() }.orEmpty()

    /**
     * Sections for a list in date order. [datesMs] holds one entry per list
     * item, in list order; null = no date (it joins the run before it, or
     * the first run when it leads the list). Runs follow the list as it is,
     * so an order that isn't strictly by date simply makes more runs.
     *
     * The bubble label is the localized year and month ("Mar 2024",
     * "2024年3月"); the tick is the year, or the short month ("Mar", "3月")
     * when the dates span under two years. Then the year stands in for the
     * month wherever a new year starts ("2024", "Feb", "2023", "Nov" …): a
     * repeated month name always has its year above it, and two runs of the
     * same month a year apart never merge into one tick.
     *
     * No sections at all when more than 20% of the items are undated, or one
     * tick segment (a year, or a month when cut by month) holds more than
     * about 60% of the dated items.
     */
    fun timeline(
        datesMs: List<Long?>,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): List<FastScrollSection> {
        val dated = datesMs.filterNotNull()
        if (dated.isEmpty()) return emptyList()
        if (datesMs.size - dated.size > datesMs.size * MAX_UNDATED_SHARE) return emptyList()

        val zone = timeZone.toZoneId()
        val byMonth = dated.maxOf { it } - dated.minOf { it } < TWO_YEARS_MS
        val segmentCounts = HashMap<Int, Int>()
        for (ms in dated) {
            val month = monthOf(ms, zone)
            val segment = if (byMonth) month else month / 12
            segmentCounts[segment] = (segmentCounts[segment] ?: 0) + 1
        }
        if (segmentCounts.values.max() > dated.size * MAX_SEGMENT_SHARE) return emptyList()

        val bubbleFormat = skeletonFormat("yMMM", locale, timeZone)
        val monthFormat = skeletonFormat("MMM", locale, timeZone)
        val sections = ArrayList<FastScrollSection>()
        var currentMonth = Int.MIN_VALUE
        var currentYear = Int.MIN_VALUE
        datesMs.forEachIndexed { position, ms ->
            if (ms == null) return@forEachIndexed
            val month = monthOf(ms, zone)
            if (month == currentMonth) return@forEachIndexed
            currentMonth = month
            val year = month / 12
            val date = Date(ms)
            sections += FastScrollSection(
                label = bubbleFormat.format(date),
                // Leading undated items belong to the first run.
                startIndex = if (sections.isEmpty()) 0 else position,
                tickLabel = if (byMonth && year == currentYear) monthFormat.format(date) else year.toString()
            )
            currentYear = year
        }
        return sections
    }

    /**
     * Sections for Recents. [lastSeenMs] holds one entry per list item, in
     * list order: when it was last opened or played in Yoin, newest first,
     * then null for each item with no record. The recorded part is cut by
     * that time, in the app's calendar: today, this week, this month, then
     * month by month (the year standing in for the month wherever an earlier
     * year starts), or year by year once the records reach back two years or
     * more. The items with no record are one last section.
     *
     * The timeline's rule ([timeline]) judges the recorded part alone: no
     * sections when one of its segments holds more than about 60% of it. The
     * items with no record never count as undated, so a library mostly never
     * opened in Yoin still gets its ticks; with no record at all there are
     * none (the handle alone).
     *
     * The relative sections and the last carry [RecentsLabel] keys, named in
     * the app's language where they are drawn.
     */
    fun recents(
        lastSeenMs: List<Long?>,
        nowMs: Long,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): List<FastScrollSection> {
        // Nothing recorded (a new device): no calendar to read at all.
        if (lastSeenMs.all { it == null }) return emptyList()
        val bubbleFormat by lazy { skeletonFormat("yMMM", locale, timeZone) }
        val monthFormat by lazy { skeletonFormat("MMM", locale, timeZone) }
        return recentsSections(
            lastSeenMs = lastSeenMs,
            nowMs = nowMs,
            zone = timeZone.toZoneId(),
            firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek,
            monthLabel = { ms -> bubbleFormat.format(Date(ms)) },
            monthTick = { ms -> monthFormat.format(Date(ms)) }
        )
    }

    /**
     * [recents] with the calendar and the month names given: the bubble's
     * year and month ([monthLabel]) and the tick's month ([monthTick]).
     */
    internal fun recentsSections(
        lastSeenMs: List<Long?>,
        nowMs: Long,
        zone: ZoneId,
        firstDayOfWeek: DayOfWeek,
        monthLabel: (Long) -> String,
        monthTick: (Long) -> String
    ): List<FastScrollSection> {
        val recorded = lastSeenMs.filterNotNull()
        if (recorded.isEmpty()) return emptyList()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val startOfWeek = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val startOfMonth = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val byMonth = nowMs - recorded.minOf { it } < TWO_YEARS_MS

        // Today, this week and this month above every month; a month, or a year.
        fun segmentOf(ms: Long): Int = when {
            ms >= startOfToday -> RECENTS_TODAY
            ms >= startOfWeek -> RECENTS_THIS_WEEK
            ms >= startOfMonth -> RECENTS_THIS_MONTH
            byMonth -> monthOf(ms, zone)
            else -> monthOf(ms, zone) / 12
        }
        val segmentCounts = recorded.groupingBy(::segmentOf).eachCount()
        if (segmentCounts.values.max() > recorded.size * MAX_SEGMENT_SHARE) return emptyList()

        val sections = ArrayList<FastScrollSection>()
        var currentSegment: Int? = null
        var currentYear = today.year
        var unrecordedStarted = false
        lastSeenMs.forEachIndexed { position, ms ->
            if (ms == null) {
                if (!unrecordedStarted) {
                    unrecordedStarted = true
                    sections += FastScrollSection(RecentsLabel.NotOpened.key, startIndex = position)
                }
                return@forEachIndexed
            }
            val segment = segmentOf(ms)
            if (segment == currentSegment) return@forEachIndexed
            currentSegment = segment
            val start = if (sections.isEmpty()) 0 else position
            sections += when (segment) {
                RECENTS_TODAY -> FastScrollSection(RecentsLabel.Today.key, startIndex = start)
                RECENTS_THIS_WEEK -> FastScrollSection(RecentsLabel.ThisWeek.key, startIndex = start)
                RECENTS_THIS_MONTH -> FastScrollSection(RecentsLabel.ThisMonth.key, startIndex = start)
                else -> {
                    val year = Instant.ofEpochMilli(ms).atZone(zone).year
                    val tick = if (byMonth && year == currentYear) monthTick(ms) else year.toString()
                    currentYear = year
                    FastScrollSection(
                        label = if (byMonth) monthLabel(ms) else year.toString(),
                        startIndex = start,
                        tickLabel = tick
                    )
                }
            }
        }
        return sections
    }

    // Recents' relative segments: above any month or year number.
    private const val RECENTS_TODAY = Int.MAX_VALUE
    private const val RECENTS_THIS_WEEK = Int.MAX_VALUE - 1
    private const val RECENTS_THIS_MONTH = Int.MAX_VALUE - 2

    // ── Alphabetical internals ──────────────────────────────────────────

    /**
     * One display section: ICU buckets that share a label merge (the zh
     * collator can keep a Latin "A" bucket apart from the Han pinyin "A"
     * bucket), ordered by the first of them. Underflow (digits, symbols)
     * becomes "#" at the very end; inflow and overflow buckets keep their
     * own place and ICU's label.
     */
    private class Group(val label: String, val rank: Int)

    private class Entry<T>(
        val item: T,
        val position: Int,
        val group: Group,
        val key: CollationKey
    )

    private val EntryOrder = Comparator<Entry<*>> { a, b ->
        when {
            a.group.rank != b.group.rank -> a.group.rank.compareTo(b.group.rank)
            else -> a.key.compareTo(b.key).takeIf { it != 0 } ?: a.position.compareTo(b.position)
        }
    }

    private fun bucketGroups(index: AlphabeticIndex.ImmutableIndex<Any>): Array<Group> {
        val count = index.bucketCount
        val byLabel = HashMap<String, Group>()
        val underflow = Group(NUMBER_LABEL, rank = count)
        return Array(count) { bucketIndex ->
            val bucket = index.getBucket(bucketIndex)
            when (bucket.labelType) {
                AlphabeticIndex.Bucket.LabelType.UNDERFLOW -> underflow
                AlphabeticIndex.Bucket.LabelType.NORMAL ->
                    byLabel.getOrPut(bucket.label) { Group(bucket.label, rank = bucketIndex) }
                else -> Group(bucket.label, rank = bucketIndex)
            }
        }
    }

    private fun stripArticle(text: String, articles: Collection<String>): String {
        for (article in articles) {
            if (article.isEmpty() || text.length <= article.length + 1) continue
            if (!text.regionMatches(0, article, 0, article.length, ignoreCase = true)) continue
            if (!text[article.length].isWhitespace()) continue
            val rest = text.substring(article.length + 1).trimStart()
            if (rest.isNotEmpty()) return rest
        }
        return text
    }

    private val Whitespace = Regex("\\s+")

    // ── Timeline internals ──────────────────────────────────────────────

    /** Months since year 0, in [zone]. */
    private fun monthOf(ms: Long, zone: ZoneId): Int {
        val date = Instant.ofEpochMilli(ms).atZone(zone)
        return date.year * 12 + (date.monthValue - 1)
    }

    private fun skeletonFormat(skeleton: String, locale: Locale, timeZone: TimeZone): DateFormat =
        DateFormat.getInstanceForSkeleton(skeleton, locale).apply {
            this.timeZone = android.icu.util.TimeZone.getTimeZone(timeZone.id)
        }
}

/**
 * The Recents sections a [FastScrollSection] names by key ([LibraryIndex.recents]):
 * the scroller draws them in the app's language ([LibraryGridFastScroller]).
 */
enum class RecentsLabel {
    Today,
    ThisWeek,
    ThisMonth,

    /** Never opened or played in Yoin: the last section. */
    NotOpened;

    /** What a section carries until it is drawn: no name a list item could have. */
    val key: String get() = KEY_PREFIX + name

    companion object {
        private const val KEY_PREFIX = "\u0000recents."

        /** The label [key] stands for, or null for a label of its own. */
        fun of(label: String): RecentsLabel? =
            if (label.startsWith(KEY_PREFIX)) entries.firstOrNull { it.key == label } else null
    }
}

/**
 * Han → pinyin for sort keys (`Han-Latin/Names`: surname readings, tone
 * marks kept — the collator treats them as accents). Text without Han passes
 * straight through, and so does everything when ICU lacks the transform.
 */
private object HanToPinyin : (String) -> String {
    override fun invoke(value: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !containsHan(value)) return value
        return IcuHanToPinyin.apply(value)
    }

    private fun containsHan(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) return true
            i += Character.charCount(cp)
        }
        return false
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private object IcuHanToPinyin {

    private const val TRANSFORM_ID = "Han-Latin/Names"

    /** null = the system ICU has no such transform; keys then keep their Han. */
    private val transliterator: Transliterator? by lazy {
        try {
            Transliterator.getInstance(TRANSFORM_ID)
        } catch (e: RuntimeException) {
            null
        }
    }

    fun apply(value: String): String {
        val transform = transliterator ?: return value
        // ICU's Transliterator isn't guaranteed thread-safe; indexes build on Default.
        return synchronized(transform) { transform.transliterate(value) } ?: value
    }
}
