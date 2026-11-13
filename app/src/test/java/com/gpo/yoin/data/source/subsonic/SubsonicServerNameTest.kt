package com.gpo.yoin.data.source.subsonic

import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.SubsonicApi
import com.gpo.yoin.data.remote.SubsonicError
import com.gpo.yoin.data.remote.SubsonicResponse
import com.gpo.yoin.data.remote.SubsonicResponseBody
import com.gpo.yoin.data.repository.SubsonicException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The account page's Test: one ping, answered with what the server calls itself. */
class SubsonicServerNameTest {

    private val api = mockk<SubsonicApi>()
    private val source = SubsonicMusicSource(
        credentials = ServerCredentials("https://music.example", "51", "secret"),
        apiFactory = { api }
    )

    private fun answers(body: SubsonicResponseBody) {
        coEvery { api.ping() } returns SubsonicResponse(body)
    }

    @Test
    fun should_answerWithTheServersName_when_anOpenSubsonicServerIsReached() = runTest {
        answers(SubsonicResponseBody(status = "ok", type = "navidrome", serverVersion = "0.64.2", openSubsonic = true))

        assertEquals("Navidrome 0.64.2", source.pingServerName())
        coVerify(exactly = 1) { api.ping() }
    }

    @Test
    fun should_answerWithNoName_when_aPlainSubsonicServerIsReached() = runTest {
        answers(SubsonicResponseBody(status = "ok", version = "1.16.1"))

        assertNull(source.pingServerName())
    }

    @Test
    fun should_failAsPingDoes_when_theServerRefusesTheSignIn() = runTest {
        answers(
            SubsonicResponseBody(
                status = "failed",
                type = "navidrome",
                serverVersion = "0.64.2",
                error = SubsonicError(code = 40, message = "Wrong username or password")
            )
        )

        val error = runCatching { source.pingServerName() }.exceptionOrNull()

        assertTrue(error is SubsonicException)
        assertEquals(40, (error as SubsonicException).code)
    }
}
