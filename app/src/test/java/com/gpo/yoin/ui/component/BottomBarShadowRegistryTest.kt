package com.gpo.yoin.ui.component

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomBarShadowRegistryTest {
    private val barBounds = Rect(16f, 2200f, 1064f, 2300f)

    private fun scene(): Triple<BottomBarShadowRegistry, Any, Long> {
        val registry = BottomBarShadowRegistry()
        val shell = registry.newHost()
        registry.startCasting(shell)
        registry.activate(shell)
        val shellBar = Any()
        registry.place(shellBar, shell, barBounds)
        return Triple(registry, shellBar, registry.newHost())
    }

    @Test
    fun should_keepOnlyShellShadow_when_detailWindowIsNotOnScreenYet() {
        val (registry, shellBar, detail) = scene()
        val detailBar = Any()
        registry.place(detailBar, detail, barBounds)
        // Created and laid out, but no frame shown: the shell keeps casting
        // and the incoming bar stays bare, so the two never stack.
        assertTrue(registry.ownsShadow(shellBar))
        assertFalse(registry.ownsShadow(detailBar))
    }

    @Test
    fun should_castOverShellShadow_when_detailPageCoversIt() {
        val (registry, shellBar, detail) = scene()
        val detailBar = Any()
        registry.place(detailBar, detail, barBounds)
        registry.startCasting(detail)
        // Page drawn but its frame not confirmed on screen: the covered shell
        // shadow stays until then, so a late frame can never leave a gap.
        assertTrue(registry.ownsShadow(detailBar))
        assertTrue(registry.ownsShadow(shellBar))
    }

    @Test
    fun should_handShadowToDetail_when_itsWindowIsOnScreen() {
        val (registry, shellBar, detail) = scene()
        val detailBar = Any()
        registry.place(detailBar, detail, barBounds)
        registry.activate(detail)
        assertFalse(registry.ownsShadow(shellBar))
        assertTrue(registry.ownsShadow(detailBar))
    }

    @Test
    fun should_returnShadowToShell_when_detailStartsFinishing() {
        val (registry, shellBar, detail) = scene()
        val detailBar = Any()
        registry.place(detailBar, detail, barBounds)
        registry.activate(detail)
        // Still composed (the close dissolve is playing), but leaving: the
        // shell casts again and the leaving bar keeps its own shadow, which
        // dissolves with its window.
        registry.release(detail)
        assertTrue(registry.ownsShadow(shellBar))
        assertTrue(registry.ownsShadow(detailBar))
    }

    @Test
    fun should_stayReleased_when_firstFrameSignalArrivesAfterFinish() {
        val (registry, shellBar, detail) = scene()
        registry.place(Any(), detail, barBounds)
        registry.release(detail)
        registry.activate(detail)
        assertTrue(registry.ownsShadow(shellBar))
    }

    @Test
    fun should_crossfadeOneShadow_when_detailHandsBackBeforeFinishing() {
        val (registry, shellBar, detail) = scene()
        val detailBar = Any()
        registry.place(detailBar, detail, barBounds)
        registry.activate(detail)
        assertTrue(registry.beginHandBack(detail))
        assertEquals(0f, registry.shadowShare(shellBar), 0f)
        assertEquals(1f, registry.shadowShare(detailBar), 0f)
        // Every frame of the crossfade: the two windows' shares sum to one
        // shadow — never a gap, never a doubled shadow.
        for (share in listOf(0.1f, 0.35f, 0.5f, 0.8f, 1f)) {
            registry.setHandBackShare(detail, share)
            assertEquals(share, registry.shadowShare(shellBar), 1e-6f)
            assertEquals(1f - share, registry.shadowShare(detailBar), 1e-6f)
        }
    }

    @Test
    fun should_stayBareWithoutReleaseFade_when_handedBackWindowFinishes() {
        val (registry, shellBar, detail) = scene()
        val detailBar = Any()
        registry.place(detailBar, detail, barBounds)
        registry.activate(detail)
        registry.beginHandBack(detail)
        registry.setHandBackShare(detail, 1f)
        registry.release(detail)
        // The system dissolve now carries a bare bar; the shell's shadow is
        // already whole and must not restart a release fade underneath.
        assertEquals(1f, registry.shadowShare(shellBar), 0f)
        assertEquals(0f, registry.shadowShare(detailBar), 0f)
        assertEquals(Long.MIN_VALUE / 2, registry.lastReleaseUptimeMs)
    }

    @Test
    fun should_keepOwnShadow_when_nothingBeneathTakesItBack() {
        val registry = BottomBarShadowRegistry()
        val detail = registry.newHost()
        registry.startCasting(detail)
        registry.activate(detail)
        val detailBar = Any()
        // A page over Now Playing: the shell's bar is gone, nothing overlaps.
        registry.place(detailBar, detail, barBounds)
        assertFalse(registry.beginHandBack(detail))
        registry.release(detail)
        assertEquals(1f, registry.shadowShare(detailBar), 0f)
    }

    @Test
    fun should_stampReleaseOnce_when_finishingReportsTwice() {
        val (registry, _, detail) = scene()
        registry.place(Any(), detail, barBounds)
        registry.activate(detail)
        registry.release(detail)
        // Another window's hand-back clears the fade clock meanwhile; the
        // disposal-time report of the same release must not restart it.
        val next = registry.newHost()
        registry.startCasting(next)
        registry.activate(next)
        registry.place(Any(), next, barBounds)
        registry.beginHandBack(next)
        registry.release(detail)
        assertEquals(Long.MIN_VALUE / 2, registry.lastReleaseUptimeMs)
    }

    @Test
    fun should_keepBothShadows_when_barsDoNotOverlap() {
        val (registry, shellBar, detail) = scene()
        val paneBar = Any()
        registry.place(paneBar, detail, barBounds.translate(1200f, 0f))
        registry.activate(detail)
        assertTrue(registry.ownsShadow(shellBar))
        assertTrue(registry.ownsShadow(paneBar))
    }
}
