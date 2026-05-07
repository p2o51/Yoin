package com.gpo.yoin.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.gpo.yoin.data.profile.AndroidKeyStoreCredentialsCipher
import com.gpo.yoin.data.profile.EncryptedProfileCredentialsCodec
import com.gpo.yoin.data.profile.FileBackedProfileCredentialsStore
import com.gpo.yoin.data.profile.PlaintextProfileCredentialsCodec
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.source.spotify.SpotifyAuthConfig
import com.gpo.yoin.data.source.spotify.SpotifyAuthService
import java.io.File
import okhttp3.OkHttpClient

/**
 * Debug-only token bridge for local tooling.
 *
 * Usage:
 * adb shell content query \
 *   --uri content://com.gpo.yoin.debug.spotifytoken/access_token
 *
 * The provider is only packaged in debug builds and requires the platform
 * DUMP permission, which ADB shell has and normal apps do not.
 */
class SpotifyTokenExportProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        if (uri.path != "/access_token") {
            return row(error = "Unsupported path: ${uri.path}")
        }
        return runCatching {
            exportToken(
                requestedProfileId = uri.getQueryParameter("profileId"),
                clientIdOverride = uri.getQueryParameter("clientId")
                    ?: uri.getQueryParameter("client_id"),
                includeRefreshToken = uri.getBooleanQueryParameter("includeRefreshToken", false),
            )
        }
            .getOrElse { error -> row(error = error.message ?: error.toString()) }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Read-only debug provider")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only debug provider")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Read-only debug provider")

    private fun exportToken(
        requestedProfileId: String?,
        clientIdOverride: String?,
        includeRefreshToken: Boolean,
    ): Cursor {
        val appContext = requireNotNull(context).applicationContext
        val dbFile = appContext.getDatabasePath("yoin-database")
        if (!dbFile.exists()) return row(error = "Yoin database not found")

        val activeProfileId = appContext
            .getSharedPreferences(PROFILES_PREFS, android.content.Context.MODE_PRIVATE)
            .getString(ACTIVE_PROFILE_ID, null)

        val profile = SQLiteDatabase.openDatabase(
            dbFile.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            loadSpotifyProfile(
                db = db,
                requestedProfileId = requestedProfileId,
                activeProfileId = activeProfileId,
            )
        } ?: orphanCredentialProfile(requestedProfileId, activeProfileId)
            ?: return row(error = "Spotify profile not found")

        val credentials = readCredentials(profile)
            ?: return row(profile = profile, error = "Spotify credentials unavailable")

        val now = System.currentTimeMillis()
        val resolved = if (includeRefreshToken) {
            TokenExport(credentials = credentials, refreshed = false)
        } else if (credentials.expiresAtEpochMs <= now + REFRESH_BUFFER_MS) {
            refreshCredentials(
                profile = profile,
                credentials = credentials,
                now = now,
                clientIdOverride = clientIdOverride,
            )
        } else {
            TokenExport(credentials = credentials, refreshed = false)
        }

        return row(
            profile = profile,
            token = resolved.credentials.accessToken,
            refreshToken = resolved.credentials.refreshToken.takeIf { includeRefreshToken },
            expiresAtEpochMs = resolved.credentials.expiresAtEpochMs,
            refreshed = resolved.refreshed,
        )
    }

    private fun loadSpotifyProfile(
        db: SQLiteDatabase,
        requestedProfileId: String?,
        activeProfileId: String?,
    ): DebugProfile? {
        requestedProfileId?.takeIf { it.isNotBlank() }?.let { id ->
            queryProfile(db, "id = ? AND provider = ?", arrayOf(id, SPOTIFY_PROVIDER))
                ?.let { return it }
            return null
        }

        activeProfileId?.takeIf { it.isNotBlank() }?.let { id ->
            queryProfile(db, "id = ? AND provider = ?", arrayOf(id, SPOTIFY_PROVIDER))
                ?.let { return it }
        }

        return queryProfile(
            db = db,
            where = "provider = ?",
            args = arrayOf(SPOTIFY_PROVIDER),
        )
    }

    private fun queryProfile(
        db: SQLiteDatabase,
        where: String,
        args: Array<String>,
    ): DebugProfile? = db.query(
        "profiles",
        arrayOf("id", "displayName", "credentialsJson"),
        where,
        args,
        null,
        null,
        "createdAt ASC",
        "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) return null
        DebugProfile(
            id = cursor.getString(0),
            displayName = cursor.getString(1),
            credentialsJson = cursor.getString(2),
        )
    }

    private fun orphanCredentialProfile(
        requestedProfileId: String?,
        activeProfileId: String?,
    ): DebugProfile? {
        val storageDir = File(
            requireNotNull(context).applicationContext.noBackupFilesDir,
            "profile_credentials",
        )
        if (!storageDir.isDirectory) return null

        val preferredIds = listOfNotNull(
            requestedProfileId?.takeIf(String::isNotBlank),
            activeProfileId?.takeIf(String::isNotBlank),
        )
        preferredIds.firstNotNullOfOrNull { id ->
            File(storageDir, "$id.bin")
                .takeIf(File::exists)
                ?.let { orphanProfile(id) }
        }?.let { return it }

        return storageDir
            .listFiles { file -> file.isFile && file.name.endsWith(".bin") }
            .orEmpty()
            .sortedBy(File::getName)
            .firstOrNull()
            ?.name
            ?.removeSuffix(".bin")
            ?.let(::orphanProfile)
    }

    private fun orphanProfile(id: String): DebugProfile = DebugProfile(
        id = id,
        displayName = "orphan:${id.take(8)}",
        credentialsJson = ProfileManager.STORE_MARKER_V1,
    )

    private fun readCredentials(profile: DebugProfile): ProfileCredentials.Spotify? {
        val decoded = if (profile.credentialsJson == ProfileManager.STORE_MARKER_V1) {
            credentialStore().read(profile.id)
        } else {
            PlaintextProfileCredentialsCodec().decode(profile.credentialsJson)
        }
        return decoded as? ProfileCredentials.Spotify
    }

    private fun refreshCredentials(
        profile: DebugProfile,
        credentials: ProfileCredentials.Spotify,
        now: Long,
        clientIdOverride: String?,
    ): TokenExport {
        val clientId = clientIdOverride
            ?.takeIf(String::isNotBlank)
            ?: spotifyClientId()
        if (clientId.isBlank()) {
            throw IllegalStateException("Spotify token expired and client id is missing")
        }

        val response = SpotifyAuthService(OkHttpClient()).refreshToken(
            refreshToken = credentials.refreshToken,
            clientId = clientId,
        )
        val refreshed = credentials.copy(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken ?: credentials.refreshToken,
            expiresAtEpochMs = now + response.expiresInSec * 1_000L,
            scopes = response.scope
                ?.split(' ')
                ?.filter(String::isNotBlank)
                ?: credentials.scopes,
            revoked = false,
        )
        credentialStore().write(profile.id, refreshed)
        return TokenExport(credentials = refreshed, refreshed = true)
    }

    private fun spotifyClientId(): String {
        val dbFile = requireNotNull(context).applicationContext.getDatabasePath("yoin-database")
        val stored = SQLiteDatabase.openDatabase(
            dbFile.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.query(
                "spotify_config",
                arrayOf("clientId"),
                "id = 1",
                null,
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            }
        }
        return stored.takeIf(String::isNotBlank) ?: SpotifyAuthConfig.FALLBACK_CLIENT_ID
    }

    private fun credentialStore() = FileBackedProfileCredentialsStore(
        storageDir = File(requireNotNull(context).applicationContext.noBackupFilesDir, "profile_credentials"),
        codec = EncryptedProfileCredentialsCodec(AndroidKeyStoreCredentialsCipher()),
    )

    private fun row(
        profile: DebugProfile? = null,
        token: String? = null,
        refreshToken: String? = null,
        expiresAtEpochMs: Long? = null,
        refreshed: Boolean = false,
        error: String? = null,
    ): MatrixCursor = MatrixCursor(COLUMNS).apply {
        addRow(
            arrayOf<Any?>(
                if (error == null) "ok" else "error",
                profile?.id,
                profile?.displayName,
                token,
                refreshToken,
                expiresAtEpochMs,
                if (refreshed) 1 else 0,
                error,
            ),
        )
    }

    private data class DebugProfile(
        val id: String,
        val displayName: String,
        val credentialsJson: String,
    )

    private data class TokenExport(
        val credentials: ProfileCredentials.Spotify,
        val refreshed: Boolean,
    )

    companion object {
        private const val PROFILES_PREFS = "yoin_profiles"
        private const val ACTIVE_PROFILE_ID = "active_profile_id"
        private const val SPOTIFY_PROVIDER = "spotify"
        private const val REFRESH_BUFFER_MS = 60_000L

        private val COLUMNS = arrayOf(
            "status",
            "profile_id",
            "display_name",
            "access_token",
            "refresh_token",
            "expires_at_epoch_ms",
            "refreshed",
            "error",
        )
    }
}
