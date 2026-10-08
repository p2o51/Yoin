package com.gpo.yoin.ui.home

import android.content.res.Resources
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import com.gpo.yoin.ui.component.MetaGroup
import com.gpo.yoin.ui.component.MetaLine
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
import com.gpo.yoin.R
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
    "Nothing to rediscover yet" // i18n-allow: RediscoverCopyTest asserts this English

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
    val albumEyebrows = remember(shown, eyebrowMeasurer, eyebrowStyle) {
        RediscoverEyebrowReserve(
            measurer = eyebrowMeasurer,
            style = eyebrowStyle,
            lineCounts = shown.filter { it.song == null }.map(::rediscoverEyebrowLineCount),
        )
    }
    val songEyebrows = remember(shown, eyebrowMeasurer, eyebrowStyle) {
        RediscoverEyebrowReserve(
            measurer = eyebrowMeasurer,
            style = eyebrowStyle,
            lineCounts = shown.filter { it.song != null }.map(::rediscoverEyebrowLineCount),
        )
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeSectionTitle(text = stringResource(HomeSection.Rediscover.titleRes))
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
                RediscoverEyebrowBlock(
                    item = item,
                    nowMillis = nowMillis,
                    ink = colors.ink,
                    reserve = eyebrowReserve,
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
                RediscoverFootnoteLine(item.firstPlayedAt, item.playCount)
            }
        }
    }
}

/**
 * A song you rated or noted, as the album card's smaller sibling: the song's
 * Circle backdrop (the Home entity-shape mapping) at 76dp, the away line, the
 * title, artist and album, and — with no score to badge — one line of its
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
                    onClickLabel = stringResource(R.string.home_rediscover_play),
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
                RediscoverEyebrowBlock(
                    item = item,
                    nowMillis = nowMillis,
                    ink = colors.ink,
                    reserve = eyebrowReserve,
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
                    RediscoverSongLine(line)
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
 * bottom, against the title. A card with a reason line above the away label
 * no longer leaves its title and artist lower than its neighbours'. Layout
 * only: a width spring re-measures, never recomposes.
 */
private fun Modifier.rediscoverEyebrowSlot(reserve: RediscoverEyebrowReserve): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val height = maxOf(placeable.height, reserve.heightAt(constraints.maxWidth))
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(placeable.width, height) { placeable.placeRelative(0, height - placeable.height) }
    }

/**
 * The tallest eyebrow of one card kind, in whole lines of [style]. A reason
 * above the away label is two lines; the away label alone is one. Every card
 * of the kind shares the width, so the last answer is kept for the next card
 * asking at it.
 */
@Stable
internal class RediscoverEyebrowReserve(
    private val measurer: TextMeasurer,
    private val style: TextStyle,
    private val lineCounts: List<Int>,
) {
    private var lastWidth = -1
    private var lastHeight = 0

    fun heightAt(maxWidth: Int): Int {
        if (maxWidth == Constraints.Infinity || lineCounts.isEmpty()) return 0
        if (maxWidth != lastWidth) {
            val line = measurer.measure(
                text = "0",
                style = style,
                overflow = TextOverflow.Clip,
                maxLines = 1,
                softWrap = false,
                constraints = Constraints(maxWidth = maxWidth),
            ).size.height
            lastHeight = line * (lineCounts.maxOrNull() ?: 1)
            lastWidth = maxWidth
        }
        return lastHeight
    }
}

@Composable
private fun RediscoverEyebrowBlock(
    item: HomeRediscoverItem,
    nowMillis: Long,
    ink: Color,
    reserve: RediscoverEyebrowReserve,
) {
    val resources = LocalContext.current.resources
    val away = remember(item.lastPlayedAt, nowMillis, resources.configuration) {
        rediscoverEyebrow(item, nowMillis, resources)
    }
    val notes = rediscoverNoteCount(item)
    val reason = if (notes == null) rediscoverReason(item, resources) else null
    Column(modifier = Modifier.rediscoverEyebrowSlot(reserve)) {
        if (notes != null) {
            MetaLine(
                groups = listOf(
                    MetaGroup.Stat(
                        notes.toString(),
                        pluralStringResource(R.plurals.home_unit_notes, notes),
                    ),
                ),
                style = MaterialTheme.typography.labelMedium,
            )
        } else if (reason != null) {
            Text(
                text = reason,
                style = MaterialTheme.typography.labelMedium,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = away,
            style = MaterialTheme.typography.labelMedium,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RediscoverFootnoteLine(firstPlayedAt: Long?, playCount: Int) {
    val footnote = rediscoverFootnote(firstPlayedAt, playCount) ?: return
    val groups = buildList {
        footnote.month?.let { add(MetaGroup.Plain(it)) }
        if (footnote.playCount > 0) {
            add(
                MetaGroup.Stat(
                    footnote.playCount.toString(),
                    rediscoverPlayUnit(footnote.playCount, LocalContext.current.resources),
                ),
            )
        }
    }
    if (groups.isEmpty()) return
    MetaLine(
        groups = groups,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun RediscoverSongLine(line: RediscoverSongSubtitle) {
    val groups = buildList {
        line.artist?.let { add(MetaGroup.Plain(it)) }
        line.album?.let { add(MetaGroup.Plain(it, muted = true)) }
    }
    if (groups.isEmpty()) return
    MetaLine(groups = groups, style = MaterialTheme.typography.bodyMedium)
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
    val description = rediscoverBadgeDescription(scoreText, kind, LocalContext.current.resources)
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
internal fun rediscoverAwayText(
    lastPlayedAt: Long,
    nowMillis: Long,
    resources: Resources? = null,
): String {
    val days = ((nowMillis - lastPlayedAt) / DayMillis).coerceAtLeast(0L)
    return if (days >= 365) {
        val years = (days / 365).toInt()
        if (resources == null) {
            if (years == 1) "1 year" else "$years years" // i18n-allow: RediscoverCopyTest asserts this English
        } else {
            resources.getQuantityString(R.plurals.home_rediscover_years, years, years)
        }
    } else {
        val months = (days / 30).coerceAtLeast(1L).toInt()
        if (resources == null) {
            if (months == 1) "1 month" else "$months months" // i18n-allow: RediscoverCopyTest asserts this English
        } else {
            resources.getQuantityString(R.plurals.home_rediscover_months, months, months)
        }
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
internal fun rediscoverReason(item: HomeRediscoverItem, resources: Resources? = null): String? = when {
    item.scoreText != null -> null
    item.song != null -> null
    item.hasReview -> resources?.getString(R.string.home_rediscover_reviewed)
        ?: "Reviewed" // i18n-allow: RediscoverCopyTest asserts this English
    item.noteCount > 0 -> if (resources == null) {
        if (item.noteCount == 1) "1 note" else "${item.noteCount} notes" // i18n-allow: RediscoverCopyTest asserts this English
    } else {
        resources.getQuantityString(R.plurals.home_rediscover_notes, item.noteCount, item.noteCount)
    }
    item.ratedTrackCount > 0 -> if (resources == null) {
        if (item.ratedTrackCount == 1) "1 track rated" else "${item.ratedTrackCount} tracks rated" // i18n-allow: RediscoverCopyTest asserts this English
    } else {
        resources.getQuantityString(
            R.plurals.home_rediscover_tracks_rated,
            item.ratedTrackCount,
            item.ratedTrackCount,
        )
    }
    else -> null
}

/** Notes shown as [MetaGroup.Stat] above the away label. Null when a score, review, or song card says it instead. */
internal fun rediscoverNoteCount(item: HomeRediscoverItem): Int? =
    if (item.scoreText == null && item.song == null && !item.hasReview && item.noteCount > 0) item.noteCount else null

/** Two lines when a reason sits above the away label, otherwise the away label alone. */
internal fun rediscoverEyebrowLineCount(item: HomeRediscoverItem): Int =
    if (rediscoverReason(item) != null) 2 else 1

/** "7 months away" / "1 year away". The reason, when there is one, is a separate line. */
internal fun rediscoverEyebrow(
    item: HomeRediscoverItem,
    nowMillis: Long,
    resources: Resources? = null,
): String {
    if (resources == null) {
        return "${rediscoverAwayText(item.lastPlayedAt, nowMillis)} away" // i18n-allow: RediscoverCopyTest asserts this English
    }
    val days = ((nowMillis - item.lastPlayedAt) / DayMillis).coerceAtLeast(0L)
    return if (days >= 365) {
        val years = (days / 365).toInt()
        resources.getQuantityString(R.plurals.home_rediscover_eyebrow_years, years, years)
    } else {
        val months = (days / 30).coerceAtLeast(1L).toInt()
        resources.getQuantityString(R.plurals.home_rediscover_eyebrow_months, months, months)
    }
}

/** A song card's artist and album, either half dropping when unknown. */
internal data class RediscoverSongSubtitle(val artist: String?, val album: String?)

internal fun rediscoverSongSubtitle(item: HomeRediscoverItem): RediscoverSongSubtitle? {
    val artist = item.artistName?.takeIf(String::isNotBlank)
    val album = item.albumName.takeIf(String::isNotBlank)
    if (artist == null && album == null) return null
    return RediscoverSongSubtitle(artist, album)
}

/** TalkBack for the badge: "Your rating 9.0" / "Track average 8.4". */
internal fun rediscoverBadgeDescription(
    scoreText: String,
    kind: MemoryScoreKind,
    resources: Resources? = null,
): String = when (kind) {
    MemoryScoreKind.AVERAGE_TRACK_RATING ->
        resources?.getString(R.string.home_rediscover_cd_track_average, scoreText)
            ?: "Track average $scoreText" // i18n-allow: RediscoverCopyTest asserts this English
    else ->
        resources?.getString(R.string.home_rediscover_cd_your_rating, scoreText)
            ?: "Your rating $scoreText" // i18n-allow: RediscoverCopyTest asserts this English
}

/**
 * The first-play month (`yyyy.MM`) and play count. Either half is absent when
 * history lacks it; null when both are. The month stamp stays `yyyy.MM`.
 */
internal data class RediscoverFootnote(val month: String?, val playCount: Int)

internal fun rediscoverFootnote(
    firstPlayedAt: Long?,
    playCount: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): RediscoverFootnote? {
    val month = firstPlayedAt?.let { millis ->
        Instant.ofEpochMilli(millis).atZone(zone).format(RediscoverMonthFormatter)
    }
    val plays = playCount.takeIf { it > 0 }
    if (month == null && plays == null) return null
    return RediscoverFootnote(month, plays ?: 0)
}

/** English "play" / "plays", or the localized unit. The count stays separate. */
internal fun rediscoverPlayUnit(playCount: Int, resources: Resources? = null): String {
    if (resources == null) {
        return if (playCount == 1) "play" else "plays" // i18n-allow: RediscoverCopyTest asserts this English
    }
    return resources.getQuantityString(R.plurals.home_unit_plays, playCount)
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
