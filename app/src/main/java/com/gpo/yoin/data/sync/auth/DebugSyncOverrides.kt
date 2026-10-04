package com.gpo.yoin.data.sync.auth

import android.content.Context
import android.content.Intent
import com.gpo.yoin.BuildConfig
import com.gpo.yoin.data.sync.GoogleAuthAvailability
import com.gpo.yoin.data.sync.GoogleAuthResult
import com.gpo.yoin.data.sync.GoogleSyncAuthorizer
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Debug-only QA override: point cloud sync at a local fake Drive instead of Google.
 * Example `files/sync-debug.json`:
 * `{"baseUrl":"http://127.0.0.1:8765/","email":"qa@example.com","permissionId":"qa"}`
 */
@Serializable
data class DebugSyncConfig(
    @SerialName("baseUrl") val baseUrl: String,
    @SerialName("email") val email: String,
    @SerialName("permissionId") val permissionId: String,
) {
    private val normalizedBase: String get() = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    /** DriveAppDataClient apiBase. */
    val apiBase: String get() = normalizedBase + "drive/v3/"

    /** DriveAppDataClient uploadBase. */
    val uploadBase: String get() = normalizedBase + "upload/drive/v3/"
}

object DebugSyncOverrides {
    const val FILE_NAME = "sync-debug.json"

    private val json = Json { ignoreUnknownKeys = true }

    /** Non-null only in debug builds with a valid `filesDir/sync-debug.json`. R8 drops the rest in release. */
    fun load(context: Context): DebugSyncConfig? {
        if (!BuildConfig.DEBUG) return null
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return null
        return runCatching { parse(file.readText()) }.getOrNull()
    }

    internal fun parse(text: String): DebugSyncConfig? {
        val config = runCatching { json.decodeFromString(DebugSyncConfig.serializer(), text) }.getOrNull()
            ?: return null
        val url = config.baseUrl.toHttpUrlOrNull() ?: return null
        if (config.email.isBlank() || config.permissionId.isBlank()) return null
        return config.copy(baseUrl = url.toString())
    }
}

/** Authorizer for the fake Drive: always available, fixed token, never asks for consent. */
class DebugSyncAuthorizer(private val config: DebugSyncConfig) : GoogleSyncAuthorizer {
    private val token = GoogleAuthResult.Token(
        accessToken = DEBUG_TOKEN,
        grantedScopes = listOf(GoogleSyncAuthorizer.SCOPE_DRIVE_APPDATA),
    )

    /** The fake account's email, for the sync manager's account pinning and UI. */
    val accountEmail: String get() = config.email

    override fun availability(): GoogleAuthAvailability = GoogleAuthAvailability.Available

    override suspend fun authorizeSilently(accountEmail: String?): GoogleAuthResult = token

    override suspend fun authorizeInteractively(): GoogleAuthResult = token

    override fun resultFromIntent(data: Intent?): GoogleAuthResult = token

    override suspend fun clearToken(accessToken: String) = Unit

    override suspend fun revoke(accountEmail: String?) = Unit

    override fun signingCertSha1(): String? = null

    companion object {
        const val DEBUG_TOKEN = "debug-token"
    }
}
