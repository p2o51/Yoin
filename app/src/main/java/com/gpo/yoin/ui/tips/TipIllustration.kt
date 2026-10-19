package com.gpo.yoin.ui.tips

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.player.PlayMode
import com.gpo.yoin.ui.home.HomeBubbleTip
import com.gpo.yoin.ui.nowplaying.PlayModeButton
import com.gpo.yoin.ui.nowplaying.PlayModeSplitHoldMs
import com.gpo.yoin.ui.nowplaying.playModeNaturalWidth
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * How a tip is done, as a small live phone made of real components (owner
 * 2026-10-19: native UI the user can press too, with nothing behind it). A
 * touch dot acts the gesture out on a loop — tap (a ripple), hold (its ring
 * fills), drag — and the phone answers the way the app does. Once the user
 * touches the phone the demo steps aside and the phone is theirs; untouched
 * for [TakeoverIdleMs], the demo starts over. It runs only while [playing]
 * (its page is the settled one); reduced motion keeps the phone still but
 * still pressable. Decorative for accessibility: the text says it.
 */
@Composable
internal fun TipIllustration(
    tip: HomeBubbleTip,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(28.dp))
            .padding(vertical = 16.dp)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        when (tip) {
            HomeBubbleTip.EditHome -> EditHomeDemo(playing)
            HomeBubbleTip.PullForMemories -> PullDemo(playing)
            HomeBubbleTip.CoverMenu -> CoverMenuDemo(playing, keepScreenOn = false)
            HomeBubbleTip.SongMenu -> SongMenuDemo(playing)
            HomeBubbleTip.PlayModes -> PlayModesDemo(playing)
            HomeBubbleTip.NoteMoments -> NoteMomentsDemo(playing)
            HomeBubbleTip.LibrarySearch -> LibrarySearchDemo(playing)
            HomeBubbleTip.KeepScreenOn -> CoverMenuDemo(playing, keepScreenOn = true)
            HomeBubbleTip.ScrollEdge -> ScrollEdgeDemo(playing)
            HomeBubbleTip.Name -> NameDemo(playing)
        }
    }
}

// ── The phone and its demo touch ─────────────────────────────────────────

/** The demo's touch: where it is (fractions of the phone), whether it is down, its hold ring. */
private class DemoFinger(private val spring: FiniteAnimationSpec<Float>) {
    val x = Animatable(0.5f)
    val y = Animatable(0.5f)
    val alpha = Animatable(0f)
    val press = Animatable(0f)
    val ring = Animatable(0f)
    val ripple = Animatable(1f)

    suspend fun moveTo(fx: Float, fy: Float) {
        if (alpha.value < 0.05f) {
            x.snapTo(fx)
            y.snapTo(fy)
            alpha.animateTo(1f, tween(180))
        } else {
            coroutineScope {
                launch { x.animateTo(fx, spring) }
                y.animateTo(fy, spring)
            }
        }
    }

    suspend fun tap(action: () -> Unit) {
        press.animateTo(1f, tween(70))
        action()
        ripple.snapTo(0f)
        coroutineScope {
            launch { ripple.animateTo(1f, tween(420)) }
            press.animateTo(0f, spring)
        }
    }

    suspend fun hold(action: () -> Unit) {
        press.animateTo(1f, tween(90))
        ring.snapTo(0f)
        // The long-press timeout, shown as the ring filling: a clock, so linear.
        ring.animateTo(1f, tween(HoldMs, easing = LinearEasing))
        action()
        ring.snapTo(0f)
        press.animateTo(0f, spring)
    }

    /** Down where it is, slide to ([fx], [fy]) over [ms]; [onProgress] gets 0..1 every frame. */
    suspend fun drag(fx: Float, fy: Float, ms: Int, onProgress: (Float) -> Unit) {
        press.animateTo(1f, tween(80))
        val progress = Animatable(0f)
        coroutineScope {
            launch { x.animateTo(fx, tween(ms)) }
            launch { y.animateTo(fy, tween(ms)) }
            progress.animateTo(1f, tween(ms)) { onProgress(value) }
        }
        press.animateTo(0f, spring)
    }

    suspend fun lift() {
        alpha.animateTo(0f, tween(220))
    }

    suspend fun hide() {
        alpha.snapTo(0f)
        press.snapTo(0f)
        ring.snapTo(0f)
        ripple.snapTo(1f)
    }
}

/**
 * A 168×300dp phone that plays [script] on a loop while [playing] and the
 * user isn't touching it. [script] puts the scene back at rest first.
 */
@Composable
private fun DemoPhone(
    playing: Boolean,
    script: suspend DemoFinger.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    val density = LocalDensity.current
    val fingerSpring = YoinMotion.spatialSpring<Float>()
    val finger = remember { DemoFinger(fingerSpring) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    var userHas by remember { mutableStateOf(false) }
    LaunchedEffect(playing, userHas, reduced) {
        if (!playing || userHas || reduced) {
            finger.hide()
            return@LaunchedEffect
        }
        delay(StartDelayMs)
        while (true) {
            finger.script()
            finger.lift()
            delay(LoopGapMs)
        }
    }
    LaunchedEffect(lastTouch) {
        if (lastTouch == 0L) return@LaunchedEffect
        delay(TakeoverIdleMs)
        userHas = false
    }
    Box(
        modifier = Modifier
            .size(PhoneWidth, PhoneHeight)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surface)
            .border(1.5.dp, colors.outlineVariant, RoundedCornerShape(24.dp))
            // Watch (never consume) the user's touches: the phone becomes theirs.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    userHas = true
                    lastTouch = System.nanoTime()
                }
            },
    ) {
        content()
        Canvas(modifier = Modifier.fillMaxSize()) {
            val a = finger.alpha.value
            if (a <= 0f) return@Canvas
            val c = Offset(size.width * finger.x.value, size.height * finger.y.value)
            val r = with(density) { 14.dp.toPx() }
            val down = finger.press.value
            val primary = colors.primary
            drawCircle(primary.copy(alpha = 0.22f * a), r * (1f - 0.15f * down), c)
            drawCircle(primary.copy(alpha = 0.9f * a), r * 0.42f * (1f - 0.15f * down), c)
            val ripple = finger.ripple.value
            if (ripple < 1f) {
                drawCircle(
                    primary.copy(alpha = (1f - ripple) * 0.5f * a),
                    r * (1f + ripple * 1.4f),
                    c,
                    style = Stroke(width = with(density) { 2.dp.toPx() }),
                )
            }
            val ring = finger.ring.value
            if (ring > 0f) {
                drawArc(
                    primary.copy(alpha = a),
                    startAngle = -90f,
                    sweepAngle = 360f * ring,
                    useCenter = false,
                    topLeft = Offset(c.x - r * 1.3f, c.y - r * 1.3f),
                    size = Size(r * 2.6f, r * 2.6f),
                    style = Stroke(width = with(density) { 2.5.dp.toPx() }, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/** A plain block standing in for content. */
@Composable
private fun Block(width: Dp, height: Dp, modifier: Modifier = Modifier, strong: Boolean = false) {
    Box(
        modifier = modifier
            .size(width, height)
            .background(
                if (strong) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(10.dp),
            ),
    )
}

@Composable
private fun MiniHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(start = 12.dp, top = 14.dp, bottom = 8.dp),
    )
}

/** A bottom sheet rising over the phone, with a scrim that closes it. */
@Composable
private fun BoxScope.MiniSheet(open: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = open, enter = YoinMotion.fadeIn(YoinMotionRole.Expressive), exit = YoinMotion.fadeOut(YoinMotionRole.Expressive), modifier = Modifier.matchParentSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible = open,
        enter = YoinMotion.slideInVertically(YoinMotionRole.Expressive) { it },
        exit = YoinMotion.slideOutVertically(YoinMotionRole.Expressive) { it },
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(28.dp, 3.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
                Spacer(modifier = Modifier.height(6.dp))
                content()
            }
        }
    }
}

@Composable
private fun SheetRow(label: String, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke()
    }
}

// ── Scenes ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditHomeDemo(playing: Boolean) {
    var editing by remember { mutableStateOf(false) }
    val wiggle = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(editing) {
        if (!editing) {
            wiggle.floatValue = 0f
            return@LaunchedEffect
        }
        val start = withFrameNanos { it }
        while (true) withFrameNanos { wiggle.floatValue = sin((it - start) / 1e9f * 2f * PI.toFloat() * 3f) * 3f }
    }
    DemoPhone(playing = playing, script = {
        editing = false
        moveTo(0.27f, 0.24f)
        hold { editing = true }
        lift()
        delay(1_600)
        moveTo(0.84f, 0.08f)
        tap { editing = false }
    }) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniHeader(stringResource(R.string.home_title), Modifier.weight(1f))
                AnimatedVisibility(visible = editing, enter = YoinMotion.fadeIn(YoinMotionRole.Expressive) + YoinMotion.scaleIn(YoinMotionRole.Expressive, initialScale = 0.8f), exit = YoinMotion.fadeOut(YoinMotionRole.Expressive) + YoinMotion.scaleOut(YoinMotionRole.Expressive, targetScale = 0.8f)) {
                    TextButton(onClick = { editing = false }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(stringResource(R.string.cmp_bar_label_done), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Column(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                repeat(3) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(2) { col ->
                            val sign = if ((row + col) % 2 == 0) 1f else -1f
                            Box(
                                modifier = Modifier
                                    .graphicsLayer { rotationZ = wiggle.floatValue * sign }
                                    .clip(RoundedCornerShape(10.dp))
                                    .combinedClickable(onClick = {}, onLongClick = { editing = true }),
                            ) {
                                Block(67.dp, 62.dp, strong = row == 0 && col == 0)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PullDemo(playing: Boolean) {
    val pull = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val maxPull = with(LocalDensity.current) { 96.dp.toPx() }
    val spring = YoinMotion.spatialSpring<Float>()
    DemoPhone(playing = playing, script = {
        pull.snapTo(0f)
        moveTo(0.5f, 0.32f)
        drag(0.5f, 0.62f, ms = 700) { p -> scope.launch { pull.snapTo(p * maxPull) } }
        delay(700)
        coroutineScope {
            launch { lift() }
            pull.animateTo(0f, spring)
        }
    }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        scope.launch { pull.snapTo((pull.value + delta * 0.6f).coerceIn(0f, maxPull)) }
                    },
                    onDragStopped = { pull.animateTo(0f, spring) },
                ),
        ) {
            // What the pull reveals: Memories, growing in above the page.
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .graphicsLayer {
                        val p = (pull.value / maxPull).coerceIn(0f, 1f)
                        alpha = p
                        scaleX = 0.6f + 0.4f * p
                        scaleY = 0.6f + 0.4f * p
                    },
            ) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(YoinSymbols.Memory, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.home_memories_label), style = MaterialTheme.typography.labelMedium)
                }
            }
            Column(modifier = Modifier.graphicsLayer { translationY = pull.value }) {
                MiniHeader(stringResource(R.string.home_title))
                Column(modifier = Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(4) { Block(144.dp, 48.dp, strong = it == 0) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverMenuDemo(playing: Boolean, keepScreenOn: Boolean) {
    var open by remember { mutableStateOf(false) }
    var awake by remember { mutableStateOf(false) }
    DemoPhone(playing = playing, script = {
        open = false
        awake = false
        moveTo(0.5f, 0.3f)
        hold { open = true }
        if (keepScreenOn) {
            moveTo(0.82f, 0.9f)
            tap { awake = true }
            delay(900)
        } else {
            lift()
            delay(1_400)
        }
        moveTo(0.5f, 0.2f)
        tap { open = false }
    }) {
        Column(modifier = Modifier.padding(horizontal = 14.dp)) {
            Spacer(modifier = Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .combinedClickable(onClick = {}, onLongClick = { open = true }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    YoinSymbols.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Block(90.dp, 10.dp)
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(YoinSymbols.Queue, YoinSymbols.Devices, YoinSymbols.EditNote).forEach { icon ->
                    FilledIconButton(onClick = {}, modifier = Modifier.size(34.dp)) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        MiniSheet(open = open, onDismiss = { open = false }) {
            SheetRow(stringResource(R.string.detail_menu_play_next), onClick = { open = false })
            SheetRow(stringResource(R.string.detail_menu_add_to_queue), onClick = { open = false })
            SheetRow(stringResource(R.string.np_keep_screen_on), onClick = { awake = !awake }) {
                Switch(
                    checked = awake,
                    onCheckedChange = { awake = it },
                    modifier = Modifier.graphicsLayer {
                        scaleX = 0.7f
                        scaleY = 0.7f
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongMenuDemo(playing: Boolean) {
    var open by remember { mutableStateOf(false) }
    var pressedRow by remember { mutableIntStateOf(-1) }
    DemoPhone(playing = playing, script = {
        open = false
        moveTo(0.6f, 0.58f)
        pressedRow = 2
        hold { open = true }
        pressedRow = -1
        lift()
        delay(1_400)
        moveTo(0.5f, 0.2f)
        tap { open = false }
    }) {
        Column {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Block(56.dp, 56.dp, strong = true)
                Spacer(modifier = Modifier.width(10.dp))
                Block(60.dp, 10.dp)
            }
            Column(modifier = Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(6) { i ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (pressedRow == i) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            )
                            .combinedClickable(onClick = {}, onLongClick = { open = true })
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(10.dp))
                        Block(70.dp, 8.dp)
                    }
                }
            }
        }
        MiniSheet(open = open, onDismiss = { open = false }) {
            SheetRow(stringResource(R.string.detail_menu_play_next), onClick = { open = false })
            SheetRow(stringResource(R.string.detail_menu_add_to_queue), onClick = { open = false })
        }
    }
}

/** The real play-mode button (PlaybackControls.PlayModeButton), on a phone-sized stage. */
@Composable
private fun PlayModesDemo(playing: Boolean) {
    var mode by remember { mutableStateOf(PlayMode.RepeatAll) }
    val split = remember { mutableStateOf(false) }
    // 56dp control: split, the segment centres sit ±(56·1.6 + 2)/4 from the middle.
    DemoPhone(playing = playing, script = {
        split.value = false
        mode = PlayMode.RepeatAll
        moveTo(0.5f, 0.65f)
        tap { mode = mode.next() }
        delay(600)
        tap { mode = mode.next() }
        delay(600)
        hold { split.value = true }
        moveTo(0.36f, 0.65f)
        tap { mode = mode.withShuffle(!mode.shuffle) }
        delay(500)
        moveTo(0.64f, 0.65f)
        tap { mode = mode.withRepeatOne(!mode.repeatOne) }
        lift()
        delay(PlayModeSplitHoldMs + 600)
    }) {
        Column(modifier = Modifier.padding(horizontal = 14.dp)) {
            Spacer(modifier = Modifier.height(18.dp))
            Block(140.dp, 120.dp, strong = true)
            Spacer(modifier = Modifier.height(30.dp))
            Box(modifier = Modifier.fillMaxWidth().height(DemoControlSize), contentAlignment = Alignment.Center) {
                PlayModeButton(
                    playMode = mode,
                    onSetPlayMode = { mode = it },
                    height = DemoControlSize,
                    splitWidth = playModeNaturalWidth(DemoControlSize),
                    iconSize = 24.dp,
                    splitState = split,
                )
            }
        }
    }
}

private val DemoControlSize = 56.dp

@Composable
private fun NoteMomentsDemo(playing: Boolean) {
    val position = remember { mutableFloatStateOf(0.1f) }
    val pins = remember { mutableStateListOf<Float>() }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                position.floatValue = (position.floatValue + (now - last) / 1e9f / 12f) % 1f
                last = now
            }
        }
    }
    DemoPhone(playing = playing, script = {
        pins.clear()
        delay(900)
        moveTo(0.71f, 0.85f)
        tap { pins += position.floatValue }
        lift()
        delay(2_200)
    }) {
        Column(modifier = Modifier.padding(horizontal = 14.dp)) {
            Spacer(modifier = Modifier.height(18.dp))
            Block(140.dp, 140.dp, strong = true)
            Spacer(modifier = Modifier.height(12.dp))
            Block(90.dp, 10.dp)
            Spacer(modifier = Modifier.height(26.dp))
            val primary = MaterialTheme.colorScheme.primary
            val track = MaterialTheme.colorScheme.outlineVariant
            val pin = MaterialTheme.colorScheme.tertiary
            Canvas(modifier = Modifier.fillMaxWidth().height(20.dp)) {
                val y = size.height * 0.75f
                val stroke = 3.dp.toPx()
                drawLine(track, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
                drawLine(primary, Offset(0f, y), Offset(size.width * position.floatValue, y), stroke, StrokeCap.Round)
                pins.forEach { p ->
                    val x = size.width * p
                    drawLine(pin, Offset(x, y), Offset(x, y - 10.dp.toPx()), 2.dp.toPx())
                    drawCircle(pin, 4.dp.toPx(), Offset(x, y - 12.dp.toPx()))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(
                    onClick = { pins += position.floatValue },
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Icon(YoinSymbols.EditNote, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.np_pill_write), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibrarySearchDemo(playing: Boolean) {
    var searching by remember { mutableStateOf(false) }
    DemoPhone(playing = playing, script = {
        searching = false
        moveTo(0.72f, 0.93f)
        hold { searching = true }
        lift()
        delay(1_500)
        moveTo(0.5f, 0.4f)
        tap { searching = false }
    }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { searching = false },
        ) {
            MiniHeader(stringResource(R.string.cmp_bar_label_library))
            Column(modifier = Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(3) { Block(144.dp, 52.dp) }
            }
        }
        Column(modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)) {
            AnimatedVisibility(
                visible = searching,
                enter = YoinMotion.fadeIn(YoinMotionRole.Expressive) + YoinMotion.slideInVertically(YoinMotionRole.Expressive) { it / 2 },
                exit = YoinMotion.fadeOut(YoinMotionRole.Expressive) + YoinMotion.slideOutVertically(YoinMotionRole.Expressive) { it / 2 },
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                ) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(YoinSymbols.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.library_search_placeholder_library),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape, modifier = Modifier.weight(1f)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(YoinSymbols.HomeFilled, contentDescription = null, modifier = Modifier.padding(8.dp).size(16.dp))
                        }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = CircleShape,
                        modifier = Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .combinedClickable(onClick = {}, onLongClick = { searching = true }),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(YoinSymbols.Library, contentDescription = null, modifier = Modifier.padding(8.dp).size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScrollEdgeDemo(playing: Boolean) {
    val list = rememberLazyListState()
    DemoPhone(playing = playing, script = {
        list.scrollToItem(0)
        moveTo(0.5f, 0.78f)
        coroutineScope {
            launch { list.animateScrollBy(420f, tween(900)) }
            drag(0.5f, 0.4f, ms = 900) {}
        }
        lift()
        delay(900)
        list.animateScrollToItem(0)
    }) {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 52.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(12) { i -> Block(144.dp, 44.dp, strong = i == 1) }
        }
        // The fixed top bar; content sinks under its soft wave edge, which
        // drifts with the scroll (read in the draw phase).
        val surface = MaterialTheme.colorScheme.surface
        Canvas(modifier = Modifier.fillMaxWidth().height(56.dp)) {
            val bottom = size.height - 10.dp.toPx()
            val amp = 4.dp.toPx()
            val phase = (list.firstVisibleItemIndex * 54 + list.firstVisibleItemScrollOffset) / 40f
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, bottom)
                val steps = 24
                for (s in steps downTo 0) {
                    val fx = s / steps.toFloat()
                    lineTo(size.width * fx, bottom + amp * sin(fx * 4f * PI.toFloat() + phase))
                }
                close()
            }
            drawPath(path, surface)
        }
        MiniHeader(stringResource(R.string.cmp_bar_label_library))
    }
}

@Composable
private fun NameDemo(playing: Boolean) {
    // 余韻: each tap (the demo's, or the user's) sounds a note whose rings keep spreading.
    val notes = remember { mutableStateListOf<Long>() }
    val now = remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (true) {
            withFrameNanos { t ->
                now.longValue = t
                notes.removeAll { t - it > NoteLifeNs + 2 * NoteEchoNs }
            }
        }
    }
    DemoPhone(playing = playing, script = {
        moveTo(0.5f, 0.5f)
        tap { notes += now.longValue }
        delay(1_200)
        tap { notes += now.longValue }
        lift()
        delay(2_000)
    }) {
        val primary = MaterialTheme.colorScheme.primary
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { notes += now.longValue },
        ) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val max = size.height * 0.6f
            val t = now.longValue
            notes.forEach { start ->
                repeat(3) { k ->
                    val p = ((t - start - k * NoteEchoNs) / NoteLifeNs.toFloat()).coerceIn(0f, 1f)
                    if (p > 0f && p < 1f) {
                        drawCircle(
                            primary.copy(alpha = (1f - p) * 0.6f),
                            max * p,
                            c,
                            style = Stroke(width = (1f - p) * 4.dp.toPx() + 1.dp.toPx()),
                        )
                    }
                }
            }
            drawCircle(primary, 8.dp.toPx(), c)
        }
    }
}

private val PhoneWidth = 168.dp
private val PhoneHeight = 300.dp
private const val HoldMs = 550
private const val StartDelayMs = 500L
private const val LoopGapMs = 700L
private const val TakeoverIdleMs = 5_000L
private const val NoteLifeNs = 2_400_000_000L
private const val NoteEchoNs = 220_000_000L

@Preview(showBackground = true)
@Composable
private fun TipIllustrationPreview() {
    YoinTheme {
        TipIllustration(tip = HomeBubbleTip.CoverMenu, playing = false, modifier = Modifier.padding(16.dp))
    }
}
