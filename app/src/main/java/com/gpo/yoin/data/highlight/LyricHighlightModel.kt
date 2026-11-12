package com.gpo.yoin.data.highlight

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
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

    /**
     * FULL first (accuracy over speed: the answer is ~15 tokens, so FULL costs little more than FAST). A phone that
     * only carries the FAST variant (some Galaxy Z Fold8 report nano-v4-fast only) falls back to it.
     */
    @Volatile
    private var preference: Int = ModelPreference.FULL

    private fun client(): GenerativeModel =
        client ?: nanoClient(ModelReleaseStage.STABLE, preference).also { client = it }

    override suspend fun status(): NanoStatus = lock.withLock {
        // A variant that is ready wins (FULL before FAST); otherwise the first one AICore can fetch, FULL first.
        val full = probe(ModelPreference.FULL)
        if (full == NanoStatus.AVAILABLE) return@withLock full
        val fullReason = unavailableReason
        val fast = probe(ModelPreference.FAST)
        if (fast == NanoStatus.AVAILABLE) return@withLock fast
        if (full != NanoStatus.UNAVAILABLE) {
            switchTo(ModelPreference.FULL)
            unavailableReason = null
            return@withLock full
        }
        if (fast != NanoStatus.UNAVAILABLE) return@withLock fast
        // Neither: report FULL's reason (the one a bug report needs first) and start from FULL next time.
        unavailableReason = fullReason ?: unavailableReason
        switchTo(ModelPreference.FULL)
        NanoStatus.UNAVAILABLE
    }

    private suspend fun probe(wanted: Int): NanoStatus {
        switchTo(wanted)
        return try {
            val raw = client().checkStatus()
            unavailableReason = if (raw == FeatureStatus.UNAVAILABLE) "FeatureStatus.UNAVAILABLE" else null
            Log.i(TAG, "Gemini Nano status (${preferenceName(wanted)}): $raw")
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
            unavailableReason = describeNanoError(e)
            Log.i(TAG, "Gemini Nano unavailable (${preferenceName(wanted)}): $unavailableReason")
            // A client that failed to set up is not reused: the next check builds a fresh one.
            runCatching { client?.close() }
            client = null
            NanoStatus.UNAVAILABLE
        }
    }

    private fun switchTo(wanted: Int) {
        if (preference == wanted) return
        runCatching { client?.close() }
        client = null
        preference = wanted
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

/** A Gemini Nano client for one model variant (Stable / Preview × Full / Fast). */
internal fun nanoClient(stage: Int, variant: Int): GenerativeModel =
    Generation.getClient(
        generationConfig {
            modelConfig = modelConfig {
                releaseStage = stage
                preference = variant
            }
        },
    )

internal fun preferenceName(preference: Int): String =
    if (preference == ModelPreference.FAST) "Fast" else "Full"

internal fun releaseStageName(stage: Int): String =
    if (stage == ModelReleaseStage.PREVIEW) "Preview" else "Stable"

/** AICore's refusal in a line: the error code's name when it is a GenAiException, else the exception's class. */
internal fun describeNanoError(e: Throwable): String {
    val head = (e as? GenAiException)?.let { "ErrorCode ${it.errorCode} ${genAiErrorName(it.errorCode)}" }
        ?: e.javaClass.simpleName.ifEmpty { e.javaClass.name }
    return listOfNotNull(head, e.message?.take(160)).joinToString(": ")
}

internal fun genAiErrorName(code: Int): String = when (code) {
    GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR -> "REQUEST_PROCESSING_ERROR"
    GenAiException.ErrorCode.CANCELLED -> "CANCELLED"
    GenAiException.ErrorCode.NOT_AVAILABLE -> "NOT_AVAILABLE"
    GenAiException.ErrorCode.BUSY -> "BUSY"
    GenAiException.ErrorCode.RESPONSE_PROCESSING_ERROR -> "RESPONSE_PROCESSING_ERROR"
    GenAiException.ErrorCode.REQUEST_TOO_LARGE -> "REQUEST_TOO_LARGE"
    GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR -> "RESPONSE_GENERATION_ERROR"
    GenAiException.ErrorCode.NOT_SUPPORTED -> "NOT_SUPPORTED"
    GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> "PER_APP_BATTERY_USE_QUOTA_EXCEEDED"
    GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> "BACKGROUND_USE_BLOCKED"
    GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> "NOT_ENOUGH_DISK_SPACE"
    GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> "NEEDS_SYSTEM_UPDATE"
    GenAiException.ErrorCode.AICORE_INCOMPATIBLE -> "AICORE_INCOMPATIBLE"
    else -> "UNKNOWN"
}
