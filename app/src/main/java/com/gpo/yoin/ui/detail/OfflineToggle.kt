package com.gpo.yoin.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.gpo.yoin.AppContainer
import com.gpo.yoin.R
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.offline.OfflineCollection
import com.gpo.yoin.data.offline.OfflineStatus
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.symbols.YoinSymbols

/**
 * The Download / Remove download row of an album's ▾ menu, a playlist's ⋮
 * menu and a song's sheet: what it says now ([label], [icon]) and what a tap
 * does. Downloading, a tap removes what came so far.
 */
@Immutable
class OfflineToggle internal constructor(
    val status: OfflineStatus,
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * [collection]'s toggle, or null where the active account can't download
 * (Spotify, Apple Music) or the page hasn't loaded what to download.
 * [tracks] is read on tap. [onMessage] says what was queued or removed.
 */
@Composable
internal fun rememberOfflineToggle(
    container: AppContainer,
    collection: OfflineCollection?,
    tracks: suspend () -> List<Track>,
    onMessage: (String) -> Unit,
): OfflineToggle? {
    if (collection == null) return null
    if (Capability.OFFLINE_DOWNLOAD !in container.repository.currentCapabilities()) return null
    val resources = LocalContext.current.resources
    val downloads = container.offlineDownloads
    val library by downloads.library.collectAsState()
    val status = library.statusOf(collection.key)
    val latestTracks by rememberUpdatedState(tracks)
    val latestOnMessage by rememberUpdatedState(onMessage)
    return remember(collection, status, resources) {
        val (label, icon) = when (status) {
            OfflineStatus.None -> resources.getString(R.string.offline_download) to YoinSymbols.Download
            is OfflineStatus.Downloading ->
                resources.getString(R.string.offline_downloading, status.done, status.total) to YoinSymbols.Close
            OfflineStatus.Downloaded -> resources.getString(R.string.offline_remove_download) to YoinSymbols.DownloadDone
            OfflineStatus.Failed -> resources.getString(R.string.offline_retry) to YoinSymbols.Download
        }
        OfflineToggle(status = status, label = label, icon = icon) {
            when (status) {
                OfflineStatus.None, OfflineStatus.Failed -> downloads.downloadLater(
                    load = { latestTracks() },
                    collection = collection,
                ) { queued ->
                    if (queued > 0) {
                        val message = if (downloads.waitsForWifi()) {
                            R.string.offline_queued_waiting_wifi
                        } else {
                            R.string.offline_queued
                        }
                        latestOnMessage(resources.getString(message, queued))
                    }
                }
                is OfflineStatus.Downloading, OfflineStatus.Downloaded -> downloads.removeLater(collection.key) {
                    latestOnMessage(resources.getString(R.string.offline_removed))
                }
            }
        }
    }
}

/** An album page's download: the album as the listener sees it. */
internal fun albumOfflineCollection(content: AlbumDetailUiState.Content?): OfflineCollection? = content?.let {
    OfflineCollection(
        kind = OfflineCollection.Kind.Album,
        id = it.albumId,
        title = it.albumName,
        subtitle = it.artistName,
        cover = it.coverArtId,
    )
}

/** A playlist page's download. [coverKey] is a CoverRef storage key, never a signed url. */
internal fun playlistOfflineCollection(content: PlaylistDetailUiState.Content?, coverKey: String?): OfflineCollection? =
    content?.let {
        OfflineCollection(
            kind = OfflineCollection.Kind.Playlist,
            id = it.playlistId,
            title = it.playlistName,
            subtitle = it.owner,
            cover = coverKey?.takeUnless { key -> key.startsWith("http") },
        )
    }

/** A song downloaded on its own, from its sheet. */
internal fun songOfflineCollection(track: Track?): OfflineCollection? = track?.let {
    OfflineCollection(
        kind = OfflineCollection.Kind.Song,
        id = it.id.toString(),
        title = it.title.orEmpty(),
        subtitle = it.artist,
        cover = CoverRef.toStorageKey(it.coverArt)?.takeUnless { key -> key.startsWith("http") },
    )
}
