package com.gpo.yoin.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.Capability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: YoinRepository,
    private val activeProfileId: StateFlow<String?>,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var artistPool: List<Artist> = emptyList()
    private var artistPoolWarmupJob: Job? = null

    init {
        refresh()
        observeRecentHistory()
    }

    fun refresh() {
        val providerId = repository.currentProviderId()
        val profileId = activeProfileId.value
        artistPoolWarmupJob?.cancel()
        artistPoolWarmupJob = null
        artistPool = emptyList()
        viewModelScope.launch {
            val scopeKey = homeScopeKey(providerId, profileId)
            val cachedHomeContent = homeContentCache[scopeKey]
            val cachedSpotifyContent = if (
                providerId == MediaId.PROVIDER_SPOTIFY &&
                !profileId.isNullOrBlank()
            ) {
                loadCachedSpotifyHomeContent(profileId)
            } else {
                null
            }

            _uiState.value = cachedHomeContent
                ?: cachedSpotifyContent
                ?: HomeUiState.Loading

            try {
                val freshContent = when {
                    providerId == MediaId.PROVIDER_SPOTIFY && !profileId.isNullOrBlank() ->
                        loadSpotifyHomeContent(profileId)

                    else -> loadHomeContent()
                }
                if (!matchesCurrentScope(providerId, profileId)) return@launch
                homeContentCache[scopeKey] = freshContent
                _uiState.value = freshContent
                if (providerId != MediaId.PROVIDER_SPOTIFY) {
                    warmArtistPool()
                }
            } catch (e: Exception) {
                if (!matchesCurrentScope(providerId, profileId)) return@launch
                if (cachedSpotifyContent == null) {
                    _uiState.value = HomeUiState.Error(
                        e.message ?: "Failed to load home content",
                    )
                }
            }
        }
    }

    fun refreshJumpBackIn() {
        val currentContent = _uiState.value as? HomeUiState.Content ?: return
        viewModelScope.launch {
            try {
                _uiState.value = currentContent.copy(
                    jumpBackInItems = loadJumpBackInItems(
                        existingIds = emptySet(),
                        batchSize = JUMP_BACK_IN_FIXED_COUNT,
                    ),
                )
            } catch (_: Exception) {
                // Keep the current section stable if recommendation refresh fails.
            }
        }
    }

    fun buildCoverArtUrl(coverArtId: String): String =
        repository.resolveSubsonicCoverUrl(coverArtId, size = 320).orEmpty()

    private suspend fun loadHomeContent(): HomeUiState.Content =
        coroutineScope {
            val activitiesDeferred = async {
                repository.getRecentActivities(limit = 20).first()
            }
            val jumpBackInDeferred = async {
                loadJumpBackInItems(
                    existingIds = emptySet(),
                    batchSize = JUMP_BACK_IN_FIXED_COUNT,
                )
            }
            val memoryTeaserDeferred = async {
                repository.getTopAlbumMemoryCandidate()?.toMemoryTeaser()
            }

            HomeUiState.Content(
                activities = activitiesDeferred.await(),
                jumpBackInItems = jumpBackInDeferred.await(),
                memoryTeaser = memoryTeaserDeferred.await(),
            )
        }

    private suspend fun loadCachedSpotifyHomeContent(
        profileId: String,
    ): HomeUiState.Content? = coroutineScope {
        val activitiesDeferred = async {
            repository.getRecentActivities(limit = 20).first()
        }
        val cacheSnapshotDeferred = async {
            repository.getCachedSpotifyHomeJumpBackIn(
                profileId = profileId,
                maxAgeMs = SpotifyHomeCacheTtlMillis,
            )
        }
        val memoryTeaserDeferred = async {
            repository.getTopAlbumMemoryCandidate()?.toMemoryTeaser()
        }

        val activities = activitiesDeferred.await()
        val cacheSnapshot = cacheSnapshotDeferred.await()
        val jumpBackInItems = buildJumpBackInBatch(
            albumCandidates = cacheSnapshot.albums,
            songCandidates = emptyList(),
            artistCandidates = cacheSnapshot.artists,
            existingIds = emptySet(),
            batchSize = JUMP_BACK_IN_FIXED_COUNT,
            shuffleCandidates = false,
        )

        val memoryTeaser = memoryTeaserDeferred.await()
        if (activities.isEmpty() && jumpBackInItems.isEmpty() && memoryTeaser == null) {
            null
        } else {
            HomeUiState.Content(
                activities = activities,
                jumpBackInItems = jumpBackInItems,
                memoryTeaser = memoryTeaser,
            )
        }
    }

    private suspend fun loadSpotifyHomeContent(
        profileId: String,
    ): HomeUiState.Content = coroutineScope {
        val activitiesDeferred = async { resolveSpotifyActivities() }
        val albumDeferred = async {
            repository.getAlbumList("random", size = JUMP_BACK_IN_ALBUM_REQUEST_SIZE)
        }
        val artistsDeferred = async {
            loadArtistsFlat()
        }
        val memoryTeaserDeferred = async {
            repository.getTopAlbumMemoryCandidate()?.toMemoryTeaser()
        }

        val (activities, activitiesFromRemote) = activitiesDeferred.await()
        val shuffledAlbums = albumDeferred.await()
            .distinctBy { album -> album.id }
            .shuffled()
            .take(SpotifyHomeCacheCandidateCount)
        val allArtists = artistsDeferred.await()
        artistPool = allArtists
        val shuffledArtists = allArtists
            .distinctBy { artist -> artist.id }
            .shuffled()
            .take(SpotifyHomeCacheCandidateCount)

        repository.replaceSpotifyHomeJumpBackInCache(
            profileId = profileId,
            albums = shuffledAlbums,
            artists = shuffledArtists,
        )

        HomeUiState.Content(
            activities = activities,
            activitiesFromRemote = activitiesFromRemote,
            jumpBackInItems = buildJumpBackInBatch(
                albumCandidates = shuffledAlbums,
                songCandidates = emptyList(),
                artistCandidates = shuffledArtists,
                existingIds = emptySet(),
                batchSize = JUMP_BACK_IN_FIXED_COUNT,
                shuffleCandidates = false,
            ),
            memoryTeaser = memoryTeaserDeferred.await(),
        )
    }

    /**
     * Spotify Activities prefer the real recently-played endpoint, falling back
     * to the locally recorded feed when the endpoint fails (e.g.
     * user-read-recently-played not granted) or the account has no recent
     * plays. Returns the events plus whether they came from the endpoint, so
     * [observeRecentHistory] knows whether the endpoint owns the feed.
     * CancellationException is rethrown so cooperative cancellation isn't
     * swallowed by the fallback.
     */
    private suspend fun resolveSpotifyActivities(): Pair<List<ActivityEvent>, Boolean> {
        val remote = try {
            repository.getSpotifyRecentActivities(limit = 20)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        return if (!remote.isNullOrEmpty()) {
            remote to true
        } else {
            repository.getRecentActivities(limit = 20).first() to false
        }
    }

    private suspend fun loadJumpBackInItems(
        existingIds: Set<String>,
        batchSize: Int,
    ): List<HomeJumpBackInItem> = coroutineScope {
        val albumDeferred = async {
            repository.getAlbumList("random", size = JUMP_BACK_IN_ALBUM_REQUEST_SIZE)
        }
        // Only providers that declare RANDOM_SONGS have a "pick me random
        // tracks" endpoint. Spotify Web API has nothing equivalent, so skip
        // the song candidate pool entirely — no empty network request, no
        // awkward empty "random songs" hole in the jump-back-in feed.
        val songDeferred = async {
            if (Capability.RANDOM_SONGS in repository.currentCapabilities()) {
                repository.getRandomSongs(size = JUMP_BACK_IN_SONG_REQUEST_SIZE)
            } else {
                emptyList()
            }
        }

        buildJumpBackInBatch(
            albumCandidates = albumDeferred.await(),
            songCandidates = songDeferred.await(),
            artistCandidates = artistPool,
            existingIds = existingIds,
            batchSize = batchSize,
        )
    }

    private fun observeRecentHistory() {
        viewModelScope.launch {
            repository.getRecentActivities(limit = 20).collectLatest { localActivities ->
                val currentContent = _uiState.value as? HomeUiState.Content ?: return@collectLatest
                // The recently-played endpoint owns the Spotify feed ONLY when
                // the activities actually came from it; on the local fallback
                // (scope missing / no recent plays) the feed must keep
                // live-updating from local writes like every other provider.
                // Otherwise a local activity-event write must not clobber the
                // endpoint feed. The memory teaser refreshes for all providers.
                val keepEndpointFeed = currentContent.activitiesFromRemote &&
                    repository.currentProviderId() == MediaId.PROVIDER_SPOTIFY
                val nextContent = currentContent.copy(
                    activities = if (keepEndpointFeed) currentContent.activities else localActivities,
                    activitiesFromRemote = keepEndpointFeed,
                    memoryTeaser = repository.getTopAlbumMemoryCandidate()?.toMemoryTeaser(),
                )
                homeContentCache[homeScopeKey(repository.currentProviderId(), activeProfileId.value)] = nextContent
                _uiState.value = nextContent
            }
        }
    }

    private fun AlbumMemoryCandidate.toMemoryTeaser(): MemoryTeaser {
        // A "formed" memory speaks in a recall voice; a still-forming one nudges
        // the user to finish shaping it. The coverage gate mirrors the original
        // Memory rule, so a fully-rated album counts as formed even without a
        // written review.
        val formed = hasAlbumReview || ratingCoverage >= MEMORY_FORMED_COVERAGE
        val timeHook = recallTimePhrase(lastPlayedAt ?: firstPlayedAt)
        return MemoryTeaser(
            albumId = "$provider:$albumId",
            sessionId = sessionId,
            title = albumName,
            isFormed = formed,
            supportingText = if (formed) {
                when {
                    timeHook != null -> "You shaped this memory — last with it $timeHook."
                    else -> "Your album memory is here whenever you want to revisit it."
                }
            } else {
                when {
                    totalTracks > 0 && ratedTrackCount > 0 ->
                        "Rated $ratedTrackCount/$totalTracks songs. Shape it into an album memory."
                    noteCount >= 2 ->
                        "$noteCount notes are waiting to become an album memory."
                    else ->
                        "There is enough signal to shape this album into a memory."
                }
            },
        )
    }

    // Warm, relative recall phrase for a formed memory's last touch. Null when
    // the candidate has no usable timestamp (e.g. a review with no recorded play).
    private fun recallTimePhrase(epochMillis: Long?): String? {
        if (epochMillis == null || epochMillis <= 0L) return null
        val days = ((System.currentTimeMillis() - epochMillis).coerceAtLeast(0L)) / 86_400_000L
        return when {
            days <= 0L -> "earlier today"
            days == 1L -> "yesterday"
            days < 7L -> "$days days ago"
            days < 14L -> "last week"
            days < 60L -> "${days / 7} weeks ago"
            days < 365L -> "${(days / 30).coerceAtLeast(1)} months ago"
            else -> "${(days / 365).coerceAtLeast(1)} years ago"
        }
    }

    private fun buildJumpBackInBatch(
        albumCandidates: List<Album>,
        songCandidates: List<Track>,
        artistCandidates: List<Artist>,
        existingIds: Set<String>,
        batchSize: Int,
        shuffleCandidates: Boolean = true,
    ): List<HomeJumpBackInItem> {
        val orderedAlbums = if (shuffleCandidates) albumCandidates.shuffled() else albumCandidates
        val orderedSongs = if (shuffleCandidates) songCandidates.shuffled() else songCandidates
        val orderedArtists = if (shuffleCandidates) artistCandidates.shuffled() else artistCandidates

        val albumItems = orderedAlbums
            .map { HomeJumpBackInItem.AlbumItem(it) }
            .distinctBy { it.stableId }
            .filterNot { it.stableId in existingIds }
            .take(batchSize)

        val songItems = orderedSongs
            .map { HomeJumpBackInItem.SongItem(it) }
            .distinctBy { it.stableId }
            .filterNot { it.stableId in existingIds }
            .take(batchSize)

        val artistItems = orderedArtists
            .map { HomeJumpBackInItem.ArtistItem(it) }
            .distinctBy { it.stableId }
            .filterNot { it.stableId in existingIds }
            .take(batchSize)

        val result = mutableListOf<HomeJumpBackInItem>()
        val albumsIterator = albumItems.iterator()
        val songsIterator = songItems.iterator()
        val artistsIterator = artistItems.iterator()

        while (result.size < batchSize) {
            var appended = false
            if (albumsIterator.hasNext()) {
                result += albumsIterator.next()
                appended = true
            }
            if (result.size < batchSize && albumsIterator.hasNext()) {
                result += albumsIterator.next()
                appended = true
            }
            if (result.size < batchSize && songsIterator.hasNext()) {
                result += songsIterator.next()
                appended = true
            }
            if (result.size < batchSize && songsIterator.hasNext()) {
                result += songsIterator.next()
                appended = true
            }
            if (result.size < batchSize && artistsIterator.hasNext()) {
                result += artistsIterator.next()
                appended = true
            }
            if (!appended) {
                break
            }
        }

        while (result.size < batchSize) {
            val nextItem = when {
                albumsIterator.hasNext() -> albumsIterator.next()
                songsIterator.hasNext() -> songsIterator.next()
                artistsIterator.hasNext() -> artistsIterator.next()
                else -> break
            }
            result += nextItem
        }

        return result
            .distinctBy { it.stableId }
            .take(batchSize)
    }

    private fun warmArtistPool() {
        if (artistPool.isNotEmpty() || artistPoolWarmupJob?.isActive == true) return

        artistPoolWarmupJob = viewModelScope.launch {
            try {
                artistPool = loadArtistsFlat()
            } catch (_: Exception) {
                // Skip artist warmup. Albums and songs are enough for the home feed.
            } finally {
                artistPoolWarmupJob = null
            }
        }
    }

    private suspend fun loadArtistsFlat(): List<Artist> {
        val indices: List<ArtistIndex> = repository.getArtists()
        return indices.flatMap { it.artists }
    }

    private fun matchesCurrentScope(
        providerId: String?,
        profileId: String?,
    ): Boolean = repository.currentProviderId() == providerId &&
        activeProfileId.value == profileId

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(
                repository = container.repository,
                activeProfileId = container.profileManager.activeProfileId,
            ) as T
    }

    private companion object {
        // 3-column grid × 3 rows = 9 Jump Back In cards, fixed. The section
        // used to be 18 (six rows) which dominated the feed for the little
        // signal it carried; three rows is enough of a "pick up where you
        // left off" nudge without pushing everything else off-screen. The
        // candidate request/cache pools stay larger so pull-to-reshuffle
        // (refreshJumpBackIn) still has fresh material to draw from. No paged
        // "load more" — the previous bottom-scroll pagination fired a network
        // batch per scroll and the palette-extracting render cost piled up
        // until the list fought for frames.
        private const val JUMP_BACK_IN_FIXED_COUNT = 9
        private const val JUMP_BACK_IN_ALBUM_REQUEST_SIZE = 18
        private const val JUMP_BACK_IN_SONG_REQUEST_SIZE = 18
        private const val SpotifyHomeCacheCandidateCount = 18
        private const val SpotifyHomeCacheTtlMillis = 60L * 60L * 1000L

        // Rated-coverage at/above which an album reads as a "formed" memory in
        // the home teaser, matching the original Memory recommendation rule.
        private const val MEMORY_FORMED_COVERAGE = 0.6f
        private val homeContentCache = mutableMapOf<String, HomeUiState.Content>()

        private fun homeScopeKey(providerId: String?, profileId: String?): String =
            "${providerId.orEmpty()}|${profileId.orEmpty()}"
    }
}
