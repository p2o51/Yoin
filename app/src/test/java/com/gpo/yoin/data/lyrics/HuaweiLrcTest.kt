package com.gpo.yoin.data.lyrics

import com.gpo.yoin.data.model.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic LRC bodies shaped like Huawei's files (no real lyrics). */
class HuaweiLrcTest {

    private val translated = setOf("translate", "scrolling")

    @Test
    fun should_splitAtCaret_when_spacesPresentOrAbsent() {
        val payload = HuaweiLrc.split(
            """
            [00:10.00]First line ^第一行
            [00:12.50]Second line^第二行
            [00:15.00]Third line  ^  第三行
            """.trimIndent(),
            translated,
        )!!

        assertEquals(
            "[00:10.00]First line\n[00:12.50]Second line\n[00:15.00]Third line",
            payload.lyric,
        )
        assertEquals(
            "[00:10.00]第一行\n[00:12.50]第二行\n[00:15.00]第三行",
            payload.translatedLyric,
        )
    }

    @Test
    fun should_splitAtLastCaret_when_originalContainsCaret() {
        val split = HuaweiLrc.split("[00:10.00]Smile ^_^ for me^为我微笑", translated)!!

        assertEquals("[00:10.00]Smile ^_^ for me", split.lyric)
        assertEquals("[00:10.00]为我微笑", split.translatedLyric)
    }

    @Test
    fun should_keepCaretAsText_when_nothingReadableFollowsIt() {
        val payload = HuaweiLrc.split("[00:10.00]Smile ^_^\n[00:12.00]Next ^下一句", translated)!!

        assertEquals("[00:10.00]Smile ^_^\n[00:12.00]Next", payload.lyric)
        assertEquals("[00:10.00]//\n[00:12.00]下一句", payload.translatedLyric)
    }

    @Test
    fun should_emitPlaceholder_when_lineHasNoTranslation() {
        val payload = HuaweiLrc.split(
            """
            [00:00.00]Song - Artist
            [00:00.00]Lyrics by : Someone
            [00:00.00]Opening line ^开场白
            [00:14.07]Yeah
            [00:20.00]Dangling caret^
            """.trimIndent(),
            translated,
        )!!

        assertEquals(
            listOf(
                "[00:00.00]//",
                "[00:00.00]//",
                "[00:00.00]开场白",
                "[00:14.07]//",
                "[00:20.00]//",
            ),
            payload.translatedLyric!!.lines(),
        )
        assertTrue(payload.lyric.lines().contains("[00:20.00]Dangling caret"))
        // Both sides parse to the same number of lines per timestamp → positional pairing works.
        val original = LrcParser.parse(payload.lyric) as Lyrics.Synced
        val translation = LrcParser.parse(payload.translatedLyric!!) as Lyrics.Synced
        assertEquals(original.lines.map { it.startMs }, translation.lines.map { it.startMs })
    }

    @Test
    fun should_keepTagsAndUntimedLinesInOriginalOnly_when_splitting() {
        val payload = HuaweiLrc.split(
            """
            [ti:Song]
            [ar:Artist]
            [offset:0]
            [00:01.00]Line ^行
            """.trimIndent(),
            translated,
        )!!

        assertEquals("[ti:Song]\n[ar:Artist]\n[offset:0]\n[00:01.00]Line", payload.lyric)
        assertEquals("[00:01.00]行", payload.translatedLyric)
    }

    @Test
    fun should_notSplit_when_subTypeLacksTranslate() {
        val raw = "[00:01.00]Up ^ and down^上下"

        val payload = HuaweiLrc.split(raw, setOf("scrolling"))!!

        assertEquals(raw, payload.lyric)
        assertNull(payload.translatedLyric)
    }

    @Test
    fun should_sniffInlineTranslation_when_subTypeUnknown() {
        val withTranslation = HuaweiLrc.split("[00:01.00]Hello ^你好", emptySet())!!
        val withoutTranslation = HuaweiLrc.split("[00:01.00]Up ^ and down^ up", emptySet())!!

        assertEquals("[00:01.00]Hello", withTranslation.lyric)
        assertEquals("[00:01.00]你好", withTranslation.translatedLyric)
        assertEquals("[00:01.00]Up ^ and down^ up", withoutTranslation.lyric)
        assertNull(withoutTranslation.translatedLyric)
    }

    @Test
    fun should_returnNullTranslation_when_noLineTranslated() {
        val payload = HuaweiLrc.split("[00:00.00]Song - Artist\n[00:01.00]Ooh", translated)!!

        assertEquals("[00:00.00]Song - Artist\n[00:01.00]Ooh", payload.lyric)
        assertNull(payload.translatedLyric)
    }

    @Test
    fun should_keepAllTimestamps_when_lineHasMultipleStamps() {
        val payload = HuaweiLrc.split("[00:10.00][01:10.00]Chorus ^副歌", translated)!!

        assertEquals("[00:10.00][01:10.00]Chorus", payload.lyric)
        assertEquals("[00:10.00][01:10.00]副歌", payload.translatedLyric)
    }

    @Test
    fun should_dropLineFromBothSides_when_originalTextIsEmpty() {
        val payload = HuaweiLrc.split("[00:01.00]Line ^行\n[00:02.00]^孤立译文\n[00:03.00]", translated)!!

        assertEquals("[00:01.00]Line", payload.lyric)
        assertEquals("[00:01.00]行", payload.translatedLyric)
    }

    @Test
    fun should_produceOriginalWithoutCaretTails_when_translated() {
        val payload = HuaweiLrc.split(
            "[00:01.00]A ^甲\n[00:02.00]B^乙\n[00:03.00]C\n[00:04.00]D ^丁",
            translated,
        )!!

        assertFalse(payload.lyric.contains('^'))
        assertEquals(
            listOf("A", "B", "C", "D"),
            (LrcParser.parse(payload.lyric) as Lyrics.Synced).lines.map { it.text },
        )
    }

    @Test
    fun should_returnNull_when_instrumentalPlaceholder() {
        assertNull(HuaweiLrc.split("[00:00:00]此歌曲为没有填词的纯音乐", setOf("abs")))
        assertNull(HuaweiLrc.split("[00:00.00]此歌曲为没有填词的纯音乐\n", emptySet()))
        assertNull(HuaweiLrc.split("﻿\r\n  \r\n", translated))
    }

    @Test
    fun should_stripBomAndCrlf_when_present() {
        val payload = HuaweiLrc.split("﻿[ti:Song]\r\n[00:01.00]Line ^行\r\n", translated)!!

        assertEquals("[ti:Song]\n[00:01.00]Line", payload.lyric.trim())
        assertEquals("[00:01.00]行", payload.translatedLyric)
    }

    @Test
    fun should_decodeNumericEntities_when_present() {
        val payload = HuaweiLrc.split("[00:01.00]&#45684;&#51652;&#49828; ma&#xF1;ana ^新牛仔", translated)!!

        assertEquals("[00:01.00]뉴진스 mañana", payload.lyric)
        assertEquals("[00:01.00]新牛仔", payload.translatedLyric)
    }
}
