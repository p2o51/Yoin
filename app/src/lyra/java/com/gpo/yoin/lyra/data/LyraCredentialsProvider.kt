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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Encrypted, device-local storage for optional `LYRA_TOKEN` admin bearer tokens, scoped per
 * normalized server base URL so a token for one server is never sent to a different server.
 *
 * Stored under `noBackupFilesDir` (never included in Android cloud backup) using Yoin's
 * existing AES-GCM AndroidKeyStore cipher.
 */
class LyraAdminTokenStore(context: Context) {
    private val file = File(context.applicationContext.noBackupFilesDir, "lyra_admin_tokens.bin")
    private val legacyFile = File(context.applicationContext.noBackupFilesDir, "lyra_admin_token.bin")
    private val cipher by lazy { AndroidKeyStoreCredentialsCipher() }
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    @Synchronized
    fun read(serverUrl: String): String? {
        val key = normalizeKey(serverUrl) ?: return null
        return readAllLocked()[key]?.trim()?.takeIf { it.isNotEmpty() }
    }

    @Synchronized
    fun write(serverUrl: String, token: String?) {
        val key = normalizeKey(serverUrl) ?: return
        val trimmed = token?.trim().orEmpty()
        val map = readAllLocked().toMutableMap()
        if (trimmed.isEmpty()) {
            map.remove(key)
        } else {
            map[key] = trimmed
        }
        writeAllLocked(map)
    }

    @Synchronized
    fun clear(serverUrl: String) {
        val key = normalizeKey(serverUrl) ?: return
        val map = readAllLocked().toMutableMap()
        if (map.remove(key) != null) {
            writeAllLocked(map)
        }
    }

    @Synchronized
    fun retainOnly(activeServerUrls: Collection<String>) {
        val keep = activeServerUrls.mapNotNull(::normalizeKey).toSet()
        val map = readAllLocked()
        val filtered = map.filterKeys { it in keep }
        if (filtered.size != map.size) {
            writeAllLocked(filtered)
        }
        // Always purge legacy unscoped single-token file so it can never leak across servers.
        runCatching { if (legacyFile.exists()) legacyFile.delete() }
    }

    @Synchronized
    fun clearAll() {
        runCatching { if (file.exists()) file.delete() }
        runCatching { if (legacyFile.exists()) legacyFile.delete() }
    }

    fun hasToken(serverUrl: String): Boolean = !read(serverUrl).isNullOrBlank()

    private fun readAllLocked(): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            val raw = file.readBytes()
            if (raw.size < 2) return emptyMap()
            val ivLen = raw[0].toInt() and 0xff
            if (ivLen <= 0 || 1 + ivLen >= raw.size) return emptyMap()
            val blob = EncryptedBlob(
                iv = raw.copyOfRange(1, 1 + ivLen),
                ciphertext = raw.copyOfRange(1 + ivLen, raw.size),
            )
            val decoded = String(cipher.decrypt(blob), Charsets.UTF_8)
            json.decodeFromString(mapSerializer, decoded)
        }.onFailure { error ->
            Log.w(TAG, "Failed to read encrypted Lyra admin tokens", error)
        }.getOrDefault(emptyMap())
    }

    private fun writeAllLocked(map: Map<String, String>) {
        if (map.isEmpty()) {
            clearAll()
            return
        }
        runCatching {
            file.parentFile?.mkdirs()
            val encoded = json.encodeToString(mapSerializer, map)
            val blob = cipher.encrypt(encoded.toByteArray(Charsets.UTF_8))
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
            if (legacyFile.exists()) legacyFile.delete()
        }.onFailure { error ->
            Log.w(TAG, "Failed to write encrypted Lyra admin tokens", error)
        }
    }

    private fun normalizeKey(serverUrl: String): String? =
        serverUrl.trim().takeIf { it.isNotEmpty() }?.let { LyraApiClient.normalizeServerBase(it) }

    companion object {
        private const val TAG = "LyraAdminTokenStore"
    }
}

/**
 * Resolves [LyraServerAuth] from Yoin's active Subsonic/Lyra profile (or a specifically pinned
 * profile ID for CloudSync) plus the per-server [LyraAdminTokenStore].
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
        buildAuth(candidate.id, profileManager.decodeCredentials(candidate) as? ProfileCredentials.Subsonic)
    }

    /**
     * Resolves credentials strictly for [profileId] when non-null (never silently falling back
     * to another Subsonic server), or [currentAuth] when [profileId] is null.
     */
    suspend fun authForProfile(profileId: String?): LyraServerAuth? = withContext(Dispatchers.IO) {
        if (profileId.isNullOrBlank()) return@withContext currentAuth()
        val match = profileManager.profiles.first()
            .firstOrNull { it.id == profileId && it.provider == MediaId.PROVIDER_SUBSONIC }
            ?: return@withContext null
        buildAuth(match.id, profileManager.decodeCredentials(match) as? ProfileCredentials.Subsonic)
    }

    /** Removes stored admin tokens for any server no longer associated with a remaining profile. */
    suspend fun pruneOrphanedTokens() = withContext(Dispatchers.IO) {
        val remainingUrls = profileManager.profiles.first()
            .filter { it.provider == MediaId.PROVIDER_SUBSONIC }
            .mapNotNull { profile ->
                (profileManager.decodeCredentials(profile) as? ProfileCredentials.Subsonic)?.serverUrl
            }
        adminTokenStore.retainOnly(remainingUrls)
    }

    private fun buildAuth(profileId: String, creds: ProfileCredentials.Subsonic?): LyraServerAuth? {
        if (creds == null) return null
        return LyraServerAuth(
            serverUrl = creds.serverUrl,
            username = creds.username,
            password = creds.password,
            adminToken = adminTokenStore.read(creds.serverUrl),
            profileId = profileId,
        )
    }
}
