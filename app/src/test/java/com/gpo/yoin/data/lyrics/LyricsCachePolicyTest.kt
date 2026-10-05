package com.gpo.yoin.data.lyrics

import com.gpo.yoin.data.local.LyricsCache
import com.gpo.yoin.data.lyrics.LyricsCachePolicy.Footprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsCachePolicyTest {

    @Test
    fun should_markSongId_when_entryIsAUserChosenPick() {
        val entry = entry(provider = "qq", songId = "mid-1", userChosen = true)

        assertEquals("user|mid-1", entry.lyricsProviderSongId)
        assertTrue(LyricsCachePolicy.isUserChosen(entry))
        assertEquals("mid-1", LyricsCachePolicy.providerSongId(entry))
    }

    @Test
    fun should_keepSongIdUnmarked_when_entryIsAutomatic() {
        val entry = entry(provider = "qq", songId = "mid-1", userChosen = false)

        assertEquals("mid-1", entry.lyricsProviderSongId)
        assertFalse(LyricsCachePolicy.isUserChosen(entry))
        assertEquals("mid-1", LyricsCachePolicy.providerSongId(entry))
    }

    @Test
    fun should_keepManualSongIdNull_when_entryIsTypedLyrics() {
        val entry = entry(provider = LyricsCachePolicy.MANUAL_PROVIDER, songId = null, userChosen = true)

        assertNull(entry.lyricsProviderSongId)
        assertTrue(LyricsCachePolicy.isUserChosen(entry))
    }

    @Test
    fun should_treatManualRowAsUserChosen_when_writtenBeforeThePolicy() {
        // Rows typed before this change carry no marker; "manual" alone is enough.
        val legacy = LyricsCache("spotify", "t1", "manual", null, "[00:01.00]placeholder", 1L)

        assertTrue(LyricsCachePolicy.isUserChosen(legacy))
    }

    @Test
    fun should_decodeNull_when_userChosenPickHadNoSongId() {
        val entry = entry(provider = "netease", songId = null, userChosen = true)

        assertTrue(LyricsCachePolicy.isUserChosen(entry))
        assertNull(LyricsCachePolicy.providerSongId(entry))
    }

    @Test
    fun should_keepHuaweiIdIntact_when_markedAndUnmarked() {
        val entry = entry(provider = "huawei", songId = "hw1|42|Title+Artist", userChosen = true)

        assertEquals("hw1|42|Title+Artist", LyricsCachePolicy.providerSongId(entry))
    }

    @Test
    fun should_expireAutomaticRow_when_olderThanTtl() {
        val now = 100L * DAY
        val fresh = entry(provider = "qq", songId = "a", userChosen = false, cachedAt = now - 29L * DAY)
        val stale = entry(provider = "qq", songId = "a", userChosen = false, cachedAt = now - 31L * DAY)

        assertTrue(LyricsCachePolicy.isUsable(fresh, now))
        assertFalse(LyricsCachePolicy.isUsable(stale, now))
    }

    @Test
    fun should_keepUserChosenRow_when_olderThanTtl() {
        val now = 1_000L * DAY
        val pick = entry(provider = "qq", songId = "a", userChosen = true, cachedAt = 1L)
        val typed = entry(provider = "manual", songId = null, userChosen = true, cachedAt = 1L)

        assertTrue(LyricsCachePolicy.isUsable(pick, now))
        assertTrue(LyricsCachePolicy.isUsable(typed, now))
    }

    @Test
    fun should_refreshRecencyOnlyForUserRows_when_aDayHasPassed() {
        val now = 10L * DAY
        val userOld = entry(provider = "qq", songId = "a", userChosen = true, cachedAt = now - DAY)
        val userRecent = entry(provider = "qq", songId = "a", userChosen = true, cachedAt = now - DAY + 1)
        val automaticOld = entry(provider = "qq", songId = "a", userChosen = false, cachedAt = now - 5L * DAY)

        assertTrue(LyricsCachePolicy.needsRecencyRefresh(userOld, now))
        assertFalse(LyricsCachePolicy.needsRecencyRefresh(userRecent, now))
        assertFalse(LyricsCachePolicy.needsRecencyRefresh(automaticOld, now))
    }

    @Test
    fun should_countUtf8Bytes_when_textHasCjk() {
        assertEquals(3L, LyricsCachePolicy.utf8Bytes("abc"))
        assertEquals(6L, LyricsCachePolicy.utf8Bytes("占位"))
    }

    @Test
    fun should_evictNothing_when_withinBudget() {
        val footprints = listOf(footprint("a", user = false, lastUsed = 1, bytes = 50))

        assertTrue(LyricsCachePolicy.planEviction(footprints, budgetBytes = 100, targetBytes = 80).isEmpty())
    }

    @Test
    fun should_evictAutomaticRowsBeforeUserRows_when_overBudget() {
        val footprints = listOf(
            footprint("user-old", user = true, lastUsed = 1, bytes = 40),
            footprint("auto-new", user = false, lastUsed = 900, bytes = 40),
            footprint("auto-old", user = false, lastUsed = 100, bytes = 40),
        )

        val victims = LyricsCachePolicy.planEviction(footprints, budgetBytes = 100, targetBytes = 40)

        assertEquals(listOf("auto-old", "auto-new"), victims.map(Footprint::trackRawId))
    }

    @Test
    fun should_evictLeastRecentlyUsedUserRowFirst_when_automaticRowsAreGone() {
        val footprints = listOf(
            footprint("user-recent", user = true, lastUsed = 500, bytes = 40),
            footprint("user-stale", user = true, lastUsed = 10, bytes = 40),
            footprint("auto", user = false, lastUsed = 900, bytes = 40),
        )

        val victims = LyricsCachePolicy.planEviction(footprints, budgetBytes = 100, targetBytes = 40)

        assertEquals(listOf("auto", "user-stale"), victims.map(Footprint::trackRawId))
    }

    @Test
    fun should_stopAtTarget_when_trimming() {
        val footprints = (1..10).map { footprint("t$it", user = false, lastUsed = it.toLong(), bytes = 10) }

        val victims = LyricsCachePolicy.planEviction(footprints, budgetBytes = 90, targetBytes = 70)

        assertEquals(listOf("t1", "t2", "t3"), victims.map(Footprint::trackRawId))
    }

    @Test
    fun should_neverEvictProtectedTrack_when_itIsTheOldest() {
        val footprints = listOf(
            footprint("just-written", user = false, lastUsed = 1, bytes = 80),
            footprint("other", user = false, lastUsed = 2, bytes = 40),
        )

        val victims = LyricsCachePolicy.planEviction(
            footprints,
            budgetBytes = 100,
            targetBytes = 10,
            protectedTrack = "spotify" to "just-written",
        )

        assertEquals(listOf("other"), victims.map(Footprint::trackRawId))
    }

    @Test
    fun should_neverEvictTypedLyrics_when_theyAreTheLeastRecentlyUsed() {
        val footprints = listOf(
            footprint("typed-old", user = true, lastUsed = 1, bytes = 40, typed = true),
            footprint("pick", user = true, lastUsed = 500, bytes = 40),
            footprint("auto", user = false, lastUsed = 900, bytes = 40),
        )

        val victims = LyricsCachePolicy.planEviction(footprints, budgetBytes = 100, targetBytes = 40)

        assertEquals(listOf("auto", "pick"), victims.map(Footprint::trackRawId))
    }

    @Test
    fun should_stopShortOfTarget_when_onlyTypedLyricsAndProtectedTrackRemain() {
        val footprints = listOf(
            footprint("typed-a", user = true, lastUsed = 1, bytes = 60, typed = true),
            footprint("typed-b", user = true, lastUsed = 2, bytes = 60, typed = true),
            footprint("auto", user = false, lastUsed = 3, bytes = 10),
            footprint("just-written", user = false, lastUsed = 0, bytes = 10),
        )

        val victims = LyricsCachePolicy.planEviction(
            footprints,
            budgetBytes = 100,
            targetBytes = 50,
            protectedTrack = "spotify" to "just-written",
        )

        assertEquals(listOf("auto"), victims.map(Footprint::trackRawId))
    }

    @Test
    fun should_treatOnlyManualRowsAsTyped_when_checkingProvider() {
        assertTrue(LyricsCachePolicy.isTyped(LyricsCachePolicy.MANUAL_PROVIDER))
        assertFalse(LyricsCachePolicy.isTyped("qq"))
        assertFalse(LyricsCachePolicy.isTyped("subsonic"))
    }

    private fun entry(
        provider: String,
        songId: String?,
        userChosen: Boolean,
        cachedAt: Long = 0L,
    ): LyricsCache = LyricsCachePolicy.entry(
        trackProvider = "spotify",
        trackRawId = "t1",
        lyricsProvider = provider,
        lyricsProviderSongId = songId,
        lrc = "[00:01.00]placeholder",
        cachedAt = cachedAt,
        userChosen = userChosen,
    )

    private fun footprint(rawId: String, user: Boolean, lastUsed: Long, bytes: Long, typed: Boolean = false) =
        Footprint(
            trackProvider = "spotify",
            trackRawId = rawId,
            userChosen = user,
            lastUsedAt = lastUsed,
            bytes = bytes,
            lyricsCachedAt = lastUsed,
            typed = typed,
        )

    private companion object {
        const val DAY = 24L * 60L * 60L * 1000L
    }
}
