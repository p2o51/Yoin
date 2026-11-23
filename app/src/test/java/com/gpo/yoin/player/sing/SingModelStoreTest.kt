package com.gpo.yoin.player.sing

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SingModelStoreTest {
    private val server = MockWebServer()
    private val dir: File = Files.createTempDirectory("sing-model").toFile()
    private val bytes = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun store(sha256: String) = SingModelStore(
        dir,
        OkHttpClient(),
        SingModelConfig(baseUrl = server.url("/models").toString(), sha256 = sha256, bytes = bytes.size.toLong()),
    )

    private fun enqueue() = server.enqueue(MockResponse().setBody(Buffer().write(bytes)))

    @Test
    fun should_downloadOnceAndVerify_when_hashMatches() = runTest {
        enqueue()
        val s = store(sha)
        val file = s.ensure()
        assertArrayEquals(bytes, file.readBytes())
        assertEquals("/models/lyra_sing_lite_fp32.onnx", server.takeRequest().path)
        // Second call: already here, no request.
        s.ensure()
        assertEquals(1, server.requestCount)
        assertEquals(sha, SingModelStore.sha256(file))
    }

    @Test
    fun should_refuseFile_when_hashDiffers() = runTest {
        enqueue()
        val s = store("0".repeat(64))
        val error = runCatching { s.ensure() }.exceptionOrNull()
        assertTrue(error is IOException)
        assertFalse(s.file.exists())
        assertFalse(File(dir, s.file.name + ".part").exists())
    }

    @Test
    fun should_pinOfficialHashes_when_variantsListed() {
        assertEquals("e475d5992091378f0eec4c99ea63e4d7b9abca72d3ef4d396f09a864544bf03e", SingModelVariant.FP32.sha256)
        assertEquals(SingModelVariant.FP32, SingModelConfig().variant)
        assertTrue(SingModelVariant.entries.none { it.fileName.contains("int8") })
        assertTrue(SingModelConfig().url.endsWith("/lyra_sing_lite_fp32.onnx"))
    }
}
