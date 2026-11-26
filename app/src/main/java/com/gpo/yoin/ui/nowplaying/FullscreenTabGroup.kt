package com.gpo.yoin.ui.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.gpo.yoin.R
import com.gpo.yoin.ui.component.YoinTabGroup

/** The expanded page's Lyrics | About | Note group (the shared [YoinTabGroup]). */
@Composable
internal fun FullscreenTabGroup(
    selected: NowPlayingDetailPage,
    onSelect: (NowPlayingDetailPage) -> Unit,
    modifier: Modifier = Modifier,
    /** Gemini is loading About over content already shown: an indicator between About and Note. */
    aboutBusy: Boolean = false,
) {
    YoinTabGroup(
        labels = listOf(
            stringResource(R.string.np_tab_lyrics),
            stringResource(R.string.np_tab_about),
            stringResource(R.string.np_tab_note),
        ),
        selectedIndex = Pages.indexOf(selected),
        onSelect = { index -> onSelect(Pages[index]) },
        modifier = modifier,
        busyAfterIndex = Pages.indexOf(NowPlayingDetailPage.About),
        busy = aboutBusy,
        // The tabs not chosen are neutral (owner 2026-11-26): in dark Expressive schemes primary and
        // primaryContainer are nearly one tone, so a primaryContainer tab read as chosen too.
        container = MaterialTheme.colorScheme.onSurface.copy(alpha = NeutralTabAlpha),
        content = MaterialTheme.colorScheme.onSurface,
    )
}

/** The unchosen tabs' wash of onSurface: neutral, light or dark. */
internal const val NeutralTabAlpha = 0.09f

private val Pages = listOf(NowPlayingDetailPage.Lyrics, NowPlayingDetailPage.About, NowPlayingDetailPage.Note)
