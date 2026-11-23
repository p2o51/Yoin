package com.gpo.yoin.data.transfer.memories

import androidx.room.withTransaction
import com.gpo.yoin.data.local.AlbumMemoryTitle
import com.gpo.yoin.data.local.AlbumNote
import com.gpo.yoin.data.local.LocalRating
import com.gpo.yoin.data.local.MemoryCopyCache
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.local.TrackAlbumLink
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.adapters.TrackRatingSyncAdapter
import com.gpo.yoin.data.sync.adapters.newAlbumRow
import com.gpo.yoin.data.transfer.MatchDecision
import com.gpo.yoin.data.transfer.TrackMatcher
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

        val trackRefs = LinkedHashSet<SourceRef>()
        pendingItems.songNotes.forEach { trackRefs += it.track }
        pendingItems.trackRatings.forEach { trackRefs += it.track }
        pendingItems.trackAlbumLinks.forEach { trackRefs += it.track }
        val needTracks = trackRefs.filterNot { compatible(source, target, it) }
        val linkAlbums = pendingItems.trackAlbumLinks.map { SourceRef(it.track.provider, it.albumId) }
        val needAlbums = (pendingItems.albums + linkAlbums).distinct().filterNot { compatible(source, target, it) }
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
            val reused = Album(earlierId, known?.first ?: "", known?.second, null, null, null, null, null, null)
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
        val source = plan.source
        val target = plan.target
        val done = plan.ledger
        fun pending(key: String) = done[key]?.startsWith(TransferLedger.COPIED) != true
        val items = pendingItems(plan.items, ::pending)
        val trackMatches = plan.tracks.associateBy { it.source }
        val albumMatches = plan.albums.associateBy { it.source }

        fun trackTarget(ref: SourceRef): MediaId? =
            if (compatible(source, target, ref)) {
                runCatching { MediaId(ref.provider, ref.id) }.getOrNull()
            } else {
                trackMatches[ref]?.takeIf { it.status == MatchStatus.Matched || it.status == MatchStatus.Picked }?.chosen?.id
            }
        fun albumTarget(ref: SourceRef): MediaId? =
            if (compatible(source, target, ref)) {
                runCatching { MediaId(ref.provider, ref.id) }.getOrNull()
            } else {
                albumMatches[ref]?.takeIf { it.status == MatchStatus.Matched || it.status == MatchStatus.Picked }?.chosen?.id
            }

        val copied = Counter()
        val updated = Counter()
        val kept = Counter()
        val unmatched = Counter()
        var joined = 0
        val entries = LinkedHashMap<String, String>()
        val dao = db.syncDomainDao()
        val titles = db.albumMemoryTitleDao()

        db.withTransaction {
            for (note in items.songNotes) {
                val ledgerKey = "note:${note.id}"
                val track = trackTarget(note.track)
                if (track == null) {
                    unmatched.songNotes++
                    entries[ledgerKey] = TransferLedger.UNMATCHED
                    continue
                }
                val id = MigrationIds.derive(source.scope, note.id, target.scope)
                val matched = trackMatches[note.track]?.chosen
                val row = SongNote(
                    id = id,
                    profileId = profileId,
                    trackId = track.rawId,
                    provider = track.provider,
                    content = note.content,
                    createdAt = note.createdAt,
                    updatedAt = note.updatedAt,
                    title = matched?.title?.takeIf(String::isNotBlank) ?: note.title,
                    artist = matched?.artist?.takeIf(String::isNotBlank) ?: note.artist,
                    positionMs = note.positionMs,
                )
                val existing = dao.noteById(id)
                when {
                    existing == null -> {
                        dao.writeNote(row)
                        copied.songNotes++
                    }
                    existing.profileId != profileId || existing.updatedAt >= note.updatedAt -> kept.songNotes++
                    else -> {
                        dao.writeNote(row.copy(createdAt = existing.createdAt))
                        updated.songNotes++
                    }
                }
                entries[ledgerKey] = TransferLedger.COPIED + id
            }

            for (rating in items.trackRatings) {
                val ledgerKey = "track_rating:${rating.track}"
                val track = trackTarget(rating.track)
                if (track == null) {
                    unmatched.trackRatings++
                    entries[ledgerKey] = TransferLedger.UNMATCHED
                    continue
                }
                val existing = dao.trackRating(profileId, track.rawId, track.provider)
                val row = LocalRating(
                    profileId = profileId,
                    songId = track.rawId,
                    provider = track.provider,
                    rating = rating.rating,
                    serverRating = TrackRatingSyncAdapter.serverRatingFor(rating.rating),
                    needsSync = false,
                    updatedAt = rating.updatedAt,
                )
                when {
                    existing == null -> {
                        dao.upsertTrackRating(row)
                        copied.trackRatings++
                    }
                    existing.rating == rating.rating -> kept.trackRatings++
                    existing.rating == null || rating.updatedAt > existing.updatedAt -> {
                        dao.upsertTrackRating(row)
                        updated.trackRatings++
                    }
                    else -> kept.trackRatings++
                }
                entries[ledgerKey] = TransferLedger.COPIED + SyncFormat.compositeKey(track.provider, track.rawId)
            }

            for (rating in items.albumRatings) {
                val ledgerKey = "album_rating:${rating.album}"
                val album = albumTarget(rating.album)
                if (album == null) {
                    unmatched.albumRatings++
                    entries[ledgerKey] = TransferLedger.UNMATCHED
                    continue
                }
                val existing = dao.albumRating(profileId, album.rawId, album.provider)
                when {
                    existing == null -> {
                        dao.insertAlbumRatingIfAbsent(
                            newAlbumRow(profileId, album.rawId, album.provider, rating = rating.rating, updatedAt = rating.updatedAt),
                        )
                        copied.albumRatings++
                    }
                    existing.rating == rating.rating -> kept.albumRatings++
                    existing.rating == 0f -> {
                        // The row exists for its review only: the score itself is new here.
                        dao.updateAlbumRatingValue(profileId, album.rawId, album.provider, rating.rating, maxOf(existing.updatedAt, rating.updatedAt))
                        copied.albumRatings++
                    }
                    rating.updatedAt > existing.updatedAt -> {
                        // Rating only: the NeoDB flags stay as they are (never set: nothing is pushed for a copy).
                        dao.updateAlbumRatingValue(
                            profileId,
                            album.rawId,
                            album.provider,
                            rating.rating,
                            maxOf(existing.updatedAt, rating.updatedAt),
                        )
                        updated.albumRatings++
                    }
                    else -> kept.albumRatings++
                }
                entries[ledgerKey] = TransferLedger.COPIED + SyncFormat.compositeKey(album.provider, album.rawId)
            }

            for (review in items.albumReviews) {
                val ledgerKey = "album_review:${review.album}"
                val album = albumTarget(review.album)
                if (album == null) {
                    unmatched.albumReviews++
                    entries[ledgerKey] = TransferLedger.UNMATCHED
                    continue
                }
                val existing = dao.albumRating(profileId, album.rawId, album.provider)
                val text = review.review.trim()
                val writtenAt = review.reviewUpdatedAt ?: review.updatedAt
                when {
                    existing == null -> {
                        dao.insertAlbumRatingIfAbsent(
                            newAlbumRow(profileId, album.rawId, album.provider, review = text, updatedAt = review.updatedAt)
                                .copy(reviewUpdatedAt = writtenAt),
                        )
                        copied.albumReviews++
                    }
                    existing.review.isNullOrBlank() -> {
                        // The row exists for its score only: the review itself is new here.
                        dao.updateAlbumReviewValue(
                            profileId,
                            album.rawId,
                            album.provider,
                            text,
                            writtenAt,
                            maxOf(existing.updatedAt, review.updatedAt),
                        )
                        copied.albumReviews++
                    }
                    existing.review.contains(text) -> kept.albumReviews++
                    else -> {
                        dao.updateAlbumReviewValue(
                            profileId,
                            album.rawId,
                            album.provider,
                            joinReviews(existing.review, text, source.displayName),
                            maxOf(existing.reviewUpdatedAt ?: existing.updatedAt, writtenAt),
                            maxOf(existing.updatedAt, review.updatedAt),
                        )
                        updated.albumReviews++
                        joined++
                    }
                }
                entries[ledgerKey] = TransferLedger.COPIED + SyncFormat.compositeKey(album.provider, album.rawId)
            }

            for (note in items.albumNotes) {
                val ledgerKey = "album_note:${note.id}"
                val album = albumTarget(note.album)
                if (album == null) {
                    unmatched.albumNotes++
                    entries[ledgerKey] = TransferLedger.UNMATCHED
                    continue
                }
                val id = MigrationIds.derive(source.scope, note.id, target.scope)
                val matched = albumMatches[note.album]?.chosen
                val row = AlbumNote(
                    id = id,
                    profileId = profileId,
                    albumId = album.rawId,
                    provider = album.provider,
                    content = note.content,
                    createdAt = note.createdAt,
                    updatedAt = note.updatedAt,
                    albumName = matched?.name?.takeIf(String::isNotBlank) ?: note.albumName,
                    artist = matched?.artist?.takeIf(String::isNotBlank) ?: note.artist,
                )
                val existing = dao.albumNoteById(id)
                when {
                    existing == null -> {
                        dao.writeAlbumNote(row)
                        copied.albumNotes++
                    }
                    existing.profileId != profileId || existing.updatedAt >= note.updatedAt -> kept.albumNotes++
                    else -> {
                        dao.writeAlbumNote(row.copy(createdAt = existing.createdAt))
                        updated.albumNotes++
                    }
                }
                entries[ledgerKey] = TransferLedger.COPIED + id
            }

            for (title in items.albumTitles) {
                val ledgerKey = "album_title:${title.album}"
                val album = albumTarget(title.album)
                if (album == null) {
                    unmatched.albumTitles++
                    entries[ledgerKey] = TransferLedger.UNMATCHED
                    continue
                }
                val existing = titles.get(profileId, album.provider, album.rawId)
                when {
                    existing == null -> {
                        titles.upsert(AlbumMemoryTitle(profileId, album.provider, album.rawId, title.title, title.updatedAt))
                        copied.albumTitles++
                    }
                    existing.title == title.title || existing.updatedAt >= title.updatedAt -> kept.albumTitles++
                    else -> {
                        titles.upsert(AlbumMemoryTitle(profileId, album.provider, album.rawId, title.title, title.updatedAt))
                        updated.albumTitles++
                    }
                }
                entries[ledgerKey] = TransferLedger.COPIED + SyncFormat.compositeKey(album.provider, album.rawId)
            }

            for (title in items.memoryTitles) {
                val ledgerKey = "memory_title:" + SyncFormat.compositeKey(title.provider, title.entityType, title.entityId)
                val entity = memoryEntityTarget(title.provider, title.entityType, title.entityId, source, target, ::albumTarget)
                if (entity == null) {
                    unmatched.memoryTitles++
                    entries[ledgerKey] = TransferLedger.UNMATCHED
                    continue
                }
                val existing = dao.memoryCopy(profileId, entity.provider, title.entityType, entity.rawId)
                when {
                    existing == null -> {
                        dao.upsertMemoryCopy(
                            MemoryCopyCache(profileId, entity.provider, title.entityType, entity.rawId, "", "", 0L, title.title, title.titlePromptHash),
                        )
                        copied.memoryTitles++
                    }
                    // The target's own AI title wins: it was made from the target's own Memories.
                    !existing.title.isNullOrBlank() -> kept.memoryTitles++
                    else -> {
                        dao.upsertMemoryCopy(existing.copy(title = title.title, titlePromptHash = title.titlePromptHash))
                        copied.memoryTitles++
                    }
                }
                entries[ledgerKey] = TransferLedger.COPIED + SyncFormat.compositeKey(entity.provider, title.entityType, entity.rawId)
            }

            for (copy in items.memoryCopies) {
                val ledgerKey = "memory_copy:" + SyncFormat.compositeKey(copy.provider, copy.entityType, copy.entityId)
                val entity = memoryEntityTarget(copy.provider, copy.entityType, copy.entityId, source, target, ::albumTarget)
                if (entity == null) {
                    unmatched.memoryCopies++
                    entries[ledgerKey] = TransferLedger.UNMATCHED
                    continue
                }
                val existing = dao.memoryCopy(profileId, entity.provider, copy.entityType, entity.rawId)
                when {
                    existing == null -> {
                        dao.upsertMemoryCopy(
                            MemoryCopyCache(profileId, entity.provider, copy.entityType, entity.rawId, copy.copy, copy.promptHash, copy.generatedAt),
                        )
                        copied.memoryCopies++
                    }
                    existing.copy.isBlank() -> {
                        dao.upsertMemoryCopy(existing.copy(copy = copy.copy, promptHash = copy.promptHash, generatedAt = copy.generatedAt))
                        copied.memoryCopies++
                    }
                    copy.generatedAt > existing.generatedAt -> {
                        dao.upsertMemoryCopy(existing.copy(copy = copy.copy, promptHash = copy.promptHash, generatedAt = copy.generatedAt))
                        updated.memoryCopies++
                    }
                    else -> kept.memoryCopies++
                }
                entries[ledgerKey] = TransferLedger.COPIED + SyncFormat.compositeKey(entity.provider, copy.entityType, entity.rawId)
            }

            // Track → album links: the source's (mapped), then what matching learned; a link already there stays.
            val linked = HashSet<MediaId>()
            for (link in items.trackAlbumLinks) {
                val ledgerKey = "link:${link.track}"
                val track = trackTarget(link.track)
                // The album side is mapped on its own: a catalog song can sit on a library-only album id.
                val album = trackMatches[link.track]?.chosen?.albumId
                    ?: albumTarget(SourceRef(link.track.provider, link.albumId))
                if (track == null || album == null || album.provider != track.provider) {
                    unmatched.trackAlbumLinks++
                    entries[ledgerKey] = TransferLedger.UNMATCHED
                    continue
                }
                if (writeLink(profileId, track, album.rawId, if (track.rawId == link.track.id) link.source else TrackAlbumLink.SOURCE_IMPORT, link.updatedAt)) {
                    copied.trackAlbumLinks++
                } else {
                    kept.trackAlbumLinks++
                }
                linked += track
                entries[ledgerKey] = TransferLedger.COPIED + SyncFormat.compositeKey(track.provider, track.rawId)
            }
            for (match in plan.tracks) {
                val chosen = match.chosen ?: continue
                if (match.status != MatchStatus.Matched && match.status != MatchStatus.Picked) continue
                val album = chosen.albumId ?: continue
                if (chosen.id in linked || album.provider != chosen.id.provider) continue
                if (writeLink(profileId, chosen.id, album.rawId, TrackAlbumLink.SOURCE_IMPORT, clock())) {
                    copied.trackAlbumLinks++
                }
            }
        }

        for (match in plan.tracks) entries[trackKey(match.source)] = ledgerValue(match.status, match.chosen?.id)
        for (match in plan.albums) entries[albumKey(match.source)] = ledgerValue(match.status, match.chosen?.id)
        ledger.write(source.scope, profileId, entries)

        return TransferResult(
            copied = copied.counts(),
            updated = updated.counts(),
            kept = kept.counts(),
            reviewsJoined = joined,
            unmatched = unmatched.counts(),
            skippedPreviously = countItems(plan.items) - countItems(items),
        )
    }

    private suspend fun writeLink(profileId: String, track: MediaId, albumId: String, source: String, updatedAt: Long): Boolean {
        val dao = db.syncDomainDao()
        if (dao.trackAlbumLink(profileId, track.provider, track.rawId) != null) return false
        dao.upsertTrackAlbumLink(TrackAlbumLink(profileId, track.provider, track.rawId, albumId, source, updatedAt))
        return true
    }

    private fun ledgerValue(status: MatchStatus, chosen: MediaId?): String = when {
        chosen != null && status == MatchStatus.Picked -> TransferLedger.CHOSEN + chosen
        chosen != null && status == MatchStatus.Matched -> TransferLedger.MATCHED + chosen
        else -> TransferLedger.UNMATCHED
    }

    private inline fun memoryEntityTarget(
        provider: String,
        entityType: String,
        entityId: String,
        source: TransferAccount,
        target: TransferAccount,
        albumTarget: (SourceRef) -> MediaId?,
    ): MediaId? {
        val ref = SourceRef(provider, entityId)
        return when {
            entityType == MemoryCopyCache.ENTITY_ALBUM -> albumTarget(ref)
            compatible(source, target, ref) -> runCatching { MediaId(provider, entityId) }.getOrNull()
            else -> null
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun mappableCounts(plan: TransferPlan, items: MemoryItems): TransferCounts {
        val tracks = plan.tracks.associateBy { it.source }
        val albums = plan.albums.associateBy { it.source }
        fun track(ref: SourceRef) = compatible(plan.source, plan.target, ref) || tracks[ref]?.chosen != null
        fun album(ref: SourceRef) = compatible(plan.source, plan.target, ref) || albums[ref]?.chosen != null
        return TransferCounts(
            songNotes = items.songNotes.count { track(it.track) },
            trackRatings = items.trackRatings.count { track(it.track) },
            albumRatings = items.albumRatings.count { album(it.album) },
            albumReviews = items.albumReviews.count { album(it.album) },
            albumNotes = items.albumNotes.count { album(it.album) },
            albumTitles = items.albumTitles.count { album(it.album) },
            memoryTitles = items.memoryTitles.count { album(SourceRef(it.provider, it.entityId)) },
            memoryCopies = items.memoryCopies.count { album(SourceRef(it.provider, it.entityId)) },
            trackAlbumLinks = items.trackAlbumLinks.count { track(it.track) },
        )
    }

    private class Counter {
        var songNotes = 0
        var trackRatings = 0
        var albumRatings = 0
        var albumReviews = 0
        var albumNotes = 0
        var albumTitles = 0
        var memoryTitles = 0
        var memoryCopies = 0
        var trackAlbumLinks = 0

        fun counts() = TransferCounts(
            songNotes,
            trackRatings,
            albumRatings,
            albumReviews,
            albumNotes,
            albumTitles,
            memoryTitles,
            memoryCopies,
            trackAlbumLinks,
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
        const val MAX_CANDIDATES = 3

        /** The divider a joined review gets above the source's text. */
        fun joinReviews(target: String, source: String, sourceAccount: String): String =
            target.trimEnd() + "\n\n— 来自 " + sourceAccount + "\n" + source.trim()

        /**
         * Whether [ref]'s id means the same song/album on [target]: same provider, and Spotify, an Apple Music
         * catalog id (library ids "library:…" are per listener), or one Subsonic server.
         */
        fun compatible(source: TransferAccount, target: TransferAccount, ref: SourceRef): Boolean {
            if (ref.provider != target.provider) return false
            return when (ref.provider) {
                MediaId.PROVIDER_SPOTIFY -> true
                MediaId.PROVIDER_APPLE_MUSIC -> !isAppleLibraryId(ref.id)
                MediaId.PROVIDER_SUBSONIC ->
                    source.provider == target.provider && source.serverKey != null &&
                        source.serverKey.equals(target.serverKey, ignoreCase = true)
                else -> source.scope == target.scope
            }
        }

        /** Apple Music library ids ("library:…", or a bare "i." / "l." resource id) are per listener. */
        fun isAppleLibraryId(id: String): Boolean =
            id.startsWith("library:") || id.startsWith("i.") || id.startsWith("l.")

        internal fun trackKey(ref: SourceRef) = "track:$ref"

        internal fun albumKey(ref: SourceRef) = "album:$ref"

        private fun linkedAlbum(ref: SourceRef, items: MemoryItems): String? =
            items.trackAlbumLinks.firstOrNull { it.track == ref }?.albumId

        private fun album(ref: SourceRef, name: String, artist: String): Album? =
            runCatching { Album(MediaId(ref.provider, ref.id), name, artist, null, null, null, null, null, null) }.getOrNull()

        /** [items] without what the ledger says is already copied. */
        internal fun pendingItems(items: MemoryItems, pending: (String) -> Boolean): MemoryItems = items.copy(
            songNotes = items.songNotes.filter { pending("note:${it.id}") },
            trackRatings = items.trackRatings.filter { pending("track_rating:${it.track}") },
            albumRatings = items.albumRatings.filter { pending("album_rating:${it.album}") },
            albumReviews = items.albumReviews.filter { pending("album_review:${it.album}") },
            albumNotes = items.albumNotes.filter { pending("album_note:${it.id}") },
            albumTitles = items.albumTitles.filter { pending("album_title:${it.album}") },
            memoryTitles = items.memoryTitles.filter {
                pending("memory_title:" + SyncFormat.compositeKey(it.provider, it.entityType, it.entityId))
            },
            memoryCopies = items.memoryCopies.filter {
                pending("memory_copy:" + SyncFormat.compositeKey(it.provider, it.entityType, it.entityId))
            },
            trackAlbumLinks = items.trackAlbumLinks.filter { pending("link:${it.track}") },
        )

        internal fun countItems(items: MemoryItems): Int =
            items.songNotes.size + items.trackRatings.size + items.albumRatings.size + items.albumReviews.size +
                items.albumNotes.size + items.albumTitles.size + items.memoryTitles.size + items.memoryCopies.size +
                items.trackAlbumLinks.size
    }
}
