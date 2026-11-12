package com.gpo.yoin.data.highlight

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** On-device model availability, as the lyric highlights care about it. */
enum class NanoStatus { AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }

/** One answer from the on-device model, with AICore's refusals spelled out. */
sealed interface NanoAnswer {
    data class Text(val text: String) : NanoAnswer

    /** Too many requests just now (ErrorCode.BUSY): back off. */
    data object Busy : NanoAnswer

    /** The app's long-term battery quota is used up: stop for the day. */
    data object QuotaExhausted : NanoAnswer

    /** Called while Yoin wasn't the top app: stop this visit. */
    data object NotForeground : NanoAnswer

    /** Anything else; not the song's fault, so it isn't recorded as unusable. */
    data class Failed(val code: Int?) : NanoAnswer
}

/** The model seam, so [LyricHighlightProcessor] is testable without AICore. */
interface LyricHighlightModel {
    suspend fun status(): NanoStatus

    /** Why the last [status] said UNAVAILABLE, when AICore gave a reason (shown in Yoin Labs). */
    val unavailableReason: String? get() = null

    suspend fun baseModelName(): String?

    /** Fetches the model when [status] says DOWNLOADABLE; true once it is on the device. */
    suspend fun download(): Boolean

    suspend fun answer(prompt: String): NanoAnswer
}

/**
 * Gemini Nano through ML Kit GenAI's Prompt API (AICore). Only works on supported devices (Pixel 9 and later,
 * Galaxy S26, Z Fold8 / Flip8, …) and only while Yoin is the top foreground app; see
 * [LyricHighlightProcessor] for when it is called.
 *
 * The client is created on first use and kept; [close] releases it. One request at a time.
 */
class GeminiNanoLyricModel : LyricHighlightModel {
    private val lock = Mutex()
    private var client: GenerativeModel? = null

    @Volatile
    override var unavailableReason: String? = null
        private set

    private fun client(): GenerativeModel = client ?: Generation.getClient(
        generationConfig {
            // Accuracy over speed: the answer is ~15 tokens, so FULL costs little more than FAST.
            modelConfig = modelConfig { preference = ModelPreference.FULL }
        },
    ).also { client = it }

    override suspend fun status(): NanoStatus = lock.withLock {
        try {
            val raw = client().checkStatus()
            unavailableReason = if (raw == FeatureStatus.UNAVAILABLE) "FeatureStatus.UNAVAILABLE" else null
            Log.i(TAG, "Gemini Nano status: $raw")
            when (raw) {
                FeatureStatus.AVAILABLE -> NanoStatus.AVAILABLE
                FeatureStatus.DOWNLOADABLE -> NanoStatus.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> NanoStatus.DOWNLOADING
                else -> NanoStatus.UNAVAILABLE
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // No AICore at all (most devices) throws rather than answering UNAVAILABLE. Throwable, not Exception:
            // a linkage error here used to end the check silently, so Home never learned anything (11-09).
            val reason = (e as? GenAiException)?.let { "GenAiException ${it.errorCode}" } ?: e.javaClass.simpleName
            unavailableReason = listOfNotNull(reason, e.message?.take(120)).joinToString(": ")
            Log.i(TAG, "Gemini Nano unavailable: $unavailableReason")
            // A client that failed to set up is not reused: the next check builds a fresh one.
            runCatching { client?.close() }
            client = null
            NanoStatus.UNAVAILABLE
        }
    }

    override suspend fun download(): Boolean = lock.withLock {
        try {
            // AICore keeps the download going on its side; this only waits for the outcome.
            val end = client().download().first { it is DownloadStatus.DownloadCompleted || it is DownloadStatus.DownloadFailed }
            if (end is DownloadStatus.DownloadFailed) Log.i(TAG, "Gemini Nano download failed: ${end.e.errorCode}")
            end is DownloadStatus.DownloadCompleted
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.i(TAG, "Gemini Nano download failed: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    override suspend fun baseModelName(): String? = lock.withLock {
        try {
            client().getBaseModelName()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun answer(prompt: String): NanoAnswer = lock.withLock {
        try {
            val response = client().generateContent(
                generateContentRequest(TextPart(prompt)) {
                    temperature = 0.2f
                    topK = 16
                    candidateCount = 1
                    maxOutputTokens = LyricHighlightPrompt.MAX_OUTPUT_TOKENS
                },
            )
            val text = response.candidates.firstOrNull()?.text
            if (text.isNullOrBlank()) NanoAnswer.Failed(null) else NanoAnswer.Text(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenAiException) {
            when (e.errorCode) {
                GenAiException.ErrorCode.BUSY -> NanoAnswer.Busy
                GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> NanoAnswer.QuotaExhausted
                GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> NanoAnswer.NotForeground
                else -> {
                    Log.w(TAG, "Gemini Nano failed: ${e.errorCode} ${e.message}")
                    NanoAnswer.Failed(e.errorCode)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gemini Nano failed", e)
            NanoAnswer.Failed(null)
        }
    }

    fun close() {
        client?.close()
        client = null
    }

    private companion object {
        const val TAG = "GeminiNanoLyric"
    }
}
