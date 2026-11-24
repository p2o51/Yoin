package com.gpo.yoin.player.sing

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/**
 * LiteRT builds on this device, ready to open: their meta ([catalog]) and the directory its `file`s are
 * relative to. [origin] says where they came from ("Play AI Pack sing_model_pack, group other", "bundled").
 */
data class SingModelSet(val origin: String, val catalog: LiteRtCatalog, val dir: File) {
    fun fileFor(meta: LiteRtModelMeta): File = File(dir, meta.file)

    /** Every build the meta lists is on disk at its size (no hashing: [SingModelFiles.verify] does that). */
    fun complete(): Boolean = catalog.models.isNotEmpty() && catalog.models.all { fileFor(it).length() == it.bytes }
}

/**
 * Where on-device Sing's models come from ([SingModelResolver] tries them in order): the Play AI Pack
 * `sing_model_pack` ([AiPackSingModelSource], per-SoC builds Play delivers on demand), or the builds
 * bundled in a `-PyoinSingBundledModels=true` APK ([BundledSingModelSource]).
 */
interface SingModelSource {
    /** For logs: "Play AI Pack", "bundled". */
    val name: String

    /** The builds if they are on this device already (nothing downloaded or copied), else null. Blocking, cheap. */
    fun local(): SingModelSet?

    /**
     * The builds, downloading or copying them first ([onProgress] 0..1), SHA-256-checked. Throws
     * IOException when this source can't provide them.
     */
    suspend fun obtain(onProgress: (Float) -> Unit): SingModelSet
}

/**
 * Picks where the models come from: the first source that has them on the device already; else each in
 * order asked to obtain them (download, copy) until one does. None can → [SingModelUnavailable] (an
 * IOException: [OnDeviceSingStreamProvider] reports it as [SingFailure.ModelUnavailable]).
 */
class SingModelResolver(
    val sources: List<SingModelSource>,
    private val log: (String) -> Unit = {},
) {
    /** The builds already on the device, from the first source that has them; null if none does. */
    fun local(): SingModelSet? {
        for (source in sources) {
            val set = try {
                source.local()
            } catch (e: Exception) {
                log("${source.name}: not readable here (${e.message ?: e.javaClass.simpleName})")
                null
            }
            if (set != null) {
                log("models on the device from ${set.origin}: ${describe(set)}")
                return set
            }
        }
        return null
    }

    suspend fun resolve(onProgress: (Float) -> Unit = {}): SingModelSet {
        local()?.let { return it }
        if (sources.isEmpty()) throw SingModelUnavailable("no model source in this build")
        val failures = mutableListOf<String>()
        for (source in sources) {
            try {
                log("${source.name}: obtaining the models")
                val set = source.obtain(onProgress)
                log("models obtained from ${set.origin}: ${describe(set)}")
                return set
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                log("${source.name}: unavailable (${e.message ?: e.javaClass.simpleName})")
                failures += "${source.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw SingModelUnavailable(failures.joinToString("; "))
    }

    private fun describe(set: SingModelSet): String =
        set.catalog.models.joinToString { m ->
            "${m.id ?: m.file} (${SingLiteRtPlan.TRUSTED_SHA256[m.sha256.lowercase()] ?: "untrusted"}, ${m.accelerators.joinToString("/")})"
        }
}

/** No source could provide Sing's models (no Play, pack unavailable, nothing bundled). */
class SingModelUnavailable(message: String) : IOException(message)

/** SHA-256 checks of model files against their meta and [SingLiteRtPlan.TRUSTED]. */
object SingModelFiles {
    fun sha256(file: File): String {
        if (!file.isFile) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Throws IOException unless [file] is [meta]'s build — its size, its SHA-256 — and a trusted one. */
    fun verify(file: File, meta: LiteRtModelMeta) {
        if (!file.isFile) throw IOException("${meta.file}: missing")
        if (file.length() != meta.bytes) throw IOException("${meta.file}: ${file.length()} B, not ${meta.bytes}")
        val sha = sha256(file)
        if (sha != meta.sha256.lowercase() || sha !in SingLiteRtPlan.TRUSTED) {
            throw IOException("${meta.file}: SHA-256 $sha is not the trusted ${meta.sha256}")
        }
    }

    /** Every build of [set] verified ([verify]). */
    fun verifyAll(set: SingModelSet) {
        if (set.catalog.models.isEmpty()) throw IOException("${set.origin}: the meta lists no build")
        set.catalog.models.forEach { verify(set.fileFor(it), it) }
    }
}
