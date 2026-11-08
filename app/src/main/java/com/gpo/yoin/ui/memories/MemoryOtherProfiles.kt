package com.gpo.yoin.ui.memories

import androidx.compose.runtime.Immutable
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.SubsonicApiFactory
import com.gpo.yoin.ui.settings.ProfileCard
import com.gpo.yoin.ui.settings.profileCardsFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/*
 * Memories shows every account's memories (owner 2026-11-08), not only the account in use. A memory from
 * another account says so beside its Go to album (「来自 …」, owner 2026-11-04: just the account, no "this
 * switches accounts"), and is read-only here: its title, review and NeoDB line belong to that account. Go to
 * album, or playing one of its tracks, switches to that account first (as Home's other-accounts shelf does).
 */

/** The account a memory belongs to, when it isn't the one in use: what the "From …" line shows. */
@Immutable
data class MemoryOtherProfile(
    val id: String,
    val name: String,
    /** The account's avatar shape and service, for the mark before its name. */
    val shape: Int = 0,
    val kind: ProviderKind = ProviderKind.SUBSONIC,
)

/** Another account the deck reads memories from: who it is, its provider, and its own cover URLs. */
class MemoryProfileSource(
    val profile: MemoryOtherProfile,
    /** The provider key its rows are stored under ([com.gpo.yoin.data.model.MediaId.provider]). */
    val provider: String,
    val resolveCoverUrl: (CoverRef, Int?) -> String?,
)

/** The accounts other than the one in use whose memories the deck also deals. */
fun interface OtherMemoryProfiles {
    suspend fun list(): List<MemoryProfileSource>
}

/**
 * The accounts Yoin could switch to: the same ones Home's other-accounts shelf offers (not in use, nothing
 * standing in the way), since Go to album has to switch there.
 */
internal fun usableOtherProfiles(cards: List<ProfileCard>): List<ProfileCard> =
    cards.filter { !it.isActive && it.unavailableReason == null && !it.requiresReconnect }

/** [OtherMemoryProfiles] over the app's accounts: a Subsonic account's covers use its own server. */
internal fun containerOtherMemoryProfiles(container: AppContainer): OtherMemoryProfiles = OtherMemoryProfiles {
    // credentials are decoded from disk: off the main thread
    withContext(Dispatchers.IO) { otherMemoryProfiles(container) }
}

private suspend fun otherMemoryProfiles(container: AppContainer): List<MemoryProfileSource> {
    val profiles = container.profileManager
    val rows = profiles.profiles.first().associateBy { it.id }
    return usableOtherProfiles(profileCardsFlow(container).first()).mapNotNull { card ->
        val row = rows[card.id] ?: return@mapNotNull null
        val server = if (card.provider == ProviderKind.SUBSONIC) {
            (profiles.decodeCredentials(row) as? ProfileCredentials.Subsonic)?.let { credentials ->
                ServerCredentials(credentials.serverUrl, credentials.username, credentials.password)
            }
        } else {
            null
        }
        MemoryProfileSource(
            profile = MemoryOtherProfile(
                id = card.id,
                name = card.title,
                shape = card.avatarShape,
                kind = card.provider,
            ),
            provider = card.provider.key,
            resolveCoverUrl = { ref, size -> otherProfileCoverUrl(ref, size, server) },
        )
    }
}

/** A URL is the cover itself; a Subsonic cover id needs that account's server (null for any other service). */
internal fun otherProfileCoverUrl(ref: CoverRef, size: Int?, server: ServerCredentials?): String? = when (ref) {
    is CoverRef.Url -> ref.url
    is CoverRef.SourceRelative -> server?.let { SubsonicApiFactory.buildCoverArtUrl(it, ref.coverArtId, size) }
}
