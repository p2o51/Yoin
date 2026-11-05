package com.gpo.yoin.data.sync.identity

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.sync.SyncBindingEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountKeysTest {
    private val home = checkNotNull(
        AccountKeys.subsonicFingerprint(ProfileCredentials.Subsonic("https://home.example", "chen", "pw")),
    )
    private val work = checkNotNull(
        AccountKeys.subsonicFingerprint(ProfileCredentials.Subsonic("https://work.example", "chen", "pw")),
    )

    private fun binding(fingerprint: String?, state: String = SyncBindingEntity.STATE_ACTIVE) = SyncBindingEntity(
        localProfileId = "p1",
        syncProfileId = "s-old",
        provider = MediaId.PROVIDER_SUBSONIC,
        fingerprintHash = fingerprint?.let(AccountFingerprints::fingerprintHash),
        state = state,
        observedFingerprintHash = null,
        boundAt = 0L,
    )

    @Test
    fun should_useTheBinding_when_itIsStillThisAccount() {
        assertTrue(AccountKeys.bindingStillFits(binding(home), home))
    }

    @Test
    fun should_notUseTheBinding_when_theProfileWasEditedToAnotherServer() {
        // Sync off, profile edited: the binding is still active but names the old server.
        assertFalse(AccountKeys.bindingStillFits(binding(home), work))
    }

    @Test
    fun should_notUseTheBinding_when_itIsPaused() {
        assertFalse(AccountKeys.bindingStillFits(binding(home, SyncBindingEntity.STATE_PAUSED_MISMATCH), home))
    }

    @Test
    fun should_useTheBinding_when_noFingerprintCanBeChecked() {
        // Apple Music links carry no fingerprint; Spotify's needs the network.
        assertTrue(AccountKeys.bindingStillFits(binding(null), null))
        assertTrue(AccountKeys.bindingStillFits(binding(home), null))
    }
}
