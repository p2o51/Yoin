package com.gpo.yoin.data.source.spotify

import com.gpo.yoin.data.cache.DetailCacheStore
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.local.ActivityEventDao
import com.gpo.yoin.data.local.SpotifyHomeArtistCache
import com.gpo.yoin.data.local.SpotifyHomeCacheDao
import com.gpo.yoin.data.local.SpotifyLibraryCacheDao
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.perf.YoinPerf
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What GET /artists/{id} said of an artist's portrait: [url] null = it has
 * none ([name] null too: Spotify no longer has the artist).
 */
data class SpotifyArtistPortrait(val name: String?, val url: String?)

/**
 * Portraits for the artists on Spotify's Home Activities (owner Q16).
 * recently-played names each play's artists without images, so an artist
 * card would show its type icon.
 *
 * [withPortraits] fills them from what the device already has, no request:
 * what GET /artists said within [PORTRAIT_TTL_MS] (spotify_home_artist_cache),
 * the followed artists of the library sync, artist-page visits that recorded
 * a portrait, and the artist pages on disk. The feed gets them before it goes
 * up, so a portrait never shows, drops to an icon and comes back.
 *
 * [fetchMissing] asks Spotify for the rest of the artists Home shows, one
 * request at a time and only between the caller's turns ([fetchMissing]'s
 * `awaitTurn`). The rate-limit gate is per profile and covers playback too:
 * a closed gate, or the first 429, ends the pass, and after a 429 no more
 * passes run for that profile in this process. What Spotify answered is kept
 * — a portrait for [PORTRAIT_TTL_MS], "none" for [NO_PORTRAIT_TTL_MS] — and a
 * failed read keeps nothing, so a later pass asks again.
 */
class SpotifyActivityArtistArtwork(
    private val homeCache: SpotifyHomeCacheDao,
    private val libraryCache: SpotifyLibraryCacheDao,
    private val activityEvents: ActivityEventDao,
    private val detailCache: DetailCacheStore?,
    private val rateLimitGate: SpotifyRateLimitGate?,
    private val clock: () -> Long = System::currentTimeMillis,
    // A beat between two requests of one pass, so it never bursts.
    private val fetchSpacingMs: Long = FETCH_SPACING_MS
) {
    private val passLock = Mutex()

    // Profiles a pass met a 429 for: no more passes for them in this process.
    private val rateLimitedProfiles: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Profiles whose long-expired cache rows this process has dropped.
    private val purgedProfiles: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * [events] with each Spotify artist's portrait from the device; an artist
     * without one keeps the cover it came with (its play's album cover). A
     * failed read keeps [events] as they are.
     */
    suspend fun withPortraits(profileId: String, events: List<ActivityEvent>): List<ActivityEvent> {
        val artistIds = events.filter(::isSpotifyArtist).map(::artistIdOf).distinct()
        if (artistIds.isEmpty()) return events
        val portraits = try {
            lookUp(profileId, artistIds).portraits
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return events
        }
        if (portraits.isEmpty()) return events
        return events.map { event ->
            val portrait = if (isSpotifyArtist(event)) portraits[artistIdOf(event)] else null
            if (portrait == null || portrait == event.coverArtId) event else event.copy(coverArtId = portrait)
        }
    }

    /**
     * Ask Spotify for the portraits of [artistIds] (raw ids, at most
     * [MAX_FETCHES_PER_PASS] of them — the most the Activities show) that the
     * device has no word on: one GET at a time, each after [awaitTurn]. Each
     * portrait found goes to [onPortrait] as it lands, on the caller's
     * context. Returns when done or stopped (see the class).
     */
    suspend fun fetchMissing(
        profileId: String,
        artistIds: List<String>,
        fetch: suspend (artistId: String) -> SpotifyArtistPortrait,
        awaitTurn: suspend () -> Unit = {},
        onPortrait: (artistId: String, url: String) -> Unit
    ) {
        if (!mayRequest(profileId)) return
        passLock.withLock {
            val shown = artistIds.distinct().take(MAX_FETCHES_PER_PASS)
            if (shown.isEmpty()) return
            purgeExpiredOnce(profileId)
            val settled = try {
                lookUp(profileId, shown).settled
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return
            }
            val pass = Pass(shown = shown.size)
            val stop = runPass(profileId, shown.filterNot { it in settled }, pass, fetch, awaitTurn, onPortrait)
            // Debug only (docs/perf/yoinperf-logging.md).
            YoinPerf.mark(
                "home.portraits",
                "shown" to pass.shown,
                "asked" to pass.asked,
                "found" to pass.found,
                "stop" to stop
            )
        }
    }

    /** One pass's requests over [pending]; returns why it ended: done | gate | 429 | error. */
    private suspend fun runPass(
        profileId: String,
        pending: List<String>,
        pass: Pass,
        fetch: suspend (artistId: String) -> SpotifyArtistPortrait,
        awaitTurn: suspend () -> Unit,
        onPortrait: (artistId: String, url: String) -> Unit
    ): String {
        pending.forEachIndexed { index, artistId ->
            if (index > 0) delay(fetchSpacingMs)
            awaitTurn()
            if (!mayRequest(profileId)) return "gate"
            pass.asked++
            val portrait = try {
                fetch(artistId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: SpotifyRateLimitException) {
                rateLimitedProfiles += profileId
                return "429"
            } catch (_: Exception) {
                // Offline, a server error, an expired session: nothing is
                // kept, and the next pass asks again.
                return "error"
            }
            keep(profileId, artistId, portrait)
            portrait.url?.let { url ->
                pass.found++
                onPortrait(artistId, url)
            }
        }
        return "done"
    }

    private class Pass(val shown: Int) {
        var asked = 0
        var found = 0
    }

    private fun mayRequest(profileId: String): Boolean =
        profileId !in rateLimitedProfiles && rateLimitGate?.isBlocked(profileId) != true

    /**
     * What the device has on [artistIds]: [Lookup.portraits] to show, and
     * [Lookup.settled] — the artists no request is needed for (a portrait
     * other than an expired cache row's, or a recent "none").
     */
    private suspend fun lookUp(profileId: String, artistIds: List<String>): Lookup {
        val now = clock()
        val portraits = HashMap<String, String>()
        val settled = HashSet<String>()
        val expiredPortraits = HashMap<String, String>()
        homeCache.getArtists(profileId, artistIds.map(::cacheKey)).forEach { row ->
            val artistId = MediaId.storedRawId(MediaId.PROVIDER_SPOTIFY, row.artistId)
            val url = row.coverArtKey?.takeIf(::isUrl)
            val age = now - row.cachedAt
            when {
                url != null && age <= PORTRAIT_TTL_MS -> {
                    portraits[artistId] = url
                    settled += artistId
                }
                url != null -> expiredPortraits[artistId] = url
                age <= NO_PORTRAIT_TTL_MS -> settled += artistId
            }
        }
        fun missing(): List<String> = artistIds.filter { it !in portraits }
        fun found(artistId: String, url: String) {
            portraits[artistId] = url
            settled += artistId
        }

        // The followed artists, as the library sync last read them.
        var wanted = missing().toHashSet()
        if (wanted.isNotEmpty()) {
            libraryCache.getFreshArtists(profileId, minCachedAt = 0L).forEach { row ->
                val url = row.coverArtKey?.takeIf(::isUrl)
                if (url != null && row.artistId in wanted) found(row.artistId, url)
            }
        }
        // Artist pages visited, newest first (a visit records the portrait).
        wanted = missing().toHashSet()
        if (wanted.isNotEmpty()) {
            val keys = wanted.flatMap { id -> listOf(id, cacheKey(id)) }
            activityEvents.getArtistVisitsWithCover(profileId, MediaId.PROVIDER_SPOTIFY, keys).forEach { visit ->
                val artistId = MediaId.storedRawId(MediaId.PROVIDER_SPOTIFY, visit.entityId)
                val url = visit.coverArtId?.takeIf(::isUrl)
                if (url != null && artistId in wanted && artistId !in portraits) found(artistId, url)
            }
        }
        // Artist pages on disk (visited or prefetched): one row each, so last.
        if (detailCache != null) {
            missing().forEach { artistId ->
                val cover = detailCache.readArtist(profileId, cacheKey(artistId))?.value()?.coverArt
                (cover as? CoverRef.Url)?.url?.takeIf(::isUrl)?.let { url -> found(artistId, url) }
            }
        }
        // An expired portrait still beats the album cover until it is asked again.
        expiredPortraits.forEach { (artistId, url) -> portraits.putIfAbsent(artistId, url) }
        return Lookup(portraits = portraits, settled = settled)
    }

    private suspend fun keep(profileId: String, artistId: String, portrait: SpotifyArtistPortrait) {
        try {
            homeCache.insertArtists(
                listOf(
                    SpotifyHomeArtistCache(
                        profileId = profileId,
                        artistId = cacheKey(artistId),
                        name = portrait.name.orEmpty(),
                        coverArtKey = portrait.url,
                        sortOrder = 0,
                        cachedAt = clock()
                    )
                )
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Shown anyway; the next pass asks again.
        }
    }

    private suspend fun purgeExpiredOnce(profileId: String) {
        if (!purgedProfiles.add(profileId)) return
        try {
            homeCache.deleteArtistsCachedBefore(profileId, clock() - PURGE_AFTER_MS)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            purgedProfiles -= profileId
        }
    }

    private class Lookup(val portraits: Map<String, String>, val settled: Set<String>)

    companion object {
        /** A portrait GET /artists answered with is used without asking again for this long. */
        const val PORTRAIT_TTL_MS = 30L * 24 * 60 * 60 * 1000

        /** An artist Spotify said has no portrait isn't asked about again for this long. */
        const val NO_PORTRAIT_TTL_MS = 24L * 60 * 60 * 1000

        /** The most the Activities show: the tablet's XL bento seats 20 entries. */
        const val MAX_FETCHES_PER_PASS = 20

        private const val FETCH_SPACING_MS = 250L

        // Rows this old are dropped; an expired portrait younger than this
        // still shows until its artist is asked again.
        private const val PURGE_AFTER_MS = 90L * 24 * 60 * 60 * 1000

        private fun isSpotifyArtist(event: ActivityEvent): Boolean =
            event.provider == MediaId.PROVIDER_SPOTIFY && event.entityType == ActivityEntityType.ARTIST.name

        private fun artistIdOf(event: ActivityEvent): String =
            MediaId.storedRawId(MediaId.PROVIDER_SPOTIFY, event.entityId)

        private fun isUrl(key: String): Boolean = CoverRef.fromStorageKey(key) is CoverRef.Url

        /** spotify_home_artist_cache and the detail cache key artists by MediaId string. */
        private fun cacheKey(artistId: String): String = MediaId.spotify(artistId).toString()
    }
}
