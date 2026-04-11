package com.gpo.yoin.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.remote.Album
import com.gpo.yoin.data.remote.Artist
import com.gpo.yoin.data.remote.ArtistIndex
import com.gpo.yoin.data.remote.Song
import com.gpo.yoin.data.repository.YoinRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: YoinRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var artistPool: List<Artist> = emptyList()
    private var artistPoolWarmupJob: Job? = null
    private var isLoadingMoreJumpBackIn = false

    init {
        refresh()
        observeRecentHistory()
    }

    fun refresh() {
        viewModelScope.launch {
            val previousContent = _uiState.value as? HomeUiState.Content
            if (previousContent == null) {
                _uiState.value = HomeUiState.Loading
            }
            try {
                _uiState.value = loadHomeContent(
                    previousRevision = previousContent?.jumpBackInRevision ?: 0,
                )
                warmArtistPool()
            } catch (e: Exception) {
                _uiState.value = HomeUiState.Error(
                    e.message ?: "Failed to load home content",
                )
            }
        }
    }

    fun refreshJumpBackIn() {
        val currentContent = _uiState.value as? HomeUiState.Content ?: return
        viewModelScope.launch {
            try {
                coroutineScope {
                    val randomDeferred = async {
                        repository.getAlbumList("random", size = 24)
                    }
                    val quickPlayDeferred = async {
                        repository.getRandomSongs(size = 24)
                    }
                    val quickAlbumsDeferred = async {
                        repository.getAlbumList("random", size = 12)
                    }
                    val newestDeferred = async {
                        repository.getAlbumList("newest", size = 12)
                    }
                    val frequentDeferred = async {
                        repository.getAlbumList("frequent", size = 12)
                    }

                    val mixForYou = randomDeferred.await()
                    val quickPlaySongs = quickPlayDeferred.await()
                    val quickPlayAlbums = quickAlbumsDeferred.await()
                    val recentlyAdded = newestDeferred.await()
                    val mostPlayed = frequentDeferred.await()

                    _uiState.value = currentContent.copy(
                        mixForYou = mixForYou,
                        quickPlaySongs = quickPlaySongs,
                        quickPlayAlbums = quickPlayAlbums,
                        recentlyAdded = recentlyAdded,
                        mostPlayed = mostPlayed,
                        jumpBackInItems = buildJumpBackInBatch(
                            albumCandidates = mixForYou + quickPlayAlbums + recentlyAdded + mostPlayed,
                            songCandidates = quickPlaySongs,
                            artistCandidates = artistPool,
                            existingIds = emptySet(),
                            batchSize = JUMP_BACK_IN_BATCH_SIZE,
                        ),
                        jumpBackInRevision = currentContent.jumpBackInRevision + 1,
                    )
                    warmArtistPool()
                }
            } catch (_: Exception) {
                // Keep the current section stable if recommendation refresh fails.
            }
        }
    }

    fun loadMoreJumpBackIn() {
        val currentContent = _uiState.value as? HomeUiState.Content ?: return
        if (isLoadingMoreJumpBackIn) return
        isLoadingMoreJumpBackIn = true
        _uiState.value = currentContent.copy(isLoadingMoreJumpBackIn = true)
        viewModelScope.launch {
            try {
                coroutineScope {
                    val albumDeferred = async {
                        repository.getAlbumList("random", size = 24)
                    }
                    val songDeferred = async {
                        repository.getRandomSongs(size = 24)
                    }
                    val nextBatch = buildJumpBackInBatch(
                        albumCandidates = albumDeferred.await(),
                        songCandidates = songDeferred.await(),
                        artistCandidates = artistPool,
                        existingIds = currentContent.jumpBackInItems.mapTo(mutableSetOf()) { it.stableId },
                        batchSize = JUMP_BACK_IN_BATCH_SIZE,
                    )
                    val latest = _uiState.value as? HomeUiState.Content ?: return@coroutineScope
                    _uiState.value = latest.copy(
                        jumpBackInItems = latest.jumpBackInItems + nextBatch,
                        isLoadingMoreJumpBackIn = false,
                    )
                }
            } catch (_: Exception) {
                val latest = _uiState.value as? HomeUiState.Content ?: return@launch
                _uiState.value = latest.copy(isLoadingMoreJumpBackIn = false)
            } finally {
                isLoadingMoreJumpBackIn = false
            }
        }
    }

    fun buildCoverArtUrl(coverArtId: String): String =
        repository.buildCoverArtUrl(coverArtId, size = 480)

    private suspend fun loadHomeContent(previousRevision: Int): HomeUiState.Content =
        coroutineScope {
            val activitiesDeferred = async {
                repository.getRecentActivities(limit = 20).first()
            }
            val randomDeferred = async {
                repository.getAlbumList("random", size = 24)
            }
            val newestDeferred = async {
                repository.getAlbumList("newest", size = 12)
            }
            val frequentDeferred = async {
                repository.getAlbumList("frequent", size = 12)
            }
            val quickPlayDeferred = async {
                repository.getRandomSongs(size = 24)
            }
            val quickAlbumsDeferred = async {
                repository.getAlbumList("random", size = 12)
            }

            val activities = activitiesDeferred.await()
            val mixForYou = randomDeferred.await()
            val recentlyAdded = newestDeferred.await()
            val mostPlayed = frequentDeferred.await()
            val quickPlaySongs = quickPlayDeferred.await()
            val quickPlayAlbums = quickAlbumsDeferred.await()

            HomeUiState.Content(
                activities = activities,
                jumpBackInItems = buildJumpBackInBatch(
                    albumCandidates = mixForYou + quickPlayAlbums + recentlyAdded + mostPlayed,
                    songCandidates = quickPlaySongs,
                    artistCandidates = artistPool,
                    existingIds = emptySet(),
                    batchSize = INITIAL_JUMP_BACK_IN_BATCH_SIZE,
                ),
                recentlyAdded = recentlyAdded,
                mixForYou = mixForYou,
                mostPlayed = mostPlayed,
                quickPlaySongs = quickPlaySongs,
                quickPlayAlbums = quickPlayAlbums,
                jumpBackInRevision = previousRevision,
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

    private suspend fun buildJumpBackInBatch(
        albumCandidates: List<Album>,
        songCandidates: List<Song>,
        artistCandidates: List<Artist>,
        existingIds: Set<String>,
        batchSize: Int,
    ): List<HomeJumpBackInItem> {
        val albumItems = albumCandidates
            .shuffled()
            .map { HomeJumpBackInItem.AlbumItem(it) }
            .distinctBy { it.stableId }
            .filterNot { it.stableId in existingIds }
            .take(batchSize / 5 * 2)

        val songItems = songCandidates
            .shuffled()
            .map { HomeJumpBackInItem.SongItem(it) }
            .distinctBy { it.stableId }
            .filterNot { it.stableId in existingIds }
            .take(batchSize / 5 * 2)

        val artistItems = artistCandidates
            .shuffled()
            .map { HomeJumpBackInItem.ArtistItem(it) }
            .distinctBy { it.stableId }
            .filterNot { it.stableId in existingIds }
            .take(batchSize / 5)

        val result = mutableListOf<HomeJumpBackInItem>()
        val albumsIterator = albumItems.iterator()
        val songsIterator = songItems.iterator()
        val artistsIterator = artistItems.iterator()

        while (result.size < batchSize) {
            if (albumsIterator.hasNext()) result += albumsIterator.next()
            if (albumsIterator.hasNext()) result += albumsIterator.next()
            if (songsIterator.hasNext()) result += songsIterator.next()
            if (songsIterator.hasNext()) result += songsIterator.next()
            if (artistsIterator.hasNext()) result += artistsIterator.next()
            if (!albumsIterator.hasNext() && !songsIterator.hasNext() && !artistsIterator.hasNext()) {
                break
            }
        }
        return result.take(batchSize)
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
        return indices.flatMap { it.artist }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(container.repository) as T
    }

    private companion object {
        private const val INITIAL_JUMP_BACK_IN_BATCH_SIZE = 12
        private const val JUMP_BACK_IN_BATCH_SIZE = 18
    }
}
