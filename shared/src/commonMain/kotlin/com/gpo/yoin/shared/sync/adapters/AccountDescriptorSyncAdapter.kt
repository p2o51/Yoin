package com.gpo.yoin.shared.sync.adapters

import com.gpo.yoin.shared.sync.ApplyOutcome
import com.gpo.yoin.shared.sync.ConflictPolicy
import com.gpo.yoin.shared.sync.DomainRow
import com.gpo.yoin.shared.sync.FileClass
import com.gpo.yoin.shared.sync.SkipReason
import com.gpo.yoin.shared.sync.SyncAdapter
import com.gpo.yoin.shared.sync.SyncBindingEntity
import com.gpo.yoin.shared.sync.SyncFormat
import com.gpo.yoin.shared.sync.SyncKinds
import com.gpo.yoin.shared.sync.SyncProviders
import com.gpo.yoin.shared.sync.SyncStore
import com.gpo.yoin.shared.sync.identity.AccountDescriptorFields
import com.gpo.yoin.shared.sync.identity.AccountFingerprints
import com.gpo.yoin.shared.sync.identity.SyncProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * [SyncKinds.ACCOUNT]: how each ACTIVE binding's account is described to other devices
 * (key = syncProfileId). Read-only towards the domain. Port of Android's
 * AccountDescriptorSyncAdapter: provider, display name, created time, the
 * sanitized Subsonic "user @ host" hint and the Apple storefront; never credentials.
 */
class AccountDescriptorSyncAdapter(
    private val syncDb: SyncStore,
    private val profiles: suspend () -> List<SyncProfile>,
) : SyncAdapter {
    override val kind = SyncKinds.ACCOUNT
    override val kindVersion = 1
    override val fileClass = FileClass.STATE
    override val perAccount = false
    override val propagatesDeletes = false
    override val conflictPolicy = ConflictPolicy.LWW

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val byId = profiles().associateBy(SyncProfile::id)
        return syncDb.bindings()
            .filter { it.state == SyncBindingEntity.STATE_ACTIVE }
            .sortedBy(SyncBindingEntity::syncProfileId)
            .mapNotNull { binding ->
                val profile = byId[binding.localProfileId] ?: return@mapNotNull null
                val hint = when (profile.provider) {
                    SyncProviders.SUBSONIC -> {
                        val server = profile.subsonicServerUrl.takeIf { profile.credentialsReadable }
                        val user = profile.subsonicUsername.takeIf { profile.credentialsReadable }
                        (if (server != null && user != null) AccountFingerprints.subsonicHint(server, user) else null)
                            ?: publishedField(binding.syncProfileId, AccountDescriptorFields.HINT)
                            // Nothing published yet either: wait rather than publish a degraded descriptor.
                            ?: return@mapNotNull null
                    }
                    else -> null
                }
                val store = if (profile.provider == SyncProviders.APPLE_MUSIC) {
                    profile.storefront?.takeIf(String::isNotBlank)
                        ?: publishedField(binding.syncProfileId, AccountDescriptorFields.STOREFRONT)
                } else {
                    null
                }
                DomainRow(
                    key = binding.syncProfileId,
                    projection = projection(profile.provider, profile.displayName, profile.createdAt, hint, store),
                    rowTs = profile.createdAt,
                )
            }
    }

    override fun project(payload: JsonObject): JsonObject? {
        val provider = payload.requiredString(AccountDescriptorFields.PROVIDER)?.takeIf(String::isNotEmpty)
            ?: return null
        val displayName = payload.requiredString(AccountDescriptorFields.DISPLAY_NAME) ?: return null
        val createdAt = payload.requiredLong(AccountDescriptorFields.CREATED_AT) ?: return null
        val hint = payload.optionalString(AccountDescriptorFields.HINT).orElse { return null }
        val store = payload.optionalString(AccountDescriptorFields.STOREFRONT).orElse { return null }
        return projection(provider, displayName, createdAt, hint, store)
    }

    override fun decoratePayload(payload: JsonObject, deviceName: String): JsonObject =
        payload.with(AccountDescriptorFields.DEVICE_NAME, JsonPrimitive(deviceName))

    override suspend fun apply(
        localProfileId: String?,
        key: String,
        payload: JsonObject,
        versionTs: Long,
        expectedLocalHash: String?,
    ): ApplyOutcome = ApplyOutcome.Skipped(SkipReason.POLICY, currentHash(key))

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome =
        ApplyOutcome.Skipped(SkipReason.POLICY, currentHash(key))

    private suspend fun currentHash(key: String): String? =
        readAll(null).firstOrNull { it.key == key }?.projection.hashOrNull()

    private suspend fun publishedField(syncProfileId: String, field: String): String? {
        val record = syncDb.getRecord(SyncKinds.ACCOUNT, SyncFormat.GLOBAL_SCOPE, syncProfileId)
            ?.takeUnless { it.deleted }
            ?: return null
        val payload = record.payload ?: return null
        return runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?.requiredString(field)
            ?.takeIf(String::isNotBlank)
    }

    private fun projection(
        provider: String,
        displayName: String,
        createdAt: Long,
        hint: String?,
        storefront: String?,
    ) = JsonObject(
        mapOf(
            AccountDescriptorFields.PROVIDER to jsonString(provider),
            AccountDescriptorFields.DISPLAY_NAME to jsonString(displayName),
            AccountDescriptorFields.CREATED_AT to jsonLong(createdAt),
            AccountDescriptorFields.HINT to jsonString(hint),
            AccountDescriptorFields.STOREFRONT to jsonString(storefront),
        ),
    )
}

/**
 * The kinds this platform applies. Android's allowlist also has playlist_backup (and setting
 * keys iOS has no such setting for: spotify_client_id, seam_top_style); a device without an adapter for a
 * kind or key keeps those records opaquely and republishes them unchanged (SyncEngine.mergeRecord /
 * outgoingFiles), so nothing is lost on the way.
 */
object SharedSyncAdapters {
    fun create(domain: SyncDomain, syncDb: SyncStore): List<SyncAdapter> = listOf(
        SongNoteSyncAdapter(domain),
        TrackRatingSyncAdapter(domain),
        AlbumRatingSyncAdapter(domain),
        AlbumReviewSyncAdapter(domain),
        SettingsSyncAdapter(domain) { key ->
            syncDb.getLocalState(SyncKinds.SETTING, SyncFormat.GLOBAL_SCOPE, key)?.localHash != null
        },
        LyricsTranslationSyncAdapter(domain),
        MemoryCopySyncAdapter(domain),
        MemoryTitleSyncAdapter(domain),
        AlbumTitleSyncAdapter(domain),
        SongAboutSyncAdapter(domain),
        HomeLayoutSyncAdapter(domain),
        AccountDescriptorSyncAdapter(syncDb) { domain.profiles() },
    )
}
