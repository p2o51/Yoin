package com.gpo.yoin.player.sing

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why the router left a backend: it wouldn't open ([UNAVAILABLE]) or a chunk on it failed ([FAILED]). */
enum class SingFallbackReason { UNAVAILABLE, FAILED }

/** The backend the router left just before the one that ran. */
data class SingFallback(val accelerator: SingAccelerator, val reason: SingFallbackReason)

/** What on-device Sing's last session ran on (its first good chunk there). */
data class SingBackendRun(
    val accelerator: SingAccelerator,
    /** The build, as [SingLiteRtPlan.TRUSTED_SHA256] names it ("fp16", "Tensor G6 AOT"). */
    val model: String? = null,
    val fallback: SingFallback? = null,
    /** Build.SOC_MANUFACTURER and Build.SOC_MODEL, for diagnosis. */
    val soc: String? = null,
)

/**
 * Settings › Labs text for a [SingBackendRun]. Technical labels, not user copy: never translated.
 */
object SingBackendLabel {
    const val TENSOR_TPU = "Tensor TPU"
    const val GPU = "GPU"
    const val CPU = "CPU"
    const val UNAVAILABLE = "unavailable"
    const val FAILED = "failed"
    const val SOC_PREFIX = "SoC "
    const val DETAIL_SEPARATOR = " · "

    fun accelerator(accelerator: SingAccelerator): String = when (accelerator) {
        SingAccelerator.NPU -> TENSOR_TPU
        SingAccelerator.GPU -> GPU
        SingAccelerator.CPU -> CPU
    }

    /** "Tensor TPU", "GPU (NPU unavailable)", "CPU (GPU failed)". */
    fun summary(run: SingBackendRun): String {
        val fallback = run.fallback ?: return accelerator(run.accelerator)
        val why = when (fallback.reason) {
            SingFallbackReason.UNAVAILABLE -> UNAVAILABLE
            SingFallbackReason.FAILED -> FAILED
        }
        return "${accelerator(run.accelerator)} (${fallback.accelerator.name} $why)"
    }

    /** "Tensor G6 AOT · SoC Google Tensor G6"; null with neither known. */
    fun detail(run: SingBackendRun): String? =
        listOfNotNull(run.model, run.soc?.let { SOC_PREFIX + it }).joinToString(DETAIL_SEPARATOR).ifEmpty { null }

    /**
     * The fallback to report when [running] took over after [failures] (each backend left, in order, and
     * why): the last one left on another accelerator, or null when the first choice is the one running.
     */
    fun fallback(running: SingAccelerator, failures: List<Pair<SingAccelerator, SingFallbackReason>>): SingFallback? =
        failures.lastOrNull { it.first != running }?.let { (accelerator, reason) -> SingFallback(accelerator, reason) }
}

/**
 * The backend on-device Sing's last session actually ran on (its first good chunk), with the build, the
 * fallback that led there and the SoC, kept across launches. Settings › Labs shows it once there is one.
 */
class SingAcceleratorReport private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _last = MutableStateFlow(read())

    val last: StateFlow<SingBackendRun?> = _last.asStateFlow()

    fun record(run: SingBackendRun) {
        if (_last.value == run) return
        _last.value = run
        prefs.edit {
            putString(KEY_LAST, run.accelerator.name)
            putString(KEY_MODEL, run.model)
            putString(KEY_FALLBACK_ACCELERATOR, run.fallback?.accelerator?.name)
            putString(KEY_FALLBACK_REASON, run.fallback?.reason?.name)
            putString(KEY_SOC, run.soc)
        }
    }

    private fun read(): SingBackendRun? {
        val accelerator = enumOrNull<SingAccelerator>(prefs.getString(KEY_LAST, null)) ?: return null
        val fallbackAccelerator = enumOrNull<SingAccelerator>(prefs.getString(KEY_FALLBACK_ACCELERATOR, null))
        val fallbackReason = enumOrNull<SingFallbackReason>(prefs.getString(KEY_FALLBACK_REASON, null))
        return SingBackendRun(
            accelerator = accelerator,
            model = prefs.getString(KEY_MODEL, null),
            fallback = if (fallbackAccelerator != null && fallbackReason != null) SingFallback(fallbackAccelerator, fallbackReason) else null,
            soc = prefs.getString(KEY_SOC, null),
        )
    }

    companion object {
        private const val PREFS = "sing_backend"
        private const val KEY_LAST = "last_accelerator"
        private const val KEY_MODEL = "last_model"
        private const val KEY_FALLBACK_ACCELERATOR = "last_fallback_accelerator"
        private const val KEY_FALLBACK_REASON = "last_fallback_reason"
        private const val KEY_SOC = "last_soc"

        @Volatile private var instance: SingAcceleratorReport? = null

        fun get(context: Context): SingAcceleratorReport =
            instance ?: synchronized(this) {
                instance ?: SingAcceleratorReport(context.applicationContext).also { instance = it }
            }

        private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
            name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }
    }
}
