package com.gpo.yoin.ui.navigation.pane

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.gpo.yoin.R
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.source.WebLinkKind
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.common.asString
import com.gpo.yoin.ui.component.BarExtraAction
import com.gpo.yoin.ui.component.BarPlaySplitActions
import com.gpo.yoin.ui.detail.AlbumDetailScreen
import com.gpo.yoin.ui.detail.AlbumDetailUiState
import com.gpo.yoin.ui.detail.AlbumDetailViewModel
import com.gpo.yoin.ui.detail.ArtistDetailScreen
import com.gpo.yoin.ui.detail.ArtistDetailUiState
import com.gpo.yoin.ui.detail.ArtistDetailViewModel
import com.gpo.yoin.ui.detail.DetailMenuRows
import com.gpo.yoin.ui.detail.PlaylistDetailScreen
import com.gpo.yoin.ui.detail.PlaylistDetailUiState
import com.gpo.yoin.ui.detail.PlaylistDetailViewModel
import com.gpo.yoin.ui.detail.detailShareText
import com.gpo.yoin.ui.detail.rememberDetailMenu
import com.gpo.yoin.ui.detail.rememberDetailWebLink
import com.gpo.yoin.ui.navigation.trackCoverArtId
import com.gpo.yoin.ui.settings.SettingsActivity
import com.gpo.yoin.ui.theme.rememberCoverColorScheme
import kotlinx.coroutines.launch

/*
 * The detail column's entries: the SAME page composables the detail
 * Activities render, wired to ViewModels scoped to the NavDisplay entry
 * (rememberViewModelStoreNavEntryDecorator) and to playback through the
 * shell's AppContainer. What a window page draws in its own bottom bar — the
 * Play split and its menu — an entry PUBLISHES instead, and the shell's one
 * bar renders it beside its nav buttons (adaptive principle 2).
 */

/** Playback facts the entries read (narrow projections the shell already holds — never the ticking state). */
internal class PanePlaybackFacts(
    val isPlaying: Boolean,
    val currentTrackId: String?,
    val playbackSignal: Float,
)

/** Where an entry's Play split lands: the shell bar reads the TOP entry's. */
class PaneBarRegistry {
    private val actions = androidx.compose.runtime.mutableStateMapOf<NavKey, BarPlaySplitActions>()

    operator fun get(key: NavKey): BarPlaySplitActions? = actions[key]

    fun publish(key: NavKey, value: BarPlaySplitActions) {
        if (actions[key] !== value) actions[key] = value
    }

    fun retire(key: NavKey) {
        actions.remove(key)
    }
}

/** Publishes [actions] under [key] while composed; retired on dispose. */
@Composable
private fun PublishPaneBarActions(registry: PaneBarRegistry, key: NavKey, actions: BarPlaySplitActions) {
    SideEffect { registry.publish(key, actions) }
    DisposableEffect(registry, key) {
        onDispose { registry.retire(key) }
    }
}

@Composable
internal fun AlbumPaneEntry(
    route: DetailPaneRoute.Album,
    app: YoinApplication,
    playback: PanePlaybackFacts,
    registry: PaneBarRegistry,
    onBack: () -> Unit,
    onOpenArtist: (artistId: String) -> Unit,
    onMessage: (String) -> Unit,
    onAddToPlaylist: (List<MediaId>) -> Unit,
) {
    val context = LocalContext.current
    val viewModel: AlbumDetailViewModel = viewModel(
        factory = AlbumDetailViewModel.Factory(route.albumId, app.container),
    )
    val uiState by viewModel.uiState.collectAsState()
    val notedSongIds by viewModel.notedSongIds.collectAsState()
    val expandedSongId by viewModel.expandedSongId.collectAsState()
    val expandedNoteBundle by viewModel.expandedNoteBundle.collectAsState()
    val scrapbook by viewModel.scrapbook.collectAsState()
    val neoDb by viewModel.neoDb.collectAsState()
    val currentOnMessage by rememberUpdatedState(onMessage)
    // A failed NeoDB sync surfaces on the shell's snackbar.
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> currentOnMessage(message.asString(context)) }
    }
    val content = uiState as? AlbumDetailUiState.Content

    // explicitStart = false for Play / Shuffle: they pick no song, so an Apple
    // Music import first in line gives way to the next that plays.
    fun playFrom(startIndex: Int, shuffle: Boolean, explicitStart: Boolean = true) {
        val ordered = viewModel.getAlbumSongs()
        if (ordered.isEmpty()) return
        val tracks = if (shuffle) ordered.shuffled() else ordered
        val activityContext = content?.let {
            ActivityContext.Album(
                albumId = it.albumId,
                albumName = it.albumName,
                artistName = it.artistName,
                artistId = it.artistId,
                coverArtId = it.coverArtId,
            )
        } ?: ActivityContext.None
        app.container.profileManager.activeSource.value?.let { source ->
            app.container.playbackManager.play(
                tracks = tracks,
                startIndex = startIndex.coerceIn(0, tracks.lastIndex),
                source = source,
                activityContext = activityContext,
                shuffled = shuffle,
                explicitStart = explicitStart,
            )
        }
    }
    fun playSong(songId: String) {
        val index = viewModel.getAlbumSongs()
            .indexOfFirst { it.id.toString() == songId }
            .coerceAtLeast(0)
        playFrom(startIndex = index, shuffle = false)
    }
    val webLink = rememberDetailWebLink(app.container, WebLinkKind.Album, route.albumId)
    val menu = rememberDetailMenu(
        container = app.container,
        link = webLink,
        provider = MediaId.parseOrNull(route.albumId)?.provider,
        tracks = { viewModel.getAlbumSongs() },
        onMessage = onMessage,
        onAddToPlaylist = onAddToPlaylist,
    )
    val share = {
        val title = content?.let {
            context.getString(R.string.shell_share_album_title, it.albumName, it.artistName)
        } ?: context.getString(R.string.shell_share_album_fallback)
        context.startActivity(Intent.createChooser(shareIntent(detailShareText(title, webLink)), null))
    }
    val artistId = content?.artistId

    // The bar's Play rides the cover-seeded primary (the shell bar animates
    // the colour change itself, so the registry only sees targets).
    val barScheme = rememberCoverColorScheme(content?.coverArtUrl) ?: MaterialTheme.colorScheme
    val goToArtist = stringResource(R.string.shell_pane_album_go_to_artist)
    val shareAlbum = stringResource(R.string.shell_pane_album_share)
    PublishPaneBarActions(
        registry = registry,
        key = route,
        actions = rememberPaneBarActions(
            playContainer = barScheme.primary,
            playContent = barScheme.onPrimary,
            onPlay = { playFrom(startIndex = 0, shuffle = false, explicitStart = false) },
            onShuffle = { playFrom(startIndex = 0, shuffle = true, explicitStart = false) },
            promotable = listOfNotNull(
                artistId?.let { id ->
                    BarExtraAction(icon = YoinSymbols.Artist, label = goToArtist) { onOpenArtist(id) }
                },
                BarExtraAction(icon = YoinSymbols.Share, label = shareAlbum, onClick = share),
            ),
            menuItems = { dismissMenu -> DetailMenuRows(menu, dismissMenu) },
        ),
    )

    AlbumDetailScreen(
        uiState = uiState,
        onBackClick = onBack,
        onSongClick = { songId -> playSong(songId) },
        // Page 2 (the scrapbook), as the Activity host wires it.
        scrapbook = scrapbook,
        onNoteMomentClick = { songId, positionMs ->
            // Seek in place when that song is already current; otherwise play it and seek once it is ready.
            val inPlace = positionMs != null && viewModel.requestNoteSeek(songId, positionMs)
            if (!inPlace) playSong(songId)
        },
        onToggleStar = viewModel::toggleStar,
        onRetry = viewModel::retry,
        onResumed = viewModel::onResumed,
        onRenameMemoryTitle = viewModel::renameMemoryTitle,
        onRestoreMemoryTitle = viewModel::restoreMemoryTitle,
        notedSongIds = notedSongIds,
        currentTrackId = playback.currentTrackId,
        expandedSongId = expandedSongId,
        expandedNoteBundle = expandedNoteBundle,
        onToggleExpandedSong = viewModel::toggleExpandedSong,
        onRatingCommit = viewModel::setUserRating,
        onReviewDraftChange = viewModel::onReviewDraftChange,
        neoDb = neoDb,
        onRateSheetOpened = viewModel::onRateSheetOpened,
        onRateSheetClosed = viewModel::onRateSheetClosed,
        onNeoDbRetry = viewModel::retryNeoDbSync,
        onNeoDbSignIn = { context.startActivity(SettingsActivity.intent(context, "neodb")) },
        onPlayAlbum = { playFrom(startIndex = 0, shuffle = false, explicitStart = false) },
        onShufflePlay = { playFrom(startIndex = 0, shuffle = true, explicitStart = false) },
        onShare = share,
        menu = menu,
        onOpenArtist = artistId?.let { id -> { onOpenArtist(id) } },
        isPlaying = playback.isPlaying,
        playbackSignal = playback.playbackSignal,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
internal fun ArtistPaneEntry(
    route: DetailPaneRoute.Artist,
    app: YoinApplication,
    playback: PanePlaybackFacts,
    registry: PaneBarRegistry,
    isTop: Boolean,
    onBack: () -> Unit,
    onOpenAlbum: (albumId: String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: ArtistDetailViewModel = viewModel(
        factory = ArtistDetailViewModel.Factory(route.artistId, app.container),
    )
    val uiState by viewModel.uiState.collectAsState()
    val content = uiState as? ArtistDetailUiState.Content

    // Ratings given on an album page (or plays made) while this entry sat
    // under another show up when it is the top entry again — the window
    // page's ON_RESUME refresh.
    LaunchedEffect(isTop) { if (isTop) viewModel.refreshPersonal() }

    fun playArtist(shuffle: Boolean) {
        scope.launch {
            // Already in play order: Shuffle's order is drawn before its albums load.
            val ordered = viewModel.getPlayTracks(shuffle)
            if (ordered.isEmpty()) return@launch
            app.container.profileManager.activeSource.value?.let { source ->
                app.container.playbackManager.play(
                    tracks = ordered,
                    startIndex = 0,
                    source = source,
                    activityContext = ActivityContext.None,
                    // Play / Shuffle pick no song: an Apple Music import first
                    // in line gives way to the next that plays.
                    explicitStart = false,
                )
            }
        }
    }
    fun playMostPlayed(startIndex: Int) {
        val tracks = viewModel.getMostPlayedTracks()
        if (tracks.isEmpty()) return
        app.container.profileManager.activeSource.value?.let { source ->
            app.container.playbackManager.play(
                tracks = tracks,
                startIndex = startIndex.coerceIn(0, tracks.lastIndex),
                source = source,
                activityContext = ActivityContext.None,
            )
        }
    }
    val webLink = rememberDetailWebLink(app.container, WebLinkKind.Artist, route.artistId)
    val menu = rememberDetailMenu(
        container = app.container,
        link = webLink,
        provider = MediaId.parseOrNull(route.artistId)?.provider,
        tracks = { viewModel.getAllTracks() },
        onMessage = onMessage,
        onAddToPlaylist = null,
    )
    val share = {
        val title = content?.artistName ?: context.getString(R.string.shell_share_artist_fallback)
        context.startActivity(Intent.createChooser(shareIntent(detailShareText(title, webLink)), null))
    }

    val heroUrl = content?.heroCoverArtUrl ?: content?.albums?.firstOrNull()?.coverArtUrl
    val scheme = rememberCoverColorScheme(heroUrl) ?: MaterialTheme.colorScheme
    PublishPaneBarActions(
        registry = registry,
        key = route,
        actions = rememberPaneBarActions(
            playContainer = scheme.primary,
            playContent = scheme.onPrimary,
            onPlay = { playArtist(shuffle = false) },
            onShuffle = { playArtist(shuffle = true) },
            promotable = listOf(
                BarExtraAction(
                    icon = YoinSymbols.Share,
                    label = stringResource(R.string.shell_pane_artist_share),
                    onClick = share,
                ),
            ),
            menuItems = { dismissMenu -> DetailMenuRows(menu, dismissMenu) },
        ),
    )

    ArtistDetailScreen(
        uiState = uiState,
        onBackClick = onBack,
        onAlbumClick = onOpenAlbum,
        onRetry = viewModel::retry,
        onToggleFollow = viewModel::toggleFollow,
        onPlay = { playArtist(shuffle = false) },
        onShuffle = { playArtist(shuffle = true) },
        menu = menu,
        onMostPlayedClick = { index -> playMostPlayed(index) },
        onShare = share,
        isPlaying = playback.isPlaying,
        playbackSignal = playback.playbackSignal,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
internal fun PlaylistPaneEntry(
    route: DetailPaneRoute.Playlist,
    app: YoinApplication,
    playback: PanePlaybackFacts,
    registry: PaneBarRegistry,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val viewModel: PlaylistDetailViewModel = viewModel(
        factory = PlaylistDetailViewModel.Factory(route.playlistId, app.container),
    )
    val uiState by viewModel.uiState.collectAsState()
    val content = uiState as? PlaylistDetailUiState.Content
    val currentOnMessage by rememberUpdatedState(onMessage)
    val currentOnBack by rememberUpdatedState(onBack)
    // Rename/delete/remove outcomes surface on the shell's snackbar; a
    // successful delete leaves the column.
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> currentOnMessage(message.asString(context)) }
    }
    LaunchedEffect(viewModel) {
        viewModel.deleted.collect { currentOnBack() }
    }

    // explicitStart = false for Play / Shuffle: they pick no song, so an Apple
    // Music import first in line gives way to the next that plays.
    fun playFrom(startIndex: Int, shuffle: Boolean, explicitStart: Boolean = true) {
        val ordered = viewModel.getPlaylistSongs()
        if (ordered.isEmpty()) return
        val tracks = if (shuffle) ordered.shuffled() else ordered
        val activityContext = content?.let {
            ActivityContext.Playlist(
                playlistId = route.playlistId,
                playlistName = it.playlistName,
                owner = it.owner.takeIf { owner -> owner.isNotBlank() },
                coverArtId = viewModel.getPlaylistCoverArtKey()
                    ?: ordered.firstNotNullOfOrNull(::trackCoverArtId),
            )
        } ?: ActivityContext.None
        app.container.profileManager.activeSource.value?.let { source ->
            app.container.playbackManager.play(
                tracks = tracks,
                startIndex = startIndex.coerceIn(0, tracks.lastIndex),
                source = source,
                activityContext = activityContext,
                shuffled = shuffle,
                explicitStart = explicitStart,
            )
        }
    }
    val share = {
        val text = content?.playlistName ?: context.getString(R.string.shell_share_playlist_fallback)
        context.startActivity(Intent.createChooser(shareIntent(text), null))
    }

    val scheme = rememberCoverColorScheme(content?.coverArtUrl) ?: MaterialTheme.colorScheme
    PublishPaneBarActions(
        registry = registry,
        key = route,
        actions = rememberPaneBarActions(
            playContainer = scheme.primary,
            playContent = scheme.onPrimary,
            onPlay = { playFrom(startIndex = 0, shuffle = false, explicitStart = false) },
            onShuffle = { playFrom(startIndex = 0, shuffle = true, explicitStart = false) },
            promotable = listOf(
                BarExtraAction(
                    icon = YoinSymbols.Share,
                    label = stringResource(R.string.shell_pane_playlist_share),
                    onClick = share,
                ),
            ),
        ),
    )

    PlaylistDetailScreen(
        uiState = uiState,
        onBackClick = onBack,
        onPlayAllClick = { playFrom(startIndex = 0, shuffle = false, explicitStart = false) },
        onShufflePlay = { playFrom(startIndex = 0, shuffle = true, explicitStart = false) },
        onSongClick = { songId ->
            val index = viewModel.getPlaylistSongs()
                .indexOfFirst { it.id.toString() == songId }
                .coerceAtLeast(0)
            playFrom(startIndex = index, shuffle = false)
        },
        onRetry = viewModel::retry,
        onRename = viewModel::rename,
        onDelete = viewModel::delete,
        onShare = share,
        isPlaying = playback.isPlaying,
        playbackSignal = playback.playbackSignal,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * One [BarPlaySplitActions] instance per (target colour, extras) change: the
 * shell bar recomposes on a new instance, so the callbacks read the latest
 * lambdas through [rememberUpdatedState] instead of minting a new object per
 * frame — the colours are the palette's targets, never an animated value.
 */
@Composable
private fun rememberPaneBarActions(
    playContainer: Color,
    playContent: Color,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    promotable: List<BarExtraAction>,
    menuItems: @Composable androidx.compose.foundation.layout.ColumnScope.(dismissMenu: () -> Unit) -> Unit = { _ -> },
): BarPlaySplitActions {
    val currentPlay by rememberUpdatedState(onPlay)
    val currentShuffle by rememberUpdatedState(onShuffle)
    val currentMenu by rememberUpdatedState(menuItems)
    val currentPromotable by rememberUpdatedState(promotable)
    val promotableKey = promotable.map { it.label }
    return remember(playContainer, playContent, promotableKey) {
        BarPlaySplitActions(
            playContainer = playContainer,
            playContent = playContent,
            onPlay = { currentPlay() },
            onShuffle = { currentShuffle() },
            menuItems = { dismissMenu -> currentMenu(this, dismissMenu) },
            promotable = currentPromotable.map { action ->
                BarExtraAction(icon = action.icon, label = action.label) {
                    currentPromotable.firstOrNull { it.label == action.label }?.onClick?.invoke()
                }
            },
        )
    }
}

private fun shareIntent(text: String): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_TEXT, text)
}

