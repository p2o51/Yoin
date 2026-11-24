package com.gpo.yoin.player.sing

import android.content.Context
import io.mockk.mockk
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Where Sing's models come from: the Play AI Pack (on the device / fetched / fetch failing), the bundled builds
 * of a `-PyoinSingBundledModels=true` APK, neither; the per-group metas of :sing-model-pack; the SHA-256 checks.
 */
class SingModelSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    /** :sing-model-pack's assets (unit tests run in app/). */
    private val packAssets = File("../sing-model-pack/src/main/assets")
    private val groups = listOf("Google_Tensor_G6", "Google_Tensor_G5", "other")

    private fun groupDir(group: String) = File(packAssets, "model#group_$group")

    private fun meta(group: String) = SingLiteRtPlan.parse(File(groupDir(group), SingLiteRtPlan.META_FILE).readText())

    /** Lays out what Play delivers for [group]: <assets>/model/{models.json, lyra_sing_lite.tflite}. */
    private fun delivered(group: String): File {
        val assets = tmp.newFolder("pack-$group", "assets")
        groupDir(group).copyRecursively(File(assets, AiPackSingModelSource.MODEL_DIR))
        return assets
    }

    private class FakeAiPack(
        var location: File? = null,
        private val onFetch: FakeAiPack.((Float) -> Unit) -> Unit = { throw IOException("fetch failed (API_NOT_AVAILABLE)") },
    ) : AiPackClient {
        var fetches = 0
        override fun assetsPath(pack: String): String? = location?.path

        override suspend fun fetch(pack: String, onProgress: (Float) -> Unit) {
            fetches++
            onFetch(onProgress)
        }
    }

    private class DirAssets(private val root: File) : SingAssetReader {
        val opened = mutableListOf<String>()
        override fun list(path: String): List<String> = File(root, path).list()?.toList().orEmpty()

        override fun open(path: String): InputStream {
            opened += path
            return FileInputStream(File(root, path))
        }
    }

    /** A bundled-models APK's assets: sing/bundled/<group>/ as app/build.gradle.kts copies them. */
    private fun bundledAssets(): File {
        val root = tmp.newFolder("apk-assets")
        for (group in groups) groupDir(group).copyRecursively(File(root, "${BundledSingModelSource.ASSET_DIR}/$group"))
        return root
    }

    private fun bundled(soc: Pair<String?, String?>, root: File = bundledAssets(), dir: File = tmp.newFolder("nobackup")) =
        BundledSingModelSource(DirAssets(root), dir) { SingLiteRtPlan.matchesSoc(it, soc.first, soc.second) }

    // --- the per-group metas -------------------------------------------------------------------------------

    @Test
    fun should_describeEachGroupsBuild_when_packMetasParsed() {
        val g6 = meta("Google_Tensor_G6")
        assertEquals("sing_model_pack", g6.pack)
        assertEquals("Google_Tensor_G6", g6.group)
        assertEquals("lyra_sing_lite-tensor_g6-aot", g6.models.single().id)
        assertEquals(listOf(SingAccelerator.NPU), g6.models.single().accelerators)
        assertEquals("Tensor G6" to "Google", g6.models.single().let { it.socModel to it.socManufacturer })
        val g5 = meta("Google_Tensor_G5")
        assertEquals(listOf(SingAccelerator.NPU), g5.models.single().accelerators)
        assertEquals("Tensor G5", g5.models.single().socModel)
        val other = meta("other")
        assertEquals("lyra_sing_lite-fp16", other.models.single().id)
        assertEquals(listOf(SingAccelerator.GPU, SingAccelerator.CPU), other.models.single().accelerators)
        assertNull(other.models.single().socModel)
    }

    @Test
    fun should_matchFilesAndTrustedBuilds_when_packMetasChecked() {
        for (group in groups) {
            for (m in meta(group).models) {
                val file = File(groupDir(group), m.file)
                SingModelFiles.verify(file, m) // size, SHA-256, trusted
                assertEquals(SingLiteRtPlan.TRUSTED.getValue(m.sha256).socModel, m.socModel)
            }
        }
        assertEquals(SingLiteRtPlan.TRUSTED.keys, groups.flatMap { g -> meta(g).models.map { it.sha256 } }.toSet())
    }

    @Test
    fun should_agreeWithDeviceTargeting_when_groupsAndSocsCompared() {
        val xml = File("device_targeting_configuration.xml").readText()
        val declared = Regex("""<config:device-group name="([^"]+)">\s*<config:device-selector>\s*<config:system-on-chip manufacturer="([^"]+)" model="([^"]+)"/>""")
            .findAll(xml).map { it.groupValues.let { (_, g, maker, model) -> Triple(g, maker, model) } }.toList()
        // "other" is Play's own default group: never declared, every device no group matched.
        assertEquals(listOf("Google_Tensor_G6", "Google_Tensor_G5"), declared.map { it.first })
        for ((group, maker, model) in declared) {
            val m = meta(group).models.single()
            assertEquals(group, maker, m.socManufacturer)
            assertEquals(group, model, m.socModel)
        }
        assertEquals(groups.map { "model#group_$it" }.toSet(), packAssets.list()!!.toSet())
    }

    // --- the AI Pack ----------------------------------------------------------------------------------------

    @Test
    fun should_useDeliveredPack_withoutFetching_when_packLocationPresent() = runBlocking {
        val client = FakeAiPack(location = delivered("Google_Tensor_G5"))
        val resolver = SingModelResolver(listOf(AiPackSingModelSource(client)))
        val local = resolver.local()
        assertNotNull(local)
        assertEquals("Google_Tensor_G5", local!!.catalog.group)
        val set = resolver.resolve()
        assertEquals(0, client.fetches)
        assertEquals("lyra_sing_lite-tensor_g5-aot", set.catalog.models.single().id)
        assertTrue(set.fileFor(set.catalog.models.single()).isFile)
        assertTrue(set.origin, set.origin.contains("Google_Tensor_G5"))
    }

    @Test
    fun should_fetchWithProgress_when_packLocationAbsent() = runBlocking {
        val assets = delivered("other")
        val client = FakeAiPack { progress ->
            progress(0.5f)
            location = assets
        }
        val source = AiPackSingModelSource(client)
        assertNull(source.local())
        val seen = mutableListOf<Float>()
        val set = SingModelResolver(listOf(source)).resolve { seen += it }
        assertEquals(1, client.fetches)
        assertEquals(listOf(0.5f, 1f), seen)
        assertEquals("other", set.catalog.group)
        val plan = SingLiteRtPlan.plan(set.catalog, "Google", "Tensor G2", npuAllowed = true)
        assertEquals(listOf(SingAccelerator.GPU, SingAccelerator.CPU), plan.map { it.accelerator })
    }

    @Test
    fun should_reportModelUnavailable_when_fetchFails() = runBlocking {
        val client = FakeAiPack()
        try {
            SingModelResolver(listOf(AiPackSingModelSource(client))).resolve()
            fail("resolved without a model")
        } catch (e: SingModelUnavailable) {
            assertTrue(e.message, e.message!!.contains("API_NOT_AVAILABLE"))
        }
        assertEquals(1, client.fetches)
    }

    @Test
    fun should_reportModelUnavailable_when_fetchedButNoLocation() = runBlocking {
        val client = FakeAiPack(onFetch = { })
        val e = runCatching { SingModelResolver(listOf(AiPackSingModelSource(client))).resolve() }.exceptionOrNull()
        assertTrue(e is SingModelUnavailable)
    }

    @Test
    fun should_rejectDeliveredBuild_when_hashDoesNotMatch() = runBlocking {
        val assets = delivered("other")
        File(assets, "model/lyra_sing_lite.tflite").let { f ->
            val bytes = f.readBytes()
            bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
            f.writeBytes(bytes)
        }
        val client = FakeAiPack { location = assets }
        val e = runCatching { SingModelResolver(listOf(AiPackSingModelSource(client))).resolve() }.exceptionOrNull()
        assertTrue(e.toString(), e is SingModelUnavailable && e.message!!.contains("SHA-256"))
    }

    @Test
    fun should_treatPackAsAbsent_when_deliveredWithoutMeta() = runBlocking {
        val assets = delivered("other")
        File(assets, "model/${SingLiteRtPlan.META_FILE}").delete()
        val source = AiPackSingModelSource(FakeAiPack(location = assets))
        assertNull(SingModelResolver(listOf(source)).local())
        val e = runCatching { SingModelResolver(listOf(source)).resolve() }.exceptionOrNull()
        assertTrue(e.toString(), e is SingModelUnavailable && e.message!!.contains("models.json"))
    }

    // --- bundled builds -------------------------------------------------------------------------------------

    @Test
    fun should_useBundledBuilds_beforeThePack_when_bundledFlagOn() = runBlocking {
        val client = FakeAiPack()
        val resolver = SingModelResolver(listOf(bundled("Google" to "Tensor G5"), AiPackSingModelSource(client)))
        assertNull(resolver.local()) // not copied out yet
        val set = resolver.resolve()
        assertEquals(0, client.fetches)
        assertEquals(setOf("Google_Tensor_G5/lyra_sing_lite.tflite", "other/lyra_sing_lite.tflite"), set.catalog.models.map { it.file }.toSet())
        set.catalog.models.forEach { SingModelFiles.verify(set.fileFor(it), it) }
        val plan = SingLiteRtPlan.plan(set.catalog, "Google", "Tensor G5", npuAllowed = true)
        assertEquals(
            listOf("Tensor G5 AOT" to SingAccelerator.NPU, "fp16" to SingAccelerator.GPU, "fp16" to SingAccelerator.CPU),
            plan.map { LiteRtSingBackend.buildName(it) to it.accelerator },
        )
        // Copied once: on the device from then on.
        assertNotNull(resolver.local())
    }

    @Test
    fun should_copyOnlyThisDevicesBuilds_when_bundled() = runBlocking {
        val root = bundledAssets()
        val assets = DirAssets(root)
        val source = BundledSingModelSource(assets, tmp.newFolder("nb")) { SingLiteRtPlan.matchesSoc(it, "Qualcomm", "SM8750") }
        val set = source.obtain {}
        assertEquals(listOf("other/lyra_sing_lite.tflite"), set.catalog.models.map { it.file })
        assertEquals(listOf("${BundledSingModelSource.ASSET_DIR}/other/lyra_sing_lite.tflite"), assets.opened.filter { it.endsWith(".tflite") })
    }

    @Test
    fun should_fallBackToThePack_when_nothingBundled() = runBlocking {
        val empty = tmp.newFolder("no-assets")
        val assets = delivered("other")
        val client = FakeAiPack { location = assets }
        val set = SingModelResolver(listOf(bundled(null to null, root = empty), AiPackSingModelSource(client))).resolve()
        assertEquals(1, client.fetches)
        assertEquals("other", set.catalog.group)
    }

    @Test
    fun should_reportModelUnavailable_when_noSourceHasTheModels() = runBlocking {
        val e = runCatching {
            SingModelResolver(listOf(bundled(null to null, root = tmp.newFolder("none")), AiPackSingModelSource(FakeAiPack()))).resolve()
        }.exceptionOrNull()
        assertTrue(e.toString(), e is SingModelUnavailable && e.message!!.contains("bundled") && e.message!!.contains("AI Pack"))
        assertTrue(runCatching { SingModelResolver(emptyList()).resolve() }.exceptionOrNull() is SingModelUnavailable)
    }

    // --- hash checks ----------------------------------------------------------------------------------------

    @Test
    fun should_checkSizeShaAndTrust_when_verifyingAFile() {
        val m = meta("other").models.single()
        val good = File(groupDir("other"), m.file)
        SingModelFiles.verify(good, m)
        assertEquals(m.sha256, SingModelFiles.sha256(good))

        fun rejects(file: File, meta: LiteRtModelMeta) =
            assertTrue(runCatching { SingModelFiles.verify(file, meta) }.exceptionOrNull() is IOException)

        rejects(File(tmp.root, "missing.tflite"), m)
        val short = tmp.newFile("short.tflite").apply { writeBytes(good.readBytes().copyOf(1000)) }
        rejects(short, m)
        // The right size and the meta's own hash, but not a build this code trusts.
        val other = tmp.newFile("other.tflite").apply { writeBytes(ByteArray(m.bytes.toInt()) { 7 }) }
        rejects(other, m.copy(sha256 = SingModelFiles.sha256(other)))
        // A trusted hash in the meta, but the file is another build.
        rejects(File(groupDir("Google_Tensor_G5"), "lyra_sing_lite.tflite"), meta("Google_Tensor_G5").models.single().copy(sha256 = m.sha256))
    }

    // --- the backend before and after the models are here ---------------------------------------------------

    @Test
    fun should_nameExpectedBuild_beforeTheModelsAreHere() {
        val g5 = "10bd17fb365d"
        val fp16 = "176b9dd47bcc"
        assertEquals(g5, SingLiteRtPlan.expectedSha256("Google", "Tensor G5", true)?.take(12))
        assertEquals("0534f3d74f7f", SingLiteRtPlan.expectedSha256("Google", "Tensor G6", true)?.take(12))
        assertEquals(fp16, SingLiteRtPlan.expectedSha256("Google", "Tensor G5", false)?.take(12))
        assertEquals(fp16, SingLiteRtPlan.expectedSha256("Google", "Tensor G2", true)?.take(12))
        assertEquals(fp16, SingLiteRtPlan.expectedSha256(null, null, false)?.take(12))

        val context = mockk<Context>(relaxed = true)
        val backend = LiteRtSingBackend(context, SingModelResolver(listOf(AiPackSingModelSource(FakeAiPack()))), 2, "Google", "Tensor G5", true)
        assertEquals("litert:$g5", backend.modelId)
        assertNull(backend.readyModel)
        assertTrue(backend.plan.isEmpty())
    }

    @Test
    fun should_planDeliveredBuild_when_backendEnsures() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val assets = delivered("Google_Tensor_G6")
        val client = FakeAiPack { location = assets }
        val backend = LiteRtSingBackend(context, SingModelResolver(listOf(AiPackSingModelSource(client))), 2, "Google", "Tensor G6", true)
        val file = backend.ensure {}
        assertEquals(File(assets, "model/lyra_sing_lite.tflite").path, file.path)
        assertEquals(listOf("Tensor G6 AOT on NPU (Tensor TPU)"), backend.plan.map { LiteRtSingBackend.label(it) })
        assertEquals(file.path, backend.readyModel?.path)
    }

    @Test
    fun should_failAsModelUnavailable_when_deliveredGroupHasNothingForThisSoc() = runBlocking {
        // A pack meta whose only build is another SoC's AOT: nothing this device may run.
        val context = mockk<Context>(relaxed = true)
        val client = FakeAiPack(location = delivered("Google_Tensor_G5"))
        val backend = LiteRtSingBackend(context, SingModelResolver(listOf(AiPackSingModelSource(client))), 2, "Qualcomm", "SM8750", true)
        val e = runCatching { backend.ensure {} }.exceptionOrNull()
        assertTrue(e.toString(), e is SingModelUnavailable)
    }

    @Test
    fun should_failAsModelUnavailable_when_packUnavailable() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val backend = LiteRtSingBackend(context, SingModelResolver(listOf(AiPackSingModelSource(FakeAiPack()))), 2, "Google", "Tensor G2", true)
        // OnDeviceSingStreamProvider maps the IOException to SingFailure.ModelUnavailable.
        val e = runCatching { backend.ensure {} }.exceptionOrNull()
        assertTrue(e.toString(), e is IOException)
    }
}
