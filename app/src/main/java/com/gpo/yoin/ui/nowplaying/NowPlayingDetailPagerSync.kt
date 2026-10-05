package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Two-way sync between the detail pager and the model's [detailPage], ONE
 * driver per direction: external writes (the model) animate the pager; the
 * model learns the page only from SETTLED pages, so a two-page tab jump never
 * writes the page it sweeps across.
 *
 * The settled-page collector compares against the LATEST [detailPage]. It used
 * to compare against the value captured when the effect launched: after a
 * Lyrics → About → Lyrics round trip the return looked like "already Lyrics"
 * and was never reported, so the model stayed on About — the lyric tools'
 * auto-hide (eligible only on Lyrics) never re-armed, and a later About tap
 * no-oped because the model already said About.
 *
 * Settling AWAY from Note also lets go of the keyboard: the pager keeps a
 * focused page composed, so the note composer would otherwise stay focused
 * off screen with the IME up, typing into a draft nobody can see.
 */
@Composable
internal fun SyncDetailPageWithPager(
    pagerState: PagerState,
    detailPage: NowPlayingDetailPage,
    onDetailPageChange: (NowPlayingDetailPage) -> Unit,
    onAboutOpened: () -> Unit,
) {
    LaunchedEffect(detailPage) {
        if (detailPage.ordinal != pagerState.targetPage) {
            pagerState.settleToPage(detailPage.ordinal)
        }
    }
    val latestDetailPage by rememberUpdatedState(detailPage)
    val latestOnDetailPageChange by rememberUpdatedState(onDetailPageChange)
    val latestOnAboutOpened by rememberUpdatedState(onAboutOpened)
    val focusManager = LocalFocusManager.current
    LaunchedEffect(pagerState) {
        var previous: NowPlayingDetailPage? = null
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            val page = NowPlayingDetailPage.entries[settled]
            if (leftNotePage(previous, page)) focusManager.clearFocus()
            previous = page
            if (page != latestDetailPage) latestOnDetailPageChange(page)
            if (page == NowPlayingDetailPage.About) latestOnAboutOpened()
        }
    }
}

/** The pager settled off the Note page — the composer's focus (and the IME) must go. */
internal fun leftNotePage(previous: NowPlayingDetailPage?, settled: NowPlayingDetailPage): Boolean =
    previous == NowPlayingDetailPage.Note && settled != NowPlayingDetailPage.Note

/**
 * Animate to [target] and pin the landing: the stage reshape remeasures the
 * pager mid-flight, which can strand animateScrollToPage between pages —
 * finish with an exact snap when that happens.
 */
internal suspend fun PagerState.settleToPage(target: Int) {
    if (currentPage == target && currentPageOffsetFraction == 0f) return
    animateScrollToPage(target)
    if (currentPage != target || currentPageOffsetFraction != 0f) {
        scrollToPage(target)
    }
}
