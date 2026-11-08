package com.gpo.yoin.data.source.subsonic

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.Share
import com.gpo.yoin.data.remote.SharesResponse
import com.gpo.yoin.data.remote.SubsonicApi
import com.gpo.yoin.data.remote.SubsonicError
import com.gpo.yoin.data.remote.SubsonicResponse
import com.gpo.yoin.data.remote.SubsonicResponseBody
import com.gpo.yoin.data.repository.SubsonicException
import com.gpo.yoin.data.source.WebLinkKind
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

class SubsonicShareLinkTest {

    /** A SubsonicApi whose createShare answers with [answer]; every other call fails the test. */
    private class FakeApi(var answer: () -> SubsonicResponse) {
        val calls = mutableListOf<List<Any?>>()

        val api: SubsonicApi = Proxy.newProxyInstance(
            SubsonicApi::class.java.classLoader,
            arrayOf(SubsonicApi::class.java),
            InvocationHandler { _, method, args ->
                check(method.name == "createShare") { "unexpected call: ${method.name}" }
                // The last argument is the suspend continuation: returning a value resumes it
                // directly; a failure goes through it, since a proxy can't throw a checked exception.
                calls += args.orEmpty().dropLast(1)
                runCatching(answer).getOrElse { error ->
                    @Suppress("UNCHECKED_CAST")
                    (args.last() as Continuation<Any?>).resumeWith(Result.failure(error))
                    COROUTINE_SUSPENDED
                }
            },
        ) as SubsonicApi
    }

    private fun source(fake: FakeApi) = SubsonicMusicSource(
        credentials = ServerCredentials("https://music.example", "51", "secret"),
        apiFactory = { fake.api },
    )

    private fun ok(url: String) = SubsonicResponse(
        SubsonicResponseBody(status = "ok", shares = SharesResponse(listOf(Share(id = "s1", url = url)))),
    )

    private fun refused() = SubsonicResponse(
        SubsonicResponseBody(status = "failed", error = SubsonicError(code = 50, message = "Sharing is disabled")),
    )

    @Test
    fun should_returnServerShareUrl_when_albumIsShared() = runTest {
        val fake = FakeApi { ok("https://music.example/share/abc") }

        val link = source(fake).metadata()
            .createShareLink(WebLinkKind.Album, MediaId(MediaId.PROVIDER_SUBSONIC, "al-1"), "Album – Artist")

        assertEquals("https://music.example/share/abc", link)
        assertEquals(listOf(listOf(listOf("al-1"), "Album – Artist")), fake.calls)
    }

    @Test
    fun should_notAskServer_when_artistOrOtherProvider() = runTest {
        val fake = FakeApi { ok("https://music.example/share/abc") }
        val metadata = source(fake).metadata()

        assertNull(metadata.createShareLink(WebLinkKind.Artist, MediaId(MediaId.PROVIDER_SUBSONIC, "ar-1"), null))
        assertNull(metadata.createShareLink(WebLinkKind.Track, MediaId(MediaId.PROVIDER_SPOTIFY, "t-1"), null))
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun should_stopAsking_when_serverRefusedOnce() = runTest {
        val fake = FakeApi { refused() }
        val metadata = source(fake).metadata()
        val song = MediaId(MediaId.PROVIDER_SUBSONIC, "so-1")

        try {
            metadata.createShareLink(WebLinkKind.Track, song, null)
            fail("a refusal should throw the first time")
        } catch (expected: SubsonicException) {
            assertEquals(50, expected.code)
        }
        assertNull(metadata.createShareLink(WebLinkKind.Track, song, null))
        assertEquals(1, fake.calls.size)
    }

    @Test
    fun should_askAgain_when_networkFailed() = runTest {
        val fake = FakeApi { throw IOException("offline") }
        val metadata = source(fake).metadata()
        val song = MediaId(MediaId.PROVIDER_SUBSONIC, "so-1")

        try {
            metadata.createShareLink(WebLinkKind.Track, song, null)
            fail("a network failure should throw")
        } catch (_: IOException) {
        }
        fake.answer = { ok("https://music.example/share/def") }

        assertEquals("https://music.example/share/def", metadata.createShareLink(WebLinkKind.Track, song, null))
    }

    @Test
    fun should_askAgain_when_serverHiccuped() = runTest {
        val fake = FakeApi { throw HttpException(Response.error<Any>(503, "".toResponseBody())) }
        val metadata = source(fake).metadata()
        val song = MediaId(MediaId.PROVIDER_SUBSONIC, "so-1")

        try {
            metadata.createShareLink(WebLinkKind.Track, song, null)
            fail("a 503 should throw")
        } catch (_: HttpException) {
        }
        fake.answer = { ok("https://music.example/share/ghi") }

        assertEquals("https://music.example/share/ghi", metadata.createShareLink(WebLinkKind.Track, song, null))
    }
}
