package com.gpo.yoin.ui.component

import com.gpo.yoin.BuildConfig
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiException
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiFailure
import com.gpo.yoin.data.source.spotify.SpotifyAuthException
import com.gpo.yoin.data.source.spotify.SpotifyClientIdKind
import com.gpo.yoin.data.source.spotify.SpotifyPlaylistObject
import com.gpo.yoin.data.source.spotify.SpotifyResponseDecodeException
import java.io.IOException
import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorDiagnosticsTest {
    private val now = Instant.parse("2026-10-16T13:41:03.250Z")
    private val device = "Android 17 (API 37), Google Pixel Tablet"

    @Test fun should_sayStatusCodeAndRequest_when_appleRefusedAndTheCatalogFailedToo() {
        val failure = AppleMusicApiException(
            AppleMusicApiFailure.AccessDenied,
            status = 403,
            request = "/v1/me/library/playlists/{id}",
            appleCode = "40300",
        )
        failure.addSuppressed(
            AppleMusicApiException(AppleMusicApiFailure.Http(404), 404, "/v1/catalog/jp/playlists/pl.u-shared")
        )
        assertEquals(
            """
            Yoin ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
            Android 17 (API 37), Google Pixel Tablet
            Apple Music AccessDenied, HTTP 403, Apple 40300
            GET /v1/me/library/playlists/{id}
            then Apple Music Http, HTTP 404
            GET /v1/catalog/jp/playlists/pl.u-shared
            2026-10-16T13:41:03Z
            """.trimIndent(),
            failure.diagnosticReport(now, device),
        )
    }

    @Test fun should_leaveTheMessageOut_when_itCouldQuoteAnAddress() {
        val report = IOException("unexpected end of stream on https://music.example/rest?t=secret")
            .diagnosticReport(now, device)
        assertEquals("IOException", report.lines()[2])
        assertFalse(report.contains("secret"))
    }

    @Test fun should_sayStatusReasonRequestAndClientId_when_spotifyRefused() {
        val failure = SpotifyAuthException(
            code = 403,
            message = "Spotify request failed: 403",
            request = "GET /v1/playlists/{id}/items",
            reason = "Forbidden",
            clientIdKind = SpotifyClientIdKind.Own,
        )
        assertEquals(
            listOf(
                "Spotify HTTP 403: Forbidden",
                "GET /v1/playlists/{id}/items",
                "Spotify client ID own",
            ),
            failure.diagnosticReport(now, device).lines().subList(2, 5),
        )
    }

    @Test fun should_sayUnknown_when_itIsNotKnownWhoseAppTheTokensBelongTo() {
        val failure = SpotifyAuthException(
            code = 500,
            message = "Spotify request failed: 500",
            request = "GET /v1/me",
        )
        assertEquals(
            listOf("Spotify HTTP 500", "GET /v1/me", "Spotify client ID unknown"),
            failure.diagnosticReport(now, device).lines().subList(2, 5),
        )
    }

    @Test fun should_keepTheOAuthLine_when_theTokenEndpointRefused() {
        val failure = SpotifyAuthException(code = 400, message = "refresh failed", oauthError = "invalid_grant")
        assertEquals("Spotify auth 400 invalid_grant", failure.diagnosticReport(now, device).lines()[2])
    }

    @Test fun should_sayWhereTheJsonStopped_when_aSpotifyResponseWouldNotDecode() {
        val decodeError = runCatching {
            Json.decodeFromString(
                SpotifyPlaylistObject.serializer(),
                """{"id":"pl","name":"secret name","images":[{"url":null}]}""",
            )
        }.exceptionOrNull() as SerializationException
        val report = SpotifyResponseDecodeException(
            request = "GET /v1/playlists/{id}",
            clientIdKind = SpotifyClientIdKind.BuiltIn,
            cause = decodeError,
        ).diagnosticReport(now, device)
        val lines = report.lines()

        assertEquals("Spotify response unreadable", lines[2])
        assertEquals("GET /v1/playlists/{id}", lines[3])
        assertEquals("Spotify client ID built-in", lines[4])
        assertTrue(lines[5], lines[5].startsWith("cause JsonDecodingException") && lines[5].endsWith("at $.images[0].url"))
        assertFalse(report.contains("secret"))
    }

    @Test fun should_nameTheMissingField_when_aRequiredOneIsAbsent() {
        val decodeError = runCatching {
            Json.decodeFromString(SpotifyPlaylistObject.serializer(), """{"name":"x"}""")
        }.exceptionOrNull() as SerializationException
        val line = decodeError.diagnosticReport(now, device).lines()[2]
        assertTrue(line, line.startsWith("MissingFieldException missing id"))
    }
}
