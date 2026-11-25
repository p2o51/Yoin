package com.gpo.yoin.player.sing

import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SingModelManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `baseline identifies Tensor G6 and G5 TPU targets before download`() {
        val g6 = SingModelOverview.baseline("Google", "Tensor G6", npuAllowed = true, bundledInApk = false)
        assertTrue(g6.isTensorTpu)
        assertEquals(TensorGeneration.G6, g6.tensorGeneration)
        assertEquals("Tensor G6 AOT", g6.expectedBuildName)
        assertEquals(SingModelOverview.GROUP_TENSOR_G6, g6.expectedPackGroup)
        assertTrue(g6.distributionLine().contains("Google_Tensor_G6"))

        val g5 = SingModelOverview.baseline("Google", "Tensor G5", npuAllowed = true, bundledInApk = false)
        assertTrue(g5.isTensorTpu)
        assertEquals(TensorGeneration.G5, g5.tensorGeneration)
        assertEquals("Tensor G5 AOT", g5.expectedBuildName)
        assertEquals(SingModelOverview.GROUP_TENSOR_G5, g5.expectedPackGroup)

        val generic = SingModelOverview.baseline("Qualcomm", "SM8750", npuAllowed = true, bundledInApk = false)
        assertFalse(generic.isTensorTpu)
        assertEquals(null, generic.tensorGeneration)
        assertEquals(null, generic.optimizedForTarget)
        assertEquals("fp16", generic.expectedBuildName)
        assertEquals(SingModelOverview.GROUP_OTHER, generic.expectedPackGroup)

        val mediatekNpu = SingModelOverview(
            status = SingModelDownloadStatus.Ready,
            expectedBuildName = "Dimensity 9400 AOT",
            expectedPackGroup = "MediaTek_Dimensity_9400",
            installedGroup = "MediaTek_Dimensity_9400",
            installedBuilds = listOf(
                SingInstalledBuild("Dimensity 9400 AOT", 7_100_000L, "abcdef12", listOf(SingAccelerator.NPU)),
            ),
            soc = "Mediatek Dimensity 9400",
            lastRun = SingBackendRun(SingAccelerator.NPU, model = "Dimensity 9400 AOT", soc = "Mediatek Dimensity 9400"),
        )
        assertFalse(mediatekNpu.isTensorTpu)
        assertEquals(null, mediatekNpu.tensorGeneration)
        assertEquals("MediaTek Dimensity 9400", mediatekNpu.optimizedForTarget)
    }

    @Test
    fun `refresh, download, and remove transition state cleanly and invoke storage callback`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val dir = tmp.newFolder("pack")
        val modelFile = File(dir, "lyra.tflite").apply { writeBytes(ByteArray(2048)) }
        val meta = LiteRtModelMeta(
            id = "g6_aot",
            file = modelFile.name,
            bytes = modelFile.length(),
            sha256 = "0534f3d74f7f0d5e5f32131d70ff25550399becaf3b45098f81b769119c2b355",
            precision = "int16x8",
            accelerators = listOf(SingAccelerator.NPU),
            socManufacturer = "Google",
            socModel = "Tensor G6",
        )
        val modelSet = SingModelSet(
            origin = "Play AI Pack sing_model_pack, group Google_Tensor_G6",
            catalog = LiteRtCatalog(group = "Google_Tensor_G6", models = listOf(meta)),
            dir = dir,
        )

        var installed = false
        var invalidated = 0
        val source = object : SingModelSource {
            override val name = "Play AI Pack sing_model_pack"
            override fun local(): SingModelSet? = if (installed) modelSet else null
            override suspend fun obtain(onProgress: (Float) -> Unit): SingModelSet {
                onProgress(0.5f)
                installed = true
                onProgress(1f)
                return modelSet
            }

            override suspend fun clear(): Boolean {
                val was = installed
                installed = false
                return was
            }
        }

        val manager = SingModelManager(
            resolver = SingModelResolver(listOf(source)),
            report = null,
            scope = backgroundScope,
            socManufacturer = "Google",
            socModel = "Tensor G6",
            npuAllowed = true,
            bundledInApk = false,
            ioDispatcher = dispatcher,
            onModelStorageChanged = { invalidated += 1 },
        )

        manager.refresh()
        advanceUntilIdle()
        assertEquals(SingModelDownloadStatus.NotDownloaded, manager.state.value.status)

        manager.downloadNow()
        advanceUntilIdle()
        assertEquals(SingModelDownloadStatus.Ready, manager.state.value.status)
        assertEquals("Google_Tensor_G6", manager.state.value.installedGroup)
        assertEquals(1, manager.state.value.installedBuilds.size)
        assertEquals("Tensor G6 AOT", manager.state.value.installedBuilds.single().name)
        assertEquals(1, invalidated)

        manager.removeModel()
        advanceUntilIdle()
        assertEquals(SingModelDownloadStatus.NotDownloaded, manager.state.value.status)
        assertTrue(manager.state.value.installedBuilds.isEmpty())
        assertEquals(2, invalidated)
    }

    @Test
    fun `download failure surfaces readable error state`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val failingSource = object : SingModelSource {
            override val name = "Play AI Pack sing_model_pack"
            override fun local(): SingModelSet? = null
            override suspend fun obtain(onProgress: (Float) -> Unit): SingModelSet {
                throw IOException("PACK_UNAVAILABLE")
            }
        }
        val manager = SingModelManager(
            resolver = SingModelResolver(listOf(failingSource)),
            report = null,
            scope = backgroundScope,
            socManufacturer = "Google",
            socModel = "GS201",
            npuAllowed = true,
            bundledInApk = false,
            ioDispatcher = dispatcher,
        )

        manager.downloadNow()
        advanceUntilIdle()
        val status = manager.state.value.status
        assertTrue(status is SingModelDownloadStatus.Failed)
        assertTrue((status as SingModelDownloadStatus.Failed).message.contains("PACK_UNAVAILABLE"))
    }
}
