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
    )
}

private val Pages = listOf(NowPlayingDetailPage.Lyrics, NowPlayingDetailPage.About, NowPlayingDetailPage.Note)
