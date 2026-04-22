package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens

/**
 * Fullscreen detail surface opened from inside Now Playing. Rendered over
 * the compact content by an [androidx.compose.animation.AnimatedVisibility]
 * in `NowPlayingScreen` — it does NOT install a second nav destination
 * and does NOT register its own shared-element keys. Back priority is
 * handled by YoinNavHost.
 *
 * Three pages (Lyrics / About / Note) share selection with the compact
 * pager via [detailPage]. The Ask Gemini bar is rendered as a Box
 * overlay anchored to the bottom of the screen so that expanding it
 * visually covers the song title + artist sitting directly above.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NowPlayingFullscreenPane(
    state: NowPlayingUiState.Playing,
    detailPage: NowPlayingDetailPage,
    onDetailPageChange: (NowPlayingDetailPage) -> Unit,
    onBack: () -> Unit,
    aboutUiState: AboutUiState,
    askState: AskBarState,
    onAboutOpened: () -> Unit,
    onAskQuestion: (String) -> Unit,
    onAskBarFocused: () -> Unit,
    onAskBarCollapseRequested: () -> Unit,
    onDismissAskError: () -> Unit,
    onRetryCanonical: () -> Unit,
    notes: List<SongNote>,
    onSaveNote: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val background = MaterialTheme.colorScheme.background

    ProvideYoinMotionRole(role = YoinMotionRole.Expressive) {
        // Gradient paints edge-to-edge (under status bar + nav bar).
        // Insets are consumed at the content layer, not the background:
        // top via statusBarsPadding, bottom via each component's own
        // navigationBarsPadding / imePadding — so the Ask bar can lift
        // above nav bar + keyboard itself without fighting a parent Box.
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(surfaceContainer, background)))
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.Start,
            ) {
                TopBar(
                    onBack = onBack,
                    state = state,
                    onAlbumClick = onAlbumClick,
                    onArtistClick = onArtistClick,
                    onPlaylistClick = onPlaylistClick,
                )

                Spacer(modifier = Modifier.height(12.dp))

                FullscreenTabGroup(
                    selected = detailPage,
                    onSelect = onDetailPageChange,
                )

                Spacer(modifier = Modifier.height(16.dp))

                val pagerState = rememberPagerState(
                    initialPage = detailPage.ordinal,
                    pageCount = { 3 },
                )
                LaunchedEffect(detailPage) {
                    if (pagerState.currentPage != detailPage.ordinal) {
                        pagerState.animateScrollToPage(detailPage.ordinal)
                    }
                }
                LaunchedEffect(pagerState.currentPage) {
                    val page = NowPlayingDetailPage.entries[pagerState.currentPage]
                    if (page != detailPage) onDetailPageChange(page)
                    if (page == NowPlayingDetailPage.About) onAboutOpened()
                }

                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) { page ->
                    when (NowPlayingDetailPage.entries[page]) {
                        NowPlayingDetailPage.Lyrics -> LyricsFullscreenPane(
                            lyrics = state.lyrics,
                            positionMs = state.positionMs,
                            loading = state.lyricsLoading,
                            modifier = Modifier.fillMaxSize(),
                        )
                        NowPlayingDetailPage.About -> AboutFullscreenPane(
                            aboutUiState = aboutUiState,
                            onRetryCanonical = onRetryCanonical,
                            modifier = Modifier.fillMaxSize(),
                        )
                        NowPlayingDetailPage.Note -> NoteFullscreenPane(
                            notes = notes,
                            onSave = onSaveNote,
                            onDelete = onDeleteNote,
                            autoFocusComposer = detailPage == NowPlayingDetailPage.Note,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom title + artist. Always in the layout; when the
                // Ask bar expands it renders on top of this via the
                // sibling overlay below.
                BottomHero(
                    title = state.songTitle,
                    artist = state.artist,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                // Reserve room for the floating Ask bar (56dp idle +
                // 8dp bottom padding + breathing). Without this the hero
                // content sits directly under the idle bar. About-only,
                // so Lyrics/Note tabs get the full bottom area.
                if (detailPage == NowPlayingDetailPage.About) {
                    Spacer(modifier = Modifier.height(72.dp))
                }
            }

            // Ask Gemini bar — only rendered on the About page. Anchored
            // to the bottom so expanding upward covers the hero above.
            if (detailPage == NowPlayingDetailPage.About) {
                AskGeminiBar(
                    askState = askState,
                    onSubmit = onAskQuestion,
                    onFocus = onAskBarFocused,
                    onCollapseRequest = onAskBarCollapseRequested,
                    onDismissError = onDismissAskError,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // The outer Box only applies statusBarsPadding —
                        // the Ask bar owns the bottom inset itself so it
                        // can both rise with the keyboard (imePadding)
                        // AND stay above the nav bar when the keyboard
                        // is hidden.
                        .imePadding()
                        .navigationBarsPadding()
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    onBack: () -> Unit,
    state: NowPlayingUiState.Playing,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Close fullscreen",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(modifier = Modifier.size(4.dp))
        Column {
            val kindLabel: String
            val nameLabel: String
            val routeAction: (() -> Unit)?
            when (val ctx = state.activityContext) {
                is com.gpo.yoin.data.repository.ActivityContext.Album -> {
                    kindLabel = "PLAYING FROM ALBUM"
                    nameLabel = ctx.albumName
                    routeAction = { onAlbumClick(ctx.albumId) }
                }
                is com.gpo.yoin.data.repository.ActivityContext.Playlist -> {
                    kindLabel = "PLAYING FROM PLAYLIST"
                    nameLabel = ctx.playlistName
                    routeAction = { onPlaylistClick(ctx.playlistId) }
                }
                is com.gpo.yoin.data.repository.ActivityContext.Artist -> {
                    kindLabel = "PLAYING FROM ARTIST"
                    nameLabel = ctx.artistName
                    routeAction = { onArtistClick(ctx.artistId) }
                }
                com.gpo.yoin.data.repository.ActivityContext.None -> {
                    kindLabel = "PLAYING FROM"
                    nameLabel = state.albumName
                    routeAction = null
                }
            }
            Text(
                text = kindLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val nameModifier = if (routeAction != null) {
                Modifier.clickable { routeAction() }
            } else {
                Modifier
            }
            Text(
                text = nameLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = nameModifier,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FullscreenTabGroup(
    selected: NowPlayingDetailPage,
    onSelect: (NowPlayingDetailPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        val lyricsInteraction = remember { MutableInteractionSource() }
        val aboutInteraction = remember { MutableInteractionSource() }
        val noteInteraction = remember { MutableInteractionSource() }

        ButtonGroup(
            overflowIndicator = { _ -> },
            modifier = modifier
                .fillMaxWidth()
                .height(52.dp),
            expandedRatio = ButtonGroupDefaults.ExpandedRatio,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Three buttons each take an equal 1f weight so the group
            // fills the available width. `animateWidth` still animates
            // size changes on press within the shared slot.
            customItem(
                buttonGroupContent = {
                    TabButton(
                        label = "Lyrics",
                        isSelected = selected == NowPlayingDetailPage.Lyrics,
                        interactionSource = lyricsInteraction,
                        onClick = { onSelect(NowPlayingDetailPage.Lyrics) },
                        modifier = Modifier.weight(1f),
                    )
                },
                menuContent = { _ -> },
            )
            customItem(
                buttonGroupContent = {
                    TabButton(
                        label = "About",
                        isSelected = selected == NowPlayingDetailPage.About,
                        interactionSource = aboutInteraction,
                        onClick = { onSelect(NowPlayingDetailPage.About) },
                        modifier = Modifier.weight(1f),
                    )
                },
                menuContent = { _ -> },
            )
            customItem(
                buttonGroupContent = {
                    TabButton(
                        label = "Note",
                        isSelected = selected == NowPlayingDetailPage.Note,
                        interactionSource = noteInteraction,
                        onClick = { onSelect(NowPlayingDetailPage.Note) },
                        modifier = Modifier.weight(1f),
                    )
                },
                menuContent = { _ -> },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ButtonGroupScope.TabButton(
    label: String,
    isSelected: Boolean,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "tabContainer",
    )
    val content by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        },
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "tabContent",
    )
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxHeight()
            .animateWidth(interactionSource),
        interactionSource = interactionSource,
        shape = YoinShapeTokens.Full,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = container,
            contentColor = content,
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun BottomHero(
    title: String,
    artist: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = artist,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = MaterialTheme.typography.titleMedium.fontSize * 0.9f,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
