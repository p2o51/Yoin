package com.gpo.yoin.data.transfer.memories

import androidx.room.withTransaction
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.transfer.MatchDecision
import com.gpo.yoin.data.transfer.TrackMatcher
import com.gpo.yoin.shared.portability.memories.AlbumMatchMethod
import com.gpo.yoin.shared.portability.memories.AlbumPick
import com.gpo.yoin.shared.portability.memories.AlbumTarget
import com.gpo.yoin.shared.portability.memories.MatchStatus
import com.gpo.yoin.shared.portability.memories.MemoryItems
import com.gpo.yoin.shared.portability.memories.MemoryTransferApplier
import com.gpo.yoin.shared.portability.memories.MigrationIds
import com.gpo.yoin.shared.portability.memories.SourceRef
import com.gpo.yoin.shared.portability.memories.TargetRef
import com.gpo.yoin.shared.portability.memories.TrackPick
import com.gpo.yoin.shared.portability.memories.TrackTarget
import com.gpo.yoin.shared.portability.memories.TransferAccount
import com.gpo.yoin.shared.portability.memories.TransferCounts
import com.gpo.yoin.shared.portability.memories.TransferLedger
import com.gpo.yoin.shared.portability.memories.TransferResult
import com.gpo.yoin.shared.portability.memories.TransferRules
import kotlin.coroutines.cancellation.CancellationException

/**
 * Copies Memories (song notes, track scores, album scores and reviews, album notes, the listener's album
 * titles, the AI titles and copy, track→album links) from one account into a local profile. Copy, never
 * move. Rules (docs/cloud-sync.md「转移 Memories」):
 *
 *  - Ids: copied as they are when both sides share an id space (same provider and: Spotify ids, Apple Music
 *    catalog ids, one Subsonic server); otherwise every song is matched on the target (ISRC first, then
 *    title + artist + duration through [TrackMatcher]) and every album from its matched songs (the target
 *    album most of them are on), else by an album search on title + artist. Unsure → the plan's review lists.
 *  - Notes get [MigrationIds] ids, so a rerun finds its own copies and never touches the original.
 *  - Conflicts in the target: notes are all kept; scores and titles: the later edit wins; reviews: both kept,
 *    the source's under "— 来自 <source account>"; album rows are written with ratingNeedsSync and
 *    reviewNeedsSync false (nothing is pushed to NeoDB); track scores aren't pushed to the target service.
 *  - Written into the domain like any edit, so Cloud sync publishes them as the target's own changes.
 *  - A [TransferLedger] per (source, target) remembers copied items (skipped next time), matches and picks
 *    (reused), and unmatched items (retried).
 *
 * Matching (catalog search through [MemoryCatalog], on the app's Track / Album models) is this class's;
 * what is copied where and every conflict rule are :shared's ([MemoryTransferApplier], [TransferRules]).
 */
class MemoryTransfer(
    private val db: YoinDatabase,
    private val ledger: TransferLedger,
    private val matcher: TrackMatcher = TrackMatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // ------------------------------------------------------------------ plan

    suspend fun plan(
        source: TransferAccount,
        items: MemoryItems,
        target: TransferAccount,
        catalog: MemoryCatalog?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        /** The ledger's entries, when the caller read them already (under the sync lock); else [ledger] is read. */
        previous: Map<String, String>? = null,
    ): TransferPlan {
        val targetProfile = requireNotNull(target.localProfileId) { "Memories are copied into a local profile" }
        val done = previous ?: ledger.read(source.scope, targetProfile)
        fun pending(key: String) = done[key]?.startsWith(TransferLedger.COPIED) != true
        val pendingItems = pendingItems(items, ::pending)

        val (needTracks, needAlbums) = TransferRules.needsMatching(source, target, pendingItems)
        val total = needTracks.size + needAlbums.size
        var progress = 0

        val described = HashMap<SourceRef, Track?>()
        val tracks = matchTracks(needTracks, items, done, catalog, described) {
            onProgress(++progress, total)
        }
        val albums = needAlbums.map { ref ->
            matchAlbum(ref, items, done, tracks, described, catalog).also { onProgress(++progress, total) }
        }
        val draft = TransferPlan(
            source = source,
            target = target,
            items = items,
            tracks = tracks.values.toList(),
            albums = albums,
            previouslyCopied = countItems(items) - countItems(pendingItems),
            toCopy = TransferCounts(),
            ledger = done,
        )
        return draft.copy(toCopy = mappableCounts(draft, pendingItems))
    }

    private suspend fun matchTracks(
        refs: List<SourceRef>,
        items: MemoryItems,
        done: Map<String, String>,
        catalog: MemoryCatalog?,
        described: MutableMap<SourceRef, Track?>,
        onEach: () -> Unit,
    ): LinkedHashMap<SourceRef, TrackMatch> {
        val out = LinkedHashMap<SourceRef, TrackMatch>()
        val sources = LinkedHashMap<SourceRef, Track?>()
        for (ref in refs) sources[ref] = sourceTrack(ref, items, catalog, described)
        val isrcs = sources.values.mapNotNull { it?.extras?.get(Track.EXTRA_ISRC) }.distinct()
        val byIsrc = if (catalog == null || isrcs.isEmpty()) emptyMap() else safely(emptyMap()) { catalog.tracksByIsrc(isrcs) }
        val searches = HashMap<String, List<Track>>()
        suspend fun search(query: String): List<Track> =
            searches.getOrPut(query) { if (catalog == null) emptyList() else safely(emptyList()) { catalog.searchTracks(query) } }

        for ((ref, track) in sources) {
            val earlier = done[trackKey(ref)]
            val reused = reusedTrack(earlier, track)
            out[ref] = when {
                reused != null -> TrackMatch(
                    ref,
                    track,
                    if (earlier!!.startsWith(TransferLedger.CHOSEN)) MatchStatus.Picked else MatchStatus.Matched,
                    listOf(reused),
                    reused,
                )
                track == null || track.title.isNullOrBlank() || catalog == null ->
                    TrackMatch(ref, track, MatchStatus.Unmatched, emptyList(), null)
                else -> {
                    val isrcDecision = track.extras[Track.EXTRA_ISRC]?.let(byIsrc::get)?.let { matcher.chooseByIsrc(track, it) }
                    val searched = isrcDecision?.takeIf { it is MatchDecision.Matched } ?: run {
                        val query = matcher.searchQuery(track)
                        val first = query?.let { matcher.choose(track, search(it)) } ?: MatchDecision.NotFound
                        val titleOnly = matcher.titleQuery(track)
                        if (first == MatchDecision.NotFound && titleOnly != null && titleOnly != query) {
                            matcher.choose(track, search(titleOnly))
                        } else {
                            first
                        }
                    }
                    // Searching didn't settle it and nothing local named an ISRC: ask the source service for one
                    // (only now, so a large library doesn't cost one lookup per song).
                    val decision = if (searched !is MatchDecision.Matched && track.extras[Track.EXTRA_ISRC] == null) {
                        val isrc = described.getOrPut(ref) { safely(null) { catalog.describeSourceTrack(ref.provider, ref.id) } }
                            ?.extras?.get(Track.EXTRA_ISRC)
                        val byCode = isrc?.let { code -> safely(emptyMap()) { catalog.tracksByIsrc(listOf(code)) }[code] }
                        byCode?.let { matcher.chooseByIsrc(track, it) }?.takeIf { it is MatchDecision.Matched } ?: searched
                    } else {
                        searched
                    }
                    when (decision) {
                        is MatchDecision.Matched -> TrackMatch(ref, track, MatchStatus.Matched, listOf(decision.track), decision.track)
                        is MatchDecision.Ambiguous ->
                            if (decision.candidates.isEmpty()) {
                                TrackMatch(ref, track, MatchStatus.Unmatched, emptyList(), null)
                            } else {
                                TrackMatch(ref, track, MatchStatus.Uncertain, decision.candidates.take(MAX_CANDIDATES), null)
                            }
                        MatchDecision.NotFound -> TrackMatch(ref, track, MatchStatus.Unmatched, emptyList(), null)
                    }
                }
            }
            onEach()
        }
        return out
    }

    /** What is known about a source song: local facts, a note's title, else the catalog's description. */
    private suspend fun sourceTrack(
        ref: SourceRef,
        items: MemoryItems,
        catalog: MemoryCatalog?,
        described: MutableMap<SourceRef, Track?>,
    ): Track? {
        val facts = items.trackFacts[ref]
        val note = items.songNotes.firstOrNull { it.track == ref && it.title.isNotBlank() }
        val title = facts?.title ?: note?.title
        val artist = facts?.artist?.takeIf(String::isNotBlank) ?: note?.artist?.takeIf(String::isNotBlank)
        val id = runCatching { MediaId(ref.provider, ref.id) }.getOrNull() ?: return null
        if (title != null) {
            return Track(
                id = id,
                title = title,
                artist = artist,
                artistId = null,
                album = facts?.album,
                albumId = (facts?.albumId ?: linkedAlbum(ref, items))?.let { runCatching { MediaId(ref.provider, it) }.getOrNull() },
                coverArt = null,
                durationSec = facts?.durationSec,
                trackNumber = null,
                year = null,
                genre = null,
                userRating = null,
            )
        }
        if (catalog == null) return null
        return described.getOrPut(ref) { safely(null) { catalog.describeSourceTrack(ref.provider, ref.id) } }
    }

    private fun reusedTrack(earlier: String?, source: Track?): Track? {
        val id = earlier?.let {
            when {
                it.startsWith(TransferLedger.CHOSEN) -> it.removePrefix(TransferLedger.CHOSEN)
                it.startsWith(TransferLedger.MATCHED) -> it.removePrefix(TransferLedger.MATCHED)
                else -> null
            }
        }?.let(MediaId::parseOrNull) ?: return null
        return Track(id, source?.title, source?.artist, null, null, null, null, source?.durationSec, null, null, null, null)
    }

    private suspend fun matchAlbum(
        ref: SourceRef,
        items: MemoryItems,
        done: Map<String, String>,
        tracks: Map<SourceRef, TrackMatch>,
        described: Map<SourceRef, Track?>,
        catalog: MemoryCatalog?,
    ): AlbumMatch {
        val known = items.albumFacts[ref]
        val earlier = done[albumKey(ref)]
        val earlierId = earlier?.substringAfter('|', "")?.let(MediaId::parseOrNull)
        val sourceAlbum = known?.let { (name, artist) -> album(ref, name, artist) }
        if (earlierId != null) {
            val status = if (earlier.startsWith(TransferLedger.CHOSEN)) MatchStatus.Picked else MatchStatus.Matched
            val reused = Album(earlierId, known?.name ?: "", known?.artist, null, null, null, null, null, null)
            return AlbumMatch(ref, sourceAlbum, status, listOf(reused), reused)
        }
        // 1. The target album most of this album's matched songs are on.
        val votes = LinkedHashMap<MediaId, MutableList<Track>>()
        for ((trackRef, match) in tracks) {
            val chosen = match.chosen ?: continue
            if (match.status != MatchStatus.Matched && match.status != MatchStatus.Picked) continue
            val sourceAlbumId = linkedAlbum(trackRef, items)
                ?: items.trackFacts[trackRef]?.albumId
                ?: described[trackRef]?.albumId?.rawId
                ?: match.sourceTrack?.albumId?.rawId
            if (sourceAlbumId != ref.id || trackRef.provider != ref.provider) continue
            val targetAlbum = chosen.albumId ?: continue
            votes.getOrPut(targetAlbum) { ArrayList() } += chosen
        }
        if (votes.isNotEmpty()) {
            val ranked = votes.entries.sortedByDescending { it.value.size }
            val top = ranked.first().value.size
            val leaders = ranked.takeWhile { it.value.size == top }
            val candidates = leaders.take(MAX_CANDIDATES).map { (id, tracks) ->
                Album(id, tracks.first().album.orEmpty(), tracks.first().artist, null, null, null, null, null, null)
            }
            return if (leaders.size == 1) {
                AlbumMatch(ref, sourceAlbum, MatchStatus.Matched, candidates, candidates.first(), AlbumMatchMethod.Tracks)
            } else {
                AlbumMatch(ref, sourceAlbum, MatchStatus.Uncertain, candidates, null, AlbumMatchMethod.Tracks)
            }
        }
        // 2. Album search by title + artist.
        if (catalog == null) return AlbumMatch(ref, sourceAlbum, MatchStatus.Unmatched, emptyList(), null)
        val info = sourceAlbum ?: safely(null) { catalog.describeSourceAlbum(ref.provider, ref.id) }
        val name = info?.name?.takeIf(String::isNotBlank)
            ?: return AlbumMatch(ref, info, MatchStatus.Unmatched, emptyList(), null)
        val query = listOfNotNull(TrackMatcher.stripDecorations(name).ifBlank { name }, info.artist?.let { TrackMatcher.splitArtists(it).firstOrNull() })
            .joinToString(" ")
        val scored = safely(emptyList()) { catalog.searchAlbums(query) }
            .map { it to albumScore(name, info.artist, it) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return AlbumMatch(ref, info, MatchStatus.Unmatched, emptyList(), null, AlbumMatchMethod.Search)
        val sameVersion = TrackMatcher.versionTags(name) == TrackMatcher.versionTags(best.first.name)
        return when {
            best.second >= TrackMatcher.MATCH_SCORE && sameVersion ->
                AlbumMatch(ref, info, MatchStatus.Matched, listOf(best.first), best.first, AlbumMatchMethod.Search)
            best.second >= TrackMatcher.AMBIGUOUS_SCORE -> AlbumMatch(
                ref,
                info,
                MatchStatus.Uncertain,
                scored.takeWhile { it.second >= TrackMatcher.AMBIGUOUS_SCORE }.map { it.first }.take(MAX_CANDIDATES),
                null,
                AlbumMatchMethod.Search,
            )
            else -> AlbumMatch(ref, info, MatchStatus.Unmatched, emptyList(), null, AlbumMatchMethod.Search)
        }
    }

    private fun albumScore(name: String, artist: String?, candidate: Album): Double {
        val title = matcher.titleSimilarity(name, candidate.name)
        if (title < 0.6) return 0.0
        val credit = if (artist.isNullOrBlank() || candidate.artist.isNullOrBlank()) {
            0.6
        } else {
            matcher.artistSimilarity(artist, candidate.artist)
        }
        if (credit < 0.5) return 0.0
        return 0.6 * title + 0.4 * credit
    }

    // ------------------------------------------------------------------ execute

    suspend fun execute(plan: TransferPlan): TransferResult {
        val profileId = requireNotNull(plan.target.localProfileId)
        val tracks = plan.tracks.map { match ->
            TrackPick(
                match.source,
                match.status,
                match.chosen?.let { track ->
                    TrackTarget(track.id.provider, track.id.rawId, track.title, track.artist, track.albumId?.provider, track.albumId?.rawId)
                },
            )
        }
        val albums = plan.albums.map { match ->
            AlbumPick(match.source, match.status, match.chosen?.let { AlbumTarget(it.id.provider, it.id.rawId, it.name, it.artist) })
        }
        val outcome = db.withTransaction {
            MemoryTransferApplier(RoomTransferStore(db)).apply(plan.source, plan.target, plan.items, tracks, albums, plan.ledger, clock)
        }
        ledger.write(plan.source.scope, profileId, outcome.ledgerEntries)
        return outcome.result
    }

    // ------------------------------------------------------------------ helpers

    private fun mappableCounts(plan: TransferPlan, items: MemoryItems): TransferCounts {
        val tracks = plan.tracks.associateBy { it.source }
        val albums = plan.albums.associateBy { it.source }
        return TransferRules.mappableCounts(
            plan.source,
            plan.target,
            items,
            hasTrack = { tracks[it]?.chosen != null },
            hasAlbum = { albums[it]?.chosen != null },
        )
    }

    private suspend fun <T> safely(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback
    }

    companion object {
        const val MAX_CANDIDATES = TransferRules.MAX_CANDIDATES

        /** The divider a joined review gets above the source's text ([TransferRules.joinReviews]). */
        fun joinReviews(target: String, source: String, sourceAccount: String): String =
            TransferRules.joinReviews(target, source, sourceAccount)

        /** Whether [ref]'s id means the same song/album on [target] ([TransferRules.compatible]). */
        fun compatible(source: TransferAccount, target: TransferAccount, ref: SourceRef): Boolean =
            TransferRules.compatible(source, target, ref)

        /** Apple Music library ids ("library:…", or a bare "i." / "l." resource id) are per listener. */
        fun isAppleLibraryId(id: String): Boolean = TransferRules.isAppleLibraryId(id)

        /** [TransferRules.remapMentions] with the app's [MediaId]s. */
        internal fun remapMentionsIn(
            text: String,
            trackTarget: (SourceRef) -> MediaId?,
            albumTarget: (SourceRef) -> MediaId?,
        ): String = TransferRules.remapMentions(
            text,
            { ref -> trackTarget(ref)?.let { TargetRef(it.provider, it.rawId) } },
            { ref -> albumTarget(ref)?.let { TargetRef(it.provider, it.rawId) } },
        )

        internal fun trackKey(ref: SourceRef) = TransferRules.trackKey(ref)

        internal fun albumKey(ref: SourceRef) = TransferRules.albumKey(ref)

        private fun linkedAlbum(ref: SourceRef, items: MemoryItems): String? = TransferRules.linkedAlbum(ref, items)

        private fun album(ref: SourceRef, name: String, artist: String): Album? =
            runCatching { Album(MediaId(ref.provider, ref.id), name, artist, null, null, null, null, null, null) }.getOrNull()

        /** [items] without what the ledger says is already copied. */
        internal fun pendingItems(items: MemoryItems, pending: (String) -> Boolean): MemoryItems =
            TransferRules.pendingItems(items, pending)

        internal fun countItems(items: MemoryItems): Int = TransferRules.countItems(items)
    }
}
