package com.gpo.yoin.ui.detail

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.yoin.AppContainer
import com.gpo.yoin.R
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.model.isUnplayableAppleImport
import com.gpo.yoin.data.source.WebLinkKind
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.YoinModalBottomSheet
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.settings.settingsSegmentShape
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import kotlinx.coroutines.launch

/**
 * What a song's long-press sheet can reach, built once by its window (owner,
 * 2026-10-17 launch sprint; Spotify's song sheet as the reference). [trackFor]
 * looks a song up when its sheet opens; the go-to callbacks are the window's
 * own navigation, null where the sheet should leave the row out (an album row
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
    /**
     * The album page's own "View in Scrapbook": move to page 2 at this song. Null elsewhere —
     * there the row opens the album ([onGoToAlbum]) with a [ScrapbookFocusRequest].
     */
    val onViewInScrapbook: ((songId: String) -> Unit)? = null,
) {
    /** This host on an album page whose Scrapbook can show [songId] in place. */
    fun withViewInScrapbook(view: (songId: String) -> Unit): TrackMenuHost = TrackMenuHost(
        container = container,
        trackFor = trackFor,
        onMessage = onMessage,
        onAddToPlaylist = onAddToPlaylist,
        onGoToAlbum = onGoToAlbum,
        onGoToArtist = onGoToArtist,
        onViewInScrapbook = view,
    )
}

/** One row of the sheet: a symbol and a label; [onClick] runs as the sheet goes down. */
private class TrackMenuRow(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * A song's long-press sheet, rising from the bottom (owner 2026-10-17: a
 * bottom sheet, not a dropdown): the song (cover, title, artist), then
 * Play next / Add to queue / Add to playlist — the detail ▾ rows for one song
 * ([rememberDetailMenu], so the provider rules and the snackbar lines are the
 * same) — then Go to album / View in Scrapbook / Go to artist / Share / Open in …, then
 * [playerSection] when the host has player settings (Now Playing's Keep screen
 * on). A row the song or provider can't do is left out, and so is an empty
 * group. Compose it only while open; [onDismissRequest] clears that. A row
 * acts at once and the sheet slides down; back, a tap on the scrim or a drag
 * down close it the M3 way ([YoinModalBottomSheet]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackMenuSheet(
    songId: String,
    onDismissRequest: () -> Unit,
    host: TrackMenuHost,
    playerSection: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val haptics = rememberYoinHaptics()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Read once as the sheet opens, so it is the song as it is now.
    val track = remember(songId) { host.trackFor(songId) }
    val coverUrl = remember(track) { track?.coverArt?.let { host.container.repository.resolveCoverUrl(it) } }
    val link = rememberDetailWebLink(host.container, WebLinkKind.Track, songId)
    val menu = rememberDetailMenu(
        container = host.container,
        link = link,
        provider = MediaId.parseOrNull(songId)?.provider,
        tracks = { listOfNotNull(track) },
        onMessage = host.onMessage,
        onAddToPlaylist = host.onAddToPlaylist,
        queueAvailable = track != null && !track.isUnplayableAppleImport,
    )
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismissRequest()
        }
    }
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
    val queueRows = listOfNotNull(
        menu.onPlayNext?.let { TrackMenuRow(stringResource(R.string.detail_menu_play_next), YoinSymbols.SkipNext, it) },
        menu.onAddToQueue?.let { TrackMenuRow(stringResource(R.string.detail_menu_add_to_queue), YoinSymbols.Queue, it) },
        menu.onAddToPlaylist?.let {
            TrackMenuRow(stringResource(R.string.detail_menu_add_to_playlist), YoinSymbols.Playlist, it)
        },
    )
    val goRows = listOfNotNull(
        host.onGoToAlbum?.let { go ->
            track?.albumId?.let { id ->
                TrackMenuRow(stringResource(R.string.track_menu_go_to_album), YoinSymbols.Album) { go(id.toString()) }
            }
        },
        // The song's cards on its album's second page: in place on the album page, or the album
        // opened at page 2 from anywhere else.
        when {
            host.onViewInScrapbook != null -> TrackMenuRow(
                stringResource(R.string.track_menu_view_in_scrapbook),
                YoinSymbols.Note,
            ) { host.onViewInScrapbook(songId) }
            host.onGoToAlbum != null && track?.albumId != null -> TrackMenuRow(
                stringResource(R.string.track_menu_view_in_scrapbook),
                YoinSymbols.Note,
            ) {
                ScrapbookFocusRequest.request(songId)
                host.onGoToAlbum(track.albumId.toString())
            }
            else -> null
        },
        host.onGoToArtist?.let { go ->
            track?.artistId?.let { id ->
                TrackMenuRow(stringResource(R.string.track_menu_go_to_artist), YoinSymbols.Artist) { go(id.toString()) }
            }
        },
        share?.let { TrackMenuRow(stringResource(R.string.track_menu_share), YoinSymbols.Share, it) },
        menu.onOpenIn?.let { open -> menu.openInLabel?.let { TrackMenuRow(it, YoinSymbols.Launch, open) } },
    )

    YoinModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        // The navigation bar is padded inside the scroll, so the last row clears it.
        contentWindowInsets = {
            BottomSheetDefaults.modalWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        },
    ) {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp + navBottom),
            verticalArrangement = Arrangement.spacedBy(TrackMenuGroupGap),
        ) {
            TrackMenuHeader(track = track, coverUrl = coverUrl)
            for (rows in listOf(queueRows, goRows)) {
                if (rows.isEmpty()) continue
                TrackMenuGroup(count = rows.size) { index, shape ->
                    val row = rows[index]
                    TrackMenuRowItem(
                        label = row.label,
                        icon = row.icon,
                        shape = shape,
                        onClick = {
                            haptics.performContextClick()
                            row.onClick()
                            close()
                        },
                    )
                }
            }
            playerSection?.invoke()
        }
    }
}

/** The song the sheet is about: its cover, its title and artist. */
@Composable
private fun TrackMenuHeader(track: Track?, coverUrl: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExpressiveMediaArtwork(
            model = coverUrl,
            contentDescription = null,
            modifier = Modifier.size(TrackMenuCoverSize),
            shape = YoinArtworkShapes.Thumb,
            fallbackIcon = YoinSymbols.MusicNote,
            tonalElevation = 0.dp,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track?.title.orEmpty(),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            track?.artist?.takeIf { it.isNotBlank() }?.let { artist ->
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A group of rows, segmented like Settings: outer corners round, inner corners tight. */
@Composable
internal fun TrackMenuGroup(count: Int, row: @Composable (index: Int, shape: Shape) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(TrackMenuRowGap)) {
        repeat(count) { index -> row(index, settingsSegmentShape(index, count)) }
    }
}

@Composable
private fun TrackMenuRowItem(label: String, icon: ImageVector, shape: Shape, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        TrackMenuRowContent(label = label, icon = icon)
    }
}

@Composable
private fun TrackMenuRowContent(label: String, icon: ImageVector, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TrackMenuRowHeight)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/**
 * A row that switches a setting and keeps the sheet up: the whole row toggles
 * the [Switch] at its end (one target for touch and TalkBack).
 */
@Composable
internal fun TrackMenuToggleRow(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: Shape,
) {
    val haptics = rememberYoinHaptics()
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = {
                    haptics.performTick()
                    onCheckedChange(it)
                },
            ),
    ) {
        TrackMenuRowContent(label = label, icon = icon) {
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

private val TrackMenuCoverSize = 56.dp
private val TrackMenuRowHeight = 56.dp
private val TrackMenuRowGap = 2.dp
private val TrackMenuGroupGap = 12.dp
