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
            if (_uiState.value !is HomeUiState.Content) {
                _uiState.value = HomeUiState.Loading
            }
            try {
                _uiState.value = loadHomeContent()
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
                _uiState.value = currentContent.copy(
                    jumpBackInItems = loadJumpBackInItems(
                        existingIds = emptySet(),
                        batchSize = currentContent.jumpBackInItems
                            .size
                            .coerceAtLeast(INITIAL_JUMP_BACK_IN_BATCH_SIZE),
                    ),
                )
                warmArtistPool()
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
                val nextBatch = loadJumpBackInItems(
                    existingIds = currentContent.jumpBackInItems.mapTo(mutableSetOf()) { it.stableId },
                    batchSize = JUMP_BACK_IN_BATCH_SIZE,
                )
                val latest = _uiState.value as? HomeUiState.Content ?: return@launch
                _uiState.value = latest.copy(
                    jumpBackInItems = latest.jumpBackInItems + nextBatch,
                    isLoadingMoreJumpBackIn = false,
                )
            } catch (_: Exception) {
                val latest = _uiState.value as? HomeUiState.Content ?: return@launch
                _uiState.value = latest.copy(isLoadingMoreJumpBackIn = false)
            } finally {
                isLoadingMoreJumpBackIn = false
            }
        }
    }

    fun buildCoverArtUrl(coverArtId: String): String =
        repository.buildCoverArtUrl(coverArtId, size = 320)

    private suspend fun loadHomeContent(): HomeUiState.Content =
        coroutineScope {
            val activitiesDeferred = async {
                repository.getRecentActivities(limit = 20).first()
            }
            val jumpBackInDeferred = async {
                loadJumpBackInItems(
                    existingIds = emptySet(),
                    batchSize = INITIAL_JUMP_BACK_IN_BATCH_SIZE,
                )
            }

            HomeUiState.Content(
                activities = activitiesDeferred.await(),
                jumpBackInItems = jumpBackInDeferred.await(),
            )
        }

    private suspend fun loadJumpBackInItems(
        existingIds: Set<String>,
        batchSize: Int,
    ): List<HomeJumpBackInItem> = coroutineScope {
        val albumDeferred = async {
            repository.getAlbumList("random", size = JUMP_BACK_IN_ALBUM_REQUEST_SIZE)
        }
        val songDeferred = async {
            repository.getRandomSongs(size = JUMP_BACK_IN_SONG_REQUEST_SIZE)
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
            .take(batchSize)

        val songItems = songCandidates
            .shuffled()
            .map { HomeJumpBackInItem.SongItem(it) }
            .distinctBy { it.stableId }
            .filterNot { it.stableId in existingIds }
            .take(batchSize)

        val artistItems = artistCandidates
            .shuffled()
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
        private const val JUMP_BACK_IN_ALBUM_REQUEST_SIZE = 18
        private const val JUMP_BACK_IN_SONG_REQUEST_SIZE = 18
    }
}
