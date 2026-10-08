package com.gpo.yoin.debug

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.ui.detail.AlbumNeoDbSync
import com.gpo.yoin.ui.experience.rememberRevealState
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryEntry
import com.gpo.yoin.ui.memories.MemoryScoreKind
import com.gpo.yoin.ui.memories.MemoryTrack
import com.gpo.yoin.ui.memories.MemoryWriting
import com.gpo.yoin.ui.memories.award.rememberMemoriesAwardLifecycle
import com.gpo.yoin.ui.memories.copy.MemoryExcerpt
import com.gpo.yoin.ui.memories.copy.MemoryTitleKind
import com.gpo.yoin.ui.memories.copy.MemoryVoice
import com.gpo.yoin.ui.memories.diaryAlbumNotes
import com.gpo.yoin.ui.memories.diaryTracks
import com.gpo.yoin.ui.memories.emblem.GrooveBeat
import com.gpo.yoin.ui.memories.emblem.GrooveHapticRoute
import com.gpo.yoin.ui.memories.emblem.GrooveHapticTrace
import com.gpo.yoin.ui.memories.emblem.GrooveHapticTraceEvent
import com.gpo.yoin.ui.memories.emblem.LocalHapticTrace
import com.gpo.yoin.ui.memories.emblem.rememberGrooveReducedMotion
import com.gpo.yoin.ui.memories.formatScore
import com.gpo.yoin.ui.memories.memoryCopyInput
import com.gpo.yoin.ui.memories.memoryLitNoteId
import com.gpo.yoin.ui.memories.memoryPlayHistory
import com.gpo.yoin.ui.memories.rememberMemoriesHomeBehind
import com.gpo.yoin.ui.memories.showcase.MemoriesDiaryDeck
import com.gpo.yoin.ui.memories.showcase.MemoriesDiaryHost
import com.gpo.yoin.ui.memories.showcase.MemoriesShowcase
import com.gpo.yoin.ui.memories.showcase.MemoriesShowcaseFixtures
import com.gpo.yoin.ui.memories.showcase.MemoriesSpreadDeck
import com.gpo.yoin.ui.memories.showcase.MemoryPalette
import com.gpo.yoin.ui.memories.showcase.MemoryPaletteSamples
import com.gpo.yoin.ui.memories.showcase.memoriesGestures
import com.gpo.yoin.ui.memories.showcase.rememberMemoriesDiaryState
import com.gpo.yoin.ui.memories.showcase.rememberMemoriesGestureRouter
import com.gpo.yoin.ui.memories.showcase.rememberMemoryTitleEditor
import com.gpo.yoin.ui.memories.withUserMemoryTitle
import com.gpo.yoin.ui.navigation.back.MemoriesBackLevel
import com.gpo.yoin.ui.navigation.back.MemoriesPredictiveBack
import com.gpo.yoin.ui.navigation.back.memoriesDismissCorners
import com.gpo.yoin.ui.navigation.back.rememberMemoriesDismissRules
import com.gpo.yoin.ui.theme.YoinTheme
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/*
 * Debug-only harness for the Memories showcase (card and diary), with no ViewModel: the five memories of the
 * approved prototype (data.js m1–m4, twostate4's m5) built through the coordinator's own copy pipeline, drawn
 * covers and the prototype's palettes and theme tokens. Launched through MemoryCardScreenshotActivity:
 *
 *   adb shell am start -n com.gpo.yoin/.debug.MemoryCardScreenshotActivity --es mode showcase \
 *       [--ei page 0..4] [--ez diary true] [--ef p 0.5] [--es play 2:125] [--ez long true] \
 *       [--ez dark true] [--ez reduced true] [--ez trace true] [--ez failsave true]
 *
 * The page opens like Memories does (q 1 → 0, so the first card earns its award on open), swipes up / the
 * Home pill / back close it, and it re-opens 0.9s later as a new open (every card earns its award again).
 *  · diary: opens on the diary (p = 1). p: freezes the morph at that p (no spring; a frame for screenshots).
 *  · play track:pos: a simulated playhead on the page's memory (track number : seconds), +1s a second into
 *    the next track, lighting notes by NP's rule; tapping a track or note row restarts it there.
 *  · long: twostate4's long start — m5, the diary open and scrolled 600dp, Thin Ice playing at 2:05.
 *  · failsave: a diary review never saves (the draft is kept, as offline without the album cached).
 * trace: a rolling 4s haptic log at the bottom (the tablet has no vibrator) plus the lifecycle's steps; every
 * line also goes to logcat under "GrooveTrace".
 */

internal data class ShowcaseHarnessOptions(
    val page: Int,
    val dark: Boolean,
    val reduced: Boolean?,
    val trace: Boolean,
    val diary: Boolean = false,
    val frame: Float? = null,
    val play: Pair<Int, Int>? = null,
    val scrollDp: Int = 0,
    val failSave: Boolean = false,
) {
    companion object {
        fun from(intent: Intent): ShowcaseHarnessOptions {
            val long = intent.getBooleanExtra("long", false)
            val play = intent.getStringExtra("play")?.split(':')?.let { parts ->
                val track = parts.getOrNull(0)?.toIntOrNull()
                val at = parts.getOrNull(1)?.toIntOrNull()
                if (track != null && at != null) track to at else null
            }
            return ShowcaseHarnessOptions(
                page = if (long) 4 else intent.getIntExtra("page", 0),
                dark = intent.getBooleanExtra("dark", false),
                reduced = if (intent.hasExtra("reduced")) intent.getBooleanExtra("reduced", false) else null,
                trace = intent.getBooleanExtra("trace", false),
                diary = long || intent.getBooleanExtra("diary", false),
                frame = if (intent.hasExtra("p")) intent.getFloatExtra("p", 0f) else null,
                play = play ?: if (long) 2 to 125 else null,
                scrollDp = if (long) 600 else intent.getIntExtra("scroll", 0),
                failSave = intent.getBooleanExtra("failsave", false),
            )
        }
    }
}

/** The prototype's fixed day (twostate4 `TODAY`). */
private val FixtureToday: LocalDate = LocalDate.of(2026, 10, 4)

@Composable
internal fun MemoriesShowcaseHarness(options: ShowcaseHarnessOptions) {
    val trace = remember { ShowcaseTrace() }
    val fixtures = remember { showcaseFixtures(FixtureToday, ZoneId.systemDefault()) }
    YoinTheme(colorSchemeOverride = prototypeScheme(options.dark), darkTheme = options.dark) {
        CompositionLocalProvider(LocalHapticTrace provides trace) {
            var opens by remember { mutableIntStateOf(0) }
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                key(opens) {
                    HarnessDeck(
                        memories = fixtures.first,
                        fixtures = fixtures.second,
                        // a re-open starts plain: on the card, nothing frozen, at the top
                        options = options.takeIf { opens == 0 }
                            ?: options.copy(diary = false, frame = null, scrollDp = 0),
                        trace = trace,
                        onClosed = { opens++ },
                    )
                }
                if (options.trace) {
                    TraceStrip(
                        trace = trace,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.navigationBars),
                    )
                }
            }
        }
    }
}

@Composable
private fun HarnessDeck(
    memories: List<MemoryEntry>,
    fixtures: MemoriesShowcaseFixtures,
    options: ShowcaseHarnessOptions,
    trace: ShowcaseTrace,
    onClosed: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduced = options.reduced ?: rememberGrooveReducedMotion()
    // the host's reveal q: an open is q 1 → 0, as Home's pull-down settles it (a frozen frame starts open)
    val reveal = rememberRevealState(initialFraction = if (options.frame != null) 0f else 1f)
    val diary = rememberMemoriesDiaryState(
        initialFraction = if (options.diary) 1f else 0f,
        reducedMotion = reduced,
    )
    val awards = rememberMemoriesAwardLifecycle()
    val router = rememberMemoriesGestureRouter(reveal, diary, rememberMemoriesDismissRules())
    val titleEditor = rememberMemoryTitleEditor()
    val pagerState = rememberPagerState(initialPage = options.page.coerceIn(0, memories.lastIndex)) { memories.size }
    var deckMemories by remember { mutableStateOf(memories) }
    val playhead = remember { HarnessPlayhead() }
    val host = remember(playhead) { HarnessDiaryHost(playhead, options.failSave, trace) }
    SideEffect {
        host.onSaved = { saved -> deckMemories = deckMemories.map { if (it.stableId == saved.stableId) saved else it } }
    }
    LaunchedEffect(playhead) { playhead.advance() }
    LaunchedEffect(options.play) {
        val (n, at) = options.play ?: return@LaunchedEffect
        val memory = deckMemories.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
        val track = memory.tracks.firstOrNull { it.number == n } ?: return@LaunchedEffect
        playhead.start(memory, track, at * 1000L)
    }
    // a frozen morph frame: p held where asked (any spring is stopped first)
    LaunchedEffect(options.frame) {
        val frame = options.frame ?: return@LaunchedEffect
        diary.snapTo(frame)
    }
    val diaryLevel by remember(diary) { derivedStateOf { diary.isDiaryLevel } }
    val reopen = {
        scope.launch {
            delay(ReopenDelayMs)
            onClosed()
        }
    }
    val haptics = rememberYoinHaptics()
    SideEffect {
        router.awards = awards
        router.cardPresent = true
        router.onDismissed = { reopen() }
        // CLOCK_TICK as a drag crosses its commit line; the tablet has no vibrator, so the trace shows it
        router.onThresholdCrossed = { what ->
            haptics.performTick()
            trace.lifecycle("CLOCK_TICK · $what")
        }
        awards.debugLog = trace::lifecycle
        router.debugLog = trace::lifecycle
        router.titleEditing = { titleEditor.isEditing }
    }
    LaunchedEffect(reveal) {
        trace.lifecycle("open")
        reveal.animateTo(0f)
    }
    MemoriesPredictiveBack(
        enabled = true,
        level = when {
            titleEditor.isEditing -> MemoriesBackLevel.TitleEdit
            diaryLevel && !router.isSpread -> MemoriesBackLevel.Diary
            else -> MemoriesBackLevel.Card
        },
        reveal = reveal,
        containerHeightPx = { router.heightPx },
        onDismiss = {
            awards.onDismissCommitted()
            scope.launch {
                reveal.animateTo(1f)
                reopen()
            }
        },
        diary = diary,
        onCardBackStarted = router::onBackStarted,
        onCardBackFinished = router::onBackFinished,
        titleEditor = titleEditor,
    )
    // the host's pose, as YoinNavHost's: the page translates by q (reduced motion: fades in place)
    // Home, behind the overlay: the host's pose (0.94 / 0.5 → 1 / 1 as Memories retreats)
    HarnessHome(Modifier.fillMaxSize().then(rememberMemoriesHomeBehind(reveal)))
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                if (reduced) {
                    translationY = 0f
                    alpha = 1f - reveal.fraction
                } else {
                    translationY = -reveal.fraction * size.height
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (reduced) Modifier else Modifier.memoriesDismissCorners(reveal) { router.cornerThresholdPx })
                .background(MaterialTheme.colorScheme.background)
                .onPlaced(router::onRootPlaced)
                .memoriesGestures(router),
        ) {
            MemoriesShowcase(
                memories = deckMemories,
                pagerState = pagerState,
                reveal = reveal,
                diary = diary,
                awards = awards,
                onHome = {
                    awards.onDismissCommitted()
                    scope.launch {
                        reveal.animateTo(1f)
                        reopen()
                    }
                },
                onOpenAlbum = { memory ->
                    Toast.makeText(context, "Opening ${memory.title}", Toast.LENGTH_SHORT).show()
                },
                onOpenDiary = { trace.lifecycle("diary opened") },
                onBarPlaced = router::onBarPlaced,
                today = FixtureToday,
                reducedMotion = reduced,
                fixtures = fixtures,
                diaryHost = host,
                router = router,
                awardBlocked = { router.backBusy },
                titleEditor = titleEditor,
            )
            // the long start: the diary scrolled (once its page has laid out)
            if (options.scrollDp > 0) {
                val density = LocalDensity.current
                LaunchedEffect(Unit) {
                    delay(LongStartDelayMs)
                    trace.lifecycle("scroll ${options.scrollDp}dp")
                    val px = with(density) { options.scrollDp.dp.toPx() }
                    val scroll = when (val probe = router.diaryProbe) {
                        is MemoriesDiaryDeck -> probe.current()?.scroll
                        is MemoriesSpreadDeck -> probe.current()
                        else -> null
                    }
                    scroll?.scrollTo(px.roundToInt())
                }
            }
        }
    }
}

private const val LongStartDelayMs = 450L

/** twostate4 `homeHTML`: Home's head and three tinted blocks, enough to see it come up behind the deck. */
@Composable
private fun HarnessHome(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "Home",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 6.dp, top = 32.dp),
        )
        Text(
            text = "Activities",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 6.dp, top = 24.dp, bottom = 12.dp),
        )
        with(MemoryPaletteSamples) { listOf(M1, M3, M2, M4, M5, M1, M3) }.forEach { p ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .height(140.dp)
                    .background(p.base.copy(alpha = 0.24f), RoundedCornerShape(26.dp)),
            )
        }
    }
}

// ---------------------------------------------------------------- the diary's host (playhead, writing)

/** twostate4's simulated playhead: +1s a second, into the next track by number; notes light by NP's rule. */
private class HarnessPlayhead {
    var memory by mutableStateOf<MemoryEntry?>(null)
    var trackId by mutableStateOf<String?>(null)
    var positionMs by mutableLongStateOf(0L)
    private var number = 0

    fun start(memory: MemoryEntry, track: MemoryTrack, atMs: Long) {
        this.memory = memory
        number = track.number ?: 0
        trackId = track.trackId
        positionMs = atMs
    }

    suspend fun advance() {
        while (true) {
            delay(1_000L)
            val m = memory ?: continue
            val current = m.tracks.firstOrNull { it.number == number } ?: continue
            positionMs += 1_000L
            if (positionMs >= (current.durationSeconds ?: Int.MAX_VALUE) * 1000L) {
                val next = m.tracks.firstOrNull { it.number == number + 1 }
                if (next == null) {
                    trackId = null
                    memory = null
                } else {
                    number = next.number ?: (number + 1)
                    trackId = next.trackId
                    positionMs = 0L
                }
            }
        }
    }
}

private class HarnessDiaryHost(
    private val playhead: HarnessPlayhead,
    private val failSave: Boolean,
    private val trace: ShowcaseTrace,
) : MemoriesDiaryHost {
    var onSaved: (MemoryEntry) -> Unit = {}
    private val drafts = mutableStateMapOf<String, String>()

    override val litNoteId: String?
        get() {
            val m = playhead.memory ?: return null
            val notes = m.diaryTracks.flatMap { it.notes }
            return memoryLitNoteId(notes, playhead.trackId, playhead.positionMs)
        }

    override val playingTrackId: String? get() = playhead.trackId

    override fun reviewDraft(memory: MemoryEntry): String? = drafts[memory.stableId]

    override fun saveReview(memory: MemoryEntry, text: String) {
        if (failSave) {
            drafts[memory.stableId] = text
            trace.lifecycle("review save failed (draft kept)")
            return
        }
        drafts.remove(memory.stableId)
        val now = LocalDate.of(2026, 10, 4).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        onSaved(
            memory.copy(
                review = MemoryWriting(kind = MemoryWriting.Kind.REVIEW, text = text, writtenAt = now),
                hasAlbumReview = true,
            ),
        )
        trace.lifecycle("review saved")
    }

    override val titlesEditable: Boolean get() = true

    // the harness keeps titles in memory: the card is patched as the app's ViewModel patches it
    override fun saveMemoryTitle(memory: MemoryEntry, title: String) {
        onSaved(memory.withUserMemoryTitle(title))
        trace.lifecycle("title saved: $title")
    }

    override fun restoreMemoryTitle(memory: MemoryEntry) {
        onSaved(memory.withUserMemoryTitle(null))
        trace.lifecycle("title restored")
    }

    override fun playTrack(memory: MemoryEntry, track: MemoryTrack) {
        playhead.start(memory, track, 0L)
        trace.lifecycle("play ${track.title} from 0:00")
    }

    override fun playNote(memory: MemoryEntry, track: MemoryTrack, note: MemoryWriting) {
        val at = note.positionMs ?: 0L
        playhead.start(memory, track, at)
        trace.lifecycle("play ${track.title} from ${at / 1000}s")
    }

    override val neoDbConfigured: Boolean get() = true

    override fun neoDbSync(memory: MemoryEntry): Flow<AlbumNeoDbSync> = flowOf(AlbumNeoDbSync.Synced)
}

private const val ReopenDelayMs = 900L

// ---------------------------------------------------------------- trace

private class ShowcaseTrace : GrooveHapticTrace {
    class Entry(val atMs: Long, val text: String, val beat: GrooveBeat?, val route: GrooveHapticRoute?)

    val entries = mutableStateListOf<Entry>()
    var last by mutableStateOf("Haptic trace: no beats yet.")
    var count by mutableIntStateOf(0)

    override fun onRunStart(tag: String, beats: List<GrooveBeat>, route: GrooveHapticRoute) {
        Log.i(TAG, "start $tag route=$route plan=" + beats.joinToString { "${it.primitive}@${it.atMs}×${it.scale}" })
    }

    override fun onBeat(event: GrooveHapticTraceEvent) {
        val b = event.beat
        count++
        val line = "${b.primitive}×${b.scale} · ${event.tag} · ${b.atMs}→${event.firedAtMs}ms · ${event.route}"
        last = "$line · total $count"
        add(Entry(SystemClock.uptimeMillis(), line, b, event.route))
        Log.i(TAG, "beat $line")
    }

    fun lifecycle(step: String) {
        add(Entry(SystemClock.uptimeMillis(), step, null, null))
        last = step
        Log.i(TAG, "lifecycle $step")
    }

    private fun add(entry: Entry) {
        entries.removeAll { entry.atMs - it.atMs > TraceSpanMs }
        entries += entry
    }

    companion object {
        const val TAG = "GrooveTrace"
    }
}

/** The prototype's hxLog: a rolling 4s window, now at the right edge; beats as dots, lifecycle steps as ticks. */
@Composable
private fun TraceStrip(trace: ShowcaseTrace, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(trace) {
        while (true) {
            withFrameNanos { now = SystemClock.uptimeMillis() }
        }
    }
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            val span = TraceSpanMs.toFloat()
            val y = size.height / 2
            drawLine(ink.copy(alpha = 0.2f), Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
            trace.entries.forEach { e ->
                val age = (now - e.atMs).toFloat()
                if (age > span) return@forEach
                val x = size.width * (1f - age / span)
                val fresh = age < 420f
                val color = if (fresh) accent else ink
                if (e.beat != null) {
                    val r = (2.6f + 3.4f * e.beat.scale) * density
                    drawCircle(color, r, Offset(x, y))
                } else {
                    val tick = 1.5f * density
                    drawLine(color.copy(alpha = 0.7f), Offset(x, 2f), Offset(x, size.height - 2f), strokeWidth = tick)
                }
            }
        }
        Text(
            text = trace.last,
            style = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = ink),
            maxLines = 2,
        )
    }
}

private const val TraceSpanMs = 4_000L

// ---------------------------------------------------------------- fixtures (data.js m1–m4 + twostate4 m5)

private class FxTrack(val n: Int, val title: String, val dur: Int, val rating: Float? = null)

private class FxNote(val track: Int?, val at: Int?, val date: String, val text: String)

private class FxMemory(
    val id: String,
    val album: String,
    val artist: String,
    val year: Int,
    val palette: MemoryPalette,
    val coverFrom: Long,
    val coverTo: Long,
    val coverDot: Long,
    val scoreKind: MemoryScoreKind,
    val score: Float?,
    val aiTitle: String?,
    val review: String?,
    val reviewWrittenAt: String?,
    val firstPlayed: String,
    val lastPlayed: String,
    val plays: Int,
    val tracks: List<FxTrack>,
    val notes: List<FxNote>,
)

private val SharedTwelve = listOf(
    "序曲" to 180, "Night Bus" to 197, "未寄出的信" to 214, "Glass Harbour" to 231, "看不见的潮汐" to 248,
    "Lemonade" to 265, "Satellite Hearts" to 282, "雨のち晴れ" to 299, "Afterglow" to 316, "Paper Moon" to 333,
    "Static" to 350, "Coda" to 367,
)

private val FxMemories = listOf(
    FxMemory(
        id = "m1",
        album = "夜行列车与未寄出的信",
        artist = "椎名林檎 & 东京事变",
        year = 2019,
        palette = MemoryPaletteSamples.M1,
        coverFrom = 0xFF1C1A6B,
        coverTo = 0xFF7B4FD8,
        coverDot = 0xFFE2C27A,
        scoreKind = MemoryScoreKind.ALBUM_RATING,
        score = 9.5f,
        aiTitle = "写给自己的、不寄出的信",
        review = "第一次听这张是在去机场的夜班巴士上，窗外的高速路灯一盏一盏往后退，耳机里的弦乐刚好起来。" +
            "后来每次出远门都会把它从头放到尾，像是给自己写一封不打算寄出去的信。第三首的鼓点让我想起高中操场，" +
            "第七首的和声又把我拉回现在。它不是那种一听就爱上的专辑，但它会在你需要的时候一直在那里。" +
            "如果只能留一张专辑陪我坐完一整夜的车，我会选它。",
        reviewWrittenAt = "2026-07-26",
        firstPlayed = "2026-03-14",
        lastPlayed = "2026-10-02",
        plays = 37,
        tracks = SharedTwelve.take(10).mapIndexed { i, (title, dur) ->
            FxTrack(i + 1, title, dur, listOf(9.0f, 8.5f, 10.0f, 7.5f).getOrNull(i))
        },
        notes = listOf(
            FxNote(3, 72, "2026-08-02", "副歌前那一拍停顿，像信写到一半停笔。"),
            FxNote(7, 141, "2026-07-19", "和声把我拉回现在。"),
        ),
    ),
    FxMemory(
        id = "m2",
        album = "Godspeed!",
        artist = "HYUKOH",
        year = 2024,
        palette = MemoryPaletteSamples.M2,
        coverFrom = 0xFF6B4A10,
        coverTo = 0xFFD6DC5A,
        coverDot = 0xFF86B8E0,
        scoreKind = MemoryScoreKind.NONE,
        score = null,
        aiTitle = "雨天里的水底吉他",
        review = null,
        reviewWrittenAt = null,
        firstPlayed = "2026-09-21",
        lastPlayed = "2026-09-30",
        plays = 9,
        tracks = SharedTwelve.mapIndexed { i, (title, dur) -> FxTrack(i + 1, title, dur) },
        notes = listOf(
            FxNote(1, 12, "2026-10-01", "前奏的吉他像在水底。"),
            FxNote(2, 64, "2026-09-30", "副歌那一句一直在脑子里转，走路的时候会不自觉地跟着节奏。"),
            FxNote(5, 140, "2026-09-29", "Bridge 太好了"),
            FxNote(null, null, "2026-09-28", "整张专辑适合下雨天一个人在家听。"),
        ),
    ),
    FxMemory(
        id = "m3",
        album = "The Patterns Lost to the Tide (Expanded Edition)",
        artist = "Marielle V Jakobsons",
        year = 2021,
        palette = MemoryPaletteSamples.M3,
        coverFrom = 0xFF163F7A,
        coverTo = 0xFF4A76E8,
        coverDot = 0xFFE5A07C,
        scoreKind = MemoryScoreKind.AVERAGE_TRACK_RATING,
        score = 7.8f,
        aiTitle = null,
        review = null,
        reviewWrittenAt = null,
        firstPlayed = "2026-01-05",
        lastPlayed = "2026-10-01",
        plays = 22,
        tracks = listOf(
            FxTrack(1, "Tidal", 312, 8.0f),
            FxTrack(2, "Lost Pattern", 268, 7.0f),
            FxTrack(3, "Undertow", 405, 9.0f),
            FxTrack(4, "Salt Light", 220, 6.5f),
            FxTrack(5, "Littoral", 351, 8.5f),
            FxTrack(6, "Fathom", 290, 7.5f),
            FxTrack(7, "Brine (Expanded)", 377),
            FxTrack(8, "Tideline (Live)", 444),
        ),
        notes = listOf(FxNote(3, 201, "2026-05-11", "合成器在这里像退潮。")),
    ),
    FxMemory(
        id = "m4",
        album = "MeMe",
        artist = "衛柏Neon",
        year = 2025,
        palette = MemoryPaletteSamples.M4,
        coverFrom = 0xFF7A1030,
        coverTo = 0xFFE08A5A,
        coverDot = 0xFF7FE0C4,
        scoreKind = MemoryScoreKind.ALBUM_RATING,
        score = 10.0f,
        aiTitle = "一句好听就够了",
        review = "好听。",
        reviewWrittenAt = "2026-09-29",
        firstPlayed = "2026-09-27",
        lastPlayed = "2026-09-29",
        plays = 5,
        tracks = listOf(
            FxTrack(1, "MeMe", 201),
            FxTrack(2, "Afterparty", 188),
            FxTrack(3, "霓虹", 233),
            FxTrack(4, "Static Love", 210),
            FxTrack(5, "Kiss Me Neon", 199),
            FxTrack(6, "Outro", 120),
        ),
        notes = emptyList(),
    ),
    FxMemory(
        id = "m5",
        album = "雪线以北",
        artist = "Alder & Ash",
        year = 2025,
        palette = MemoryPaletteSamples.M5,
        coverFrom = 0xFF1D4F3B,
        coverTo = 0xFF8FC58A,
        coverDot = 0xFFE8B86A,
        scoreKind = MemoryScoreKind.ALBUM_RATING,
        score = 8.5f,
        aiTitle = "一整个冬天的回信",
        review = "去年十一月搬到北边的城市，第一周每天下班都要走二十分钟的夜路。那时候手机里只有这一张，是朋友临走前塞给我的，她说冬天很长，你会需要它。\n" +
            "前两个月我几乎只听前四首。《初雪》开头那段钢琴很慢，慢到我以为播放器卡住了，后来才明白那是故意留出来的呼吸。" +
            "《Thin Ice》的鼓一直压着拍子走，像踩在冰面上不敢用力。那段时间我每天都把音量调得很低，怕吵到隔壁，也怕吵到自己。" +
            "到了一月，我终于能从头听到尾，第七首《Snowline》合唱进来的那一下，我在地铁上没忍住。\n" +
            "春天以后听得少了，偶尔想起来放一遍，发现它和冬天时不一样了。以前觉得它冷，现在觉得它其实一直在等天亮。" +
            "最后一首结尾那段很长的环境声，我以前总是跳过，现在会等它放完，像是陪它把灯关掉。\n" +
            "这不是一张会推荐给所有人的专辑。它太安静，前半张也确实有点闷，第一次听很容易半路关掉。" +
            "但如果你也在一个陌生的地方熬过一个冬天，它会比任何人都懂你。给 8.5，扣掉的那一点留给第十首，它不该那么短。",
        reviewWrittenAt = "2025-12-20",
        firstPlayed = "2025-11-08",
        lastPlayed = "2026-09-20",
        plays = 64,
        tracks = listOf(
            FxTrack(1, "初雪", 245, 8.0f),
            FxTrack(2, "Thin Ice", 212, 8.5f),
            FxTrack(3, "夜路", 198, 7.5f),
            FxTrack(4, "Radiator Hum", 230, 7.0f),
            FxTrack(5, "北窗", 260),
            FxTrack(6, "Breath on Glass", 205, 8.0f),
            FxTrack(7, "Snowline", 318, 9.5f),
            FxTrack(8, "冻土", 240),
            FxTrack(9, "Late Thaw", 276, 9.0f),
            FxTrack(10, "Interlude", 64, 6.5f),
            FxTrack(11, "融雪", 233),
            FxTrack(12, "Morning Train", 402),
        ),
        notes = listOf(
            FxNote(1, 8, "2025-11-09", "钢琴慢到我以为播放器卡住了。"),
            FxNote(1, 102, "2026-01-14", "原来那是留给呼吸的空白。"),
            FxNote(2, 31, "2025-11-21", "鼓压着拍子走，像踩在冰面上。"),
            FxNote(2, 130, "2025-12-03", "这里贝斯终于敢用力了。"),
            FxNote(2, null, "2025-11-15", "整首都像屏着一口气。"),
            FxNote(10, null, "2026-02-10", "太短了，刚进入状态就结束。"),
            FxNote(3, 65, "2025-11-12", "下班的夜路，路灯每隔二十步一盏。"),
            FxNote(6, 47, "2026-02-02", "呵气在玻璃上画了个圈。"),
            FxNote(7, 118, "2026-01-20", "合唱进来那一下，地铁上没忍住。"),
            FxNote(7, 220, "2026-03-08", "第二遍合唱压低了半度，像天快亮了。"),
            FxNote(7, 295, "2026-06-30", "夏天再听，它不冷了。"),
            FxNote(9, 20, "2026-04-02", "解冻是从屋檐开始的。"),
            FxNote(9, 192, "2026-04-19", "弦乐把整张专辑托起来了，这一首可以单独拿出来听很多遍。"),
            FxNote(12, 330, "2026-09-20", "以前总跳过这段环境声，今天等它放完了。"),
            FxNote(null, null, "2026-09-12", "适合一个人走很长的路。"),
        ),
    ),
)

/** The fixtures as the coordinator would build them, plus their palettes and drawn covers. */
private fun showcaseFixtures(today: LocalDate, zone: ZoneId): Pair<List<MemoryEntry>, MemoriesShowcaseFixtures> {
    val entries = FxMemories.map { it.toEntry(today, zone) }
    val byId = FxMemories.associateBy { "fixture:${it.id}" }
    val fixtures = MemoriesShowcaseFixtures(
        palettes = byId.mapValues { (_, fx) -> fx.palette },
        cover = { memory, modifier ->
            val fx = byId.getValue(memory.stableId)
            FixtureCover(fx, modifier)
        },
    )
    return entries to fixtures
}

/** twostate4 `cover()`: a vertical gradient with a centred disc (r = 26%), bare art. */
@Composable
private fun FixtureCover(fx: FxMemory, modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(Color(fx.coverFrom), Color(fx.coverTo))))
        drawCircle(Color(fx.coverDot), radius = size.minDimension * 0.26f)
    }
}

private fun FxMemory.toEntry(today: LocalDate, zone: ZoneId): MemoryEntry {
    fun epoch(iso: String): Long = LocalDate.parse(iso).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    val memoryTracks = tracks.mapIndexed { index, t ->
        MemoryTrack(
            stableId = "fixture:$id:song:${t.n}",
            title = t.title,
            artist = artist,
            durationSeconds = t.dur,
            rating = t.rating,
            number = t.n,
            trackId = "$id-t${t.n}",
            playbackIndex = index,
        )
    }
    val writings = notes.mapIndexed { index, note ->
        if (note.track == null) {
            MemoryWriting(
                kind = MemoryWriting.Kind.ALBUM_NOTE,
                text = note.text,
                writtenAt = epoch(note.date),
                noteId = "$id-n$index",
            )
        } else {
            MemoryWriting(
                kind = MemoryWriting.Kind.SONG_NOTE,
                text = note.text,
                writtenAt = epoch(note.date),
                trackTitle = tracks.first { it.n == note.track }.title,
                positionMs = note.at?.let { it * 1000L },
                trackId = "$id-t${note.track}",
                noteId = "$id-n$index",
            )
        }
    }.sortedByDescending(MemoryWriting::writtenAt)
    val reviewWriting = review?.let { text ->
        MemoryWriting(kind = MemoryWriting.Kind.REVIEW, text = text, writtenAt = epoch(reviewWrittenAt ?: lastPlayed))
    }
    val history = memoryPlayHistory(plays, epoch(firstPlayed), epoch(lastPlayed))
    val rated = tracks.count { it.rating != null }
    val copyInput = memoryCopyInput(
        albumName = album,
        aiTitle = aiTitle,
        review = reviewWriting,
        writings = writings,
        tracks = memoryTracks,
        ratedTracks = rated,
        totalTracks = tracks.size,
        albumScore = score.takeIf { scoreKind == MemoryScoreKind.ALBUM_RATING },
        history = history,
        zone = zone,
    )
    val voice = MemoryVoice.compose(copyInput, today)
    return MemoryEntry(
        stableId = "fixture:$id",
        sourceActivityId = 0L,
        entityType = MemoryEntityType.ALBUM,
        entityId = id,
        entityProvider = "subsonic",
        title = album,
        supportingText = artist,
        supportingYear = year.toString(),
        metaText = null,
        coverArtUrl = null,
        timestamp = epoch(lastPlayed),
        scoreText = score.formatScore(),
        scoreKind = scoreKind,
        scoreSupportingText = scoreKind.label,
        footerText = null,
        hasAlbumReview = reviewWriting != null,
        noteCount = notes.size,
        ratedTrackCount = rated,
        totalTrackCount = tracks.size,
        playCount = plays,
        firstPlayedAt = epoch(firstPlayed),
        lastPlayedAt = epoch(lastPlayed),
        memoryTitle = voice.title,
        review = reviewWriting,
        writings = writings,
        narrativeCopy = voice.narration,
        playbackSongs = emptyList(),
        tracks = memoryTracks,
        playsInYoin = history?.plays,
        firstHeardAt = history?.firstHeardAt,
        lastHeardAt = history?.lastHeardAt,
        memoryTitleKind = voice.titleKind,
        generatedMemoryTitle = voice.title.takeIf { voice.titleKind != MemoryTitleKind.ALBUM },
        generatedMemoryTitleKind = voice.titleKind.takeIf { it != MemoryTitleKind.ALBUM },
        proseLanguage = voice.language,
        yoinNarration = voice.narration,
        yoinQuestion = voice.question,
        excerptCandidates = MemoryExcerpt.candidates(copyInput, capped = false, today = today),
        excerptCandidatesMedium = MemoryExcerpt.candidates(copyInput, capped = true, today = today),
        diaryAlbumNotes = diaryAlbumNotes(writings),
        diaryTracks = diaryTracks(memoryTracks, writings),
    )
}
