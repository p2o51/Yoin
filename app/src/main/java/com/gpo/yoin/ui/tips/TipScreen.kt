package com.gpo.yoin.ui.tips

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.MorphPolygonShape
import com.gpo.yoin.ui.component.YoinPageWidths
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.home.HomeBubbleTip
import com.gpo.yoin.ui.settings.SettingsBackButton
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/**
 * The tips, one per page, starting at [startTip]. Swiping looks around;
 * "Read" marks (or unmarks) the one on screen; "Next" marks it read and moves
 * to the next unread one — "Done" when it is the last, which closes the page.
 * The bubble on Home only brings up unread tips ([TipReadStore]).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TipScreen(
    startTip: HomeBubbleTip,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = LocalTipReadStore.current ?: remember(context) { SharedPrefsTipReadStore.get(context) }
    val read by store.read.collectAsState()
    val tips = HomeBubbleTip.entries
    val pager = rememberPagerState(initialPage = tips.indexOf(startTip)) { tips.size }
    val scope = rememberCoroutineScope()
    val haptics = rememberYoinHaptics()
    val current = tips[pager.currentPage]
    val nextUnread = nextUnreadTip(current, read)
    val pageSpring = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Standard)
    val bars = WindowInsets.systemBars.asPaddingValues()
    val direction = LocalLayoutDirection.current

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(
                PaddingValues(
                    start = bars.calculateStartPadding(direction),
                    top = bars.calculateTopPadding(),
                    end = bars.calculateEndPadding(direction),
                    // A window with no navigation bar (a bubble, a desktop
                    // window) still keeps the buttons off its rounded edge.
                    bottom = maxOf(bars.calculateBottomPadding(), TipBottomFloor),
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsBackButton(onClick = onBackClick)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(R.string.tip_position, pager.currentPage + 1, tips.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 24.dp),
            )
        }
        HorizontalPager(
            state = pager,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            beyondViewportPageCount = 1,
        ) { page ->
            TipPage(
                tip = tips[page],
                page = page,
                pager = pager,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = YoinPageWidths.Prose)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isRead = current in read
            ToggleButton(
                checked = isRead,
                onCheckedChange = { checked ->
                    haptics.performClick()
                    store.setRead(current, checked)
                },
                shapes = ToggleButtonDefaults.shapes(),
                modifier = Modifier.height(TipButtonHeight),
            ) {
                if (isRead) {
                    Icon(
                        imageVector = YoinSymbols.Check,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                }
                Text(stringResource(R.string.tip_read))
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    haptics.performConfirm()
                    store.setRead(current, true)
                    val target = nextUnread
                    if (target == null) {
                        onBackClick()
                    } else {
                        scope.launch {
                            pager.animateScrollToPage(tips.indexOf(target), animationSpec = pageSpring)
                        }
                    }
                },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.height(TipButtonHeight),
            ) {
                Text(stringResource(if (nextUnread == null) R.string.tip_done else R.string.tip_next))
                if (nextUnread != null) {
                    Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                    Icon(
                        imageVector = YoinSymbols.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                }
            }
        }
    }
}

/** One tip: its symbol on a large Expressive shape, the title, then the text. */
@Composable
private fun TipPage(
    tip: HomeBubbleTip,
    page: Int,
    pager: PagerState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = YoinPageWidths.Prose)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 24.dp),
        ) {
            TipTile(tip = tip, page = page, pager = pager)
            Spacer(modifier = Modifier.height(40.dp))
            Text(
                text = stringResource(tip.title),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(tip.text),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The tip's symbol on its shape. Swiping drives it straight from the pager
 * (draw phase only): it turns and shrinks a little as its page slides away;
 * arriving, the shape grows in from the previous tip's on the spatial spring.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TipTile(tip: HomeBubbleTip, page: Int, pager: PagerState) {
    val colors = MaterialTheme.colorScheme
    val own = tipPolygon(page)
    val morph = remember(page) { Morph(tipPolygon(page - 1), own) }
    val grown = remember(page) { Animatable(if (pager.currentPage == page) 1f else 0f) }
    val spring = YoinMotion.spatialSpring<Float>()
    LaunchedEffect(pager, page) {
        snapshotFlow { pager.settledPage == page }.collect { settled ->
            if (settled) grown.animateTo(1f, spring) else grown.snapTo(0f)
        }
    }
    val restShape = own.toShape()
    val shape = if (grown.value >= 1f) restShape else MorphPolygonShape(morph, grown.value)
    Box(
        modifier = Modifier
            .size(TipTileSize)
            .graphicsLayer {
                val offset = (pager.currentPage - page + pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
                rotationZ = offset * TipTileTurnDegrees
                val shrink = 1f - offset.absoluteValue * TipTileShrink
                scaleX = shrink
                scaleY = shrink
            }
            .background(colors.primaryContainer, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = tip.icon,
            contentDescription = null,
            tint = colors.onPrimaryContainer,
            modifier = Modifier.size(TipSymbolSize),
        )
    }
}

/** A different silhouette per tip, so paging reads as moving on. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun tipPolygon(index: Int): RoundedPolygon = when (Math.floorMod(index, 6)) {
    0 -> MaterialShapes.Cookie9Sided
    1 -> MaterialShapes.Clover4Leaf
    2 -> MaterialShapes.Sunny
    3 -> MaterialShapes.Cookie6Sided
    4 -> MaterialShapes.Gem
    else -> MaterialShapes.Pentagon
}

private val TipTileSize = 160.dp
private val TipSymbolSize = 64.dp
private const val TipTileTurnDegrees = 40f
private const val TipTileShrink = 0.2f
private val TipButtonHeight = 56.dp
private val TipBottomFloor = 16.dp

@Preview(showBackground = true)
@Composable
private fun TipScreenPreview() {
    YoinTheme {
        CompositionLocalProvider(LocalTipReadStore provides InMemoryTipReadStore()) {
            TipScreen(startTip = HomeBubbleTip.PlayModes, onBackClick = {}, modifier = Modifier.fillMaxSize())
        }
    }
}
