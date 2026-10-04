package com.gpo.yoin.ui.component

import com.gpo.yoin.ui.theme.YoinMotion
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.experience.EdgeSplitContentStart
import com.gpo.yoin.ui.experience.EdgeSplitSide
import com.gpo.yoin.ui.experience.rememberEdgeSplitSide
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
 * [paneProgress] is the Wide shell's merged pose while its detail column is
 * open (adaptive principle 2: one window, one bar). [editPose] is the
 * shell's Home edit pose, `[Undo|Add] [Done]`.
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
    // Wide shell with its detail column open: the one bar spans both columns,
    // [Home][Library][pill][Play ▾][Shuffle] (0 = nav pose, 1 = merged).
    paneProgress: () -> Float = { 0f },
    playbackProgress: Float = 0f,
    isPlaying: Boolean = false,
    chromeProgress: () -> Float = { 0f },
    exitProgress: () -> Float = { 0f },
    // Home edit pose for either form; off everywhere but the shell.
    editPose: BarEditPose? = null,
    editing: Boolean = false,
    playSplitActions: BarPlaySplitActions? = null,
    onLibraryLongClick: () -> Unit = onLibraryClick,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    // The Play split's colours arrive as TARGETS (a page's cover palette, the
    // theme stand-in while a column page is still building): they animate
    // here, once, for every form — a palette landing never snaps the button.
    val playContainer by animateColorAsState(
        targetValue = playSplitActions?.playContainer ?: MaterialTheme.colorScheme.primary,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "barPlayContainer",
    )
    val playContent by animateColorAsState(
        targetValue = playSplitActions?.playContent ?: MaterialTheme.colorScheme.onPrimary,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "barPlayContent",
    )
    val animatedPlaySplitActions = playSplitActions?.let { actions ->
        remember(actions, playContainer, playContent) {
            BarPlaySplitActions(
                playContainer = playContainer,
                playContent = playContent,
                onPlay = actions.onPlay,
                onShuffle = actions.onShuffle,
                menuItems = actions.menuItems,
                promotable = actions.promotable,
            )
        }
    }
    when (form) {
        ShellChromeForm.EdgeSplit -> {
        // The capsules leave over their own edge (the cutout's).
        val edgeOut = if (rememberEdgeSplitSide() == EdgeSplitSide.Right) 1f else -1f
        YoinEdgeSplitGroup(
            selectedSection = selectedSection,
            currentTrackId = currentTrackId,
            currentTrackTitle = currentTrackTitle,
            currentTrackArtist = currentTrackArtist,
            currentTrackCoverArtUrl = currentTrackCoverArtUrl,
            connectionErrorMessage = connectionErrorMessage,
            playbackProgress = playbackProgress,
            isPlaying = isPlaying,
            chromeProgress = chromeProgress,
            playSplitActions = animatedPlaySplitActions,
            editPose = editPose,
            editing = editing,
            onHomeClick = onHomeClick,
            onNowPlayingClick = onNowPlayingClick,
            onLibraryClick = onLibraryClick,
            onLibraryLongClick = onLibraryLongClick,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = edgeOut * EdgeSplitContentStart.toPx() * 1.15f *
                        exitProgress().coerceIn(0f, 1f)
                },
        )

        }

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
            playSplitActions = animatedPlaySplitActions,
            onHomeClick = onHomeClick,
            onNowPlayingClick = onNowPlayingClick,
            onLibraryClick = onLibraryClick,
            onLibraryLongClick = onLibraryLongClick,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            centered = form == ShellChromeForm.CenteredBar,
            wideMargin = wide,
            navOnly = navOnly,
            paneProgress = paneProgress,
            editPose = editPose,
            editing = editing,
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

@androidx.compose.ui.tooling.preview.Preview(
    name = "Centred bar · editing Home",
    widthDp = 800,
    heightDp = 160,
    showBackground = true,
)
@Composable
private fun YoinChromeGroupCenteredEditPreview() {
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
            editPose = BarEditPose(
                progress = { 1f },
                leftSlot = { BarEditLeftSlot.Add },
                onLeftSlotClick = {},
                onDone = {},
            ),
            editing = true,
            onHomeClick = {},
            onNowPlayingClick = {},
            onLibraryClick = {},
        )
    }
}
