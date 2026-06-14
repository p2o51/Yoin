package com.gpo.yoin.ui.detail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.enableYoinEdgeToEdge

/**
 * Standalone Activity for an album detail page. Rendered as its own Activity
 * (not a NavDisplay route) so back navigation plays the device-native
 * cross-Activity predictive back animation — we register NO consuming back
 * callback and never call overrideActivityTransition, so the system draws it.
 */
class AlbumDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val albumId = intent.getStringExtra(EXTRA_ALBUM_ID)
        if (albumId.isNullOrBlank()) {
            finish()
            return
        }
        setContent {
            YoinActivityRoot {
                val context = LocalContext.current
                val app = context.applicationContext as YoinApplication
                val viewModel: AlbumDetailViewModel = viewModel(
                    factory = AlbumDetailViewModel.Factory(albumId, app.container),
                )
                val uiState by viewModel.uiState.collectAsState()
                val notedSongIds by viewModel.notedSongIds.collectAsState()
                val expandedSongId by viewModel.expandedSongId.collectAsState()
                val expandedNoteBundle by viewModel.expandedNoteBundle.collectAsState()
                AlbumDetailScreen(
                    uiState = uiState,
                    sharedTransitionKey = null,
                    onBackClick = { finish() },
                    onSongClick = { songId ->
                        val songs = viewModel.getAlbumSongs()
                        val index = songs.indexOfFirst { it.id.toString() == songId }
                            .coerceAtLeast(0)
                        val activityContext = (uiState as? AlbumDetailUiState.Content)?.let { content ->
                            ActivityContext.Album(
                                albumId = content.albumId,
                                albumName = content.albumName,
                                artistName = content.artistName,
                                artistId = content.artistId,
                                coverArtId = content.coverArtId,
                            )
                        } ?: ActivityContext.None
                        app.container.profileManager.activeSource.value?.let { source ->
                            app.container.playbackManager.play(
                                tracks = songs,
                                startIndex = index,
                                source = source,
                                activityContext = activityContext,
                            )
                        }
                    },
                    onToggleStar = viewModel::toggleStar,
                    notedSongIds = notedSongIds,
                    expandedSongId = expandedSongId,
                    expandedNoteBundle = expandedNoteBundle,
                    onToggleExpandedSong = viewModel::toggleExpandedSong,
                    onRatingCommit = viewModel::setUserRating,
                    onReviewDraftChange = viewModel::onReviewDraftChange,
                    onSaveReview = viewModel::saveUserReview,
                    onRetry = viewModel::retry,
                    sharedTransitionScope = null,
                    animatedVisibilityScope = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    companion object {
        private const val EXTRA_ALBUM_ID = "albumId"

        fun intent(context: Context, albumId: String): Intent =
            Intent(context, AlbumDetailActivity::class.java)
                .putExtra(EXTRA_ALBUM_ID, albumId)
    }
}
