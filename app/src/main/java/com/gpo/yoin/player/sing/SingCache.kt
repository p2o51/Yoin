package com.gpo.yoin.player.sing

import java.io.File
import java.security.MessageDigest

/**
 * Sing results — the on-device engine's vocal tracks ([SingVocalTrack]): finished ones ([suffix]
 * `.vtrack`) and unfinished ones ([PARTIAL_SUFFIX], a song Sing stopped working on, carried on next
 * time) — least recently used out first once the directory passes [maxBytes] (1 GB unless set; a
 * 4-minute song at 44.1 kHz is ~64 MB). Partial files count and age like finished ones. A hit marks
 * the file used by moving its modification time to [clock]; nothing else is kept on the side, so a
 * cleared app cache is simply a cold cache. A file being written is [pin]ned: never evicted.
 */
class SingCache(
    val dir: File,
    @Volatile var maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
    private val suffix: String = SUFFIX,
) {
    private val pinned = HashSet<File>()

    /** The cached stream for [key], marked as just used; null on a miss. */
    @Synchronized
    fun get(key: String): File? {
        val file = fileFor(key)
        if (!file.isFile) return null
        file.setLastModified(clock())
        return file
    }

    /** [key]'s unfinished track, marked as just used; null when there is none. */
    @Synchronized
    fun getPartial(key: String): File? {
        val file = partialFile(key)
        if (!file.isFile) return null
        file.setLastModified(clock())
        return file
    }

    /** Where [key]'s unfinished track lives (kept, counted, evicted like a finished one). */
    fun partialFile(key: String): File {
        dir.mkdirs()
        return File(dir, name(key) + PARTIAL_SUFFIX)
    }

    /** Keeps [file] out of eviction while it's being written. */
    @Synchronized
    fun pin(file: File) {
        pinned += file
    }

    @Synchronized
    fun unpin(file: File) {
        pinned -= file
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
        pinned -= part
        target.setLastModified(clock())
        trim(keep = target)
        return target
    }

    @Synchronized
    fun remove(key: String) {
        fileFor(key).delete()
        partialFile(key).delete()
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
            if (file == keep || file in pinned) continue
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    fun fileFor(key: String): File = File(dir, name(key) + suffix)

    /**
     * Deletes scratch files left by work that never finished (a process that died mid-song); partial
     * tracks stay, to be carried on.
     */
    @Synchronized
    fun clearParts() {
        dir.listFiles { f -> f.isFile && (f.name.endsWith(PART_SUFFIX) || f.name.endsWith(REMAKE_SUFFIX)) }?.forEach { it.delete() }
    }

    private fun entries(): List<File> =
        dir.listFiles { f -> f.isFile && (f.name.endsWith(suffix) || f.name.endsWith(PARTIAL_SUFFIX)) }?.toList().orEmpty()

    companion object {
        const val DEFAULT_MAX_BYTES = 1L shl 30
        const val SUFFIX = ".m4a"
        const val PART_SUFFIX = ".part"

        /** A partial track being remade at the song's true length (gone once moved in). */
        const val REMAKE_SUFFIX = ".new"

        /** An unfinished vocal track, kept to be carried on. */
        const val PARTIAL_SUFFIX = ".vpart"

        /**
         * A stream's key: the request (profile, provider, song) and the model that made it, so a new
         * model never plays an old result.
         */
        fun keyFor(request: SingRequest, modelSha256: String): String = "${request.key}|$modelSha256"

        private fun name(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(40)
    }
}
