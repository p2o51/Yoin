package com.gpo.yoin.shared.albumabout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The credits line's rules, moved with them from Android (app AlbumCreditsTest); JVM and iOS simulator. */
class AlbumCreditsTest {

    private val full = AlbumAiCredits(
        label = listOf("AI Label"),
        producers = listOf("Manfred Eicher"),
        mastering = listOf("Jan Erik Kongshaug"),
        mixing = listOf("Mixer"),
        recordedTime = "1975年1月",
        recordedStudios = listOf("Rainbow Studio, Oslo"),
        source = "《攻壳机动队》",
        firstReleased = "1975年",
    )

    private fun kinds(items: List<AlbumCreditItem>) = items.map { it.kind }

    private val zh = AlbumCreditLabels("厂牌", "制作", "母带", "混音", "录制", "原作", "首版", "、", "%1\$s，%2\$s")
    private val en = AlbumCreditLabels("Label", "Producer", "Mastering", "Mix", "Recorded", "Source", "First released", ", ", "%1\$s, %2\$s")

    private fun items(labels: List<String>, release: String?, original: String?, ai: AlbumAiCredits) =
        YoinAlbumAbout.creditItems(labels, release, original, ai)

    private fun line(items: List<AlbumCreditItem>, labels: AlbumCreditLabels, dateText: String? = null) =
        YoinAlbumAbout.creditsLine(items, labels, dateText)

    @Test
    fun should_takeTheServicesLabel_overTheAis() {
        val items = items(listOf(" ECM ", "ECM"), "1975", null, full)

        assertEquals(listOf("ECM"), items.first { it.kind == AlbumCreditKind.Label }.names)
    }

    @Test
    fun should_fallBackToTheAisLabel_when_theServiceNamesNone() {
        val items = items(emptyList(), null, null, full)

        assertEquals(listOf("AI Label"), items.first { it.kind == AlbumCreditKind.Label }.names)
    }

    @Test
    fun should_orderTheItems_andPreferMasteringOverMix() {
        val items = items(emptyList(), null, null, full)

        assertEquals(
            listOf(
                AlbumCreditKind.Label,
                AlbumCreditKind.Producer,
                AlbumCreditKind.Mastering,
                AlbumCreditKind.Recorded,
                AlbumCreditKind.Source,
                AlbumCreditKind.FirstReleased,
            ),
            kinds(items),
        )
    }

    @Test
    fun should_showTheMix_when_masteringIsUnknown() {
        assertEquals(listOf(AlbumCreditKind.Mixing), kinds(items(emptyList(), null, null, AlbumAiCredits(mixing = listOf("Mixer")))))
    }

    @Test
    fun should_skipEveryUnknownItem_andLeaveNoLine_when_nothingIsKnown() {
        val items = items(emptyList(), "2020", null, AlbumAiCredits.None)

        assertTrue(items.isEmpty())
        assertNull(line(items, zh))
    }

    @Test
    fun should_dateTheFirstReleaseFromTheService_when_itIsEarlier() {
        val items = items(emptyList(), "2024-03-08", "2014-05-20", full)

        val first = items.single { it.kind == AlbumCreditKind.FirstReleased }
        assertEquals("2014-05-20", first.date)
        assertNull(first.text)
        assertEquals("首版 2014年5月20日", line(listOf(first), zh, "2014年5月20日")?.plain)
        // Without the platform's date text the ISO date shows as it is.
        assertEquals("First released 2014-05-20", line(listOf(first), en)?.plain)
    }

    @Test
    fun should_notCallItAReissue_when_theServiceDatesTheFirstReleaseThisYear() {
        assertFalse(AlbumCreditKind.FirstReleased in kinds(items(emptyList(), "2014", "2014-05", full)))
    }

    @Test
    fun should_dropTheAisFirstRelease_when_itIsNotEarlierThanThisRelease() {
        val same = items(emptyList(), "2016", null, AlbumAiCredits(firstReleased = "August 2016"))
        val earlier = items(emptyList(), "2016", null, AlbumAiCredits(firstReleased = "1997年5月"))
        val undated = items(emptyList(), null, null, AlbumAiCredits(firstReleased = "1997"))

        assertTrue(same.isEmpty())
        assertEquals("1997年5月", earlier.single().text)
        assertEquals("1997", undated.single().text)
    }

    @Test
    fun should_joinNamesWithTheChineseComma_andItemsWithDots_inChinese() {
        val items = items(
            listOf("ECM"),
            "1975",
            null,
            AlbumAiCredits(producers = listOf("Manfred Eicher", "Steve Lake"), recordedTime = "1975年1月", recordedStudios = listOf("Rainbow Studio")),
        )

        assertEquals("厂牌 ECM · 制作 Manfred Eicher、Steve Lake · 录制 1975年1月，Rainbow Studio", line(items, zh)?.plain)
    }

    @Test
    fun should_joinNamesWithCommas_inEnglish() {
        val items = items(emptyList(), null, null, AlbumAiCredits(producers = listOf("A", "B"), source = "Akira"))

        assertEquals("Producer A, B · Source Akira", line(items, en)?.plain)
    }

    @Test
    fun should_breakBetweenItemsAndWords_neverInsideALabelOrAChineseName() {
        val items = items(
            listOf("林间工作室"),
            null,
            null,
            full.copy(recordedTime = "Sep–Dec 1975", recordedStudios = listOf("大溪地录音室，台北"), source = "电影《夜航》"),
        )
        val text = line(items, zh)!!.text
        val cjk = { c: Char -> c.code in 0x2E80..0x9FFF || c.code in 0xFF00..0xFFEF }

        // The dot never starts a line: a no-break space before it, a breakable space after.
        assertEquals(items.size - 1, Regex("\u00A0· ").findAll(text).count())
        text.split(YoinAlbumAbout.creditItemSeparator).forEach { item ->
            // The label is glued to its value.
            assertTrue(item.substringBefore('\u00A0').none { it == ' ' } && '\u00A0' in item, item)
            for (i in 1 until item.length) {
                val before = item[i - 1]
                val at = item[i]
                // Word joiners and no-break spaces are themselves no-break points.
                if (at == '\u2060' || before == '\u2060' || at == '\u00A0' || before == '\u00A0') continue
                // A Chinese run only breaks after its comma; a dash holds its neighbours.
                val breakable = (cjk(before) || cjk(at) || before == '–') && before !in "，、；"
                assertFalse(breakable, "break opportunity in \"$item\" at $i")
            }
        }
        // 原作 电影《夜航》 holds together; the recording place may break after its comma.
        assertTrue(text.contains("原\u2060作\u00A0电\u2060影\u2060《\u2060夜\u2060航\u2060》"))
        assertTrue(text.contains("大\u2060溪\u2060地\u2060录\u2060音\u2060室\u2060，台\u2060北"))
        assertTrue(text.contains("Sep–\u2060Dec 1975"))
        assertEquals("First\u00A0released", YoinAlbumAbout.unbreakable("First released"))
        assertEquals("录\u2060制", YoinAlbumAbout.unbreakable("录制"))
    }

    @Test
    fun should_letLatinValuesBreakBetweenWords_soANarrowColumnNeverSplitsAWord() {
        val l = line(items(emptyList(), null, null, AlbumAiCredits(recordedStudios = listOf("Sunset Sound, Los Angeles"))), en)!!

        assertEquals("Recorded\u00A0Sunset Sound, Los Angeles", l.text)
        assertEquals(listOf("Recorded Sunset ", "Sound, ", "Los ", "Angeles"), l.segments)
    }

    @Test
    fun should_cutSegments_onlyWhereTheLineMayBreak() {
        val items = items(
            listOf("林间工作室"),
            null,
            null,
            AlbumAiCredits(recordedTime = "Sep–Dec 1975", recordedStudios = listOf("大溪地录音室，台北"), source = "电影《夜航》"),
        )
        val l = line(items, zh)!!

        assertEquals(listOf("厂牌 林间工作室 · ", "录制 Sep–Dec ", "1975，", "大溪地录音室，", "台北 · ", "原作 电影《夜航》"), l.segments)
        assertEquals(l.plain, l.segments.joinToString(""))
        // A space before "/" or "," keeps it with the word before (UAX #14 LB13).
        assertEquals(listOf("Label Roc-A-Fella / ", "Def ", "Jam"), YoinAlbumAbout.creditSegments("Label\u00A0Roc-\u2060A-\u2060Fella /\u2060 Def Jam"))
    }

    @Test
    fun should_showTimeOrPlaceAlone_when_onlyOneIsKnown() {
        assertEquals("Recorded 1975", line(items(emptyList(), null, null, AlbumAiCredits(recordedTime = "1975")), en)?.plain)
        assertEquals("Recorded A, B", line(items(emptyList(), null, null, AlbumAiCredits(recordedStudios = listOf("A", "B"))), en)?.plain)
    }

    @Test
    fun should_listEachCredit_forTheSheet() {
        val rows = line(items(listOf("ECM"), null, null, AlbumAiCredits(mixing = listOf("A", "B"))), zh)!!.rows

        assertEquals(listOf(AlbumCreditRow(AlbumCreditKind.Label, "厂牌", "ECM"), AlbumCreditRow(AlbumCreditKind.Mixing, "混音", "A、B")), rows)
    }
}
