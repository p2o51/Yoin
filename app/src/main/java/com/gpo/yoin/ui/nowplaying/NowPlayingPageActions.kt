package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens

/*
 * Now Playing's chrome, option B (owner 2026-11-26, prototype np-chrome-options):
 *
 * - Where the window is short — the height budget folds the resting lyrics to one line (a 16:9 phone, a
 *   16:10 phone like 475 × 751) — the Lyrics / About / Note text tabs ride the end of the top bar, at rest
 *   and expanded alike (at rest they are the one-line column's way to About and Note), and the 52dp tab
 *   row and its 12dp gap go to the page. Everywhere else, the tablet's enlarged column included, the
 *   expanded page keeps the big 52dp tab row (owner 2026-11-27: "height is ample, use the big pills — more
 *   expressive"). The rule reads height alone ([nowPlayingTabsInTopBar]).
 * - The bottom row, on every size: the page's own action fills the row (the lyric tools; Ask, today's tall
 *   pill; Write a note, filled in primary). About and Note end it with one Share button, opening the share
 *   sheet on that page; it greys out where the page has nothing to share (an empty About, no notes). The
 *   lyrics page has no Share key (owner 2026-11-27): its keys end with Select, and picked lines share from
 *   select mode.
 */

/**
 * Whether the tabs ride the top bar's end: only where the height budget folds the resting lyrics to one line
 * ([lyricOneLine], the short-window gate). A height rule alone — the enlarged column of a tablet in portrait
 * keeps the big tab row like any tall phone (owner 2026-11-27).
 */
internal fun nowPlayingTabsInTopBar(lyricOneLine: Boolean): Boolean = lyricOneLine

/** The share sheet's tab for a Now Playing page. */
internal fun NowPlayingDetailPage.shareTab(): ShareTab = when (this) {
    NowPlayingDetailPage.Lyrics -> ShareTab.Lyrics
    NowPlayingDetailPage.About -> ShareTab.About
    NowPlayingDetailPage.Note -> ShareTab.Note
}

/**
 * Whether [page] has anything for the share sheet — the sheet's own rule ([ShareContent.has]), read from what
 * the page shows. Where it is false the Share button greys out instead of opening an empty sheet.
 */
internal fun pageHasShare(
    page: NowPlayingDetailPage,
    state: NowPlayingUiState.Playing,
    aboutEntries: List<SongAboutEntry>,
    notes: List<SongNote>,
    aboutLabels: AboutLabels,
    reviewLabel: String,
): Boolean {
    val content = ShareContent(
        song = ShareSong(
            songId = state.songId,
            title = state.songTitle,
            artist = state.artist,
            coverArtUrl = state.coverArtUrl,
            albumName = state.albumName,
            durationMs = state.durationMs,
        ),
        // Which line doesn't matter here, only whether there is one.
        lyrics = if (page == NowPlayingDetailPage.Lyrics) {
            currentLyricsPick(state.lyrics, 0L, state.showLyricsTranslation)
        } else {
            null
        },
        about = if (page == NowPlayingDetailPage.About) {
            aboutSharePick(
                entries = aboutEntries,
                credits = state.lyricCredits,
                facts = state.spineFacts,
                labels = aboutLabels,
                reviewLabel = reviewLabel,
            )
        } else {
            null
        },
        note = if (page == NowPlayingDetailPage.Note) noteSharePick(notes, null, 0L) else null,
    )
    return content.has(page.shareTab())
}

/** A page's Share: [enabled] while it has something to share; [open] opens the sheet on it. */
@Immutable
internal data class PageShare(val enabled: Boolean, val open: () -> Unit)

/**
 * The Share of each page for the song on show: the sheet opens on that page's tab (the lyrics' current
 * line, About's paragraph, the note at the playhead). Without a share sheet (previews, tests) it still
 * greys and lights by the content but opens nothing.
 */
@Composable
internal fun rememberPageShares(
    state: NowPlayingUiState.Playing,
    aboutUiState: AboutUiState,
    notes: List<SongNote>,
): (NowPlayingDetailPage) -> PageShare {
    val host = LocalShareSheetHost.current
    val resources = LocalResources.current
    val aboutEntries = (aboutUiState as? AboutUiState.Ready)?.entries.orEmpty()
    val labels = remember(resources) { resourceAboutLabels(resources) to resources.getString(R.string.np_about_review) }
    val available = remember(state.songId, state.lyrics, state.lyricCredits, state.spineFacts, aboutEntries, notes, labels) {
        NowPlayingDetailPage.entries.associateWith { page ->
            pageHasShare(page, state, aboutEntries, notes, labels.first, labels.second)
        }
    }
    return remember(host, available) {
        { page ->
            PageShare(enabled = available[page] == true) {
                host?.open(ShareRequest(page.shareTab()))
            }
        }
    }
}

/**
 * The bottom row of an expanded page: [lead] (the lyric tools, the Ask pill, the write bar) takes the row,
 * the Share button its end. [showShare] false (Ask open for typing, a note being written, lyrics being
 * picked) gives the whole row to [lead]; Share folds away on the spatial spring.
 */
@Composable
internal fun PageActionRow(
    share: PageShare,
    showShare: Boolean,
    modifier: Modifier = Modifier,
    lead: @Composable (Modifier) -> Unit,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        lead(Modifier.weight(1f).fillMaxHeight())
        AnimatedVisibility(
            visible = showShare,
            enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard) +
                YoinMotion.expandHorizontally(role = YoinMotionRole.Standard, expandFrom = Alignment.Start),
            exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard) +
                YoinMotion.shrinkHorizontally(role = YoinMotionRole.Standard, shrinkTowards = Alignment.Start),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Spacer(modifier = Modifier.width(PageShareGap))
                PageShareButton(share = share)
            }
        }
    }
}

/** Share as image for the page shown: a round tonal button, greyed while there is nothing. */
@Composable
internal fun PageShareButton(
    share: PageShare,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    val scheme = MaterialTheme.colorScheme
    // Lit and greyed on the effects spring: a colour change.
    val container by animateColorAsState(
        targetValue = scheme.surfaceContainerHigh.copy(alpha = 0.92f)
            .let { if (share.enabled) it else it.copy(alpha = it.alpha * 0.5f) },
        animationSpec = YoinMotion.effectsSpring(),
        label = "pageShareContainer",
    )
    val content by animateColorAsState(
        targetValue = scheme.onSurface.let { if (share.enabled) it else it.copy(alpha = 0.38f) },
        animationSpec = YoinMotion.effectsSpring(),
        label = "pageShareContent",
    )
    val interaction = remember { MutableInteractionSource() }
    Box(modifier = modifier.size(PageShareSize), contentAlignment = Alignment.Center) {
        FilledTonalIconButton(
            onClick = {
                haptics.performContextClick()
                share.open()
            },
            enabled = share.enabled,
            interactionSource = interaction,
            shape = YoinShapeTokens.Full,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = container,
                contentColor = content,
                disabledContainerColor = container,
                disabledContentColor = content,
            ),
            modifier = Modifier
                .size(PageShareSize)
                .handPointer(enabled = share.enabled)
                .testTag(PageShareTag),
        ) {
            Icon(
                imageVector = YoinSymbols.Share,
                contentDescription = stringResource(R.string.np_share_sheet_title),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** The Share button beside the 56dp Ask pill and write capsule: the same height, a circle. */
private val PageShareSize = 56.dp
private val PageShareGap = 8.dp

internal const val PageShareTag = "pageShare"
