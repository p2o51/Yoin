package com.gpo.yoin.ui.detail

import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.gpo.yoin.AppContainer
import com.gpo.yoin.R
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.model.isUnplayableAppleImport
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.WebLinkKind
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.PlayMenuItem
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlinx.coroutines.launch

/**
 * What a detail page's ▾ menu offers after Shuffle play (owner F1,
 * 2026-10-05: the split button's functions, implemented) — the same rows on
 * the window pages and in the shell's detail column; each host builds one
 * with [rememberDetailMenu]. A null action leaves its row out.
 */
@Immutable
class DetailMenu internal constructor(
    val onPlayNext: (() -> Unit)? = null,
    val onAddToQueue: (() -> Unit)? = null,
    val onAddToPlaylist: (() -> Unit)? = null,
    /** "Open in Spotify" / "Open in Apple Music" ([openInLabel]). */
    val openInLabel: String? = null,
    val onOpenIn: (() -> Unit)? = null,
    /**
     * Whether the page's album is in the library: "Save to library" or
     * "Remove from library", which [onToggleLibrary] does. Null leaves the
     * row out: only a Spotify album page has one, and only once it is known
     * whether the album is saved.
     */
    val inLibrary: Boolean? = null,
    val onToggleLibrary: (() -> Unit)? = null,
)

/** [menu]'s rows, in the Play menu's own look. */
@Composable
internal fun ColumnScope.DetailMenuRows(menu: DetailMenu, dismissMenu: () -> Unit) {
    menu.onPlayNext?.let {
        PlayMenuItem(stringResource(R.string.detail_menu_play_next), YoinSymbols.SkipNext, dismissMenu, it)
    }
    menu.onAddToQueue?.let {
        PlayMenuItem(stringResource(R.string.detail_menu_add_to_queue), YoinSymbols.Queue, dismissMenu, it)
    }
    menu.onAddToPlaylist?.let {
        PlayMenuItem(stringResource(R.string.detail_menu_add_to_playlist), YoinSymbols.Playlist, dismissMenu, it)
    }
    val inLibrary = menu.inLibrary
    // The row waits for the album's saved state, which may land while the menu
    // is open: it grows in then. It keeps the state it last showed while it leaves.
    val shownInLibrary = remember { mutableStateOf(false) }.apply { if (inLibrary != null) value = inLibrary }
    AnimatedVisibility(
        visible = inLibrary != null && menu.onToggleLibrary != null,
        enter = expandVertically(YoinMotion.spatialSpring(), expandFrom = Alignment.Top) +
            YoinMotion.fadeIn(role = YoinMotionRole.Standard),
        exit = shrinkVertically(YoinMotion.spatialSpring(), shrinkTowards = Alignment.Top) +
            YoinMotion.fadeOut(role = YoinMotionRole.Standard)
    ) {
        val saved = shownInLibrary.value
        val label = if (saved) R.string.detail_menu_remove_from_library else R.string.detail_menu_save_to_library
        val icon = if (saved) YoinSymbols.LibraryAdded else YoinSymbols.LibraryAdd
        PlayMenuItem(stringResource(label), icon, dismissMenu) { menu.onToggleLibrary?.invoke() }
    }
    val openIn = menu.onOpenIn
    if (menu.openInLabel != null && openIn != null) {
        PlayMenuItem(menu.openInLabel, YoinSymbols.Launch, dismissMenu, openIn)
    }
}

/** The page's public link ([com.gpo.yoin.data.source.MusicMetadata.webUrl]); null until resolved, or without one. */
@Composable
internal fun rememberDetailWebLink(container: AppContainer, kind: WebLinkKind, id: String?): String? {
    val link by produceState<String?>(initialValue = null, container, kind, id) {
        val mediaId = id?.let(MediaId::parseOrNull) ?: return@produceState
        value = container.repository.webUrl(kind, mediaId)
    }
    return link
}

/** "Open in Spotify" / "Open in Apple Music" for [link]'s host; null for any other link. */
internal fun openInLabel(link: String?, resources: Resources? = null): String? = when {
    link == null -> null
    link.startsWith("https://open.spotify.com/") ->
        resources?.getString(R.string.detail_menu_open_spotify)
            ?: "Open in Spotify" // i18n-allow: DetailMenuTest asserts this English
    link.startsWith("https://music.apple.com/") ->
        resources?.getString(R.string.detail_menu_open_apple_music)
            ?: "Open in Apple Music" // i18n-allow: DetailMenuTest asserts this English
    else -> null
}

/** What Share sends: the page's [title], with its [link] on the next line when it has one. */
internal fun detailShareText(title: String, link: String?): String = if (link == null) title else "$title\n$link"

/** The snackbar line after a page's songs were queued. */
internal fun queuedMessage(count: Int, next: Boolean, resources: Resources? = null): String {
    if (resources == null) {
        val songs = if (count == 1) {
            "1 song" // i18n-allow: DetailMenuTest asserts this English
        } else {
            "$count songs" // i18n-allow: DetailMenuTest asserts this English
        }
        return if (next) {
            "$songs will play next" // i18n-allow: DetailMenuTest asserts this English
        } else {
            "Added $songs to the queue" // i18n-allow: DetailMenuTest asserts this English
        }
    }
    val id = if (next) R.plurals.detail_menu_play_next_count else R.plurals.detail_menu_queued
    return resources.getQuantityString(id, count, count)
}

/**
 * The ▾ actions on a page whose songs are [tracks] (read when a row is
 * tapped, so they are the page's current list): queue them — Play next is
 * left out on Spotify, whose one queue already plays before the context
 * resumes — hand their ids to [onAddToPlaylist] (the window's add-to-playlist
 * sheet; only where the provider writes playlists), and open [link] in the
 * provider's app. [onMessage] confirms on the window's snackbar. An album
 * page whose service saves albums passes [inLibrary] (null: no row) and
 * [onToggleLibrary].
 */
@Composable
internal fun rememberDetailMenu(
    container: AppContainer,
    link: String?,
    provider: String?,
    tracks: suspend () -> List<Track>,
    onMessage: (String) -> Unit,
    onAddToPlaylist: ((List<MediaId>) -> Unit)?,
    inLibrary: Boolean? = null,
    onToggleLibrary: () -> Unit = {},
): DetailMenu {
    val context = LocalContext.current
    val resources = context.resources
    val scope = rememberCoroutineScope()
    val latestTracks by rememberUpdatedState(tracks)
    val latestOnMessage by rememberUpdatedState(onMessage)
    val latestOnAddToPlaylist by rememberUpdatedState(onAddToPlaylist)
    val latestOnToggleLibrary by rememberUpdatedState(onToggleLibrary)
    val canAddToPlaylist = onAddToPlaylist != null &&
        Capability.PLAYLISTS_WRITE in container.repository.currentCapabilities()
    return remember(container, link, provider, canAddToPlaylist, inLibrary) {
        fun queue(next: Boolean) {
            scope.launch {
                // addToQueue leaves Apple Music imports out: so does the count.
                val songs = latestTracks().filterNot { it.isUnplayableAppleImport }
                val source = container.profileManager.activeSource.value ?: return@launch
                if (songs.isEmpty()) return@launch
                container.playbackManager.addToQueue(songs, source, next)
                latestOnMessage(queuedMessage(songs.size, next, resources))
            }
        }
        DetailMenu(
            onPlayNext = if (provider != MediaId.PROVIDER_SPOTIFY) ({ queue(next = true) }) else null,
            onAddToQueue = { queue(next = false) },
            onAddToPlaylist = if (canAddToPlaylist) {
                {
                    scope.launch {
                        val ids = latestTracks().map(Track::id)
                        if (ids.isNotEmpty()) latestOnAddToPlaylist?.invoke(ids)
                    }
                }
            } else {
                null
            },
            openInLabel = openInLabel(link, resources),
            onOpenIn = link?.let { url ->
                { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
            },
            inLibrary = inLibrary,
            onToggleLibrary = inLibrary?.let { { latestOnToggleLibrary() } },
        )
    }
}
