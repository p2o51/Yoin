package com.gpo.yoin.ui.home.edit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.experience.smoothstep
import com.gpo.yoin.ui.home.HomeHeaderTitleBreathing
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * The header title through P (port sheet §2.8, spec §2.2.3): "Home" is
 * measured as usual and fades out early; "Edit Home" lies over it at zero
 * width, so the header never makes room for it, and fades in later. The
 * overlay is composed only while it can show and announces itself to
 * TalkBack. [onEnterEdit] gives "Home" an "Edit Home" action while the
 * header is still the feed's.
 */
@Composable
internal fun HomeEditHeaderTitle(
    progress: () -> Float,
    style: TextStyle,
    modifier: Modifier = Modifier,
    onEnterEdit: (() -> Unit)? = null,
) {
    val color = MaterialTheme.colorScheme.onBackground
    val homeTitle = stringResource(R.string.home_title)
    val editTitle = stringResource(R.string.home_edit_title)
    val editAction = stringResource(R.string.home_edit_action_header)
    val overlayShown by remember(progress) { derivedStateOf { progress() > ComposeFloor } }
    val feedTitle = rememberHomeEditIconsEnabled(progress)
    val enterAction = if (onEnterEdit != null && feedTitle) {
        Modifier.semantics {
            customActions = listOf(
                CustomAccessibilityAction(editAction) {
                    onEnterEdit()
                    true
                },
            )
        }
    } else {
        Modifier
    }
    Box(modifier) {
        Text(
            text = homeTitle,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .seamFade(fontSize = style.fontSize)
                .graphicsLayer { alpha = 1f - smoothstep(HomeFadeStart, HomeFadeEnd, clampedP(progress)) }
                .then(enterAction),
        )
        if (overlayShown) {
            Text(
                text = editTitle,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    // Zero width: "Edit Home" overhangs instead of widening the title.
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(
                            constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity),
                        )
                        layout(0, placeable.height) { placeable.place(0, 0) }
                    }
                    .seamFade(fontSize = style.fontSize)
                    .graphicsLayer { alpha = smoothstep(EditFadeStart, EditFadeEnd, clampedP(progress)) }
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * "Drag to reorder" at the end of the header's free span, for the first edit
 * sessions ([visible]). It shows only where it clears "Edit Home" (set in
 * [titleStyle]) by 16dp, the prototype's fit check (critique 16): the
 * overlay's overhang past "Home" first runs through the title's
 * [titleEndPadding], and only the rest of it is reserved. Never changes the
 * header's height.
 */
@Composable
internal fun HomeEditHeaderHint(
    progress: () -> Float,
    visible: Boolean,
    titleStyle: TextStyle,
    modifier: Modifier = Modifier,
    titleEndPadding: Dp = HomeHeaderTitleBreathing,
) {
    val shown by remember(progress) { derivedStateOf { progress() > ComposeFloor } }
    if (!visible || !shown) return
    val editOverhang = rememberEditTitleOverhangPx(titleStyle)
    Layout(
        content = {
            Text(
                text = stringResource(R.string.home_edit_hint_drag),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .graphicsLayer { alpha = smoothstep(HintFadeStart, HintFadeEnd, clampedP(progress)) }
                    // The "Edit Home" announcement already covers it.
                    .semantics { hideFromAccessibility() },
            )
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val text = measurables.first().measure(Constraints())
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else text.width
        val height = constraints.minHeight
        // Only the overhang past the title's end padding reaches into this span.
        val reserve = (editOverhang - titleEndPadding.roundToPx()).coerceAtLeast(0) + HintGap.roundToPx()
        val fits = reserve + text.width <= width
        layout(width, height) {
            if (fits) text.place(width - text.width, (height - text.height) / 2)
        }
    }
}

/** The header icons (the Memories entry, Settings) fade out over the first half of P. Layer only. */
internal fun Modifier.homeEditHeaderIcon(progress: () -> Float): Modifier = graphicsLayer {
    alpha = 1f - smoothstep(0f, IconsOffAt, clampedP(progress))
}

/**
 * Whether the header icons take input: P below one half. Callers keep the
 * icons composed (the row's height stays) and clear their semantics when false.
 */
@Composable
internal fun rememberHomeEditIconsEnabled(progress: () -> Float): Boolean {
    val enabled by remember(progress) { derivedStateOf { progress() < IconsOffAt } }
    return enabled
}

// How far "Edit Home" runs past "Home" in [style], px.
@Composable
private fun rememberEditTitleOverhangPx(style: TextStyle): Int {
    val homeTitle = stringResource(R.string.home_title)
    val editTitle = stringResource(R.string.home_edit_title)
    val measurer = rememberTextMeasurer(cacheSize = 2)
    return remember(style, measurer, homeTitle, editTitle) {
        val edit = measurer.measure(editTitle, style, maxLines = 1, softWrap = false).size.width
        val home = measurer.measure(homeTitle, style, maxLines = 1, softWrap = false).size.width
        (edit - home).coerceAtLeast(0)
    }
}

private fun clampedP(progress: () -> Float): Float = progress().coerceIn(0f, 1f)

// P above which the edit-only pieces are composed: before either fades in
// (EditFadeStart, HintFadeStart), but past the entry's first frames, which
// already compose the feed's edit state.
private const val ComposeFloor = .3f
private const val HomeFadeStart = .2f
private const val HomeFadeEnd = .6f
private const val EditFadeStart = .4f
private const val EditFadeEnd = .8f
private const val HintFadeStart = .5f
private const val HintFadeEnd = 1f

// The icons are gone, and stop taking input, at P one half.
private const val IconsOffAt = .5f
private val HintGap = 16.dp

// ── Previews ──────────────────────────────────────────────────────────────

@Composable
private fun PreviewHeader(progress: Float) {
    YoinTheme {
        val style = MaterialTheme.typography.headlineLarge
        val p = { progress }
        val iconsEnabled = rememberHomeEditIconsEnabled(p)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeEditHeaderTitle(
                progress = p,
                style = style,
                modifier = Modifier.padding(end = HomeHeaderTitleBreathing),
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                HomeEditHeaderHint(progress = p, visible = true, titleStyle = style)
            }
            Spacer(Modifier.width(2.dp))
            IconButton(
                onClick = {},
                enabled = iconsEnabled,
                modifier = Modifier
                    .homeEditHeaderIcon(p)
                    .then(if (iconsEnabled) Modifier else Modifier.clearAndSetSemantics {}),
            ) {
                Icon(YoinSymbols.Settings, contentDescription = "Settings")
            }
        }
    }
}

@Preview(name = "Header · P 0", showBackground = true, widthDp = 600)
@Composable
private fun HomeEditHeaderRestPreview() = PreviewHeader(progress = 0f)

@Preview(name = "Header · P .5", showBackground = true, widthDp = 600)
@Composable
private fun HomeEditHeaderMidPreview() = PreviewHeader(progress = .5f)

@Preview(name = "Header · P 1", showBackground = true, widthDp = 600)
@Composable
private fun HomeEditHeaderEditPreview() = PreviewHeader(progress = 1f)

@Preview(name = "Header · P 1, 360dp", showBackground = true, widthDp = 360)
@Composable
private fun HomeEditHeaderPhonePreview() = PreviewHeader(progress = 1f)
