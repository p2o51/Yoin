package com.gpo.yoin.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.component.MarqueeText
import com.gpo.yoin.ui.component.elasticPress
import com.gpo.yoin.ui.component.horizontalEdgeFadeOnScroll
import com.gpo.yoin.ui.component.ignoreParentHorizontalPadding
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.component.seamDissolve
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.experience.LocalPaneWidthInMotion
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
import com.gpo.yoin.ui.experience.feedFrameClass
import com.gpo.yoin.ui.home.edit.homeEditCard
import com.gpo.yoin.ui.home.edit.homeEditInteractive
import com.gpo.yoin.ui.memories.MemoryScoreKind
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.withTabularFigures
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ── Rediscover (P0-9) ──────────────────────────────────────────────────
//
// Albums you rated 8 or higher that haven't played in Yoin for 90+ days,
// each with one line of reasons you can check. Tapping opens the album (not
// the Memories deck — that's what tells it apart from a Memory card). A
// shelf in every tier so a played card leaves with animateItem; only the
// phone's peeking shelf actually scrolls.

/** Edit mode's placeholder copy when Rediscover has nothing to bring back. */
internal const val RediscoverPlaceholderText =
    "Rate an album 8 or higher — when it's been a while, it comes back here"

/** Before × fontScale: the 104dp backdrop plus 14dp padding above and below. */
private val RediscoverCardHeight = 132.dp
private val RediscoverBackdropSize = 104.dp
private val RediscoverCardPadding = 14.dp
private val RediscoverCardGap = 12.dp

@Composable
internal fun RediscoverSection(
    items: List<HomeRediscoverItem>,
    // The feed's frame supplies the live margins: the shelf bleeds past them
    // to the container's edge and pads its content by the same amounts.
    frame: HomeFeedFrame,
    nowMillis: Long,
    extractBackdropColors: Boolean = true,
    scrollEnabled: Boolean = true,
    onAlbumClick: (albumId: String, sharedTransitionKey: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val windowInfo = LocalYoinWindowInfo.current
    val visibleCount = rediscoverVisibleCount(windowInfo.feedUnits, windowInfo.isCompactHeight)
    val shelf = isRediscoverShelf(visibleCount)
    val shown = remember(items, visibleCount) { items.take(visibleCount) }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    // A lone shelf card fills the content; when the second-to-last card
    // leaves, the survivor grows into the space instead of snapping wide.
    val lone by animateFloatAsState(
        targetValue = if (shelf && shown.size <= 1) 1f else 0f,
        animationSpec = YoinMotion.defaultSpatialSpec(),
        label = "rediscoverLone",
    )
    val paneWidthInMotion = LocalPaneWidthInMotion.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeSectionTitle(text = HomeSection.Rediscover.title)
        val shelfState = rememberLazyListState()
        val sidePadding = remember(frame) { FeedFrameSidePadding(frame) }
        LazyRow(
            state = shelfState,
            modifier = Modifier
                .fillMaxWidth()
                .ignoreParentHorizontalPadding(start = { frame.start }, end = { frame.end })
                .horizontalEdgeFadeOnScroll(shelfState),
            contentPadding = sidePadding,
            horizontalArrangement = Arrangement.spacedBy(RediscoverCardGap),
            userScrollEnabled = scrollEnabled && shown.size > (if (shelf) 1 else visibleCount),
        ) {
            // Edit-mode card order = shelf order.
            itemsIndexed(
                items = shown,
                key = { _, item -> "rediscover:${item.albumId}" },
            ) { index, item ->
                RediscoverCard(
                    item = item,
                    nowMillis = nowMillis,
                    extractBackdropColors = extractBackdropColors,
                    onClick = { onAlbumClick(item.albumId.toString(), null) },
                    modifier = Modifier
                        .homeEditCard(index)
                        .animateItem(
                            fadeInSpec = YoinMotion.effectsSpring(),
                            placementSpec = if (paneWidthInMotion.value || frame.isBlending) {
                                null
                            } else {
                                YoinMotion.spatialSpring()
                            },
                            fadeOutSpec = YoinMotion.effectsSpring(),
                        )
                        .height(RediscoverCardHeight * fontScale)
                        // Width follows the feed's content width in the layout
                        // pass only, so a column spring never recomposes the shelf.
                        .layout { measurable, constraints ->
                            val content = frame.contentWidth
                            val width = lerp(
                                rediscoverCardWidth(content, visibleCount, itemCount = 2, gap = RediscoverCardGap),
                                rediscoverCardWidth(content, visibleCount, itemCount = 1, gap = RediscoverCardGap),
                                lone,
                            ).roundToPx()
                            val placeable = measurable.measure(
                                constraints.copy(minWidth = width, maxWidth = width),
                            )
                            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                        },
                )
            }
        }
    }
}

@Composable
private fun RediscoverCard(
    item: HomeRediscoverItem,
    nowMillis: Long,
    extractBackdropColors: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // The Activities wash: hue-faithful to this album's own cover.
    val colors = rememberActivityCardColors(item.coverArtUrl, extractBackdropColors)
    val eyebrow = remember(item, nowMillis) { rediscoverEyebrow(item, nowMillis) }
    val footnote = remember(item) { rediscoverFootnote(item.firstPlayedAt, item.playCount) }
    Surface(
        // The tinted card and its cover break up as one print; the text on it
        // is lifted out and passes under the bar whole.
        modifier = modifier
            .elasticPress(interactionSource)
            .seamDissolve(),
        shape = YoinContainerShapes.Card,
        color = colors.container,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .noRippleClickable(
                    interactionSource = interactionSource,
                    enabled = homeEditInteractive(),
                    onClick = onClick,
                )
                .padding(RediscoverCardPadding),
            horizontalArrangement = Arrangement.spacedBy(RediscoverCardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WidgetBackdropArtwork(
                model = item.coverArtUrl,
                kind = WidgetShapeKind.Album,
                contentDescription = item.albumName,
                extractBackdropColors = extractBackdropColors,
                interactionSource = interactionSource,
                modifier = Modifier.size(RediscoverBackdropSize),
            )
            // The proto's rhythm: a two-line eyebrow still leaves the four
            // lines inside the 104dp the backdrop sets.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .seamFade(),
            ) {
                Text(
                    text = eyebrow,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                MarqueeText(
                    text = item.albumName,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                        lineHeight = 26.sp,
                    ),
                    color = colors.content,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                )
                item.artistName?.let { artist ->
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.contentMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                footnote?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall.withTabularFigures(),
                        color = colors.contentMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

// ── Copy ───────────────────────────────────────────────────────────────

private const val DayMillis = 24L * 60 * 60 * 1000
private val RediscoverMonthFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM", Locale.US)

/**
 * How long since the last play in Yoin: whole months (days / 30) under a
 * year, whole years (days / 365) from 365 days — "7 months", "1 year".
 */
internal fun rediscoverAwayText(lastPlayedAt: Long, nowMillis: Long): String {
    val days = ((nowMillis - lastPlayedAt) / DayMillis).coerceAtLeast(0L)
    return if (days >= 365) {
        val years = days / 365
        if (years == 1L) "1 year" else "$years years"
    } else {
        val months = (days / 30).coerceAtLeast(1L)
        if (months == 1L) "1 month" else "$months months"
    }
}

/** The seal's own "%.1f", always with a dot: "9.0". */
internal fun rediscoverScoreText(score: Float): String = String.format(Locale.US, "%.1f", score)

/** "Rated 9.0 · Not played in Yoin for 7 months". */
internal fun rediscoverEyebrow(item: HomeRediscoverItem, nowMillis: Long): String =
    "Rated ${item.scoreText} · Not played in Yoin for ${rediscoverAwayText(item.lastPlayedAt, nowMillis)}"

/**
 * "First played 2025.11 · 23 plays" (local month, play history only); either
 * half drops when history lacks it, null when both do.
 */
internal fun rediscoverFootnote(firstPlayedAt: Long?, playCount: Int, zone: ZoneId = ZoneId.systemDefault()): String? {
    val parts = buildList {
        firstPlayedAt?.let { millis ->
            add("First played " + Instant.ofEpochMilli(millis).atZone(zone).format(RediscoverMonthFormatter))
        }
        if (playCount > 0) add(if (playCount == 1) "1 play" else "$playCount plays")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

// ── Previews ───────────────────────────────────────────────────────────

private const val PreviewNow = 1_790_000_000_000L

private val PreviewRediscover = listOf(
    previewRediscoverItem("emotion", "Emotion", "Carly Rae Jepsen", 9.0f, daysAway = 214, playCount = 23),
    previewRediscoverItem("heaven", "Heaven or Las Vegas", "Cocteau Twins", 8.5f, daysAway = 160, playCount = 41),
    previewRediscoverItem("blonde", "Blonde", "Frank Ocean", 8.0f, daysAway = 400, playCount = 1),
)

private fun previewRediscoverItem(
    id: String,
    name: String,
    artist: String,
    score: Float,
    daysAway: Long,
    playCount: Int,
) = HomeRediscoverItem(
    albumId = MediaId.subsonic(id),
    albumName = name,
    artistName = artist,
    coverArtUrl = null,
    score = score,
    scoreText = rediscoverScoreText(score),
    scoreKind = MemoryScoreKind.ALBUM_RATING,
    lastPlayedAt = PreviewNow - daysAway * DayMillis,
    firstPlayedAt = PreviewNow - (daysAway + 120) * DayMillis,
    playCount = playCount,
)

@Composable
private fun RediscoverPreviewFrame(items: List<HomeRediscoverItem>) {
    val frame = rememberHomeFeedFrame(feedFrameClass(LocalYoinWindowInfo.current))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .feedFrameWidth(frame)
            .padding(FeedFrameSidePadding(frame))
            .padding(vertical = 16.dp),
    ) {
        RediscoverSection(
            items = items,
            frame = frame,
            nowMillis = PreviewNow,
            extractBackdropColors = false,
            onAlbumClick = { _, _ -> },
        )
    }
}

@Preview(name = "Rediscover · phone shelf", widthDp = 412, showBackground = true)
@Composable
private fun RediscoverPhonePreview() {
    YoinTheme {
        ProvidePreviewWindow(widthDp = 412, heightDp = 915) {
            RediscoverPreviewFrame(PreviewRediscover)
        }
    }
}

@Preview(
    name = "Rediscover · phone, one card",
    widthDp = 412,
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun RediscoverSinglePreview() {
    YoinTheme {
        ProvidePreviewWindow(widthDp = 412, heightDp = 915) {
            RediscoverPreviewFrame(PreviewRediscover.take(1))
        }
    }
}

@Preview(name = "Rediscover · tablet portrait", widthDp = 800, showBackground = true)
@Composable
private fun RediscoverTabletPreview() {
    YoinTheme {
        ProvidePreviewWindow(widthDp = 800, heightDp = 1280) {
            RediscoverPreviewFrame(PreviewRediscover)
        }
    }
}
