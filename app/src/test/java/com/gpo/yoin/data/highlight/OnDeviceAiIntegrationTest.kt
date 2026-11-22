package com.gpo.yoin.data.highlight

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.local.SongAboutEntry
import java.time.ZoneOffset
import java.util.Locale
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        override suspend fun answer(prompt: String): NanoAnswer = answer(prompt, 24, 0.2f, 16)
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

    @Test
    fun homeGreetingStore_persistsThreeSlots_andServesInstantlyUntilDayOrLocaleChanges() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        var nowMs = 1_800_000_000_000L
        var locale = Locale.SIMPLIFIED_CHINESE
        val store = HomeGreetingStore(
            context = app,
            clock = { nowMs },
            zoneId = { ZoneOffset.UTC },
            localeProvider = { locale },
        )
        store.clear()

        val model = RecordingModel(
            NanoAnswer.Text("""{"morning":"早安，先听一首","afternoon":"午后慢一点","evening":"今晚留给音乐"}"""),
        )

        assertTrue(store.needsGeneration("profile-1"))
        val outcome = store.generateIfNeeded("profile-1", "subsonic", playHistoryDao = null, model = model)
        assertEquals(HomeGreetingStore.GenerationOutcome.GENERATED, outcome)
        assertEquals(1, model.calls)
        assertEquals(HomeGreetingPrompt.MAX_OUTPUT_TOKENS, model.lastMaxTokens)

        // Zero-wait cache hit for all three slots on the same day.
        assertFalse(store.needsGeneration("profile-1"))
        assertEquals("早安，先听一首", store.cachedFor(HomeGreetingSlot.MORNING, "profile-1"))
        assertEquals("午后慢一点", store.cachedFor(HomeGreetingSlot.AFTERNOON, "profile-1"))
        assertEquals("今晚留给音乐", store.cachedFor(HomeGreetingSlot.EVENING, "profile-1"))

        // Second call same day does not touch AICore again.
        assertEquals(
            HomeGreetingStore.GenerationOutcome.ALREADY_READY,
            store.generateIfNeeded("profile-1", "subsonic", playHistoryDao = null, model = model),
        )
        assertEquals(1, model.calls)

        // Switching language or advancing to tomorrow falls back cleanly (null) and marks generation needed.
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

        // Unsupported phone -> empty list silently, zero model calls.
        assertEquals(emptyList<String>(), generator.suggest(entries, emptyList()))
        assertEquals(0, model.calls)

        // Disabled switch -> empty list silently, zero model calls.
        status.value = NanoStatus.AVAILABLE
        enabled.value = false
        assertEquals(emptyList<String>(), generator.suggest(entries, emptyList()))
        assertEquals(0, model.calls)

        // Available + enabled -> generates once and caches for instant peek & repeat calls.
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
                            morning = "周五早，听听宇多田光",
                            afternoon = "午后可以再听点怀旧的",
                            evening = "晚上放松，听点舒服的",
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
        assertEquals("周五早，听听宇多田光", store.cachedFor(HomeGreetingSlot.MORNING, "profile-typed"))
        assertEquals("午后可以再听点怀旧的", store.cachedFor(HomeGreetingSlot.AFTERNOON, "profile-typed"))

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
