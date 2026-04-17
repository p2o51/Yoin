package com.gpo.yoin.data.profile

import com.gpo.yoin.data.model.MediaId

/**
 * Typed enumeration of music backends Yoin can talk to. Room stores the
 * string [key]; domain/UI code should deal in [ProviderKind] values so the
 * compiler catches provider-specific branches.
 *
 * Add a new kind here first — callers will break in a controlled way.
 */
enum class ProviderKind(
    val key: String,
    val displayLabel: String,
    val isAvailable: Boolean,
) {
    SUBSONIC(MediaId.PROVIDER_SUBSONIC, "Subsonic", isAvailable = true),
    SPOTIFY(MediaId.PROVIDER_SPOTIFY, "Spotify", isAvailable = true),
    LOCAL(MediaId.PROVIDER_LOCAL, "Local", isAvailable = false),
    ;

    companion object {
        fun fromKey(key: String): ProviderKind? = entries.firstOrNull { it.key == key }

        fun fromKeyOrSubsonic(key: String): ProviderKind =
            fromKey(key) ?: SUBSONIC
    }
}
