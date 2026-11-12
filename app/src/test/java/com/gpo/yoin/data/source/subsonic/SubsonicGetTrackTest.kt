package com.gpo.yoin.data.source.subsonic

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.Song
import com.gpo.yoin.data.remote.SubsonicApi
import com.gpo.yoin.data.remote.SubsonicError
import com.gpo.yoin.data.remote.SubsonicResponse
import com.gpo.yoin.data.remote.SubsonicResponseBody
import com.gpo.yoin.data.repository.SubsonicException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class SubsonicGetTrackTest {

    /** A SubsonicApi whose getSong answers with [answer]; every other call fails the test. */
    private fun source(answer: SubsonicResponse): SubsonicMusicSource {
        val api = Proxy.newProxyInstance(
            SubsonicApi::class.java.classLoader,
            arrayOf(SubsonicApi::class.java),
            InvocationHandler { _, method, _ ->
                check(method.name == "getSong") { "unexpected call: ${method.name}" }
                answer
            },
        ) as SubsonicApi
        return SubsonicMusicSource(
            credentials = ServerCredentials("https://music.example", "51", "secret"),
            apiFactory = { api },
        )
    }

    private fun failed(code: Int) = SubsonicResponse(
        SubsonicResponseBody(status = "failed", error = SubsonicError(code = code, message = "no")),
    )

    @Test
    fun should_returnTheSongWithItsAlbum_when_serverHasIt() = runTest {
        val song = Song(id = "s1", title = "Pulse", album = "Pulse EP", artist = "Regene", albumId = "al1")
        val source = source(SubsonicResponse(SubsonicResponseBody(status = "ok", song = song)))

        val track = source.library().getTrack(MediaId(MediaId.PROVIDER_SUBSONIC, "s1"))

        assertEquals("Pulse", track?.title)
        assertEquals("al1", track?.albumId?.rawId)
        assertEquals("Pulse EP", track?.album)
    }

    @Test
    fun should_returnNull_when_serverNoLongerHasTheSong() = runTest {
        // Navidrome and the others answer a missing id with error 70, not with an empty body.
        val source = source(failed(70))

        assertNull(source.library().getTrack(MediaId(MediaId.PROVIDER_SUBSONIC, "gone")))
    }

    @Test
    fun should_fail_when_serverRefusesForAnotherReason() = runTest {
        val source = source(failed(40))

        try {
            source.library().getTrack(MediaId(MediaId.PROVIDER_SUBSONIC, "s1"))
            fail("a wrong password is not a missing song")
        } catch (error: SubsonicException) {
            assertEquals(40, error.code)
        }
    }
}
