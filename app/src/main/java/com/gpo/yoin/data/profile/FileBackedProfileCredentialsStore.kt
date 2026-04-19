package com.gpo.yoin.data.profile

import java.io.File
import java.io.IOException

/**
 * [ProfileCredentialsStore] that writes one envelope-per-profile file
 * under [storageDir].
 *
 * In production, [storageDir] is `context.noBackupFilesDir +
 * "/profile_credentials"` so the framework auto-excludes the directory
 * from cloud backup. Tests pass a temp directory.
 *
 * Atomic writes are done with a write-to-`.tmp` + rename dance — Spotify
 * token refresh fires from background scope, and a process kill mid-
 * write would otherwise leave a half-written envelope on disk that fails
 * to decrypt on next launch. We deliberately roll our own rather than
 * pull `android.util.AtomicFile` so the same code runs in JVM unit
 * tests without Android shims.
 */
class FileBackedProfileCredentialsStore(
    private val storageDir: File,
    private val codec: ProfileCredentialsCodec,
) : ProfileCredentialsStore {

    override fun write(profileId: String, credentials: ProfileCredentials) {
        ensureStorageDir()
        val target = fileFor(profileId)
        val tmp = File(storageDir, "${profileId}$TMP_SUFFIX")
        val envelope = codec.encode(credentials).toByteArray(Charsets.UTF_8)

        try {
            tmp.writeBytes(envelope)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }

        // Best-effort atomic replace. `renameTo` semantics differ across
        // filesystems; if the destination exists we delete first. The
        // window between delete + rename is the only one a concurrent
        // reader could observe an empty file — acceptable for our use
        // (next read would fall back through codec.decode failing →
        // null → "credentials missing" UX).
        if (target.exists() && !target.delete()) {
            tmp.delete()
            throw IOException("Couldn't remove existing credentials file at ${target.absolutePath}")
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Couldn't atomically rename credentials file at ${target.absolutePath}")
        }
    }

    override fun read(profileId: String): ProfileCredentials? {
        val file = fileFor(profileId)
        if (!file.exists()) return null
        val envelope = file.readText(Charsets.UTF_8)
        // codec.decode throws on malformed envelope or cipher failure —
        // bubble up so callers can tell "missing" (null) from "broken".
        return codec.decode(envelope)
    }

    override fun delete(profileId: String) {
        val file = fileFor(profileId)
        if (file.exists()) file.delete()
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

    companion object {
        private const val BIN_SUFFIX = ".bin"
        private const val TMP_SUFFIX = ".bin.tmp"
    }
}
