package com.gpo.yoin.player.sing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a LiteRT build of Lyra-Sing-Lite runs. NPU is the Google Tensor TPU (AOT builds only). */
enum class SingAccelerator { NPU, GPU, CPU }

/**
 * One LiteRT build of Lyra-Sing-Lite as the model meta lists it (assets/sing/litert/models.json for the
 * sideloaded test build; Play AI Packs later). Every build has the ONNX model's contract: signature
 * `serving_default`, `args_0` [1, 4, 512, 256] float32 in, `output_0` [1, 4, 512, 256] float32 out.
 */
@Serializable
data class LiteRtModelMeta(
    val file: String,
    val sha256: String,
    val bytes: Long,
    /** What the weights are: `fp16`, `fp32`, or `bf16-aot` (compiled ahead of time for one SoC). */
    val precision: String,
    /**
     * The SoC an AOT build was compiled for, as Play's device targeting names it
     * (`<system-on-chip manufacturer="Google" model="Tensor G5"/>`, matched against
     * Build.SOC_MANUFACTURER / Build.SOC_MODEL); null for a build any device can run.
     */
    val socManufacturer: String? = null,
    val socModel: String? = null,
    /** Where it may run, preferred first. */
    val accelerators: List<SingAccelerator>,
)

@Serializable
data class LiteRtCatalog(val version: Int = 1, val models: List<LiteRtModelMeta>)

/** One step of the router: this build on this accelerator. */
data class LiteRtCandidate(val model: LiteRtModelMeta, val accelerator: SingAccelerator) {
    override fun toString(): String = "${model.file}@$accelerator"
}

/**
 * Which LiteRT builds this device tries, in order (the router, [SingRoutedModel], takes the first that
 * opens and moves on when one fails): the Tensor TPU with the AOT build for this SoC, then the GPU,
 * then the CPU (XNNPACK) with the fp16 build. Never an integer build on the CPU (int8 smears the
 * vocal; int16x8 has no fast CPU kernels).
 */
object SingLiteRtPlan {
    const val ASSET_DIR = "sing/litert"
    const val META_ASSET = "$ASSET_DIR/models.json"

    /** Builds the CPU may run: float weights only. */
    private val CPU_PRECISIONS = setOf("fp16", "fp32")

    /** Builds the GPU may run (an AOT build is NPU bytecode). */
    private val GPU_PRECISIONS = setOf("fp16", "fp32")

    /**
     * The builds this code will load, by SHA-256 (checked again on every open): a meta can name only
     * these. (lyra-sing-lite artifacts/tflite/official_ai_pack, 2026-11-24.)
     */
    val TRUSTED_SHA256: Map<String, String> = mapOf(
        // model#group_Google_Tensor_G6/lyra_sing_lite.tflite, 8 287 936 B
        "0534f3d74f7f0d5e5f32131d70ff25550399becaf3b45098f81b769119c2b355" to "Tensor G6 AOT",
        // model#group_Google_Tensor_G5/lyra_sing_lite.tflite, 7 372 736 B
        "10bd17fb365d23f1fea5dc91c307409827eb4d7c9b0ce38993efee9c70a0a500" to "Tensor G5 AOT",
        // model#group_other/lyra_sing_lite.tflite = lyra_sing_lite_fp16.tflite, 4 465 088 B
        "176b9dd47bcc3de24e27c14bc4edb44bf1fff6d74e95c53476a4f5a7a1610651" to "fp16",
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): LiteRtCatalog = json.decodeFromString(LiteRtCatalog.serializer(), text)

    /**
     * Whether [meta] is for this device: a build without a SoC is for any; an AOT build only for the SoC
     * it was compiled for. Play matches the strings exactly; here also ignoring case and outer spaces.
     */
    fun matchesSoc(meta: LiteRtModelMeta, socManufacturer: String?, socModel: String?): Boolean {
        val wantModel = meta.socModel ?: return meta.socManufacturer == null
        val wantMaker = meta.socManufacturer ?: return false
        return socManufacturer?.trim().equals(wantMaker, ignoreCase = true) &&
            socModel?.trim().equals(wantModel, ignoreCase = true)
    }

    /**
     * The router's order for a device with this SoC ([socManufacturer]/[socModel] are null before API 31,
     * which has no Build.SOC_*; [npuAllowed] false there too: LiteRT's NPU path needs API 31). By
     * accelerator — NPU, GPU, CPU — and within one, the SoC's own builds before the generic ones.
     * Untrusted builds (not in [TRUSTED_SHA256]) and integer builds on the CPU are left out.
     */
    fun plan(
        catalog: LiteRtCatalog,
        socManufacturer: String?,
        socModel: String?,
        npuAllowed: Boolean,
    ): List<LiteRtCandidate> {
        val usable = catalog.models.filter { it.sha256.lowercase() in TRUSTED_SHA256 && matchesSoc(it, socManufacturer, socModel) }
        return SingAccelerator.entries.flatMap { accelerator ->
            if (accelerator == SingAccelerator.NPU && !npuAllowed) return@flatMap emptyList()
            usable
                .filter { accelerator in it.accelerators && runsOn(it, accelerator) }
                .sortedBy { if (it.socModel != null) 0 else 1 }
                .map { LiteRtCandidate(it, accelerator) }
        }
    }

    private fun runsOn(meta: LiteRtModelMeta, accelerator: SingAccelerator): Boolean = when (accelerator) {
        SingAccelerator.NPU -> meta.socModel != null
        SingAccelerator.GPU -> meta.precision in GPU_PRECISIONS && meta.socModel == null
        SingAccelerator.CPU -> meta.precision in CPU_PRECISIONS && meta.socModel == null
    }
}
