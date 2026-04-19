package com.gpo.yoin.data.profile

/**
 * Persists per-profile credentials outside Room.
 *
 * Why outside Room: Android's auto-backup transparently copies
 * `databases/` to the user's Google account when
 * `android:allowBackup="true"`. Encrypting the JSON in the table
 * doesn't help — the AES key lives in the device's `AndroidKeyStore`
 * and won't be restored, so the backup ends up with ciphertext that
 * nobody can decrypt and the underlying secrets still leak by-value
 * through any tooling that inspects backups directly.
 *
 * Files under `context.noBackupFilesDir` are explicitly excluded from
 * Android's auto-backup, which gives us the right semantics: profile
 * metadata (name, provider, marker) restores cleanly via Room; secrets
 * stay device-local and require the user to re-authorise after a
 * device restore. UI must surface the missing-secret state clearly
 * (Spotify → Reconnect, Subsonic → Credentials missing).
 *
 * Implementations MAY return `null` from [read] when the file simply
 * doesn't exist (post-restore, post-clear, never-written) — that's a
 * normal recoverable state, not an error. They SHOULD throw when the
 * file exists but is corrupt / undecryptable / malformed, so callers
 * can distinguish "needs reauth" from "something on disk is broken".
 */
interface ProfileCredentialsStore {
    fun write(profileId: String, credentials: ProfileCredentials)

    /**
     * Returns the stored credentials, or `null` when no file exists for
     * this profile. Throws when the file exists but can't be parsed /
     * decrypted — caller decides whether to surface that as missing or
     * corrupt.
     */
    fun read(profileId: String): ProfileCredentials?

    fun delete(profileId: String)
}
