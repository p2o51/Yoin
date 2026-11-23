package com.gpo.yoin.ui.tips

import com.gpo.yoin.ui.component.handPointer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gpo.yoin.R
import com.gpo.yoin.data.highlight.LyricHighlightSelector
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.data.source.spotify.SpotifyElsewhere
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.BarEditLeftSlot
import com.gpo.yoin.ui.component.BarEditPose
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.YoinButtonGroup
import com.gpo.yoin.ui.home.HomeAccountButton
import com.gpo.yoin.ui.home.HomeAccountFanState
import com.gpo.yoin.ui.home.HomeAccountQuickSwitch
import com.gpo.yoin.ui.home.HomeGreetingTitle
import com.gpo.yoin.ui.home.HomeHeaderTitleBreathing
import com.gpo.yoin.ui.home.HomeLyricHighlight
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.HomeSpotifyElsewherePill
import com.gpo.yoin.ui.home.HomeWidgetGridSection
import com.gpo.yoin.ui.home.LyricHighlightSection
import com.gpo.yoin.ui.home.MemoryBubbleController
import com.gpo.yoin.ui.home.MemoryBubbleOverlay
import com.gpo.yoin.ui.home.edit.HomeEditCardScope
import com.gpo.yoin.ui.home.edit.LocalHomeEditCardScope
import com.gpo.yoin.ui.home.edit.homeEditHeaderIcon
import com.gpo.yoin.ui.home.memoryBubbleFreeSpan
import com.gpo.yoin.ui.home.memoryBubblePage
import com.gpo.yoin.ui.home.memoryBubbleTitle
import com.gpo.yoin.ui.home.titleRes
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryEntry
import com.gpo.yoin.ui.memories.MemoryScoreKind
import com.gpo.yoin.ui.memories.award.MemoriesAwardLifecycle
import com.gpo.yoin.ui.memories.MemoryOtherProfile
import com.gpo.yoin.ui.memories.copy.MemoryExcerptAttribution
import com.gpo.yoin.ui.memories.copy.MemoryExcerptCandidate
import com.gpo.yoin.ui.memories.copy.MemoryExcerptKind
import com.gpo.yoin.ui.memories.emblem.rememberGrooveTilt
import com.gpo.yoin.ui.memories.memoriesAuroraBackground
import com.gpo.yoin.ui.memories.showcase.CardEmblem
import com.gpo.yoin.ui.memories.showcase.MemoriesTopBar
import com.gpo.yoin.ui.memories.showcase.MemoriesTopBarTokens
import com.gpo.yoin.ui.memories.showcase.MemoryCardFace
import com.gpo.yoin.ui.memories.showcase.MemoryPaletteSamples
import com.gpo.yoin.ui.memories.showcase.memoryCardMetrics
import com.gpo.yoin.ui.navigation.YoinSection
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinMotion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Stable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt

/*
 * Home on the demo phone: the real header (greeting, "Home" → "Edit Home",
 * the Memories arrow), the real Jump Back In grid (or a scene's own body),
 * and the real bar — idle Home | Library, or [Undo] [Done] in edit.
 */

/**
 * Rearranging Home on a tablet on its side (two columns): long-press Home into edit mode, pinch
 * the sections into a list and back out, long-press a section and drag it up, then drop one
 * beside another and the two share a row. The real header and bar; the sections are drawn
 * simply (plate, title, covers, handle), each step as the app answers it.
 */
@Composable
internal fun EditHomeDemo(playing: Boolean) {
    var editing by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val spatial = YoinMotion.spatialSpring<Float>()
    val scope = rememberCoroutineScope()
    val state = remember { DemoEditFeed() }
    LaunchedEffect(editing) {
        progress.animateTo(if (editing) 1f else 0f, spatial)
    }
    DemoPhone(playing = playing, wide = true, script = {
        editing = false
        state.reset()
        delay(400)
        // Long-press Home: edit mode.
        moveTo(DemoEditFeed.keyOf(HomeSection.JumpBackIn), fx = 0.3f, fy = 0.55f)
        hold { editing = true }
        delay(700)
        // Pinch: the sections close into a list, then open back out.
        val centre = at(DemoEditFeed.FeedKey)
        pinch(centre.x, centre.y, fromGap = 0.34f, toGap = 0.12f, ms = 650) { p -> scope.launch { state.list.snapTo(p) } }
        delay(900)
        pinch(centre.x, centre.y, fromGap = 0.12f, toGap = 0.34f, ms = 650) { p -> scope.launch { state.list.snapTo(1f - p) } }
        delay(500)
        // Long-press drag: Your Playlists up above Recently Added.
        moveTo(DemoEditFeed.keyOf(HomeSection.YourPlaylists), fx = 0.5f, fy = 0.5f)
        hold { state.pick(scope, HomeSection.YourPlaylists) }
        val up = at(DemoEditFeed.keyOf(HomeSection.RecentlyAdded), fx = 0.5f, fy = 0.5f)
        drag(up.x, up.y, ms = 700) { p -> state.carry(scope, HomeSection.YourPlaylists, slot = 2f - p, left = 0f, width = 1f) }
        state.drop(scope, HomeSection.YourPlaylists, slot = 1f, left = 0f, width = 1f)
        delay(600)
        // Drop beside: Recently Added onto the right of Your Playlists, and the two share a row.
        moveTo(DemoEditFeed.keyOf(HomeSection.RecentlyAdded), fx = 0.5f, fy = 0.5f)
        hold { state.pick(scope, HomeSection.RecentlyAdded) }
        val beside = at(DemoEditFeed.keyOf(HomeSection.YourPlaylists), fx = 0.8f, fy = 0.5f)
        drag(beside.x, beside.y, ms = 800) { p ->
            state.carry(scope, HomeSection.RecentlyAdded, slot = 2f - p, left = 0.5f * p, width = 1f - 0.5f * p)
            if (p > 0.6f) state.makeRoom(scope, HomeSection.YourPlaylists)
        }
        state.drop(scope, HomeSection.RecentlyAdded, slot = 1f, left = 0.5f, width = 0.5f)
        lift()
        delay(1_600)
        moveTo("bar", fx = 0.75f)
        tap { editing = false }
        delay(900)
    }) {
        DemoHomePage(
            editProgress = { progress.value },
            editing = editing,
            onEnterEdit = { editing = true },
            onDone = { editing = false },
            cardScope = null,
            body = {
                DemoEditFeedBody(
                    state = state,
                    editProgress = { progress.value },
                    modifier = Modifier.demoLongPress { editing = true },
                )
            },
        )
    }
}

/**
 * The demo's three sections: each one's row ([slot], from the top), its left edge and its width
 * (fractions of the feed: 0 / 1 full, 0.5 / 0.5 the right half), how far it is lifted, and how far
 * the feed is pinched into a list ([list]). The carried one follows the finger; the rest spring.
 */
@Stable
private class DemoEditFeed {
    val list = Animatable(0f)
    val lift = Animatable(0f)
    var carried: HomeSection? by mutableStateOf(null)
        private set
    private val slots = Sections.associateWith { Animatable(Sections.indexOf(it).toFloat()) }
    private val lefts = Sections.associateWith { Animatable(0f) }
    private val widths = Sections.associateWith { Animatable(1f) }

    fun slot(section: HomeSection): Float = slots.getValue(section).value
    fun left(section: HomeSection): Float = lefts.getValue(section).value
    fun width(section: HomeSection): Float = widths.getValue(section).value

    suspend fun reset() {
        carried = null
        lift.snapTo(0f)
        list.snapTo(0f)
        Sections.forEachIndexed { i, section ->
            slots.getValue(section).snapTo(i.toFloat())
            lefts.getValue(section).snapTo(0f)
            widths.getValue(section).snapTo(1f)
        }
    }

    fun pick(scope: CoroutineScope, section: HomeSection) {
        carried = section
        scope.launch { lift.animateTo(1f, YoinMotion.effectsSpring()) }
    }

    /** The carried one under the finger. Carried straight up past the middle of the row above, that one moves down. */
    fun carry(scope: CoroutineScope, section: HomeSection, slot: Float, left: Float, width: Float) {
        scope.launch {
            slots.getValue(section).snapTo(slot)
            lefts.getValue(section).snapTo(left)
            widths.getValue(section).snapTo(width)
        }
        if (left == 0f && slot < 1.5f) {
            val above = Sections.firstOrNull { it != section && slots.getValue(it).targetValue == 1f }
            if (above != null) scope.launch { slots.getValue(above).animateTo(2f, Settle) }
        }
    }

    /** A pair forming: [section] narrows to the left half for the one coming beside it. */
    fun makeRoom(scope: CoroutineScope, section: HomeSection) {
        val w = widths.getValue(section)
        if (w.targetValue != 0.5f) scope.launch { w.animateTo(0.5f, Settle) }
    }

    suspend fun drop(scope: CoroutineScope, section: HomeSection, slot: Float, left: Float, width: Float) {
        coroutineScope {
            launch { slots.getValue(section).animateTo(slot, Settle) }
            launch { lefts.getValue(section).animateTo(left, Settle) }
            launch { widths.getValue(section).animateTo(width, Settle) }
            launch { lift.animateTo(0f, Settle) }
        }
        carried = null
    }

    companion object {
        val Sections = listOf(HomeSection.JumpBackIn, HomeSection.RecentlyAdded, HomeSection.YourPlaylists)
        const val FeedKey = "edit-feed"
        fun keyOf(section: HomeSection): String = "edit-${section.id}"
        private val Settle = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
    }
}

/** The sections as edit mode draws them: a plate (with edit mode), title, covers (gone in the list) and handle. */
@Composable
private fun DemoEditFeedBody(state: DemoEditFeed, editProgress: () -> Float, modifier: Modifier = Modifier) {
    val covers = demoCoverUrls()
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(DemoFeedRow * DemoEditFeed.Sections.size + DemoFeedGap * (DemoEditFeed.Sections.size - 1))
            .demoTarget(DemoEditFeed.FeedKey),
    ) {
        DemoEditFeed.Sections.forEachIndexed { index, section ->
            val title = stringResource(section.titleRes)
            Box(
                modifier = Modifier
                    .zIndex(if (state.carried == section) 1f else 0f)
                    .layout { measurable, constraints ->
                        val list = state.list.value
                        val gap = lerp(DemoFeedGap, DemoListGap, list).roundToPx()
                        val rowH = lerp(DemoFeedRow, DemoListRow, list).roundToPx()
                        val colGap = DemoFeedGap.roundToPx()
                        val full = constraints.maxWidth
                        val w = (state.width(section) * (full + colGap) - colGap).roundToInt().coerceAtLeast(1)
                        val x = (state.left(section) * (full + colGap)).roundToInt()
                        val y = (state.slot(section) * (rowH + gap)).roundToInt()
                        val placeable = measurable.measure(Constraints.fixed(w, rowH))
                        layout(full, rowH) { placeable.place(x, y) }
                    }
                    .graphicsLayer {
                        val lift = if (state.carried == section) state.lift.value else 0f
                        val scale = 1f + 0.03f * lift
                        scaleX = scale
                        scaleY = scale
                        shadowElevation = with(density) { 12.dp.toPx() } * lift
                        shape = DemoPlateShape
                        clip = true
                    }
                    .drawBehind { drawRect(colors.surfaceContainerHigh, alpha = editProgress().coerceIn(0f, 1f)) }
                    .demoTarget(DemoEditFeed.keyOf(section)),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = YoinSymbols.DragHandle,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.graphicsLayer { alpha = editProgress().coerceIn(0f, 1f) },
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.graphicsLayer { alpha = 1f - state.list.value.coerceIn(0f, 1f) },
                    ) {
                        repeat(DemoCoversPerRow) { i ->
                            ExpressiveMediaArtwork(
                                model = covers.getOrNull((index * 3 + i) % covers.size.coerceAtLeast(1)),
                                contentDescription = null,
                                modifier = Modifier.size(DemoCover),
                                shape = YoinArtworkShapes.Thumb,
                                fallbackIcon = YoinSymbols.Album,
                                tonalElevation = 0.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun lerp(start: Dp, stop: Dp, fraction: Float): Dp = start + (stop - start) * fraction.coerceIn(0f, 1f)

private val DemoPlateShape = RoundedCornerShape(20.dp)
private val DemoFeedRow = 116.dp
private val DemoListRow = 48.dp
private val DemoFeedGap = 16.dp
private val DemoListGap = 8.dp
private val DemoCover = 60.dp
private const val DemoCoversPerRow = 10

/** Pull Home down → Memories comes down over it; its Home pill sends it back up. */
@Composable
internal fun PullDemo(playing: Boolean) {
    // 0 = Home, 1 = Memories open: the one value the pull, the release and the pill drive.
    val reveal = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val spatial = YoinMotion.spatialSpring<Float>()
    val heightPx = with(LocalDensity.current) { DemoScreenHeight.toPx() }
    val open: () -> Unit = { scope.launch { reveal.animateTo(1f, spatial) } }
    val close: () -> Unit = { scope.launch { reveal.animateTo(0f, spatial) } }
    DemoPhone(playing = playing, script = {
        reveal.snapTo(0f)
        moveTo("header", fy = 1.2f)
        drag(at("header", fy = 1.2f).x, 0.62f, ms = 750) { p -> scope.launch { reveal.snapTo(p * PullTravel) } }
        reveal.animateTo(1f, spatial)
        lift()
        delay(1_800)
        moveTo("memoriesHome", fx = 0.16f)
        tap { close() }
        delay(900)
    }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        scope.launch { reveal.snapTo((reveal.value + delta / heightPx).coerceIn(0f, 1f)) }
                    },
                    onDragStopped = { if (reveal.value > OpenAt) open() else close() },
                ),
        ) {
            DemoHomePage(
                editProgress = { 0f },
                editing = false,
                onEnterEdit = {},
                onDone = {},
                cardScope = null,
                hint = { (reveal.value / OpenAt).coerceIn(0f, 1f) },
            )
            DemoMemoriesPage(
                onHome = close,
                modifier = Modifier.graphicsLayer {
                    translationY = -size.height * (1f - reveal.value)
                },
            )
        }
    }
}

/**
 * Hold the header's avatar: the other accounts drop out of it in a column;
 * slide down onto one and let go, and it is the account in use (Home's
 * HomeAccountButton and its fan, the real ones, drawn in place).
 */
@Composable
internal fun AccountQuickSwitchDemo(playing: Boolean) {
    val accounts = DemoAccounts
    var activeId by remember { mutableStateOf(accounts[0].id) }
    val fan = remember { HomeAccountFanState() }
    val avatarPx = with(LocalDensity.current) { DemoAccountBox.roundToPx() }
    val others = remember(activeId) { accounts.filter { it.id != activeId } }
    val quickSwitch = remember(others) { HomeAccountQuickSwitch(others) { activeId = it.id } }
    DemoPhone(playing = playing, script = {
        activeId = accounts[0].id
        fan.holding = false
        fan.hover = -1
        delay(400)
        moveTo("account")
        // Held for the long-press time (the ring fills), then the column drops out.
        press.animateTo(1f, tween(90))
        ring.snapTo(0f)
        ring.animateTo(1f, tween(HoldMs, easing = LinearEasing))
        ring.snapTo(0f)
        fan.anchorWidth = avatarPx
        fan.anchorHeight = avatarPx
        fan.accounts = accounts.drop(1)
        fan.holding = true
        delay(450)
        // Down the column, onto the first account, then the second.
        repeat(2) { i ->
            moveTo("account", fy = DemoFanItemFy[i])
            fan.hover = i
            delay(380)
        }
        // Let go: the second one is the account in use.
        activeId = accounts[2].id
        fan.holding = false
        fan.hover = -1
        press.animateTo(0f, tween(120))
        lift()
        delay(1_800)
    }) {
        DemoHomePage(
            editProgress = { 0f },
            editing = false,
            onEnterEdit = {},
            onDone = {},
            cardScope = null,
            headerAction = {
                HomeAccountButton(
                    card = accounts.first { it.id == activeId },
                    onClick = {},
                    quickSwitch = quickSwitch,
                    fanState = fan,
                    inlineFan = true,
                    modifier = Modifier.demoTarget("account"),
                )
            },
        )
    }
}

/**
 * Opened in a non-Spotify account while the signed-in Spotify one plays: the
 * header's pill (Home's own HomeSpotifyElsewherePill) shows the song beside
 * the avatar; the finger taps it and Spotify's account is the one in use.
 */
@Composable
internal fun SpotifyElsewhereDemo(playing: Boolean) {
    val accounts = DemoAccounts
    val spotify = accounts.first { it.provider == ProviderKind.SPOTIFY }
    var activeId by remember { mutableStateOf(accounts[0].id) }
    var shown by remember { mutableStateOf(false) }
    val covers = demoCoverUrls()
    val elsewhere = remember(covers, spotify) {
        val album = DemoAlbums[3]
        SpotifyElsewhere(
            profileId = spotify.id,
            trackTitle = DemoTrackTitles[0],
            artistName = album.artist,
            coverUrl = covers.getOrNull(3),
            isPlaying = true,
        )
    }
    DemoPhone(playing = playing, script = {
        activeId = accounts[0].id
        shown = false
        delay(700)
        // The app comes to the front: Spotify's song shows up top.
        shown = true
        delay(1_600)
        moveTo("spotify-pill")
        tap {
            shown = false
            activeId = spotify.id
        }
        lift()
        delay(2_000)
    }) {
        DemoHomePage(
            editProgress = { 0f },
            editing = false,
            onEnterEdit = {},
            onDone = {},
            cardScope = null,
            headerAction = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HomeSpotifyElsewherePill(
                        elsewhere = elsewhere,
                        visible = shown,
                        onClick = {
                            shown = false
                            activeId = spotify.id
                        },
                        modifier = Modifier.demoTarget("spotify-pill"),
                    )
                    HomeAccountButton(
                        card = accounts.first { it.id == activeId },
                        onClick = {},
                    )
                }
            },
        )
    }
}

/**
 * 「歌词一句」: the real Home shelf under the header. The finger swipes on to the next song's line
 * and back, then taps a card to play from it; the lines here are made up for the demo (a real card
 * shows the song's own).
 */
@Composable
internal fun LyricHighlightDemo(playing: Boolean) {
    val covers = demoCoverUrls()
    val cards = remember(covers) {
        DemoLyricLines.mapIndexed { i, (text, kind) ->
            val album = DemoAlbums[i]
            HomeLyricHighlight(
                songId = MediaId.subsonic("tip-lyric-$i"),
                title = DemoTrackTitles[i],
                artist = album.artist,
                album = album.name,
                albumId = null,
                coverKey = covers[i],
                durationMs = 200_000L,
                text = text,
                startMs = null,
                kind = kind,
            )
        }
    }
    val pagerState = rememberPagerState(pageCount = { 3 })
    DemoPhone(playing = playing, script = {
        pagerState.scrollToPage(0)
        delay(900)
        for (target in listOf(1, 2, 1)) {
            moveTo("lyric", fx = 0.8f, fy = 0.6f)
            delay(200)
            coroutineScope {
                launch { pagerState.animateScrollToPage(target) }
                drag(fx = at("lyric", fx = 0.3f, fy = 0.6f).x, fy = at("lyric", fy = 0.6f).y, ms = 420) {}
            }
            delay(900)
        }
        moveTo("lyric", fx = 0.4f, fy = 0.5f)
        delay(250)
        tap {}
        delay(900)
        lift()
        delay(600)
    }) {
        DemoHomePage(
            editProgress = { 0f },
            editing = false,
            onEnterEdit = {},
            onDone = {},
            cardScope = null,
            body = {
                LyricHighlightSection(
                    cards = cards,
                    count = 3,
                    frame = null,
                    buildCoverArtUrl = { it },
                    extractBackdropColors = true,
                    onPlay = {},
                    shelfScrollEnabled = false,
                    pagerState = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .demoTarget("lyric"),
                )
            },
        )
    }
}

private val DemoLyricLines = listOf(
    "We left the porch light on all winter,\nso the summer would know the way" to LyricHighlightSelector.Bucket.HEAVY,
    "Some songs wait in a drawer for years,\nthen fit you like a coat" to LyricHighlightSelector.Bucket.REDISCOVER,
    "I hum the parts I don't know yet" to LyricHighlightSelector.Bucket.USER,
)

// The header avatar's box (HomeAccountButton), and where the fan's items sit
// under it, as fractions of that box (HomeAccountQuickSwitch's 12dp gap, 48dp items, 60dp steps).
private val DemoAccountBox = 48.dp
private val DemoFanItemFy = listOf(1f + 36f / 48f, 1f + 96f / 48f)

@Composable
private fun BoxScope.DemoHomePage(
    editProgress: () -> Float,
    editing: Boolean,
    onEnterEdit: () -> Unit,
    onDone: () -> Unit,
    cardScope: HomeEditCardScope?,
    hint: () -> Float = { 0f },
    // In place of the Settings gear (the account in use, on Home).
    headerAction: (@Composable () -> Unit)? = null,
    // In place of Jump Back In (another section's scene).
    body: (@Composable () -> Unit)? = null,
) {
    val cards = demoWidgetCards()
    val bubble = remember { MemoryBubbleController() }
    ExpressivePageBackground(modifier = Modifier.matchParentSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // HomeContentHeader's row, with the Settings gear in the corner.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .memoryBubblePage(bubble)
                    .statusBarsPadding()
                    .padding(top = 12.dp)
                    .heightIn(min = 48.dp)
                    // Over the page under it: the account fan drops out of its corner.
                    .zIndex(1f)
                    .demoTarget("header"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeGreetingTitle(
                    editProgress = editProgress,
                    onEnterEdit = onEnterEdit,
                    modifier = Modifier
                        .memoryBubbleTitle(bubble)
                        .padding(end = HomeHeaderTitleBreathing),
                )
                Box(modifier = Modifier.weight(1f).memoryBubbleFreeSpan(bubble))
                Spacer(modifier = Modifier.width(2.dp))
                if (headerAction != null) {
                    headerAction()
                } else {
                    IconButton(onClick = {}, modifier = Modifier.homeEditHeaderIcon(editProgress).handPointer()) {
                        Icon(
                            imageVector = YoinSymbols.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (body != null) {
                body()
            } else CompositionLocalProvider(LocalHomeEditCardScope provides cardScope) {
                HomeWidgetGridSection(
                    title = stringResource(HomeSection.JumpBackIn.titleRes),
                    cards = cards,
                    extractBackdropColors = true,
                    onCardClick = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .demoTarget("jbi")
                        .demoLongPress { if (cardScope != null) onEnterEdit() },
                )
            }
        }
        MemoryBubbleOverlay(
            pill = null,
            controller = bubble,
            hintProgress = hint,
            revealProgress = { 1f },
            scrolledPx = { 0f },
            // Quiet: the arrow only, no speech.
            covered = true,
            extractBackdropColors = false,
            onOpenMemoryFocus = {},
            onNavigateToMemories = {},
            modifier = Modifier.matchParentSize(),
            editProgress = editProgress,
            editing = editing,
        )
        YoinButtonGroup(
            selectedSection = YoinSection.HOME,
            currentTrackId = null,
            currentTrackTitle = null,
            currentTrackArtist = null,
            currentTrackCoverArtUrl = null,
            isPlaybackReady = false,
            connectionErrorMessage = null,
            onHomeClick = {},
            onNowPlayingClick = {},
            onLibraryClick = {},
            editPose = remember(editProgress, onDone) {
                BarEditPose(
                    progress = editProgress,
                    leftSlot = { BarEditLeftSlot.UndoDisabled },
                    onLeftSlotClick = {},
                    onDone = onDone,
                )
            },
            editing = editing,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .demoTarget("bar"),
        )
    }
}

/**
 * The Memories deck's first card on its ambient wash, under the deck's top bar. [owner]: the card is another
 * account's, so it says whose beside Go to album (and drops its quote, to leave the small phone room).
 */
@Composable
private fun DemoMemoriesPage(
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
    owner: MemoryOtherProfile? = null,
) {
    val dark = isSystemInDarkTheme()
    val palette = if (owner == null) MemoryPaletteSamples.M1 else MemoryPaletteSamples.M3
    val album = DemoAlbums[if (owner == null) 3 else 0]
    val cover = demoCoverUrl(album)
    val memory = remember(cover, owner) {
        MemoryEntry(
            stableId = "tip-memory",
            sourceActivityId = 0L,
            entityType = MemoryEntityType.ALBUM,
            entityId = "tip-album",
            entityProvider = MediaId.PROVIDER_SUBSONIC,
            title = album.name,
            supportingText = album.artist,
            supportingYear = "2024",
            metaText = null,
            coverArtUrl = cover,
            timestamp = 0L,
            scoreText = "8.6",
            scoreKind = MemoryScoreKind.ALBUM_RATING,
            scoreSupportingText = null,
            footerText = null,
            memoryTitle = "Night walks in the rain",
            excerptCandidates = if (owner != null) {
                emptyList()
            } else {
                listOf(
                    MemoryExcerptCandidate(
                        "The second side is the one I keep coming back to.",
                        MemoryExcerptAttribution(MemoryExcerptKind.REVIEW, "Oct 12"),
                    ),
                )
            },
            playbackSongs = emptyList(),
            tracks = emptyList(),
            otherProfile = owner,
        )
    }
    val metrics = remember { memoryCardMetrics(ScreenWidth, DemoScreenHeight) }
    val awards = remember { MemoriesAwardLifecycle() }
    val tilt = rememberGrooveTilt(active = false, reducedMotion = true)
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .memoriesAuroraBackground(baseColor = palette.base, accentColor = palette.accent, visible = true),
    ) {
        MemoryCardFace(
            memory = memory,
            tones = palette.tones(dark = dark),
            metrics = metrics,
            onOpenDiary = {},
            onOpenAlbum = {},
            cover = { m ->
                ExpressiveMediaArtwork(
                    model = cover,
                    contentDescription = null,
                    modifier = m,
                    shape = YoinArtworkShapes.Hero,
                    fallbackIcon = YoinSymbols.Album,
                    tonalElevation = 0.dp,
                )
            },
            emblem = { m ->
                CardEmblem(
                    memory = memory,
                    palette = palette,
                    size = metrics.seal,
                    awards = awards,
                    tilt = tilt,
                    isCurrent = false,
                    reducedMotion = true,
                    captionAlpha = { 1f },
                    modifier = m,
                )
            },
            interactive = false,
            marqueeRunning = false,
            modifier = Modifier.padding(top = statusTop + MemoriesTopBarTokens.Height),
        )
        MemoriesTopBar(
            dotColors = listOf(palette.accent, MemoryPaletteSamples.M3.accent, MemoryPaletteSamples.M5.accent),
            dotLabels = listOf(album.name, DemoAlbums[0].name, DemoAlbums[2].name),
            position = { 0f },
            diaryProgress = { 0f },
            onHome = onHome,
            onDot = {},
            currentDot = 0,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .demoTarget("memoriesHome"),
        )
    }
}

/**
 * Memories deals every account's memories: swipe to the next card, and one from another account says whose it
 * is beside Go to album (the real card face and its「来自 …」line).
 */
@Composable
internal fun MemoriesAllAccountsDemo(playing: Boolean) {
    // 0 = this account's card, 1 = the next one, from another account.
    val shift = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val spatial = YoinMotion.spatialSpring<Float>()
    val widthPx = with(LocalDensity.current) { ScreenWidth.toPx() }
    val settle: () -> Unit = { scope.launch { shift.animateTo(if (shift.value > 0.5f) 1f else 0f, spatial) } }
    DemoPhone(playing = playing, script = {
        shift.snapTo(0f)
        delay(900)
        moveTo(0.8f, 0.45f)
        drag(0.25f, 0.45f, ms = 600) { p -> scope.launch { shift.snapTo(p * SwipeTravel) } }
        shift.animateTo(1f, spatial)
        lift()
        delay(2_800)
    }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { shift.snapTo((shift.value - delta / widthPx).coerceIn(0f, 1f)) }
                    },
                    onDragStopped = { settle() },
                ),
        ) {
            DemoMemoriesPage(
                onHome = {},
                modifier = Modifier.graphicsLayer { translationX = -size.width * shift.value },
            )
            DemoMemoriesPage(
                onHome = {},
                owner = DemoOtherAccount,
                modifier = Modifier.graphicsLayer { translationX = size.width * (1f - shift.value) },
            )
        }
    }
}

/** The account the demo's second memory is from (one of the switcher demo's accounts). */
private val DemoOtherAccount = MemoryOtherProfile(id = "demo-b", name = "Okto", shape = 1, kind = ProviderKind.SPOTIFY)

/** How far the demo's swipe carries the deck before it lets go (the rest is the settle). */
private const val SwipeTravel = 0.6f

/** How far the demo's pull takes Memories before it lets go (the rest is the settle). */
private const val PullTravel = 0.45f

/** A user's pull past this opens Memories; short of it, Home springs back. */
private const val OpenAt = 0.25f


private val DemoScreenHeight = ScreenWidth * (300f / 168f)
