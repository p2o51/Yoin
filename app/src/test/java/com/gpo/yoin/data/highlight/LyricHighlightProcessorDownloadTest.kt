package com.gpo.yoin.data.highlight

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Download model, from Yoin Labs and the diagnostics page: a download that ends without the model leaves AICore's
 * reason behind, so the page can say why.
 *
 * The processor works on its own IO scope, so these wait in real time (runBlocking, not runTest's virtual clock).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LyricHighlightProcessorDownloadTest {

    private class FakeModel : LyricHighlightModel {
        var status = NanoStatus.DOWNLOADABLE

        /** What the next download ends with: AICore's refusal, or null for the model arriving. */
        var refusal: String? = null

        override var downloadFailure: String? = null
            private set

        override suspend fun status(): NanoStatus = status

        override suspend fun baseModelName(): String? = null

        override suspend fun download(): Boolean {
            downloadFailure = refusal
            if (refusal == null) status = NanoStatus.AVAILABLE
            return refusal == null
        }

        override suspend fun answer(prompt: String): NanoAnswer = NanoAnswer.Failed(null)
    }

    private val model = FakeModel()

    private val processor = LyricHighlightProcessor(
        context = ApplicationProvider.getApplicationContext(),
        repository = mockk(),
        dao = mockk(),
        albumRatingDao = mockk(),
        model = model,
        currentTrack = { null },
        profileStartupDone = MutableStateFlow(true),
    )

    @Test
    fun should_keepAiCoresReason_when_theDownloadFails() = runBlocking {
        model.refusal = "ErrorCode 11 NOT_ENOUGH_DISK_SPACE"

        download()

        assertEquals("ErrorCode 11 NOT_ENOUGH_DISK_SPACE", processor.downloadFailure.value)
        // The check that follows still says DOWNLOADABLE: the reason stays for the page to show.
        awaitStatus(NanoStatus.DOWNLOADABLE)
        assertEquals("ErrorCode 11 NOT_ENOUGH_DISK_SPACE", processor.downloadFailure.value)
    }

    @Test
    fun should_dropTheReason_when_theNextDownloadBringsTheModel() = runBlocking {
        model.refusal = "ErrorCode 11 NOT_ENOUGH_DISK_SPACE"
        download()
        awaitStatus(NanoStatus.DOWNLOADABLE)

        model.refusal = null
        download()

        assertNull(processor.downloadFailure.value)
    }

    @Test
    fun should_dropTheReason_when_aLaterCheckFindsTheModelReady() = runBlocking {
        model.refusal = "ErrorCode 11 NOT_ENOUGH_DISK_SPACE"
        download()
        awaitStatus(NanoStatus.DOWNLOADABLE)

        // AICore carried on by itself.
        model.status = NanoStatus.AVAILABLE
        processor.checkNow()

        assertNull(withTimeout(TIMEOUT_MS) { processor.downloadFailure.first { it == null } })
    }

    /** Starts the download and waits for it to end ([LyricHighlightProcessor.downloading] is raised at once). */
    private suspend fun download() {
        processor.downloadNow()
        withTimeout(TIMEOUT_MS) { processor.downloading.first { !it } }
    }

    private suspend fun awaitStatus(status: NanoStatus) {
        withTimeout(TIMEOUT_MS) { processor.status.first { it == status } }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
