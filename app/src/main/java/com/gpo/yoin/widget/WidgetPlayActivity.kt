package com.gpo.yoin.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.player.ConnectionPhase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The widget's play button lands here: an invisible Activity, so playback starts from a foreground context
 * (no background foreground-service start) and Spotify's App Remote — which only connects while a Yoin
 * Activity is started — has a host. It finishes once the requested track is actually playing; a failure or a
 * timeout opens the detail page. Artists have no single queue to resume, so they open the artist page instead.
 */
class WidgetPlayActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entity = intent.getStringExtra(WidgetIntents.EXTRA_ENTITY)?.let { runCatching { WidgetEntity.valueOf(it) }.getOrNull() }
        val id = MediaId.parseOrNull(intent.getStringExtra(WidgetIntents.EXTRA_ID))
        if (entity == null || id == null) {
            finishQuietly()
            return
        }
        if (entity == WidgetEntity.ARTIST) {
            startActivity(WidgetIntents.openDetail(this, entity, id))
            finishQuietly()
            return
        }
        lifecycleScope.launch {
            val started = startPlayback(entity, id)
            if (!started) startActivity(WidgetIntents.openDetail(this@WidgetPlayActivity, entity, id))
            finishQuietly()
        }
    }

    private suspend fun startPlayback(entity: WidgetEntity, id: MediaId): Boolean {
        val container = yoinContainer
        val repository = container.repository
        val source = repository.awaitActiveSource(SOURCE_TIMEOUT_MS) ?: return false
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE)?.takeIf { it.isNotBlank() }
        val (tracks: List<Track>, context: ActivityContext) = when (entity) {
            WidgetEntity.ALBUM -> {
                val album = runCatching { repository.getAlbum(id) }.getOrNull() ?: return false
                album.tracks to ActivityContext.Album(
                    albumId = id.toString(),
                    albumName = album.name.ifBlank { title },
                    artistName = album.artist ?: subtitle,
                    artistId = album.artistId?.toString(),
                    coverArtId = CoverRef.toStorageKey(album.coverArt),
                )
            }
            WidgetEntity.PLAYLIST -> {
                val playlist = runCatching { repository.getPlaylist(id) }.getOrNull() ?: return false
                playlist.tracks to ActivityContext.Playlist(
                    playlistId = id.toString(),
                    playlistName = playlist.name.ifBlank { title },
                    owner = playlist.owner?.takeIf { it.isNotBlank() },
                    coverArtId = CoverRef.toStorageKey(playlist.coverArt),
                )
            }
            WidgetEntity.ARTIST -> return false
        }
        if (tracks.isEmpty()) return false
        val playback = container.playbackManager
        val wanted = tracks.first().id
        playback.play(tracks = tracks, startIndex = 0, source = source, activityContext = context)
        // Stay (invisibly) until the REQUESTED track plays — Spotify needs this Activity as its App Remote host
        // meanwhile. Only states about that track count: something already playing (or an old error) must not
        // end the wait before the request lands. An error or a timeout falls back to the detail page.
        val outcome = withTimeoutOrNull(PLAY_TIMEOUT_MS) {
            playback.playbackState.first { state ->
                val aboutWanted = state.currentTrack?.id == wanted || state.pendingTrack?.id == wanted
                aboutWanted && (state.isPlaying || state.connectionPhase == ConnectionPhase.Error)
            }
        }
        return outcome != null && outcome.connectionPhase != ConnectionPhase.Error
    }

    private fun finishQuietly() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_TITLE = "widget_title"
        const val EXTRA_SUBTITLE = "widget_subtitle"
        private const val SOURCE_TIMEOUT_MS = 8_000L
        private const val PLAY_TIMEOUT_MS = 6_000L
    }
}
