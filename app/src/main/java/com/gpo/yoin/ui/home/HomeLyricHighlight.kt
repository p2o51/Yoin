package com.gpo.yoin.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.BuildConfig
import com.gpo.yoin.R
import com.gpo.yoin.data.highlight.LyricHighlightProcessor
import com.gpo.yoin.data.highlight.LyricHighlightSelector
import com.gpo.yoin.data.highlight.LyricHighlightSignals
import com.gpo.yoin.data.highlight.NanoStatus
import com.gpo.yoin.data.local.LyricHighlight
import com.gpo.yoin.data.local.LyricHighlightDao
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.player.PlaybackManager
import com.gpo.yoin.ui.component.elasticPress
import com.gpo.yoin.ui.component.ignoreParentHorizontalPadding
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.component.seamDissolve
import com.gpo.yoin.ui.home.edit.LocalHomeEditCardScope
import com.gpo.yoin.ui.home.edit.homeEditCard
import com.gpo.yoin.ui.home.edit.homeEditInteractive
import com.gpo.yoin.ui.home.edit.homeEditShelfClip
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinSerifTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Home's 「歌词一句」 (owner 2026-11-08): lyric lines Gemini Nano picked on this phone
 * (data/highlight/), plus lines the user shared or saved themselves, each on its song's
 * cover wash, one to four cards that swipe sideways. A tap plays the song from that line.
 * Shown only where Nano is available, and only once there is at least one highlight.
 */

/** The highlight on Home: the picked lines, its song, and why it was chosen. */
@Immutable
data class HomeLyricHighlight(
    val songId: MediaId,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: MediaId?,
    /** A [CoverRef] storage key. */
    val coverKey: String?,
    val durationMs: Long,
    /** The original lines, joined by "\n". */
    val text: String,
    val startMs: Long?,
    val kind: LyricHighlightSelector.Bucket?,
) {
    fun asTrack(): Track = Track(
        id = songId,
        title = title,
        artist = artist,
        artistId = null,
        album = album.ifBlank { null },
        albumId = albumId,
        coverArt = CoverRef.fromStorageKey(coverKey),
        durationSec = (durationMs / 1000L).toInt().takeIf { it > 0 },
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
    )
}

/** Home's current highlights, a few songs at a time, and playing one from its line. */
class LyricHighlightViewModel internal constructor(
    private val dao: LyricHighlightDao,
    private val processor: LyricHighlightProcessor,
    private val profiles: ProfileManager,
    private val playback: PlaybackManager,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /** Whether this device has Nano at all; Home leaves the section out entirely when it doesn't. */
    val available: StateFlow<Boolean> = processor.status
        .map { it == NanoStatus.AVAILABLE || BuildConfig.NANO_TEST }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            processor.status.value == NanoStatus.AVAILABLE || BuildConfig.NANO_TEST,
        )

    /** The Gemini Nano test copy's step log, newest first; always empty in a normal build. */
    val testLog: StateFlow<List<String>> = processor.events

    private val _cards = MutableStateFlow<List<HomeLyricHighlight>>(emptyList())

    /** Up to [MAX_CARDS] different songs' lines; Home shows as many as the section's setting asks for. */
    val cards: StateFlow<List<HomeLyricHighlight>> = _cards.asStateFlow()

    init {
        // A new set for each visit to Home (this ViewModel's life) or a new account; a highlight arriving
        // only fills an empty place, so no line is swapped out from under the reader.
        viewModelScope.launch {
            profiles.activeProfileId.collect { draw(keep = emptyList()) }
        }
        viewModelScope.launch {
            processor.revision.collect { if (_cards.value.size < MAX_CARDS) draw(keep = _cards.value) }
        }
    }

    private var drawnAt = 0L

    /** Home came back into view: a fresh set when the last one has been up a while. */
    fun onHomeShown() {
        if (clock() - drawnAt >= ROTATE_AFTER_MS) viewModelScope.launch { draw(keep = emptyList()) }
    }

    private suspend fun draw(keep: List<HomeLyricHighlight>) {
        val picked = withContext(Dispatchers.IO) { pick(keep) } ?: return
        if (keep.isEmpty()) drawnAt = clock()
        _cards.value = picked
    }

    /** [keep] plus new songs up to [MAX_CARDS]; null when nothing changed. */
    private suspend fun pick(keep: List<HomeLyricHighlight>): List<HomeLyricHighlight>? {
        if (processor.status.value != NanoStatus.AVAILABLE && !BuildConfig.NANO_TEST) return emptyList()
        val profileId = profiles.activeProfileId.value ?: return emptyList()
        val source = profiles.activeSource.value ?: withTimeoutOrNull(SOURCE_WAIT_MS) {
            profiles.activeSource.first { it != null }
        } ?: return emptyList()
        val provider = source.id
        val kept = keep.filter { it.songId.provider == provider }
        val rows = dao.usable(profileId, provider)
        val now = clock()
        val history = LyricHighlightSignals.load(dao, profileId, provider, now)
        val userPicks = rows.filter { it.model == LyricHighlightProcessor.USER_PICK }.mapTo(HashSet()) { it.songId }
        val bucketOf = { songId: String ->
            if (songId in userPicks) LyricHighlightSelector.Bucket.USER else history.bucketOf(songId, now)
        }
        val playing = playback.playbackState.value.currentTrack?.id?.rawId
        val taken = kept.mapTo(HashSet()) { it.songId.rawId }
        val added = mutableListOf<HomeLyricHighlight>()
        while (kept.size + added.size < MAX_CARDS) {
            val pool = rows.filter { it.songId !in taken }
            if (pool.isEmpty()) break
            // Not the song playing now while there are others.
            val chosen = LyricHighlightSelector.nextToShow(
                shown = pool.map { LyricHighlightSelector.Shown(it.songId, it.lastShownAt) },
                bucketOf = bucketOf,
                now = now,
                exclude = playing.takeIf { pool.size > 1 },
            ) ?: break
            val row = pool.first { it.songId == chosen.songId }
            dao.markShown(profileId, provider, row.songId, now)
            taken += row.songId
            added += row.toCard(bucketOf(row.songId))
        }
        if (keep.isNotEmpty() && added.isEmpty() && kept.size == keep.size) return null
        return kept + added
    }

    /**
     * Plays the song alone, then moves to the picked line once it is playing. Without a synced line it just
     * plays from the top.
     */
    fun play(card: HomeLyricHighlight) {
        viewModelScope.launch {
            val source = profiles.activeSource.value ?: withTimeoutOrNull(SOURCE_WAIT_MS) {
                profiles.activeSource.first { it != null }
            } ?: return@launch
            if (source.id != card.songId.provider) return@launch
            playback.playSingle(track = card.asTrack(), source = source)
            val startMs = card.startMs?.takeIf { it > SEEK_LEAD_MS } ?: return@launch
            val started = withTimeoutOrNull(START_WAIT_MS) {
                playback.playbackState.first { state ->
                    state.currentTrack?.id == card.songId && state.isPlaying && state.duration > 0
                }
            } ?: return@launch
            if (startMs < started.duration) playback.seekTo(startMs - SEEK_LEAD_MS)
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LyricHighlightViewModel(
            dao = container.database.lyricHighlightDao(),
            processor = container.lyricHighlightProcessor,
            profiles = container.profileManager,
            playback = container.playbackManager,
        ) as T
    }

    private companion object {
        const val SOURCE_WAIT_MS = 4_000L

        /** The most lines the section can show (its largest setting). */
        const val MAX_CARDS = 4
        const val START_WAIT_MS = 10_000L

        /** Coming back to Home after this long shows another line. */
        const val ROTATE_AFTER_MS = 20L * 60L * 1000L

        /** Land a breath before the line, so its first word isn't clipped. */
        const val SEEK_LEAD_MS = 600L
    }
}

private fun LyricHighlight.toCard(kind: LyricHighlightSelector.Bucket?): HomeLyricHighlight = HomeLyricHighlight(
    songId = MediaId(provider, songId),
    title = title,
    artist = artist,
    album = album,
    albumId = albumId.takeIf { it.isNotBlank() }?.let { MediaId(provider, it) },
    coverKey = coverArtId,
    durationMs = durationMs,
    text = text.orEmpty(),
    startMs = startMs,
    kind = kind,
)

private val LyricCardPadding = 16.dp
private val LyricCardArtSize = 40.dp
private val LyricCardWidth = 290.dp
private val LyricCardHeight = 216.dp
private val LyricCardGap = 8.dp

/** How much of the next card shows past a card's edge when there are several. */
private val LyricCardPeek = 40.dp

/**
 * The section's setting as a number of cards (owner 2026-11-08: two to four, four by default; each card's lines
 * stay as they are). XL is never written; a stored one reads as four.
 */
internal val HomeRowPreset.lyricCards: Int
    get() = when (this) {
        HomeRowPreset.S -> 2
        HomeRowPreset.M -> 3
        HomeRowPreset.L, HomeRowPreset.XL -> 4
    }

/**
 * The section (owner 2026-11-08, design C): its title and a shelf of [count] cards that swipe sideways, page
 * dots under them. Each card wears its song's cover wash; the lines are set in the serif so they read as a
 * quote, under a small eyebrow for why the song came up. One card fills the width and has no dots.
 */
@Composable
internal fun LyricHighlightSection(
    cards: List<HomeLyricHighlight>,
    count: Int,
    // The feed's frame, for a shelf that bleeds to the screen's edges; null (the Tips demo) keeps it in the column.
    frame: HomeFeedFrame?,
    buildCoverArtUrl: (String) -> String,
    extractBackdropColors: Boolean,
    onPlay: (HomeLyricHighlight) -> Unit,
    modifier: Modifier = Modifier,
    shelfScrollEnabled: Boolean = true,
    listState: LazyListState = rememberLazyListState(),
    testLog: List<String> = emptyList(),
) {
    val editCardScope = LocalHomeEditCardScope.current
    val shown = cards.take(count.coerceIn(1, 4))
    val single = shown.size <= 1
    // The column's width is the content width either way (the shelf itself may bleed past it).
    val columnWidth = remember { mutableIntStateOf(0) }
    Column(
        modifier = modifier.onSizeChanged { columnWidth.intValue = it.width },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeSectionTitle(text = stringResource(HomeSection.LyricHighlight.titleRes))
        val sidePadding = remember(frame) { frame?.let(::FeedFrameSidePadding) ?: PaddingValues() }
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (frame == null) {
                        Modifier
                    } else {
                        Modifier
                            .ignoreParentHorizontalPadding(start = { frame.start }, end = { frame.end })
                            .homeEditShelfClip(
                                editCardScope,
                                escapeStart = { frame.start },
                                escapeEnd = { frame.end },
                            )
                    },
                ),
            contentPadding = sidePadding,
            horizontalArrangement = Arrangement.spacedBy(LyricCardGap),
            verticalAlignment = Alignment.Top,
            userScrollEnabled = shelfScrollEnabled && !single,
            flingBehavior = rememberSnapFlingBehavior(listState),
        ) {
            itemsIndexed(items = shown, key = { _, card -> card.songId.rawId }) { index, card ->
                LyricHighlightCard(
                    card = card,
                    coverArtUrl = remember(card.coverKey, buildCoverArtUrl) {
                        when (val ref = CoverRef.fromStorageKey(card.coverKey)) {
                            null -> null
                            is CoverRef.Url -> ref.url
                            is CoverRef.SourceRelative -> buildCoverArtUrl(ref.coverArtId)
                        }
                    },
                    extractBackdropColors = extractBackdropColors,
                    onPlay = { onPlay(card) },
                    modifier = Modifier
                        .animateItem()
                        .lyricCardWidth({ columnWidth.intValue }, single)
                        .height(LyricCardHeight)
                        .homeEditCard(index),
                )
            }
        }
        if (!single) {
            val current by remember(listState) {
                derivedStateOf {
                    val info = listState.layoutInfo
                    // The last card counts once the shelf has run out of room to scroll.
                    if (!listState.canScrollForward && info.totalItemsCount > 0) {
                        info.totalItemsCount - 1
                    } else {
                        listState.firstVisibleItemIndex +
                            if (listState.firstVisibleItemScrollOffset > LyricDotSwitchPx) 1 else 0
                    }
                }
            }
            LyricPageDots(
                count = shown.size,
                current = current,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        if (testLog.isNotEmpty()) NanoTestLog(lines = testLog, withTitle = false, modifier = Modifier.fillMaxWidth())
    }
}

// Scroll past which the dots move on to the next card (about a third of a card).
private const val LyricDotSwitchPx = 280

/** A card's width: the whole content column for one card, else [LyricCardWidth] leaving the next one peeking. */
private fun Modifier.lyricCardWidth(contentPx: () -> Int, single: Boolean): Modifier = layout { measurable, constraints ->
    val content = contentPx()
    val px = if (single) {
        content
    } else {
        minOf(LyricCardWidth.roundToPx(), content - LyricCardPeek.roundToPx())
    }.coerceAtLeast(0)
    val placeable = measurable.measure(constraints.copy(minWidth = px, maxWidth = px))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** The page dots: the current card a short pill, the others dots, moving on a spring. */
@Composable
private fun LyricPageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            val active = index == current
            val width by animateDpAsState(
                targetValue = if (active) 18.dp else 6.dp,
                animationSpec = YoinMotion.spatialSpring(),
                label = "lyricDot",
            )
            val color by animateColorAsState(
                targetValue = if (active) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                animationSpec = YoinMotion.effectsSpring(),
                label = "lyricDotColor",
            )
            Box(
                Modifier
                    .size(width = width, height = 6.dp)
                    .background(color, CircleShape),
            )
        }
    }
}

/**
 * The Gemini Nano test copy's log (BuildConfig.NANO_TEST): what Nano is doing, newest first, so a run on a real
 * phone can be read without adb. Never shown in a normal build (its log stays empty).
 */
@Composable
internal fun NanoTestLog(lines: List<String>, withTitle: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (withTitle) HomeSectionTitle(text = stringResource(HomeSection.LyricHighlight.titleRes))
        Surface(
            shape = YoinContainerShapes.Card,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(LyricCardPadding),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Nano test",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                lines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricHighlightCard(
    card: HomeLyricHighlight,
    coverArtUrl: String?,
    extractBackdropColors: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = rememberActivityCardColors(coverArtUrl, extractBackdropColors)
    Surface(
        modifier = modifier
            .elasticPress(interactionSource)
            .seamDissolve(),
        shape = YoinContainerShapes.Card,
        color = colors.container,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .noRippleClickable(
                    interactionSource = interactionSource,
                    enabled = homeEditInteractive(),
                    onClickLabel = stringResource(R.string.home_lyric_play_from_line),
                    onClick = onPlay,
                )
                .padding(LyricCardPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WidgetBackdropArtwork(
                    model = coverArtUrl,
                    kind = WidgetShapeKind.Song,
                    contentDescription = card.title,
                    extractBackdropColors = extractBackdropColors,
                    interactionSource = interactionSource,
                    modifier = Modifier.size(LyricCardArtSize),
                )
                card.kind?.let { kind ->
                    Text(
                        text = stringResource(kind.labelRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.ink,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = card.text,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = YoinSerifTitle,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    lineHeight = 30.sp,
                ),
                color = colors.content,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                // No seamFade on the card's text: inside a swiping shelf the card's print lifted a
                // recycled neighbour's text and left it ghosting over the peeking card (Pixel 11, 11-08).
                modifier = Modifier.weight(1f),
            )
            Text(
                text = listOf(card.title, card.artist).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = colors.contentMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val LyricHighlightSelector.Bucket.labelRes: Int
    get() = when (this) {
        LyricHighlightSelector.Bucket.HEAVY -> R.string.home_lyric_kind_heavy
        LyricHighlightSelector.Bucket.REDISCOVER -> R.string.home_lyric_kind_rediscover
        LyricHighlightSelector.Bucket.FRESH -> R.string.home_lyric_kind_fresh
        LyricHighlightSelector.Bucket.USER -> R.string.home_lyric_kind_user
    }
