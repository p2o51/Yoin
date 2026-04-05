package com.gpo.yoin.data.repository

import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.SubsonicApi
import com.gpo.yoin.data.remote.SubsonicError
import com.gpo.yoin.data.remote.SubsonicResponse
import com.gpo.yoin.data.remote.SubsonicResponseBody
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoinRepositoryTest {
    private val api = mockk<SubsonicApi>()
    private val database = mockk<YoinDatabase>(relaxed = true)
    private val credentials =
        ServerCredentials(
            serverUrl = "https://music.example.com",
            username = "demo",
            password = "secret",
        )

    private val repository =
        YoinRepository(
            apiProvider = { api },
            database = database,
            credentials = { credentials },
        )

    @Test
    fun should_returnTrue_when_pingSucceeds() = runTest {
        coEvery { api.ping() } returns
            SubsonicResponse(
                subsonicResponse =
                    SubsonicResponseBody(
                        status = "ok",
                        version = "1.16.1",
                    ),
            )

        assertTrue(repository.testConnection())
    }

    @Test
    fun should_throwSubsonicException_when_pingReturnsAuthError() = runTest {
        coEvery { api.ping() } returns
            SubsonicResponse(
                subsonicResponse =
                    SubsonicResponseBody(
                        status = "failed",
                        version = "1.16.1",
                        error =
                            SubsonicError(
                                code = 40,
                                message = "Wrong username or password",
                            ),
                    ),
            )

        val error =
            try {
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
