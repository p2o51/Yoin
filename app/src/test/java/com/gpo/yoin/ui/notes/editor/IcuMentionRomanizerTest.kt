package com.gpo.yoin.ui.notes.editor

import com.gpo.yoin.shared.notes.MentionKind
import com.gpo.yoin.shared.notes.NoteMention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The platform's pinyin (ICU Han-Latin), as the @ popup uses it on a device. */
@RunWith(RobolectricTestRunner::class)
class IcuMentionRomanizerTest {

    @Test
    fun should_spellChineseNames_andLeaveOthersAlone() {
        assertEquals("niu yue", IcuMentionRomanizer.romanize("纽约"))
        assertNull(IcuMentionRomanizer.romanize("New York"))
    }

    @Test
    fun should_letAComposingPinyinQueryFindTheChineseTitle() {
        val row = MentionCandidate(NoteMention(MentionKind.Track, "subsonic", "t", "晴天", "周杰伦"), coverUrl = null, artist = "周杰伦")

        assertEquals(MentionRanking.Tier.TitlePrefix, MentionRanking.tier("qingt", row, IcuMentionRomanizer))
        assertEquals(MentionRanking.Tier.Initials, MentionRanking.tier("qt", row, IcuMentionRomanizer))
        assertEquals(MentionRanking.Tier.Artist, MentionRanking.tier("zjl", row, IcuMentionRomanizer))
    }
}
