@file:Suppress("ktlint:standard:function-naming") // Composables are PascalCase

package com.gpo.yoin.ui.library

import com.gpo.yoin.ui.theme.YoinShapeTokens
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.shared.memory.MemoryLedgerAlbum
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.shared.search.LedgerMark
import com.gpo.yoin.shared.search.LedgerMarkKind
import com.gpo.yoin.shared.search.MemoryAttachment
import com.gpo.yoin.shared.search.MemorySearchHit
import com.gpo.yoin.shared.search.NoteSearchHit
import com.gpo.yoin.data.search.SearchBestMatch
import com.gpo.yoin.shared.search.SearchFragment
import com.gpo.yoin.data.search.SearchPage
import com.gpo.yoin.shared.search.SearchQuery
import com.gpo.yoin.shared.search.SearchSection
import com.gpo.yoin.data.search.SearchSectionPage
import com.gpo.yoin.data.search.SearchTile
import com.gpo.yoin.data.search.SearchTileRow
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ExplicitTitle
import com.gpo.yoin.ui.component.coverKindTag
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.dashedOutline
import com.gpo.yoin.ui.component.elasticPress
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.ui.component.seamDissolveViewport
import com.gpo.yoin.ui.component.seamScrolledPx
import com.gpo.yoin.ui.home.HomeAlbumMemoryMark
import com.gpo.yoin.ui.home.WidgetBackdropArtwork
import com.gpo.yoin.ui.home.WidgetShapeKind
import com.gpo.yoin.ui.home.allowsCoverMarks
import com.gpo.yoin.ui.memories.MemoryScoreKind
import com.gpo.yoin.ui.theme.YoinMotion
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import com.gpo.yoin.ui.component.nameWithoutKindSuffix

/*
 * The search's All page and Memories list as Home's Jump Back In tiles (owner 2026-11-20, #Yoin:#18,
 * prototype v3.1): a Material shape behind each cover (album Bun, song Circle, playlist Ghostish, artist a
 * plain circle), a song met through a note or an album through its memory's words as a 1×2 with the
 * words beside the cover and the hit inked like a marker pen. The model is data/search/SearchPage.kt.
 */

/** What a tile does when tapped: the screen's navigation and playback, bound once. */
@Immutable
internal class SearchTileActions(
    val onSong: (Track) -> Unit,
    val onSongLongClick: ((Track) -> Unit)?,
    /** A note: play its song from where it was written ([NoteSearchHit.positionMs]); [track] = the returned song, if any. */
    val onNote: (note: NoteSearchHit, track: Track?) -> Unit,
    /** A returned album; [memory] non-null = it holds a memory, opened on the scrapbook. */
    val onAlbum: (album: Album, memory: MemoryAttachment?) -> Unit,
    /** An album known only from the ledger (another account's, or not returned): its scrapbook. */
    val onLedgerAlbum: (MemoryLedgerAlbum) -> Unit,
    val onArtist: (Artist) -> Unit,
    val onPlaylist: (Playlist) -> Unit,
    val onShowAll: (SearchSection) -> Unit,
)

private val TileGap = 12.dp
private val TileRowGap = 16.dp

/** The All page: the best-match card, then each section as rows of tiles, best section first. */
@Composable
internal fun SearchAllTiles(
    page: SearchPage,
    query: SearchQuery,
    providerName: String?,
    listState: LazyListState,
    actions: SearchTileActions,
    coverArtUrlBuilder: ((String) -> String)?,
    modifier: Modifier = Modifier,
    columns: Int = 3,
    bottomReserve: Dp = 24.dp,
) {
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val cell = (maxWidth - 32.dp - TileGap * (columns - 1)) / columns
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .seamDissolveViewport { listState.seamScrolledPx() },
            contentPadding = PaddingValues(top = 8.dp, bottom = navBarBottom + bottomReserve),
        ) {
            page.bestMatch?.let { best ->
                item(key = "best") {
                    SearchBestMatchCard(
                        best = best,
                        query = query,
                        actions = actions,
                        coverArtUrlBuilder = coverArtUrlBuilder,
                        modifier = Modifier.animateItem(
                            fadeInSpec = YoinMotion.effectsSpring(),
                            placementSpec = YoinMotion.spatialSpring(),
                            fadeOutSpec = YoinMotion.effectsSpring(),
                        ),
                    )
                }
            }
            page.sections.forEach { section ->
                if (section.allRows.isEmpty()) return@forEach
                item(key = "head-${section.section}") {
                    SearchTileHeader(
                        title = sectionTitle(section.section),
                        showAll = section.tiles.size > section.allRows.sumOf { it.tiles.size },
                        onShowAll = { actions.onShowAll(section.section) },
                        modifier = Modifier.animateItem(placementSpec = YoinMotion.spatialSpring()),
                    )
                }
                items(
                    items = section.allRows,
                    key = { row -> "row-${section.section}-" + row.tiles.joinToString("|") { it.key } },
                ) { row ->
                    SearchTileRowView(
                        row = row,
                        cell = cell,
                        query = query,
                        actions = actions,
                        coverArtUrlBuilder = coverArtUrlBuilder,
                        modifier = Modifier.animateItem(
                            fadeInSpec = YoinMotion.effectsSpring(),
                            placementSpec = YoinMotion.spatialSpring(),
                            fadeOutSpec = YoinMotion.effectsSpring(),
                        ),
                    )
                }
                if (section.folded > 0 && providerName != null) {
                    item(key = "folded-${section.section}") {
                        SearchFoldedNote(
                            count = section.folded,
                            providerName = providerName,
                            onClick = { actions.onShowAll(section.section) },
                            modifier = Modifier.animateItem(placementSpec = YoinMotion.spatialSpring()),
                        )
                    }
                }
            }
        }
    }
}

/** The Memories chip: every note and memory the query met, one full-width 1×2 per row. */
@Composable
internal fun SearchMemoriesList(
    page: SearchPage,
    query: SearchQuery,
    listState: LazyListState,
    actions: SearchTileActions,
    coverArtUrlBuilder: ((String) -> String)?,
    modifier: Modifier = Modifier,
    bottomReserve: Dp = 24.dp,
) {
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    if (page.memories.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.library_search_no_memories),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val cell = (maxWidth - 32.dp - TileGap * 2) / 3
        val rowWidth = maxWidth - 32.dp
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .seamDissolveViewport { listState.seamScrolledPx() },
            contentPadding = PaddingValues(top = 8.dp, bottom = navBarBottom + bottomReserve),
            verticalArrangement = Arrangement.spacedBy(TileRowGap),
        ) {
            items(items = page.memories, key = { it.key }) { tile ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .animateItem(placementSpec = YoinMotion.spatialSpring()),
                ) {
                    SearchTileView(
                        tile = tile,
                        cell = cell,
                        width = rowWidth,
                        query = query,
                        actions = actions,
                        coverArtUrlBuilder = coverArtUrlBuilder,
                        textLines = 6,
                    )
                }
            }
        }
    }
}

@Composable
private fun sectionTitle(section: SearchSection): String = when (section) {
    SearchSection.Songs -> stringResource(R.string.library_search_section_songs)
    SearchSection.Albums -> stringResource(R.string.library_search_section_albums)
    SearchSection.Artists -> stringResource(R.string.library_search_section_artists)
    SearchSection.Playlists -> stringResource(R.string.library_search_section_playlists)
}

@Composable
private fun SearchTileHeader(title: String, showAll: Boolean, onShowAll: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (showAll) {
            Text(
                text = stringResource(R.string.library_search_show_all),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .handPointer()
                    // A text button's pill, not a square, under the press.
                    .clip(YoinShapeTokens.Full)
                    .combinedClickable(onClick = onShowAll)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** "Spotify: 7 less relevant": the service's rows the query doesn't appear in, behind the section's chip. */
@Composable
private fun SearchFoldedNote(count: Int, providerName: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .handPointer(),
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.library_search_folded, count, providerName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.library_search_show_all),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** One packed row: its 1×2 first, or after the singles when the row alternates ([SearchTileRow.wideAtEnd]). */
@Composable
private fun SearchTileRowView(
    row: SearchTileRow,
    cell: Dp,
    query: SearchQuery,
    actions: SearchTileActions,
    coverArtUrlBuilder: ((String) -> String)?,
    modifier: Modifier = Modifier,
) {
    val wide = row.tiles.filter { it.wide }
    val singles = row.tiles.filterNot { it.wide }
    val ordered = if (row.wideAtEnd) singles + wide else wide + singles
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = TileRowGap),
        horizontalArrangement = Arrangement.spacedBy(TileGap),
        verticalAlignment = Alignment.Top,
    ) {
        ordered.forEach { tile ->
            SearchTileView(
                tile = tile,
                cell = cell,
                width = if (tile.wide) cell * 2 + TileGap else cell,
                query = query,
                actions = actions,
                coverArtUrlBuilder = coverArtUrlBuilder,
            )
        }
    }
}

@Composable
private fun SearchTileView(
    tile: SearchTile,
    cell: Dp,
    width: Dp,
    query: SearchQuery,
    actions: SearchTileActions,
    coverArtUrlBuilder: ((String) -> String)?,
    textLines: Int = 4,
) {
    when (tile) {
        is SearchTile.Song -> {
            val note = tile.note
            TileFrame(
                width = width,
                cell = cell,
                art = { size, source ->
                    TileArt(
                        model = coverUrl(tile.track.coverArt, coverArtUrlBuilder),
                        kind = WidgetShapeKind.Song,
                        size = size,
                        description = tile.track.title.orEmpty(),
                        interactionSource = source,
                        explicit = tile.track.isExplicit,
                    )
                },
                side = note?.let { { NoteSide(it, query, textLines) } },
                title = tile.track.title.orEmpty(),
                subtitle = tile.track.artist.orEmpty(),
                query = query,
                onClick = { if (note != null) actions.onNote(note, tile.track) else actions.onSong(tile.track) },
                onLongClick = actions.onSongLongClick?.let { open -> { open(tile.track) } },
            )
        }
        is SearchTile.Note -> {
            val note = tile.note
            TileFrame(
                width = width,
                cell = cell,
                art = { size, source ->
                    TileArt(
                        model = ledgerCoverUrl(note.album, coverArtUrlBuilder, sameAccount = !note.isOtherAccount),
                        kind = WidgetShapeKind.Song,
                        size = size,
                        description = note.trackTitle.orEmpty(),
                        interactionSource = source,
                    )
                },
                side = { NoteSide(note, query, textLines) },
                title = note.trackTitle.orEmpty(),
                subtitle = note.album?.artist.orEmpty(),
                otherAccount = note.isOtherAccount,
                query = query,
                onClick = { actions.onNote(note, null) },
            )
        }
        is SearchTile.AlbumRow -> {
            val album = tile.album
            val marks = album.id.provider.allowsCoverMarks()
            TileFrame(
                width = width,
                cell = cell,
                art = { size, source ->
                    TileArt(
                        model = coverUrl(album.coverArt, coverArtUrlBuilder),
                        kind = WidgetShapeKind.Album,
                        size = size,
                        description = album.name,
                        interactionSource = source,
                        mark = tile.memory?.mark?.takeIf { marks }?.toHomeMark(),
                        version = album.version,
                        explicit = album.isExplicit,
                        kindTag = coverKindTag(album.releaseType),
                    )
                },
                side = tile.hit?.let { hit -> { MemorySide(hit, tile.memory?.mark ?: hit.mark, query, textLines) } },
                title = nameWithoutKindSuffix(album.name, album.releaseType),
                subtitle = album.artist.orEmpty(),
                inlineMark = tile.memory?.mark?.takeIf { !marks },
                query = query,
                onClick = { actions.onAlbum(album, tile.memory) },
            )
        }
        is SearchTile.Memory -> {
            val hit = tile.hit
            val marks = hit.album.provider.allowsCoverMarks()
            TileFrame(
                width = width,
                cell = cell,
                art = { size, source ->
                    TileArt(
                        model = ledgerCoverUrl(hit.album, coverArtUrlBuilder, sameAccount = !hit.isOtherAccount),
                        kind = WidgetShapeKind.Album,
                        size = size,
                        description = hit.album.name,
                        interactionSource = source,
                        mark = hit.mark?.takeIf { marks }?.toHomeMark(),
                    )
                },
                side = if (tile.wide) {
                    { MemorySide(hit, hit.mark, query, textLines) }
                } else {
                    null
                },
                title = hit.album.name,
                subtitle = hit.album.artist,
                inlineMark = hit.mark?.takeIf { !marks },
                otherAccount = hit.isOtherAccount,
                query = query,
                onClick = { actions.onLedgerAlbum(hit.album) },
            )
        }
        is SearchTile.ArtistRow -> TileFrame(
            width = width,
            cell = cell,
            art = { size, source ->
                ExpressiveMediaArtwork(
                    model = coverUrl(tile.artist.coverArt, coverArtUrlBuilder),
                    contentDescription = tile.artist.name,
                    modifier = Modifier.size(size).elasticPress(source),
                    shape = CircleShape,
                    fallbackIcon = YoinSymbols.Artist,
                    interactionSource = source,
                    tonalElevation = 1.dp,
                    shadowElevation = 0.dp,
                )
            },
            title = tile.artist.name,
            subtitle = null,
            centered = true,
            query = query,
            onClick = { actions.onArtist(tile.artist) },
        )
        is SearchTile.PlaylistRow -> TileFrame(
            width = width,
            cell = cell,
            art = { size, source ->
                TileArt(
                    model = coverUrl(tile.playlist.coverArt, coverArtUrlBuilder),
                    kind = WidgetShapeKind.Playlist,
                    size = size,
                    description = tile.playlist.name,
                    interactionSource = source,
                )
            },
            title = tile.playlist.name,
            subtitle = tile.playlist.owner,
            query = query,
            onClick = { actions.onPlaylist(tile.playlist) },
        )
    }
}

/**
 * A tile: the cover [cell] square (with [side] beside it on a 1×2), then the caption under both. The
 * whole tile is one press target; the cover takes the press, as on Home.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TileFrame(
    width: Dp,
    cell: Dp,
    art: @Composable (size: Dp, source: MutableInteractionSource) -> Unit,
    title: String,
    subtitle: String?,
    query: SearchQuery,
    onClick: () -> Unit,
    side: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    inlineMark: LedgerMark? = null,
    otherAccount: Boolean = false,
    centered: Boolean = false,
    // The song or album is marked explicit: the "E" badge follows the title (ExplicitBadge).
    explicit: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .width(width)
            .handPointer()
            .combinedClickable(
                interactionSource = source,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) {
            art(cell, source)
            if (side != null) {
                Box(modifier = Modifier.weight(1f).padding(top = 2.dp)) { side() }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (inlineMark != null) {
                InlineMemoryMark(inlineMark, modifier = Modifier.padding(end = 4.dp))
            }
            ExplicitTitle(
                text = title,
                explicit = explicit,
                style = MaterialTheme.typography.titleSmall,
            ) { titleModifier ->
                MarkedText(
                    text = title,
                    marks = listOfNotNull(query.match(title).originalRange.takeUnless { it.isEmpty() }),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = titleModifier,
                )
            }
        }
        if (!subtitle.isNullOrBlank() || otherAccount) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (otherAccount) OtherAccountBadge(modifier = Modifier.padding(end = 4.dp))
                if (!subtitle.isNullOrBlank()) {
                    MarkedText(
                        text = subtitle,
                        marks = listOfNotNull(query.match(subtitle).originalRange.takeUnless { it.isEmpty() }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun TileArt(
    model: String?,
    kind: WidgetShapeKind,
    size: Dp,
    description: String,
    interactionSource: MutableInteractionSource,
    mark: HomeAlbumMemoryMark? = null,
    version: String? = null,
    explicit: Boolean = false,
    kindTag: String? = null,
) {
    WidgetBackdropArtwork(
        model = model,
        kind = kind,
        contentDescription = description,
        extractBackdropColors = true,
        modifier = Modifier.size(size),
        interactionSource = interactionSource,
        memoryMark = mark,
        version = version,
        explicit = explicit,
        kindTag = kindTag,
    )
}

/** A note beside its song's cover: "Your note", where it was written, and the note with the hit inked. */
@Composable
private fun NoteSide(note: NoteSearchHit, query: SearchQuery, lines: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.library_search_your_note),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        note.positionMs?.let { position ->
            Text(
                text = formatNoteTime(position),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        FragmentText(note.fragment, lines)
    }
}

/** A memory's words beside its album's cover: its score and mark, its title, the words with the hit inked. */
@Composable
private fun MemorySide(hit: MemorySearchHit, mark: LedgerMark?, query: SearchQuery, lines: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val score = mark?.score ?: hit.albumScore
        if (mark != null || score != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                mark?.let { InlineMemoryMark(it, size = 16.dp, modifier = Modifier.padding(end = 6.dp)) }
                score?.let {
                    Text(
                        text = String.format(Locale.ROOT, "%.1f", it),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
        Text(
            text = hit.title ?: stringResource(R.string.library_search_your_memory),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        FragmentText(hit.fragment, lines)
    }
}

@Composable
private fun FragmentText(fragment: SearchFragment, lines: Int) {
    MarkedText(
        text = fragment.text,
        marks = listOfNotNull(fragment.highlight.takeUnless { it.isEmpty() }),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = lines,
    )
}

/** The best match, large: Home's tile at hero size, what it is, its name, and a way in. */
@Composable
private fun SearchBestMatchCard(
    best: SearchBestMatch,
    query: SearchQuery,
    actions: SearchTileActions,
    coverArtUrlBuilder: ((String) -> String)?,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val (title, subtitle, kindLabel) = when (best) {
        is SearchBestMatch.Song -> Triple(best.track.title.orEmpty(), best.track.artist.orEmpty(), stringResource(R.string.library_search_section_songs))
        is SearchBestMatch.AlbumMatch -> Triple(best.album.name, best.album.artist.orEmpty(), stringResource(R.string.library_search_section_albums))
        is SearchBestMatch.ArtistMatch -> Triple(best.artist.name, "", stringResource(R.string.library_search_section_artists))
        is SearchBestMatch.PlaylistMatch -> Triple(best.playlist.name, best.playlist.owner.orEmpty(), stringResource(R.string.library_search_section_playlists))
    }
    val open = {
        when (best) {
            is SearchBestMatch.Song -> actions.onSong(best.track)
            is SearchBestMatch.AlbumMatch -> actions.onAlbum(best.album, best.memory)
            is SearchBestMatch.ArtistMatch -> actions.onArtist(best.artist)
            is SearchBestMatch.PlaylistMatch -> actions.onPlaylist(best.playlist)
        }
    }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
            .handPointer()
            .combinedClickable(interactionSource = source, indication = null, onClick = open),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            when (best) {
                is SearchBestMatch.ArtistMatch -> ExpressiveMediaArtwork(
                    model = coverUrl(best.artist.coverArt, coverArtUrlBuilder),
                    contentDescription = title,
                    modifier = Modifier.size(104.dp).elasticPress(source),
                    shape = CircleShape,
                    fallbackIcon = YoinSymbols.Artist,
                    interactionSource = source,
                    tonalElevation = 1.dp,
                    shadowElevation = 0.dp,
                )
                is SearchBestMatch.Song -> TileArt(coverUrl(best.track.coverArt, coverArtUrlBuilder), WidgetShapeKind.Song, 104.dp, title, source)
                is SearchBestMatch.AlbumMatch -> TileArt(
                    coverUrl(best.album.coverArt, coverArtUrlBuilder),
                    WidgetShapeKind.Album,
                    104.dp,
                    title,
                    source,
                    mark = best.memory?.mark?.takeIf { best.album.id.provider.allowsCoverMarks() }?.toHomeMark(),
                )
                is SearchBestMatch.PlaylistMatch -> TileArt(coverUrl(best.playlist.coverArt, coverArtUrlBuilder), WidgetShapeKind.Playlist, 104.dp, title, source)
            }
            Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                Text(text = kindLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val explicit = when (best) {
                    is SearchBestMatch.Song -> best.track.isExplicit
                    is SearchBestMatch.AlbumMatch -> best.album.isExplicit
                    else -> false
                }
                ExplicitTitle(
                    text = title,
                    explicit = explicit,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                ) { titleModifier ->
                    MarkedText(
                        text = title,
                        marks = listOfNotNull(query.match(title).originalRange.takeUnless { it.isEmpty() }),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        modifier = titleModifier,
                    )
                }
                if (subtitle.isNotBlank()) {
                    Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                (best as? SearchBestMatch.AlbumMatch)?.memory?.let { memory ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        memory.mark?.let { InlineMemoryMark(it, modifier = Modifier.padding(end = 6.dp)) }
                        val line = listOfNotNull(memory.title, (memory.albumScore ?: memory.mark?.score)?.let { String.format(Locale.ROOT, "%.1f", it) })
                            .joinToString(" · ")
                        if (line.isNotEmpty()) {
                            Text(text = line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The memory mark beside a title instead of on the cover: Spotify's covers may carry nothing
 * ([allowsCoverMarks]). The same cookie as the sticker: filled = your score, a line = your tracks'
 * average, dashed = notes only.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InlineMemoryMark(mark: LedgerMark, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    val ink = MaterialTheme.colorScheme.tertiary
    val cookie = MaterialShapes.Cookie12Sided.toShape()
    Box(
        modifier = modifier
            .size(size)
            .drawWithCache {
                val outline = cookie.createOutline(this.size, layoutDirection, this)
                onDrawBehind { if (mark.kind == LedgerMarkKind.AlbumRating) drawOutline(outline, ink) }
            }
            .then(
                if (mark.kind == LedgerMarkKind.AlbumRating) {
                    Modifier
                } else {
                    Modifier.dashedOutline(
                        shape = cookie,
                        color = { ink },
                        width = 1.25.dp,
                        dash = 2.dp,
                        gap = 1.5.dp,
                        solidity = { if (mark.kind == LedgerMarkKind.TrackAverage) 1f else 0f },
                    )
                },
            ),
    )
}

/** Another account's: a light badge (the account in use carries none). */
@Composable
private fun OtherAccountBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .background(MaterialTheme.colorScheme.secondary, CircleShape),
    )
}

/**
 * [text] with [marks] inked like a marker pen: a band over the lower part of each line the hit covers,
 * a little slanted and ragged, in the theme's tertiary container, under the glyphs.
 */
@Composable
internal fun MarkedText(
    text: String,
    marks: List<IntRange>,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val ink = MaterialTheme.colorScheme.tertiaryContainer
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
        modifier = modifier.drawBehind {
            val l = layout ?: return@drawBehind
            marks.forEach { drawMarker(l, it, ink) }
        },
    )
}

private fun DrawScope.drawMarker(layout: TextLayoutResult, range: IntRange, ink: Color) {
    val length = layout.layoutInput.text.length
    val start = range.first.coerceIn(0, length)
    val end = (range.last + 1).coerceIn(0, length)
    if (start >= end) return
    val firstLine = layout.getLineForOffset(start)
    val lastLine = layout.getLineForOffset(end - 1)
    for (line in firstLine..lastLine) {
        if (line >= layout.lineCount) break
        val lineStart = if (line == firstLine) start else layout.getLineStart(line)
        val lineEnd = if (line == lastLine) end else layout.getLineEnd(line, visibleEnd = true)
        if (lineStart >= lineEnd) continue
        val x0 = layout.getHorizontalPosition(lineStart, usePrimaryDirection = true)
        val x1 = if (lineEnd >= layout.getLineEnd(line, visibleEnd = true)) {
            layout.getLineRight(line)
        } else {
            layout.getHorizontalPosition(lineEnd, usePrimaryDirection = true)
        }
        val top = layout.getLineTop(line)
        val bottom = layout.getLineBottom(line)
        val h = bottom - top
        val left = min(x0, x1) - h * 0.12f
        val right = max(x0, x1) + h * 0.12f
        val path = Path().apply {
            moveTo(left, top + h * 0.40f)
            lineTo(right, top + h * 0.34f)
            lineTo(right - h * 0.04f, bottom - h * 0.06f)
            lineTo(left + h * 0.05f, bottom - h * 0.02f)
            close()
        }
        drawPath(path, ink, style = Fill)
        drawPath(
            path,
            ink,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = h * 0.10f,
                pathEffect = PathEffect.cornerPathEffect(h * 0.2f),
            ),
        )
    }
}

private fun formatNoteTime(positionMs: Long): String {
    val seconds = positionMs / 1000
    return "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
}

private fun LedgerMark.toHomeMark(): HomeAlbumMemoryMark = HomeAlbumMemoryMark(
    kind = when (kind) {
        LedgerMarkKind.AlbumRating -> MemoryScoreKind.ALBUM_RATING
        LedgerMarkKind.TrackAverage -> MemoryScoreKind.AVERAGE_TRACK_RATING
        LedgerMarkKind.NotesOnly -> MemoryScoreKind.NONE
    },
    scoreText = score?.let { String.format(Locale.ROOT, "%.1f", it) },
    noteCount = noteCount,
)

private fun coverUrl(ref: CoverRef?, builder: ((String) -> String)?): String? = when (ref) {
    null -> null
    is CoverRef.Url -> ref.url
    is CoverRef.SourceRelative -> builder?.invoke(ref.coverArtId)
}

/** A ledger album's cover: its stored key; a server-relative one only resolves in the account in use. */
private fun ledgerCoverUrl(album: MemoryLedgerAlbum?, builder: ((String) -> String)?, sameAccount: Boolean): String? {
    val ref = CoverRef.fromStorageKey(album?.coverKey) ?: return null
    return when (ref) {
        is CoverRef.Url -> ref.url
        is CoverRef.SourceRelative -> if (sameAccount) builder?.invoke(ref.coverArtId) else null
    }
}
