package com.gpo.yoin.data.lyrics

import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.local.LyricsCache
import com.gpo.yoin.data.local.LyricsCacheDao
import com.gpo.yoin.data.local.LyricsTranslationCache
import com.gpo.yoin.data.local.YoinDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/** Size budget over a real Room table: what counts, what goes first, and what never goes. */
@RunWith(RobolectricTestRunner::class)
class LyricsCacheBudgetTest {

    private lateinit var database: YoinDatabase
    private lateinit var dao: LyricsCacheDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            YoinDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = database.lyricsCacheDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun should_measureUtf8Bytes_when_lyricsHaveCjkText() = runTest {
        dao.upsert(row("t1", lrc = "[00:01.00]占位"))

        val footprint = dao.lyricsFootprints().single()

        assertEquals(LyricsCachePolicy.utf8Bytes("[00:01.00]占位"), footprint.lrcBytes)
        assertEquals(16L, footprint.lrcBytes)
    }

    @Test
    fun should_countOnlyProviderTranslations_when_measuringTranslationBytes() = runTest {
        dao.upsert(row("t1"))
        translationDao().upsert(translation("t1", model = "provider:qq", json = """["一","二"]"""))
        translationDao().upsert(translation("t1", model = "gemini-x", json = """["paid line"]"""))

        val footprint = dao.providerTranslationFootprints().single()

        assertEquals("t1", footprint.trackRawId)
        assertEquals(LyricsCachePolicy.utf8Bytes("""["一","二"]"""), footprint.bytes)
    }

    @Test
    fun should_evictAutomaticRowsBeforeUserChosen_when_overBudget() = runTest {
        dao.upsert(row("user-old", cachedAt = 1L, userChosen = true, lrc = lrcOf(40)))
        dao.upsert(row("auto-new", cachedAt = 300L, lrc = lrcOf(40)))
        dao.upsert(row("auto-old", cachedAt = 200L, lrc = lrcOf(40)))
        val budget = LyricsCacheBudget(dao, budgetBytes = 100, targetBytes = 50)

        val evicted = budget.trim()

        assertEquals(2, evicted)
        assertNotNull(dao.get("spotify", "user-old"))
        assertNull(dao.get("spotify", "auto-new"))
        assertNull(dao.get("spotify", "auto-old"))
    }

    @Test
    fun should_evictLeastRecentlyUsedUserRow_when_onlyUserRowsRemain() = runTest {
        dao.upsert(row("user-stale", cachedAt = 10L, userChosen = true, lrc = lrcOf(40)))
        dao.upsert(row("manual-recent", cachedAt = 500L, provider = "manual", userChosen = true, lrc = lrcOf(40)))
        dao.upsert(row("user-mid", cachedAt = 100L, userChosen = true, lrc = lrcOf(40)))
        val budget = LyricsCacheBudget(dao, budgetBytes = 100, targetBytes = 80)

        budget.trim()

        assertNull(dao.get("spotify", "user-stale"))
        assertNotNull(dao.get("spotify", "user-mid"))
        assertNotNull(dao.get("spotify", "manual-recent"))
    }

    @Test
    fun should_readUserRowAsRecent_when_markedUsed() = runTest {
        dao.upsert(row("user-a", cachedAt = 10L, userChosen = true, lrc = lrcOf(40)))
        dao.upsert(row("user-b", cachedAt = 20L, userChosen = true, lrc = lrcOf(40)))
        dao.upsert(row("user-c", cachedAt = 30L, userChosen = true, lrc = lrcOf(40)))
        dao.markUsed("spotify", "user-a", usedAt = 1_000L)
        val budget = LyricsCacheBudget(dao, budgetBytes = 100, targetBytes = 80)

        budget.trim()

        assertNotNull(dao.get("spotify", "user-a"))
        assertNull(dao.get("spotify", "user-b"))
        assertNotNull(dao.get("spotify", "user-c"))
    }

    @Test
    fun should_neverMoveRecencyBackwards_when_markedUsedWithAnOlderTime() = runTest {
        dao.upsert(row("user-a", cachedAt = 500L, userChosen = true))

        assertEquals(0, dao.markUsed("spotify", "user-a", usedAt = 100L))
        assertEquals(500L, dao.get("spotify", "user-a")?.cachedAt)
    }

    @Test
    fun should_deleteProviderTranslationsWithTheirLyricRow_when_evicting() = runTest {
        dao.upsert(row("auto", cachedAt = 1L, lrc = lrcOf(30)))
        dao.upsert(row("user", cachedAt = 2L, userChosen = true, lrc = lrcOf(30)))
        translationDao().upsert(translation("auto", model = "provider:qq", json = jsonOf(30)))
        translationDao().upsert(translation("auto", model = "gemini-x", json = jsonOf(30)))
        translationDao().upsert(translation("user", model = "provider:netease", json = jsonOf(30)))
        // Lyric + free translation: auto = 60, user = 60. Paid rows never count.
        val budget = LyricsCacheBudget(dao, budgetBytes = 100, targetBytes = 60)

        budget.trim()

        assertNull(dao.get("spotify", "auto"))
        assertNull(translationDao().get("spotify", "auto", "hash", "Simplified Chinese", "provider:qq"))
        assertNotNull(translationDao().get("spotify", "auto", "hash", "Simplified Chinese", "gemini-x"))
        assertNotNull(dao.get("spotify", "user"))
        assertNotNull(translationDao().get("spotify", "user", "hash", "Simplified Chinese", "provider:netease"))
    }

    @Test
    fun should_evictOrphanProviderTranslations_when_theirLyricRowIsGone() = runTest {
        dao.upsert(row("user", cachedAt = 1L, userChosen = true, lrc = lrcOf(60)))
        translationDao().upsert(translation("orphan", model = "provider:qq", json = jsonOf(60), cachedAt = 999L))
        val budget = LyricsCacheBudget(dao, budgetBytes = 100, targetBytes = 60)

        budget.trim()

        assertNull(translationDao().get("spotify", "orphan", "hash", "Simplified Chinese", "provider:qq"))
        assertNotNull(dao.get("spotify", "user"))
    }

    @Test
    fun should_keepRow_when_itWasRewrittenAfterTheSnapshot() = runTest {
        dao.upsert(row("t1", cachedAt = 10L))

        assertEquals(0, dao.deleteIfUnchanged("spotify", "t1", cachedAt = 9L))
        assertNotNull(dao.get("spotify", "t1"))
        assertEquals(1, dao.deleteIfUnchanged("spotify", "t1", cachedAt = 10L))
    }

    @Test
    fun should_keepJustWrittenRow_when_writeCrossesTheBudget() = runTest {
        val budget = LyricsCacheBudget(dao, budgetBytes = 100, targetBytes = 60)
        dao.upsert(row("old", cachedAt = 1L, lrc = lrcOf(50)))
        budget.onWrite("spotify", "old", 50)
        // An oversized row written later than everything else, but stamped older: still protected.
        dao.upsert(row("big", cachedAt = 0L, lrc = lrcOf(120)))
        budget.onWrite("spotify", "big", 120)

        assertNotNull(dao.get("spotify", "big"))
        assertNull(dao.get("spotify", "old"))
    }

    @Test
    fun should_notScanAgain_when_writesStayUnderTheBudget() = runTest {
        val budget = LyricsCacheBudget(dao, budgetBytes = 100, targetBytes = 60)
        dao.upsert(row("a", cachedAt = 1L, lrc = lrcOf(30)))
        budget.onWrite("spotify", "a", 30)
        // A row the estimate never hears about (e.g. written by cloud sync) is only found by the next scan.
        dao.upsert(row("unseen", cachedAt = 2L, lrc = lrcOf(60)))
        dao.upsert(row("b", cachedAt = 3L, lrc = lrcOf(30)))
        budget.onWrite("spotify", "b", 30)

        // Estimate 60 <= 100: no trim yet, although the table really holds 120.
        assertNotNull(dao.get("spotify", "a"))
        assertNotNull(dao.get("spotify", "unseen"))

        dao.upsert(row("c", cachedAt = 4L, lrc = lrcOf(50)))
        budget.onWrite("spotify", "c", 50)

        // 110 > 100 on the estimate: a real scan (170 bytes) trims the oldest down to 60.
        assertNull(dao.get("spotify", "a"))
        assertNull(dao.get("spotify", "unseen"))
        assertNull(dao.get("spotify", "b"))
        assertNotNull(dao.get("spotify", "c"))
    }

    @Test
    fun should_keepTypedLyrics_when_theyAreTheLeastRecentlyUsed() = runTest {
        dao.upsert(row("typed-old", cachedAt = 1L, provider = "manual", userChosen = true, lrc = lrcOf(40)))
        dao.upsert(row("pick", cachedAt = 100L, userChosen = true, lrc = lrcOf(40)))
        dao.upsert(row("auto", cachedAt = 200L, lrc = lrcOf(40)))
        val budget = LyricsCacheBudget(dao, budgetBytes = 100, targetBytes = 40)

        assertEquals(2, budget.trim())

        assertNotNull(dao.get("spotify", "typed-old"))
        assertNull(dao.get("spotify", "pick"))
        assertNull(dao.get("spotify", "auto"))
    }

    @Test
    fun should_stopEvictingAndWarnOnce_when_typedLyricsAloneExceedTheBudget() = runTest {
        ShadowLog.reset()
        dao.upsert(row("typed-a", cachedAt = 1L, provider = "manual", userChosen = true, lrc = lrcOf(60)))
        dao.upsert(row("typed-b", cachedAt = 2L, provider = "manual", userChosen = true, lrc = lrcOf(60)))
        dao.upsert(row("auto", cachedAt = 3L, lrc = lrcOf(20)))
        val budget = LyricsCacheBudget(dao, budgetBytes = 100, targetBytes = 60)

        assertEquals(1, budget.trim())
        // A later write still finds only typed lyrics over the budget: nothing to evict, no second warning.
        dao.upsert(row("new", cachedAt = 4L, lrc = lrcOf(20)))
        budget.onWrite("spotify", "new", 20)

        assertNull(dao.get("spotify", "auto"))
        assertNotNull(dao.get("spotify", "typed-a"))
        assertNotNull(dao.get("spotify", "typed-b"))
        assertNotNull(dao.get("spotify", "new"))
        assertEquals(1, ShadowLog.getLogsForTag("LyricsCacheBudget").count { it.type == Log.WARN })
    }

    private fun translationDao() = database.lyricsTranslationCacheDao()

    private fun row(
        rawId: String,
        cachedAt: Long = 0L,
        provider: String = "qq",
        userChosen: Boolean = false,
        lrc: String = "[00:01.00]placeholder",
    ): LyricsCache = LyricsCachePolicy.entry(
        trackProvider = "spotify",
        trackRawId = rawId,
        lyricsProvider = provider,
        lyricsProviderSongId = if (provider == "manual") null else "song-$rawId",
        lrc = lrc,
        cachedAt = cachedAt,
        userChosen = userChosen,
    )

    private fun translation(
        rawId: String,
        model: String,
        json: String,
        cachedAt: Long = 0L,
    ) = LyricsTranslationCache(
        trackProvider = "spotify",
        trackRawId = rawId,
        sourceHash = "hash",
        targetLanguage = "Simplified Chinese",
        model = model,
        translationsJson = json,
        cachedAt = cachedAt,
    )

    /** ASCII placeholder LRC of exactly [bytes] bytes. */
    private fun lrcOf(bytes: Int): String {
        val head = "[00:01.00]"
        return head + "x".repeat(bytes - head.length)
    }

    /** ASCII JSON array of exactly [bytes] bytes. */
    private fun jsonOf(bytes: Int): String = "[\"" + "y".repeat(bytes - 4) + "\"]"
}
