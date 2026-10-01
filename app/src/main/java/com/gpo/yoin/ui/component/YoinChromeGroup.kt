package com.gpo.yoin.ui.component

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.experience.EdgeSplitContentStart
import com.gpo.yoin.ui.experience.ShellChromeForm
import com.gpo.yoin.ui.navigation.YoinSection

/**
 * The ONE Button Group, in whichever form the window reads
 * ([ShellChromeForm], 断点交接 §1–§2): the portrait bottom bar, the centred
 * capped bar, or the edge-split capsules. The shell and every detail
 * Activity render it through here, so both windows of a hand-off always
 * agree on the form.
 *
 * [exitProgress] rides the whole group off-screen 1:1 — down for the bar
 * forms, left for the capsules (detail pages opened over Now Playing).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun YoinChromeGroup(
    form: ShellChromeForm,
    selectedSection: YoinSection,
    currentTrackId: String?,
    currentTrackTitle: String?,
    currentTrackArtist: String?,
    currentTrackCoverArtUrl: String?,
    isPlaybackReady: Boolean,
    connectionErrorMessage: String?,
    onHomeClick: () -> Unit,
    onNowPlayingClick: () -> Unit,
    onLibraryClick: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    navOnly: Boolean = false,
    playbackProgress: Float = 0f,
    isPlaying: Boolean = false,
    chromeProgress: () -> Float = { 0f },
    exitProgress: () -> Float = { 0f },
    playSplitActions: BarPlaySplitActions? = null,
    onLibraryLongClick: () -> Unit = onLibraryClick,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    when (form) {
        ShellChromeForm.EdgeSplit -> YoinEdgeSplitGroup(
            selectedSection = selectedSection,
            currentTrackId = currentTrackId,
            currentTrackTitle = currentTrackTitle,
            currentTrackArtist = currentTrackArtist,
            currentTrackCoverArtUrl = currentTrackCoverArtUrl,
            connectionErrorMessage = connectionErrorMessage,
            playbackProgress = playbackProgress,
            isPlaying = isPlaying,
            chromeProgress = chromeProgress,
            playSplitActions = playSplitActions,
            onHomeClick = onHomeClick,
            onNowPlayingClick = onNowPlayingClick,
            onLibraryClick = onLibraryClick,
            onLibraryLongClick = onLibraryLongClick,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = -EdgeSplitContentStart.toPx() * 1.15f *
                        exitProgress().coerceIn(0f, 1f)
                },
        )

        ShellChromeForm.PortraitBar,
        ShellChromeForm.CenteredBar,
        -> YoinButtonGroup(
            selectedSection = selectedSection,
            currentTrackId = currentTrackId,
            currentTrackTitle = currentTrackTitle,
            currentTrackArtist = currentTrackArtist,
            currentTrackCoverArtUrl = currentTrackCoverArtUrl,
            isPlaybackReady = isPlaybackReady,
            connectionErrorMessage = connectionErrorMessage,
            playbackProgress = playbackProgress,
            isPlaying = isPlaying,
            chromeProgress = chromeProgress,
            playSplitActions = playSplitActions,
            onHomeClick = onHomeClick,
            onNowPlayingClick = onNowPlayingClick,
            onLibraryClick = onLibraryClick,
            onLibraryLongClick = onLibraryLongClick,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            centered = form == ShellChromeForm.CenteredBar,
            wideMargin = wide,
            navOnly = navOnly,
            // Its own height plus spare covers the nav-bar inset the scaffold
            // carries internally.
            modifier = modifier.graphicsLayer {
                translationY = size.height * 1.15f * exitProgress().coerceIn(0f, 1f)
            },
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(
    name = "Centred bar · detail pose",
    widthDp = 690,
    heightDp = 160,
    showBackground = true,
)
@Composable
private fun YoinChromeGroupCenteredDetailPreview() {
    com.gpo.yoin.ui.theme.YoinTheme {
        YoinChromeGroup(
            form = ShellChromeForm.CenteredBar,
            selectedSection = YoinSection.HOME,
            currentTrackId = "1",
            currentTrackTitle = "RUNNING TO YOU",
            currentTrackArtist = "Blusher",
            currentTrackCoverArtUrl = null,
            isPlaybackReady = true,
            connectionErrorMessage = null,
            chromeProgress = { 1f },
            playSplitActions = BarPlaySplitActions(
                playContainer = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                playContent = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                onPlay = {},
                onShuffle = {},
                menuItems = {},
                promotable = listOf(
                    BarExtraAction(YoinSymbols.Artist, "Go to artist") {},
                    BarExtraAction(YoinSymbols.Share, "Share") {},
                ),
            ),
            onHomeClick = {},
            onNowPlayingClick = {},
            onLibraryClick = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(
    name = "Centred bar · nav pose",
    widthDp = 800,
    heightDp = 160,
    showBackground = true,
)
@Composable
private fun YoinChromeGroupCenteredNavPreview() {
    com.gpo.yoin.ui.theme.YoinTheme {
        YoinChromeGroup(
            form = ShellChromeForm.CenteredBar,
            selectedSection = YoinSection.LIBRARY,
            currentTrackId = "1",
            currentTrackTitle = "RUNNING TO YOU",
            currentTrackArtist = "Blusher",
            currentTrackCoverArtUrl = null,
            isPlaybackReady = true,
            connectionErrorMessage = null,
            onHomeClick = {},
            onNowPlayingClick = {},
            onLibraryClick = {},
        )
    }
}
