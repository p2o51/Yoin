package com.gpo.yoin.data.remote.applemusic

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun should_sendUserTokenToCatalogRelationship_when_confirmingLibraryMembership() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"data":[{"id":"i.saved","type":"library-songs"}]}""")
        )
        assertEquals("i.saved", client.librarySongId("jp", "123"))
        val request = server.takeRequest()
        assertEquals("/v1/catalog/jp/songs/123/library", request.path)
        assertEquals("Bearer developer", request.getHeader("Authorization"))
        assertEquals("user", request.getHeader("Music-User-Token"))
    }

    @Test
    fun should_onlyReportAbsentFromCompleteResponse_when_checkingLibraryMembership() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        assertNull(client.librarySongId("jp", "123"))
        for (body in listOf("{}", """{"data":[{"id":"123","type":"songs"}]}""", """{"data":["invalid-resource"]}""")) {
            server.enqueue(MockResponse().setBody(body))
            assertTrue(runCatching { client.librarySongId("jp", "123") }.isFailure)
        }
    }

    @Test
    fun should_requireUserAuthorization_when_confirmingLibraryMembership() = runTest {
        val unauthorized = AppleMusicApiClient({ "developer" }, { null }, baseUrl = server.url("/"))
        val failure = runCatching { unauthorized.librarySongId("jp", "123") }.exceptionOrNull()
        assertTrue(failure is AppleMusicApiException)
        assertEquals(AppleMusicApiFailure.UserAuthorizationRequired, (failure as AppleMusicApiException).failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun should_confirmExactCatalogSongBeforeReportingAbsence_when_libraryRelationshipReturns404() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"123","type":"songs"}]}"""))
        assertNull(client.librarySongId("jp", "123"))
        val relationship = server.takeRequest()
        assertEquals("/v1/catalog/jp/songs/123/library", relationship.path)
        assertEquals("user", relationship.getHeader("Music-User-Token"))
        val catalog = server.takeRequest()
        assertEquals("/v1/catalog/jp/songs/123", catalog.path)
        assertNull(catalog.getHeader("Music-User-Token"))
    }

    @Test
    fun should_reportStatusAppleCodeAndMaskedRequest_when_appleRefuses() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"errors":[{"id":"X","title":"Forbidden","detail":"Invalid authentication",""" +
                    """"status":"403","code":"40300"}]}"""
            )
        )
        val error = runCatching {
            client.resourcePage(
                listOf("v1", "me", "library", "playlists", "p.mine", "tracks"),
                personal = true,
                query = mapOf("include" to "catalog"),
            )
        }.exceptionOrNull() as AppleMusicApiException
        assertEquals(AppleMusicApiFailure.AccessDenied, error.failure)
        assertEquals(403, error.status)
        assertEquals("40300", error.appleCode)
        // The user's own library id is masked; nothing of the error's prose is kept.
        assertEquals("/v1/me/library/playlists/{id}/tracks include=catalog", error.request)
    }

    @Test
    fun should_keepCatalogIds_when_describingACatalogRequest() {
        assertEquals(
            "/v1/catalog/jp/songs/6805970194",
            diagnosticRequest("https://api.music.apple.com/v1/catalog/jp/songs/6805970194".toHttpUrl()),
        )
    }

    @Test
    fun should_leaveAppleCodeOut_when_bodyIsNotAppleError() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("<html>Server error</html>"))
        val error = runCatching { client.resourcePage(listOf("v1", "catalog", "jp", "songs", "1"), false) }
            .exceptionOrNull() as AppleMusicApiException
        assertEquals(500, error.status)
        assertNull(error.appleCode)
    }

    @Test
    fun should_propagateMissingCatalogSong_when_libraryRelationshipAndCatalogReturn404() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        val error = runCatching { client.librarySongId("jp", "123") }.exceptionOrNull()
        assertTrue(error is AppleMusicApiException)
        assertEquals(AppleMusicApiFailure.Http(404), (error as AppleMusicApiException).failure)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun should_preserveAccessFailureWithoutCatalogFallback_when_relationshipIsForbidden() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        val error = runCatching { client.librarySongId("jp", "123") }.exceptionOrNull()
        assertTrue(error is AppleMusicApiException)
        assertEquals(AppleMusicApiFailure.AccessDenied, (error as AppleMusicApiException).failure)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun should_preserveCatalogAccessFailure_when_missingRelationshipNeedsCatalogConfirmation() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(403))
        val error = runCatching { client.librarySongId("jp", "123") }.exceptionOrNull()
        assertTrue(error is AppleMusicApiException)
        assertEquals(AppleMusicApiFailure.AccessDenied, (error as AppleMusicApiException).failure)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun should_rejectMismatchedCatalogIdentity_when_confirmingMissingLibraryRelationship() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"456","type":"songs"}]}"""))
        val error = runCatching { client.librarySongId("jp", "123") }.exceptionOrNull()
        assertEquals("Apple Music returned no matching catalog song", error?.message)
    }

    @Test
    fun should_notExposeResponseContent_when_apiReturnsInvalidJson() = runTest {
        server.enqueue(MockResponse().setBody("sensitive-response-content"))
        val error = runCatching { client.librarySongId("jp", "123") }.exceptionOrNull()
        assertEquals("Apple Music returned an invalid JSON response", error?.message)
        assertNull(error?.cause)
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
    fun should_removeOnlyExcludedQueryParameters_when_followingAResourcePageLink() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        client.resourcePage(
            segments = listOf("v1", "me", "library", "artists"),
            personal = true,
            query = mapOf("limit" to "100", "include" to "catalog", "l" to "en-US"),
            next = "/v1/me/library/artists?offset=100&limit=50&include=catalog&%69nclude=albums&cursor=a%2Bb%2Fc%3D",
            excludedQueryParameters = setOf("include")
        )
        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertEquals("/v1/me/library/artists", url.encodedPath)
        assertEquals("100", url.queryParameter("offset"))
        assertEquals(listOf("50"), url.queryParameterValues("limit"))
        assertEquals("a+b/c=", url.queryParameter("cursor"))
        assertEquals("en-US", url.queryParameter("l"))
        assertFalse("include" in url.queryParameterNames)
        assertEquals("user", request.getHeader("Music-User-Token"))
    }

    @Test
    fun should_rejectInvalidResourcePaginationBeforeSendingTokens_when_excludingQueryParameters() = runTest {
        val path = "/v1/me/library/artists?offset=100&include=catalog"
        val invalidLinks = listOf(
            "https://untrusted.example$path",
            server.url("/v1/me/library/albums?offset=100&include=catalog").toString(),
            server.url(path).newBuilder().port(server.port + 1).build().toString(),
            server.url(path).newBuilder().username("unexpected").build().toString(),
            server.url(path).newBuilder().password("unexpected").build().toString()
        )
        for (next in invalidLinks) {
            val error = runCatching {
                client.resourcePage(
                    segments = listOf("v1", "me", "library", "artists"),
                    personal = true,
                    query = mapOf("limit" to "100"),
                    next = next,
                    excludedQueryParameters = setOf("include")
                )
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        }
        assertEquals(0, server.requestCount)
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
    fun should_keepMembershipSeparateFromFavorite_when_catalogHasLibraryRelationship() {
        val catalog = song(
            """{
              "id":"123","type":"songs","attributes":{"name":"Title"},
              "relationships":{"library":{"data":[{"id":"i.saved","type":"library-songs"}]}}
            }"""
        )
        assertEquals("i.saved", catalog.toTrack().extras["appleMusicLibraryId"])
        assertEquals("123", catalog.toTrack().id.rawId)
        assertFalse(catalog.toTrack().isStarred)
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

    @Test
    fun should_lookUpIsrcsInBatchesAndKeyByEachSongsIsrc_when_resolvingCatalogSongs() = runTest {
        val codes = (0 until 26).map { "USQE9210" + it.toString().padStart(4, '0') }
        server.enqueue(
            MockResponse().setBody(
                """{"data":[""" +
                    """{"id":"1","type":"songs","attributes":{"name":"Entropy","isrc":"usqe92100000"}},""" +
                    """{"id":"2","type":"songs","attributes":{"name":"Entropy","isrc":"USQE92100000"}},""" +
                    """{"id":"3","type":"songs","attributes":{"name":"Other","isrc":"ZZZZ99999999"}}""" +
                    """]}"""
            )
        )
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))

        val found = client.catalogSongsByIsrc("jp", codes + "not-an-isrc")

        assertEquals(listOf("1", "2"), found["USQE92100000"]!!.map { it.catalogId })
        assertEquals(setOf("USQE92100000"), found.keys)
        val first = server.takeRequest()
        assertEquals("/v1/catalog/jp/songs", first.requestUrl!!.encodedPath)
        assertEquals(codes.take(25).joinToString(","), first.requestUrl!!.queryParameter("filter[isrc]"))
        assertNull(first.getHeader("Music-User-Token"))
        assertEquals(codes.drop(25).single(), server.takeRequest().requestUrl!!.queryParameter("filter[isrc]"))
    }

    @Test
    fun should_carryIsrcIntoTrackExtras_when_catalogSongNamesOne() {
        val track = song("""{"id":"1","type":"songs","attributes":{"name":"Entropy","isrc":" usqe92100257 "}}""")
            .toTrack()
        assertEquals("USQE92100257", track.extras[com.gpo.yoin.data.model.Track.EXTRA_ISRC])
    }

    @Test
    fun should_postNameDescriptionAndTracksInOrder_when_creatingLibraryPlaylist() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"data":[{"id":"p.new","type":"library-playlists","attributes":{"name":"Mix","canEdit":true}}]}"""
            )
        )

        val created = client.createLibraryPlaylist(
            "Mix",
            "For the road",
            listOf(AppleMusicTrackRef("123", "songs"), AppleMusicTrackRef("i.abc", "library-songs"))
        )

        assertEquals("p.new", created["id"]!!.toString().trim('"'))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/me/library/playlists", request.path)
        assertEquals("user", request.getHeader("Music-User-Token"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals(
            Json.parseToJsonElement(
                """{"attributes":{"name":"Mix","description":"For the road"},""" +
                    """"relationships":{"tracks":{"data":[{"id":"123","type":"songs"},""" +
                    """{"id":"i.abc","type":"library-songs"}]}}}"""
            ),
            Json.parseToJsonElement(request.body.readUtf8())
        )
    }

    @Test
    fun should_omitEmptyRelationshipsAndDescription_when_creatingEmptyPlaylist() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody("""{"data":[{"id":"p.new","type":"library-playlists"}]}""")
        )
        client.createLibraryPlaylist("Mix", " ", emptyList())
        assertEquals(
            Json.parseToJsonElement("""{"attributes":{"name":"Mix"}}"""),
            Json.parseToJsonElement(server.takeRequest().body.readUtf8())
        )
    }

    @Test
    fun should_appendTracksAsDataArray_when_addingToLibraryPlaylist() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        client.addTracksToLibraryPlaylist("p.new", listOf(AppleMusicTrackRef("123", "songs")))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/me/library/playlists/p.new/tracks", request.path)
        assertEquals(
            Json.parseToJsonElement("""{"data":[{"id":"123","type":"songs"}]}"""),
            Json.parseToJsonElement(request.body.readUtf8())
        )
    }

    @Test
    fun should_fail_when_appleRefusesPlaylistCreation() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        val failure = runCatching { client.createLibraryPlaylist("Mix", null, emptyList()) }.exceptionOrNull()
        assertEquals(AppleMusicApiFailure.AccessDenied, (failure as AppleMusicApiException).failure)
    }

    private fun song(json: String) = AppleMusicSong.fromJson(Json.parseToJsonElement(json).jsonObject)
}
