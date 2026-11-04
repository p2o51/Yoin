package com.gpo.yoin.ui.detail

import com.gpo.yoin.ui.component.audioFormatTags
import com.gpo.yoin.ui.component.AudioFormatTagRow
import android.content.Intent
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.AppContainer
import com.gpo.yoin.R
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.model.isUnplayableAppleImport
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.WebLinkKind
import com.gpo.yoin.symbols.rememberFavoriteSymbolPainter
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.YoinModalBottomSheet
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.settings.settingsSegmentShape
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable

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
internal class TrackMenuRow(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * One button of the sheet's top row: an action on the song itself that lands
 * in one tap and has a short name (owner 2026-11-03, after Apple Music's
 * Add / Favorite / Share row). [keepsOpen]: the sheet stays up after it (a
 * toggle, a queue add with its own snackbar); otherwise it goes down as a
 * row's does. [checked] draws the button selected (Favorite on).
 */
internal class TrackMenuQuickAction(
    val label: String,
    val icon: @Composable () -> Painter,
    val keepsOpen: Boolean,
    val checked: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * A song's long-press sheet, rising from the bottom (owner 2026-10-17: a
 * bottom sheet, not a dropdown): the song (cover, title, artist), then a top
 * row of one-tap actions on the song — Play next / Add to queue / Favorite /
 * Share (owner 2026-11-03) — then Add to playlist, then Go to album / View in
 * Scrapbook / Go to artist / Open in …, then [playerSection] when the host has
 * player settings (Now Playing's Keep screen on). The queue actions are the
 * detail ▾ rows for one song ([rememberDetailMenu], so the provider rules and
 * the snackbar lines are the same).
 *
 * [nowPlaying]: the sheet is about the song that is playing (the mini player's
 * and the Now Playing cover's long-press). Queueing it again makes no sense, so
 * Play next / Add to queue leave and Add to playlist takes their place on top:
 * Favorite / Add to playlist / Share.
 *
 * A row or button the song or provider can't do is left out, and so is an
 * empty group; a top row that would hold fewer than two buttons folds into the
 * list instead. Compose it only while open; [onDismissRequest] clears that.
 * Back, a tap on the scrim or a drag down close it the M3 way
 * ([YoinModalBottomSheet]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackMenuSheet(
    songId: String,
    onDismissRequest: () -> Unit,
    host: TrackMenuHost,
    nowPlaying: Boolean = false,
    playerSection: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val haptics = rememberYoinHaptics()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repository = host.container.repository
    // Read once as the sheet opens, so it is the song as it is now.
    val track = remember(songId) { host.trackFor(songId) }
    val coverUrl = remember(track) { track?.coverArt?.let { repository.resolveCoverUrl(it) } }
    val link = rememberDetailWebLink(host.container, WebLinkKind.Track, songId)
    val menu = rememberDetailMenu(
        container = host.container,
        link = link,
        provider = MediaId.parseOrNull(songId)?.provider,
        tracks = { listOfNotNull(track) },
        onMessage = host.onMessage,
        onAddToPlaylist = host.onAddToPlaylist,
        queueAvailable = !nowPlaying && track != null && !track.isUnplayableAppleImport,
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
    // Favorite: the heart as Yoin resolves it (a write in flight, what the
    // service answered, Spotify's library mirror, then the flag the song was
    // read with), as Now Playing reads it. A tap shows its state at once
    // ([pendingFavorite]) until the resolved state catches up, and drops it if
    // the service refuses. The write outlives the sheet.
    val canFavorite = track != null && track.id.rawId.isNotBlank() &&
        Capability.FAVORITES in repository.currentCapabilities()
    val resolvedFavorite by remember(track) {
        track?.let { song -> repository.observeFavoriteState(song).map { it.isStarred } } ?: flowOf(false)
    }.collectAsState(
        initial = track?.let { repository.favoriteOverrides.value[it.id] ?: it.isStarred } ?: false,
    )
    var pendingFavorite by remember(track) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(resolvedFavorite) {
        if (pendingFavorite == resolvedFavorite) pendingFavorite = null
    }
    val favorite = pendingFavorite ?: resolvedFavorite
    val couldntSaveFavorite = stringResource(R.string.np_msg_couldnt_save_favorite)
    val couldntRemoveFavorite = stringResource(R.string.np_msg_couldnt_remove_favorite)
    val toggleFavorite: (() -> Unit)? = track?.takeIf { canFavorite }?.let { song ->
        {
            val next = !favorite
            pendingFavorite = next
            scope.launch(NonCancellable) {
                repository.setFavorite(song, next).onFailure {
                    pendingFavorite = null
                    host.onMessage(if (next) couldntSaveFavorite else couldntRemoveFavorite)
                }
            }
        }
    }

    val playNextLabel = stringResource(R.string.detail_menu_play_next)
    val addToQueueLabel = stringResource(R.string.detail_menu_add_to_queue)
    val addToPlaylistLabel = stringResource(R.string.detail_menu_add_to_playlist)
    val favoriteLabel = stringResource(R.string.track_menu_favorite)
    val favoritedLabel = stringResource(R.string.track_menu_favorited)
    val shareLabel = stringResource(R.string.track_menu_share)
    val favoriteAction = toggleFavorite?.let {
        TrackMenuQuickAction(
            label = if (favorite) favoritedLabel else favoriteLabel,
            icon = { rememberFavoriteSymbolPainter(favorite = favorite) },
            keepsOpen = true,
            checked = favorite,
            onClick = it,
        )
    }
    val shareAction = share?.let {
        TrackMenuQuickAction(shareLabel, { rememberVectorPainter(YoinSymbols.Share) }, keepsOpen = false, onClick = it)
    }
    val playlistAction = menu.onAddToPlaylist?.let {
        TrackMenuQuickAction(
            addToPlaylistLabel,
            { rememberVectorPainter(YoinSymbols.Playlist) },
            keepsOpen = false,
            onClick = it,
        )
    }
    val candidates = if (nowPlaying) {
        listOfNotNull(favoriteAction, playlistAction, shareAction)
    } else {
        listOfNotNull(
            menu.onPlayNext?.let {
                TrackMenuQuickAction(playNextLabel, { rememberVectorPainter(YoinSymbols.SkipNext) }, keepsOpen = true, onClick = it)
            },
            menu.onAddToQueue?.let {
                TrackMenuQuickAction(addToQueueLabel, { rememberVectorPainter(YoinSymbols.Queue) }, keepsOpen = true, onClick = it)
            },
            favoriteAction,
            shareAction,
        )
    }
    // One button alone is not a row: it goes back to the list.
    val quickActions = candidates.takeIf { it.size >= 2 }.orEmpty()
    val folded = if (quickActions.isEmpty()) {
        candidates.filter { it !== favoriteAction }.map { action ->
            val vector = when (action) {
                shareAction -> YoinSymbols.Share
                playlistAction -> YoinSymbols.Playlist
                else -> if (action.label == playNextLabel) YoinSymbols.SkipNext else YoinSymbols.Queue
            }
            TrackMenuRow(action.label, vector, action.onClick)
        }
    } else {
        emptyList()
    }
    val playlistRows = listOfNotNull(
        playlistAction?.takeIf { !nowPlaying && quickActions.isNotEmpty() }?.let {
            TrackMenuRow(addToPlaylistLabel, YoinSymbols.Playlist, it.onClick)
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
        *goToArtistRows(track, host.onGoToArtist).toTypedArray(),
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
        TrackMenuSheetBody(
            track = track,
            coverUrl = coverUrl,
            quickActions = quickActions,
            onQuickActionClick = { action ->
                if (action.keepsOpen) {
                    haptics.performTick()
                    action.onClick()
                } else {
                    haptics.performContextClick()
                    action.onClick()
                    close()
                }
            },
            groups = listOf(folded + playlistRows, goRows),
            onRowClick = { row ->
                haptics.performContextClick()
                row.onClick()
                close()
            },
            bottomPadding = 16.dp + navBottom,
            playerSection = playerSection,
        )
    }
}

/**
 * What the sheet shows: the song, then each non-empty group of [groups], then
 * [playerSection]. Shared by [TrackMenuSheet] and the tip page's demo phone,
 * which shows it in a sheet of its own.
 */
@Composable
internal fun TrackMenuSheetBody(
    track: Track?,
    coverUrl: String?,
    groups: List<List<TrackMenuRow>>,
    onRowClick: (TrackMenuRow) -> Unit,
    bottomPadding: Dp,
    playerSection: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    quickActions: List<TrackMenuQuickAction> = emptyList(),
    onQuickActionClick: (TrackMenuQuickAction) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(start = 16.dp, end = 16.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(TrackMenuGroupGap),
    ) {
        TrackMenuHeader(track = track, coverUrl = coverUrl)
        if (quickActions.isNotEmpty()) {
            TrackMenuQuickRow(actions = quickActions, onClick = onQuickActionClick)
        }
        for (rows in groups) {
            if (rows.isEmpty()) continue
            TrackMenuGroup(count = rows.size) { index, shape ->
                val row = rows[index]
                TrackMenuRowItem(
                    label = row.label,
                    icon = row.icon,
                    shape = shape,
                    onClick = { onRowClick(row) },
                )
            }
        }
        playerSection?.invoke()
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
            // The file's own format (Subsonic); what is playing shows on Now Playing.
            AudioFormatTagRow(
                tags = audioFormatTags(track?.audioFormat),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * The top row: an MD3 Expressive connected button group. Outer corners round,
 * inner corners tight; a press squeezes the button's corners (the toggle
 * button's own shape morph) and a checked button rounds into a pill in the
 * tonal colour, its heart beating in ([rememberFavoriteSymbolPainter]).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TrackMenuQuickRow(actions: List<TrackMenuQuickAction>, onClick: (TrackMenuQuickAction) -> Unit) {
    val colors = ToggleButtonDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    // Every button fits: the group never overflows, so it has no overflow menu.
    ButtonGroup(
        overflowIndicator = {},
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        actions.forEachIndexed { index, action ->
            customItem(
                buttonGroupContent = {
                    val interaction = remember { MutableInteractionSource() }
                    ToggleButton(
                        checked = action.checked,
                        onCheckedChange = { onClick(action) },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            actions.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        colors = colors,
                        elevation = null,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp),
                        interactionSource = interaction,
                        // The pressed button widens and its neighbours give way.
                        modifier = Modifier
                            .weight(1f)
                            .animateWidth(interaction)
                            .heightIn(min = TrackMenuQuickHeight),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                painter = action.icon(),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            // A neighbour squeezed by a press shrinks its label rather than cut it.
                            Text(
                                text = action.label,
                                style = MaterialTheme.typography.labelMedium,
                                autoSize = TextAutoSize.StepBased(
                                    minFontSize = 9.sp,
                                    maxFontSize = MaterialTheme.typography.labelMedium.fontSize,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                },
                menuContent = {},
            )
        }
    }
}

/**
 * "Go to artist", or one "Go to <name>" per credited artist with a page of
 * their own when the service names several (OpenSubsonic `artists`).
 */
@Composable
private fun goToArtistRows(track: Track?, go: ((String) -> Unit)?): List<TrackMenuRow?> {
    if (go == null || track == null) return emptyList()
    val credits = track.artistCredits.filter { it.id != null }
    if (credits.size > 1) {
        return credits.map { credit ->
            TrackMenuRow(stringResource(R.string.track_menu_go_to_named_artist, credit.name), YoinSymbols.Artist) {
                go(credit.id.toString())
            }
        }
    }
    val id = track.artistId ?: return emptyList()
    return listOf(TrackMenuRow(stringResource(R.string.track_menu_go_to_artist), YoinSymbols.Artist) { go(id.toString()) })
}

/** A group of rows, segmented like Settings: outer corners round, inner corners tight. */
@Composable
internal fun TrackMenuGroup(count: Int, row: @Composable (index: Int, shape: Shape) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(TrackMenuRowGap)) {
        repeat(count) { index -> row(index, settingsSegmentShape(index, count)) }
    }
}

@Composable
internal fun TrackMenuRowItem(label: String, icon: ImageVector, shape: Shape, onClick: () -> Unit) {
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
private val TrackMenuQuickHeight = 76.dp
