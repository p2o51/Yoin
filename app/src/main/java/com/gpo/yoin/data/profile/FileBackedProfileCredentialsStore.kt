package com.gpo.yoin.data.profile

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * [ProfileCredentialsStore] that writes one envelope-per-profile file
 * under [storageDir].
 *
 * In production, [storageDir] is `context.noBackupFilesDir +
 * "/profile_credentials"` so the framework auto-excludes the directory
 * from cloud backup. Tests pass a temp directory.
 *
 * Atomic writes are done with a write-to-`.tmp` + backup/rename dance.
 * When replacing an existing file we first move the old payload to a
 * sibling `.bak`, then move the new temp file into place, then delete
 * the backup. If the process dies in the middle, reads fall back to the
 * backup copy instead of misclassifying the profile as "credentials
 * missing". We deliberately keep this plain-JDK rather than pull
 * `android.util.AtomicFile` so the same code runs in JVM unit tests
 * without Android shims.
 */
class FileBackedProfileCredentialsStore(
    private val storageDir: File,
    private val codec: ProfileCredentialsCodec,
) : ProfileCredentialsStore {

    override fun write(profileId: String, credentials: ProfileCredentials) {
        ensureStorageDir()
        val target = fileFor(profileId)
        val tmp = File(storageDir, "${profileId}$TMP_SUFFIX")
        val backup = File(storageDir, "${profileId}$BACKUP_SUFFIX")
        val envelope = codec.encode(credentials).toByteArray(Charsets.UTF_8)

        try {
            tmp.writeBytes(envelope)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }

        try {
            if (target.exists()) {
                moveIntoPlace(source = target, target = backup, replaceExisting = true)
            }
            moveIntoPlace(source = tmp, target = target, replaceExisting = true)
            backup.takeIf(File::exists)?.delete()
        } catch (e: IOException) {
            tmp.delete()
            // Best effort: restore the last known-good payload so callers
            // don't observe a fake "missing credentials" state after a
            // failed refresh write.
            if (!target.exists() && backup.exists()) {
                runCatching {
                    moveIntoPlace(source = backup, target = target, replaceExisting = true)
                }
            }
            throw e
        }
    }

    override fun read(profileId: String): ProfileCredentials? {
        val file = fileFor(profileId)
        val backup = File(storageDir, "${profileId}$BACKUP_SUFFIX")
        val readable = when {
            file.exists() -> file
            backup.exists() -> backup
            else -> return null
        }
        val envelope = readable.readText(Charsets.UTF_8)
        // codec.decode throws on malformed envelope or cipher failure —
        // bubble up so callers can tell "missing" (null) from "broken".
        return codec.decode(envelope)
    }

    override fun delete(profileId: String) {
        val file = fileFor(profileId)
        if (file.exists()) file.delete()
        File(storageDir, "${profileId}$BACKUP_SUFFIX").takeIf(File::exists)?.delete()
        // Belt-and-braces: also clean up any stray tmp from a crashed write.
        File(storageDir, "${profileId}$TMP_SUFFIX").takeIf(File::exists)?.delete()
    }

    private fun ensureStorageDir() {
        if (!storageDir.exists() && !storageDir.mkdirs() && !storageDir.exists()) {
            throw IOException("Couldn't create credentials directory at ${storageDir.absolutePath}")
        }
    }

    private fun fileFor(profileId: String): File =
        File(storageDir, "$profileId$BIN_SUFFIX")

    private fun moveIntoPlace(
        source: File,
        target: File,
        replaceExisting: Boolean,
    ) {
        val options = buildList {
            if (replaceExisting) add(StandardCopyOption.REPLACE_EXISTING)
            add(StandardCopyOption.ATOMIC_MOVE)
        }.toTypedArray()
        try {
            Files.move(source.toPath(), target.toPath(), *options)
        } catch (_: AtomicMoveNotSupportedException) {
            val fallbackOptions = buildList {
                if (replaceExisting) add(StandardCopyOption.REPLACE_EXISTING)
            }.toTypedArray()
            Files.move(source.toPath(), target.toPath(), *fallbackOptions)
        }
    }

    companion object {
        private const val BIN_SUFFIX = ".bin"
        private const val TMP_SUFFIX = ".bin.tmp"
        private const val BACKUP_SUFFIX = ".bin.bak"
    }
}
