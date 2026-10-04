package com.gpo.yoin.data.sync.identity

import androidx.room.withTransaction
import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.sync.ActiveBinding
import com.gpo.yoin.data.sync.CloudAccountRow
import com.gpo.yoin.data.sync.SyncAccountRow
import com.gpo.yoin.data.sync.SyncAccountStatus
import com.gpo.yoin.data.sync.SyncBindingEntity
import com.gpo.yoin.data.sync.SyncDatabase
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.data.sync.SyncMetaKeys
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Result of one [AccountBinder.refresh]. */
data class BindingSnapshot(
    /** Bindings to capture/apply this cycle: ACTIVE and verified (credentials readable, fingerprint unchanged). */
    val active: List<ActiveBinding>,
    /** One row per local profile, oldest first, for the Accounts group. */
    val rows: List<SyncAccountRow>,
    /** Accounts described in Drive that no local profile is bound to. */
    val cloudOnly: List<CloudAccountRow>,
    /** Bindings created by this refresh (they JOIN: no local state exists for their scope yet). */
    val newlyBound: List<ActiveBinding>,
)

/**
 * A "Start fresh as this account" in progress. Between [AccountBinder.prepareStartFresh]
 * and [AccountBinder.completeStartFresh] the caller deletes, WITHOUT tombstones,
 * the domain rows this profile holds for [oldScope] (the keys of its local
 * state rows). Must not proceed while [pendingChanges] > 0.
 */
data class StartFreshPlan(
    val localProfileId: String,
    val provider: String,
    val oldScope: String,
    /** Scope to bind to afterwards; null = stay unbound (identity unknown, or that account is bound elsewhere). */
    val newSyncProfileId: String?,
    val newFingerprintHash: String?,
    /** Unpublished local changes in [oldScope]. */
    val pendingChanges: Int,
)

/**
 * Keeps the local profile <-> sync scope bindings (spec §4 step 4, §5).
 *
 * Bindings are frozen once made: a scope id is minted from the account
 * fingerprint at first bind (Subsonic, Spotify) or at random on an explicit
 * user action (Apple Music, which never auto-binds) and only changes through
 * the explicit actions below. A changed fingerprint pauses the binding, a
 * vanished profile unbinds it; neither ever creates tombstones.
 */
class AccountBinder(
    private val syncDb: SyncDatabase,
    private val profiles: suspend () -> List<Profile>,
    private val decodeCredentials: (Profile) -> ProfileCredentials?,
    private val spotify: SpotifyIdentityResolver,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao get() = syncDb.syncDao()

    suspend fun refresh(cloudAccounts: List<CloudAccountDescriptor>): BindingSnapshot {
        val localProfiles = profiles().sortedWith(compareBy(Profile::createdAt, Profile::id))
        // Network (Spotify /me) happens here, outside any transaction.
        val identities = localProfiles.associate { it.id to identityOf(it) }
        val now = clock()

        return syncDb.withTransaction {
            val profileIds = localProfiles.mapTo(HashSet(), Profile::id)
            for (binding in dao.bindings()) {
                if (binding.localProfileId !in profileIds) unbind(binding)
            }
            val bindingsByProfile = dao.bindings().associateByTo(HashMap(), SyncBindingEntity::localProfileId)
            val bindingsByScope = bindingsByProfile.values.associateByTo(HashMap(), SyncBindingEntity::syncProfileId)
            val active = mutableListOf<ActiveBinding>()
            val newlyBound = mutableListOf<ActiveBinding>()
            // null = Apple Music "not synced", filled in once the cloud-only list is final.
            val statuses = LinkedHashMap<Profile, SyncAccountStatus?>()

            for (profile in localProfiles) {
                val identity = identities.getValue(profile.id)
                val binding = bindingsByProfile[profile.id]
                statuses[profile] = if (binding != null) {
                    val (status, updated) = verify(binding, identity)
                    if (updated != binding) {
                        if (updated.state != binding.state) forgetDescriptorState(updated.syncProfileId)
                        dao.upsertBinding(updated)
                        bindingsByProfile[profile.id] = updated
                        bindingsByScope[updated.syncProfileId] = updated
                    }
                    if (status == SyncAccountStatus.Syncing) active += updated.toActive()
                    status
                } else {
                    when (identity) {
                        Identity.NoCredentials, Identity.Unusable -> SyncAccountStatus.NeedsReconnect
                        Identity.Unresolved -> SyncAccountStatus.NeedsIdentity
                        Identity.Explicit -> null
                        is Identity.Known -> {
                            val scope = AccountFingerprints.deterministicId(identity.fingerprint)
                            val holder = bindingsByScope[scope]
                            if (holder != null) {
                                val holderName = localProfiles.firstOrNull { it.id == holder.localProfileId }
                                    ?.displayName
                                    .orEmpty()
                                SyncAccountStatus.DuplicateOf(holderName)
                            } else {
                                val created = bind(
                                    profile = profile,
                                    scope = scope,
                                    fingerprintHash = AccountFingerprints.fingerprintHash(identity.fingerprint),
                                    now = now,
                                )
                                bindingsByProfile[profile.id] = created
                                bindingsByScope[scope] = created
                                active += created.toActive()
                                newlyBound += created.toActive()
                                SyncAccountStatus.Syncing
                            }
                        }
                    }
                }
            }

            val cloudOnly = cloudAccounts
                .distinctBy(CloudAccountDescriptor::syncProfileId)
                .filter { it.syncProfileId !in bindingsByScope }
                .map { descriptor ->
                    CloudAccountRow(
                        syncProfileId = descriptor.syncProfileId,
                        provider = descriptor.provider,
                        displayName = descriptor.displayName,
                        hint = descriptor.hint,
                        fromDeviceName = descriptor.deviceName,
                    )
                }
            val appleCandidates = cloudOnly.filter { it.provider == MediaId.PROVIDER_APPLE_MUSIC }
            val rows = statuses.map { (profile, status) ->
                SyncAccountRow(
                    localProfileId = profile.id,
                    provider = profile.provider,
                    displayName = profile.displayName,
                    status = status ?: SyncAccountStatus.NotSynced(appleCandidates),
                )
            }
            BindingSnapshot(active = active, rows = rows, cloudOnly = cloudOnly, newlyBound = newlyBound)
        }
    }

    /** Apple Music "Start syncing": binds to a new random scope. Null if not an unbound Apple Music profile. */
    suspend fun startSyncing(localProfileId: String): ActiveBinding? {
        val profile = profiles().firstOrNull { it.id == localProfileId } ?: return null
        if (profile.provider != MediaId.PROVIDER_APPLE_MUSIC) return null
        return syncDb.withTransaction {
            if (dao.bindingFor(localProfileId) != null) return@withTransaction null
            bind(profile, scope = APPLE_SCOPE_PREFIX + UUID.randomUUID(), fingerprintHash = null, now = clock())
                .toActive()
        }
    }

    /**
     * "Link" an unbound local profile to an account already in Drive (Apple
     * Music's only way to join another device's data). Refused unless the
     * replica holds that account's descriptor for the same provider and no
     * other local profile is bound to it.
     */
    suspend fun link(localProfileId: String, syncProfileId: String): Boolean {
        val profile = profiles().firstOrNull { it.id == localProfileId } ?: return false
        val fingerprintHash = (identityOf(profile) as? Identity.Known)
            ?.let { AccountFingerprints.fingerprintHash(it.fingerprint) }
        return syncDb.withTransaction {
            if (dao.bindingFor(localProfileId) != null) return@withTransaction false
            if (dao.bindingForScope(syncProfileId) != null) return@withTransaction false
            val descriptor = dao.getRecord(SyncKinds.ACCOUNT, SyncFormat.GLOBAL_SCOPE, syncProfileId)
                ?.toRecord()
                ?.let(CloudAccountDescriptor::fromRecord)
            if (descriptor?.provider != profile.provider) return@withTransaction false
            bind(profile, syncProfileId, fingerprintHash, clock())
            true
        }
    }

    /** "Keep syncing as before": the changed fingerprint is the same account (new address, re-sign-in). */
    suspend fun keepAsBefore(localProfileId: String): Boolean = syncDb.withTransaction {
        val binding = dao.bindingFor(localProfileId) ?: return@withTransaction false
        val observed = binding.observedFingerprintHash
        if (binding.state != SyncBindingEntity.STATE_PAUSED_MISMATCH || observed == null) {
            return@withTransaction false
        }
        dao.upsertBinding(
            binding.copy(
                fingerprintHash = observed,
                state = SyncBindingEntity.STATE_ACTIVE,
                observedFingerprintHash = null,
            ),
        )
        true
    }

    /** First half of "Start fresh as this account"; null unless the binding is paused on a mismatch. */
    suspend fun prepareStartFresh(localProfileId: String): StartFreshPlan? {
        val binding = dao.bindingFor(localProfileId) ?: return null
        if (binding.state != SyncBindingEntity.STATE_PAUSED_MISMATCH) return null
        val profile = profiles().firstOrNull { it.id == localProfileId } ?: return null
        val fingerprint = (identityOf(profile) as? Identity.Known)?.fingerprint
        val newScope = fingerprint?.let(AccountFingerprints::deterministicId)?.takeIf { scope ->
            dao.bindingForScope(scope)?.let { it.localProfileId == localProfileId } ?: true
        }
        return StartFreshPlan(
            localProfileId = localProfileId,
            provider = profile.provider,
            oldScope = binding.syncProfileId,
            newSyncProfileId = newScope,
            newFingerprintHash = newScope?.let { fingerprint?.let(AccountFingerprints::fingerprintHash) },
            pendingChanges = dao.localStatesInScope(binding.syncProfileId).count { it.pending },
        )
    }

    /**
     * Second half: drops the old scope's local state, unbinds, and binds to the
     * plan's new scope if it is still free. False (nothing changed) when the
     * plan is stale or local changes are still waiting to upload.
     */
    suspend fun completeStartFresh(plan: StartFreshPlan): Boolean = syncDb.withTransaction {
        val binding = dao.bindingFor(plan.localProfileId)
        if (binding == null || binding.syncProfileId != plan.oldScope) return@withTransaction false
        if (dao.localStatesInScope(plan.oldScope).any { it.pending }) return@withTransaction false
        dao.deleteLocalStatesInScope(plan.oldScope)
        dao.deleteLocalStatesForProfile(plan.localProfileId)
        forgetDescriptorState(plan.oldScope)
        dao.deleteBinding(plan.localProfileId)
        val newScope = plan.newSyncProfileId
        if (newScope != null && plan.newFingerprintHash != null && dao.bindingForScope(newScope) == null) {
            dao.deleteLocalStatesInScope(newScope)
            forgetDescriptorState(newScope)
            dao.upsertBinding(
                SyncBindingEntity(
                    localProfileId = plan.localProfileId,
                    syncProfileId = newScope,
                    provider = plan.provider,
                    fingerprintHash = plan.newFingerprintHash,
                    state = SyncBindingEntity.STATE_ACTIVE,
                    observedFingerprintHash = null,
                    boundAt = clock(),
                ),
            )
        }
        true
    }

    /** Status of an existing binding under [identity], and the binding as it should be stored. */
    private fun verify(binding: SyncBindingEntity, identity: Identity): Pair<SyncAccountStatus, SyncBindingEntity> =
        when (identity) {
            Identity.NoCredentials, Identity.Unusable -> SyncAccountStatus.NeedsReconnect to binding
            Identity.Unresolved -> SyncAccountStatus.NeedsIdentity to binding
            Identity.Explicit ->
                if (binding.state == SyncBindingEntity.STATE_ACTIVE) {
                    SyncAccountStatus.Syncing to binding
                } else {
                    SyncAccountStatus.AccountChanged to binding
                }
            is Identity.Known -> {
                val observed = AccountFingerprints.fingerprintHash(identity.fingerprint)
                when {
                    // Linked before its fingerprint was known: adopt the first one seen.
                    binding.fingerprintHash == null -> SyncAccountStatus.Syncing to binding.copy(
                        fingerprintHash = observed,
                        state = SyncBindingEntity.STATE_ACTIVE,
                        observedFingerprintHash = null,
                    )
                    // Same account (again): resume a paused binding.
                    observed == binding.fingerprintHash -> SyncAccountStatus.Syncing to binding.copy(
                        state = SyncBindingEntity.STATE_ACTIVE,
                        observedFingerprintHash = null,
                    )
                    else -> SyncAccountStatus.AccountChanged to binding.copy(
                        state = SyncBindingEntity.STATE_PAUSED_MISMATCH,
                        observedFingerprintHash = observed,
                    )
                }
            }
        }

    private suspend fun bind(profile: Profile, scope: String, fingerprintHash: String?, now: Long): SyncBindingEntity {
        // A binding always JOINs: no local state may exist for its scope (nor for its descriptor).
        dao.deleteLocalStatesInScope(scope)
        forgetDescriptorState(scope)
        val binding = SyncBindingEntity(
            localProfileId = profile.id,
            syncProfileId = scope,
            provider = profile.provider,
            fingerprintHash = fingerprintHash,
            state = SyncBindingEntity.STATE_ACTIVE,
            observedFingerprintHash = null,
            boundAt = now,
        )
        dao.upsertBinding(binding)
        return binding
    }

    /** The profile is gone: forget the binding and its local state; replica records stay. */
    private suspend fun unbind(binding: SyncBindingEntity) {
        dao.deleteLocalStatesForProfile(binding.localProfileId)
        dao.deleteLocalStatesInScope(binding.syncProfileId)
        forgetDescriptorState(binding.syncProfileId)
        dao.deleteBinding(binding.localProfileId)
        dao.deleteMeta(SyncMetaKeys.SPOTIFY_UID_PREFIX + binding.localProfileId)
    }

    /**
     * Drops the global [SyncKinds.ACCOUNT] local state of [scope] whenever this
     * device starts or stops describing it (only ACTIVE bindings are described).
     * Left behind, the descriptor would read as a missing row on the next
     * capture, and two at once trip the engine's domain-reset check for the
     * whole global scope (settings, translations). The replica record stays;
     * the next apply re-acknowledges it.
     */
    private suspend fun forgetDescriptorState(scope: String) {
        dao.deleteLocalState(SyncKinds.ACCOUNT, SyncFormat.GLOBAL_SCOPE, scope)
    }

    private suspend fun identityOf(profile: Profile): Identity {
        val credentials = runCatching { decodeCredentials(profile) }.getOrNull() ?: return Identity.NoCredentials
        if (credentials.providerId != profile.provider) return Identity.Unusable
        return when (credentials) {
            is ProfileCredentials.Subsonic ->
                AccountFingerprints.subsonic(credentials.serverUrl, credentials.username)
                    ?.let(Identity::Known)
                    ?: Identity.Unusable
            is ProfileCredentials.Spotify -> {
                val userId = try {
                    spotify.userId(profile.id, credentials)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                userId?.let { Identity.Known(AccountFingerprints.spotify(it)) } ?: Identity.Unresolved
            }
            is ProfileCredentials.AppleMusic -> Identity.Explicit
        }
    }

    private fun SyncBindingEntity.toActive() = ActiveBinding(localProfileId, syncProfileId, provider)

    private sealed interface Identity {
        data class Known(val fingerprint: String) : Identity

        /** Credentials can't be read on this device (e.g. restored backup). */
        data object NoCredentials : Identity

        /** Credentials present but no fingerprint can be derived (unparseable address, blank user...). */
        data object Unusable : Identity

        /** Spotify user id not known yet; retry next cycle. */
        data object Unresolved : Identity

        /** Apple Music: identity only through explicit user choice. */
        data object Explicit : Identity
    }

    companion object {
        const val APPLE_SCOPE_PREFIX = "a-"
    }
}
