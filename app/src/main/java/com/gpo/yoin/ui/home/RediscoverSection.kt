package com.gpo.yoin.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
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
import com.gpo.yoin.ui.home.edit.LocalHomeEditCardScope
import com.gpo.yoin.ui.home.edit.homeEditCard
import com.gpo.yoin.ui.home.edit.homeEditShelfClip
import com.gpo.yoin.ui.home.edit.homeEditInteractive
import com.gpo.yoin.ui.memories.MemoryScoreKind
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.withTabularFigures
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ── Rediscover (P0-9) ──────────────────────────────────────────────────
//
// Albums you kept something on — a rating, a review, a note, a rated track —
// that haven't played in Yoin for 90+ days, each with one line of reasons you
// can check and, when the album has a score, a rating badge on its cover's
// corner (owner 2026-10-05: no 8.0 bar; any album with a memory comes back).
// Tapping opens the album (not the Memories deck — that's what tells it
// apart from a Memory card). Songs you rated or noted come back too (owner
// 2026-10-05, "rediscover 本身也可以加入一些歌曲"): a smaller card on the
// song's Circle backdrop, its rating badge or one line of its newest note;
// tapping plays it alone. One order for both. A shelf in every tier so a
// played card leaves with animateItem; only the phone's peeking shelf
// actually scrolls.

/** Edit mode's placeholder copy when Rediscover has nothing to bring back. */
internal const val RediscoverPlaceholderText =
    "Albums and songs you rated or wrote about come back here when it's been a while"

/** Before × fontScale: the 104dp backdrop plus 14dp padding above and below. */
private val RediscoverCardHeight = 132.dp
private val RediscoverBackdropSize = 104.dp
private val RediscoverCardPadding = 14.dp
private val RediscoverCardGap = 12.dp

// A song card is the album card's smaller sibling: a 76dp Circle backdrop and
// 12dp padding, which leaves its four text lines (a two-line eyebrow at most)
// 92dp beside the cover. Before × fontScale.
private val RediscoverSongCardHeight = 116.dp
private val RediscoverSongBackdropSize = 76.dp
private val RediscoverSongCardPadding = 12.dp

// The note snippet's rail: the journal's track-note mark, kept to one line.
private val RediscoverNoteRail = 2.dp
private val RediscoverNoteRailGap = 8.dp

// The rating badge: a sticker on the backdrop's bottom-end corner, hanging a
// little into the gap so it reads as beside the cover, not printed on it.
private val RediscoverBadgeHeight = 24.dp
private val RediscoverBadgePadding = 8.dp
private val RediscoverBadgeHang = 6.dp
private val RediscoverBadgeLine = 1.5.dp

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
    onSongClick: (Track) -> Unit,
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
    val editCardScope = LocalHomeEditCardScope.current
    // One eyebrow height per card kind across the whole shelf (a phone's
    // off-screen cards included), so the row's titles line up.
    val eyebrowStyle = MaterialTheme.typography.labelMedium
    val eyebrowMeasurer = rememberTextMeasurer()
    val albumEyebrows = remember(shown, nowMillis, eyebrowMeasurer, eyebrowStyle) {
        RediscoverEyebrowReserve(
            measurer = eyebrowMeasurer,
            style = eyebrowStyle,
            eyebrows = shown.filter { it.song == null }.map { rediscoverEyebrow(it, nowMillis) },
        )
    }
    val songEyebrows = remember(shown, nowMillis, eyebrowMeasurer, eyebrowStyle) {
        RediscoverEyebrowReserve(
            measurer = eyebrowMeasurer,
            style = eyebrowStyle,
            eyebrows = shown.filter { it.song != null }.map { rediscoverEyebrow(it, nowMillis) },
        )
    }
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
                .horizontalEdgeFadeOnScroll(shelfState)
                // Plates V1 (shipped) / V2 only: clipped to the edit plate while editing.
                .homeEditShelfClip(editCardScope, escapeStart = { frame.start }, escapeEnd = { frame.end }),
            contentPadding = sidePadding,
            horizontalArrangement = Arrangement.spacedBy(RediscoverCardGap),
            // A smaller song card sits on the album cards' centre line.
            verticalAlignment = Alignment.CenterVertically,
            userScrollEnabled = scrollEnabled && shown.size > (if (shelf) 1 else visibleCount),
        ) {
            // Edit-mode card order = shelf order.
            itemsIndexed(
                items = shown,
                key = { _, item -> item.shelfKey },
            ) { index, item ->
                val song = item.song
                val cardModifier = Modifier
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
                    .height((if (song != null) RediscoverSongCardHeight else RediscoverCardHeight) * fontScale)
                    // Width follows the feed's content width in the layout
                    // pass only, so a column spring never recomposes the shelf.
                    .layout { measurable, constraints ->
                        // Less the plate's content inset (V2; else 0).
                        val content = frame.contentWidth - (editCardScope?.contentInset() ?: 0.dp) * 2
                        val width = lerp(
                            rediscoverCardWidth(content, visibleCount, itemCount = 2, gap = RediscoverCardGap),
                            rediscoverCardWidth(content, visibleCount, itemCount = 1, gap = RediscoverCardGap),
                            lone,
                        ).roundToPx()
                        val placeable = measurable.measure(
                            constraints.copy(minWidth = width, maxWidth = width),
                        )
                        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                    }
                if (song != null) {
                    RediscoverSongCard(
                        item = item,
                        nowMillis = nowMillis,
                        eyebrowReserve = songEyebrows,
                        extractBackdropColors = extractBackdropColors,
                        // Plays alone, no haptic: the Now Playing pill answers the tap.
                        onClick = { onSongClick(song) },
                        modifier = cardModifier,
                    )
                } else {
                    RediscoverCard(
                        item = item,
                        nowMillis = nowMillis,
                        eyebrowReserve = albumEyebrows,
                        extractBackdropColors = extractBackdropColors,
                        onClick = { onAlbumClick(item.albumId.toString(), null) },
                        modifier = cardModifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun RediscoverCard(
    item: HomeRediscoverItem,
    nowMillis: Long,
    eyebrowReserve: RediscoverEyebrowReserve,
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
            Box(modifier = Modifier.size(RediscoverBackdropSize)) {
                WidgetBackdropArtwork(
                    model = item.coverArtUrl,
                    kind = WidgetShapeKind.Album,
                    contentDescription = item.albumName,
                    extractBackdropColors = extractBackdropColors,
                    interactionSource = interactionSource,
                    modifier = Modifier.fillMaxSize(),
                )
                item.scoreText?.let { score ->
                    RediscoverScoreBadge(
                        scoreText = score,
                        kind = item.scoreKind,
                        ink = colors.ink,
                        container = colors.container,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = RediscoverBadgeHang),
                    )
                }
            }
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
                    maxLines = RediscoverEyebrowMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.rediscoverEyebrowSlot(eyebrowReserve),
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

/**
 * A song you rated or noted, as the album card's smaller sibling: the song's
 * Circle backdrop (the Home entity-shape mapping) at 76dp, the away line, the
 * title, "artist · album", and — with no score to badge — one line of its
 * newest note on the journal's track-note rail. Tapping plays it alone.
 */
@Composable
private fun RediscoverSongCard(
    item: HomeRediscoverItem,
    nowMillis: Long,
    eyebrowReserve: RediscoverEyebrowReserve,
    extractBackdropColors: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = rememberActivityCardColors(item.coverArtUrl, extractBackdropColors)
    val eyebrow = remember(item, nowMillis) { rediscoverEyebrow(item, nowMillis) }
    val subtitle = remember(item) { rediscoverSongSubtitle(item) }
    val snippet = item.noteSnippet?.takeIf { item.scoreText == null }
    Surface(
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
                    onClickLabel = RediscoverPlayLabel,
                    onClick = onClick,
                )
                .padding(RediscoverSongCardPadding),
            horizontalArrangement = Arrangement.spacedBy(RediscoverSongCardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(RediscoverSongBackdropSize)) {
                WidgetBackdropArtwork(
                    model = item.coverArtUrl,
                    kind = WidgetShapeKind.Song,
                    contentDescription = item.title,
                    extractBackdropColors = extractBackdropColors,
                    interactionSource = interactionSource,
                    modifier = Modifier.fillMaxSize(),
                )
                item.scoreText?.let { score ->
                    RediscoverScoreBadge(
                        scoreText = score,
                        kind = item.scoreKind,
                        ink = colors.ink,
                        container = colors.container,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = RediscoverBadgeHang),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .seamFade(),
            ) {
                Text(
                    text = eyebrow,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.ink,
                    maxLines = RediscoverEyebrowMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.rediscoverEyebrowSlot(eyebrowReserve),
                )
                MarqueeText(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        lineHeight = 22.sp,
                    ),
                    color = colors.content,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                )
                subtitle?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.contentMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                snippet?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .noteRail(colors.ink)
                            .padding(start = RediscoverNoteRail + RediscoverNoteRailGap),
                    )
                }
            }
        }
    }
}

/**
 * The eyebrow's slot: as tall as the shelf's tallest eyebrow of the same card
 * kind ([RediscoverEyebrowReserve]), the card's own eyebrow sitting on its
 * bottom, against the title. So one card's two-line "1 note · Not played in
 * Yoin for 8 months" no longer leaves its title, artist and footnote lower
 * than its neighbours' — every card in the row keeps the same lines, the copy
 * whole. Layout only: a width spring re-measures, never recomposes.
 */
private fun Modifier.rediscoverEyebrowSlot(reserve: RediscoverEyebrowReserve): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val height = maxOf(placeable.height, reserve.heightAt(constraints.maxWidth))
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(placeable.width, height) { placeable.placeRelative(0, height - placeable.height) }
    }

/** An eyebrow wraps to this many lines at most ("1 note · Not played in Yoin for 8 months" on a phone). */
private const val RediscoverEyebrowMaxLines = 2

/**
 * The tallest of one card kind's [eyebrows] on the shelf, laid out in [style]
 * at a card's text width ([heightAt], px) — at most [RediscoverEyebrowMaxLines]
 * lines. Every card of the kind shares the width, so the last answer is kept
 * for the next card asking at it.
 */
@Stable
internal class RediscoverEyebrowReserve(
    private val measurer: TextMeasurer,
    private val style: TextStyle,
    private val eyebrows: List<String>,
) {
    private var lastWidth = -1
    private var lastHeight = 0

    fun heightAt(maxWidth: Int): Int {
        if (maxWidth == Constraints.Infinity || eyebrows.isEmpty()) return 0
        if (maxWidth != lastWidth) {
            lastHeight = eyebrows.maxOf { text ->
                measurer.measure(
                    text = text,
                    style = style,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = RediscoverEyebrowMaxLines,
                    constraints = Constraints(maxWidth = maxWidth),
                ).size.height
            }
            lastWidth = maxWidth
        }
        return lastHeight
    }
}

/** The journal's track-note rail down the snippet's leading edge (draw only). */
private fun Modifier.noteRail(color: Color): Modifier = drawBehind {
    val width = RediscoverNoteRail.toPx()
    val inset = 2.dp.toPx()
    if (size.height <= inset * 2) return@drawBehind
    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
    drawRoundRect(
        color = color,
        topLeft = Offset(x, inset),
        size = Size(width, size.height - inset * 2),
        cornerRadius = CornerRadius(width / 2f, width / 2f),
    )
}

/**
 * The score as a sticker, in the Memories seal's grammar: your album rating
 * is a solid ink pill, a track average an ink outline on the card's wash.
 * Ink is the card's own (palette accent in dark, base in light — the JBI
 * score rule), score digits tabular. A badge, not a cover outline.
 */
@Composable
private fun RediscoverScoreBadge(
    scoreText: String,
    kind: MemoryScoreKind,
    ink: Color,
    container: Color,
    modifier: Modifier = Modifier,
) {
    val solid = kind != MemoryScoreKind.AVERAGE_TRACK_RATING
    val description = rediscoverBadgeDescription(scoreText, kind)
    Box(
        modifier = modifier
            .height(RediscoverBadgeHeight)
            .clearAndSetSemantics { contentDescription = description }
            .seamDissolve()
            .background(color = if (solid) ink else container, shape = YoinShapeTokens.Full)
            .then(
                if (solid) {
                    Modifier
                } else {
                    Modifier.border(RediscoverBadgeLine, ink, YoinShapeTokens.Full)
                },
            )
            .padding(horizontal = RediscoverBadgePadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = scoreText,
            style = MaterialTheme.typography.labelLarge
                .copy(fontWeight = FontWeight.Bold)
                .withTabularFigures(),
            color = if (solid) container else ink,
            maxLines = 1,
        )
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

/**
 * What was kept on an album with no score to badge: a review, then notes, then
 * rated tracks — "Reviewed", "3 notes", "2 tracks rated". Null when a score
 * exists (the badge says it), on a song card (its note snippet says it) or
 * when nothing is known.
 */
internal fun rediscoverReason(item: HomeRediscoverItem): String? = when {
    item.scoreText != null -> null
    item.song != null -> null
    item.hasReview -> "Reviewed"
    item.noteCount > 0 -> if (item.noteCount == 1) "1 note" else "${item.noteCount} notes"
    item.ratedTrackCount > 0 ->
        if (item.ratedTrackCount == 1) "1 track rated" else "${item.ratedTrackCount} tracks rated"
    else -> null
}

/**
 * "Not played in Yoin for 7 months" — the score sits in the badge beside it —
 * or, with no score, the memory first: "3 notes · Not played in Yoin for 7 months".
 */
internal fun rediscoverEyebrow(item: HomeRediscoverItem, nowMillis: Long): String {
    val away = "Not played in Yoin for ${rediscoverAwayText(item.lastPlayedAt, nowMillis)}"
    return rediscoverReason(item)?.let { reason -> "$reason · $away" } ?: away
}

/** A song card's second line: "Artist · Album", either half dropping when unknown. */
internal fun rediscoverSongSubtitle(item: HomeRediscoverItem): String? =
    listOfNotNull(item.artistName?.takeIf(String::isNotBlank), item.albumName.takeIf(String::isNotBlank))
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")

/** TalkBack's click label on a song card: it plays, it doesn't open. */
private const val RediscoverPlayLabel = "Play"

/** TalkBack for the badge: "Your rating 9.0" / "Track average 8.4". */
internal fun rediscoverBadgeDescription(scoreText: String, kind: MemoryScoreKind): String = when (kind) {
    MemoryScoreKind.AVERAGE_TRACK_RATING -> "Track average $scoreText"
    else -> "Your rating $scoreText"
}

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
    previewRediscoverSong("s1", "Late Train Home", "Quiet Lines", "Harbour Lights", 8.5f, daysAway = 130),
    previewRediscoverItem(
        "heaven",
        "Heaven or Las Vegas",
        "Cocteau Twins",
        8.4f,
        daysAway = 160,
        playCount = 41,
        kind = MemoryScoreKind.AVERAGE_TRACK_RATING,
    ),
    previewRediscoverItem("blonde", "Blonde", "Frank Ocean", null, daysAway = 400, playCount = 1, notes = 3),
    previewRediscoverSong(
        "s2",
        "Paper Kites",
        "Ena",
        "Long Way Round",
        null,
        daysAway = 120,
        note = "The drums come in late and it's worth the wait every time",
    ),
)

private fun previewRediscoverSong(
    id: String,
    title: String,
    artist: String,
    album: String,
    score: Float?,
    daysAway: Long,
    note: String? = null,
) = HomeRediscoverItem(
    albumId = MediaId.subsonic("album-$id"),
    albumName = album,
    artistName = artist,
    coverArtUrl = null,
    score = score,
    scoreText = score?.let(::rediscoverScoreText),
    scoreKind = if (score == null) MemoryScoreKind.NONE else MemoryScoreKind.ALBUM_RATING,
    lastPlayedAt = PreviewNow - daysAway * DayMillis,
    firstPlayedAt = PreviewNow - (daysAway + 60) * DayMillis,
    playCount = 9,
    noteCount = if (note != null) 1 else 0,
    song = Track(
        id = MediaId.subsonic(id),
        title = title,
        artist = artist,
        artistId = null,
        album = album,
        albumId = MediaId.subsonic("album-$id"),
        coverArt = null,
        durationSec = 214,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
    ),
    noteSnippet = note,
)

private fun previewRediscoverItem(
    id: String,
    name: String,
    artist: String,
    score: Float?,
    daysAway: Long,
    playCount: Int,
    kind: MemoryScoreKind = MemoryScoreKind.ALBUM_RATING,
    notes: Int = 0,
) = HomeRediscoverItem(
    albumId = MediaId.subsonic(id),
    albumName = name,
    artistName = artist,
    coverArtUrl = null,
    score = score,
    scoreText = score?.let(::rediscoverScoreText),
    scoreKind = if (score == null) MemoryScoreKind.NONE else kind,
    lastPlayedAt = PreviewNow - daysAway * DayMillis,
    firstPlayedAt = PreviewNow - (daysAway + 120) * DayMillis,
    playCount = playCount,
    noteCount = notes,
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
            onSongClick = {},
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

@Preview(name = "Rediscover · phone, songs only", widthDp = 412, showBackground = true)
@Composable
private fun RediscoverSongsPreview() {
    YoinTheme {
        ProvidePreviewWindow(widthDp = 412, heightDp = 915) {
            RediscoverPreviewFrame(PreviewRediscover.filter { it.song != null })
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
