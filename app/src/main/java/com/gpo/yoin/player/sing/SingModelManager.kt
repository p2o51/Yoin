package com.gpo.yoin.player.sing

import android.content.Context
import android.os.Build
import com.gpo.yoin.BuildConfig
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Download / readiness state for the on-device Lyra-Sing-Lite model shown in Yoin Labs. */
sealed interface SingModelDownloadStatus {
    data object Checking : SingModelDownloadStatus

    data object NotDownloaded : SingModelDownloadStatus

    data class Downloading(val progress: Float) : SingModelDownloadStatus

    data object Ready : SingModelDownloadStatus

    data class Failed(val message: String) : SingModelDownloadStatus
}

/** One verified LiteRT build currently on disk for this device. */
data class SingInstalledBuild(
    val name: String,
    val bytes: Long,
    val sha256Short: String,
    val accelerators: List<SingAccelerator>,
)

/** Recognized Google Tensor generation optimized for on-device Lyra Sing TPU execution. */
enum class TensorGeneration(val chipCode: String, val displayName: String) {
    G5("G5", "Google Tensor G5"),
    G6("G6", "Google Tensor G6"),
    Generic("G", "Google Tensor"),
}

/** Snapshot of Lyra-Sing-Lite distribution, local installation, and hardware acceleration for Yoin Labs. */
data class SingModelOverview(
    val status: SingModelDownloadStatus = SingModelDownloadStatus.Checking,
    val expectedBuildName: String = "fp16",
    val expectedPackGroup: String = GROUP_OTHER,
    val expectedDownloadBytes: Long = GENERIC_PACK_BYTES,
    val expectedSha256Short: String? = null,
    val bundledInApk: Boolean = false,
    val installedOrigin: String? = null,
    val installedGroup: String? = null,
    val installedBuilds: List<SingInstalledBuild> = emptyList(),
    val totalInstalledBytes: Long = 0L,
    val soc: String? = null,
    val lastRun: SingBackendRun? = null,
) {
    val isReady: Boolean get() = status is SingModelDownloadStatus.Ready

    val isDownloading: Boolean get() = status is SingModelDownloadStatus.Downloading

    /** True on Google Tensor G5 / G6 TPU AOT targets. */
    val isTensorTpu: Boolean
        get() = tensorGeneration != null

    /** Which Google Tensor generation this device is optimized for, or null on non-Tensor devices. */
    val tensorGeneration: TensorGeneration?
        get() {
            val haystack = listOfNotNull(
                lastRun?.takeIf { it.accelerator == SingAccelerator.NPU }?.model,
                installedBuilds.firstOrNull { SingAccelerator.NPU in it.accelerators }?.name,
                expectedBuildName.takeIf { it.contains("Tensor", ignoreCase = true) },
                installedGroup?.takeIf { it.contains("Tensor", ignoreCase = true) },
                expectedPackGroup.takeIf { it.contains("Tensor", ignoreCase = true) },
            ).joinToString(" ")
            val isTensor = haystack.contains("Tensor", ignoreCase = true) ||
                (lastRun?.accelerator == SingAccelerator.NPU && soc?.contains("Tensor", ignoreCase = true) == true)
            if (!isTensor) return null
            val full = listOfNotNull(haystack, soc).joinToString(" ")
            return when {
                full.contains("G6", ignoreCase = true) -> TensorGeneration.G6
                full.contains("G5", ignoreCase = true) -> TensorGeneration.G5
                else -> TensorGeneration.Generic
            }
        }

    /**
     * Human-readable chip name when this device has a dedicated NPU / AOT optimization
     * (e.g. "Google Tensor G6", "Google Tensor G5", "MediaTek Dimensity 9400"), or null when
     * using the generic GPU/CPU build.
     */
    val optimizedForTarget: String?
        get() {
            tensorGeneration?.let { return it.displayName }
            val hasNpuBuild = lastRun?.accelerator == SingAccelerator.NPU ||
                installedBuilds.any { SingAccelerator.NPU in it.accelerators } ||
                (expectedPackGroup != GROUP_OTHER && !expectedBuildName.equals("fp16", ignoreCase = true)) ||
                (installedGroup != null && !installedGroup.equals(GROUP_OTHER, ignoreCase = true))
            if (!hasNpuBuild) return null
            return formatOptimizedChipName(
                soc = soc,
                group = installedGroup ?: expectedPackGroup.takeIf { it != GROUP_OTHER },
                buildName = lastRun?.model
                    ?: installedBuilds.firstOrNull { SingAccelerator.NPU in it.accelerators }?.name
                    ?: expectedBuildName,
            )
        }

    val activeBuildName: String
        get() = lastRun?.model ?: installedBuilds.firstOrNull()?.name ?: expectedBuildName

    val activeSha256Short: String?
        get() = installedBuilds.firstOrNull()?.sha256Short ?: expectedSha256Short

    /** Technical distribution line (pack channel, targeting group, and included builds/sizes). */
    fun distributionLine(): String {
        if (isReady && installedBuilds.isNotEmpty()) {
            val source = when {
                installedOrigin?.startsWith("bundled", ignoreCase = true) == true -> "Bundled APK"
                else -> "Play AI Pack · ${AiPackSingModelSource.PACK}"
            }
            val groupPart = installedGroup?.takeIf { it.isNotBlank() }?.let { "group $it" }
            val buildsPart = installedBuilds.joinToString(" + ") { it.name }
            return listOfNotNull(source, groupPart, buildsPart).joinToString(" · ")
        }
        val channel = if (bundledInApk) "Bundled APK" else "Play AI Pack · ${AiPackSingModelSource.PACK}"
        val sizeText = formatBytes(expectedDownloadBytes)
        return "$channel · group $expectedPackGroup · $expectedBuildName (~$sizeText)"
    }

    /** Technical last-run accelerator readout, or expected accelerator before first run. */
    fun backendLine(): String? {
        val run = lastRun
        if (run != null) {
            return listOfNotNull(SingBackendLabel.summary(run), SingBackendLabel.detail(run))
                .joinToString(SingBackendLabel.DETAIL_SEPARATOR)
        }
        val targetAccel = if (isTensorTpu) SingBackendLabel.TENSOR_TPU else "${SingBackendLabel.GPU} / ${SingBackendLabel.CPU}"
        return listOfNotNull(targetAccel, soc?.let { SingBackendLabel.SOC_PREFIX + it })
            .joinToString(SingBackendLabel.DETAIL_SEPARATOR)
            .ifEmpty { null }
    }

    companion object {
        const val GROUP_TENSOR_G6 = "Google_Tensor_G6"
        const val GROUP_TENSOR_G5 = "Google_Tensor_G5"
        const val GROUP_OTHER = "other"

        // Actual Play AI Pack delivery sizes (AOT build + fallback fp16 build for Tensor; fp16 only for others).
        const val TENSOR_G6_PACK_BYTES = 8_287_936L + 4_465_088L
        const val TENSOR_G5_PACK_BYTES = 7_372_736L + 4_465_088L
        const val GENERIC_PACK_BYTES = 4_465_088L

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 MB"
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 1.0) {
                String.format(Locale.US, "%.1f MB", mb)
            } else {
                String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            }
        }

        fun formatOptimizedChipName(
            soc: String?,
            group: String?,
            buildName: String?,
        ): String {
            val raw = soc?.takeIf { it.isNotBlank() }
                ?: group?.takeIf { it.isNotBlank() && !it.equals(GROUP_OTHER, ignoreCase = true) }?.replace('_', ' ')
                ?: buildName?.removeSuffix(" AOT")?.removeSuffix(" aot")?.trim()?.takeIf { it.isNotBlank() }
                ?: "NPU"
            return raw
                .replace(Regex("\\bmediatek\\b", RegexOption.IGNORE_CASE), "MediaTek")
                .replace(Regex("\\bqualcomm\\b", RegexOption.IGNORE_CASE), "Qualcomm")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun baseline(
            socManufacturer: String?,
            socModel: String?,
            npuAllowed: Boolean,
            bundledInApk: Boolean,
            lastRun: SingBackendRun? = null,
        ): SingModelOverview {
            val expectedSha = SingLiteRtPlan.expectedSha256(socManufacturer, socModel, npuAllowed)
            val trusted = expectedSha?.let { SingLiteRtPlan.TRUSTED[it] }
            val buildName = trusted?.name ?: "fp16"
            val (group, estBytes) = when {
                buildName.contains("G6", ignoreCase = true) -> GROUP_TENSOR_G6 to TENSOR_G6_PACK_BYTES
                buildName.contains("G5", ignoreCase = true) -> GROUP_TENSOR_G5 to TENSOR_G5_PACK_BYTES
                trusted?.socModel != null -> {
                    val customGroup = listOfNotNull(trusted.socManufacturer, trusted.socModel)
                        .joinToString("_")
                        .replace(' ', '_')
                    customGroup to TENSOR_G5_PACK_BYTES
                }
                else -> GROUP_OTHER to GENERIC_PACK_BYTES
            }
            val soc = listOfNotNull(socManufacturer, socModel)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
                .ifEmpty { null }
            return SingModelOverview(
                status = SingModelDownloadStatus.Checking,
                expectedBuildName = buildName,
                expectedPackGroup = group,
                expectedDownloadBytes = estBytes,
                expectedSha256Short = expectedSha?.take(8),
                bundledInApk = bundledInApk,
                soc = soc,
                lastRun = lastRun,
            )
        }
    }
}

/**
 * Coordinates Yoin Labs inspection, proactive download, and removal of the on-device Lyra-Sing-Lite model.
 */
class SingModelManager(
    private val resolver: SingModelResolver,
    private val report: SingAcceleratorReport?,
    private val scope: CoroutineScope,
    private val socManufacturer: String? = LiteRtSingBackend.socManufacturer(),
    private val socModel: String? = LiteRtSingBackend.socModel(),
    private val npuAllowed: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    private val bundledInApk: Boolean = BuildConfig.SING_BUNDLED_MODELS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onModelStorageChanged: () -> Unit = {},
) {
    private val _state = MutableStateFlow(
        SingModelOverview.baseline(
            socManufacturer = socManufacturer,
            socModel = socModel,
            npuAllowed = npuAllowed,
            bundledInApk = bundledInApk,
            lastRun = report?.last?.value,
        ),
    )
    val state: StateFlow<SingModelOverview> = _state.asStateFlow()

    private var activeJob: Job? = null

    init {
        if (report != null) {
            scope.launch {
                report.last.collect { run ->
                    _state.value = _state.value.copy(lastRun = run)
                }
            }
        }
    }

    /** Inspects local storage without triggering any network download. */
    fun refresh() {
        if (_state.value.isDownloading) return
        scope.launch(ioDispatcher) {
            val local = runCatching { resolver.local() }.getOrNull()
            applyLocalSet(local)
        }
    }

    /** Proactively fetches & SHA-256-verifies the model pack with live progress updates. */
    fun downloadNow() {
        if (_state.value.isDownloading) return
        activeJob?.cancel()
        _state.value = _state.value.copy(status = SingModelDownloadStatus.Downloading(0f))
        activeJob = scope.launch(ioDispatcher) {
            try {
                val set = resolver.resolve { progress ->
                    _state.value = _state.value.copy(
                        status = SingModelDownloadStatus.Downloading(progress.coerceIn(0f, 1f)),
                    )
                }
                applyLocalSet(set)
                onModelStorageChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                _state.value = _state.value.copy(status = SingModelDownloadStatus.Failed(detail))
            }
        }
    }

    /** Removes the downloaded/copied model files so storage is freed or delivery can be re-tested. */
    fun removeModel() {
        activeJob?.cancel()
        activeJob = scope.launch(ioDispatcher) {
            runCatching { resolver.clear() }
            onModelStorageChanged()
            val local = runCatching { resolver.local() }.getOrNull()
            applyLocalSet(local)
        }
    }

    private fun applyLocalSet(set: SingModelSet?) {
        val current = _state.value
        if (set == null) {
            _state.value = current.copy(
                status = SingModelDownloadStatus.NotDownloaded,
                installedOrigin = null,
                installedGroup = null,
                installedBuilds = emptyList(),
                totalInstalledBytes = 0L,
            )
            return
        }
        val builds = set.catalog.models.map { meta ->
            val sha = meta.sha256.lowercase()
            val name = SingLiteRtPlan.TRUSTED_SHA256[sha] ?: meta.id ?: meta.file
            val fileBytes = set.fileFor(meta).length().takeIf { it > 0L } ?: meta.bytes
            SingInstalledBuild(
                name = name,
                bytes = fileBytes,
                sha256Short = sha.take(8),
                accelerators = meta.accelerators,
            )
        }
        _state.value = current.copy(
            status = SingModelDownloadStatus.Ready,
            installedOrigin = set.origin,
            installedGroup = set.catalog.group,
            installedBuilds = builds,
            totalInstalledBytes = builds.sumOf { it.bytes },
        )
    }

    companion object {
        fun create(
            context: Context,
            scope: CoroutineScope,
            onModelStorageChanged: () -> Unit = {},
        ): SingModelManager = SingModelManager(
            resolver = LiteRtSingBackend.resolver(context, BuildConfig.SING_BUNDLED_MODELS),
            report = SingAcceleratorReport.get(context),
            scope = scope,
            onModelStorageChanged = onModelStorageChanged,
        )
    }
}
