package com.gpo.yoin.data.sync.backup

import com.gpo.yoin.shared.portability.BackupFormat
import com.gpo.yoin.shared.portability.BackupParser
import com.gpo.yoin.shared.portability.BackupReadException
import com.gpo.yoin.shared.portability.ParsedBackup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** Reads a backup zip ([BackupFormat]) from a stream the caller owns; :shared checks it ([BackupParser]). */
object BackupReader {
    fun read(input: InputStream): ParsedBackup {
        var manifestBytes: ByteArray? = null
        var snapshotBytes: ByteArray? = null
        var readable = 0
        try {
            val zip = ZipInputStream(input)
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.isDirectory -> Unit
                    entry.name == BackupFormat.MANIFEST_PATH ->
                        manifestBytes = zip.readLimited(BackupFormat.MAX_MANIFEST_BYTES)
                    entry.name == BackupFormat.SNAPSHOT_PATH ->
                        snapshotBytes = zip.readLimited(BackupFormat.MAX_SNAPSHOT_BYTES)
                    entry.name.startsWith(BackupFormat.READABLE_DIR) -> readable++
                }
                zip.closeEntry()
            }
        } catch (e: ZipException) {
            throw BackupReadException.NotABackup("Not a zip file")
        } catch (e: IOException) {
            throw BackupReadException.Corrupt("Couldn't read the backup", e)
        }
        return BackupParser.parse(manifestBytes, snapshotBytes, readable)
    }

    private fun InputStream.readLimited(limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) throw BackupReadException.Corrupt("Backup entry larger than $limit bytes")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
