package com.gpo.yoin.data.transfer

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.PartialPlaylistImportException
import kotlinx.coroutines.CancellationException

/**
 * Copies a playlist from one profile's service to another's, both live in
 * this install (docs/playlist-transfer.md §3): no stash in between. Read the
 * source playlist, [resolve] each track on the target (ISRC first where the
 * target can look one up, then a title search scored by [TrackMatcher]),
 * then [write] a new playlist holding the matched tracks in the source's
 * order. Ambiguous and unmatched tracks are reported, never added.
 */
class PlaylistTransfer(
    private val matcher: TrackMatcher = TrackMatcher,
) {
    /**
     * Each of [tracks] (the source playlist's, in order) resolved on
     * [target]. [onProgress] is told how many were looked at, of how many.
     * A search that fails leaves its track unmatched rather than failing
     * the whole copy; cancellation still cancels.
     */
    suspend fun resolve(
        tracks: List<Track>,
        target: MusicSource,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<TrackResolution> {
        val library = target.library()
        val isrcs = tracks.mapNotNull { it.extras[Track.EXTRA_ISRC] }.distinct()
        val byIsrc: Map<String, List<Track>> = if (isrcs.isEmpty()) {
            emptyMap()
        } else {
            try {
                library.tracksByIsrc(isrcs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyMap()
            }
        }
        val searches = HashMap<String, List<Track>>()
        return tracks.mapIndexed { index, track ->
            val isrcDecision = track.extras[Track.EXTRA_ISRC]
                ?.let(byIsrc::get)
                ?.let { matcher.chooseByIsrc(track, it) }
            val decision = isrcDecision?.takeIf { it is MatchDecision.Matched } ?: run {
                val query = matcher.searchQuery(track)
                if (query == null) {
                    MatchDecision.NotFound
                } else {
                    val candidates = searches.getOrPut(query) { search(target, query) }
                    val decision = matcher.choose(track, candidates)
                    // The artist as the target spells it can sink "title artist" (a romanised name against
                    // the original script): the title alone, then.
                    val titleOnly = matcher.titleQuery(track)
                    if (decision == MatchDecision.NotFound && titleOnly != null && titleOnly != query) {
                        matcher.choose(track, searches.getOrPut(titleOnly) { search(target, titleOnly) })
                    } else {
                        decision
                    }
                }
            }
            onProgress(index + 1, tracks.size)
            TrackResolution(position = index, source = track, decision = decision)
        }
    }

    /**
     * A playlist named [name] on [target] holding [resolutions]' matched
     * tracks, in order (the same target track twice stays twice, as the
     * source had it).
     */
    suspend fun write(
        name: String,
        description: String?,
        resolutions: List<TrackResolution>,
        target: MusicSource,
    ): TransferReport {
        val ids = resolutions.mapNotNull { (it.decision as? MatchDecision.Matched)?.track?.id }
        // Nothing matched: no empty playlist left behind on the target.
        if (ids.isEmpty()) return TransferReport(name, created = null, resolutions = resolutions, added = 0)
        val result = target.writeActions().createPlaylistWithTracks(name, description, ids)
        val playlist = result.getOrNull()
        val failure = result.exceptionOrNull()
        if (failure is CancellationException) throw failure
        val partial = failure as? PartialPlaylistImportException
        return TransferReport(
            playlistName = name,
            created = playlist ?: partial?.playlist,
            resolutions = resolutions,
            added = when {
                playlist != null -> ids.size
                partial != null -> partial.added
                else -> 0
            },
            failure = failure,
        )
    }

    /** [resolve] then [write]: the whole copy of [source] to [target]. */
    suspend fun copy(
        source: Playlist,
        target: MusicSource,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): TransferReport {
        val resolutions = resolve(source.tracks, target, onProgress)
        return write(source.name, source.comment?.takeIf(String::isNotBlank), resolutions, target)
    }

    private suspend fun search(target: MusicSource, query: String): List<Track> = try {
        target.library().search(query).tracks
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }
}

/** One source track at [position] and what became of it on the target. */
data class TrackResolution(
    val position: Int,
    val source: Track,
    val decision: MatchDecision,
)

/**
 * The outcome of a copy. [created] is the new playlist on the target, null
 * when it couldn't be made; [added] how many tracks it holds; [failure] what
 * stopped the write, if anything (a [PartialPlaylistImportException] still
 * has [created]).
 */
data class TransferReport(
    val playlistName: String,
    val created: Playlist?,
    val resolutions: List<TrackResolution>,
    val added: Int,
    val failure: Throwable? = null,
) {
    val total: Int get() = resolutions.size
    val matched: List<TrackResolution> get() = resolutions.filter { it.decision is MatchDecision.Matched }
    val ambiguous: List<TrackResolution> get() = resolutions.filter { it.decision is MatchDecision.Ambiguous }
    val unmatched: List<TrackResolution> get() = resolutions.filter { it.decision is MatchDecision.NotFound }

    /** Tracks the target didn't get: ambiguous and not found, in playlist order. */
    val missing: List<TrackResolution> get() = resolutions.filter { it.decision !is MatchDecision.Matched }
    val createdId: MediaId? get() = created?.id
}
