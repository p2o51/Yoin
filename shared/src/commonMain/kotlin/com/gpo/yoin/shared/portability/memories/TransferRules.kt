package com.gpo.yoin.shared.portability.memories

import com.gpo.yoin.shared.notes.MentionKind
import com.gpo.yoin.shared.notes.NoteMarkup
import com.gpo.yoin.shared.sync.CanonicalJson
import com.gpo.yoin.shared.sync.SyncFormat

/**
 * Ids for copied notes (song_notes / album_notes ids are global primary keys, so a copy can never reuse the
 * original's): `mig-` + the first 16 hex of sha256(srcScope ␟ srcId ␟ dstScope), ␟ = U+001F. Deterministic,
 * so copying the same note into the same account again finds the earlier copy instead of duplicating it,
 * on any device; never equal to the original id.
 */
object MigrationIds {
    const val PREFIX = "mig-"

    fun derive(srcScope: String, srcId: String, dstScope: String): String =
        PREFIX + CanonicalJson.sha256Hex(SyncFormat.compositeKey(srcScope, srcId, dstScope)).take(16)
}

/**
 * What earlier runs of a transfer did, per (source scope, target profile): source item key -> result.
 * Values: `copied|<target key>`, `unmatched`, `matched|<target media id>`, `chosen|<target media id>`
 * (songs/albums the user picked). Reruns skip copied items, reuse earlier matches and picks, retry unmatched.
 */
interface TransferLedger {
    suspend fun read(srcScope: String, dstProfileId: String): Map<String, String>

    suspend fun write(srcScope: String, dstProfileId: String, entries: Map<String, String>)

    companion object {
        const val COPIED = "copied|"
        const val UNMATCHED = "unmatched"
        const val MATCHED = "matched|"
        const val CHOSEN = "chosen|"

        /** Android keeps the ledger in sync_meta, one row per item. */
        const val META_PREFIX = "transfer:"

        /**
         * The sync_meta key prefix of one (source scope, target profile) ledger:
         * `transfer:<16 hex of sha256(srcScope ␟ dstProfileId)>:`; an item's row is this + its item key.
         */
        fun metaPrefix(srcScope: String, dstProfileId: String): String =
            META_PREFIX + CanonicalJson.sha256Hex(SyncFormat.compositeKey(srcScope, dstProfileId)).take(16) + ":"
    }
}

/** For tests, previews and a plan that must not touch the stored ledger. */
class InMemoryTransferLedger : TransferLedger {
    private val entries = HashMap<Pair<String, String>, MutableMap<String, String>>()

    override suspend fun read(srcScope: String, dstProfileId: String): Map<String, String> =
        entries[srcScope to dstProfileId].orEmpty().toMap()

    override suspend fun write(srcScope: String, dstProfileId: String, entries: Map<String, String>) {
        this.entries.getOrPut(srcScope to dstProfileId) { HashMap() }.putAll(entries)
    }
}

/**
 * The pure rules of a Memories transfer (docs/cloud-sync.md「转移 Memories」): which ids carry over as they
 * are, ledger keys, how a review joins, how mentions follow their song / album.
 */
object TransferRules {
    /** memory_copy_cache.entityType of an album Memory. */
    const val ENTITY_ALBUM = "album"

    const val PROVIDER_SUBSONIC = "subsonic"
    const val PROVIDER_SPOTIFY = "spotify"
    const val PROVIDER_APPLE_MUSIC = "applemusic"

    /** Candidates kept for an uncertain match. */
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
            PROVIDER_SPOTIFY -> true
            PROVIDER_APPLE_MUSIC -> !isAppleLibraryId(ref.id)
            PROVIDER_SUBSONIC ->
                source.provider == target.provider && source.serverKey != null &&
                    source.serverKey.equals(target.serverKey, ignoreCase = true)
            else -> source.scope == target.scope
        }
    }

    /** Apple Music library ids ("library:…", or a bare "i." / "l." resource id) are per listener. */
    fun isAppleLibraryId(id: String): Boolean = id.startsWith("library:") || id.startsWith("i.") || id.startsWith("l.")

    /** A valid media id (non-empty provider without ':', non-empty id), else null — what MediaId accepts. */
    fun targetOf(provider: String, id: String): TargetRef? =
        if (provider.isEmpty() || provider.contains(':') || id.isEmpty()) null else TargetRef(provider, id)

    /** "provider:id" back to a [TargetRef] (a ledger's `matched|` / `chosen|` value), or null. */
    fun parseTarget(value: String?): TargetRef? {
        if (value == null) return null
        val sep = value.indexOf(':')
        if (sep <= 0 || sep >= value.length - 1) return null
        return targetOf(value.substring(0, sep), value.substring(sep + 1))
    }

    /** A stored id that may carry its provider prefix ("subsonic:123") as the raw id ("123"). */
    fun storedRawId(provider: String, stored: String): String = stored.removePrefix("$provider:")

    /**
     * [text] with each track / album mention whose item this transfer maps ([trackTarget] / [albumTarget])
     * pointing at the target's id; names, other mentions (artists, unmapped items) and every other
     * character as written.
     */
    fun remapMentions(
        text: String,
        trackTarget: (SourceRef) -> TargetRef?,
        albumTarget: (SourceRef) -> TargetRef?,
    ): String {
        if (!NoteMarkup.hasMentions(text)) return text
        return NoteMarkup.rewriteMentions(text) { mention ->
            val ref = SourceRef(mention.provider, mention.id)
            val to = when (mention.kind) {
                MentionKind.Track -> trackTarget(ref)
                MentionKind.Album -> albumTarget(ref)
                MentionKind.Artist -> null
            } ?: return@rewriteMentions null
            if (to.provider == mention.provider && to.id == mention.id) {
                null
            } else {
                mention.copy(provider = to.provider, id = to.id)
            }
        }
    }

    fun trackKey(ref: SourceRef): String = "track:$ref"

    fun albumKey(ref: SourceRef): String = "album:$ref"

    fun songNoteKey(note: SourceNote): String = "note:${note.id}"

    fun trackRatingKey(rating: SourceTrackRating): String = "track_rating:${rating.track}"

    fun albumRatingKey(rating: SourceAlbumRating): String = "album_rating:${rating.album}"

    fun albumReviewKey(review: SourceAlbumReview): String = "album_review:${review.album}"

    fun albumNoteKey(note: SourceAlbumNote): String = "album_note:${note.id}"

    fun albumTitleKey(title: SourceAlbumTitle): String = "album_title:${title.album}"

    fun memoryTitleKey(title: SourceMemoryTitle): String =
        "memory_title:" + SyncFormat.compositeKey(title.provider, title.entityType, title.entityId)

    fun memoryCopyKey(copy: SourceMemoryCopy): String =
        "memory_copy:" + SyncFormat.compositeKey(copy.provider, copy.entityType, copy.entityId)

    fun linkKey(link: SourceTrackLink): String = "link:${link.track}"

    /** A ledger value for a song / album match: `chosen|id` (picked), `matched|id`, else `unmatched`. */
    fun ledgerValue(status: MatchStatus, chosen: TargetRef?): String = when {
        chosen != null && status == MatchStatus.Picked -> TransferLedger.CHOSEN + chosen
        chosen != null && status == MatchStatus.Matched -> TransferLedger.MATCHED + chosen
        else -> TransferLedger.UNMATCHED
    }

    /** [items] without what the ledger says is already copied ([pending] = false for a copied item key). */
    fun pendingItems(items: MemoryItems, pending: (String) -> Boolean): MemoryItems = items.copy(
        songNotes = items.songNotes.filter { pending(songNoteKey(it)) },
        trackRatings = items.trackRatings.filter { pending(trackRatingKey(it)) },
        albumRatings = items.albumRatings.filter { pending(albumRatingKey(it)) },
        albumReviews = items.albumReviews.filter { pending(albumReviewKey(it)) },
        albumNotes = items.albumNotes.filter { pending(albumNoteKey(it)) },
        albumTitles = items.albumTitles.filter { pending(albumTitleKey(it)) },
        memoryTitles = items.memoryTitles.filter { pending(memoryTitleKey(it)) },
        memoryCopies = items.memoryCopies.filter { pending(memoryCopyKey(it)) },
        trackAlbumLinks = items.trackAlbumLinks.filter { pending(linkKey(it)) },
    )

    /** [items] without what [ledger] marks copied. */
    fun pendingItems(items: MemoryItems, ledger: Map<String, String>): MemoryItems =
        pendingItems(items) { key -> ledger[key]?.startsWith(TransferLedger.COPIED) != true }

    fun countItems(items: MemoryItems): Int =
        items.songNotes.size + items.trackRatings.size + items.albumRatings.size + items.albumReviews.size +
            items.albumNotes.size + items.albumTitles.size + items.memoryTitles.size + items.memoryCopies.size +
            items.trackAlbumLinks.size

    /**
     * What a run would copy: items whose song / album is in one id space ([compatible]) or has a chosen
     * counterpart ([hasTrack] / [hasAlbum]).
     */
    fun mappableCounts(
        source: TransferAccount,
        target: TransferAccount,
        items: MemoryItems,
        hasTrack: (SourceRef) -> Boolean,
        hasAlbum: (SourceRef) -> Boolean,
    ): TransferCounts {
        fun track(ref: SourceRef) = compatible(source, target, ref) || hasTrack(ref)
        fun album(ref: SourceRef) = compatible(source, target, ref) || hasAlbum(ref)
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

    /** The songs and albums of the pending items that need matching (not [compatible]), in a stable order. */
    fun needsMatching(source: TransferAccount, target: TransferAccount, pending: MemoryItems): Pair<List<SourceRef>, List<SourceRef>> {
        val trackRefs = LinkedHashSet<SourceRef>()
        pending.songNotes.forEach { trackRefs += it.track }
        pending.trackRatings.forEach { trackRefs += it.track }
        pending.trackAlbumLinks.forEach { trackRefs += it.track }
        val tracks = trackRefs.filterNot { compatible(source, target, it) }
        val linkAlbums = pending.trackAlbumLinks.map { SourceRef(it.track.provider, it.albumId) }
        val albums = (pending.albums + linkAlbums).distinct().filterNot { compatible(source, target, it) }
        return tracks to albums
    }

    /** The album a [ref] song is linked to in [items], if any. */
    fun linkedAlbum(ref: SourceRef, items: MemoryItems): String? = items.trackAlbumLinks.firstOrNull { it.track == ref }?.albumId
}
