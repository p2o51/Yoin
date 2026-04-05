package com.gpo.yoin.player

import androidx.media3.common.Player
import com.gpo.yoin.data.remote.Song

data class PlaybackState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val position: Long = 0L,
    val duration: Long = 0L,
    val bufferedPosition: Long = 0L,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleEnabled: Boolean = false,
    val audioSessionId: Int = 0,
    val isCasting: Boolean = false,
    val castDeviceName: String? = null,
    val controllerReady: Boolean = false,
    val connectionErrorMessage: String? = null,
)
