package com.gpo.yoin.ui.notes

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.notes.BacklinkSource
import com.gpo.yoin.data.notes.MentionAccount
import com.gpo.yoin.data.notes.MentionResolution
import com.gpo.yoin.data.notes.MentionResolver
import com.gpo.yoin.shared.notes.MentionKind
import com.gpo.yoin.shared.notes.MentionRef
import com.gpo.yoin.shared.notes.NoteMention
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Where a mention (or a 被提及 clipping) takes the reader. */
sealed interface NoteDestination {
    /** An album's scrapbook (page 2); [focusTrack] highlights one of its songs there. */
    data class AlbumScrapbook(val albumId: MediaId, val focusTrack: MediaId? = null) : NoteDestination

    data class Artist(val artistId: MediaId) : NoteDestination
}

/**
 * The mentions in drawn notes for the account in use (notes revamp phase B): whether each resolves
 * ([isResolved], read in composition: a resolution that lands later redraws the text) and where a tap goes
 * ([open]). A mention on the account's own provider resolves at once; another provider's is matched by name
 * in the background ([MentionResolver]). Unresolved ones draw as plain text and take no tap (owner
 * 2026-11-22). The cache is the account's: a profile switch starts over.
 */
@Stable
class NoteMentionController(
    private val resolver: MentionResolver,
    private val account: () -> MentionAccount?,
    private val scope: CoroutineScope,
    private val trackAlbum: suspend (MediaId) -> MediaId?,
    private val navigate: (Context, NoteDestination) -> Unit,
    private val resolveCover: (coverKey: String?) -> String? = { null },
) {
    private val resolved = mutableStateMapOf<MentionRef, MentionResolution>()
    private val asked = HashSet<MentionRef>()
    private var cacheFor: String? = null

    fun isResolved(mention: NoteMention): Boolean {
        val current = account() ?: return false
        if (cacheFor != current.profileId) {
            cacheFor = current.profileId
            resolved.clear()
            asked.clear()
        }
        if (mention.provider == current.provider) return true
        resolved[mention.ref]?.let { return it is MentionResolution.Resolved }
        if (asked.add(mention.ref)) {
            scope.launch { resolved[mention.ref] = resolver.resolve(mention) }
        }
        return false
    }

    /** Opens what [mention] names: an album's scrapbook, a song's album scrapbook on that song, an artist. */
    fun open(context: Context, mention: NoteMention) {
        scope.launch {
            val resolution = resolver.resolve(mention) as? MentionResolution.Resolved ?: return@launch
            destinationOf(resolution, trackAlbum)?.let { navigate(context, it) }
        }
    }

    fun open(context: Context, destination: NoteDestination) = navigate(context, destination)

    /** Opens where a 被提及 clipping was written: that album's scrapbook (a song note: on its song). */
    fun openSource(context: Context, source: BacklinkSource) {
        scope.launch { destinationOf(source, trackAlbum)?.let { navigate(context, it) } }
    }

    /** A clipping's cover, from the storage key Yoin's plays kept. */
    fun coverUrl(coverKey: String?): String? = coverKey?.let(resolveCover)

    companion object {
        suspend fun destinationOf(source: BacklinkSource, trackAlbum: suspend (MediaId) -> MediaId?): NoteDestination? =
            when (source) {
                is BacklinkSource.SongNote -> (source.albumId ?: trackAlbum(source.trackId))
                    ?.let { NoteDestination.AlbumScrapbook(it, focusTrack = source.trackId) }
                is BacklinkSource.AlbumNote -> NoteDestination.AlbumScrapbook(source.albumId)
                is BacklinkSource.Review -> NoteDestination.AlbumScrapbook(source.albumId)
            }

        suspend fun destinationOf(
            resolution: MentionResolution.Resolved,
            trackAlbum: suspend (MediaId) -> MediaId?,
        ): NoteDestination? = when (resolution.mention.kind) {
            MentionKind.Album -> NoteDestination.AlbumScrapbook(resolution.id)
            MentionKind.Track -> trackAlbum(resolution.id)?.let { NoteDestination.AlbumScrapbook(it, focusTrack = resolution.id) }
            MentionKind.Artist -> NoteDestination.Artist(resolution.id)
        }
    }
}

/** The mentions of the window's notes; null in previews (every mention then draws as one, not tappable). */
val LocalNoteMentions = staticCompositionLocalOf<NoteMentionController?> { null }
