package com.gpo.yoin.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class MetaLineTest {

    @Test
    fun should_keepEveryGroup_when_theLineIsWideEnough() {
        assertEquals(3, metaLineVisibleCount(listOf(10, 10, 10), gapPx = 5, maxWidth = 40))
    }

    @Test
    fun should_dropFromTheEnd_when_theNextGroupDoesNotFit() {
        // 40 + 12 + 40 = 92, the third group would make 144.
        assertEquals(2, metaLineVisibleCount(listOf(40, 40, 40), gapPx = 12, maxWidth = 100))
        assertEquals(1, metaLineVisibleCount(listOf(10, 10, 100), gapPx = 12, maxWidth = 30))
    }

    @Test
    fun should_dropTheOnlyGroup_when_itIsWiderThanTheLine() {
        assertEquals(0, metaLineVisibleCount(listOf(80), gapPx = 12, maxWidth = 50))
    }

    @Test
    fun should_showNothing_when_thereIsNoWidthOrNoGroups() {
        assertEquals(0, metaLineVisibleCount(emptyList(), gapPx = 12, maxWidth = 100))
        assertEquals(0, metaLineVisibleCount(listOf(10), gapPx = 12, maxWidth = 0))
    }
}
