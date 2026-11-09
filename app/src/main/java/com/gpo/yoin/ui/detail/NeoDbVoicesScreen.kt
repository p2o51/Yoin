package com.gpo.yoin.ui.detail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.gpo.yoin.R
import com.gpo.yoin.data.integration.neodb.NeoDbAlbumVoices
import com.gpo.yoin.data.integration.neodb.NeoDbReferenceScore
import com.gpo.yoin.data.integration.neodb.NeoDbVoice
import com.gpo.yoin.data.integration.neodb.NeoDbVoicesParsing
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.expressivePageSeamBackground
import com.gpo.yoin.ui.component.seamDissolveViewport
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.rememberCoverColorScheme
import java.util.Locale

@Composable
internal fun NeoDbVoicesRoute(
    viewModel: NeoDbVoicesViewModel,
    coverArtUrl: String?,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The page wears the album's colours, like the album page.
    MaterialTheme(colorScheme = rememberCoverColorScheme(coverArtUrl) ?: MaterialTheme.colorScheme) {
        NeoDbVoicesScreen(
            uiState = uiState,
            albumName = viewModel.albumName,
            artistName = viewModel.artistName,
            onBackClick = onBackClick,
            onRetry = viewModel::load,
            onLoadMore = viewModel::loadMore,
            onOpenUrl = { url -> context.openExternal(url) },
            modifier = modifier,
        )
    }
}

/**
 * Top to bottom: the score spread (five columns, NeoDB's moons), a small line with the average and how many
 * rated, other catalogues' scores, then what people wrote (long words across the width, short ones two by two),
 * and at the bottom the ways out: NeoDB, Douban, RYM, AOTY. Nothing here compares anyone with the user.
 */
@Composable
internal fun NeoDbVoicesScreen(
    uiState: NeoDbVoicesUiState,
    albumName: String,
    artistName: String?,
    onBackClick: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(modifier = modifier.background(scheme.surface)) {
        VoicesTopBar(
            albumName = albumName,
            onBackClick = onBackClick,
            modifier = Modifier.padding(top = statusTop),
        )
        AnimatedContent(
            targetState = uiState,
            contentKey = { it::class },
            transitionSpec = {
                YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard)
            },
            label = "neoDbVoices",
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { state ->
            when (state) {
                NeoDbVoicesUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    YoinLoadingIndicator()
                }
                NeoDbVoicesUiState.Failed -> VoicesMessage(
                    text = stringResource(R.string.neodb_voices_failed),
                    action = stringResource(R.string.detail_retry),
                    onAction = onRetry,
                )
                is NeoDbVoicesUiState.NotFound -> VoicesMessage(
                    text = stringResource(R.string.neodb_voices_not_found),
                    action = stringResource(R.string.neodb_voices_search),
                    onAction = { onOpenUrl(state.searchUrl) },
                )
                is NeoDbVoicesUiState.Content -> VoicesContent(
                    state = state,
                    onLoadMore = onLoadMore,
                    onOpenUrl = onOpenUrl,
                )
            }
        }
        val content = uiState as? NeoDbVoicesUiState.Content
        WaysOut(
            neoDbUrl = content?.album?.itemUrl,
            doubanUrl = content?.album?.doubanUrl,
            rymUrl = NeoDbVoicesParsing.rymSearchUrl(albumName, artistName),
            aotyUrl = NeoDbVoicesParsing.aotySearchUrl(albumName, artistName),
            onOpenUrl = onOpenUrl,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp + navBottom),
        )
    }
}

@Composable
private fun VoicesTopBar(albumName: String, onBackClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DetailBackButton(onClick = onBackClick)
        Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
            Text(
                text = stringResource(R.string.neodb_voices_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = albumName,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun VoicesMessage(text: String, action: String, onAction: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun VoicesContent(
    state: NeoDbVoicesUiState.Content,
    onLoadMore: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val gridState = rememberLazyStaggeredGridState()
    // Near the end, the next page.
    LaunchedEffect(gridState, state.voices.size) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - LOAD_MORE_AHEAD
        }.collect { nearEnd -> if (nearEnd) onLoadMore() }
    }
    var unfolded by remember { mutableStateOf(emptySet<String>()) }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .seamDissolveViewport(background = expressivePageSeamBackground()) { gridState.seamScrolledPx() },
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalItemSpacing = 8.dp,
    ) {
        item(key = "spread", span = StaggeredGridItemSpan.FullLine) { Spread(state.album) }
        if (state.references.isNotEmpty()) {
            item(key = "references", span = StaggeredGridItemSpan.FullLine) {
                References(state.references, onOpenUrl)
            }
        }
        if (state.voices.isEmpty()) {
            item(key = "empty", span = StaggeredGridItemSpan.FullLine) {
                Text(
                    text = stringResource(R.string.neodb_voices_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        itemsIndexed(
            items = state.voices,
            key = { _, voice -> voice.id },
            span = { _, voice ->
                if (voice.isLong()) StaggeredGridItemSpan.FullLine else StaggeredGridItemSpan.SingleLane
            },
        ) { index, voice ->
            VoiceCard(
                voice = voice,
                lifted = index % 3 == 0,
                unfolded = voice.id in unfolded,
                onUnfold = { unfolded = unfolded + voice.id },
                onOpen = { voice.url?.let(onOpenUrl) },
            )
        }
        item(key = "tail", span = StaggeredGridItemSpan.FullLine) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                when {
                    state.loadingMore -> YoinLoadingIndicator(size = 32.dp)
                    state.moreFailed -> TextButton(onClick = onLoadMore) {
                        Text(stringResource(R.string.detail_retry))
                    }
                    state.voices.isNotEmpty() -> Text(
                        text = pluralStringResource(R.plurals.neodb_voices_count, state.count, state.count),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun LazyStaggeredGridState.seamScrolledPx(): Float =
    if (firstVisibleItemIndex > 0) Float.POSITIVE_INFINITY else firstVisibleItemScrollOffset.toFloat()

/** Long words, a review, or folded words take the whole width; short ones sit two by two. */
private fun NeoDbVoice.isLong(): Boolean = reviewTitle != null || text.length > LONG_VOICE_CHARS

/** Five columns, one per moon count; the tallest in the accent. Then the average, small. */
@Composable
private fun Spread(album: NeoDbAlbumVoices) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (album.distribution.isNotEmpty()) {
            val top = album.distribution.max().coerceAtLeast(1)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SpreadHeight),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                album.distribution.forEachIndexed { i, percent ->
                    val spreadDescription = stringResource(R.string.neodb_voices_spread_cd)
                    var shown by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { shown = true }
                    val fraction by animateFloatAsState(
                        targetValue = if (shown) percent / top.toFloat() else 0f,
                        animationSpec = YoinMotion.slowSpatialSpring(),
                        label = "spread$i",
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .semantics(mergeDescendants = true) {
                                contentDescription = String.format(spreadDescription, i + 1, percent)
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((SpreadBarMax.value * fraction).coerceAtLeast(3f).dp)
                                .clip(SpreadBarShape)
                                .background(if (percent == top) scheme.primary else scheme.secondaryContainer),
                        )
                        Moons(rating = (i + 1) * 2, color = scheme.tertiary, size = 6.dp, showEmpty = false)
                        Text(
                            text = stringResource(R.string.neodb_voices_percent, percent),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            album.rating?.let { rating ->
                Text(
                    text = String.format(Locale.ROOT, "%.1f", rating),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurface,
                )
            }
            Text(
                text = pluralStringResource(R.plurals.neodb_voices_rated, album.ratingCount, album.ratingCount),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
    }
}

@Composable
private fun References(references: List<NeoDbReferenceScore>, onOpenUrl: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        references.forEach { ref ->
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                onClick = { onOpenUrl(ref.url) },
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).widthIn(min = 72.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = String.format(Locale.ROOT, "%.2f", ref.value),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                        Text(
                            text = stringResource(R.string.neodb_voices_scale, ref.scale),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                    Text(
                        text = ref.source,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceCard(
    voice: NeoDbVoice,
    lifted: Boolean,
    unfolded: Boolean,
    onUnfold: () -> Unit,
    onOpen: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberYoinHaptics()
    val folded = voice.folded && !unfolded
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(if (lifted) scheme.surfaceContainerHigh else scheme.surfaceContainer)
            .clickable {
                haptics.performClick()
                if (folded) onUnfold() else onOpen()
            }
            .animateContentSize(YoinMotion.spatialSpring())
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            folded -> Text(
                text = voice.foldNote ?: stringResource(R.string.neodb_voices_folded),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            else -> {
                voice.reviewTitle?.let { title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface,
                    )
                }
                if (voice.text.isNotEmpty()) {
                    Text(
                        text = voice.text,
                        style = if (voice.isLong()) {
                            MaterialTheme.typography.bodyLarge
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        color = scheme.onSurface,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AsyncImage(
                model = voice.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(scheme.secondaryContainer),
            )
            Text(
                text = voice.byline(),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (voice.reviewTitle != null) {
                Text(
                    text = stringResource(R.string.neodb_voices_review),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            voice.rating?.let { Moons(rating = it, color = scheme.tertiary, size = 7.dp) }
        }
    }
}

/** The writer's name; their instance only when it isn't the one the page reads (NeoDB is federated). */
private fun NeoDbVoice.byline(): String {
    val host = handle.substringAfter('@', "")
    return if (host.isEmpty() || host == "neodb.social") author else "$author · $host"
}

/** NeoDB's moons: a 1–10 grade as five moons, an odd grade ending in a half moon. */
@Composable
private fun Moons(rating: Int, color: Color, size: Dp, showEmpty: Boolean = true) {
    val full = rating / 2
    val half = rating % 2 == 1
    val count = if (showEmpty) 5 else full + if (half) 1 else 0
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(count) { i ->
            Canvas(Modifier.size(size)) {
                val r = this.size.minDimension / 2f
                val c = Offset(r, r)
                when {
                    i < full -> drawCircle(color, r, c)
                    i == full && half -> {
                        drawCircle(color, r - 0.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
                        clipRect(right = r) { drawCircle(color, r, c) }
                    }
                    else -> drawCircle(color.copy(alpha = 0.35f), r - 0.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
                }
            }
        }
    }
}

/** NeoDB filled (the one final action), the other catalogues tonal beside each other. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WaysOut(
    neoDbUrl: String?,
    doubanUrl: String?,
    rymUrl: String,
    aotyUrl: String,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (neoDbUrl != null) {
            Button(
                onClick = {
                    haptics.performClick()
                    onOpenUrl(neoDbUrl)
                },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.neodb_voices_open_neodb)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val others = listOfNotNull(
                doubanUrl?.let { stringResource(R.string.neodb_voices_douban) to it },
                stringResource(R.string.neodb_voices_rym) to rymUrl,
                stringResource(R.string.neodb_voices_aoty) to aotyUrl,
            )
            others.forEach { (label, url) ->
                FilledTonalButton(
                    onClick = {
                        haptics.performClick()
                        onOpenUrl(url)
                    },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f),
                ) { Text(label, maxLines = 1) }
            }
        }
    }
}

private fun Context.openExternal(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Log.w("NeoDbVoices", "no app opens $url", e)
    }
}

private const val LOAD_MORE_AHEAD = 4
private const val LONG_VOICE_CHARS = 40
private val SpreadHeight = 132.dp
private val SpreadBarMax = 92.dp
private val SpreadBarShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
