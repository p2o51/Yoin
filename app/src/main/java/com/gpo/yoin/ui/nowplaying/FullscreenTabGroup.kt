package com.gpo.yoin.ui.nowplaying

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
    )
}

private val Pages = listOf(NowPlayingDetailPage.Lyrics, NowPlayingDetailPage.About, NowPlayingDetailPage.Note)
