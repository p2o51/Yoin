package com.gpo.yoin.data.sync.adapters

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import androidx.room.withTransaction
import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.local.SpotifyConfig
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.sync.ApplyOutcome
import com.gpo.yoin.data.sync.ConflictPolicy
import com.gpo.yoin.data.sync.DomainRow
import com.gpo.yoin.data.sync.FileClass
import com.gpo.yoin.data.sync.SkipReason
import com.gpo.yoin.data.sync.SyncAdapter
import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.data.sync.SyncSettingKeys
import com.gpo.yoin.ui.component.SeamTopPreference
import com.gpo.yoin.ui.component.SeamTopStyle
import kotlinx.serialization.json.JsonObject

/** Where the Settings › Motion › Scroll edge choice lives; a seam so tests can fake it. */
interface SeamStyleGateway {
    /** The stored style key, or null when the user never chose one. */
    fun storedKey(): String?

    /** Shows and stores [key] everywhere; false if it couldn't be applied. */
    fun apply(key: String): Boolean
}

/** The real store: [SeamTopPreference] (live repaint) over the `yoin_ui_preferences` file. */
class SharedPrefsSeamStyleGateway(context: Context) : SeamStyleGateway {
    private val appContext = context.applicationContext
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun storedKey(): String? = prefs().getString(KEY_TOP, null)

    override fun apply(key: String): Boolean {
        val style = SeamTopStyle.entries.firstOrNull { it.key == key } ?: return false
        // Stored synchronously so storedKey() reads it back at once (sync hashes the read-back)...
        prefs().edit { putString(KEY_TOP, style.key) }
        // ...and shown from the main thread, where open seams read the snapshot state. Only if it is
        // still the stored choice by then: a pick the user made in between must not be overwritten.
        val show = Runnable { if (storedKey() == style.key) SeamTopPreference.select(appContext, style) }
        if (Looper.myLooper() == Looper.getMainLooper()) show.run() else mainHandler.post(show)
        return true
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private companion object {
        // Mirrors SeamTopPreference's private file/key names.
        const val PREFS_NAME = "yoin_ui_preferences"
        const val KEY_TOP = "seam_top_style"
    }
}

/**
 * Global app settings as [SyncKinds.SETTING] records, payload `{value}`.
 *
 * A setting is captured only when the user explicitly set it on this device
 * (a default is not an opinion). Once sync tracks a value for a setting here
 * ([tracked]), going back to the default is an explicit choice too: the
 * language reads as [GeminiConfig.DEFAULT_TARGET_LANGUAGE] and a cleared
 * client id as "". Otherwise the setting would read as absent, the engine
 * would drop its local state and the next apply would put the old value back.
 * Apply policies, when declined, return [SkipReason.POLICY] with the current
 * local hash so the engine marks the version applied without mistaking the
 * local value for an edit:
 *  - translation_language: only while song_about_entries is empty (those rows
 *    are language-specific and a switch in Settings wipes them; sync never
 *    does), written field-level so the Gemini API key is untouched.
 *  - spotify_client_id: bootstrap-only, i.e. no local override and no Spotify
 *    account on this device yet.
 *  - seam_top_style: live, through [SeamStyleGateway].
 * Unknown values are declined, never coerced to a default.
 */
class SettingsSyncAdapter(
    private val db: YoinDatabase,
    private val seam: SeamStyleGateway,
    /**
     * Whether this device's sync_local_state row for a setting key holds a
     * hash, i.e. sync already tracks a local value for it. Read by [readAll]
     * only: [apply] gets the same fact as a non-null expectedLocalHash.
     */
    private val tracked: suspend (key: String) -> Boolean = { false },
) : SyncAdapter {
    override val kind = SyncKinds.SETTING
    override val kindVersion = 1
    override val fileClass = FileClass.STATE
    override val perAccount = false
    override val propagatesDeletes = false
    override val conflictPolicy = ConflictPolicy.LWW

    private val dao get() = db.syncDomainDao()

    override suspend fun readAll(localProfileId: String?): List<DomainRow> = buildList {
        languageValue(tracked(SyncSettingKeys.TRANSLATION_LANGUAGE))
            ?.let { add(DomainRow(SyncSettingKeys.TRANSLATION_LANGUAGE, projection(it), null)) }
        clientIdValue(tracked(SyncSettingKeys.SPOTIFY_CLIENT_ID))
            ?.let { add(DomainRow(SyncSettingKeys.SPOTIFY_CLIENT_ID, projection(it), null)) }
        seam.storedKey()?.let { add(DomainRow(SyncSettingKeys.SEAM_TOP_STYLE, projection(it), null)) }
    }

    override fun project(payload: JsonObject): JsonObject? = payload.requiredString("value")?.let(::projection)

    override suspend fun apply(
        localProfileId: String?,
        key: String,
        payload: JsonObject,
        versionTs: Long,
        expectedLocalHash: String?,
    ): ApplyOutcome {
        val value = payload.requiredString("value")
        // The engine passes the tracked local hash: non-null = sync tracks a value here.
        val tracked = expectedLocalHash != null
        return when (key) {
            SyncSettingKeys.TRANSLATION_LANGUAGE -> db.withTransaction {
                applyLanguage(value, expectedLocalHash, tracked)
            }
            SyncSettingKeys.SPOTIFY_CLIENT_ID -> db.withTransaction {
                applyClientId(value, expectedLocalHash, tracked)
            }
            SyncSettingKeys.SEAM_TOP_STYLE -> applySeam(value, expectedLocalHash)
            else -> ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null)
        }
    }

    /** Settings never propagate deletes; a purge leaves them as they are. */
    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome =
        ApplyOutcome.Skipped(
            SkipReason.POLICY,
            currentValue(key, tracked = expectedLocalHash != null)?.let(::projection).hashOrNull(),
        )

    private suspend fun applyLanguage(
        payloadValue: String?,
        expectedLocalHash: String?,
        tracked: Boolean,
    ): ApplyOutcome {
        val currentHash = languageValue(tracked)?.let(::projection).hashOrNull()
        val value = payloadValue ?: return unsupported(currentHash)
        if (currentHash != expectedLocalHash) return changedLocally(currentHash)
        if (value !in GeminiConfig.SUPPORTED_TARGET_LANGUAGES) return policy(currentHash)
        if (dao.songAboutEntryCount() > 0) return policy(currentHash)
        if (dao.geminiConfig() != null) {
            dao.updateGeminiTargetLanguage(value)
        } else if (value != GeminiConfig.DEFAULT_TARGET_LANGUAGE) {
            dao.insertGeminiConfigIfAbsent(GeminiConfig(apiKey = "", targetLanguage = value))
        }
        return ApplyOutcome.Applied(languageValue(tracked)?.let(::projection).hashOrNull())
    }

    private suspend fun applyClientId(
        payloadValue: String?,
        expectedLocalHash: String?,
        tracked: Boolean,
    ): ApplyOutcome {
        val currentHash = clientIdValue(tracked)?.let(::projection).hashOrNull()
        val value = payloadValue ?: return unsupported(currentHash)
        if (currentHash != expectedLocalHash) return changedLocally(currentHash)
        if (value.isBlank()) return policy(currentHash)
        // Bootstrap-only: no override here, not even one the user cleared since sync tracked it ("").
        if (currentHash != null) return policy(currentHash)
        if (dao.profileCount(MediaId.PROVIDER_SPOTIFY) > 0) return policy(currentHash)
        dao.upsertSpotifyConfig(SpotifyConfig(clientId = value))
        return ApplyOutcome.Applied(clientIdValue(tracked)?.let(::projection).hashOrNull())
    }

    private fun applySeam(payloadValue: String?, expectedLocalHash: String?): ApplyOutcome {
        val currentHash = seam.storedKey()?.let(::projection).hashOrNull()
        val value = payloadValue ?: return unsupported(currentHash)
        if (currentHash != expectedLocalHash) return changedLocally(currentHash)
        if (SeamTopStyle.entries.none { it.key == value }) return policy(currentHash)
        if (!seam.apply(value)) return policy(currentHash)
        return ApplyOutcome.Applied(seam.storedKey()?.let(::projection).hashOrNull())
    }

    private fun unsupported(currentHash: String?) = ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)

    private fun changedLocally(currentHash: String?) = ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)

    private fun policy(currentHash: String?) = ApplyOutcome.Skipped(SkipReason.POLICY, currentHash)

    private suspend fun currentValue(key: String, tracked: Boolean): String? = when (key) {
        SyncSettingKeys.TRANSLATION_LANGUAGE -> languageValue(tracked)
        SyncSettingKeys.SPOTIFY_CLIENT_ID -> clientIdValue(tracked)
        SyncSettingKeys.SEAM_TOP_STYLE -> seam.storedKey()
        else -> null
    }

    /** Explicit only: a row with a non-default language, or the default once sync tracks this setting. */
    private suspend fun languageValue(tracked: Boolean): String? {
        val stored = dao.geminiConfig()?.targetLanguage
        return when {
            stored != null && stored != GeminiConfig.DEFAULT_TARGET_LANGUAGE -> stored
            tracked -> GeminiConfig.DEFAULT_TARGET_LANGUAGE
            else -> null
        }
    }

    /**
     * Explicit only: a non-blank override (blank = the build's fallback client
     * id), or "" (cleared) once sync tracks this setting.
     */
    private suspend fun clientIdValue(tracked: Boolean): String? =
        dao.spotifyConfig()?.clientId?.takeIf(String::isNotBlank) ?: if (tracked) "" else null

    private fun projection(value: String) = JsonObject(mapOf("value" to jsonString(value)))
}
