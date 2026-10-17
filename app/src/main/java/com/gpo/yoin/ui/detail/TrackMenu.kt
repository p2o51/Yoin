package com.gpo.yoin.ui.detail

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.gpo.yoin.AppContainer
import com.gpo.yoin.R
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.model.isUnplayableAppleImport
import com.gpo.yoin.data.source.WebLinkKind
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.PlayMenuItem
import com.gpo.yoin.ui.component.PlayMenuItemPadding
import com.gpo.yoin.ui.component.PlayMenuTextStyle
import com.gpo.yoin.ui.component.YoinDropdownMenuItem
import com.gpo.yoin.ui.component.YoinSectionedDropdownMenu
import com.gpo.yoin.ui.theme.YoinMotion

/**
 * What a song's long-press menu can reach, built once by its window (owner,
 * 2026-10-17 launch sprint; Spotify's song menu as the reference). [trackFor]
 * looks a song up when its menu opens; the go-to callbacks are the window's
 * own navigation, null where the menu should leave the row out (an album row
 * has no Go to album — it is that album).
 */
@Stable
class TrackMenuHost(
    val container: AppContainer,
    val trackFor: (songId: String) -> Track?,
    val onMessage: (String) -> Unit,
    val onAddToPlaylist: ((List<MediaId>) -> Unit)?,
    val onGoToAlbum: ((albumId: String) -> Unit)? = null,
    val onGoToArtist: ((artistId: String) -> Unit)? = null,
)

/**
 * A song's long-press menu, in sections: Play next, Add to queue, Add to
 * playlist (the detail ▾ rows for one song, [rememberDetailMenu], so the
 * provider rules and the snackbar lines are the same); then Go to album, Go to
 * artist, Share and Open in …; then [playerSection] when the host has player
 * settings (Now Playing's Keep screen on). A row the song or provider can't do
 * is left out, and so is a section left empty.
 */
@Composable
internal fun TrackMenu(
    songId: String,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    host: TrackMenuHost,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    playerSection: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val context = LocalContext.current
    // Read when the menu opens, so it is the song as it is now.
    val track = remember(songId, expanded) { host.trackFor(songId) }
    val link = rememberDetailWebLink(host.container, WebLinkKind.Track, songId.takeIf { expanded })
    val menu = rememberDetailMenu(
        container = host.container,
        link = link,
        provider = MediaId.parseOrNull(songId)?.provider,
        tracks = { listOfNotNull(track) },
        onMessage = host.onMessage,
        onAddToPlaylist = host.onAddToPlaylist,
        queueAvailable = track != null && !track.isUnplayableAppleImport,
    )
    val dismiss = onDismissRequest
    val goToAlbum = host.onGoToAlbum?.let { go -> track?.albumId?.let { id -> { go(id.toString()) } } }
    val goToArtist = host.onGoToArtist?.let { go -> track?.artistId?.let { id -> { go(id.toString()) } } }
    val share: (() -> Unit)? = track?.let { song ->
        {
            val title = listOfNotNull(song.title, song.artist).joinToString(" — ")
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, detailShareText(title, link))
            }
            runCatching { context.startActivity(Intent.createChooser(send, null)) }
        }
    }
    val queueRows = listOfNotNull(menu.onPlayNext, menu.onAddToQueue, menu.onAddToPlaylist)
    val openIn = menu.onOpenIn.takeIf { menu.openInLabel != null }
    val goRows = listOfNotNull(goToAlbum, goToArtist, share, openIn)
    val sections = buildList<@Composable ColumnScope.() -> Unit> {
        if (queueRows.isNotEmpty()) {
            add {
                menu.onPlayNext?.let {
                    PlayMenuItem(stringResource(R.string.detail_menu_play_next), YoinSymbols.SkipNext, dismiss, it)
                }
                menu.onAddToQueue?.let {
                    PlayMenuItem(stringResource(R.string.detail_menu_add_to_queue), YoinSymbols.Queue, dismiss, it)
                }
                menu.onAddToPlaylist?.let {
                    PlayMenuItem(stringResource(R.string.detail_menu_add_to_playlist), YoinSymbols.Playlist, dismiss, it)
                }
            }
        }
        if (goRows.isNotEmpty()) {
            add {
                goToAlbum?.let {
                    PlayMenuItem(stringResource(R.string.track_menu_go_to_album), YoinSymbols.Album, dismiss, it)
                }
                goToArtist?.let {
                    PlayMenuItem(stringResource(R.string.track_menu_go_to_artist), YoinSymbols.Artist, dismiss, it)
                }
                share?.let {
                    PlayMenuItem(stringResource(R.string.track_menu_share), YoinSymbols.Share, dismiss, it)
                }
                if (openIn != null && menu.openInLabel != null) {
                    PlayMenuItem(menu.openInLabel, YoinSymbols.Launch, dismiss, openIn)
                }
            }
        }
        if (playerSection != null) add(playerSection)
    }
    YoinSectionedDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        sections = sections,
        modifier = modifier,
        offset = offset,
    )
}

/**
 * A menu row that switches a setting and stays open: the trailing check
 * fades in when [checked], the leading symbol takes the primary colour, both
 * on the effects spring.
 */
@Composable
internal fun TrackMenuToggleItem(
    text: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val tint by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "trackMenuToggleTint",
    )
    val checkAlpha by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "trackMenuToggleCheck",
    )
    YoinDropdownMenuItem(
        text = text,
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
        leadingIcon = { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) },
        trailingIcon = {
            Icon(
                YoinSymbols.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp).alpha(checkAlpha),
            )
        },
        textStyle = PlayMenuTextStyle,
        contentPadding = PlayMenuItemPadding,
    )
}
