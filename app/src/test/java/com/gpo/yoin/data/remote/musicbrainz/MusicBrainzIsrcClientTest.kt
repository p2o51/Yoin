package com.gpo.yoin.data.remote.musicbrainz

import com.gpo.yoin.data.source.TrackNames
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** MusicBrainz knows Spotify's "Ferries wheel" (ISRC TWD950519106) as 摩天輪 by 田馥甄. */
class MusicBrainzIsrcClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun should_readReleasedNames_andKeepThem() = runTest {
        server.enqueue(MockResponse().setBody(FERRIS_WHEEL))
        val client = client()

        val names = client.names("twd950519106", durationMs = 244_800)
        val again = client.names("TWD950519106", durationMs = 244_800)

        assertEquals(TrackNames("摩天輪", "田馥甄"), names)
        assertEquals(names, again)
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("/ws/2/isrc/TWD950519106", request.requestUrl?.encodedPath)
        assertEquals("artist-credits", request.requestUrl?.queryParameter("inc"))
        assertEquals(MusicBrainzIsrcClient.USER_AGENT, request.getHeader("User-Agent"))
    }

    @Test
    fun should_beNull_when_codeIsUnknown() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"Not Found"}"""))

        assertNull(client().names("TWD950519106", durationMs = null))
    }

    @Test
    fun should_skipRecordings_ofAnotherLength() {
        val parsed = client().parse(FERRIS_WHEEL, durationMs = 300_000)

        assertNull(parsed)
    }

    @Test
    fun should_notAsk_when_codeIsMalformed() = runTest {
        assertNull(client().names("not-an-isrc", durationMs = null))
        assertEquals(0, server.requestCount)
    }

    private fun client() = MusicBrainzIsrcClient(
        client = OkHttpClient(),
        baseUrl = server.url("/"),
        clock = { 0L },
    )

    private companion object {
        val FERRIS_WHEEL = """
            {"recordings":[{"first-release-date":"2005-09-28","id":"4458104d-1689-491e-980d-45999b7c954d",
            "length":244800,"title":"摩天輪","video":false,"disambiguation":"",
            "artist-credit":[{"artist":{"name":"田馥甄","sort-name":"Tian, Hebe",
            "id":"0827063c-b93d-48bd-abc4-04ce2fbc45d7","country":"TW","type":"Person"},
            "joinphrase":"","name":"田馥甄"}]}],"isrc":"TWD950519106"}
        """.trimIndent()
    }
}
