package com.gpo.yoin.data.openin

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.shared.openin.YoinMusicLink
import com.gpo.yoin.shared.openin.YoinMusicLinkKind
import com.gpo.yoin.shared.openin.YoinMusicLinkService

/**
 * Where a music link goes (docs/open-in-yoin.md, owner 2026-11-24): by the user's accounts, not only the one
 * in use. A link belongs to its service; an account on that service is a [Hit] and the link opens there (Yoin
 * switches to it first when another account is in use). No account on that service: the link was only
 * [Found], and its title is searched on the account in use, the results ranked for the user to choose. A
 * found link never plays by itself, however close the match.
 */
sealed interface OpenInTarget {
    /** [profileId] is on the link's service: open the item itself there. */
    data class Hit(val profileId: String, val profileName: String) : OpenInTarget

    /** No account on the link's service: search the account in use. */
    data object Found : OpenInTarget
}

/** What a [OpenInTarget.Hit] does with the item, by [MediaId] on the link's own service. */
sealed interface OpenInHitAction {
    val id: MediaId

    data class PlayTrack(override val id: MediaId) : OpenInHitAction
    data class OpenAlbum(override val id: MediaId) : OpenInHitAction
    data class OpenArtist(override val id: MediaId) : OpenInHitAction
    data class OpenPlaylist(override val id: MediaId) : OpenInHitAction
}

object OpenInRouting {

    /** The Yoin provider token ([MediaId.provider]) of [service]. */
    fun providerOf(service: YoinMusicLinkService): String = when (service) {
        YoinMusicLinkService.Spotify -> MediaId.PROVIDER_SPOTIFY
        YoinMusicLinkService.AppleMusic -> MediaId.PROVIDER_APPLE_MUSIC
    }

    /**
     * [link]'s target among [profiles]. Of several accounts on its service, the one in use wins, else the
     * oldest (the first one added), so the same link always lands on the same account.
     */
    fun route(link: YoinMusicLink, profiles: List<Profile>, activeProfileId: String?): OpenInTarget {
        val provider = providerOf(link.service)
        val owners = profiles.filter { it.provider == provider }
        val owner = owners.firstOrNull { it.id == activeProfileId }
            ?: owners.minWithOrNull(compareBy<Profile> { it.createdAt }.thenBy { it.id })
            ?: return OpenInTarget.Found
        return OpenInTarget.Hit(owner.id, owner.displayName)
    }

    /** What opening [link] on its own service does; null for a short link not yet followed. */
    fun hitAction(link: YoinMusicLink): OpenInHitAction? {
        val id = MediaId(providerOf(link.service), link.id)
        return when (link.kind) {
            YoinMusicLinkKind.Track -> OpenInHitAction.PlayTrack(id)
            YoinMusicLinkKind.Album -> OpenInHitAction.OpenAlbum(id)
            YoinMusicLinkKind.Artist -> OpenInHitAction.OpenArtist(id)
            YoinMusicLinkKind.Playlist -> OpenInHitAction.OpenPlaylist(id)
            YoinMusicLinkKind.ShortLink -> null
        }
    }
}
