package com.gpo.yoin.data.source.applemusic

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.remote.applemusic.AppleMusicSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Renames songs, albums, and artists after another storefront's names, as an account's [AppleMusicNameRegion]
 * asks. Items are grouped by the storefront they ask ([OriginalNameTarget.forRegion]); each group is one request
 * per batch of catalog ids (`GET /v1/catalog/{storefront}/songs?ids=…&l=…`).
 *
 * Key accuracy and performance invariants:
 * - Plain catalog lookups never mix with `include=tracks` / `views=top-songs` inspections, so large album/artist
 *   batches stay small (~30 KB) and fast, while inspected items (speculative Katakana or generic Mandopop) use
 *   small batches (`15` albums / `10` artists) that never hit Apple's payload timeouts.
 * - Generic Mandopop artists/albums in [AppleMusicNameRegion.Original] mode inspect nested top-song/track ISRCs:
 *   Mainland China (`CN` ISRCs) re-resolves from `cn` (Simplified), Hong Kong (`HK`/`MO`) from `hk`, and Taiwan
 *   stays Traditional (`tw`).
 * - Regional-edition albums absent in the target storefront fall back to their global catalog artist ID from
 *   [accountStorefront]; fixed storefronts fetch `include=artists` only (fast, no track-payload/ISRC storm),
 *   while [AppleMusicNameRegion.Original] fetches `include=artists,tracks` in small batches only for items that
 *   need ISRC verification or title recovery.
 * - Verified artist renames are cached by `(region, shownArtist)` so delisted regional albums whose catalog ID
 *   404s on both storefronts still stay consistent with the Artists list.
 */
internal class AppleMusicNameResolver(
    /** A catalog read: the path segments and query, answered with Apple's response document. */
    private val catalog: suspend (segments: List<String>, query: Map<String, String>) -> JsonObject,
    /** Catalog songs by ISRC in a storefront and language; see AppleMusicApiClient.catalogSongsByIsrc. */
    private val songsByIsrc: suspend (storefront: String, isrcs: List<String>, language: String) ->
    Map<String, List<AppleMusicSong>>,
) {
    /** One item's names in one storefront. A null field: the storefront named it nothing there. */
    data class Names(val title: String?, val artist: String?, val album: String?)

    private data class SourceAlbumFallback(
        val artistIds: List<String>,
        val firstTrackIsrc: String?,
        val preferredChineseTarget: OriginalNameTarget?,
        val hasJapaneseScript: Boolean,
        val genre: String?,
        val tracksInspected: Boolean,
    )

    private val cache = object : LinkedHashMap<String, Names?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Names?>?) = size > CACHE_SIZE
    }

    private val sourceAlbumCache = object : LinkedHashMap<String, SourceAlbumFallback?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SourceAlbumFallback?>?) =
            size > CACHE_SIZE
    }

    /** Verified `regionKey|shownArtist -> resolvedArtist` for consistency with delisted regional albums. */
    private val artistNameCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE_SIZE
    }

    suspend fun tracks(region: AppleMusicNameRegion, accountStorefront: String?, tracks: List<Track>): List<Track> {
        if (region == AppleMusicNameRegion.Off || tracks.isEmpty()) return tracks
        val requests = tracks.mapNotNull { track ->
            val id = track.catalogId() ?: return@mapNotNull null
            val isrc = track.extras[Track.EXTRA_ISRC]
            val target = OriginalNameTarget.forRegion(
                region, track.title, track.artist, track.genre, isrc,
            ) ?: return@mapNotNull null
            if (target.storefront == accountStorefront) return@mapNotNull null
            Request(id, target, isrc)
        }
        if (requests.isEmpty()) return tracks
        val names = resolve(Kind.Songs, requests)
        val byId = requests.associateBy { it.id }
        return tracks.map { track ->
            val id = track.catalogId() ?: return@map track
            val request = byId[id] ?: return@map track
            val found = names[id] ?: return@map track
            val pickedArtist = pick(region, request.target, track.artist, found.artist, isArtist = true)
            rememberArtistName(region, track.artist, pickedArtist)
            track.copy(
                title = pick(region, request.target, track.title, found.title),
                artist = pickedArtist,
                album = pick(region, request.target, track.album, found.album),
            )
        }
    }

    suspend fun albums(
        region: AppleMusicNameRegion,
        accountStorefront: String?,
        albums: List<Album>,
        isrcByAlbumId: Map<String, String> = emptyMap(),
    ): List<Album> {
        if (region == AppleMusicNameRegion.Off || albums.isEmpty()) return albums
        val requests = albums.mapNotNull { album ->
            val id = album.id.rawId.takeIf { album.id.provider == MediaId.PROVIDER_APPLE_MUSIC && it.isCatalogId() }
                ?: return@mapNotNull null
            val isrc = isrcByAlbumId[id]
            // An album's genre says its language as a song's does; album pages also pass a unanimous track ISRC.
            val target = OriginalNameTarget.forRegion(
                region,
                album.name,
                album.artist,
                album.genre,
                isrc = isrc,
            ) ?: return@mapNotNull null
            if (target.storefront == accountStorefront) return@mapNotNull null
            val speculative = region == AppleMusicNameRegion.Original &&
                target == OriginalNameTarget.UnitedStates &&
                OriginalNameTarget.isKatakanaSpeculative(album.name, album.artist, album.genre, isrc)
            val checkChinese = region == AppleMusicNameRegion.Original &&
                target == OriginalNameTarget.Taiwan &&
                OriginalNameTarget.isMandopopGeneric(album.genre, isrc)
            Request(
                id = id,
                target = target,
                isrc = null,
                verifyNotJapanese = speculative,
                checkChineseRegion = checkChinese,
            )
        }
        if (requests.isEmpty()) return albums
        val resolved = resolveWithEffectiveTargets(Kind.Albums, requests)
        val names = resolved.names.toMutableMap()
        val effectiveTargetById = resolved.targets.toMutableMap()
        val missed = requests.filter { it.id !in names }
        if (missed.isNotEmpty() && !accountStorefront.isNullOrBlank()) {
            val albumsById = albums.associateBy { it.id.rawId }
            val recovered = resolveMissedAlbumsViaSourceStorefront(
                region = region,
                accountStorefront = accountStorefront,
                missed = missed,
                albumsById = albumsById,
            )
            for ((id, entry) in recovered) {
                names[id] = entry.first
                effectiveTargetById[id] = entry.second
            }
        }
        // Pre-populate verified artist translations first so missed albums earlier in the list
        // (e.g. a delisted regional album sorted above the artist's other albums) stay consistent.
        for (album in albums) {
            val target = effectiveTargetById[album.id.rawId] ?: continue
            val found = names[album.id.rawId] ?: continue
            val pickedArtist = pick(region, target, album.artist, found.artist, isArtist = true)
            rememberArtistName(region, album.artist, pickedArtist)
        }
        return albums.map { album ->
            val target = effectiveTargetById[album.id.rawId]
            val found = names[album.id.rawId]
            if (target != null && found != null) {
                val pickedArtist = pick(region, target, album.artist, found.artist, isArtist = true)
                    ?: cachedArtistName(region, album.artist)
                    ?: album.artist
                album.copy(
                    name = pick(region, target, album.name, found.title) ?: album.name,
                    artist = pickedArtist,
                )
            } else {
                // Consistent artist naming when a delisted regional album ID 404s on both storefronts.
                val cachedArtist = cachedArtistName(region, album.artist)
                if (cachedArtist != null) album.copy(artist = cachedArtist) else album
            }
        }
    }

    /** Catalog id and primary genre read alongside an [Artist] (for library artists whose own id isn't catalog). */
    data class ArtistHint(val catalogId: String?, val genre: String?)

    suspend fun artists(
        region: AppleMusicNameRegion,
        accountStorefront: String?,
        artists: List<Artist>,
        hints: (Artist) -> ArtistHint = { artist ->
            ArtistHint(
                catalogId = artist.id.rawId.takeIf {
                    artist.id.provider == MediaId.PROVIDER_APPLE_MUSIC && it.isCatalogId()
                },
                genre = null,
            )
        },
    ): List<Artist> {
        if (region == AppleMusicNameRegion.Off || artists.isEmpty()) return artists
        val requests = mutableListOf<Request>()
        val byIndex = mutableMapOf<Int, Request>()
        artists.forEachIndexed { index, artist ->
            if (artist.id.provider != MediaId.PROVIDER_APPLE_MUSIC) return@forEachIndexed
            val hint = hints(artist)
            val id = hint.catalogId?.takeIf { it.isCatalogId() } ?: return@forEachIndexed
            val target = OriginalNameTarget.forRegion(
                region,
                title = null,
                artist = artist.name,
                genre = hint.genre,
                isrc = null,
            ) ?: return@forEachIndexed
            if (target.storefront == accountStorefront) return@forEachIndexed
            if (region == AppleMusicNameRegion.Original && target == OriginalNameTarget.Korea) return@forEachIndexed
            val speculative = region == AppleMusicNameRegion.Original &&
                target == OriginalNameTarget.UnitedStates &&
                OriginalNameTarget.isKatakanaSpeculative(null, artist.name, hint.genre, null)
            val checkChinese = region == AppleMusicNameRegion.Original &&
                target == OriginalNameTarget.Taiwan &&
                OriginalNameTarget.isMandopopGeneric(hint.genre, null)
            val request = Request(
                id = id,
                target = target,
                isrc = null,
                verifyNotJapanese = speculative,
                checkChineseRegion = checkChinese,
            )
            requests += request
            byIndex[index] = request
        }
        if (requests.isEmpty()) return artists
        val resolved = resolveWithEffectiveTargets(Kind.Artists, requests)
        return artists.mapIndexed { index, artist ->
            val request = byIndex[index] ?: return@mapIndexed artist
            val found = resolved.names[request.id] ?: return@mapIndexed artist
            val effectiveTarget = resolved.targets[request.id] ?: request.target
            val picked = pick(region, effectiveTarget, artist.name, found.title, isArtist = true) ?: artist.name
            rememberArtistName(region, artist.name, picked)
            artist.copy(name = picked)
        }
    }

    /** An album page: the album and its tracklist, renamed together with consistent header/track artist names. */
    suspend fun album(region: AppleMusicNameRegion, accountStorefront: String?, album: Album): Album {
        if (region == AppleMusicNameRegion.Off) return album
        val unanimousIsrc = unanimousTrackIsrc(album.tracks)
        val isrcMap = if (unanimousIsrc != null) mapOf(album.id.rawId to unanimousIsrc) else emptyMap()
        return coroutineScope {
            val tracksJob = async { tracks(region, accountStorefront, album.tracks) }
            val renamed = albums(region, accountStorefront, listOf(album), isrcByAlbumId = isrcMap).first()
            val renamedTracks = tracksJob.await()
            val consistentArtist = if (renamed.artist == album.artist && !album.artist.isNullOrBlank()) {
                consensusTrackArtist(album.artist, album.tracks, renamedTracks) ?: renamed.artist
            } else {
                renamed.artist
            }
            renamed.copy(artist = consistentArtist, tracks = renamedTracks)
        }
    }

    /**
     * Regional-edition albums carry storefront-local album IDs that return empty on another storefront.
     * - Under a fixed [AppleMusicNameRegion.Storefront], fetches `include=artists` only (fast, tiny payload)
     *   to resolve global artist IDs without track-payload timeouts or ISRC storms.
     * - Under [AppleMusicNameRegion.Original], fetches `include=artists,tracks` in small batches (`15`) only
     *   for albums that need JP/CN ISRC verification or non-Latin title recovery, and `include=artists` only
     *   (`50`) for all other missed albums.
     */
    private suspend fun resolveMissedAlbumsViaSourceStorefront(
        region: AppleMusicNameRegion,
        accountStorefront: String,
        missed: List<Request>,
        albumsById: Map<String, Album>,
    ): Map<String, Pair<Names, OriginalNameTarget>> = coroutineScope {
        val withTracksIds = mutableListOf<String>()
        val artistsOnlyIds = mutableListOf<String>()
        if (region == AppleMusicNameRegion.Original) {
            for (req in missed) {
                val title = albumsById[req.id]?.name.orEmpty()
                val titleNeedsRecovery =
                    (req.target == OriginalNameTarget.UnitedStates && OriginalNameTarget.hasNonLatin(title)) ||
                        (OriginalNameTarget.isChinese(req.target) && !OriginalNameTarget.hasHanText(title))
                if (req.verifyNotJapanese || req.checkChineseRegion || titleNeedsRecovery) {
                    withTracksIds += req.id
                } else {
                    artistsOnlyIds += req.id
                }
            }
        } else {
            missed.forEach { artistsOnlyIds += it.id }
        }

        val withTracksJob = async {
            if (withTracksIds.isEmpty()) emptyMap()
            else fetchSourceAlbumFallbacks(accountStorefront, withTracksIds, includeTracks = true)
        }
        val artistsOnlyJob = async {
            if (artistsOnlyIds.isEmpty()) emptyMap()
            else fetchSourceAlbumFallbacks(accountStorefront, artistsOnlyIds, includeTracks = false)
        }
        val sourceInfo = withTracksJob.await() + artistsOnlyJob.await()
        if (sourceInfo.isEmpty()) return@coroutineScope emptyMap()

        data class Plan(
            val albumId: String,
            val target: OriginalNameTarget,
            val artistIds: List<String>,
            val isrc: String?,
            val needsTitleByIsrc: Boolean,
            val verifyArtistNotJapanese: Boolean,
            val checkChineseRegion: Boolean,
        )

        val plans = missed.mapNotNull { req ->
            val info = sourceInfo[req.id] ?: return@mapNotNull null
            val album = albumsById[req.id]
            val effectiveTarget = if (region == AppleMusicNameRegion.Original) {
                val byIsrc = OriginalNameTarget.forIsrc(info.firstTrackIsrc)
                val byGenre = OriginalNameTarget.forGenre(info.genre)
                when {
                    info.preferredChineseTarget != null -> info.preferredChineseTarget
                    byIsrc != null && byIsrc.storefront == accountStorefront -> return@mapNotNull null
                    byIsrc == OriginalNameTarget.UnitedStates -> OriginalNameTarget.UnitedStates
                    byIsrc != null -> byIsrc
                    byGenre != null && byGenre.storefront == accountStorefront -> return@mapNotNull null
                    req.verifyNotJapanese && info.hasJapaneseScript -> return@mapNotNull null
                    else -> req.target
                }
            } else {
                req.target
            }
            if (effectiveTarget.storefront == accountStorefront) return@mapNotNull null
            if (region == AppleMusicNameRegion.Original && effectiveTarget == OriginalNameTarget.Korea) {
                return@mapNotNull null
            }
            val title = album?.name.orEmpty()
            val needsTitle = region == AppleMusicNameRegion.Original && (
                (effectiveTarget == OriginalNameTarget.UnitedStates && OriginalNameTarget.hasNonLatin(title)) ||
                    (OriginalNameTarget.isChinese(effectiveTarget) && !OriginalNameTarget.hasHanText(title))
                )
            val confirmedNonJapanese = info.firstTrackIsrc != null &&
                OriginalNameTarget.forIsrc(info.firstTrackIsrc) != null
            Plan(
                albumId = req.id,
                target = effectiveTarget,
                artistIds = info.artistIds.filter { it.isCatalogId() },
                isrc = info.firstTrackIsrc,
                needsTitleByIsrc = needsTitle && !info.firstTrackIsrc.isNullOrBlank(),
                verifyArtistNotJapanese = req.verifyNotJapanese && !confirmedNonJapanese,
                checkChineseRegion = req.checkChineseRegion && info.preferredChineseTarget == null,
            )
        }
        if (plans.isEmpty()) return@coroutineScope emptyMap()

        val artistRequests = plans.flatMap { plan ->
            plan.artistIds.map { artistId ->
                Request(
                    id = artistId,
                    target = plan.target,
                    isrc = null,
                    verifyNotJapanese = plan.verifyArtistNotJapanese,
                    checkChineseRegion = plan.checkChineseRegion,
                )
            }
        }
        val artistJob = async {
            if (artistRequests.isEmpty()) ResolvedBatch(emptyMap(), emptyMap())
            else resolveWithEffectiveTargets(Kind.Artists, artistRequests)
        }
        val isrcJob = async {
            val byTarget = plans.filter { it.needsTitleByIsrc && it.isrc != null }.groupBy { it.target }
            byTarget.map { (target, group) ->
                async {
                    val isrcs = group.mapNotNull { it.isrc }.distinct()
                    val songs = try {
                        withTimeoutOrNull(ISRC_TIMEOUT_MS) {
                            songsByIsrc(target.storefront, isrcs, target.language)
                        }.orEmpty()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyMap()
                    }
                    target to songs
                }
            }.awaitAll().toMap()
        }

        val resolvedArtists = artistJob.await()
        val resolvedIsrcs = isrcJob.await()
        val out = LinkedHashMap<String, Pair<Names, OriginalNameTarget>>()
        for (plan in plans) {
            val resolvedArtistParts = plan.artistIds.mapNotNull { resolvedArtists.names[it]?.title?.takeIf(String::isNotBlank) }
            val artistName = resolvedArtistParts.takeIf { it.isNotEmpty() }?.joinToString(" & ")
            val effectiveTarget = plan.artistIds.firstNotNullOfOrNull { resolvedArtists.targets[it] } ?: plan.target
            val songMatch = plan.isrc?.let { resolvedIsrcs[plan.target]?.get(it)?.firstOrNull() }
            val title = songMatch?.album
            val artist = artistName ?: songMatch?.artist
            if (title != null || artist != null) {
                val resolved = Names(title = title, artist = artist, album = null)
                out[plan.albumId] = resolved to effectiveTarget
                synchronized(cache) {
                    cache[key(Kind.Albums, effectiveTarget, plan.albumId)] = resolved
                }
            }
        }
        out
    }

    private suspend fun fetchSourceAlbumFallbacks(
        accountStorefront: String,
        albumIds: List<String>,
        includeTracks: Boolean,
    ): Map<String, SourceAlbumFallback> = coroutineScope {
        val known = LinkedHashMap<String, SourceAlbumFallback>()
        val unread = mutableListOf<String>()
        synchronized(sourceAlbumCache) {
            for (id in albumIds.distinct()) {
                val cacheKey = "$accountStorefront|$id"
                if (sourceAlbumCache.containsKey(cacheKey)) {
                    val cached = sourceAlbumCache[cacheKey]
                    if (cached == null || cached.tracksInspected || !includeTracks) {
                        cached?.let { known[id] = it }
                    } else {
                        unread += id
                    }
                } else {
                    unread += id
                }
            }
        }
        if (unread.isEmpty()) return@coroutineScope known
        val batchSize = if (includeTracks) INSPECT_ALBUMS_BATCH else ARTISTS_ONLY_ALBUMS_BATCH
        val includeValue = if (includeTracks) "artists,tracks" else "artists"
        val semaphore = Semaphore(MAX_CONCURRENT_BATCHES)
        val batches = unread.chunked(batchSize).map { batch ->
            async {
                semaphore.withPermit {
                    val response = try {
                        withTimeoutOrNull(BATCH_TIMEOUT_MS) {
                            catalog(
                                listOf("v1", "catalog", accountStorefront, Kind.Albums.path),
                                mapOf("ids" to batch.joinToString(","), "include" to includeValue),
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    } ?: return@withPermit null
                    val parsed = LinkedHashMap<String, SourceAlbumFallback>()
                    (response["data"] as? JsonArray).orEmpty().forEach { element ->
                        val item = element as? JsonObject ?: return@forEach
                        val id = item.text("id") ?: return@forEach
                        val attrs = item["attributes"] as? JsonObject
                        val genre = attrs?.genreString()
                        val rels = item["relationships"] as? JsonObject
                        val artistIds = ((rels?.get("artists") as? JsonObject)?.get("data") as? JsonArray)
                            .orEmpty()
                            .mapNotNull { (it as? JsonObject)?.text("id") }
                        val tracksArray = ((rels?.get("tracks") as? JsonObject)?.get("data") as? JsonArray)
                        val tracksData = tracksArray.orEmpty()
                        var firstIsrc: String? = null
                        var hasJpScript = false
                        val chineseTarget = if (includeTracks) preferredChineseTargetFromTracks(tracksArray) else null
                        for (trackEl in tracksData) {
                            val trackAttrs = (trackEl as? JsonObject)?.get("attributes") as? JsonObject ?: continue
                            if (firstIsrc == null) firstIsrc = trackAttrs.text("isrc")
                            val trackName = trackAttrs.text("name").orEmpty()
                            if (OriginalNameTarget.hasJapaneseScript(trackName)) hasJpScript = true
                        }
                        parsed[id] = SourceAlbumFallback(
                            artistIds = artistIds,
                            firstTrackIsrc = firstIsrc,
                            preferredChineseTarget = chineseTarget,
                            hasJapaneseScript = hasJpScript,
                            genre = genre,
                            tracksInspected = includeTracks,
                        )
                    }
                    synchronized(sourceAlbumCache) {
                        for (id in batch) {
                            sourceAlbumCache["$accountStorefront|$id"] = parsed[id]
                        }
                    }
                    parsed
                }
            }
        }.awaitAll()
        batches.forEach { if (it != null) known += it }
        known
    }

    private fun rememberArtistName(region: AppleMusicNameRegion, shown: String?, resolved: String?) {
        val s = shown?.trim().orEmpty()
        val r = resolved?.trim().orEmpty()
        if (s.isEmpty() || r.isEmpty() || s == r) return
        if (OriginalNameTarget.isCompilationArtist(s) || OriginalNameTarget.isCompilationArtist(r)) return
        synchronized(artistNameCache) {
            artistNameCache["${region.key}|$s"] = r
        }
    }

    private fun cachedArtistName(region: AppleMusicNameRegion, shown: String?): String? {
        val s = shown?.trim().orEmpty()
        if (s.isEmpty() || OriginalNameTarget.isCompilationArtist(s)) return null
        return synchronized(artistNameCache) { artistNameCache["${region.key}|$s"] }
    }

    private fun unanimousTrackIsrc(tracks: List<Track>): String? {
        val isrcs = tracks.mapNotNull { it.extras[Track.EXTRA_ISRC]?.takeIf(String::isNotBlank) }
        if (isrcs.isEmpty()) return null
        val targets = isrcs.mapNotNull { OriginalNameTarget.forIsrc(it) }.distinct()
        return if (targets.size == 1) isrcs.first() else null
    }

    private fun consensusTrackArtist(
        originalArtist: String,
        originalTracks: List<Track>,
        renamedTracks: List<Track>,
    ): String? {
        val renamed = originalTracks.zip(renamedTracks).mapNotNull { (before, after) ->
            if (before.artist == originalArtist && !after.artist.isNullOrBlank() && after.artist != originalArtist) {
                after.artist
            } else {
                null
            }
        }.distinct()
        return renamed.singleOrNull()
    }

    private fun pick(
        region: AppleMusicNameRegion,
        target: OriginalNameTarget,
        shown: String?,
        found: String?,
        isArtist: Boolean = false,
    ) = if (OriginalNameTarget.accepts(region, target, shown, found, isArtist = isArtist)) found?.trim() else shown

    private data class ResolvedBatch(
        val names: Map<String, Names>,
        val targets: Map<String, OriginalNameTarget>,
    )

    private data class ReadOutcome(
        val found: Map<String, Names>,
        val reclassified: Map<String, OriginalNameTarget>,
    )

    /** Catalog id → its names, for every request that has an answer (cached or read now). */
    private suspend fun resolve(kind: Kind, requests: List<Request>): Map<String, Names> =
        resolveWithEffectiveTargets(kind, requests).names

    private suspend fun resolveWithEffectiveTargets(kind: Kind, requests: List<Request>): ResolvedBatch = coroutineScope {
        val semaphore = Semaphore(MAX_CONCURRENT_BATCHES)
        val distinctRequests = requests.distinctBy { it.id }
        val finalNames = LinkedHashMap<String, Names>()
        val finalTargets = distinctRequests.associate { it.id to it.target }.toMutableMap()
        val reclassifiedRequests = mutableListOf<Request>()

        val groupResults = distinctRequests.groupBy { it.target }.map { (target, group) ->
            async {
                val known = LinkedHashMap<String, Names>()
                val unreadPlain = mutableListOf<Request>()
                val unreadInspected = mutableListOf<Request>()
                synchronized(cache) {
                    group.forEach { request ->
                        val cacheKey = key(kind, target, request.id)
                        if (cache.containsKey(cacheKey)) {
                            cache[cacheKey]?.let { known[request.id] = it }
                        } else if (request.needsInspection && kind != Kind.Songs) {
                            unreadInspected += request
                        } else {
                            unreadPlain += request
                        }
                    }
                }
                val plainChunks = unreadPlain.chunked(kind.batch).map { it to false }
                val inspectedChunks = unreadInspected.chunked(kind.inspectBatch).map { it to true }
                val batchResults = (plainChunks + inspectedChunks).map { (batch, inspect) ->
                    async {
                        semaphore.withPermit {
                            val outcome = readBatch(kind, target, batch, inspect) ?: return@withPermit null
                            synchronized(cache) {
                                batch.forEach { req ->
                                    if (req.id !in outcome.reclassified) {
                                        cache[key(kind, target, req.id)] = outcome.found[req.id]
                                    }
                                }
                            }
                            outcome
                        }
                    }
                }.awaitAll()

                val groupReclassified = mutableListOf<Request>()
                batchResults.forEach { outcome ->
                    if (outcome != null) {
                        known += outcome.found
                        for ((id, newTarget) in outcome.reclassified) {
                            groupReclassified += Request(id = id, target = newTarget, isrc = null)
                        }
                    }
                }

                // Single deduplicated ISRC fallback pass per target storefront for missed songs (avoids
                // nested semaphore/concurrency storms when 500+ songs miss in a fixed storefront).
                if (kind == Kind.Songs) {
                    val missedWithIsrc = unreadPlain.filter { it.id !in known && !it.isrc.isNullOrBlank() }
                    if (missedWithIsrc.isNotEmpty()) {
                        val songs = try {
                            withTimeoutOrNull(ISRC_TIMEOUT_MS) {
                                songsByIsrc(target.storefront, missedWithIsrc.mapNotNull { it.isrc }, target.language)
                            }.orEmpty()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            emptyMap()
                        }
                        synchronized(cache) {
                            missedWithIsrc.forEach { req ->
                                val song = songs[req.isrc]?.firstOrNull() ?: return@forEach
                                val resolved = Names(song.title, song.artist, song.album)
                                known[req.id] = resolved
                                cache[key(kind, target, req.id)] = resolved
                            }
                        }
                    }
                }
                Triple(target, known, groupReclassified)
            }
        }.awaitAll()

        for ((_, known, reclass) in groupResults) {
            finalNames += known
            reclassifiedRequests += reclass
        }

        // Re-fetch Mandopop items whose nested track ISRCs proved Mainland China (`CN`) or Hong Kong (`HK`/`MO`)
        // from their actual storefront (`cn` Simplified / `hk` Traditional) in a single plain batch pass.
        if (reclassifiedRequests.isNotEmpty()) {
            val reclassResults = reclassifiedRequests.groupBy { it.target }.map { (target, group) ->
                async {
                    val known = LinkedHashMap<String, Names>()
                    val unread = mutableListOf<Request>()
                    synchronized(cache) {
                        group.forEach { req ->
                            val cacheKey = key(kind, target, req.id)
                            if (cache.containsKey(cacheKey)) {
                                cache[cacheKey]?.let { known[req.id] = it }
                            } else {
                                unread += req
                            }
                        }
                    }
                    unread.chunked(kind.batch).map { batch ->
                        async {
                            semaphore.withPermit {
                                val outcome = readBatch(kind, target, batch, inspect = false) ?: return@withPermit null
                                synchronized(cache) {
                                    batch.forEach { req -> cache[key(kind, target, req.id)] = outcome.found[req.id] }
                                }
                                outcome.found
                            }
                        }
                    }.awaitAll().forEach { found -> if (found != null) known += found }
                    target to known
                }
            }.awaitAll()

            for ((target, known) in reclassResults) {
                for ((id, names) in known) {
                    finalNames[id] = names
                    finalTargets[id] = target
                }
            }
        }

        ResolvedBatch(finalNames, finalTargets)
    }

    private suspend fun readBatch(
        kind: Kind,
        target: OriginalNameTarget,
        requests: List<Request>,
        inspect: Boolean,
    ): ReadOutcome? {
        val verifyIds = if (inspect) requests.filter { it.verifyNotJapanese }.map { it.id }.toSet() else emptySet()
        val checkChineseIds = if (inspect) requests.filter { it.checkChineseRegion }.map { it.id }.toSet() else emptySet()
        val query = buildMap {
            put("ids", requests.joinToString(",") { it.id })
            put("l", target.language)
            if (verifyIds.isNotEmpty() || checkChineseIds.isNotEmpty()) {
                when (kind) {
                    Kind.Artists -> put("views", "top-songs")
                    Kind.Albums -> put("include", "tracks")
                    Kind.Songs -> Unit
                }
            }
        }
        val response = try {
            withTimeoutOrNull(BATCH_TIMEOUT_MS) {
                catalog(listOf("v1", "catalog", target.storefront, kind.path), query)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        val found = LinkedHashMap<String, Names>()
        val reclassified = LinkedHashMap<String, OriginalNameTarget>()
        (response["data"] as? JsonArray).orEmpty().forEach { resource ->
            val item = resource as? JsonObject ?: return@forEach
            val id = item.text("id") ?: return@forEach
            val attributes = item["attributes"] as? JsonObject ?: return@forEach
            if (id in verifyIds && isConfirmedNonWesternOrigin(kind, item, attributes)) {
                return@forEach
            }
            if (id in checkChineseIds) {
                val nestedTracks = nestedTracksFor(kind, item)
                val preferred = preferredChineseTargetFromTracks(nestedTracks)
                if (preferred != null && preferred != target) {
                    reclassified[id] = preferred
                }
            }
            found[id] = Names(
                title = attributes.text("name"),
                artist = attributes.text("artistName"),
                album = attributes.text("albumName"),
            )
        }
        return ReadOutcome(found, reclassified)
    }

    private fun nestedTracksFor(kind: Kind, item: JsonObject): JsonArray? = when (kind) {
        Kind.Artists -> (((item["views"] as? JsonObject)?.get("top-songs") as? JsonObject)?.get("data") as? JsonArray)
        Kind.Albums -> (((item["relationships"] as? JsonObject)?.get("tracks") as? JsonObject)?.get("data") as? JsonArray)
        Kind.Songs -> null
    }

    /**
     * Infers whether a Mandopop artist/album originates from Mainland China (`CN` ISRCs) or Hong Kong (`HK`/`MO`)
     * vs Taiwan (`TW`). Returns null when track ISRCs are absent or do not point away from Taiwan.
     */
    private fun preferredChineseTargetFromTracks(nestedTracks: JsonArray?): OriginalNameTarget? {
        if (nestedTracks == null || nestedTracks.isEmpty()) return null
        var cn = 0
        var tw = 0
        var hk = 0
        for (el in nestedTracks) {
            val attrs = (el as? JsonObject)?.get("attributes") as? JsonObject ?: continue
            when (OriginalNameTarget.forIsrc(attrs.text("isrc"))) {
                OriginalNameTarget.MainlandChina -> cn++
                OriginalNameTarget.Taiwan -> tw++
                OriginalNameTarget.HongKong -> hk++
                else -> Unit
            }
        }
        return when {
            cn > 0 && cn >= tw + hk -> OriginalNameTarget.MainlandChina
            hk > 0 && hk > tw && cn == 0 -> OriginalNameTarget.HongKong
            tw > 0 -> OriginalNameTarget.Taiwan
            else -> null
        }
    }

    /**
     * Checks catalog metadata (`genreNames`, artist `views.top-songs`, album `relationships.tracks`) for a
     * speculative Katakana-to-US lookup so native Japanese (e.g. ヨルシカ, サカナクション, ササノマリイ) or
     * Chinese/Korean acts are never romanized.
     */
    private fun isConfirmedNonWesternOrigin(kind: Kind, item: JsonObject, attributes: JsonObject): Boolean {
        val itemGenreTarget = OriginalNameTarget.forGenre(attributes.genreString())
        if (itemGenreTarget != null && itemGenreTarget != OriginalNameTarget.UnitedStates) return true
        val nestedTracks = nestedTracksFor(kind, item) ?: return false
        for (el in nestedTracks) {
            val trackAttrs = (el as? JsonObject)?.get("attributes") as? JsonObject ?: continue
            val isrcTarget = OriginalNameTarget.forIsrc(trackAttrs.text("isrc"))
            if (isrcTarget != null && isrcTarget != OriginalNameTarget.UnitedStates) return true
            val trackGenreTarget = OriginalNameTarget.forGenre(trackAttrs.genreString())
            if (trackGenreTarget != null && trackGenreTarget != OriginalNameTarget.UnitedStates) return true
            val trackName = trackAttrs.text("name").orEmpty()
            if (OriginalNameTarget.hasJapaneseScript(trackName)) return true
        }
        return false
    }

    /** [batch]: Apple's most ids per plain request; [inspectBatch]: smaller ceiling when including nested tracks. */
    private enum class Kind(val path: String, val batch: Int, val inspectBatch: Int) {
        Songs("songs", 300, 300),
        Albums("albums", 100, INSPECT_ALBUMS_BATCH),
        Artists("artists", 25, INSPECT_ARTISTS_BATCH),
    }

    private data class Request(
        val id: String,
        val target: OriginalNameTarget,
        val isrc: String?,
        val verifyNotJapanese: Boolean = false,
        val checkChineseRegion: Boolean = false,
    ) {
        val needsInspection: Boolean get() = verifyNotJapanese || checkChineseRegion
    }

    private fun key(kind: Kind, target: OriginalNameTarget, id: String) =
        "${kind.path}|${target.storefront}|${target.language}|$id"

    private fun Track.catalogId(): String? {
        if (id.provider != MediaId.PROVIDER_APPLE_MUSIC) return null
        return (extras[AppleMusicSong.EXTRA_CATALOG_ID] ?: id.rawId).takeIf { it.isCatalogId() }
    }

    private fun String.isCatalogId() = isNotEmpty() && all(Char::isDigit)

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.genreString(): String? = (get("genreNames") as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.joinToString(", ")
        ?.ifBlank { null }

    private companion object {
        /** Items whose names are kept: a large playlist's worth. */
        const val CACHE_SIZE = 4_000

        /** How long a list waits on one batch of names before it opens under the names it has. */
        const val BATCH_TIMEOUT_MS = 6_000L

        /** Timeout for a deduplicated multi-batch ISRC fallback pass. */
        const val ISRC_TIMEOUT_MS = 10_000L

        /** Small batch ceilings when requests embed nested `tracks` / `top-songs` to avoid multi-MB timeouts. */
        const val INSPECT_ALBUMS_BATCH = 15
        const val INSPECT_ARTISTS_BATCH = 10
        const val ARTISTS_ONLY_ALBUMS_BATCH = 50

        /** Bounded parallelism for multi-batch region lookups. */
        const val MAX_CONCURRENT_BATCHES = 4
    }
}
