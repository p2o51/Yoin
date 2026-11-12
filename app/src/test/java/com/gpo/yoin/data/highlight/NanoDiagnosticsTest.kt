package com.gpo.yoin.data.highlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NanoDiagnosticsTest {
    private fun report(
        stableFull: NanoStatus = NanoStatus.UNAVAILABLE,
        stableFast: NanoStatus = NanoStatus.UNAVAILABLE,
        previewFull: NanoStatus = NanoStatus.UNAVAILABLE,
        previewFast: NanoStatus = NanoStatus.UNAVAILABLE,
    ) = NanoDiagnostics.Report(
        device = "Google Pixel 11 Pro (blazer)",
        android = "17 (API 37), CP41",
        app = "0.5.9 (24) release",
        components = listOf(
            NanoDiagnostics.Component("AICore", "com.google.android.aicore", "1.0 (42)"),
            NanoDiagnostics.Component("Private Compute Services", "com.google.android.as.oss", null),
        ),
        probes = listOf(
            NanoDiagnostics.Probe(
                stage = "Stable",
                variant = "Full",
                status = stableFull,
                baseModel = "nano-v4".takeIf { stableFull == NanoStatus.AVAILABLE },
            ),
            NanoDiagnostics.Probe(
                stage = "Stable",
                variant = "Fast",
                status = stableFast,
                error = "ErrorCode 8 NOT_SUPPORTED".takeIf { stableFast == NanoStatus.UNAVAILABLE },
            ),
            NanoDiagnostics.Probe("Preview", "Full", previewFull),
            NanoDiagnostics.Probe("Preview", "Fast", previewFast),
        ),
    )

    @Test
    fun should_offerDownload_when_stableVariantCanBeFetchedAndNoneIsReady() {
        assertTrue(report(stableFull = NanoStatus.DOWNLOADABLE).canDownload)
        assertTrue(report(stableFast = NanoStatus.DOWNLOADABLE).canDownload)
        // A download in flight keeps the row (it shows the spinner).
        assertTrue(report(stableFull = NanoStatus.DOWNLOADING).canDownload)
    }

    @Test
    fun should_notOfferDownload_when_onlyPreviewCanBeFetched() {
        val report = report(previewFull = NanoStatus.DOWNLOADABLE, previewFast = NanoStatus.DOWNLOADING)
        assertFalse(report.canDownload)
        assertFalse(report.anyAvailable)
    }

    @Test
    fun should_notOfferDownload_when_aStableVariantIsAlreadyReady() {
        // The lyric cards' model keeps to the ready variant, so its download would fetch nothing new.
        assertFalse(report(stableFull = NanoStatus.AVAILABLE, stableFast = NanoStatus.DOWNLOADABLE).canDownload)
        assertFalse(report(stableFull = NanoStatus.DOWNLOADABLE, stableFast = NanoStatus.AVAILABLE).canDownload)
    }

    @Test
    fun should_offerDownload_when_onlyPreviewIsReady() {
        // Preview isn't what the lyric cards use: a Stable variant that can be fetched is still worth fetching.
        val report = report(stableFull = NanoStatus.DOWNLOADABLE, previewFull = NanoStatus.AVAILABLE)
        assertTrue(report.canDownload)
        assertTrue(report.anyAvailable)
    }

    @Test
    fun should_showTheDownloadRunning_when_aStableVariantIsBeingFetched() {
        // Whoever started it: the row, the lyric cards on Wi-Fi, or a download that outlived the app.
        assertTrue(report(stableFull = NanoStatus.DOWNLOADING).downloadInFlight)
        assertTrue(report(stableFull = NanoStatus.DOWNLOADABLE, stableFast = NanoStatus.DOWNLOADING).downloadInFlight)
        assertFalse(report(stableFull = NanoStatus.DOWNLOADABLE).downloadInFlight)
        // Preview isn't what the row fetches.
        assertFalse(report(stableFull = NanoStatus.DOWNLOADABLE, previewFull = NanoStatus.DOWNLOADING).downloadInFlight)
    }

    @Test
    fun should_sayWhyTheDownloadFailed_when_copiedAfterAFailedDownload() {
        val report = report(stableFull = NanoStatus.DOWNLOADABLE)
        val text = report.asText(downloadFailure = "ErrorCode 11 NOT_ENOUGH_DISK_SPACE")
        assertTrue(text.endsWith("Preview · Fast: UNAVAILABLE\nDownload failed: ErrorCode 11 NOT_ENOUGH_DISK_SPACE"))
        assertFalse(report.asText().contains("Download failed"))
    }

    @Test
    fun should_listEveryVariantWithItsModelOrError_when_copiedAsText() {
        val text = report(stableFull = NanoStatus.AVAILABLE).asText()
        assertEquals(
            """
            Gemini Nano diagnostics
            Device: Google Pixel 11 Pro (blazer)
            Android: 17 (API 37), CP41
            Yoin: 0.5.9 (24) release
            AICore (com.google.android.aicore): 1.0 (42)
            Private Compute Services (com.google.android.as.oss): not installed
            Stable · Full: AVAILABLE [nano-v4]
            Stable · Fast: UNAVAILABLE — ErrorCode 8 NOT_SUPPORTED
            Preview · Full: UNAVAILABLE
            Preview · Fast: UNAVAILABLE
            """.trimIndent(),
            text,
        )
    }
}
