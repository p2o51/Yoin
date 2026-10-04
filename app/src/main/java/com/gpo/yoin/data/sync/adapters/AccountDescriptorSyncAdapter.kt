package com.gpo.yoin.data.sync.adapters

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.sync.ApplyOutcome
import com.gpo.yoin.data.sync.ConflictPolicy
import com.gpo.yoin.data.sync.DomainRow
import com.gpo.yoin.data.sync.FileClass
import com.gpo.yoin.data.sync.SkipReason
import com.gpo.yoin.data.sync.SyncAdapter
import com.gpo.yoin.data.sync.SyncBindingEntity
import com.gpo.yoin.data.sync.SyncDatabase
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.data.sync.identity.AccountDescriptorFields
import com.gpo.yoin.data.sync.identity.AccountFingerprints
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * [SyncKinds.ACCOUNT]: how each ACTIVE binding's account is described to
 * other devices (key = syncProfileId), so they can list "In your Drive, not
 * on this device". Read-only towards the domain: apply never creates
 * profiles, it only acknowledges ([SkipReason.POLICY]).
 *
 * Only display metadata leaves the device: provider, display name, created
 * time, the sanitized Subsonic "user @ host" hint and the Apple storefront.
 * Never credentials, never the Apple endpoint.
 *
 * A field this cycle can't recompute (credentials unreadable, storefront not
 * loaded in this process yet) keeps the value already published for that
 * account, so a descriptor neither flaps between versions nor vanishes (a
 * vanished row counts as missing towards the engine's domain-reset check).
 */
class AccountDescriptorSyncAdapter(
    private val syncDb: SyncDatabase,
    private val profiles: suspend () -> List<Profile>,
    private val decodeCredentials: (Profile) -> ProfileCredentials?,
    /** Apple Music storefront of a local profile, when known. */
    private val storefront: suspend (localProfileId: String) -> String? = { null },
) : SyncAdapter {
    override val kind = SyncKinds.ACCOUNT
    override val kindVersion = 1
    override val fileClass = FileClass.STATE
    override val perAccount = false
    override val propagatesDeletes = false
    override val conflictPolicy = ConflictPolicy.LWW

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val byId = profiles().associateBy(Profile::id)
        return syncDb.syncDao().bindings()
            .filter { it.state == SyncBindingEntity.STATE_ACTIVE }
            .sortedBy(SyncBindingEntity::syncProfileId)
            .mapNotNull { binding ->
                val profile = byId[binding.localProfileId] ?: return@mapNotNull null
                val hint = when (profile.provider) {
                    MediaId.PROVIDER_SUBSONIC -> {
                        val credentials = runCatching { decodeCredentials(profile) }.getOrNull()
                            as? ProfileCredentials.Subsonic
                        credentials?.let { AccountFingerprints.subsonicHint(it.serverUrl, it.username) }
                            ?: publishedField(binding.syncProfileId, AccountDescriptorFields.HINT)
                            // Nothing published yet either: wait rather than publish a degraded descriptor.
                            ?: return@mapNotNull null
                    }
                    else -> null
                }
                val store = if (profile.provider == MediaId.PROVIDER_APPLE_MUSIC) {
                    storefront(profile.id)?.takeIf(String::isNotBlank)
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

    /** A non-blank string field of the descriptor the replica holds for [syncProfileId], if any. */
    private suspend fun publishedField(syncProfileId: String, field: String): String? {
        val record = syncDb.syncDao().getRecord(SyncKinds.ACCOUNT, SyncFormat.GLOBAL_SCOPE, syncProfileId)
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
