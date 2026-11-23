package com.gpo.yoin.player.sing

import java.io.File
import java.security.MessageDigest

/**
 * Finished Sing streams (4-channel .m4a), least recently used out first once the directory passes
 * [maxBytes] (1 GB unless set). A hit marks the file used by moving its modification time to [clock];
 * nothing else is kept on the side, so a cleared app cache is simply a cold cache.
 */
class SingCache(
    val dir: File,
    @Volatile var maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The cached stream for [key], marked as just used; null on a miss. */
    @Synchronized
    fun get(key: String): File? {
        val file = fileFor(key)
        if (!file.isFile) return null
        file.setLastModified(clock())
        return file
    }

    /** A scratch file next to the cache for [key], for [commit] to move in when it's complete. */
    fun partFile(key: String): File {
        dir.mkdirs()
        return File(dir, "${name(key)}$PART_SUFFIX")
    }

    /** Moves [part] in as [key]'s stream and evicts down to [maxBytes] (never the one just added). */
    @Synchronized
    fun commit(key: String, part: File): File {
        dir.mkdirs()
        val target = fileFor(key)
        target.delete()
        check(part.renameTo(target)) { "could not move ${part.name} into the Sing cache" }
        target.setLastModified(clock())
        trim(keep = target)
        return target
    }

    @Synchronized
    fun remove(key: String) {
        fileFor(key).delete()
    }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun sizeBytes(): Long = entries().sumOf { it.length() }

    @Synchronized
    fun trim(keep: File? = null) {
        var total = sizeBytes()
        if (total <= maxBytes) return
        for (file in entries().sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            if (file == keep) continue
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    fun fileFor(key: String): File = File(dir, name(key) + SUFFIX)

    private fun entries(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }?.toList().orEmpty()

    companion object {
        const val DEFAULT_MAX_BYTES = 1L shl 30
        const val SUFFIX = ".m4a"
        const val PART_SUFFIX = ".part"

        /**
         * A stream's key: the request (profile, provider, song) and the model that made it, so a new
         * model never plays an old result.
         */
        fun keyFor(request: SingRequest, modelSha256: String): String = "${request.key}|$modelSha256"

        private fun name(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(40)
    }
}
