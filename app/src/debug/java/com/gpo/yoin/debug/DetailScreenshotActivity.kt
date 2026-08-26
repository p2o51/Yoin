package com.gpo.yoin.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.ui.detail.AlbumDetailScreen
import com.gpo.yoin.ui.detail.AlbumDetailUiState
import com.gpo.yoin.ui.detail.AlbumSong
import com.gpo.yoin.ui.detail.DetailMiniPlayerState
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.rememberYoinWindowInfo
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * Debug-only launcher for visual QA. Not exported in release builds.
 */
class DetailScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        setContent {
            // 对齐生产环境：QA 台也提供窗口信息，Medium/Wide 分支才可验。
            val windowInfo = rememberYoinWindowInfo()
            CompositionLocalProvider(LocalYoinWindowInfo provides windowInfo) {
            YoinTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                AlbumDetailScreen(
                    uiState = AlbumDetailUiState.Content(
                        albumId = "album-1",
                        albumName = "Describe",
                        artistName = "Hannah Jadagu",
                        artistId = "artist-1",
                        coverArtId = "cover-1",
                        coverArtUrl = null,
                        year = 2025,
                        songCount = 6,
                        totalDuration = 2040,
                        songs = listOf(
                            AlbumSong("1", "ALDEBARAN", "Artist", 1, 340, false),
                            AlbumSong("2", "Cherries & Cream", "Artist", 2, 340, false),
                            AlbumSong("3", "Falling", "Artist", 3, 340, false),
                            AlbumSong("4", "The Make Believe", "Artist", 4, 340, false),
                            AlbumSong("5", "Emperor with an Egg", "Artist", 5, 340, false),
                            AlbumSong("6", "The River", "Artist", 6, 340, false),
                        ),
                        averageTrackRating = null,
                        ratedTrackCount = 0,
                        lastPlayedAt = System.currentTimeMillis() - 86_400_000L,
                        userReview = "我爱它我爱它我爱它",
                    ),
                    onBackClick = { finish() },
                    onSongClick = {},
                    onToggleStar = {},
                    onRetry = {},
                    isPlaying = true,
                    playbackSignal = 0.35f,
                    // Fake pill state so the bottom bar can be QA'd without playback.
                    miniPlayerState = DetailMiniPlayerState(
                        trackId = "qa-fake-track",
                        title = "Cherries & Cream",
                        artist = "Hannah Jadagu",
                        coverArtUrl = null,
                        isPlaying = true,
                    ),
                    playbackProgress = 0.37f,
                )
                }
            }
            }
        }
    }
}
