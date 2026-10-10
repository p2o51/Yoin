package com.gpo.yoin.ui.library

import com.gpo.yoin.ui.component.FastScrollSection
import java.time.Instant
import java.time.ZoneOffset

/**
 * [LibraryScrollIndex] in plain JVM terms, for tests off device where
 * android.icu is a stub: a name's Latin initial ("#" for anything else, at
 * the end), then case-insensitive order; a timeline of UTC years. Only the
 * shape of [LibraryIndex] — its ICU behaviour is LibraryIndexTest's.
 */
internal object JvmLibraryScrollIndex : LibraryScrollIndex {

    override fun <T> alphabetical(
        items: List<T>,
        name: (T) -> String,
        ignoredArticles: List<String>
    ): LibraryIndex.Sorted<T> {
        class Entry(val item: T, val position: Int, val key: String, val letter: String)
        val entries = items.mapIndexed { position, item ->
            val key = stripLeadingArticle(name(item).trim(), ignoredArticles)
            Entry(item, position, key, letterOf(key))
        }
        val sorted = entries.sortedWith(
            compareBy<Entry> { it.letter == LibraryIndex.NUMBER_LABEL }
                .thenBy { it.letter }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.key }
                .thenBy { it.position }
        )
        return LibraryIndex.Sorted(sorted.map { it.item }, runs(sorted.map { it.letter }))
    }

    override fun timeline(datesMs: List<Long?>): List<FastScrollSection> {
        val sections = ArrayList<FastScrollSection>()
        var current: Int? = null
        datesMs.forEachIndexed { position, ms ->
            if (ms == null) return@forEachIndexed
            val year = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).year
            if (year == current) return@forEachIndexed
            current = year
            sections += FastScrollSection(year.toString(), startIndex = if (sections.isEmpty()) 0 else position)
        }
        return sections
    }

    private fun letterOf(key: String): String =
        key.firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' }?.toString() ?: LibraryIndex.NUMBER_LABEL

    private fun runs(labels: List<String>): List<FastScrollSection> = labels.mapIndexedNotNull { i, label ->
        FastScrollSection(label, startIndex = i).takeIf { i == 0 || labels[i - 1] != label }
    }
}
