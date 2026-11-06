package com.gpo.yoin.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.gpo.yoin.R
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.settings.AccountAvatar
import com.gpo.yoin.ui.settings.ProfileCard
import com.gpo.yoin.ui.settings.monogramOf
import com.gpo.yoin.ui.settings.serviceIdentity
import com.gpo.yoin.ui.theme.YoinMotion
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Quick switch (owner 2026-11-06, after PiliPaw's hold menu): press and hold
 * the header avatar and the other accounts drop out of it in a column, one
 * after another, each with its name beside it. Slide down onto one (it swells
 * and ticks) and let go to switch to it; let go anywhere else and nothing
 * happens. A short tap still opens the switcher's card — the hold only takes
 * over once the press is long enough, and a drag before then is the page's.
 */

/** The other accounts the header avatar offers on a hold, and what letting go on one does. */
@Immutable
internal data class HomeAccountQuickSwitch(
    /** In the switcher's order, without the one the header shows. */
    val accounts: List<ProfileCard>,
    val onPick: (ProfileCard) -> Unit,
)

/** The hold under way: whether the column is out and which account the finger is on (-1: none). */
@Stable
internal class HomeAccountFanState {
    var holding by mutableStateOf(false)
    var hover by mutableIntStateOf(-1)
    var anchorWidth by mutableIntStateOf(0)
    var anchorHeight by mutableIntStateOf(0)

    // The accounts as they were when the hold began: the column doesn't reshuffle under the finger.
    var accounts by mutableStateOf(emptyList<ProfileCard>())
}

// The column's items: the size of the header's button, so they sit right under its avatar.
private val FanItem = 48.dp
private val FanAvatar = 36.dp
private val FanStep = 60.dp
private val FanFirstGap = 12.dp

// How far sideways the finger may stray (from the avatar's centre) and still be on an item: the labels too.
private val FanReach = 200.dp
private val FanWidth = 260.dp

// Each item starts this much (of the column's entrance) after the one before it.
private const val FAN_STAGGER = 0.12f

private fun itemCenterY(state: HomeAccountFanState, density: Density, index: Int): Float = with(density) {
    state.anchorHeight + FanFirstGap.toPx() + FanItem.toPx() / 2 + index * FanStep.toPx()
}

/**
 * The hold's state and the modifier that runs it, for the header avatar's box.
 * Null [quickSwitch] (no other accounts, a switch under way, the card open)
 * or not [enabled]: no hold, the tap alone.
 */
@Composable
internal fun rememberHomeAccountFan(
    quickSwitch: HomeAccountQuickSwitch?,
    enabled: Boolean,
    state: HomeAccountFanState = remember { HomeAccountFanState() },
): Pair<HomeAccountFanState, Modifier> {
    val haptics = rememberYoinHaptics()
    val density = LocalDensity.current
    val current by rememberUpdatedState(quickSwitch)
    // Off (a switch begins, the card opens): the gesture goes, and a hold under way with it (its finally).
    val active = enabled && quickSwitch != null && quickSwitch.accounts.isNotEmpty()
    // TalkBack: each account as an action on the avatar.
    val actions = quickSwitch?.accounts.orEmpty().map { card ->
        CustomAccessibilityAction(stringResource(R.string.home_account_quick_switch_action, card.title)) {
            current?.onPick?.invoke(card)
            true
        }
    }
    val gesture = if (!active) {
        Modifier
    } else {
        Modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    state.anchorWidth = size.width
                    state.anchorHeight = size.height
                    // Up, or a drag, before the hold time: not ours (the tap opens the card, the drag scrolls).
                    val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var gone = false
                        while (!gone) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                .firstOrNull { it.id == down.id }
                            gone = change == null || !change.pressed || change.isConsumed ||
                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                        }
                        true
                    }
                    if (early != null) return@awaitEachGesture
                    val accounts = current?.accounts.orEmpty()
                    if (accounts.isEmpty()) return@awaitEachGesture
                    state.accounts = accounts
                    state.hover = -1
                    state.holding = true
                    haptics.performLongPress()
                    val step = with(density) { FanStep.toPx() }
                    val reach = with(density) { FanReach.toPx() }
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            // Ours from here: no tap on the way up, no scroll under it.
                            event.changes.forEach { it.consume() }
                            val p = change.position
                            val near = accounts.indices.minByOrNull { abs(itemCenterY(state, density, it) - p.y) }
                            val hit = near?.takeIf {
                                abs(itemCenterY(state, density, it) - p.y) < step / 2 &&
                                    abs(p.x - state.anchorWidth / 2f) < reach
                            } ?: -1
                            if (hit != state.hover) {
                                state.hover = hit
                                if (hit >= 0) haptics.performSegmentTick()
                            }
                            if (!change.pressed) break
                        }
                        val chosen = accounts.getOrNull(state.hover)
                        if (chosen != null) {
                            haptics.performConfirm()
                            current?.onPick?.invoke(chosen)
                        }
                    } finally {
                        state.holding = false
                        state.hover = -1
                    }
                }
            }
            .semantics { customActions = actions }
    }
    return state to gesture
}

/**
 * The column itself. Call it inside the header avatar's box: its popup starts
 * at the box's top-end corner (the header's corner) and opens down, the
 * names on the items' start side, toward the page. [inline]: drawn in place
 * instead, over what follows it, sized as nothing (the Tips demo phone, whose
 * scaled screen a popup window would leave).
 */
@Suppress("ktlint:standard:function-naming") // a Composable
@Composable
internal fun HomeAccountFan(state: HomeAccountFanState, inline: Boolean = false) {
    val holding = state.holding
    val shown by animateFloatAsState(
        if (holding) 1f else 0f,
        YoinMotion.defaultSpatialSpec(),
        label = "accountFanShown",
    )
    if (shown <= 0.001f) return
    if (inline) {
        Box(
            Modifier.layout { measurable, constraints ->
                val p = measurable.measure(Constraints())
                // As big as the avatar's box, so it is placed from the box's corner.
                layout(constraints.maxWidth, constraints.maxHeight) {
                    // Its top-end at the avatar box's top-end, whichever way the page reads.
                    val x = if (layoutDirection == LayoutDirection.Rtl) 0 else constraints.maxWidth - p.width
                    p.place(x, 0)
                }
            },
        ) { HomeAccountFanColumn(state, shown) }
    } else {
        Popup(
            alignment = Alignment.TopEnd,
            properties = PopupProperties(focusable = false, clippingEnabled = false),
        ) { HomeAccountFanColumn(state, shown) }
    }
}

@Suppress("ktlint:standard:function-naming") // a Composable
@Composable
private fun HomeAccountFanColumn(state: HomeAccountFanState, shown: Float) {
    val density = LocalDensity.current
    val accounts = state.accounts
    val count = accounts.size
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val height = with(density) {
        (state.anchorHeight + FanFirstGap.toPx() + count * FanStep.toPx() + 16.dp.toPx()).toDp()
    }
    Box(Modifier.size(FanWidth, height)) {
        accounts.forEachIndexed { i, card ->
            // One after another: each starts a little after the one before.
            val local = if (count <= 1) {
                shown.coerceIn(0f, 1f)
            } else {
                ((shown - i * FAN_STAGGER) / (1f - (count - 1) * FAN_STAGGER)).coerceIn(0f, 1f)
            }
            val lift by animateFloatAsState(
                if (state.hover == i) 1f else 0f,
                YoinMotion.fastSpatialSpec(),
                label = "accountFanLift",
            )
            val half = with(density) { FanItem.toPx() / 2 }
            val restY = itemCenterY(state, density, i) - half
            // Out of the avatar's centre, down to its spot.
            val fromY = state.anchorHeight / 2f - half
            val y = (restY * local + fromY * (1f - local)).roundToInt()
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, y) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                HomeAccountFanLabel(card, lift = lift, shown = local, rtl = rtl)
                HomeAccountFanItem(card, lift = lift, shown = local)
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming") // a Composable
@Composable
private fun HomeAccountFanItem(card: ProfileCard, lift: Float, shown: Float) {
    val identity = card.provider.serviceIdentity
    // An account that can't be used now: there, but quieter (letting go on it opens Settings).
    val usable = card.unavailableReason == null
    Box(
        modifier = Modifier
            .size(FanItem)
            .graphicsLayer {
                val scale = (0.4f + 0.6f * shown) * (1f + 0.22f * lift)
                scaleX = scale
                scaleY = scale
                alpha = shown
            }
            .shadow(if (lift > 0.5f) 8.dp else 3.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        AccountAvatar(
            monogram = monogramOf(card.title, stringResource(identity.nameRes)),
            shapeIndex = card.avatarShape,
            identity = identity,
            ringColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            photoUrl = card.photoUrl,
            size = FanAvatar,
            modifier = Modifier.graphicsLayer { alpha = if (usable) 1f else 0.5f },
        )
    }
}

/** The account's name beside its item: always there, so the column reads; the one under the finger steps out. */
@Suppress("ktlint:standard:function-naming") // a Composable
@Composable
private fun HomeAccountFanLabel(card: ProfileCard, lift: Float, shown: Float, rtl: Boolean) {
    Text(
        text = card.title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(end = 8.dp)
            .widthIn(max = FanWidth - FanItem - 16.dp)
            .graphicsLayer {
                alpha = shown * (0.72f + 0.28f * lift)
                // Toward the page (start), a little further while under the finger.
                val out = 4.dp.toPx() + lift * 6.dp.toPx() + (1f - shown) * 8.dp.toPx()
                translationX = if (rtl) out else -out
            }
            .shadow(if (lift > 0.5f) 4.dp else 1.dp, CircleShape)
            .clip(CircleShape)
            .background(
                if (lift > 0.5f) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
