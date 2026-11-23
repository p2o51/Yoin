package com.gpo.yoin.data.sync.backup

import com.gpo.yoin.data.sync.SyncEnvelope
import com.gpo.yoin.data.sync.engine.MalformedSyncFileException
import com.gpo.yoin.data.sync.engine.SnapshotCodec
import com.gpo.yoin.data.sync.engine.SyncSchemaTooNewException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlinx.serialization.SerializationException

/** A backup that was read and checked: its manifest and its snapshot's records. */
data class ParsedBackup(
    val manifest: BackupManifest,
    val snapshot: SyncEnvelope,
    /** Readable Markdown files present (informational; never read). */
    val readableFiles: Int,
)

/** Why a file couldn't be imported. */
sealed class BackupReadException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Not a zip, or no Yoin manifest in it. */
    class NotABackup(message: String) : BackupReadException(message)

    /** Written by a newer Yoin (backup format or sync schema): update to import it. */
    class TooNew(val formatVersion: Int) : BackupReadException("Backup format $formatVersion is newer than this Yoin")

    /** The snapshot doesn't match the manifest's SHA-256: damaged or edited. */
    class ChecksumMismatch : BackupReadException("Backup data doesn't match its checksum")

    class Corrupt(message: String, cause: Throwable? = null) : BackupReadException(message, cause)
}

/** Reads and validates a backup zip ([BackupFormat]) from a stream the caller owns. */
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
        val manifestText = manifestBytes?.toString(Charsets.UTF_8)
            ?: throw BackupReadException.NotABackup("No ${BackupFormat.MANIFEST_PATH}")
        val manifest = try {
            BackupWriter.MANIFEST_JSON.decodeFromString(BackupManifest.serializer(), manifestText)
        } catch (e: SerializationException) {
            throw BackupReadException.NotABackup("Unreadable manifest")
        } catch (e: IllegalArgumentException) {
            throw BackupReadException.NotABackup("Unreadable manifest")
        }
        if (manifest.format != BackupFormat.FORMAT) throw BackupReadException.NotABackup("Not a Yoin backup")
        if (manifest.formatVersion > BackupFormat.FORMAT_VERSION) throw BackupReadException.TooNew(manifest.formatVersion)
        val bytes = snapshotBytes ?: throw BackupReadException.Corrupt("No ${BackupFormat.SNAPSHOT_PATH}")
        if (!BackupFormat.sha256Hex(bytes).equals(manifest.snapshot.sha256, ignoreCase = true)) {
            throw BackupReadException.ChecksumMismatch()
        }
        val snapshot = try {
            SnapshotCodec.decode(bytes)
        } catch (e: SyncSchemaTooNewException) {
            throw BackupReadException.TooNew(e.schema)
        } catch (e: MalformedSyncFileException) {
            throw BackupReadException.Corrupt("Unreadable snapshot", e)
        }
        if (snapshot.fileClass != BackupFormat.SNAPSHOT_FILE_CLASS) throw BackupReadException.Corrupt("Not a backup snapshot")
        return ParsedBackup(manifest, snapshot, readable)
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
