package com.gpo.yoin.data.highlight

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.local.AlbumLastPlayed
import com.gpo.yoin.data.local.AlbumPlayHistoryAggregate
import com.gpo.yoin.data.local.AlbumPlayStats
import com.gpo.yoin.data.local.ArtistPlayStats
import com.gpo.yoin.data.local.ArtistSongPlayAggregate
import com.gpo.yoin.data.local.PlayHistory
import com.gpo.yoin.data.local.PlayHistoryDao
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.local.SongMemoryAggregate
import com.gpo.yoin.data.local.SongPlayCount
import com.gpo.yoin.shared.ondevice.HomeGreetingPrompt
import java.time.ZoneOffset
import java.util.Locale
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OnDeviceAiIntegrationTest {

    private class RecordingModel(var reply: NanoAnswer) : LyricHighlightModel {
        var calls = 0
        var lastMaxTokens = 0

        override suspend fun status(): NanoStatus = NanoStatus.AVAILABLE
        override suspend fun baseModelName(): String = "nano-v4-test"
        override suspend fun download(): Boolean = true
        override suspend fun answer(prompt: String): NanoAnswer = answer(prompt, 32, 0.2f, 16)
        override suspend fun answer(
            prompt: String,
            maxOutputTokens: Int,
            temperature: Float,
            topK: Int,
        ): NanoAnswer {
            calls++
            lastMaxTokens = maxOutputTokens
            return reply
        }
    }

    private class FakePlayHistoryDao(var rows: List<PlayHistory> = emptyList()) : PlayHistoryDao {
        override fun getRecentHistory(profileId: String, provider: String, limit: Int): Flow<List<PlayHistory>> =
            flowOf(rows.take(limit))
        override suspend fun getMostRecentPlay(songId: String, provider: String, profileId: String): PlayHistory? =
            rows.firstOrNull()
        override suspend fun getAlbumLastPlayed(albumId: String, provider: String, profileId: String): Long? = null
        override suspend fun insert(entry: PlayHistory) = Unit
        override fun observeAlbumLastPlayed(profileId: String, provider: String): Flow<List<AlbumLastPlayed>> =
            flowOf(emptyList())
        override suspend fun deleteOlderThan(timestamp: Long) = Unit
        override fun getPlayCount(songId: String, provider: String, profileId: String): Flow<Int> = flowOf(0)
        override suspend fun getAlbumAggregates(
            profileId: String,
            provider: String,
            limit: Int,
        ): List<AlbumPlayHistoryAggregate> = emptyList()
        override suspend fun getAlbumAggregatesFor(
            profileId: String,
            provider: String,
            albumIds: List<String>,
        ): List<AlbumPlayHistoryAggregate> = emptyList()
        override fun observeSongPlayCounts(
            songIds: List<String>,
            provider: String,
            profileId: String,
        ): Flow<List<SongPlayCount>> = flowOf(emptyList())
        override fun observeAlbumPlayStats(albumId: String, provider: String, profileId: String): Flow<AlbumPlayStats> =
            flowOf(AlbumPlayStats(0, null, null))
        override fun observeMostRecent(profileId: String, provider: String): Flow<PlayHistory?> =
            flowOf(rows.firstOrNull())
        override suspend fun getArtistTopSongs(
            profileId: String,
            provider: String,
            albumIds: List<String>,
            artistName: String,
            limit: Int,
        ): List<ArtistSongPlayAggregate> = emptyList()
        override suspend fun getArtistPlayStats(
            profileId: String,
            provider: String,
            albumIds: List<String>,
            artistName: String,
        ): ArtistPlayStats = ArtistPlayStats(0)
        override suspend fun getRediscoverSongs(
            profileId: String,
            provider: String,
            playedBefore: Long,
            limit: Int,
        ): List<SongMemoryAggregate> = emptyList()
    }

    @Test
    fun homeGreetingStore_persistsPerProfile_guardsNoon_andRefreshesUpcomingSlotsOnly() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // 1_800_000_000_000L = 2027-01-15T08:00:00Z (MORNING in UTC)
        var nowMs = 1_800_000_000_000L
        var locale = Locale.SIMPLIFIED_CHINESE
        val store = HomeGreetingStore(
            context = app,
            clock = { nowMs },
            zoneId = { ZoneOffset.UTC },
            localeProvider = { locale },
        )
        store.clear()

        val historyDao = FakePlayHistoryDao(
            rows = listOf(
                PlayHistory(
                    songId = "s1",
                    title = "First Love",
                    artist = "宇多田光",
                    album = "First Love",
                    albumId = "a1",
                    coverArtId = null,
                    playedAt = nowMs - 3_600_000L,
                    durationMs = 240_000L,
                    completedPercent = 1f,
                ),
            ),
        )
        val model = RecordingModel(
            NanoAnswer.Text("""{"morning":"刚听完宇多田光《First Love》","afternoon":"下午接着听这张唱片","evening":"今晚留给整张专辑"}"""),
        )

        assertTrue(store.needsGeneration("profile-1"))
        val outcome = store.generateIfNeeded("profile-1", "subsonic", playHistoryDao = historyDao, model = model)
        assertEquals(HomeGreetingStore.GenerationOutcome.GENERATED, outcome)
        assertEquals(1, model.calls)
        assertEquals(HomeGreetingPrompt.MAX_OUTPUT_TOKENS, model.lastMaxTokens)

        // Zero-wait cache hit for all three slots on the same day.
        assertEquals("刚听完宇多田光《First Love》", store.cachedFor(HomeGreetingSlot.MORNING, "profile-1"))
        assertEquals("下午接着听这张唱片", store.cachedFor(HomeGreetingSlot.AFTERNOON, "profile-1"))
        // Noon guard: 11:00-13:00 never shows a string containing "下午".
        assertNull(store.cachedFor(HomeGreetingSlot.AFTERNOON, "profile-1", isNoon = true))
        assertEquals("今晚留给整张专辑", store.cachedFor(HomeGreetingSlot.EVENING, "profile-1"))

        // Second call with unchanged listening cues makes ZERO Nano calls.
        assertEquals(
            HomeGreetingStore.GenerationOutcome.ALREADY_READY,
            store.generateIfNeeded("profile-1", "subsonic", playHistoryDao = historyDao, model = model),
        )
        assertEquals(1, model.calls)

        // Playing a new album mid-morning refreshes ONLY upcoming slots (afternoon & evening), preserving morning.
        historyDao.rows = listOf(
            PlayHistory(
                songId = "s2",
                title = "Weird Fishes",
                artist = "Radiohead",
                album = "In Rainbows",
                albumId = "a2",
                coverArtId = null,
                playedAt = nowMs + 60_000L,
                durationMs = 318_000L,
                completedPercent = 1f,
            ),
        ) + historyDao.rows
        model.reply = NanoAnswer.Text(
            """{"morning":"不该覆盖的早上句子","afternoon":"这周常听 Radiohead《In Rainbows》","evening":"今晚重新翻出《First Love》"}""",
        )
        assertEquals(
            HomeGreetingStore.GenerationOutcome.GENERATED,
            store.generateIfNeeded("profile-1", "subsonic", playHistoryDao = historyDao, model = model),
        )
        assertEquals(2, model.calls)
        assertEquals("刚听完宇多田光《First Love》", store.cachedFor(HomeGreetingSlot.MORNING, "profile-1"))
        assertEquals("这周常听 Radiohead《In Rainbows》", store.cachedFor(HomeGreetingSlot.AFTERNOON, "profile-1"))
        assertEquals("今晚重新翻出《First Love》", store.cachedFor(HomeGreetingSlot.EVENING, "profile-1"))

        // Separate profile keeps its own bucket without stomping profile-1.
        model.reply = NanoAnswer.Text("""{"morning":"周六早，换张唱片","afternoon":"午间放点爵士","evening":"夜里留给整砖"}""")
        assertEquals(
            HomeGreetingStore.GenerationOutcome.GENERATED,
            store.generateIfNeeded("profile-2", "applemusic", playHistoryDao = null, model = model),
        )
        assertEquals("刚听完宇多田光《First Love》", store.cachedFor(HomeGreetingSlot.MORNING, "profile-1"))
        assertEquals("周六早，换张唱片", store.cachedFor(HomeGreetingSlot.MORNING, "profile-2"))

        // Switching language or advancing to tomorrow invalidates cache cleanly.
        locale = Locale.US
        assertNull(store.cachedFor(HomeGreetingSlot.MORNING, "profile-1"))
        assertTrue(store.needsGeneration("profile-1"))

        locale = Locale.SIMPLIFIED_CHINESE
        nowMs += 86_400_000L
        assertNull(store.cachedFor(HomeGreetingSlot.MORNING, "profile-1"))
        assertTrue(store.needsGeneration("profile-1"))
    }

    @Test
    fun aboutFollowUpGenerator_cachesSuggestions_andFallsBackSilentlyWhenUnavailableOrDisabled() = runBlocking {
        val entries = listOf(
            SongAboutEntry(
                titleKey = "t",
                artistKey = "a",
                albumKey = "al",
                titleDisplay = "夜空中最亮的星",
                artistDisplay = "逃跑计划",
                albumDisplay = "世界",
                kind = SongAboutEntry.KIND_CANONICAL,
                entryKey = SongAboutEntry.CANON_REVIEW,
                promptText = null,
                titleText = null,
                answerText = "收录于逃跑计划2011年专辑《世界》，融合英伦摇滚与抒情旋律，最初是毛川在巡演途中写下的。",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        val model = RecordingModel(
            NanoAnswer.Text("""["毛川巡演途中是怎么写出这首歌的？","这首歌的英伦摇滚编曲有什么特点？"]"""),
        )
        val status = MutableStateFlow<NanoStatus?>(NanoStatus.UNAVAILABLE)
        val enabled = MutableStateFlow(true)
        val generator = AboutFollowUpGenerator(
            model = model,
            status = status,
            enabled = enabled,
            isForeground = { true },
            localeProvider = { Locale.SIMPLIFIED_CHINESE },
        )

        assertEquals(emptyList<String>(), generator.suggest(entries, emptyList()))
        assertEquals(0, model.calls)

        status.value = NanoStatus.AVAILABLE
        enabled.value = false
        assertEquals(emptyList<String>(), generator.suggest(entries, emptyList()))
        assertEquals(0, model.calls)

        enabled.value = true
        val first = generator.suggest(entries, emptyList())
        assertEquals(
            listOf("毛川巡演途中是怎么写出这首歌的？", "这首歌的英伦摇滚编曲有什么特点？"),
            first,
        )
        assertEquals(1, model.calls)
        assertEquals(first, generator.peekCached(entries, emptyList()))
        assertEquals(first, generator.suggest(entries, emptyList()))
        assertEquals(1, model.calls)
    }

    @Test
    fun structuredOutput_isPreferredOverPlainTextFallback_forGreetingsAndAboutFollowUps() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        var typedCalls = 0
        var plainCalls = 0
        val typedModel = object : LyricHighlightModel {
            override suspend fun status(): NanoStatus = NanoStatus.AVAILABLE
            override suspend fun baseModelName(): String = "nano-v4-fast"
            override suspend fun download(): Boolean = true
            override suspend fun answer(prompt: String): NanoAnswer {
                plainCalls++
                return NanoAnswer.Failed(null)
            }

            @Suppress("UNCHECKED_CAST")
            override suspend fun <T : Any> answerTyped(
                schema: KClass<T>,
                userPrompt: String,
                systemInstruction: String?,
                maxOutputTokens: Int,
                temperature: Float,
                topK: Int,
            ): NanoTypedAnswer<T> {
                typedCalls++
                return when (schema) {
                    HomeGreetingSchema::class -> NanoTypedAnswer.Value(
                        HomeGreetingSchema(
                            morning = "刚听完宇多田光《First Love》",
                            afternoon = "这周常听 Radiohead《In Rainbows》",
                            evening = "好久没放草东没有派对了",
                        ) as T,
                    )
                    AboutFollowUpSchema::class -> NanoTypedAnswer.Value(
                        AboutFollowUpSchema(
                            questions = listOf(
                                "“香烟的味道”具体象征着什么？",
                                "克制编曲如何烘托青春的复杂性？",
                            ),
                        ) as T,
                    )
                    else -> NanoTypedAnswer.Unsupported
                }
            }
        }

        val store = HomeGreetingStore(
            context = app,
            clock = { 1_800_000_000_000L },
            zoneId = { ZoneOffset.UTC },
            localeProvider = { Locale.SIMPLIFIED_CHINESE },
        )
        store.clear()
        val outcome = store.generateIfNeeded("profile-typed", "subsonic", playHistoryDao = null, model = typedModel)
        assertEquals(HomeGreetingStore.GenerationOutcome.GENERATED, outcome)
        assertEquals("刚听完宇多田光《First Love》", store.cachedFor(HomeGreetingSlot.MORNING, "profile-typed"))
        assertEquals("这周常听 Radiohead《In Rainbows》", store.cachedFor(HomeGreetingSlot.AFTERNOON, "profile-typed"))

        val generator = AboutFollowUpGenerator(
            model = typedModel,
            status = MutableStateFlow(NanoStatus.AVAILABLE),
            enabled = MutableStateFlow(true),
            isForeground = { true },
            localeProvider = { Locale.SIMPLIFIED_CHINESE },
        )
        val entries = listOf(
            SongAboutEntry(
                titleKey = "first love",
                artistKey = "utada",
                albumKey = "first love",
                titleDisplay = "First Love",
                artistDisplay = "宇多田光",
                albumDisplay = "First Love",
                kind = SongAboutEntry.KIND_CANONICAL,
                entryKey = SongAboutEntry.CANON_REVIEW,
                promptText = null,
                titleText = null,
                answerText = "《First Love》是宇多田光 16 岁创作的代表作，编曲克制，以钢琴和弦乐衬托人声呼吸感。",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        val followUps = generator.suggest(entries, emptyList())
        assertEquals(
            listOf("“香烟的味道”具体象征着什么？", "克制编曲如何烘托青春的复杂性？"),
            followUps,
        )
        assertEquals(2, typedCalls)
        assertEquals(0, plainCalls)
    }
}
