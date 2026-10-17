package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.detail.TrackMenu
import com.gpo.yoin.ui.detail.TrackMenuHost
import com.gpo.yoin.ui.detail.TrackMenuToggleItem
import com.gpo.yoin.ui.experience.YoinHaptics
import com.gpo.yoin.ui.experience.rememberYoinHaptics

/**
 * The song menu Now Playing's window provides (owner, 2026-10-17: long-press
 * the cover for the song's actions plus Keep screen on). Null in previews and
 * harnesses: the cover then has no long-press.
 */
val LocalNowPlayingCoverMenu = staticCompositionLocalOf<TrackMenuHost?> { null }

/** One cover's long-press menu: whether it is open, and what opens it. */
@Stable
internal class CoverMenuState(
    val host: TrackMenuHost?,
    private val haptics: YoinHaptics,
    val longClickLabel: String,
) {
    var open by mutableStateOf(false)

    fun show() {
        if (host == null) return
        haptics.performLongPress()
        open = true
    }
}

@Composable
internal fun rememberCoverMenuState(): CoverMenuState {
    val host = LocalNowPlayingCoverMenu.current
    val haptics = rememberYoinHaptics()
    val label = stringResource(R.string.np_cd_cover_menu)
    return remember(host, haptics, label) { CoverMenuState(host, haptics, label) }
}

/**
 * The cover's gestures: [onClick] where the cover focuses or unfocuses (null:
 * no tap, the cover stays a plain image to taps), and a long-press that opens
 * [menu] whenever the window provides one. No ripple: the artwork's own
 * press dip ([interactionSource]) is the feedback.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.nowPlayingCoverGestures(
    interactionSource: MutableInteractionSource,
    menu: CoverMenuState,
    onClickLabel: String? = null,
    onClick: (() -> Unit)?,
): Modifier {
    val longPress: (() -> Unit)? = if (menu.host != null) menu::show else null
    return when {
        onClick != null -> combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClickLabel = onClickLabel,
            onLongClickLabel = menu.longClickLabel.takeIf { longPress != null },
            onLongClick = longPress,
            onClick = onClick,
        )
        longPress != null -> this
            .pointerInput(menu) { detectTapGestures(onLongPress = { longPress() }) }
            .semantics { onLongClick(label = menu.longClickLabel) { longPress(); true } }
        else -> this
    }
}

/**
 * The cover's song menu: the shared song rows ([TrackMenu]) with a player
 * section, Keep screen on. Anchored to the cover it is composed in.
 */
@Composable
internal fun NowPlayingCoverMenu(songId: String, menu: CoverMenuState) {
    val host = menu.host ?: return
    val context = LocalContext.current
    val keepScreenOn = rememberNowPlayingKeepScreenOn()
    TrackMenu(
        songId = songId,
        expanded = menu.open,
        onDismissRequest = { menu.open = false },
        host = host,
        playerSection = {
            TrackMenuToggleItem(
                text = stringResource(R.string.np_keep_screen_on),
                icon = YoinSymbols.LightMode,
                checked = keepScreenOn,
                onCheckedChange = { NowPlayingKeepScreenOn.set(context, it) },
            )
        },
    )
}
