package com.gpo.yoin.ui.detail

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.gpo.yoin.R
import com.gpo.yoin.ui.component.BarPlaySplitActions
import com.gpo.yoin.ui.component.YoinButtonGroup
import com.gpo.yoin.ui.navigation.YoinSection

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
    nowPlayingOpen: Boolean = false,
    // Predictive-back scrub (0 = resting detail chrome, 1 = fully nav): the
    // gesture drives the split⇄nav morph interactively when this page will
    // reveal the shell. Pages stacked over another detail keep 0 — the bar
    // beneath is identical, so the correct read is "the bar doesn't move".
    backMorphProgress: () -> Float = { 0f },
    menuItems: @Composable ColumnScope.(dismissMenu: () -> Unit) -> Unit = {},
) {
    // Same choreography as the shell bar when NP expands over it: the bar
    // slides down out of the way while the player rises.
    AnimatedVisibility(
        visible = !nowPlayingOpen,
        enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard) +
            YoinMotion.slideInVertically(role = YoinMotionRole.Standard) { it },
        exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard) +
            YoinMotion.slideOutVertically(role = YoinMotionRole.Standard) { it },
        modifier = modifier,
    ) {
        // LITERALLY the shell's bar composable — pixel identity between the
        // two windows by construction, plus the nav side of the morph for
        // the predictive-back scrub.
        YoinButtonGroup(
            selectedSection = YoinSection.HOME,
            currentTrackId = null,
            currentTrackTitle = miniPlayer?.title,
            currentTrackArtist = miniPlayer?.artist,
            currentTrackCoverArtUrl = miniPlayer?.coverArtUrl,
            isPlaybackReady = true,
            connectionErrorMessage = null,
            playbackProgress = playbackProgress,
            isPlaying = miniPlayer?.isPlaying == true,
            chromeProgress = {
                (1f - backMorphProgress()).coerceIn(0f, 1f)
            },
            playSplitActions = BarPlaySplitActions(
                playContainer = playContainer,
                playContent = playContent,
                onPlay = onPlay,
                onShuffle = onShuffle,
                menuItems = menuItems,
            ),
            onHomeClick = {},
            onNowPlayingClick = onOpenNowPlaying,
            onLibraryClick = {},
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
    intent.putExtra(DETAIL_EXTRA_FROM_SHELL, true)
    val options = ActivityOptions.makeCustomAnimation(
        context,
        R.anim.detail_bar_handoff_enter,
        R.anim.detail_bar_handoff_exit,
    )
    context.startActivity(intent, options.toBundle())
}

/**
 * Detail Activities call this in onCreate: the CLOSE transition becomes an
 * in-place dissolve so the window's bar stays pixel-aligned over the bar
 * beneath it (shell or another detail page) — the bar reads as one fixed
 * element while only the page content fades. The shell's split→nav reverse
 * morph then plays in full view after the window settles. Pre-34 keeps the
 * system close animation (no per-gesture hook exists there).
 */
fun Activity.applyDetailCloseTransition() {
    if (Build.VERSION.SDK_INT >= 34) {
        overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_CLOSE,
            R.anim.detail_bar_close_enter,
            R.anim.detail_bar_close_exit,
        )
    }
}

/**
 * Set by [launchDetailFromShell]: this detail window sits directly over the
 * SHELL, so its predictive-back scrub should morph the bar toward nav
 * chrome. Detail→detail pushes lack it — the bar beneath is identical.
 */
const val DETAIL_EXTRA_FROM_SHELL = "fromShell"
