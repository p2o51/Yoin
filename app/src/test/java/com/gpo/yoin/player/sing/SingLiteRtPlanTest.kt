package com.gpo.yoin.player.sing

import com.gpo.yoin.player.sing.SingAccelerator.CPU
import com.gpo.yoin.player.sing.SingAccelerator.GPU
import com.gpo.yoin.player.sing.SingAccelerator.NPU
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingLiteRtPlanTest {
    private val g6 = "lyra_sing_lite_tensor_g6.tflite"
    private val g5 = "lyra_sing_lite_tensor_g5.tflite"
    private val fp16 = "lyra_sing_lite_fp16.tflite"

    /** The bundled meta (assets/sing/litert/models.json), word for word. */
    private val meta = """
        {
          "version": 1,
          "models": [
            {"file": "$g6", "sha256": "0534f3d74f7f0d5e5f32131d70ff25550399becaf3b45098f81b769119c2b355", "bytes": 8287936,
             "precision": "bf16-aot", "socManufacturer": "Google", "socModel": "Tensor G6", "accelerators": ["NPU"]},
            {"file": "$g5", "sha256": "10bd17fb365d23f1fea5dc91c307409827eb4d7c9b0ce38993efee9c70a0a500", "bytes": 7372736,
             "precision": "bf16-aot", "socManufacturer": "Google", "socModel": "Tensor G5", "accelerators": ["NPU"]},
            {"file": "$fp16", "sha256": "176b9dd47bcc3de24e27c14bc4edb44bf1fff6d74e95c53476a4f5a7a1610651", "bytes": 4465088,
             "precision": "fp16", "accelerators": ["GPU", "CPU"]}
          ]
        }
    """.trimIndent()

    private val catalog = SingLiteRtPlan.parse(meta)

    private fun plan(maker: String?, model: String?, npu: Boolean = true) =
        SingLiteRtPlan.plan(catalog, maker, model, npu).map { it.model.file to it.accelerator }

    @Test
    fun should_tryTensorG6Npu_then_fp16GpuAndCpu_when_socIsTensorG6() {
        assertEquals(listOf(g6 to NPU, fp16 to GPU, fp16 to CPU), plan("Google", "Tensor G6"))
    }

    @Test
    fun should_tryTensorG5Npu_when_socIsTensorG5() {
        assertEquals(listOf(g5 to NPU, fp16 to GPU, fp16 to CPU), plan("Google", "Tensor G5"))
    }

    @Test
    fun should_skipNpu_when_tensorWithoutAnAotBuild() {
        // Pixel Tablet / Pixel 7 (Tensor G2), Pixel 8 (G3), Pixel 9 (G4): no AOT build, fp16 on GPU then CPU.
        for (soc in listOf("Tensor", "Tensor G2", "Tensor G3", "Tensor G4")) {
            assertEquals(soc, listOf(fp16 to GPU, fp16 to CPU), plan("Google", soc))
        }
    }

    @Test
    fun should_useGenericBuild_when_otherSocOrUnknown() {
        assertEquals(listOf(fp16 to GPU, fp16 to CPU), plan("Qualcomm", "SM8750"))
        assertEquals(listOf(fp16 to GPU, fp16 to CPU), plan("Mediatek", "Tensor G5")) // maker must match too
        // Before API 31 there is no Build.SOC_*.
        assertEquals(listOf(fp16 to GPU, fp16 to CPU), plan(null, null, npu = false))
    }

    @Test
    fun should_skipNpu_when_npuNotAllowed() {
        assertEquals(listOf(fp16 to GPU, fp16 to CPU), plan("Google", "Tensor G5", npu = false))
    }

    @Test
    fun should_matchSocLeniently_when_caseOrSpacesDiffer() {
        assertEquals(g5 to NPU, plan("google", " tensor g5 ").first())
        val g5Meta = catalog.models.first { it.file == g5 }
        assertTrue(SingLiteRtPlan.matchesSoc(g5Meta, "Google", "Tensor G5"))
        assertFalse(SingLiteRtPlan.matchesSoc(g5Meta, "Google", "Tensor G6"))
        assertFalse(SingLiteRtPlan.matchesSoc(g5Meta, null, null))
        assertTrue(SingLiteRtPlan.matchesSoc(catalog.models.first { it.file == fp16 }, null, null))
    }

    @Test
    fun should_dropBuild_when_shaNotTrusted() {
        val tampered = catalog.copy(models = catalog.models.map { if (it.file == fp16) it.copy(sha256 = "0".repeat(64)) else it })
        assertEquals(listOf(g5 to NPU), SingLiteRtPlan.plan(tampered, "Google", "Tensor G5", true).map { it.model.file to it.accelerator })
        assertTrue(SingLiteRtPlan.plan(tampered, "Qualcomm", "SM8750", true).isEmpty())
    }

    @Test
    fun should_neverRunAotOrIntegerBuilds_onGpuOrCpu() {
        // A meta that asks for it anyway: an AOT build on the GPU/CPU, an fp16 build on the NPU.
        val wrong = catalog.copy(
            models = catalog.models.map {
                when (it.file) {
                    g5 -> it.copy(accelerators = listOf(NPU, GPU, CPU))
                    fp16 -> it.copy(accelerators = listOf(NPU, GPU, CPU))
                    else -> it
                }
            },
        )
        assertEquals(
            listOf(g5 to NPU, fp16 to GPU, fp16 to CPU),
            SingLiteRtPlan.plan(wrong, "Google", "Tensor G5", true).map { it.model.file to it.accelerator },
        )
        val int16 = catalog.copy(models = catalog.models.map { if (it.file == fp16) it.copy(precision = "int16x8") else it })
        assertTrue(SingLiteRtPlan.plan(int16, "Qualcomm", "SM8750", true).isEmpty())
    }

    @Test
    fun should_trustExactlyTheThreeBundledBuilds() {
        assertEquals(catalog.models.map { it.sha256 }.toSet(), SingLiteRtPlan.TRUSTED_SHA256.keys)
    }
}
