package com.gpo.yoin.ui.tips

import com.gpo.yoin.ui.home.HomeBubbleTip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TipReadStoreTest {

    @Test
    fun should_goToTheNextUnreadTip_when_currentIsRead() {
        val read = setOf(HomeBubbleTip.EditHome, HomeBubbleTip.PullForMemories, HomeBubbleTip.CoverMenu)
        assertEquals(HomeBubbleTip.SongMenu, nextUnreadTip(HomeBubbleTip.EditHome, read))
    }

    @Test
    fun should_wrapAround_when_theRestAfterCurrentIsRead() {
        val read = HomeBubbleTip.entries.toSet() - HomeBubbleTip.EditHome
        assertEquals(HomeBubbleTip.EditHome, nextUnreadTip(HomeBubbleTip.Name, read))
    }

    @Test
    fun should_returnNull_when_everyOtherTipIsRead() {
        val read = HomeBubbleTip.entries.toSet() - HomeBubbleTip.PlayModes
        assertNull(nextUnreadTip(HomeBubbleTip.PlayModes, read))
    }

    @Test
    fun should_markAndUnmark_when_toggled() {
        val store = InMemoryTipReadStore()
        store.setRead(HomeBubbleTip.Name, true)
        store.setRead(HomeBubbleTip.PlayModes, true)
        store.setRead(HomeBubbleTip.Name, false)
        assertEquals(setOf(HomeBubbleTip.PlayModes), store.read.value)
        assertEquals(HomeBubbleTip.entries - HomeBubbleTip.PlayModes, store.read.value.unreadTips())
        store.setAllUnread()
        assertEquals(emptySet<HomeBubbleTip>(), store.read.value)
    }
}
