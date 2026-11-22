package com.gpo.yoin.data.highlight

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.local.SongAboutEntry
import java.time.ZoneOffset
import java.util.Locale
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
}
