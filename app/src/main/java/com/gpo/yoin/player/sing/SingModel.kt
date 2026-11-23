package com.gpo.yoin.player.sing

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Lyra-Sing-Lite builds. Never int8 (it smears the vocal); fp16 only runs on the CPU/XNNPACK path
 * ([SingExecution]), and fp32 is the default.
 */
enum class SingModelVariant(val fileName: String, val sha256: String, val bytes: Long) {
    FP32(
        fileName = "lyra_sing_lite_fp32.onnx",
        sha256 = "e475d5992091378f0eec4c99ea63e4d7b9abca72d3ef4d396f09a864544bf03e",
        bytes = 8_633_183L,
    ),
    FP16(
        fileName = "lyra_sing_lite_fp16.onnx",
        sha256 = "8adb86de7330ee24735b5f4e70b07e58b6010a81aeea7205ac0606a4f2570349",
        bytes = 4_368_098L,
    ),
}

/** Where ONNX Runtime runs the model. Both are CPU: XNNPACK is ORT's ARM-optimised CPU kernels. */
enum class SingExecution { Cpu, Xnnpack }

/**
 * Where the model comes from. Not bundled in the APK: downloaded on first use and checked against
 * [SingModelVariant.sha256] before it is ever loaded.
 */
data class SingModelConfig(
    val variant: SingModelVariant = SingModelVariant.FP32,
    val baseUrl: String = PLACEHOLDER_BASE_URL,
    val execution: SingExecution = SingExecution.Xnnpack,
    /** The file's SHA-256 and size; the variant's own unless a test stands in another file. */
    val sha256: String = variant.sha256,
    val bytes: Long = variant.bytes,
) {
    init {
        // fp16 weights are only validated on the CPU/XNNPACK kernels; any other execution provider
        // (NNAPI, QNN, GPU) must run fp32.
        require(variant != SingModelVariant.FP16 || execution in setOf(SingExecution.Cpu, SingExecution.Xnnpack))
    }

    val url: String get() = baseUrl.trimEnd('/') + "/" + variant.fileName

    companion object {
        /**
         * TODO(owner): hosting is undecided (likely a GitHub Release asset of p2o51/Yoin). `.invalid`
         * never resolves, so until it is set a download fails cleanly as [SingFailure.ModelDownload].
         */
        const val PLACEHOLDER_BASE_URL = "https://lyra-sing-lite.invalid/models/v1"
    }
}

/** Downloads the model once into [dir] and hands out the verified file. */
class SingModelStore(
    private val dir: File,
    private val httpClient: OkHttpClient,
    val config: SingModelConfig = SingModelConfig(),
) {
    private val mutex = Mutex()

    val file: File get() = File(dir, config.variant.fileName)

    /** The verified model, downloading it first if it isn't here. Throws [IOException] on any failure. */
    suspend fun ensure(onProgress: (Float) -> Unit = {}): File = mutex.withLock {
        withContext(Dispatchers.IO) {
            val target = file
            if (target.isFile && target.length() == config.bytes) return@withContext target
            dir.mkdirs()
            val part = File(dir, target.name + ".part")
            val request = Request.Builder().url(config.url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("model download: HTTP ${response.code}")
                val body = response.body
                val total = body.contentLength().takeIf { it > 0 } ?: config.bytes
                val digest = MessageDigest.getInstance("SHA-256")
                var read = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            read += n
                            onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                val hex = digest.digest().toHex()
                if (!hex.equals(config.sha256, ignoreCase = true)) {
                    part.delete()
                    throw IOException("model download: SHA-256 $hex does not match ${config.sha256}")
                }
            }
            if (!part.renameTo(target)) {
                part.delete()
                throw IOException("model download: could not move into place")
            }
            target
        }
    }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().toHex()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
