package com.gpo.yoin.ui.home.edit

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.MarqueeText
import com.gpo.yoin.ui.component.seamDissolve
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.home.HomeEmptyCard
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.HomeSectionTitle
import com.gpo.yoin.ui.home.supportingRes
import com.gpo.yoin.ui.home.titleRes
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * The edit-mode tray under the last section (spec §2.4, port sheet §5.4):
 * "Hidden" (with an empty line when nothing is), one row per hidden section,
 * then Reset. Keys `tray-title`, `tray-<id>`, `edit-footer`. The list gives
 * them no fades of their own: the tray's alpha (and each row's) is the
 * motion's, and they fade with the feed while strips fold. Rows sit 8dp
 * apart whatever the feed's [itemSpacing]. Tray rows never wiggle.
 */
internal fun LazyListScope.homeEditTrayItems(
    hidden: List<HomeSection>,
    newBadges: Set<HomeSection>,
    canReset: Boolean,
    deps: HomeEditDeps,
    placementSpec: () -> FiniteAnimationSpec<IntOffset>?,
    itemSpacing: Dp = TrayFeedSpacing,
) {
    val motion = deps.motion
    val engine = deps.engine
    item(key = TrayTitleKey) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateItem(fadeInSpec = null, placementSpec = placementSpec(), fadeOutSpec = null)
                .zIndex(TrayZIndex)
                .graphicsLayer { alpha = motion.trayAlpha.value * feedFoldShown(engine.fold.value) }
                // With the rows' tuck this leaves 12dp under the title, as in a section.
                .padding(bottom = TrayHeadGap - TrayRowGap),
            verticalArrangement = Arrangement.spacedBy(TrayHeadGap),
        ) {
            HomeSectionTitle(text = stringResource(R.string.home_edit_hidden))
            if (hidden.isEmpty()) {
                Text(
                    text = stringResource(R.string.home_edit_hidden_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.seamFade(),
                )
            }
        }
    }
    items(items = hidden, key = ::homeEditTrayRowKey) { section ->
        HomeEditTrayRow(
            section = section,
            newBadge = section in newBadges,
            deps = deps,
            modifier = Modifier
                .animateItem(fadeInSpec = null, placementSpec = placementSpec(), fadeOutSpec = null)
                .zIndex(TrayZIndex)
                .tuckUp(itemSpacing - TrayRowGap),
        )
    }
    item(key = TrayFooterKey) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .animateItem(fadeInSpec = null, placementSpec = placementSpec(), fadeOutSpec = null)
                .zIndex(TrayZIndex)
                .graphicsLayer { alpha = motion.trayAlpha.value * feedFoldShown(engine.fold.value) },
            contentAlignment = Alignment.Center,
        ) {
            TextButton(
                onClick = { deps.controller.reset() },
                enabled = canReset,
                contentPadding = PaddingValues(horizontal = FooterButtonPadding),
                modifier = Modifier
                    .heightIn(min = FooterButtonHeight)
                    .homeEditExclusion(deps.targets, TrayFooterKey),
            ) {
                Icon(
                    imageVector = YoinSymbols.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(FooterIconSize),
                )
                Spacer(Modifier.width(FooterIconGap))
                Text(text = stringResource(R.string.home_edit_reset))
            }
        }
    }
}

/**
 * The feed's own way into edit mode, at its end (Q6b): a quiet "Edit Home"
 * button, badged "New" while a new section waits in the tray. Key
 * `home-edit-entry`. Its alpha is the footer's; HEC's [onEnter] enters from
 * the last section, with no lift.
 */
internal fun LazyListScope.homeEditFooterEntry(
    newBadge: Boolean,
    deps: HomeEditDeps,
    onEnter: () -> Unit,
    placementSpec: () -> FiniteAnimationSpec<IntOffset>?,
) {
    val motion = deps.motion
    item(key = FooterEntryKey) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .animateItem(fadeInSpec = null, placementSpec = placementSpec(), fadeOutSpec = null)
                .graphicsLayer { alpha = motion.footerAlpha.value },
            contentAlignment = Alignment.Center,
        ) {
            TextButton(
                onClick = onEnter,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                contentPadding = PaddingValues(horizontal = FooterButtonPadding),
                modifier = Modifier
                    .heightIn(min = FooterButtonHeight)
                    .homeEditExclusion(deps.targets, FooterEntryKey),
            ) {
                Icon(
                    imageVector = YoinSymbols.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(FooterIconSize),
                )
                Spacer(Modifier.width(FooterIconGap))
                Text(text = stringResource(R.string.home_edit_footer), style = MaterialTheme.typography.labelLarge)
                if (newBadge) {
                    Spacer(Modifier.width(FooterIconGap))
                    HomeEditNewChip()
                }
            }
        }
    }
}

/**
 * Every section hidden (spec §2.4): the normal-mode feed says so. Key
 * `home-all-hidden`. It belongs with the footer entry, so pass the footer's
 * alpha as [alpha] (`{ motion.footerAlpha.value }`): the list gives it no
 * fade of its own.
 */
internal fun LazyListScope.homeAllHiddenItem(
    placementSpec: () -> FiniteAnimationSpec<IntOffset>?,
    alpha: () -> Float,
) {
    item(key = AllHiddenKey) {
        // A label only: the footer's Edit Home entry sits right below it.
        HomeEmptyCard(
            title = stringResource(R.string.home_edit_all_hidden_title),
            modifier = Modifier
                .fillMaxWidth()
                .animateItem(fadeInSpec = null, placementSpec = placementSpec(), fadeOutSpec = null)
                .graphicsLayer { this.alpha = alpha() },
        )
    }
}

/** One hidden section: the whole row shows it, as does its plus button. */
@Composable
private fun HomeEditTrayRow(
    section: HomeSection,
    newBadge: Boolean,
    deps: HomeEditDeps,
    modifier: Modifier = Modifier,
) {
    val controller = deps.controller
    val motion = deps.motion
    val engine = deps.engine
    val colors = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val editing = controller.isEditing
    val title = stringResource(section.titleRes)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TrayRowHeight * fontScale)
            .graphicsLayer {
                alpha = motion.trayAlpha.value * motion.rowAlpha(section) * feedFoldShown(engine.fold.value)
            }
            .homeEditExclusion(deps.targets, TrayRowKey(section))
            .seamDissolve()
            .clip(YoinContainerShapes.Panel)
            .background(colors.surfaceContainerHigh)
            .clickable(
                enabled = editing,
                role = Role.Button,
                onClickLabel = stringResource(R.string.home_action_show_section, title),
            ) { controller.show(section) }
            .padding(horizontal = TrayRowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TrayRowContentGap),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .seamFade(),
                )
                if (newBadge) {
                    Spacer(Modifier.width(NewChipGap))
                    HomeEditNewChip()
                }
            }
            MarqueeText(
                text = stringResource(section.supportingRes),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .seamFade(),
            )
        }
        FilledTonalIconButton(
            onClick = { controller.show(section) },
            enabled = editing,
            // The exit fade keeps its look; only input stops.
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                disabledContainerColor = colors.secondaryContainer,
                disabledContentColor = colors.onSecondaryContainer,
            ),
            modifier = Modifier.size(ShowButtonSize),
        ) {
            Icon(
                imageVector = YoinSymbols.Add,
                contentDescription = stringResource(R.string.home_cd_show_section, title),
            )
        }
    }
}

/** "New": a section that arrived since the last edit session (Q6a). */
@Composable
private fun HomeEditNewChip(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.home_edit_new),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .background(MaterialTheme.colorScheme.tertiaryContainer, YoinShapeTokens.Full)
            .padding(horizontal = NewChipPaddingH, vertical = NewChipPaddingV),
    )
}

// Pulls an item up by [amount] into the list's spacing: rows sit closer than sections.
private fun Modifier.tuckUp(amount: Dp): Modifier = layout { measurable, constraints ->
    val tuck = amount.roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(constraints)
    val height = (placeable.height - tuck).coerceAtLeast(0)
    layout(placeable.width, height) { placeable.place(0, height - placeable.height) }
}

private data class TrayRowKey(val section: HomeSection)

// The feed's item keys for these pieces; Home publishes them in order (the carry's anchor reads them).
internal const val TrayTitleKey = "tray-title"
internal const val TrayFooterKey = "edit-footer"
internal const val FooterEntryKey = "home-edit-entry"
internal const val AllHiddenKey = "home-all-hidden"

/** The feed item key of [section]'s tray row. */
internal fun homeEditTrayRowKey(section: HomeSection): String = "tray-${section.id}"

/**
 * The tray draws under the sections. A reflow that brings it into view (a
 * hide) starts it at the viewport's edge, not where it was below the last
 * block, so for a few frames it crosses that block; the block's plate covers it.
 */
private const val TrayZIndex = -1f

private val TrayFeedSpacing = 18.dp
private val TrayHeadGap = 12.dp
private val TrayRowGap = 8.dp
private val TrayRowHeight = 64.dp
private val TrayRowPadding = 16.dp
private val TrayRowContentGap = 12.dp
private val ShowButtonSize = 40.dp
private val NewChipGap = 8.dp
private val NewChipPaddingH = 6.dp
private val NewChipPaddingV = 2.dp
private val FooterButtonHeight = 48.dp
private val FooterButtonPadding = 16.dp
private val FooterIconSize = 18.dp
private val FooterIconGap = 8.dp

// ── Previews ──────────────────────────────────────────────────────────────

@Composable
private fun PreviewTray(hidden: List<HomeSection>) {
    YoinTheme {
        val deps = rememberPreviewHomeEditDeps(progress = 1f, editing = true)
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(TrayFeedSpacing),
        ) {
            homeEditTrayItems(
                hidden = hidden,
                newBadges = setOf(HomeSection.Rediscover),
                canReset = hidden.isNotEmpty(),
                deps = deps,
                placementSpec = { null },
            )
        }
    }
}

@Preview(name = "Tray · rows", showBackground = true, widthDp = 360)
@Composable
private fun HomeEditTrayRowsPreview() = PreviewTray(listOf(HomeSection.RecentlyAdded, HomeSection.Rediscover))

@Preview(name = "Tray · empty", showBackground = true, widthDp = 360)
@Composable
private fun HomeEditTrayEmptyPreview() = PreviewTray(emptyList())

@Preview(name = "Footer entry", showBackground = true, widthDp = 360)
@Composable
private fun HomeEditFooterEntryPreview() {
    YoinTheme {
        val deps = rememberPreviewHomeEditDeps(progress = 0f, editing = false)
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(TrayFeedSpacing),
        ) {
            homeAllHiddenItem(
                placementSpec = { null },
                alpha = { deps.motion.footerAlpha.value },
            )
            homeEditFooterEntry(newBadge = true, deps = deps, onEnter = {}, placementSpec = { null })
        }
    }
}
