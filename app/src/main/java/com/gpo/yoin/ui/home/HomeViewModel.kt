package com.gpo.yoin.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.Capability
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
            val cachedSpotifyContent = if (
                providerId == MediaId.PROVIDER_SPOTIFY &&
                !profileId.isNullOrBlank()
            ) {
                loadCachedSpotifyHomeContent(profileId)
            } else {
                null
            }

            _uiState.value = cachedSpotifyContent ?: HomeUiState.Loading

            try {
                val freshContent = when {
                    providerId == MediaId.PROVIDER_SPOTIFY && !profileId.isNullOrBlank() ->
                        loadSpotifyHomeContent(profileId)

                    else -> loadHomeContent()
                }
                if (!matchesCurrentScope(providerId, profileId)) return@launch
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

            HomeUiState.Content(
                activities = activitiesDeferred.await(),
                jumpBackInItems = jumpBackInDeferred.await(),
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

        if (activities.isEmpty() && jumpBackInItems.isEmpty()) {
            null
        } else {
            HomeUiState.Content(
                activities = activities,
                jumpBackInItems = jumpBackInItems,
            )
        }
    }

    private suspend fun loadSpotifyHomeContent(
        profileId: String,
    ): HomeUiState.Content = coroutineScope {
        val activitiesDeferred = async {
            repository.getRecentActivities(limit = 20).first()
        }
        val albumDeferred = async {
            repository.getAlbumList("random", size = JUMP_BACK_IN_ALBUM_REQUEST_SIZE)
        }
        val artistsDeferred = async {
            loadArtistsFlat()
        }

        val activities = activitiesDeferred.await()
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
            jumpBackInItems = buildJumpBackInBatch(
                albumCandidates = shuffledAlbums,
                songCandidates = emptyList(),
                artistCandidates = shuffledArtists,
                existingIds = emptySet(),
                batchSize = JUMP_BACK_IN_FIXED_COUNT,
                shuffleCandidates = false,
            ),
        )
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
            repository.getRecentActivities(limit = 20).collectLatest { activities ->
                val currentContent = _uiState.value as? HomeUiState.Content ?: return@collectLatest
                _uiState.value = currentContent.copy(activities = activities)
            }
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
        // 3-column grid × 6 rows = 18 Jump Back In cards, fixed. No paged
        // "load more" — previous implementation fired a new network batch
        // each time the user scrolled near the bottom, and the palette-
        // extracting render cost piled up so fast that the whole list
        // felt like it was fighting for frames. A fixed recommendation
        // block is enough signal for the user; refreshJumpBackIn() is
        // still wired through for pull-to-reshuffle.
        private const val JUMP_BACK_IN_FIXED_COUNT = 18
        private const val JUMP_BACK_IN_ALBUM_REQUEST_SIZE = 18
        private const val JUMP_BACK_IN_SONG_REQUEST_SIZE = 18
        private const val SpotifyHomeCacheCandidateCount = 18
        private const val SpotifyHomeCacheTtlMillis = 60L * 60L * 1000L
    }
}
