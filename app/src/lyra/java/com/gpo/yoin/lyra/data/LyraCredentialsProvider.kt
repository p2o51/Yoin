package com.gpo.yoin.lyra.data

import android.content.Context
import android.util.Log
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.profile.AndroidKeyStoreCredentialsCipher
import com.gpo.yoin.data.profile.EncryptedBlob
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.profile.ProfileManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Encrypted, device-local storage for the optional `LYRA_TOKEN` admin bearer token.
 * Stored under `noBackupFilesDir` (never included in Android cloud backup) using Yoin's
 * existing AES-GCM AndroidKeyStore cipher.
 */
class LyraAdminTokenStore(context: Context) {
    private val file = File(context.applicationContext.noBackupFilesDir, "lyra_admin_token.bin")
    private val cipher by lazy { AndroidKeyStoreCredentialsCipher() }

    @Synchronized
    fun read(): String? {
        if (!file.isFile) return null
        return runCatching {
            val raw = file.readBytes()
            if (raw.size < 2) return null
            val ivLen = raw[0].toInt() and 0xff
            if (ivLen <= 0 || 1 + ivLen >= raw.size) return null
            val blob = EncryptedBlob(
                iv = raw.copyOfRange(1, 1 + ivLen),
                ciphertext = raw.copyOfRange(1 + ivLen, raw.size),
            )
            String(cipher.decrypt(blob), Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        }.onFailure { error ->
            Log.w(TAG, "Failed to read encrypted Lyra admin token", error)
        }.getOrNull()
    }

    @Synchronized
    fun write(token: String?) {
        val trimmed = token?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            clear()
            return
        }
        runCatching {
            file.parentFile?.mkdirs()
            val blob = cipher.encrypt(trimmed.toByteArray(Charsets.UTF_8))
            val packed = ByteArray(1 + blob.iv.size + blob.ciphertext.size).apply {
                this[0] = blob.iv.size.toByte()
                blob.iv.copyInto(this, destinationOffset = 1)
                blob.ciphertext.copyInto(this, destinationOffset = 1 + blob.iv.size)
            }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeBytes(packed)
            if (!tmp.renameTo(file)) {
                file.writeBytes(packed)
                tmp.delete()
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to write encrypted Lyra admin token", error)
        }
    }

    @Synchronized
    fun clear() {
        runCatching { if (file.exists()) file.delete() }
    }

    fun hasToken(): Boolean = !read().isNullOrBlank()

    companion object {
        private const val TAG = "LyraAdminTokenStore"
    }
}

/**
 * Resolves [LyraServerAuth] from Yoin's active Subsonic/Lyra profile (or the first configured
 * Subsonic profile if another profile is temporarily active) plus the optional [LyraAdminTokenStore].
 */
class LyraCredentialsProvider(
    private val profileManager: ProfileManager,
    val adminTokenStore: LyraAdminTokenStore,
) {
    suspend fun currentAuth(): LyraServerAuth? = withContext(Dispatchers.IO) {
        val active = profileManager.activeProfile.value
        val candidate = if (active?.provider == MediaId.PROVIDER_SUBSONIC) {
            active
        } else {
            profileManager.profiles.first().firstOrNull { it.provider == MediaId.PROVIDER_SUBSONIC }
        } ?: return@withContext null

        val creds = profileManager.decodeCredentials(candidate) as? ProfileCredentials.Subsonic
            ?: return@withContext null
        LyraServerAuth(
            serverUrl = creds.serverUrl,
            username = creds.username,
            password = creds.password,
            adminToken = adminTokenStore.read(),
        )
    }
}
