package com.gpo.yoin.data.repository

import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicMetadata
import com.gpo.yoin.data.source.MusicPlayback
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.MusicWriteActions
import io.mockk.every
import io.mockk.mockk
import io.mockk.coEvery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoinRepositoryTest {
    private val library = mockk<MusicLibrary>()
    private val source = mockk<MusicSource>()
    private val database = mockk<YoinDatabase>(relaxed = true)

    private val repository = YoinRepository(
        activeSource = MutableStateFlow(source),
        database = database,
    )

    init {
        every { source.library() } returns library
        every { source.metadata() } returns mockk<MusicMetadata>()
        every { source.writeActions() } returns mockk<MusicWriteActions>()
        every { source.playback() } returns mockk<MusicPlayback>()
    }

    @Test
    fun should_returnTrue_when_pingSucceeds() = runTest {
        coEvery { library.ping() } returns true

        assertTrue(repository.testConnection())
    }

    @Test
    fun should_throwSubsonicException_when_pingReturnsAuthError() = runTest {
        coEvery { library.ping() } throws SubsonicException(
            code = 40,
            message = "Wrong username or password",
        )

        val error = try {
            repository.testConnection()
            null
        } catch (e: SubsonicException) {
            e
        }

        val subsonicError = requireNotNull(error)
        assertEquals(40, subsonicError.code)
        assertEquals("Wrong username or password", subsonicError.message)
    }
}
