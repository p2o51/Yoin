package com.gpo.yoin.ui.component

import com.gpo.yoin.BuildConfig
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiException
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiFailure
import java.io.IOException
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
