package com.gpo.yoin.data.highlight

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.pm.PackageInfoCompat
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.gpo.yoin.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Yoin Labs › Gemini Nano diagnostics (owner 2026-11-12: "a debug page that guides people to download the model"):
 * asks AICore about every model variant separately, with its raw answer, so a phone that says "not supported" can
 * say why. Not the lyric cards' path ([GeminiNanoLyricModel] keeps its own client); every probe builds and closes a
 * client of its own.
 */
object NanoDiagnostics {

    /** One model variant (Stable / Preview × Full / Fast) as AICore answered for it. */
    data class Probe(
        val stage: String,
        val variant: String,
        val status: NanoStatus,
        /** The model's own name when AICore has it on the device (e.g. nano-v4-fast). */
        val baseModel: String? = null,
        /** AICore's refusal in one line, when it refused. */
        val error: String? = null,
    ) {
        val label: String get() = "$stage · $variant"
    }

    /** A system app the on-device model depends on, and its version (null when it isn't installed). */
    data class Component(val name: String, val packageName: String, val version: String?)

    data class Report(
        val device: String,
        val android: String,
        val app: String,
        val components: List<Component>,
        val probes: List<Probe>,
    ) {
        val anyAvailable: Boolean get() = probes.any { it.status == NanoStatus.AVAILABLE }

        /**
         * The download button would fetch something. It goes through the lyric cards' model (one owner for the
         * download), so this is that model's own rule ([GeminiNanoLyricModel.status]): only Stable (Preview needs
         * the AICore Developer Preview anyway), and only while no Stable variant is ready. With one ready the model
         * keeps to it, and a download would ask for the variant that is already on the phone.
         */
        val canDownload: Boolean
            get() {
                val stable = probes.filter { it.stage == stableStage }
                return stable.none { it.status == NanoStatus.AVAILABLE } &&
                    stable.any { it.status == NanoStatus.DOWNLOADABLE || it.status == NanoStatus.DOWNLOADING }
            }

        /**
         * AICore is already fetching a Stable variant, whoever asked: the Download row, the lyric cards on Wi-Fi
         * ([LyricHighlightProcessor.downloading] is only the row's own), or a download that outlived the app. The
         * row shows it running instead of offering a second one.
         */
        val downloadInFlight: Boolean
            get() = probes.any { it.stage == stableStage && it.status == NanoStatus.DOWNLOADING }

        /**
         * Plain text for the clipboard: what a bug report needs. [downloadFailure] is why the last download ended
         * without the model, when one did.
         */
        fun asText(downloadFailure: String? = null): String = buildString {
            appendLine("Gemini Nano diagnostics")
            appendLine("Device: $device")
            appendLine("Android: $android")
            appendLine("Yoin: $app")
            components.forEach { appendLine("${it.name} (${it.packageName}): ${it.version ?: "not installed"}") }
            probes.forEach { probe ->
                append("${probe.label}: ${probe.status}")
                probe.baseModel?.let { append(" [$it]") }
                probe.error?.let { append(" — $it") }
                appendLine()
            }
            downloadFailure?.let { appendLine("Download failed: $it") }
        }.trimEnd()
    }

    /** A test prompt's outcome: the reply and how long it took, or AICore's refusal. */
    data class TestResult(val variant: String, val reply: String?, val millis: Long, val error: String? = null)

    private val stableStage = releaseStageName(ModelReleaseStage.STABLE)

    private val variants = listOf(
        ModelReleaseStage.STABLE to ModelPreference.FULL,
        ModelReleaseStage.STABLE to ModelPreference.FAST,
        ModelReleaseStage.PREVIEW to ModelPreference.FULL,
        ModelReleaseStage.PREVIEW to ModelPreference.FAST,
    )

    suspend fun run(context: Context): Report = withContext(Dispatchers.IO) {
        Report(
            device = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.DISPLAY}",
            app = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}",
            components = listOf(
                component(context, "AICore", "com.google.android.aicore"),
                component(context, "Private Compute Services", "com.google.android.as.oss"),
                component(context, "Android System Intelligence", "com.google.android.as"),
            ),
            probes = variants.map { (stage, variant) -> probe(stage, variant) },
        )
    }

    /** Asks the first variant that is ready for a one-word reply; null when none is ready. */
    suspend fun testPrompt(report: Report): TestResult? {
        val index = report.probes.indexOfFirst { it.status == NanoStatus.AVAILABLE }.takeIf { it >= 0 } ?: return null
        val (stage, variant) = variants[index]
        val label = report.probes[index].label
        val start = SystemClock.elapsedRealtime()
        return try {
            withClient(stage, variant) { model ->
                val response = model.generateContent(
                    generateContentRequest(TextPart("Reply with exactly one word: hello")) {
                        temperature = 0f
                        maxOutputTokens = 8
                    },
                )
                val reply = response.candidates.firstOrNull()?.text?.trim()
                TestResult(label, reply, SystemClock.elapsedRealtime() - start)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TestResult(label, null, SystemClock.elapsedRealtime() - start, describeNanoError(e))
        }
    }

    private suspend fun probe(stage: Int, variant: Int): Probe {
        val stageName = releaseStageName(stage)
        val variantName = preferenceName(variant)
        return try {
            withClient(stage, variant) { model ->
                val status = when (model.checkStatus()) {
                    FeatureStatus.AVAILABLE -> NanoStatus.AVAILABLE
                    FeatureStatus.DOWNLOADABLE -> NanoStatus.DOWNLOADABLE
                    FeatureStatus.DOWNLOADING -> NanoStatus.DOWNLOADING
                    else -> NanoStatus.UNAVAILABLE
                }
                val baseModel = if (status == NanoStatus.AVAILABLE) {
                    runCatching { model.getBaseModelName() }.getOrNull()
                } else {
                    null
                }
                Probe(
                    stage = stageName,
                    variant = variantName,
                    status = status,
                    baseModel = baseModel,
                    error = if (status == NanoStatus.UNAVAILABLE) "FeatureStatus.UNAVAILABLE" else null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Probe(stageName, variantName, NanoStatus.UNAVAILABLE, error = describeNanoError(e))
        }
    }

    /** Builds a client for one variant, runs [block] and closes it. */
    private suspend fun <T> withClient(stage: Int, variant: Int, block: suspend (GenerativeModel) -> T): T {
        val model = nanoClient(stage, variant)
        return try {
            block(model)
        } finally {
            runCatching { model.close() }
        }
    }

    private fun component(context: Context, name: String, packageName: String): Component {
        val version = try {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        return Component(name, packageName, version)
    }
}
