package com.gpo.yoin.player.sing

import com.gpo.yoin.player.sing.SingAccelerator.CPU
import com.gpo.yoin.player.sing.SingAccelerator.GPU
import com.gpo.yoin.player.sing.SingAccelerator.NPU
import com.gpo.yoin.player.sing.SingFallbackReason.FAILED
import com.gpo.yoin.player.sing.SingFallbackReason.UNAVAILABLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SingBackendLabelTest {
    @Test
    fun `each backend by its technical name`() {
        assertEquals("Tensor TPU", SingBackendLabel.summary(SingBackendRun(NPU)))
        assertEquals("GPU", SingBackendLabel.summary(SingBackendRun(GPU)))
        assertEquals("CPU", SingBackendLabel.summary(SingBackendRun(CPU)))
    }

    @Test
    fun `a fallback adds what was left and why`() {
        assertEquals("GPU (NPU unavailable)", SingBackendLabel.summary(SingBackendRun(GPU, fallback = SingFallback(NPU, UNAVAILABLE))))
        assertEquals("CPU (GPU failed)", SingBackendLabel.summary(SingBackendRun(CPU, fallback = SingFallback(GPU, FAILED))))
        assertEquals("GPU (NPU failed)", SingBackendLabel.summary(SingBackendRun(GPU, fallback = SingFallback(NPU, FAILED))))
    }

    @Test
    fun `detail names the build and the SoC`() {
        assertEquals(
            "Tensor G6 AOT · SoC Google Tensor G6",
            SingBackendLabel.detail(SingBackendRun(NPU, model = "Tensor G6 AOT", soc = "Google Tensor G6")),
        )
        assertEquals("fp16", SingBackendLabel.detail(SingBackendRun(GPU, model = "fp16")))
        assertNull(SingBackendLabel.detail(SingBackendRun(CPU)))
    }

    @Test
    fun `the fallback is the last backend left on another accelerator`() {
        assertNull(SingBackendLabel.fallback(NPU, emptyList()))
        assertEquals(SingFallback(NPU, UNAVAILABLE), SingBackendLabel.fallback(GPU, listOf(NPU to UNAVAILABLE)))
        assertEquals(SingFallback(GPU, FAILED), SingBackendLabel.fallback(CPU, listOf(NPU to UNAVAILABLE, GPU to FAILED)))
        // A build that failed on the accelerator now running isn't a fallback from elsewhere.
        assertNull(SingBackendLabel.fallback(GPU, listOf(GPU to FAILED)))
    }
}
