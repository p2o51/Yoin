package com.gpo.yoin.ui.detail

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.State
import androidx.compose.animation.AnimatedVisibility
import com.gpo.yoin.ui.theme.YoinMotionRole
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.graphicsLayer
import com.gpo.yoin.ui.theme.YoinMotion
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.gpo.yoin.R
import com.gpo.yoin.ui.component.FloatingBarButtonHeight
import com.gpo.yoin.ui.component.FloatingBarItemGap
import com.gpo.yoin.ui.component.FloatingBarSplitWidth
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
    nowPlayingOpen: Boolean = false,
    menuItems: @Composable ColumnScope.(dismissMenu: () -> Unit) -> Unit = {},
) {
    // Predictive back: the system scales this whole WINDOW during the held
    // gesture, and a bar riding a shrinking window breaks the fixed-bar
    // illusion. On gesture start the bar vanishes fast, revealing the
    // shell's identical, motionless bar beneath the preview.
    val hiddenDuringBack = LocalDetailBarHiddenDuringBack.current
    val backAlpha by animateFloatAsState(
        targetValue = if (hiddenDuringBack.value) 0f else 1f,
        animationSpec = YoinMotion.fastEffectsSpec(),
        label = "detailBarBackAlpha",
    )
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
    FloatingBottomBar(
        modifier = Modifier.graphicsLayer { alpha = backAlpha },
    ) { _ ->
        PlaySplitButton(
            playContainer = playContainer,
            playContent = playContent,
            onPlay = onPlay,
            onShuffle = onShuffle,
            buttonHeight = FloatingBarButtonHeight,
            fillPlay = true,
            compact = true,
            trailingMenuItems = menuItems,
            modifier = Modifier.width(FloatingBarSplitWidth),
        )
        Spacer(modifier = Modifier.width(FloatingBarItemGap))
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
                .fillMaxHeight(),
        )
    }
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
 * Window-level "a predictive back preview is scaling this window" flag,
 * provided by the detail Activities (derived from the session store's
 * [detailBackPreviewActive] + this window being RESUMED) and consumed by
 * [DetailBottomBar].
 */
val LocalDetailBarHiddenDuringBack = staticCompositionLocalOf<State<Boolean>> {
    error("LocalDetailBarHiddenDuringBack not provided")
}
