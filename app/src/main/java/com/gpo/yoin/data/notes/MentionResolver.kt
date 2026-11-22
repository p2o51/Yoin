package com.gpo.yoin.data.notes

import com.gpo.yoin.data.local.MentionNameRow
import com.gpo.yoin.data.local.NoteMentionDao
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.shared.notes.MentionKind
import com.gpo.yoin.shared.notes.MentionMatcher
import com.gpo.yoin.shared.notes.MentionRef
import com.gpo.yoin.shared.notes.NoteMention
import kotlin.coroutines.cancellation.CancellationException

/** The account a mention is read under: its profile, and the provider its ids belong to. */
data class MentionAccount(val profileId: String, val provider: String)

/** What a mention points at for the account in use. */
sealed interface MentionResolution {
    val mention: NoteMention

    /** [id] is an item of the account in use; [via] says how it was found. */
    data class Resolved(override val mention: NoteMention, val id: MediaId, val via: Via) : MentionResolution

    /** Nothing here answers to it: drawn as plain text in the normal colour (owner 2026-11-22), not tappable. */
    data class Unresolved(override val mention: NoteMention) : MentionResolution

    enum class Via {
        /** The mention's own id (same provider as the account). */
        Id,

        /** Matched by name, title / artist / album, against what the account knows locally. */
        Name,
    }
}

fun interface MentionResolver {
    suspend fun resolve(mention: NoteMention): MentionResolution
}

/** Each distinct mention resolved once. */
suspend fun MentionResolver.resolveAll(mentions: Collection<NoteMention>): Map<MentionRef, MentionResolution> {
    val out = LinkedHashMap<MentionRef, MentionResolution>()
    mentions.forEach { mention -> if (mention.ref !in out) out[mention.ref] = resolve(mention) }
    return out
}

/** The local names a mention can be matched against, on one account. */
interface MentionNameLookup {
    suspend fun tracks(profileId: String, provider: String, titlePrefix: String): List<MentionNameRow>

    suspend fun albums(profileId: String, provider: String, titlePrefix: String): List<MentionNameRow>
}

class RoomMentionNameLookup(private val dao: NoteMentionDao) : MentionNameLookup {
    override suspend fun tracks(profileId: String, provider: String, titlePrefix: String) =
        dao.tracksTitled(profileId, provider, likeEscape(titlePrefix))

    override suspend fun albums(profileId: String, provider: String, titlePrefix: String) =
        dao.albumsTitled(profileId, provider, likeEscape(titlePrefix))
}

/**
 * Resolves mentions for the account in use, locally (no service is asked), owner 2026-11-22:
 *  1. by id: a mention on the account's own provider is that item. Spotify and Apple Music catalogue ids
 *     mean the same item on every account; a Subsonic id is trusted too, as notes are per account and the
 *     Memories transfer rewrites mentions when it moves them.
 *  2. by name: any other provider's mention (a Spotify mention read on Subsonic) is matched by title +
 *     artist (+ album for a track) against the tracks and albums this account knows — its plays, notes and
 *     album notes ([MentionMatcher]). An artist has no local record to match, so it stays unresolved.
 *  3. otherwise [MentionResolution.Unresolved].
 */
class LocalMentionResolver(
    private val account: suspend () -> MentionAccount?,
    private val names: MentionNameLookup,
) : MentionResolver {

    override suspend fun resolve(mention: NoteMention): MentionResolution {
        val current = account() ?: return MentionResolution.Unresolved(mention)
        if (mention.provider == current.provider) {
            return MentionResolution.Resolved(mention, MediaId(current.provider, mention.id), MentionResolution.Via.Id)
        }
        val found = try {
            byName(mention, current)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        return found
            ?.let { rawId ->
                MentionResolution.Resolved(mention, MediaId(current.provider, rawId), MentionResolution.Via.Name)
            }
            ?: MentionResolution.Unresolved(mention)
    }

    private suspend fun byName(mention: NoteMention, account: MentionAccount): String? {
        val prefix = MentionMatcher.searchPrefix(mention.title) ?: return null
        return when (mention.kind) {
            MentionKind.Track -> names.tracks(account.profileId, account.provider, prefix)
                .filter { row -> MentionMatcher.namesMatchTrack(mention, row.title, row.artist, row.album) }
                // A row that names the same album beats one that doesn't say.
                .sortedByDescending { row -> !mention.album.isNullOrBlank() && !row.album.isNullOrBlank() }
                .firstOrNull()?.id
            MentionKind.Album -> names.albums(account.profileId, account.provider, prefix)
                .firstOrNull { row -> MentionMatcher.namesMatchAlbum(mention, row.title, row.artist) }?.id
            MentionKind.Artist -> null
        }?.let { stored -> MediaId.storedRawId(account.provider, stored) }?.takeIf(String::isNotEmpty)
    }
}

/** [value] for `LIKE :value || '%' ESCAPE '\'`. */
internal fun likeEscape(value: String): String = buildString(value.length) {
    value.forEach { c ->
        if (c == '\\' || c == '%' || c == '_') append('\\')
        append(c)
    }
}
