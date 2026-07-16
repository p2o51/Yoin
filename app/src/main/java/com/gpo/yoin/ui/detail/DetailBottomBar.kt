package com.gpo.yoin.ui.detail

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.ui.component.FloatingBarButtonHeight
import com.gpo.yoin.ui.component.FloatingBottomBar
import com.gpo.yoin.ui.component.NowPlayingPill
import com.gpo.yoin.ui.component.PlaySplitButton

/**
 * The detail pages' bottom bar — the shell Button Group's morph target.
 *
 * Same [FloatingBottomBar] scaffold and [NowPlayingPill] as the shell, with
 * the nav buttons swapped for the Play split button: the shell bar morphs
 * into exactly this composition during the shell→detail hand-off, so the
 * incoming window's delayed crossfade lands on identical pixels. Present in
 * ALL page states (Loading/Error too) — the bar never waits for page data;
 * Play simply no-ops until the tracks arrive.
 *
 * Pill tap returns to the shell with Now Playing expanding on arrival
 * ([launchShellFromDetail]) — the standard bar→NP rise, not a special effect.
 */
@Composable
fun DetailBottomBar(
    playContainer: Color,
    playContent: Color,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    miniPlayer: DetailMiniPlayerState?,
    playbackProgress: Float,
    modifier: Modifier = Modifier,
    menuItems: @Composable ColumnScope.(dismissMenu: () -> Unit) -> Unit = {},
) {
    FloatingBottomBar(modifier = modifier) {
        PlaySplitButton(
            playContainer = playContainer,
            playContent = playContent,
            onPlay = onPlay,
            onShuffle = onShuffle,
            buttonHeight = FloatingBarButtonHeight,
            trailingMenuItems = menuItems,
        )
        NowPlayingPill(
            currentTrackId = null,
            currentTrackTitle = miniPlayer?.title,
            currentTrackArtist = miniPlayer?.artist,
            currentTrackCoverArtUrl = miniPlayer?.coverArtUrl,
            connectionErrorMessage = null,
            playbackProgress = playbackProgress,
            isPlaying = miniPlayer?.isPlaying == true,
            onClick = onOpenNowPlaying,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                // Extra start gap so the pill reads as its own island beside
                // the split button — mirrors the shell bar's detail-mode pad.
                .padding(start = DetailBarPillGap),
        )
    }
}

/**
 * Launch a detail Activity from the shell with the bar hand-off animation:
 * the incoming window holds transparent while the shell bar morphs, then
 * fades in (see res/anim/detail_bar_handoff_enter.xml). The shell arms its
 * bar morph (detailChromeActive) before calling this.
 */
fun launchDetailFromShell(context: Context, intent: Intent) {
    val options = ActivityOptions.makeCustomAnimation(
        context,
        R.anim.detail_bar_handoff_enter,
        R.anim.detail_bar_handoff_exit,
    )
    context.startActivity(intent, options.toBundle())
}

/** Detail-mode gap between the split button and the pill (on top of the row's 8dp). */
val DetailBarPillGap = 16.dp
