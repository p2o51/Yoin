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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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
    // A resolved mention's artwork: a song's or album's cover, an artist's picture (null: none known).
    private val artwork: suspend (MentionResolution.Resolved) -> String? = { null },
    // The account in use as it changes: answers for other accounts are dropped then (not in composition).
    accountChanges: Flow<String?>? = null,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** Answers by (account, mention): an answer always lands under the account it was asked for. */
    private val resolved = mutableStateMapOf<Pair<String, MentionRef>, MentionResolution>()
    private val asked = HashSet<Pair<String, MentionRef>>()

    /** Artwork by (account, mention); a missing key is not asked yet, [NoArtwork] is asked with no answer. */
    private val artworks = mutableStateMapOf<Pair<String, MentionRef>, String>()
    private val artworkAsked = HashSet<Pair<String, MentionRef>>()
    private var openJob: Job? = null
    private var openedAt: Long? = null

    init {
        accountChanges?.let { changes ->
            scope.launch {
                changes.distinctUntilChanged().collect { profileId ->
                    resolved.keys.filter { it.first != profileId }.forEach(resolved::remove)
                    asked.removeAll { it.first != profileId }
                    artworks.keys.filter { it.first != profileId }.forEach(artworks::remove)
                    artworkAsked.removeAll { it.first != profileId }
                }
            }
        }
    }

    /** Read in composition: never writes snapshot state; a missing answer is asked for in the background. */
    fun isResolved(mention: NoteMention): Boolean {
        val current = account() ?: return false
        if (mention.provider == current.provider) return true
        val key = current.profileId to mention.ref
        resolved[key]?.let { return it is MentionResolution.Resolved }
        if (asked.add(key)) {
            scope.launch { resolved[key] = resolver.resolve(mention) }
        }
        return false
    }

    /**
     * The cover (or artist picture) a drawn mention shows before its name. Read in composition like
     * [isResolved]: null until it is known (asked for once in the background), and for one with none.
     */
    fun artworkUrl(mention: NoteMention): String? {
        val current = account() ?: return null
        val key = current.profileId to mention.ref
        artworks[key]?.let { return it.takeIf { url -> url != NoArtwork } }
        if (artworkAsked.add(key)) {
            scope.launch {
                val resolution = resolver.resolve(mention) as? MentionResolution.Resolved
                val url = try {
                    resolution?.let { artwork(it) }
                } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
                artworks[key] = url?.takeIf(String::isNotBlank) ?: NoArtwork
            }
        }
        return null
    }

    /** Opens what [mention] names: an album's scrapbook, a song's album scrapbook on that song, an artist. */
    fun open(context: Context, mention: NoteMention) = launchOpen(context) {
        val resolution = resolver.resolve(mention) as? MentionResolution.Resolved
        resolution?.let { destinationOf(it, trackAlbum) }
    }

    fun open(context: Context, destination: NoteDestination) = launchOpen(context) { destination }

    /** Opens where a 被提及 clipping was written: that album's scrapbook (a song note: on its song). */
    fun openSource(context: Context, source: BacklinkSource) = launchOpen(context) { destinationOf(source, trackAlbum) }

    /**
     * One open at a time: a second tap while one is being looked up, or right after one opened (the new
     * window is still coming up), does nothing — two taps never stack two detail pages.
     */
    private fun launchOpen(context: Context, destination: suspend () -> NoteDestination?) {
        if (openJob?.isActive == true) return
        if (openedAt?.let { now() - it < OPEN_COOLDOWN_MS } == true) return
        openJob = scope.launch {
            val target = destination() ?: return@launch
            openedAt = now()
            navigate(context, target)
        }
    }

    /** A clipping's cover, from the storage key Yoin's plays kept. */
    fun coverUrl(coverKey: String?): String? = coverKey?.let(resolveCover)

    companion object {
        /** How long after opening a page another tap is taken as the same one. */
        const val OPEN_COOLDOWN_MS = 1_000L

        private const val NoArtwork = ""

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
