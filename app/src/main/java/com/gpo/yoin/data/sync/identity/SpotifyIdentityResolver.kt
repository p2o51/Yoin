package com.gpo.yoin.data.sync.identity

import com.gpo.yoin.data.lyrics.awaitResponse
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.sync.CanonicalJson
import com.gpo.yoin.data.sync.SyncDatabase
import com.gpo.yoin.data.sync.SyncMetaEntity
import com.gpo.yoin.data.sync.SyncMetaKeys
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Spotify user id of a local profile, for its fingerprint.
 *
 * Cached in sync_meta as "<userId>|<sha256(refreshToken) first 16>": a new
 * refresh token (reconnect, possibly as someone else) invalidates the cache.
 * A miss calls GET /me with the profile's stored access token only while it
 * is comfortably unexpired and not revoked — never refreshing it here, since
 * rotating the refresh token behind the live client's back would kill its
 * session. Any failure (expired, 401/403, 429, network) is null = "not known
 * yet, try next cycle", never "no identity".
 */
class SpotifyIdentityResolver(
    private val syncDb: SyncDatabase,
    private val httpClient: OkHttpClient,
    baseUrl: String = DEFAULT_BASE_URL,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val meUrl = baseUrl.trimEnd('/') + "/me"
    private val dao get() = syncDb.syncDao()

    suspend fun userId(localProfileId: String, credentials: ProfileCredentials.Spotify): String? {
        val tokenHash = refreshTokenHash(credentials.refreshToken)
        val metaKey = SyncMetaKeys.SPOTIFY_UID_PREFIX + localProfileId
        dao.meta(metaKey)?.let { cached ->
            val userId = cached.substringBeforeLast('|')
            if (cached.substringAfterLast('|') == tokenHash && userId.isNotEmpty()) return userId
        }
        // A new refresh token may be a reconnect as someone else: never vouch for it with the old
        // id. Unknown until a fresh access token can ask /me (routine use refreshes it).
        if (credentials.revoked || credentials.expiresAtEpochMs <= clock() + MIN_TOKEN_LIFETIME_MS) return null
        val userId = fetchUserId(credentials.accessToken) ?: return null
        dao.putMeta(SyncMetaEntity(metaKey, "$userId|$tokenHash"))
        return userId
    }

    /** Drops the cached id of a profile that no longer exists. */
    suspend fun forget(localProfileId: String) {
        dao.deleteMeta(SyncMetaKeys.SPOTIFY_UID_PREFIX + localProfileId)
    }

    private suspend fun fetchUserId(accessToken: String): String? {
        val request = Request.Builder()
            .url(meUrl)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return try {
            httpClient.awaitResponse(request).use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                runCatching {
                    Json.parseToJsonElement(body).jsonObject["id"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()?.takeIf(String::isNotBlank)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            null
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.spotify.com/v1/"

        /** Don't start a call with a token that may expire mid-flight. */
        const val MIN_TOKEN_LIFETIME_MS = 60_000L

        fun refreshTokenHash(refreshToken: String): String = CanonicalJson.sha256Hex(refreshToken).take(16)
    }
}
