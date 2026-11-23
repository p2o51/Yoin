package com.gpo.yoin.player.sing

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.ServiceFeatureCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SingProviderSelectorTest {
    private val onDevice = object : SingStreamProvider {
        override fun stream(request: SingRequest): Flow<SingStatus> = flowOf(SingStatus.Preparing())
    }
    private val lyra = LyraSingStreamProvider()

    @Test
    fun should_declareSingOnlyForSubsonic_when_catalogRead() {
        assertTrue(Capability.SING in ServiceFeatureCatalog.subsonic.capabilities)
        assertFalse(Capability.SING in ServiceFeatureCatalog.spotify.capabilities)
        assertFalse(Capability.SING in ServiceFeatureCatalog.appleMusic.capabilities)
        assertFalse(Capability.SING in ServiceFeatureCatalog.local.capabilities)
        assertFalse(SingProviderSelector.supportsSing(null))
    }

    @Test
    fun should_separateOnDevice_when_subsonicAndLyraOffline() {
        val selector = SingProviderSelector(onDevice, lyra) { false }
        assertSame(onDevice, selector.providerFor(MediaId.PROVIDER_SUBSONIC))
    }

    @Test
    fun should_useLyra_when_online() {
        val selector = SingProviderSelector(onDevice, lyra) { true }
        assertSame(lyra, selector.providerFor(MediaId.PROVIDER_SUBSONIC))
    }

    @Test
    fun should_beUnavailable_when_spotifyOrAppleMusic() = runTest {
        val selector = SingProviderSelector(onDevice, lyra) { true }
        for (provider in listOf(MediaId.PROVIDER_SPOTIFY, MediaId.PROVIDER_APPLE_MUSIC, MediaId.PROVIDER_LOCAL)) {
            val p = selector.providerFor(provider)
            assertSame(UnavailableSingStreamProvider, p)
            val request = SingRequest(MediaId(provider, "x"), "p")
            assertEquals(SingStatus.Unavailable(SingUnavailableReason.ProviderUnsupported), p.stream(request).first())
            assertEquals(SingAvailability.Unavailable(SingUnavailableReason.ProviderUnsupported), p.availability())
        }
    }

    @Test
    fun should_reportPending_when_lyraStubAsked() = runTest {
        val status = lyra.stream(SingRequest(MediaId.subsonic("1"), "p")).first()
        assertEquals(SingStatus.Unavailable(SingUnavailableReason.LyraPending), status)
    }
}
