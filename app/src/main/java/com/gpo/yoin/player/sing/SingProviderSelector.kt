package com.gpo.yoin.player.sing

import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.ServiceFeatureCatalog

/**
 * Which [SingStreamProvider] makes a song's Sing stream:
 *
 * - a service without [Capability.SING] (Spotify, Apple Music: their own players, their audio never
 *   reaches Yoin's sink) → unavailable;
 * - a Subsonic song while the Lyra server is online → the Lyra (server) path;
 * - a Subsonic song otherwise, streamed or downloaded → the on-device engine.
 */
class SingProviderSelector(
    private val onDevice: SingStreamProvider,
    private val lyra: SingStreamProvider = UnavailableSingStreamProvider,
    private val isLyraOnline: () -> Boolean = { false },
) {
    fun providerFor(providerId: String): SingStreamProvider = when {
        !supportsSing(providerId) -> UnavailableSingStreamProvider
        isLyraOnline() -> lyra
        else -> onDevice
    }

    companion object {
        fun supportsSing(providerId: String?): Boolean =
            Capability.SING in ServiceFeatureCatalog.forProvider(providerId).capabilities
    }
}
