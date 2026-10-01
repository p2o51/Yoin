package com.gpo.yoin.data.remote.applemusic

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AppleMusicApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: AppleMusicApiClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        client = AppleMusicApiClient({ "developer" }, { "user" }, baseUrl = server.url("/"))
    }

    @After
    fun cleanup() = server.shutdown()

    @Test
    fun should_sendBothTokens_when_fetchingStorefront() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"jp"}]}"""))
        assertEquals("jp", client.storefront())
        val request = server.takeRequest()
        assertEquals("/v1/me/storefront", request.path)
        assertEquals("Bearer developer", request.getHeader("Authorization"))
        assertEquals("user", request.getHeader("Music-User-Token"))
    }

    @Test
    fun should_omitUserTokenAndEncodeQuery_when_searchingCatalog() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":{"songs":{"data":[]}}}"""))
        client.searchSongs("jp", "A & B + 日本")
        val request = server.takeRequest()
        assertNull(request.getHeader("Music-User-Token"))
        assertEquals("A & B + 日本", request.requestUrl!!.queryParameter("term"))
        assertEquals("songs", request.requestUrl!!.queryParameter("types"))
    }

    @Test
    fun should_keepMutationPending_when_serverAcceptsWithoutBody() = runTest {
        server.enqueue(MockResponse().setResponseCode(202))
        assertEquals(AppleMusicLibraryMutation.AcceptedPendingConfirmation, client.addSongToLibrary("123"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("123", request.requestUrl!!.queryParameter("ids[songs]"))
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun should_rejectForeignPagination_when_serverReturnsExternalNext() = runTest {
        try {
            client.librarySongs("https://other.example/v1/me/library/songs")
            fail("Should reject external pagination")
        } catch (_: IllegalArgumentException) {
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun should_notFollowRedirectOrLeakTokens_when_apiRedirects() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/unexpected"))
        try {
            client.storefront()
            fail("Should reject redirect")
        } catch (error: AppleMusicApiException) {
            assertEquals(AppleMusicApiFailure.Http(302), error.failure)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun should_distinguishDeveloperAndAccessErrors_when_authFails() = runTest {
        for ((status, expected) in listOf(
            401 to AppleMusicApiFailure.DeveloperTokenRejected,
            403 to AppleMusicApiFailure.AccessDenied,
            429 to AppleMusicApiFailure.RateLimited
        )) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("sensitive response"))
            try {
                client.storefront()
                fail("Should fail")
            } catch (error: AppleMusicApiException) {
                assertEquals(expected, error.failure)
                assertFalse(error.message!!.contains("sensitive"))
            }
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun should_preserveNextLink_when_libraryHasMoreSongs() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[],"next":"/v1/me/library/songs?offset=25"}"""))
        val page = client.librarySongs()
        assertEquals("/v1/me/library/songs?offset=25", page.next)
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        assertNull(client.librarySongs(page.next).next)
        server.takeRequest()
        assertEquals("25", server.takeRequest().requestUrl!!.queryParameter("offset"))
    }

    @Test
    fun should_shareCanonicalIdentity_when_libraryHasCatalogRelationship() {
        val library =
            song(
                """{
              "id":"i.abc","type":"library-songs",
              "relationships":{"catalog":{"data":[{"id":"123","type":"songs"}]}},
              "attributes":{"name":"Title"}
            }"""
            )
        val catalog = song("""{"id":"123","type":"songs","attributes":{"name":"Title"}}""")
        assertEquals(catalog.toTrack().id, library.toTrack().id)
        assertEquals("i.abc", library.libraryId)
        assertFalse(library.toTrack().isStarred)
    }

    @Test
    fun should_preserveLibraryIdentity_when_importHasNoCatalogMatch() {
        val imported =
            song(
                """{
              "id":"i.local","type":"library-songs",
              "attributes":{"name":"Title","playParams":{"id":"i.local","isLibrary":true}}
            }"""
            )
        assertNull(imported.catalogId)
        assertEquals("applemusic:library:i.local", imported.toTrack().id.toString())
    }

    private fun song(json: String) = AppleMusicSong.fromJson(Json.parseToJsonElement(json).jsonObject)
}
