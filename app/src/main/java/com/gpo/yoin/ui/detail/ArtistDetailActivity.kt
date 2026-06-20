package com.gpo.yoin.ui.detail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.enableYoinEdgeToEdge
import kotlinx.coroutines.launch

/**
 * Standalone Activity for an artist detail page. Opening an album from here
 * launches [AlbumDetailActivity] as another Activity, so each hop gets the
 * device-native cross-Activity predictive back.
 */
class ArtistDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val artistId = intent.getStringExtra(EXTRA_ARTIST_ID)
        if (artistId.isNullOrBlank()) {
            finish()
            return
        }
        setContent {
            YoinActivityRoot {
                val context = LocalContext.current
                val app = context.applicationContext as YoinApplication
                val scope = rememberCoroutineScope()
                val viewModel: ArtistDetailViewModel = viewModel(
                    factory = ArtistDetailViewModel.Factory(artistId, app.container),
                )
                val uiState by viewModel.uiState.collectAsState()

                fun playArtist(shuffle: Boolean) {
                    scope.launch {
                        val tracks = viewModel.getAllTracks()
                        if (tracks.isEmpty()) return@launch
                        val ordered = if (shuffle) tracks.shuffled() else tracks
                        app.container.profileManager.activeSource.value?.let { source ->
                            app.container.playbackManager.play(
                                tracks = ordered,
                                startIndex = 0,
                                source = source,
                                activityContext = ActivityContext.None,
                            )
                        }
                    }
                }

                ArtistDetailScreen(
                    uiState = uiState,
                    onBackClick = { finish() },
                    onAlbumClick = { albumId ->
                        context.startActivity(AlbumDetailActivity.intent(context, albumId))
                    },
                    onRetry = viewModel::retry,
                    onToggleFollow = viewModel::toggleFollow,
                    onPlay = { playArtist(shuffle = false) },
                    onShuffle = { playArtist(shuffle = true) },
                    onShare = {
                        val text = (uiState as? ArtistDetailUiState.Content)?.artistName
                            ?: "Check out this artist"
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    companion object {
        private const val EXTRA_ARTIST_ID = "artistId"

        fun intent(context: Context, artistId: String): Intent =
            Intent(context, ArtistDetailActivity::class.java)
                .putExtra(EXTRA_ARTIST_ID, artistId)
    }
}
