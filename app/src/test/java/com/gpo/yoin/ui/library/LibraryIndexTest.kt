package com.gpo.yoin.ui.library

import android.app.Application
import android.os.Build
import com.gpo.yoin.ui.component.FastScrollMath
import com.gpo.yoin.ui.component.FastScrollSection
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/**
 * [LibraryIndex] against the real android.icu of API 26 (no public
 * Transliterator: Han follows the app language) and API 36 (pinyin keys).
 * Robolectric's ICU data is the platform's of that release, so these are
 * regression goldens, not proof of every device. Legacy graphics/SQLite and
 * a plain Application keep the API 26 sandbox off Robolectric's native
 * runtime, which can't start a second SDK in the same JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class LibraryIndexTest {

    private val mixed = listOf(
        "ABBA", "Adele", "Zedd", "Björk", "Ólafur Arnalds",
        "周杰伦", "陈奕迅",
        "あいみょん", "ヨルシカ",
        "아이유", "방탄소년단",
        "2NE1", "!!!", ""
    )

    private val utc = TimeZone.getTimeZone("UTC")

    // ── Alphabetical ────────────────────────────────────────────────────

    @Test
    fun should_bucketEveryScriptWithHashLast_when_mixingLatinKanaHangulAndSymbols() {
        val result = LibraryIndex.alphabetical(mixed, name = { it }, locale = Locale.ENGLISH)

        assertSectionsTile(result)
        assertEquals("A", result.sectionOf("ABBA"))
        assertEquals("A", result.sectionOf("Adele"))
        assertEquals("B", result.sectionOf("Björk"))
        assertEquals("O", result.sectionOf("Ólafur Arnalds"))
        assertEquals("Z", result.sectionOf("Zedd"))
        assertEquals("あ", result.sectionOf("あいみょん"))
        assertEquals("や", result.sectionOf("ヨルシカ"))
        assertEquals("ㅇ", result.sectionOf("아이유"))
        assertEquals("ㅂ", result.sectionOf("방탄소년단"))
        for (symbolic in listOf("2NE1", "!!!", "")) {
            assertEquals(LibraryIndex.NUMBER_LABEL, result.sectionOf(symbolic))
        }
        assertEquals(LibraryIndex.NUMBER_LABEL, result.sections.last().label)
        // Latin first, then the other scripts.
        val labels = result.sections.map { it.label }
        assertTrue(labels.indexOf("Z") < labels.indexOf("ㅂ"))
        assertTrue(labels.indexOf("Z") < labels.indexOf("あ"))
    }

    @Test
    fun should_fileHanByPinyinOrAtTheEnd_when_appLanguageIsEnglish() {
        val result = LibraryIndex.alphabetical(mixed, name = { it }, locale = Locale.ENGLISH)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertEquals("Z", result.sectionOf("周杰伦"))
            assertEquals("C", result.sectionOf("陈奕迅"))
            // Pinyin keys sort among the Latin names: zedd < zhōu.
            assertTrue(result.items.indexOf("Zedd") < result.items.indexOf("周杰伦"))
        } else {
            // No transliterator: Han is one trailing section, just before "#".
            val han = result.sectionOf("周杰伦")
            assertEquals(han, result.sectionOf("陈奕迅"))
            val labels = result.sections.map { it.label }
            assertEquals(labels.lastIndex - 1, labels.lastIndexOf(han))
            assertTrue(labels.indexOf("あ") < labels.lastIndexOf(han))
            assertTrue(labels.indexOf("ㅂ") < labels.lastIndexOf(han))
        }
    }

    @Test
    fun should_fileHanByPinyin_when_appLanguageIsChinese() {
        val names = listOf("周杰伦", "陈奕迅", "阿杜", "ABBA", "Zedd", "あいみょん", "2NE1")
        val result = LibraryIndex.alphabetical(names, name = { it }, locale = Locale.SIMPLIFIED_CHINESE)

        assertSectionsTile(result)
        assertEquals("Z", result.sectionOf("周杰伦"))
        assertEquals("C", result.sectionOf("陈奕迅"))
        assertEquals("A", result.sectionOf("阿杜"))
        assertEquals("A", result.sectionOf("ABBA"))
        assertEquals("あ", result.sectionOf("あいみょん"))
        assertEquals(LibraryIndex.NUMBER_LABEL, result.sections.last().label)
        // One "A" section, not a Latin one and a Han one.
        assertEquals(1, result.sections.count { it.label == "A" })
    }

    @Test
    fun should_sortWithinASectionByCollator_when_namesShareABucket() {
        val result = LibraryIndex.alphabetical(listOf("adele", "Ádám", "ABBA"), name = { it }, locale = Locale.ENGLISH)

        assertEquals(listOf("ABBA", "Ádám", "adele"), result.items)
        assertEquals(listOf(FastScrollSection("A", 0)), result.sections)
    }

    @Test
    fun should_fileUnderTheNextWord_when_aLeadingArticleIsIgnored() {
        val articles = LibraryIndex.parseIgnoredArticles("The El La Los Las Le Les Os As O A")
        val names = listOf("The Beatles", "A Tribe Called Quest", "Theatre of Tragedy", "The", "the xx", "Adele")
        val result = LibraryIndex.alphabetical(
            names,
            name = { it },
            ignoredArticles = articles,
            locale = Locale.ENGLISH
        )

        assertEquals("B", result.sectionOf("The Beatles"))
        assertEquals("T", result.sectionOf("A Tribe Called Quest"))
        assertEquals("T", result.sectionOf("Theatre of Tragedy"))
        assertEquals("T", result.sectionOf("The"))
        assertEquals("X", result.sectionOf("the xx"))
        assertEquals("A", result.sectionOf("Adele"))
        // Without the articles, "The Beatles" stays under T.
        val plain = LibraryIndex.alphabetical(names, name = { it }, locale = Locale.ENGLISH)
        assertEquals("T", plain.sectionOf("The Beatles"))
    }

    @Test
    fun should_preferTheServerSortName_when_oneIsGiven() {
        data class Artist(val name: String, val sortName: String?)
        val artists = listOf(Artist("The Beatles", "Beatles, The"), Artist("Adele", " "), Artist("Zedd", null))
        val result = LibraryIndex.alphabetical(
            artists,
            name = { it.name },
            sortName = { it.sortName },
            locale = Locale.ENGLISH
        )

        assertEquals(listOf("Adele", "The Beatles", "Zedd"), result.items.map { it.name })
        assertEquals(listOf("A", "B", "Z"), result.sections.map { it.label })
    }

    @Test
    fun should_returnNothing_when_thereAreNoItems() {
        val result = LibraryIndex.alphabetical(emptyList<String>(), name = { it })

        assertTrue(result.items.isEmpty())
        assertTrue(result.sections.isEmpty())
        assertTrue(LibraryIndex.parseIgnoredArticles(null).isEmpty())
        assertTrue(LibraryIndex.parseIgnoredArticles("  ").isEmpty())
    }

    // ── Timeline ────────────────────────────────────────────────────────

    @Test
    fun should_cutByMonthUnderYearTicks_when_datesSpanSeveralYears() {
        val dates = listOf(at(2024, 3), at(2024, 3), at(2024, 1), at(2023, 7), at(2022, 2), at(2021, 5), at(2020, 5))
        val sections = LibraryIndex.timeline(dates, Locale.US, utc)

        assertEquals(
            listOf(
                FastScrollSection("Mar 2024", 0, "2024"),
                FastScrollSection("Jan 2024", 2, "2024"),
                FastScrollSection("Jul 2023", 3, "2023"),
                FastScrollSection("Feb 2022", 4, "2022"),
                FastScrollSection("May 2021", 5, "2021"),
                FastScrollSection("May 2020", 6, "2020")
            ),
            sections
        )
    }

    @Test
    fun should_localizeTheBubbleMonth_when_appLanguageIsChinese() {
        val dates = listOf(at(2024, 3), at(2023, 7), at(2022, 2), at(2020, 5))
        val sections = LibraryIndex.timeline(dates, Locale.SIMPLIFIED_CHINESE, utc)

        assertEquals("2024年3月", sections.first().label)
        assertEquals("2024", sections.first().tickLabel)
    }

    @Test
    fun should_tickByMonthWithTheYearWhereOneStarts_when_datesSpanUnderTwoYears() {
        val dates = listOf(at(2024, 6), at(2024, 2), at(2023, 11), at(2023, 8), at(2023, 3), at(2023, 1))
        val sections = LibraryIndex.timeline(dates, Locale.US, utc)

        assertEquals(listOf("2024", "Feb", "2023", "Aug", "Mar", "Jan"), sections.map { it.tickLabel })
        assertEquals("Jun 2024", sections.first().label)
        assertEquals((0..5).toList(), sections.map { it.startIndex })
        val zh = LibraryIndex.timeline(dates, Locale.SIMPLIFIED_CHINESE, utc)
        assertEquals(listOf("2024", "2月", "2023", "8月", "3月", "1月"), zh.map { it.tickLabel })
    }

    @Test
    fun should_keepTwoTicks_when_theSameMonthOfTwoYearsMeet() {
        // Nothing added between March 2023 and March 2024.
        val dates = listOf(at(2024, 3), at(2024, 3), at(2023, 3), at(2023, 3), at(2023, 2), at(2023, 1))
        val sections = LibraryIndex.timeline(dates, Locale.US, utc)

        assertEquals(listOf("Mar 2024", "Mar 2023", "Feb 2023", "Jan 2023"), sections.map { it.label })
        assertEquals(listOf("2024", "2023", "Feb", "Jan"), sections.map { it.tickLabel })
        assertEquals(sections.size, FastScrollMath.ticks(sections).size)
    }

    @Test
    fun should_giveNoSections_when_oneYearHoldsMostOfTheList() {
        // 5 of 7 dated items in 2019 (71%).
        val dates = listOf(at(2024, 1), at(2019, 12), at(2019, 9), at(2019, 6), at(2019, 3), at(2019, 1), at(2018, 5))

        assertTrue(LibraryIndex.timeline(dates, Locale.US, utc).isEmpty())
    }

    @Test
    fun should_giveNoSections_when_oneMonthHoldsMostOfAShortSpan() {
        // A first scan: everything "added" the same month, plus a few since.
        val dates = listOf(at(2024, 5), at(2024, 2), at(2023, 9), at(2023, 9), at(2023, 9), at(2023, 9), at(2023, 9))

        assertTrue(LibraryIndex.timeline(dates, Locale.US, utc).isEmpty())
    }

    @Test
    fun should_giveNoSections_when_moreThanAFifthIsUndated() {
        val spread = listOf(at(2024, 1), at(2023, 1), at(2022, 1), at(2021, 1), at(2020, 1), at(2019, 1), at(2018, 1))
        val tooSparse = spread + listOf(null, null, null) // 30% undated
        val justEnough = spread.take(6) + at(2017, 1) + listOf(null, null) + at(2016, 1) // 20% undated

        assertTrue(LibraryIndex.timeline(tooSparse, Locale.US, utc).isEmpty())
        assertTrue(LibraryIndex.timeline(justEnough, Locale.US, utc).isNotEmpty())
        assertTrue(LibraryIndex.timeline(listOf(null, null), Locale.US, utc).isEmpty())
        assertTrue(LibraryIndex.timeline(emptyList(), Locale.US, utc).isEmpty())
    }

    @Test
    fun should_letUndatedItemsJoinTheirNeighbours_when_fewDatesAreMissing() {
        val dates =
            listOf(
                null,
                at(
                    2024,
                    3
                ),
                null, at(2024, 3), at(2022, 1), at(2020, 6), at(2018, 6), at(2016, 6), at(2014, 6), at(2012, 6)
            )
        val sections = LibraryIndex.timeline(dates, Locale.US, utc)

        assertEquals(FastScrollSection("Mar 2024", 0, "2024"), sections.first())
        assertEquals(4, sections[1].startIndex)
    }

    @Test
    fun should_followTheListOrder_when_datesAreNotMonotonic() {
        // Navidrome can sort "newest" by file mtime while created says otherwise.
        val dates = listOf(at(2024, 3), at(2024, 3), at(2021, 1), at(2021, 1), at(2024, 3), at(2018, 5), at(2018, 5))
        val sections = LibraryIndex.timeline(dates, Locale.US, utc)

        assertEquals(listOf("Mar 2024", "Jan 2021", "Mar 2024", "May 2018"), sections.map { it.label })
        assertEquals(listOf(0, 2, 4, 5), sections.map { it.startIndex })
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun at(year: Int, month: Int): Long =
        ZonedDateTime.of(year, month, 15, 12, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun <T> LibraryIndex.Sorted<T>.sectionOf(item: T): String {
        val position = items.indexOf(item)
        assertTrue("$item missing", position >= 0)
        return sections.last { it.startIndex <= position }.label
    }

    private fun assertSectionsTile(result: LibraryIndex.Sorted<*>) {
        assertEquals(0, result.sections.first().startIndex)
        result.sections.zipWithNext().forEach { (a, b) -> assertTrue("$a !< $b", a.startIndex < b.startIndex) }
        assertTrue(result.sections.last().startIndex < result.items.size)
        // Each label names one contiguous run.
        assertEquals(result.sections.size, result.sections.map { it.label }.distinct().size)
    }
}
