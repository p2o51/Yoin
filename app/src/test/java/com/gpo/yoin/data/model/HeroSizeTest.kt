package com.gpo.yoin.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [HeroSize]: one hero bucket per device, the largest display it has (PR #331 review: a
 * foldable started folded locked the hero to 1200 for the whole process after unfolding).
 */
class HeroSizeTest {

    private class MemoryStore(var value: Int = 0) : HeroSize.Store {
        override fun read(): Int = value
        override fun write(px: Int) {
            value = px
        }
    }

    private val foldOuter = 1080 to 2520
    private val foldInner = 1968 to 2184

    @Test
    fun theLargestDisplayAndModeWins() {
        val hero = HeroSize(fallback = { 1200 })
        hero.install(listOf(foldOuter, foldInner, 0 to 0), MemoryStore())
        assertEquals(1600, hero.px)
    }

    @Test
    fun aFoldableStartedFoldedRaisesToTheInnerScreenOnUnfold_andKeepsItFolded() {
        val store = MemoryStore()
        val hero = HeroSize(fallback = { 1200 })
        // Folded: the logical display reports only the outer panel.
        hero.install(listOf(foldOuter), store)
        assertEquals(1200, hero.px)

        hero.note(foldInner.first, foldInner.second) // unfolded
        assertEquals(1600, hero.px)
        hero.note(foldOuter.first, foldOuter.second) // folded again: never lowered
        assertEquals(1600, hero.px)

        // The next process, started folded: the inner screen is remembered.
        val next = HeroSize(fallback = { 1200 })
        next.install(listOf(foldOuter), store)
        assertEquals(1600, next.px)
    }

    @Test
    fun rotationNeverChangesIt() {
        val hero = HeroSize(fallback = { 1200 })
        hero.install(listOf(1600 to 2560), MemoryStore())
        hero.note(2560, 1600)
        hero.note(1600, 2560)
        assertEquals(1600, hero.px)
    }

    @Test
    fun aSmallerWindowNeverLowersIt() {
        val hero = HeroSize(fallback = { 1200 })
        hero.install(listOf(1600 to 2560), MemoryStore())
        hero.note(800, 1200) // split screen / desktop window
        assertEquals(1600, hero.px)
    }

    @Test
    fun beforeInstallTheFallbackIsUsed_andInstallOnlyRaisesIt() {
        val hero = HeroSize(fallback = { 1200 })
        assertEquals(1200, hero.px)
        hero.install(listOf(1080 to 2400), MemoryStore())
        assertEquals(1200, hero.px)
        val tablet = HeroSize(fallback = { 1200 })
        assertEquals(1200, tablet.px)
        tablet.install(listOf(1600 to 2560), MemoryStore())
        assertEquals(1600, tablet.px)
    }
}
