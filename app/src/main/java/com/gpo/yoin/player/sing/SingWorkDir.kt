package com.gpo.yoin.player.sing

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

/**
 * Scratch float buffers for one song, memory-mapped from files in [dir] instead of the Java heap: a
 * 3-minute stereo song is ~64 MB of float samples at 44.1 kHz, and the pipeline holds several such
 * signals. Each file is unlinked as soon as it's mapped (the mapping keeps it alive), so nothing is
 * left behind if the process dies; [close] drops the mappings' last references.
 */
class SingWorkDir(private val dir: File) : Closeable {
    private val open = mutableListOf<FloatBuffer>()

    init {
        dir.mkdirs()
        // Leftovers of a process that died mid-song.
        dir.listFiles()?.forEach { it.delete() }
    }

    fun floats(count: Int): FloatBuffer {
        require(count >= 0)
        val file = File.createTempFile("sing", ".f32", dir)
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val bytes = count.toLong() * 4
                raf.setLength(bytes)
                val buffer = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, bytes)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                synchronized(open) { open += buffer }
                return buffer
            }
        } finally {
            file.delete()
        }
    }

    /** Lets go of a buffer this work dir handed out (it was outgrown). */
    fun release(buffer: FloatBuffer) {
        synchronized(open) { open.remove(buffer) }
    }

    /** A new file in [dir] for a whole-file intermediate (the source bytes); the caller deletes it. */
    fun file(suffix: String): File = File.createTempFile("sing", suffix, dir)

    override fun close() {
        synchronized(open) { open.clear() }
    }
}
