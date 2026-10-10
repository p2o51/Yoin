package com.gpo.yoin.data.repository

import com.gpo.yoin.data.source.MusicSource
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YoinRepositoryActiveSourceTest {

    // What ProfileManager builds asynchronously at launch: null until then.
    private val activeSource = MutableStateFlow<MusicSource?>(null)

    private val repository = YoinRepository(
        activeSource = activeSource,
        activeProfileId = MutableStateFlow("profile-a"),
        database = mockk(relaxed = true),
        geminiService = mockk(relaxed = true),
        songAboutEntryDao = mockk(relaxed = true),
        geminiConfigDao = mockk(relaxed = true),
        lyricsCacheDao = mockk(relaxed = true),
        lyricsTranslationCacheDao = mockk(relaxed = true),
        songNoteDao = mockk(relaxed = true),
        albumNoteDao = mockk(relaxed = true),
        albumRatingDao = mockk(relaxed = true),
        memoryCopyCacheDao = mockk(relaxed = true),
        neoDbSyncService = mockk(relaxed = true)
    )

    @Test
    fun should_returnNull_when_noSourceArrivesBeforeTimeout() = runTest {
        val waiting = async { repository.awaitActiveSource(timeoutMs = 4_000L) }

        advanceTimeBy(3_999L)
        assertFalse(waiting.isCompleted)

        advanceTimeBy(2L)
        assertTrue(waiting.isCompleted)
        assertNull(waiting.await())
    }

    @Test
    fun should_returnSource_when_itArrivesBeforeTimeout() = runTest {
        val source = mockk<MusicSource>()
        val waiting = async { repository.awaitActiveSource(timeoutMs = 4_000L) }

        advanceTimeBy(1_000L)
        assertFalse(waiting.isCompleted)

        activeSource.value = source
        runCurrent()

        assertTrue(waiting.isCompleted)
        assertSame(source, waiting.await())
    }

    @Test
    fun should_returnAtOnce_when_sourceIsAlreadyActive() = runTest {
        val source = mockk<MusicSource>()
        activeSource.value = source

        assertSame(source, repository.awaitActiveSource(timeoutMs = 4_000L))
    }
}
